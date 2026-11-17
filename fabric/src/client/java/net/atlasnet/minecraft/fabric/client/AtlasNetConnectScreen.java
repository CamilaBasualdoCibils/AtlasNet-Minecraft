package net.atlasnet.minecraft.fabric.client;

import java.io.IOException;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.multiplayer.ServerData;
import java.util.concurrent.CompletableFuture;
import org.slf4j.LoggerFactory;

public final class AtlasNetConnectScreen extends Screen {
    private final Screen parent;
    private final String endpoint;
    private final ServerData server;
    private volatile boolean cancelled;
    private volatile Component status = Component.literal("Connecting through AtlasNet…");
    private boolean started;

    public AtlasNetConnectScreen(Screen parent, String endpoint, ServerData server) {
        super(Component.literal("AtlasNet connection"));
        this.parent = parent;
        this.endpoint = endpoint;
        this.server = server;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(width / 2 - 100, height / 2 + 24, 200, 20).build());
        if (!started) {
            started = true;
            Thread.ofVirtual().name("AtlasNet client connection").start(this::connect);
        }
    }

    private void connect() {
        long handle = 0;
        try {
            handle = openIngress();
            if (cancelled) {
                return;
            }
            status = Component.literal("Loading world through AtlasNet…");
            var play = CompletableFuture.supplyAsync(
                    () -> new AtlasNetPlayConnection(minecraft, parent, server, () -> cancelled = true),
                    minecraft).join();
            AtlasNetConnection.ready(handle);
            while (!cancelled && AtlasNetConnection.poll(handle)) {
                for (byte[] packet; (packet = AtlasNetConnection.nextPacket(handle)) != null;) {
                    byte[] received = packet;
                    minecraft.execute(() -> play.receive(received));
                }
                for (AtlasNetPlayConnection.Command command; (command = play.nextOutbound()) != null;) {
                    AtlasNetConnection.sendCommand(handle, command.id(), command.payload());
                }
                Thread.sleep(5);
            }
            if (!cancelled) {
                status = Component.literal("Disconnected from AtlasNet");
            }
        } catch (IOException | LinkageError error) {
            LoggerFactory.getLogger(AtlasNetConnectScreen.class).warn("AtlasNet connection failed", error);
            status = Component.literal("AtlasNet connection failed. " + error.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (handle != 0) {
                AtlasNetConnection.close(handle);
            }
        }
    }

    private long openIngress() throws IOException {
        try {
            return AtlasNetConnection.open(endpoint);
        } catch (IOException error) {
            if (cancelled || !"Timed out waiting for Client V1 HandshakeResponse".equals(error.getMessage())) {
                throw error;
            }
            status = Component.literal("Retrying AtlasNet connection…");
            return AtlasNetConnection.open(endpoint);
        }
    }

    @Override
    public void onClose() {
        cancelled = true;
        minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, status, width / 2, height / 2 - 10, 0xffffff);
    }
}
