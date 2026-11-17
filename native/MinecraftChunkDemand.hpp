#pragma once

#include "AtlasNet/Module/IAtlasNetShardAPI.hpp"
#include "FabricServerHost.hpp"
#include <algorithm>
#include <cmath>
#include <limits>

namespace MinecraftTerrain
{
constexpr int Overworld = 0;
constexpr int MaxDistance = 32;

inline AtlasNet::Spatial::Vector ChunkCenter(int x, int z)
{
  // AtlasNet projects physical XYZ coordinates onto the configured XZ plane.
  return {x * 16.0 + 8.0, 0.0, z * 16.0 + 8.0};
}

// Region bounds are half-open. Compare their effect on the chunk-center
// lattice, since a change inside the same 16-block cell moves no ownership.
inline std::int64_t FirstChunkCenterAtOrAbove(double boundary)
{
  constexpr std::int64_t FirstChunk = std::numeric_limits<std::int32_t>::min();
  constexpr std::int64_t AfterLastChunk = std::int64_t{std::numeric_limits<std::int32_t>::max()} + 1;
  const auto estimate = std::clamp(std::ceil((boundary - 8) / 16),
                                   static_cast<double>(FirstChunk),
                                   static_cast<double>(AfterLastChunk));
  auto chunk = static_cast<std::int64_t>(estimate);
  if (chunk < AfterLastChunk && chunk * 16.0 + 8.0 < boundary)
    ++chunk;
  if (chunk > FirstChunk && (chunk - 1) * 16.0 + 8.0 >= boundary)
    --chunk;
  return chunk;
}

struct Window
{
  int x, z, viewDistance, simulationDistance;
  bool operator==(const Window&) const = default;

  int Radius() const { return std::max(viewDistance + 1, simulationDistance); }
  AtlasNet::Spatial::Query Query() const
  {
    const int radius = Radius();
    return {.world = Overworld, .shape = AtlasNet::Spatial::Box{
        ChunkCenter(x - radius, z - radius), ChunkCenter(x + radius, z + radius)}};
  }
};

using Update = AtlasNet::Network::RPC::RPCMethod<"Minecraft.ChunkDemand", bool,
    std::uint64_t, std::uint64_t, int, int, int, int, bool>;
using PendingUpdate = std::future<AtlasNet::SpatialRPCResult<bool>>;

inline void Bind(AtlasNet::Module::IAtlasNetShardAPI& shard, FabricServerHost& server)
{
  shard.BindSpatialRPC<Update>([&server](const AtlasNet::SpatialRPCContext& context,
      std::uint64_t observer, std::uint64_t revision, int x, int z, int view, int simulation, bool refresh) {
    if (!server.Started() || context.world != Overworld || view < 0 || view > MaxDistance ||
        simulation < 0 || simulation > MaxDistance || x < -1875000 || x > 1875000 ||
        z < -1875000 || z > 1875000)
      return false;
    std::vector<jboolean> owned;
    if (view != 0)
    {
      const int radius = Window{x, z, view, simulation}.Radius();
      owned.reserve((2 * radius + 1) * (2 * radius + 1));
      for (int dx = -radius; dx <= radius; ++dx)
        for (int dz = -radius; dz <= radius; ++dz)
          owned.push_back(context.IsAuthoritative(ChunkCenter(x + dx, z + dz)));
    }
    server.ApplyChunkDemand(observer, revision, x, z, view, simulation, owned, refresh);
    return true;
  });
}

inline void Publish(AtlasNet::Module::IAtlasNetShardAPI& shard,
                    std::vector<PendingUpdate>& pending,
                    AtlasNet::AtlasNetEntityID observer, std::uint64_t revision,
                    const std::optional<Window>& previous, const std::optional<Window>& next, bool refresh = false)
{
  const Window update = next.value_or(Window{});
  const Update::ArgsTuple args{observer.value(), revision, update.x, update.z,
                              update.viewDistance, update.simulationDistance, refresh};
  const auto send = [&](const Window& area) {
    pending.push_back(shard.CallSpatial<Update>(area.Query(), args));
  };
  if (previous && previous != next)
    send(*previous);
  if (next)
    send(*next);
}
}
