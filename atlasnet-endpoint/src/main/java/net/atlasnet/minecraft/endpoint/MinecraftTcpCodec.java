package net.atlasnet.minecraft.endpoint;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Framing for one Minecraft 1.21.1 TCP connection. Confine it to the connection's event loop. */
public final class MinecraftTcpCodec {
    private static final int MAX_FRAME = (1 << 21) - 1;
    private static final int MAX_PACKET = 8 * 1024 * 1024;

    private Cipher inbound;
    private Cipher outbound;
    private int compressionThreshold = -1;
    private int length;
    private int lengthBytes;
    private byte[] frame;
    private int frameOffset;
    private boolean failed;

    /** The writer must write the entire frame or throw and close the transport. */
    @FunctionalInterface
    public interface FrameWriter {
        void write(byte[] frame) throws IOException;
    }

    /**
     * Reads at most one packet, leaving later bytes in `source` so the caller can
     * apply admission transitions before decoding another packet.
     */
    public byte[] read(ByteBuffer source) throws IOException {
        checkOpen();
        try {
            while (frame == null && source.hasRemaining()) {
                int next = readBytes(source, 1)[0] & 0xff;
                length |= (next & 0x7f) << (7 * lengthBytes++);
                if ((next & 0x80) == 0) {
                    if (length == 0) {
                        throw new IOException("Empty Minecraft frame");
                    }
                    frame = new byte[length];
                } else if (lengthBytes == 3) {
                    throw new IOException("Minecraft frame length exceeds VarInt21");
                }
            }
            if (frame == null) {
                return null;
            }
            int count = Math.min(source.remaining(), frame.length - frameOffset);
            if (count != 0) {
                byte[] part = readBytes(source, count);
                System.arraycopy(part, 0, frame, frameOffset, count);
                frameOffset += count;
            }
            if (frameOffset != frame.length) {
                return null;
            }
            byte[] packet = compressionThreshold < 0 ? frame : decompress(frame);
            frame = null;
            frameOffset = 0;
            length = 0;
            lengthBytes = 0;
            return packet;
        } catch (IOException | RuntimeException error) {
            failed = true;
            throw error;
        }
    }

    /** Writes a packet body, including its packet ID, using the current transport state. */
    public void write(byte[] packet, FrameWriter writer) throws IOException {
        checkOpen();
        try {
            if (packet.length == 0 || packet.length > MAX_PACKET) {
                throw new IOException("Invalid Minecraft packet length");
            }
            byte[] body = compressionThreshold < 0 ? packet : compress(packet);
            if (body.length > MAX_FRAME) {
                throw new IOException("Minecraft frame exceeds VarInt21");
            }
            var bytes = new ByteArrayOutputStream(body.length + 3);
            writeVarInt(bytes, body.length);
            bytes.writeBytes(body);
            byte[] framed = bytes.toByteArray();
            writer.write(outbound == null ? framed : outbound.update(framed));
        } catch (IOException | RuntimeException error) {
            failed = true;
            throw error;
        }
    }

    /** Applies one ordered operation from the Minecraft interactive admission handler. */
    public void applyAdmissionMessage(byte[] message, FrameWriter writer) throws IOException {
        checkOpen();
        try {
            if (message.length == 0) {
                throw new IOException("Empty Minecraft admission operation");
            }
            var payload = ByteBuffer.wrap(message, 1, message.length - 1);
            switch (message[0]) {
                case 0 -> write(remainingBytes(payload), writer);
                case 1 -> enableEncryption(remainingBytes(payload));
                case 2 -> {
                    int threshold = readVarInt(payload);
                    if (threshold < 0 || compressionThreshold >= 0) {
                        throw new IOException("Invalid Minecraft compression transition");
                    }
                    checkBoundary();
                    write(remainingBytes(payload), writer);
                    compressionThreshold = threshold;
                }
                default -> throw new IOException("Unknown Minecraft admission operation");
            }
        } catch (IOException | RuntimeException error) {
            failed = true;
            throw error;
        }
    }

