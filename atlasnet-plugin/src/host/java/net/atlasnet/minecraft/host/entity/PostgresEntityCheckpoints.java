package net.atlasnet.minecraft.host.entity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import net.atlasnet.minecraft.server.terrain.PostgresTerrainStore;
import net.atlasnet.minecraft.server.terrain.TerrainStore;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;

/** Durable entity checkpoints keyed by the logical world and vanilla UUID. */
public final class PostgresEntityCheckpoints {
    private final DataSource database;
    private final String world;

    public PostgresEntityCheckpoints(DataSource database, String world) throws SQLException {
        this.database = database;
        this.world = world;
        try (var connection = database.getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SELECT pg_advisory_xact_lock(4122895734302407016)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS atlasnet_minecraft_entities (
                        world text NOT NULL,
                        minecraft_uuid uuid NOT NULL,
                        entity_id bigint NOT NULL,
                        entity_type text NOT NULL,
                        dimension text NOT NULL,
                        ownership_generation bigint NOT NULL CHECK (ownership_generation > 0),
                        revision bigint NOT NULL CHECK (revision > 0),
                        entity_nbt bytea,
                        PRIMARY KEY (world, minecraft_uuid)
                    )
                    """);
            statement.execute("""
                    ALTER TABLE atlasnet_minecraft_entities
                    ADD COLUMN IF NOT EXISTS deleted boolean NOT NULL DEFAULT false
                    """);
            statement.execute("""
                    ALTER TABLE atlasnet_minecraft_entities
                    ADD COLUMN IF NOT EXISTS runtime_generation bigint NOT NULL DEFAULT 1,
                    ADD COLUMN IF NOT EXISTS chunk_x integer,
                    ADD COLUMN IF NOT EXISTS chunk_z integer
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS atlasnet_minecraft_entity_chunk
                    ON atlasnet_minecraft_entities (world, dimension, chunk_x, chunk_z)
                    WHERE NOT deleted AND entity_type <> 'minecraft:player' AND entity_nbt IS NOT NULL
                    """);
            connection.commit();
        }
    }

    /**
     * Claims a durable checkpoint generation for an already-authorized
     * runtime entity session. Retains any previous data until the first write.
     */
    public long claim(AtlasNetEntityStore.Snapshot snapshot) throws SQLException {
        try (var connection = database.getConnection(); var update = connection.prepareStatement("""
                INSERT INTO atlasnet_minecraft_entities AS current
                    (world, minecraft_uuid, entity_id, entity_type, dimension,
                     ownership_generation, revision, entity_nbt, runtime_generation)
                VALUES (?, ?, ?, ?, ?, 1, 1, NULL, ?)
                ON CONFLICT (world, minecraft_uuid) DO UPDATE SET
                    entity_id = EXCLUDED.entity_id,
                    ownership_generation = CASE WHEN current.entity_id = EXCLUDED.entity_id
                        AND current.runtime_generation = EXCLUDED.runtime_generation
                        THEN current.ownership_generation ELSE current.ownership_generation + 1 END,
                    revision = CASE WHEN current.entity_id = EXCLUDED.entity_id
                        AND current.runtime_generation = EXCLUDED.runtime_generation
                        THEN current.revision ELSE 1 END,
                    runtime_generation = EXCLUDED.runtime_generation
                WHERE current.entity_id <> EXCLUDED.entity_id
                   OR current.runtime_generation <= EXCLUDED.runtime_generation
                RETURNING ownership_generation
                """)) {
            update.setString(1, world);
            update.setObject(2, snapshot.minecraftId());
            update.setLong(3, snapshot.entityId());
            update.setString(4, snapshot.entityType());
            update.setString(5, snapshot.dimension());
            update.setLong(6, snapshot.ownershipGeneration());
            try (var rows = update.executeQuery()) {
                if (!rows.next()) throw new SQLException("Checkpoint claim was superseded by a newer runtime owner");
                return rows.getLong(1);
            }
        }
    }

    /**
     * Writes a checkpoint or a deletion marker. A delayed write at or below
     * the marker's generation and revision cannot resurrect the entity.
     * Run on an I/O executor.
     */
    public boolean checkpoint(AtlasNetEntityStore.Snapshot snapshot, boolean deleted)
            throws SQLException, IOException {
        byte[] payload = null;
        var data = snapshot.data();
        if (!deleted) {
            try (var output = new ByteArrayOutputStream()) {
                NbtIo.writeCompressed(data, output);
                payload = output.toByteArray();
            }
        }
        try (var connection = database.getConnection(); var write = connection.prepareStatement("""
                INSERT INTO atlasnet_minecraft_entities AS current
                    (world, minecraft_uuid, entity_id, entity_type, dimension,
                     ownership_generation, revision, entity_nbt, deleted, chunk_x, chunk_z, runtime_generation)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (world, minecraft_uuid) DO UPDATE SET
                    entity_id = EXCLUDED.entity_id, entity_type = EXCLUDED.entity_type,
                    dimension = EXCLUDED.dimension, ownership_generation = EXCLUDED.ownership_generation,
                    revision = EXCLUDED.revision, entity_nbt = EXCLUDED.entity_nbt,
                    deleted = EXCLUDED.deleted, chunk_x = EXCLUDED.chunk_x, chunk_z = EXCLUDED.chunk_z,
                    runtime_generation = EXCLUDED.runtime_generation
                WHERE (EXCLUDED.ownership_generation, EXCLUDED.revision)
                      > (current.ownership_generation, current.revision)
                """)) {
            write.setString(1, world);
            write.setObject(2, snapshot.minecraftId());
            write.setLong(3, snapshot.entityId());
            write.setString(4, snapshot.entityType());
            write.setString(5, snapshot.dimension());
            write.setLong(6, snapshot.ownershipGeneration());
            write.setLong(7, snapshot.revision());
            write.setBytes(8, payload);
            write.setBoolean(9, deleted);
            bindChunk(write, 10, data, deleted);
            write.setLong(12, snapshot.ownershipGeneration());
            return write.executeUpdate() != 0;
        }
    }

    public boolean checkpointClaimed(AtlasNetEntityStore.Snapshot snapshot, long generation, long revision)
            throws SQLException, IOException {
        return checkpointClaimed(snapshot, generation, revision, false);
    }

    public boolean checkpointClaimed(AtlasNetEntityStore.Snapshot snapshot, long generation, long revision,
                                     boolean deleted) throws SQLException, IOException {
        try (var connection = database.getConnection()) {
            return checkpointClaimed(connection, snapshot, generation, revision, deleted);
        }
    }

    /**
     * Persist a confirmed interaction's player state and release its reservation
     * together. A retry after an ambiguous commit leaves the checkpoint alone.
     */
    public boolean checkpointInteraction(AtlasNetEntityStore.Snapshot player, long generation,
                                         long revision, TerrainStore.Reservation reservation)
            throws SQLException, IOException {
        if (!world.equals(reservation.chunk().world()) || !"minecraft:player".equals(player.entityType())) {
            throw new IllegalArgumentException("Terrain settlement requires a player checkpoint in the same world");
        }
        try (var connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var release = PostgresTerrainStore.releaseInteraction(connection, player.minecraftId(), reservation, true);
                if (release == PostgresTerrainStore.ReservationRelease.ABSENT) {
                    connection.rollback();
                    return true;
                }
                if (release == PostgresTerrainStore.ReservationRelease.PENDING
                        || !checkpointClaimed(connection, player, generation, revision, false)) {
                    connection.rollback();
                    return false;
                }
                connection.commit();
                return true;
            } catch (SQLException | IOException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    private boolean checkpointClaimed(Connection connection, AtlasNetEntityStore.Snapshot snapshot,
                                      long generation, long revision, boolean deleted)
            throws SQLException, IOException {
        var output = new ByteArrayOutputStream();
        var data = snapshot.data();
        if (!deleted) NbtIo.writeCompressed(data, output);
        try (var write = connection.prepareStatement("""
                UPDATE atlasnet_minecraft_entities
                SET entity_type = ?, dimension = ?, revision = ?, entity_nbt = ?, deleted = ?, chunk_x = ?, chunk_z = ?
                WHERE world = ? AND minecraft_uuid = ? AND entity_id = ?
                  AND ownership_generation = ? AND revision < ?
                """)) {
            write.setString(1, snapshot.entityType());
            write.setString(2, snapshot.dimension());
            write.setLong(3, revision);
            write.setBytes(4, deleted ? null : output.toByteArray());
            write.setBoolean(5, deleted);
            bindChunk(write, 6, data, deleted);
            write.setString(8, world);
            write.setObject(9, snapshot.minecraftId());
            write.setLong(10, snapshot.entityId());
            write.setLong(11, generation);
            write.setLong(12, revision);
            return write.executeUpdate() != 0;
        }
    }

    private static void bindChunk(java.sql.PreparedStatement write, int index,
                                  net.minecraft.nbt.CompoundTag data, boolean deleted) throws SQLException {
        var chunk = deleted ? null : checkpointPosition(data);
        if (chunk == null) {
            write.setNull(index, java.sql.Types.INTEGER);
            write.setNull(index + 1, java.sql.Types.INTEGER);
            return;
        }
        write.setInt(index, chunk.x);
        write.setInt(index + 1, chunk.z);
    }

    private static ChunkPos checkpointPosition(net.minecraft.nbt.CompoundTag data) {
        var position = data.getList("Pos", Tag.TAG_DOUBLE);
        if (position.size() != 3 || !Double.isFinite(position.getDouble(0))
                || !Double.isFinite(position.getDouble(2))) return null;
        return new ChunkPos(net.minecraft.core.SectionPos.posToSectionCoord(position.getDouble(0)),
                net.minecraft.core.SectionPos.posToSectionCoord(position.getDouble(2)));
    }

    private void backfillChunkCoordinates(java.sql.Connection connection) throws SQLException {
        try (var select = connection.prepareStatement("""
                SELECT minecraft_uuid, ownership_generation, revision, entity_nbt
                FROM atlasnet_minecraft_entities
                WHERE world = ? AND NOT deleted AND entity_type <> 'minecraft:player'
                  AND entity_nbt IS NOT NULL AND (chunk_x IS NULL OR chunk_z IS NULL)
                """); var update = connection.prepareStatement("""
                UPDATE atlasnet_minecraft_entities SET chunk_x = ?, chunk_z = ?
                WHERE world = ? AND minecraft_uuid = ? AND ownership_generation = ? AND revision = ?
                  AND NOT deleted AND (chunk_x IS NULL OR chunk_z IS NULL)
                """)) {
            select.setString(1, world);
            try (var rows = select.executeQuery()) {
                while (rows.next()) {
                    var uuid = rows.getObject(1, UUID.class);
                    ChunkPos chunk;
                    try {
                        chunk = checkpointPosition(NbtIo.readCompressed(
                                new ByteArrayInputStream(rows.getBytes(4)),
                                NbtAccounter.create(16L * 1024 * 1024)));
                    } catch (IOException | RuntimeException failure) {
                        System.getLogger(PostgresEntityCheckpoints.class.getName()).log(
                                System.Logger.Level.WARNING, "Could not index legacy entity checkpoint " + uuid, failure);
                        continue;
                    }
                    if (chunk == null) continue;
                    update.setInt(1, chunk.x);
                    update.setInt(2, chunk.z);
                    update.setString(3, world);
                    update.setObject(4, uuid);
                    update.setLong(5, rows.getLong(2));
                    update.setLong(6, rows.getLong(3));
                    update.executeUpdate();
                }
            }
        }
    }

    record CheckpointChunk(String dimension, int x, int z) {}

    Set<CheckpointChunk> occupiedChunks() throws SQLException {
        try (var connection = database.getConnection(); var query = connection.prepareStatement("""
                SELECT DISTINCT dimension, chunk_x, chunk_z
                FROM atlasnet_minecraft_entities
                WHERE world = ? AND NOT deleted AND entity_type <> 'minecraft:player'
                  AND entity_nbt IS NOT NULL AND chunk_x IS NOT NULL AND chunk_z IS NOT NULL
                """)) {
            backfillChunkCoordinates(connection);
            query.setString(1, world);
            var chunks = new HashSet<CheckpointChunk>();
            try (var rows = query.executeQuery()) {
                while (rows.next()) chunks.add(new CheckpointChunk(rows.getString(1), rows.getInt(2), rows.getInt(3)));
            }
            return Set.copyOf(chunks);
        }
    }

    List<AtlasNetEntityStore.Snapshot> loadChunk(String dimension, ChunkPos chunk) throws SQLException, IOException {
        try (var connection = database.getConnection(); var query = connection.prepareStatement("""
                SELECT entity_id, ownership_generation, revision, minecraft_uuid, entity_type, entity_nbt
                FROM atlasnet_minecraft_entities
                WHERE world = ? AND dimension = ? AND chunk_x = ? AND chunk_z = ?
                  AND NOT deleted AND entity_type <> 'minecraft:player' AND entity_nbt IS NOT NULL
                """)) {
            query.setString(1, world);
            query.setString(2, dimension);
            query.setInt(3, chunk.x);
            query.setInt(4, chunk.z);
            var snapshots = new ArrayList<AtlasNetEntityStore.Snapshot>();
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    snapshots.add(new AtlasNetEntityStore.Snapshot(rows.getLong(1), rows.getLong(2),
                            rows.getLong(3), rows.getObject(4, UUID.class), rows.getString(5), dimension,
                            NbtIo.readCompressed(new ByteArrayInputStream(rows.getBytes(6)),
                                    NbtAccounter.create(16L * 1024 * 1024))));
                }
            }
            return snapshots;
        }
    }

    /** Reads live checkpoints and exposes deletion fences for restore decisions. */
    public Optional<Checkpoint> load(UUID uuid) throws SQLException, IOException {
        try (var connection = database.getConnection(); var query = connection.prepareStatement("""
                SELECT entity_id, entity_type, dimension, ownership_generation, revision, entity_nbt, deleted
                FROM atlasnet_minecraft_entities
                WHERE world = ? AND minecraft_uuid = ?
                """)) {
            query.setString(1, world);
            query.setObject(2, uuid);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                long generation = rows.getLong(4);
                long revision = rows.getLong(5);
                byte[] payload = rows.getBytes(6);
                var snapshot = payload == null ? Optional.<AtlasNetEntityStore.Snapshot>empty() :
                        Optional.of(new AtlasNetEntityStore.Snapshot(rows.getLong(1),
                                generation, revision, uuid, rows.getString(2), rows.getString(3),
                                NbtIo.readCompressed(new ByteArrayInputStream(payload),
                                        NbtAccounter.create(16L * 1024 * 1024))));
                return Optional.of(new Checkpoint(generation, revision, snapshot, rows.getBoolean(7)));
            }
        }
    }

    public record Checkpoint(long ownershipGeneration, long revision,
                             Optional<AtlasNetEntityStore.Snapshot> snapshot, boolean deleted) {}
}
