package net.atlasnet.minecraft.host;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Reserves a vanilla server directory for one worker process. */
record MinecraftWorkerDirectory(Path path, FileChannel reservation) implements AutoCloseable {
    static MinecraftWorkerDirectory acquire(Path configured) throws IOException {
        Path primary = configured.toAbsolutePath().normalize();
        for (int slot = 1; ; slot++) {
            Path path = slot == 1 ? primary : primary.resolveSibling(primary.getFileName() + "-" + slot);
            Files.createDirectories(path);
            var channel = FileChannel.open(path.resolve(".atlasnet-worker.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            boolean reserved = false;
            try {
                if (channel.tryLock() == null) continue;
                if (slot != 1 && !Files.exists(path.resolve("eula.txt"))) {
                    Files.copy(primary.resolve("eula.txt"), path.resolve("eula.txt"));
                }
                reserved = true;
                return new MinecraftWorkerDirectory(path, channel);
            } catch (OverlappingFileLockException occupied) {
                // Another shard in this JVM already holds the slot.
            } finally {
                if (!reserved) channel.close();
            }
        }
    }

    @Override public void close() throws IOException {
        reservation.close();
    }
}