    private void checkOpen() throws IOException {
        if (failed) {
            throw new IOException("Minecraft transport has failed");
        }
    }

    private void checkBoundary() throws IOException {
        if (lengthBytes != 0) {
            throw new IOException("Transport transition inside a Minecraft frame");
        }
    }

    private void enableEncryption(byte[] secret) throws IOException {
        checkBoundary();
        if (secret.length != 16 || inbound != null) {
            throw new IOException("Invalid Minecraft encryption transition");
        }
        try {
            var key = new SecretKeySpec(secret, "AES");
            var iv = new IvParameterSpec(secret);
            Cipher decrypt = Cipher.getInstance("AES/CFB8/NoPadding");
            Cipher encrypt = Cipher.getInstance("AES/CFB8/NoPadding");
            decrypt.init(Cipher.DECRYPT_MODE, key, iv);
            encrypt.init(Cipher.ENCRYPT_MODE, key, iv);
            inbound = decrypt;
            outbound = encrypt;
        } catch (GeneralSecurityException error) {
            throw new IOException("Could not initialize Minecraft encryption", error);
        }
    }

    private byte[] readBytes(ByteBuffer source, int count) {
        var bytes = new byte[count];
        source.get(bytes);
        return inbound == null ? bytes : inbound.update(bytes);
    }

    private byte[] compress(byte[] packet) throws IOException {
        var body = new ByteArrayOutputStream();
        if (packet.length < compressionThreshold) {
            body.write(0);
            body.writeBytes(packet);
            return body.toByteArray();
        }
        writeVarInt(body, packet.length);
        var deflater = new Deflater();
        try {
            deflater.setInput(packet);
            deflater.finish();
            var block = new byte[8192];
            while (!deflater.finished()) {
                int count = deflater.deflate(block);
                if (body.size() + count > MAX_FRAME) {
                    throw new IOException("Compressed Minecraft frame exceeds VarInt21");
                }
                body.write(block, 0, count);
            }
            return body.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private byte[] decompress(byte[] bytes) throws IOException {
        var body = ByteBuffer.wrap(bytes);
        int size = readVarInt(body);
        if (size == 0) {
            if (!body.hasRemaining()) {
                throw new IOException("Empty uncompressed Minecraft packet");
            }
            return remainingBytes(body);
        }
        if (size < 0 || size < compressionThreshold || size > MAX_PACKET || !body.hasRemaining()) {
            throw new IOException("Invalid uncompressed Minecraft packet length");
        }
        var inflater = new Inflater();
        try {
            inflater.setInput(body);
            var packet = new byte[size];
            int offset = 0;
            while (offset < packet.length && !inflater.finished()) {
                int count = inflater.inflate(packet, offset, packet.length - offset);
                if (count == 0) {
                    break;
                }
                offset += count;
            }
            if (offset != size || !inflater.finished() || inflater.getRemaining() != 0) {
                throw new IOException("Minecraft compressed data does not match its declared size");
            }
            return packet;
        } catch (DataFormatException error) {
            throw new IOException("Malformed Minecraft zlib stream", error);
        } finally {
            inflater.end();
        }
    }

    private static int readVarInt(ByteBuffer bytes) throws IOException {
        int value = 0;
        for (int shift = 0; shift <= 28 && bytes.hasRemaining(); shift += 7) {
            int next = bytes.get() & 0xff;
            if (shift == 28 && (next & 0xf0) != 0) {
                break;
            }
            value |= (next & 0x7f) << shift;
            if ((next & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("Invalid Minecraft VarInt");
    }

    private static void writeVarInt(ByteArrayOutputStream target, int value) {
        while ((value & ~0x7f) != 0) {
            target.write((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        target.write(value);
    }

    private static byte[] remainingBytes(ByteBuffer buffer) {
        var bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
}
