package net.atlasnet.minecraft.endpoint;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public final class MinecraftTcpCodecTest {
    private static final byte[] SECRET = hex("000102030405060708090a0b0c0d0e0f");

    public static void main(String[] args) throws IOException {
        encryptionMatchesReferenceAcrossReadBoundaries();
        compressionTransitionAndBothPacketForms();
        encryptedCompressionPreservesLongRunningState();
        malformedFramesFailClosed();
        validatesEndpointPorts();
        System.out.println("Minecraft TCP codec and endpoint configuration checks passed");
    }

    private static void validatesEndpointPorts() {
        check(MinecraftTcpEndpoint.configuredPort("{}", 25565) == 25565, "default port");
        check(MinecraftTcpEndpoint.configuredPort("{\"port\":25566}", 25565) == 25566, "configured port");
        check(MinecraftTcpEndpoint.configuredPort("{\"port\":65535}", 25565) == 65535, "maximum port");
        for (String invalid : new String[]{"[]", "{\"port\":0}", "{\"port\":65536}",
                "{\"port\":25566.5}", "{\"port\":\"25566\"}", "{\"port\":null}"}) {
            try {
                MinecraftTcpEndpoint.configuredPort(invalid, 25565);
                throw new AssertionError("Accepted invalid endpoint arguments " + invalid);
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().startsWith("args"), "endpoint configuration error");
            }
        }
    }

    private static void encryptionMatchesReferenceAcrossReadBoundaries() throws IOException {
        var output = new ByteArrayOutputStream();
        var writer = new MinecraftTcpCodec();
        writer.write(hex("000102"), output::writeBytes);
        writer.applyAdmissionMessage(concat(hex("01"), SECRET), output::writeBytes);
        writer.write(hex("000102"), output::writeBytes);
        writer.write(hex("0304"), output::writeBytes);
        // OpenSSL AES-128/CFB8 with key and IV 000102030405060708090a0b0c0d0e0f.
        equal(hex("030001020961565ea3c475"), output.toByteArray());

        var reader = new MinecraftTcpCodec();
        var source = ByteBuffer.wrap(output.toByteArray());
        equal(hex("000102"), reader.read(source));
        reader.applyAdmissionMessage(concat(hex("01"), SECRET), output::writeBytes);
        check(reader.read(ByteBuffer.wrap(new byte[]{source.get()})) == null, "partial encrypted frame");
        equal(hex("000102"), reader.read(source));
        equal(hex("0304"), reader.read(source));
        check(!source.hasRemaining(), "consumed two encrypted frames independently");
    }

    private static void compressionTransitionAndBothPacketForms() throws IOException {
        var writer = new MinecraftTcpCodec();
        var output = new ByteArrayOutputStream();
        // Operation 2, threshold 8, vanilla Login Set Compression (packet id 3, threshold 8).
        writer.applyAdmissionMessage(hex("02080308"), output::writeBytes);
        writer.write(hex("0001"), output::writeBytes);
        byte[] large = new byte[256];
        Arrays.fill(large, (byte) 0x5a);
        writer.write(large, output::writeBytes);

        var source = ByteBuffer.wrap(output.toByteArray());
        var reader = new MinecraftTcpCodec();
        equal(hex("0308"), reader.read(source));
        reader.applyAdmissionMessage(hex("02080308"), ignored -> {});
        equal(hex("0001"), reader.read(source));
        equal(large, reader.read(source));
        check(!source.hasRemaining(), "three frames consumed");

        // Decode the outbound compressed frame with an independent zlib reader.
        var raw = ByteBuffer.wrap(output.toByteArray(), 7, output.size() - 7);
        int frameSize = raw.get() & 0xff;
        check(frameSize == raw.remaining(), "expected single-byte compressed frame length");
        check((raw.get() & 0xff) == 0x80 && raw.get() == 2, "declared raw length is 256");
        var inflater = new Inflater();
        try {
            inflater.setInput(raw);
            var decoded = new byte[256];
            check(inflater.inflate(decoded) == 256 && inflater.finished(), "valid zlib stream");
            equal(large, decoded);
        } catch (java.util.zip.DataFormatException error) {
            throw new AssertionError(error);
        } finally {
            inflater.end();
        }
    }

    private static void encryptedCompressionPreservesLongRunningState() throws IOException {
        var writer = new MinecraftTcpCodec();
        var reader = new MinecraftTcpCodec();
        var bytes = new ByteArrayOutputStream();
        byte[] encryption = concat(hex("01"), SECRET);
        writer.applyAdmissionMessage(encryption, bytes::writeBytes);
        reader.applyAdmissionMessage(encryption, ignored -> {});
        writer.applyAdmissionMessage(hex("02200320"), bytes::writeBytes);
        equal(hex("0320"), reader.read(ByteBuffer.wrap(bytes.toByteArray())));
        reader.applyAdmissionMessage(hex("02200320"), ignored -> {});
        var random = new Random(767);
        for (int i = 0; i < 512; i++) {
            bytes.reset();
            byte[] packet = new byte[1 + random.nextInt(4000)];
            random.nextBytes(packet);
            writer.write(packet, bytes::writeBytes);
            var encrypted = ByteBuffer.wrap(bytes.toByteArray());
            while (encrypted.remaining() > 7) {
                var part = encrypted.slice(encrypted.position(), 7);
                encrypted.position(encrypted.position() + 7);
                check(reader.read(part) == null, "packet completed early");
            }
            equal(packet, reader.read(encrypted));
        }
    }

    private static void malformedFramesFailClosed() throws IOException {
        reject(() -> new MinecraftTcpCodec().read(ByteBuffer.wrap(hex("80808000"))));
        reject(() -> new MinecraftTcpCodec().read(ByteBuffer.wrap(hex("00"))));
        var reader = new MinecraftTcpCodec();
        reader.applyAdmissionMessage(concat(hex("01"), SECRET), ignored -> {});
        // These ciphertext bytes decrypt to an invalid VarInt21 length.
        reject(() -> reader.read(ByteBuffer.wrap(hex("8ae7d9"))));
        reject(() -> reader.write(hex("01"), ignored -> {}));
        reject(() -> new MinecraftTcpCodec().applyAdmissionMessage(hex("0100"), ignored -> {}));
        reject(() -> compressedReader().read(ByteBuffer.wrap(hex("058180800400"))));
        reject(() -> compressedReader().read(ByteBuffer.wrap(hex("0408deadbe"))));
        reject(() -> compressedReader().read(ByteBuffer.wrap(zlibFrame(new byte[12], 8, false))));
        reject(() -> compressedReader().read(ByteBuffer.wrap(zlibFrame(new byte[12], 12, true))));
        equal(new byte[12], compressedReader().read(ByteBuffer.wrap(zlibFrame(new byte[12], 12, false))));
    }

    private static MinecraftTcpCodec compressedReader() throws IOException {
        var codec = new MinecraftTcpCodec();
        codec.applyAdmissionMessage(hex("02080308"), ignored -> {});
        return codec;
    }

    private static byte[] zlibFrame(byte[] payload, int reportedSize, boolean appendGarbage) {
        var zlib = new Deflater();
        try {
            var compressed = new byte[64];
            zlib.setInput(payload);
            zlib.finish();
            int length = zlib.deflate(compressed);
            var frame = new byte[2 + length + (appendGarbage ? 1 : 0)];
            frame[0] = (byte) (frame.length - 1);
            frame[1] = (byte) reportedSize;
            System.arraycopy(compressed, 0, frame, 2, length);
            return frame;
        } finally {
            zlib.end();
        }
    }

    @FunctionalInterface
    private interface Checked {
        void run() throws IOException;
    }

    private static void reject(Checked op) throws IOException {
        try {
            op.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Expected invalid data to be rejected");
    }

    private static byte[] hex(String bytes) {
        return HexFormat.of().parseHex(bytes);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        var output = new ByteArrayOutputStream();
        output.writeBytes(a);
        output.writeBytes(b);
        return output.toByteArray();
    }

    private static void equal(byte[] expected, byte[] actual) {
        check(Arrays.equals(expected, actual), "Packet bytes differ");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
