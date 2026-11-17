package net.atlasnet.minecraft.fabric.server.storage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

public final class ValkeyChunkStorage implements ChunkStorage {
    private static final int MAX_COMPRESSED = 16 * 1024 * 1024;
    private static final long MAX_NBT = 64L * 1024 * 1024;
    private static final int CONNECT_TIMEOUT = 1_000;
    private static final int READ_TIMEOUT = 3_000;

    private static final String CLAIM = """
            local epoch = redis.call('HGET', KEYS[1], 'epoch')
            if epoch and epoch > ARGV[1] then return 0 end
            if epoch == ARGV[1] then
                if redis.call('HGET', KEYS[1], 'owner') == ARGV[2] then return 1 end
                return 0
            end
            if redis.call('HEXISTS', KEYS[1], 'prepared_handoff') == 1
                or redis.call('HEXISTS', KEYS[1], 'resident_session') == 1 then return 0 end
            redis.call('HSET', KEYS[1], 'epoch', ARGV[1], 'owner', ARGV[2])
            redis.call('HDEL', KEYS[1], 'handoff')
            return 1
            """;

    private static final String CHECK_AUTHORITY = """
            if redis.call('HGET', KEYS[1], 'epoch') ~= ARGV[1]
                or redis.call('HGET', KEYS[1], 'owner') ~= ARGV[2] then
                return 0
            end
            """;

    private static final String CLAIM_RESIDENT = """
            local session = redis.call('HGET', KEYS[1], 'resident_session')
            local epoch = redis.call('HGET', KEYS[1], 'epoch')
            local owner = redis.call('HGET', KEYS[1], 'owner')
            if session == ARGV[3] then
                if epoch == ARGV[1] and owner == ARGV[2] then return 1 end
                return 0
            end
            if epoch and epoch >= ARGV[1] then return 0 end
            redis.call('HSET', KEYS[1], 'epoch', ARGV[1], 'owner', ARGV[2], 'resident_session', ARGV[3])
            redis.call('HDEL', KEYS[1], 'handoff', 'prepared_handoff')
            return 1
            """;

    private static final String RELEASE_RESIDENT = CHECK_AUTHORITY + """
            if redis.call('HGET', KEYS[1], 'resident_session') ~= ARGV[3]
                or redis.call('HEXISTS', KEYS[1], 'prepared_handoff') == 1 then return 0 end
            redis.call('HDEL', KEYS[1], 'resident_session')
            return 1
            """;

    private static final String LOAD = CHECK_AUTHORITY + """
            local data = redis.call('HGET', KEYS[1], 'nbt')
            if not data then return {} end
            return {redis.call('HGET', KEYS[1], 'version'), data}
            """;
    private static final String LOAD_REPLICA = """
            local data = redis.call('HGET', KEYS[1], 'nbt')
            if not data then return {} end
            return {redis.call('HGET', KEYS[1], 'version'), data}
            """;

    private static final String VERSION = CHECK_AUTHORITY + """
            return redis.call('HGET', KEYS[1], 'version') or '0'
            """;

    private static final String SAVE = CHECK_AUTHORITY + """
            if redis.call('HEXISTS', KEYS[1], 'prepared_handoff') == 1 then return 0 end
            local previous = redis.call('HGET', KEYS[1], 'version') or '0'
            if previous ~= ARGV[3] then return 0 end
            redis.call('HSET', KEYS[1], 'version', ARGV[4], 'nbt', ARGV[5])
            return 1
            """;

    private static final String PREPARE_HANDOFF = CHECK_AUTHORITY + """
            local prepared = redis.call('HGET', KEYS[1], 'prepared_handoff')
            if prepared then
                if prepared == ARGV[6] then return 1 end
                return 0
            end
            local previous = redis.call('HGET', KEYS[1], 'version') or '0'
            if previous ~= ARGV[3] then return 0 end
            redis.call('HSET', KEYS[1], 'version', ARGV[4], 'nbt', ARGV[5], 'prepared_handoff', ARGV[6])
            return 1
            """;

    private static final String ABORT_HANDOFF = CHECK_AUTHORITY + """
            local prepared = redis.call('HGET', KEYS[1], 'prepared_handoff')
            if not prepared then return 1 end
            if prepared ~= ARGV[3] then return 0 end
            redis.call('HDEL', KEYS[1], 'prepared_handoff')
            return 1
            """;

    private static final String LOAD_PREPARED_HANDOFF = CHECK_AUTHORITY + """
            if redis.call('HGET', KEYS[1], 'prepared_handoff') ~= ARGV[3] then return {} end
            return {redis.call('HGET', KEYS[1], 'version'), redis.call('HGET', KEYS[1], 'nbt')}
            """;

