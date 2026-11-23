package net.atlasnet.minecraft.server.terrain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** Stores compressed vanilla NBT and an atomically advancing revision per chunk. */
public final class PostgresTerrainStore implements TerrainStore {
    private final DataSource database;

    public PostgresTerrainStore(DataSource database) throws IOException {
        this.database = database;
        try (var connection = database.getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            // Serialize schema creation across workers starting against an empty database.
            statement.execute("SELECT pg_advisory_xact_lock(4122895734302407015)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS atlasnet_minecraft_chunks (
                        world text NOT NULL,
                        dimension text NOT NULL,
                        chunk_x integer NOT NULL,
                        chunk_z integer NOT NULL,
                        revision bigint NOT NULL CHECK (revision > 0),
                        nbt bytea NOT NULL,
                        PRIMARY KEY (world, dimension, chunk_x, chunk_z)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS atlasnet_minecraft_chunk_fences (
                        world text NOT NULL,
                        dimension text NOT NULL,
                        chunk_x integer NOT NULL,
                        chunk_z integer NOT NULL,
                        entity_id bigint NOT NULL,
                        generation bigint NOT NULL CHECK (generation > 0),
                        PRIMARY KEY (world, dimension, chunk_x, chunk_z)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS atlasnet_minecraft_chunk_interactions (
                        world text NOT NULL,
                        dimension text NOT NULL,
                        chunk_x integer NOT NULL,
                        chunk_z integer NOT NULL,
                        interaction_id uuid NOT NULL,
                        revision bigint NOT NULL CHECK (revision > 0),
                        response bytea NOT NULL,
                        PRIMARY KEY (world, dimension, chunk_x, chunk_z, interaction_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS atlasnet_minecraft_pending_interactions (
                        world text NOT NULL,
                        player uuid NOT NULL,
                        dimension text NOT NULL,
                        chunk_x integer NOT NULL,
                        chunk_z integer NOT NULL,
                        interaction_id uuid NOT NULL,
                        PRIMARY KEY (world, player)
                    )
                    """);
            statement.execute("""
                    ALTER TABLE atlasnet_minecraft_pending_interactions
                    ADD COLUMN IF NOT EXISTS source_state bytea
                    """);
            connection.commit();
        } catch (SQLException failure) {
            throw new IOException("Could not initialize PostgreSQL terrain storage", failure);
        }
    }

    @Override
    public Optional<Fence> loadFence(ChunkKey key) throws IOException {
        try (var connection = database.getConnection()) {
            return readFence(connection, key, false);
        } catch (SQLException failure) {
            throw new IOException("Could not read terrain fence for " + key, failure);
        }
    }

    @Override
    public boolean advanceFence(ChunkKey key, Optional<Fence> expected, Fence next) throws IOException {
        if (expected.isPresent()) {
            var previous = expected.get();
            if (previous.equals(next)) return loadFence(key).equals(expected);
            if (previous.entityId() == next.entityId() && previous.generation() >= next.generation()) {
                throw new IllegalArgumentException("Chunk authority generation must advance");
            }
        }
        try (var connection = database.getConnection(); var write = connection.prepareStatement(
                expected.isEmpty() ? """
                    INSERT INTO atlasnet_minecraft_chunk_fences (entity_id, generation, world, dimension, chunk_x, chunk_z)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (world, dimension, chunk_x, chunk_z) DO NOTHING
                    """ : """
                    UPDATE atlasnet_minecraft_chunk_fences SET entity_id = ?, generation = ?
                    WHERE world = ? AND dimension = ? AND chunk_x = ? AND chunk_z = ?
                      AND entity_id = ? AND generation = ?
                    """)) {
            write.setLong(1, next.entityId());
            write.setLong(2, next.generation());
            bindKey(write, 3, key);
            if (expected.isPresent()) {
                write.setLong(7, expected.get().entityId());
                write.setLong(8, expected.get().generation());
            }
            return write.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new IOException("Could not advance terrain fence for " + key, failure);
        }
    }

    private static Optional<Fence> readFence(java.sql.Connection connection, ChunkKey key, boolean lock)
            throws SQLException {
        String query = """
                SELECT entity_id, generation FROM atlasnet_minecraft_chunk_fences
                WHERE world = ? AND dimension = ? AND chunk_x = ? AND chunk_z = ?
                """;
        try (var read = connection.prepareStatement(lock ? query + " FOR SHARE" : query)) {
            bindKey(read, 1, key);
            try (var rows = read.executeQuery()) {
                return rows.next() ? Optional.of(new Fence(rows.getLong(1), rows.getLong(2))) : Optional.empty();
            }
        }
    }

    @Override
    public Optional<Snapshot> load(ChunkKey key) throws IOException {
        try (var connection = database.getConnection(); var query = connection.prepareStatement("""
                SELECT revision, nbt FROM atlasnet_minecraft_chunks
                WHERE world = ? AND dimension = ? AND chunk_x = ? AND chunk_z = ?
                """)) {
            bindKey(query, 1, key);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                var data = NbtIo.readCompressed(new ByteArrayInputStream(rows.getBytes(2)),
                        NbtAccounter.unlimitedHeap());
                return Optional.of(new Snapshot(rows.getLong(1), data));
            }
        } catch (SQLException failure) {
            throw new IOException("Could not load terrain for " + key, failure);
        }
    }

    @Override
    public CommitResult commit(ChunkKey key, Fence fence, long baseRevision, CompoundTag data) throws IOException {
        return commitSnapshot(key, fence, baseRevision, data, null);
    }

    @Override
    public Optional<Receipt> loadInteraction(ChunkKey key, UUID id) throws IOException {
        try (var connection = database.getConnection()) {
            return readInteraction(connection, key, id);
        } catch (SQLException failure) {
            throw new IOException("Could not read terrain interaction " + id + " for " + key, failure);
        }
    }

    @Override
    public Reservation reserveInteraction(UUID player, Reservation requested) throws IOException {
        var key = requested.chunk();
        byte[] encoded = null;
        var state = requested.sourceState();
        if (state != null) {
            var output = new ByteArrayOutputStream();
            NbtIo.writeCompressed(state, output);
            encoded = output.toByteArray();
        }
        try (var connection = database.getConnection(); var write = connection.prepareStatement("""
                INSERT INTO atlasnet_minecraft_pending_interactions
                    (world, player, dimension, chunk_x, chunk_z, interaction_id, source_state)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (world, player) DO UPDATE SET player = EXCLUDED.player
                """)) {
            connection.setAutoCommit(false);
            write.setString(1, key.world());
            write.setObject(2, player);
            write.setString(3, key.dimension().toString());
            write.setInt(4, key.x());
            write.setInt(5, key.z());
            write.setObject(6, requested.interactionId());
            write.setBytes(7, encoded);
            write.executeUpdate();
            var saved = readReservation(connection, key.world(), player)
                    .orElseThrow(() -> new SQLException("Pending interaction reservation disappeared"));
            connection.commit();
            return saved;
        } catch (SQLException failure) {
            throw new IOException("Could not reserve terrain interaction for player " + player, failure);
        }
    }

    @Override
    public Optional<Reservation> loadReservation(String world, UUID player) throws IOException {
        try (var connection = database.getConnection()) {
            return readReservation(connection, world, player);
        } catch (SQLException failure) {
            throw new IOException("Could not load pending terrain interaction for player " + player, failure);
        }
    }

    private static Optional<Reservation> readReservation(java.sql.Connection connection, String world, UUID player)
            throws SQLException, IOException {
        try (var read = connection.prepareStatement("""
                SELECT dimension, chunk_x, chunk_z, interaction_id, source_state
                FROM atlasnet_minecraft_pending_interactions
                WHERE world = ? AND player = ?
                """)) {
            read.setString(1, world);
            read.setObject(2, player);
            try (var rows = read.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                var target = new ChunkKey(world, net.minecraft.resources.ResourceLocation.parse(rows.getString(1)),
                        rows.getInt(2), rows.getInt(3));
                var state = rows.getBytes(5);
                return Optional.of(new Reservation(target, rows.getObject(4, UUID.class),
                        state == null ? null : NbtIo.readCompressed(new ByteArrayInputStream(state),
                                NbtAccounter.create(2L * 1024 * 1024))));
            }
        }
    }

    @Override
    public boolean acknowledgeInteraction(UUID player, Reservation reservation) throws IOException {
        try (var connection = database.getConnection()) {
            return releaseInteraction(connection, player, reservation, false) != ReservationRelease.PENDING;
        } catch (SQLException failure) {
            throw new IOException("Could not acknowledge terrain interaction for player " + player, failure);
        }
    }

    public enum ReservationRelease { RELEASED, PENDING, ABSENT }

    /** Release only the matching reservation after its terminal terrain receipt exists. */
    public static ReservationRelease releaseInteraction(java.sql.Connection connection, UUID player,
                                                         Reservation reservation, boolean withCheckpoint) throws SQLException {
        if (withCheckpoint && connection.getAutoCommit()) {
            throw new SQLException("Player settlement must run inside the checkpoint transaction");
        }
        var key = reservation.chunk();
        try (var statement = connection.prepareStatement("""
                WITH matching AS MATERIALIZED (
                    SELECT world, player, dimension, chunk_x, chunk_z, interaction_id
                    FROM atlasnet_minecraft_pending_interactions
                    WHERE world = ? AND dimension = ? AND chunk_x = ? AND chunk_z = ?
                      AND player = ? AND interaction_id = ?
                    FOR UPDATE
                ), removed AS (
                    DELETE FROM atlasnet_minecraft_pending_interactions AS pending USING matching AS entry
                    WHERE pending.world = entry.world AND pending.player = entry.player
                      AND pending.interaction_id = entry.interaction_id
                      AND (pending.source_state IS NULL OR ?)
                      AND EXISTS (
                          SELECT 1 FROM atlasnet_minecraft_chunk_interactions AS result
                          WHERE result.world = entry.world AND result.dimension = entry.dimension
                            AND result.chunk_x = entry.chunk_x AND result.chunk_z = entry.chunk_z
                            AND result.interaction_id = entry.interaction_id
                      )
                    RETURNING pending.player
                )
                SELECT EXISTS (SELECT 1 FROM removed), EXISTS (SELECT 1 FROM matching)
                """)) {
            bindKey(statement, 1, key);
            statement.setObject(5, player);
            statement.setObject(6, reservation.interactionId());
            statement.setBoolean(7, withCheckpoint);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Missing interaction acknowledgement result");
                if (rows.getBoolean(1)) return ReservationRelease.RELEASED;
                return rows.getBoolean(2) ? ReservationRelease.PENDING : ReservationRelease.ABSENT;
            }
        }
    }

    @Override
    public Optional<Receipt> rejectInteraction(ChunkKey key, Fence fence, UUID id) throws IOException {
        try (var connection = database.getConnection()) {
            connection.setAutoCommit(false);
            if (!readFence(connection, key, true).equals(Optional.of(fence))) {
                connection.rollback();
                return Optional.empty();
            }
            try (var write = connection.prepareStatement("""
                    INSERT INTO atlasnet_minecraft_chunk_interactions
                        (world, dimension, chunk_x, chunk_z, interaction_id, revision, response)
                    SELECT world, dimension, chunk_x, chunk_z, ?, revision, ?
                    FROM atlasnet_minecraft_chunks
                    WHERE world = ? AND dimension = ? AND chunk_x = ? AND chunk_z = ?
                    ON CONFLICT (world, dimension, chunk_x, chunk_z, interaction_id) DO NOTHING
                    """)) {
                write.setObject(1, id);
                write.setBytes(2, new byte[0]);
                bindKey(write, 3, key);
                write.executeUpdate();
            }
            var receipt = readInteraction(connection, key, id);
            connection.commit();
            return receipt;
        } catch (SQLException failure) {
            throw new IOException("Could not reject terrain interaction " + id + " for " + key, failure);
        }
    }

    @Override
    public CommitResult commitInteraction(ChunkKey key, Fence fence, long baseRevision, CompoundTag data,
                                          Interaction interaction) throws IOException {
        return commitSnapshot(key, fence, baseRevision, data, java.util.Objects.requireNonNull(interaction));
    }

    private CommitResult commitSnapshot(ChunkKey key, Fence fence, long baseRevision, CompoundTag data,
                                        Interaction interaction) throws IOException {
        if (baseRevision < 0) throw new IllegalArgumentException("Negative base revision");
        var output = new ByteArrayOutputStream();
        NbtIo.writeCompressed(data, output);
        try (var connection = database.getConnection()) {
            connection.setAutoCommit(false);
            if (!readFence(connection, key, true).equals(Optional.of(fence))) {
                connection.rollback();
                return new StaleAuthority();
            }
            if (interaction != null) {
                var existing = readInteraction(connection, key, interaction.id());
                if (existing.isPresent()) {
                    connection.rollback();
                    return new ReplayedInteraction(existing.get());
                }
            }
            try (var write = connection.prepareStatement(baseRevision == 0 ? """
                    INSERT INTO atlasnet_minecraft_chunks (nbt, world, dimension, chunk_x, chunk_z, revision)
                    VALUES (?, ?, ?, ?, ?, 1)
                    ON CONFLICT (world, dimension, chunk_x, chunk_z) DO NOTHING
                    RETURNING revision
                    """ : """
                    UPDATE atlasnet_minecraft_chunks SET nbt = ?, revision = revision + 1
                    WHERE world = ? AND dimension = ? AND chunk_x = ? AND chunk_z = ? AND revision = ?
                    RETURNING revision
                    """)) {
                write.setBytes(1, output.toByteArray());
                bindKey(write, 2, key);
                if (baseRevision != 0) write.setLong(6, baseRevision);
                long revision;
                try (var rows = write.executeQuery()) {
                    if (!rows.next()) {
                        connection.rollback();
                        return new Conflict();
                    }
                    revision = rows.getLong(1);
                }
                if (interaction != null) {
                    try (var receipt = connection.prepareStatement("""
                            INSERT INTO atlasnet_minecraft_chunk_interactions
                                (world, dimension, chunk_x, chunk_z, interaction_id, revision, response)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """)) {
                        bindKey(receipt, 1, key);
                        receipt.setObject(5, interaction.id());
                        receipt.setLong(6, revision);
                        receipt.setBytes(7, interaction.response());
                        receipt.executeUpdate();
                    }
                }
                connection.commit();
                return new Committed(revision);
            } catch (SQLException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IOException("Could not commit terrain for " + key, failure);
        }
    }

    private static Optional<Receipt> readInteraction(java.sql.Connection connection, ChunkKey key, UUID id)
            throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT revision, response FROM atlasnet_minecraft_chunk_interactions
                WHERE world = ? AND dimension = ? AND chunk_x = ? AND chunk_z = ? AND interaction_id = ?
                """)) {
            bindKey(query, 1, key);
            query.setObject(5, id);
            try (var rows = query.executeQuery()) {
                return rows.next() ? Optional.of(new Receipt(rows.getLong(1), new Interaction(id, rows.getBytes(2))))
                        : Optional.empty();
            }
        }
    }

    private static void bindKey(PreparedStatement query, int first, ChunkKey key) throws SQLException {
        query.setString(first, key.world());
        query.setString(first + 1, key.dimension().toString());
        query.setInt(first + 2, key.x());
        query.setInt(first + 3, key.z());
    }
}
