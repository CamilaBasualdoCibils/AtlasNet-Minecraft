package net.atlasnet.minecraft.admission;

import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.commands.Commands;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.network.config.SynchronizeRegistriesTask;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

record VanillaConfiguration(LayeredRegistryAccess<RegistryLayer> registries,
                            List<KnownPack> knownPacks, Set<ResourceLocation> features) {
    static VanillaConfiguration load(MinecraftAdmissionConfig config) {
        SharedConstants.tryDetectVersion();
        if (!SharedConstants.getCurrentVersion().getName().equals("1.21.1")) {
            throw new IllegalStateException("Admission requires Minecraft 1.21.1");
        }
        Bootstrap.bootStrap();
        var packs = new WorldLoader.PackConfig(ServerPacksSource.createVanillaTrustedRepository(),
                WorldDataConfiguration.DEFAULT, false, true);
        var init = new WorldLoader.InitConfig(packs, Commands.CommandSelection.DEDICATED,
                config.functionPermissionLevel());
        return WorldLoader.load(init, context -> {
            var dimensions = WorldPresets.createNormalWorldDimensions(context.datapackWorldgen())
                    .bake(context.datapackDimensions().registryOrThrow(Registries.LEVEL_STEM))
                    .dimensionsRegistryAccess();
            return new WorldLoader.DataLoadOutput<>(context.dataConfiguration(), dimensions);
        }, (resources, reloadable, registries, dataConfig) -> {
            try (resources) {
                reloadable.updateRegistryTags();
                var knownPacks = resources.listPacks().flatMap(pack -> pack.knownPackInfo().stream()).toList();
                return new VanillaConfiguration(registries, knownPacks,
                        FeatureFlags.REGISTRY.toNames(dataConfig.enabledFeatures()));
            }
        }, Runnable::run, Runnable::run).join();
    }

    SynchronizeRegistriesTask newRegistryTask() {
        return new SynchronizeRegistriesTask(knownPacks, registries);
    }
}
