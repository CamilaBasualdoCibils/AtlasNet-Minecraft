/**
 * Reserved for resolving an initial Minecraft world and position during AtlasNet
 * ingress. Register a generated {@link SpawnResolver} through
 * {@link Registry#registerSpawnResolver(String, SpawnResolver)} using
 * {@link Callbacks#spawnResolver(SpawnResolver)} for boxed return ownership.
 */
package net.atlasnet.minecraft.spawn;

import org.atlasnet.atlasnet.Callbacks;
import org.atlasnet.atlasnet.Registry;
import org.atlasnet.atlasnet.SpawnResolver;