    private static final String COMMIT_HANDOFF = """
            local epoch = redis.call('HGET', KEYS[1], 'epoch')
            local owner = redis.call('HGET', KEYS[1], 'owner')
            if epoch == ARGV[3] and owner == ARGV[4] then
                if redis.call('HGET', KEYS[1], 'handoff') == ARGV[6] then return 1 end
                return 0
            end
            if epoch ~= ARGV[1] or owner ~= ARGV[2]
                or redis.call('HGET', KEYS[1], 'version') ~= ARGV[5]
                or redis.call('HGET', KEYS[1], 'prepared_handoff') ~= ARGV[6] then
                return 0
            end
            redis.call('HSET', KEYS[1], 'epoch', ARGV[3], 'owner', ARGV[4], 'handoff', ARGV[6])
            redis.call('HDEL', KEYS[1], 'prepared_handoff')
            return 1
            """;

    private final InetSocketAddress address;

    public ValkeyChunkStorage(InetSocketAddress address) {
        this.address = address;
    }

    long nextAuthorityEpoch() throws IOException {
        Object reply = execute(text("INCR"), text("minecraft:v1:authority-epoch"));
        if (reply instanceof Long epoch && epoch > 0) {
            return epoch;
        }
        throw new IOException("Invalid Minecraft authority epoch from Valkey");
    }

    @Override
    public boolean claim(ChunkKey key, Authority authority) throws IOException {
        return Long.valueOf(1).equals(eval(CLAIM, key, authority));
    }

    boolean claimResident(ChunkKey key, Authority authority, UUID session) throws IOException {
        return Long.valueOf(1).equals(eval(CLAIM_RESIDENT, key, authority, text(session.toString())));
    }

    boolean releaseResident(ChunkKey key, Authority authority, UUID session) throws IOException {
        return Long.valueOf(1).equals(eval(RELEASE_RESIDENT, key, authority, text(session.toString())));
    }

    @Override
    public Optional<StoredChunk> load(ChunkKey key, Authority authority) throws IOException {
        Object reply = eval(LOAD, key, authority);
        if (Long.valueOf(0).equals(reply)) {
            throw new IOException("Chunk authority changed for " + key);
        }
        return decodeChunk(key, reply);
    }

    Optional<StoredChunk> loadReplica(ChunkKey key) throws IOException {
        return decodeChunk(key, execute(text("EVAL"), text(LOAD_REPLICA), text("1"), text(key.storageKey())));
    }

    private static Optional<StoredChunk> decodeChunk(ChunkKey key, Object reply) throws IOException {
        if (reply instanceof List<?> values) {
            if (values.isEmpty()) {
                return Optional.empty();
            }
            if (values.size() == 2 && values.get(0) instanceof byte[] version
                    && values.get(1) instanceof byte[] bytes) {
                try {
                    long value = Long.parseLong(new String(version, StandardCharsets.US_ASCII));
                    if (value > 0) {
                        var data = NbtIo.readCompressed(new ByteArrayInputStream(bytes),
                                NbtAccounter.create(MAX_NBT));
                        checkCoordinates(key, data);
                        return Optional.of(new StoredChunk(value, data));
                    }
                } catch (NumberFormatException invalid) {
                    throw new IOException("Invalid chunk version for " + key, invalid);
                }
            }
        }
        throw new IOException("Invalid Valkey chunk record for " + key);
    }

    long version(ChunkKey key, Authority authority) throws IOException {
        Object reply = eval(VERSION, key, authority);
        if (reply instanceof byte[] version) {
            try {
                return Long.parseLong(new String(version, StandardCharsets.US_ASCII));
            } catch (NumberFormatException invalid) {
                throw new IOException("Invalid chunk version for " + key, invalid);
            }
        }
        throw new IOException("Chunk authority changed for " + key);
    }

    @Override
    public boolean save(ChunkKey key, Authority authority, long expectedVersion, CompoundTag data)
            throws IOException {
        return save(SAVE, key, authority, expectedVersion, data, null);
    }

    @Override
    public boolean prepareHandoff(ChunkKey key, UUID transferId, Authority source,
                                  long expectedVersion, CompoundTag data) throws IOException {
        return save(PREPARE_HANDOFF, key, source, expectedVersion, data, Objects.requireNonNull(transferId));
    }

    private boolean save(String script, ChunkKey key, Authority authority, long expectedVersion,
                         CompoundTag data, UUID transferId) throws IOException {
        if (expectedVersion < 0 || expectedVersion == Long.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid expected chunk version");
        }
        checkCoordinates(key, data);
        var compressed = new ByteArrayOutputStream();
        NbtIo.writeCompressed(data, compressed);
        if (compressed.size() > MAX_COMPRESSED) {
            throw new IOException("Chunk exceeds compressed NBT limit for " + key);
        }
        long nextVersion = expectedVersion + 1;
        byte[] previous = text(Long.toString(expectedVersion));
        byte[] next = text(Long.toString(nextVersion));
        byte[] nbt = compressed.toByteArray();
        Object response = transferId == null
                ? eval(script, key, authority, previous, next, nbt)
                : eval(script, key, authority, previous, next, nbt,
                        handoffToken(transferId, authority, nextVersion));
        return Long.valueOf(1).equals(response);
    }

