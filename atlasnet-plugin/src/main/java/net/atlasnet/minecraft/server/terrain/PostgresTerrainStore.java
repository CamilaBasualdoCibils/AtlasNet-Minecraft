package net.atlasnet.minecraft.server.terrain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;
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
            connection.commit();
        } catch (SQLException failure) {
            throw new IOException("Could not initialize PostgreSQL terrain storage", failure);
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
    public CommitResult commit(ChunkKey key, long baseRevision, CompoundTag data) throws IOException {
        if (baseRevision < 0) throw new IllegalArgumentException("Negative base revision");
        var output = new ByteArrayOutputStream();
        NbtIo.writeCompressed(data, output);
        try (var connection = database.getConnection()) {
            connection.setAutoCommit(false);
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

    private static void bindKey(PreparedStatement query, int first, ChunkKey key) throws SQLException {
        query.setString(first, key.world());
        query.setString(first + 1, key.dimension().toString());
        query.setInt(first + 2, key.x());
        query.setInt(first + 3, key.z());
    }
}
