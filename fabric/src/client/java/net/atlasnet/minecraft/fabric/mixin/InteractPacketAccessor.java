package net.atlasnet.minecraft.fabric.mixin;

import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerboundInteractPacket.class)
public interface InteractPacketAccessor {
    @Accessor("entityId")
    int atlasnet$entityId();
}