    @Override
    public boolean abortHandoff(ChunkKey key, UUID transferId, Authority source, long savedVersion)
            throws IOException {
        return Long.valueOf(1).equals(eval(ABORT_HANDOFF, key, source, handoffToken(transferId, source, savedVersion)));
    }

    @Override
    public Optional<StoredChunk> loadPreparedHandoff(ChunkKey key, UUID transferId, Authority source,
                                                    long savedVersion) throws IOException {
        Object reply = eval(LOAD_PREPARED_HANDOFF, key, source, handoffToken(transferId, source, savedVersion));
        if (Long.valueOf(0).equals(reply)) {
            return Optional.empty();
        }
        Optional<StoredChunk> checkpoint = decodeChunk(key, reply);
        if (checkpoint.isPresent() && checkpoint.get().version() != savedVersion) {
            throw new IOException("Prepared chunk revision changed for " + key);
        }
        return checkpoint;
    }

    @Override
    public boolean commitHandoff(ChunkKey key, UUID transferId, Authority source,
                                 Authority destination, long savedVersion) throws IOException {
        if (savedVersion <= 0 || destination.epoch() <= source.epoch()) {
            throw new IllegalArgumentException("Chunk handoff requires a saved revision and a newer epoch");
        }
        return Long.valueOf(1).equals(eval(COMMIT_HANDOFF, key, source,
                epochBytes(destination), text(destination.owner().toString()),
                text(Long.toString(savedVersion)), handoffToken(transferId, source, savedVersion)));
    }

    private static byte[] handoffToken(UUID transferId, Authority source, long savedVersion) {
        return text(transferId.toString() + "/" + source.epoch() + "/" + source.owner() + "/" + savedVersion);
    }

    private static void checkCoordinates(ChunkKey key, CompoundTag data) throws IOException {
        if (!data.contains("xPos", Tag.TAG_INT) || !data.contains("zPos", Tag.TAG_INT)
                || data.getInt("xPos") != key.x() || data.getInt("zPos") != key.z()) {
            throw new IOException("Chunk NBT coordinates do not match " + key);
        }
    }

    private Object eval(String script, ChunkKey key, Authority authority, byte[]... arguments)
            throws IOException {
        var request = new ArrayList<byte[]>(6 + arguments.length);
        request.add(text("EVAL"));
        request.add(text(script));
        request.add(text("1"));
        request.add(text(key.storageKey()));
        // Fixed-width hexadecimal comparisons preserve all bits in Valkey Lua.
        request.add(epochBytes(authority));
        request.add(text(authority.owner().toString()));
        request.addAll(List.of(arguments));
        return execute(request.toArray(byte[][]::new));
    }

    private static byte[] epochBytes(Authority authority) {
        return text(HexFormat.of().toHexDigits(authority.epoch()));
    }

    private Object execute(byte[]... arguments) throws IOException {
        try (var socket = new Socket()) {
            socket.connect(address, CONNECT_TIMEOUT);
            socket.setSoTimeout(READ_TIMEOUT);
            var out = new BufferedOutputStream(socket.getOutputStream());
            out.write(text("*" + arguments.length + "\r\n"));
            for (byte[] argument : arguments) {
                writeBulk(out, argument);
            }
            out.flush();
            return readReply(new BufferedInputStream(socket.getInputStream()), true);
        }
    }

    private static void writeBulk(BufferedOutputStream out, byte[] data) throws IOException {
        out.write(text("$" + data.length + "\r\n"));
        out.write(data);
        out.write(text("\r\n"));
    }

    private static Object readReply(InputStream in, boolean allowArray) throws IOException {
        int type = in.read();
        String header = readHeader(in);
        try {
            switch (type) {
                case ':' -> { return Long.parseLong(header); }
                case '-' -> throw new IOException("Valkey error " + header);
                case '$' -> {
                    int length = Integer.parseInt(header);
                    if (length < 0 || length > MAX_COMPRESSED) {
                        throw new IOException("Invalid Valkey bulk response length");
                    }
                    byte[] bytes = in.readNBytes(length);
                    if (bytes.length != length || in.read() != '\r' || in.read() != '\n') {
                        throw new EOFException("Truncated Valkey bulk response");
                    }
                    return bytes;
                }
                case '*' -> {
                    int count = Integer.parseInt(header);
                    if (!allowArray || count < 0 || count > 2) {
                        throw new IOException("Invalid Valkey array response length");
                    }
                    var values = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        values.add(readReply(in, false));
                    }
                    return values;
                }
                default -> throw new IOException("Unexpected Valkey response type");
            }
        } catch (NumberFormatException invalid) {
            throw new IOException("Invalid numeric response from Valkey", invalid);
        }
    }

    private static String readHeader(InputStream in) throws IOException {
        var header = new StringBuilder();
        while (header.length() < 4096) {
            int ch = in.read();
            if (ch == '\r' && in.read() == '\n') {
                return header.toString();
            }
            if (ch < 0 || ch == '\r' || ch == '\n') {
                throw new EOFException("Truncated Valkey response header");
            }
            header.append((char) ch);
        }
        throw new IOException("Valkey response header exceeds limit");
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
