package net.atlasnet.minecraft.fabric.client;

import java.io.IOException;
import java.nio.file.Path;

public final class AtlasNetConnection {
    static {
        String library = System.getProperty("atlasnet.minecraft.clientLibrary");
        if (library == null) {
            throw new UnsatisfiedLinkError("Start the AtlasNet development client with RunClient");
        }
        System.load(Path.of(library).toAbsolutePath().toString());
    }

    private AtlasNetConnection() {
    }

    static native long open(String endpoint) throws IOException;
    static native boolean poll(long handle) throws IOException;
    static native void ready(long handle) throws IOException;
    static native byte[] nextPacket(long handle);
    static native void sendCommand(long handle, int id, byte[] payload) throws IOException;
    static native void close(long handle);
}
