#include "AtlasNet/Module/ModuleAPI.hpp"
#include "FabricServerHost.hpp"
#include "MinecraftActorReplication.hpp"
#include "MinecraftChunkDemand.hpp"
#include "MinecraftWire.hpp"

#include <iostream>
#include <bit>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <memory>
#include <string_view>
#include <unordered_set>
#include <unordered_map>
#include <utility>

namespace
{
using MinecraftTerrain::Overworld;
using MinecraftWire::AppendBigEndian;
using MinecraftWire::ReadBigEndian;
constexpr AtlasNet::CmdSig::SignalID TerrainUpdateSignal = 0x4d430109;
constexpr AtlasNet::CmdSig::SignalID TerrainViewerSignal = 0x4d43010a;
constexpr AtlasNet::CmdSig::SignalID TerrainSnapshotSignal = 0x4d43010b;
constexpr AtlasNet::CmdSig::CommandID GameplayInputCommand = 0x4d430006;
constexpr AtlasNet::CmdSig::CommandID PlayerAttackCommand = 0x4d430007;
constexpr std::size_t MaxGameplayInputBytes = 16 * 1024;
constexpr std::size_t TerrainUpdateHeaderBytes = 3 * sizeof(std::int32_t);
constexpr std::size_t SnapshotQueueHeaderBytes = sizeof(std::uint64_t) + 3 * sizeof(std::int32_t);
constexpr std::size_t SnapshotWireHeaderBytes = 2 * sizeof(std::uint64_t) + 4 * sizeof(std::int32_t);
constexpr std::size_t PoseBytes = 6 * sizeof(std::uint64_t) + 3 * sizeof(std::uint32_t) + 1;
constexpr std::size_t TransferHeaderBytes = 4 * sizeof(std::uint64_t) + 16 + 5 * sizeof(std::uint32_t);
constexpr std::size_t MaxPlayerTransferBytes = 48 * 1024;
constexpr std::size_t PlayFrameHeaderBytes = 1 + sizeof(std::uint64_t);
constexpr std::size_t ChunkCheckpointBytes = 2 * sizeof(std::uint32_t) + 4 * sizeof(std::uint64_t);
using PoseSnapshot = AtlasNet::Network::RPC::RPCMethod<
    "Minecraft.PlayerPose", std::vector<std::uint8_t>>;
using TerrainPlayerPoses = AtlasNet::Network::RPC::RPCMethod<
    "Minecraft.TerrainPlayerPoses", std::vector<std::uint8_t>, double, double, double, double>;
using BreakTerrainBlock = AtlasNet::Network::RPC::RPCMethod<
    "Minecraft.BreakTerrainBlock", int, int, int, int>;
using ReconcilePlayerVisibility = AtlasNet::Network::RPC::RPCMethod<
    "Minecraft.ReconcilePlayerVisibility", bool, std::vector<std::uint8_t>>;
using TerrainVacated = AtlasNet::Network::RPC::RPCMethod<"Minecraft.TerrainVacated", bool, int, int>;
using PlayerMelee = AtlasNet::Network::RPC::RPCMethod<
    "Minecraft.PlayerMelee", std::vector<std::uint8_t>, std::vector<std::uint8_t>>;

AtlasNet::Spatial::Query TerrainAudience(std::int32_t x, std::int32_t z, int view)
{
  const auto center = MinecraftTerrain::ChunkCenter(x, z);
  const double range = (view + 2) * 16;
  constexpr double MaxPlayerY = 20'000'000;
  return {.world = Overworld, .shape = AtlasNet::Spatial::Box{
      {center[0] - range, -MaxPlayerY, center[2] - range},
      {center[0] + range, MaxPlayerY, center[2] + range}}};
}

class MinecraftShard final : public AtlasNet::Module::IAtlasNetShardAPI
{
public:
  void OnStart(AtlasNet::ShardID shard,
               AtlasNet::Network::RPC::LocalShardRPC&) override
  {
    const auto* storage = std::getenv("ATLASNET_MINECRAFT_VALKEY_TERRAIN");
    partitionedTerrain = storage && std::string_view(storage) == "partitioned";
    valkeyTerrain = partitionedTerrain || (storage && std::string_view(storage) == "1");
    liveTerrain = partitionedTerrain && std::getenv("MINECRAFT_TERRAIN_SESSION");
    shardName = shard.to_string();
    AtlasNet::EntityMigrationCallbacks callbacks;
    callbacks.prepareExtract = [this](auto entity, auto transfer, auto& payload) {
      if (auto chunk = chunkKeys.find(entity); liveTerrain && chunk != chunkKeys.end())
      {
        const auto [x, z] = ChunkCoordinates(chunk->second);
        auto checkpoint = server.PrepareChunk(x, z, transfer.to_string());
        if (!checkpoint)
          return false;
        payload = std::move(*checkpoint);
        return true;
      }
      if (worldActors.Contains(entity))
        return worldActors.Prepare(server, entity, payload);
      return markers.contains(entity) || PreparePlayerTransfer(entity, payload);
    };
    callbacks.prepareRestore = [this](auto entity, auto transfer, auto payload) {
      if (liveTerrain && payload.size() == ChunkCheckpointBytes)
      {
        const auto key = ReadBigEndian<std::uint64_t>(payload);
        if (chunks.contains(key) || !server.StageChunk(transfer.to_string(), payload))
          return false;
        incomingChunks.emplace(entity, key);
        return true;
      }
      if (liveTerrain && MinecraftActorReplication::IsCheckpoint(payload))
        return worldActors.Stage(server, entity, transfer.to_string(), payload);
      return payload.empty() ? !IsEntityRegistered(entity) : StagePlayerTransfer(entity, payload);
    };
    callbacks.commitExtract = [this](auto entity, auto transfer) {
      if (auto chunk = chunkKeys.find(entity); liveTerrain && chunk != chunkKeys.end())
      {
        const auto [x, z] = ChunkCoordinates(chunk->second);
        server.ExtractChunk(x, z, transfer.to_string(), true);
        chunks.erase(chunk->second);
        chunkKeys.erase(chunk);
        std::clog << "Transferred Minecraft chunk " << x << ", " << z << " out of " << shardName << std::endl;
        return true;
      }
      if (worldActors.Contains(entity))
      {
        worldActors.Extract(server, entity, true);
        return true;
      }
      if (markers.erase(entity) || !players.contains(entity))
        return true;
      if (!server.ExtractPlayer(entity.value()))
        return false;
      players.erase(entity);
      return true;
    };
    callbacks.commitRestore = [this](auto entity, auto transfer) {
      if (auto chunk = incomingChunks.find(entity); chunk != incomingChunks.end())
      {
        if (!server.CommitChunk(transfer.to_string()))
          return false;
        const auto key = chunk->second;
        chunks.emplace(key, entity);
        chunkKeys.emplace(entity, key);
        incomingChunks.erase(chunk);
        const auto [x, z] = ChunkCoordinates(key);
        std::clog << "Restored Minecraft chunk " << x << ", " << z << " on " << shardName << std::endl;
        return true;
      }
      if (worldActors.HasIncoming(entity))
      {
        if (!worldActors.Restore(server, entity, transfer.to_string()))
          return false;
        std::clog << "Restored Minecraft actor " << entity.value() << " on " << shardName << std::endl;
        return true;
      }
      auto incoming = stagedPlayers.find(entity);
      if (incoming == stagedPlayers.end())
      {
        if (!players.contains(entity))
          markers.insert(entity);
        return true;
      }
      if (!server.ActivatePlayer(entity.value()))
        return false;
      for (auto& [id, observer] : players)
        if (observer.visible.erase(entity))
          HidePlayer(observer.client, entity);
      auto& player = players.emplace(entity, std::move(incoming->second)).first->second;
      stagedPlayers.erase(incoming);
      CapturePose(entity, player);
      return true;
    };
    callbacks.abortExtract = [this](auto entity, auto transfer) {
      if (auto chunk = chunkKeys.find(entity); liveTerrain && chunk != chunkKeys.end())
      {
        const auto [x, z] = ChunkCoordinates(chunk->second);
        server.ExtractChunk(x, z, transfer.to_string(), false);
      }
      if (players.contains(entity))
        server.AbortPlayerTransfer(entity.value(), JNI_TRUE);
      if (worldActors.Contains(entity))
        worldActors.Extract(server, entity, false);
    };
    callbacks.abortRestore = [this](auto entity, auto transfer) {
      if (incomingChunks.contains(entity))
      {
        server.AbortChunk(transfer.to_string());
        incomingChunks.erase(entity);
      }
      if (stagedPlayers.erase(entity))
        server.AbortPlayerTransfer(entity.value(), JNI_FALSE);
      worldActors.Abort(server, entity, transfer.to_string());
    };
    SetEntityMigrationCallbacks(std::move(callbacks));

    // Only the initial unsplit region can own both bootstrap positions.
    SetRegionChangeCallback([this](const auto& shape) {
      if (shape && (shape->dimension != 2 || shape->spatialPlane != AtlasNet::SpatialPlane::XZ))
        throw std::runtime_error("Minecraft requires an AtlasNet 2D heuristic with spatialPlane XZ");
      demandAuthorityChanged = demandAuthorityChanged || !region || !shape ||
          region->id != shape->id || region->dimension != shape->dimension ||
          region->min != shape->min || region->max != shape->max || region->planes != shape->planes;
      region = shape;
      regionChanged = true;
      if (liveTerrain)
        terrainRegionChanged = true;
      if (!valkeyTerrain && !seeded)
        seedMarkers = shape && shape->Contains({-16, -60, 0})
                            && shape->Contains({16, -60, 0});
    });
    BindEntityRPC<PoseSnapshot>([this](const AtlasNet::EntityFunctionContext& context) {
      if (context.localEntities.size() != 1)
        return std::vector<std::uint8_t>{};
      auto player = players.find(context.localEntities.front());
      return player == players.end() ? std::vector<std::uint8_t>{} : player->second.pose;
    });
    BindEntityRPC<PlayerMelee>([this](const AtlasNet::EntityFunctionContext& context,
                                      const std::vector<std::uint8_t>& state) {
      if (context.localEntities.size() != 1 || !players.contains(context.localEntities.front()))
        return std::vector<std::uint8_t>{};
      return server.ApplyMelee(context.localEntities.front().value(), state);
    });
    BindSpatialRPC<ReconcilePlayerVisibility>([this](const AtlasNet::SpatialRPCContext& context,
                                                     const std::vector<std::uint8_t>& pose) {
      if (context.world != Overworld || pose.size() != PoseBytes)
        return false;
      const AtlasNet::AtlasNetEntityID subject{
          ReadBigEndian<std::uint64_t>(std::as_bytes(std::span(pose)))};
      if (players.contains(subject))
        return true;
      for (auto& [id, observer] : players)
      {
        if (!observer.ready || observer.awaitingAuthority || IsEntityMigrationPending(id) ||
            observer.interest == AtlasNet::SpatialInterestHandle{} || observer.visible.contains(subject))
          continue;
        const auto members = GetSpatialInterestEntities(observer.interest);
        if (std::ranges::find(members, subject) != members.end())
        {
          observer.visible.emplace(subject, ++visibilityGeneration);
          SendVisibility(observer.client, 0x4d430107, pose);
        }
      }
      return true;
    });
    if (valkeyTerrain)
      MinecraftTerrain::Bind(*this, server);
    else
      StartMinecraft();
    if (partitionedTerrain)
    {
      BindSpatialRPC<TerrainPlayerPoses>([this](const AtlasNet::SpatialRPCContext& context,
                                               double x, double y, double z, double radius) {
        std::vector<std::uint8_t> result;
        if (context.world != Overworld)
          return result;
        for (const auto& [entity, player] : players)
        {
          const auto dx = player.position[0] - x;
          const auto dy = player.position[1] - y;
          const auto dz = player.position[2] - z;
          if (player.pose.size() == PoseBytes && !IsEntityMigrationPending(entity) &&
              dx * dx + dy * dy + dz * dz <= radius * radius)
          {
            AppendBigEndian(result, static_cast<std::uint32_t>(PoseBytes));
            result.insert(result.end(), player.pose.begin(), player.pose.end());
          }
        }
        worldActors.AppendNearby(result, x, y, z, radius);
        return result;
      });
      BindSpatialRPC<BreakTerrainBlock>([this](const AtlasNet::SpatialRPCContext& context,
                                              int x, int y, int z) {
        if (!server.Started() || context.world != Overworld ||
            !context.IsAuthoritative(MinecraftTerrain::ChunkCenter(ChunkCoordinate(x), ChunkCoordinate(z))))
          return -1;
        return server.BreakTerrainBlock(x, y, z);
      });
    }
    if (liveTerrain)
      BindSpatialRPC<TerrainVacated>([this](const AtlasNet::SpatialRPCContext& context, int x, int z) {
        if (!server.Started() || !context.IsAuthoritative(MinecraftTerrain::ChunkCenter(x, z)))
          return false;
        server.TerrainVacated(x, z, false);
        return true;
      });
  }

  std::optional<AtlasNet::AtlasNetEntityID> OnClientSpawn(
      AtlasNet::AtlasNetClientID client, AtlasNet::AtlasNetEntityID entity,
      const AtlasNet::Module::ClientSpawnPlacement& placement,
      std::span<const std::byte>) override
  {
    if (!server.Started() ||
        !server.Spawn(client.value(), entity.value(), placement.worldName, placement.x, placement.y, placement.z))
      return std::nullopt;
    auto [player, inserted] = players.try_emplace(entity, Player{client});
    if (inserted)
    {
      player->second.trackingRange = server.TrackingRange();
      CapturePose(entity, player->second);
    }
    return entity;
  }

  void UnregisterEntity(AtlasNet::AtlasNetEntityID entity) override
  {
    worldActors.Unregister(entity);
    if (auto chunk = chunkKeys.find(entity); chunk != chunkKeys.end())
    {
      chunks.erase(chunk->second);
      chunkKeys.erase(chunk);
    }
    markers.erase(entity);
    if (auto player = players.find(entity); player != players.end())
    {
      if (player->second.demand)
        MinecraftTerrain::Publish(*this, pendingDemand, entity, ++player->second.demandRevision,
                                  player->second.demand, std::nullopt);
      if (player->second.ready)
        Broadcast(0x4d430103, server.Presence(player->second.client.value(), entity.value(), JNI_FALSE));
      if (player->second.interest != AtlasNet::SpatialInterestHandle{})
        DestroySpatialInterest(player->second.interest);
      players.erase(player);
      server.Remove(entity.value());
    }
  }

  bool IsEntityRegistered(AtlasNet::AtlasNetEntityID entity) const override
  {
    return chunkKeys.contains(entity) || markers.contains(entity) || players.contains(entity) ||
           worldActors.Contains(entity);
  }
  void SetEntityMigrationCallbacks(AtlasNet::EntityMigrationCallbacks callbacks) override
  {
    IAtlasNetShardAPI::SetEntityMigrationCallbacks(std::move(callbacks));
  }

  void RegisterCommandHandler(AtlasNet::CmdSig::CommandID, RawCommandCallback) override {}
  void HandleCommands(std::span<const AtlasNet::Module::ShardCommand> commands) override
  {
    for (const auto& command : commands)
    {
      auto player = players.find(command.entity);
      if (player == players.end())
        continue;
      AtlasNet::NetBinaryReader reader(command.payload);
      AtlasNet::CmdSig::CommandPackage package;
      reader(package);
      if (reader->adapter().error() != bitsery::ReaderError::NoError || reader.Remaining() != 0)
        continue;
      const auto& payload = package.commandPayload;
      const auto client = player->second.client.value();
      const auto entity = command.entity.value();
      if (payload.commandID == 0x4d430002 && payload.payload.empty() && !player->second.ready)
      {
        player->second.ready = true;
        if (partitionedTerrain)
        {
          std::vector<std::uint8_t> viewer;
          AppendBigEndian(viewer, command.entity.value());
          SendVisibility(player->second.client, TerrainViewerSignal, viewer);
        }
        Broadcast(0x4d430102, server.Presence(client, entity, JNI_TRUE));
        SendRegion(player->second.client, player->second.playEpoch);
        SendShardMetrics(player->second.client, player->second.playEpoch);
        PublishPose(command.entity, player->second);
        if (!valkeyTerrain || partitionedTerrain)
          ObservePlayers(command.entity, player->second);
      }
      else if (player->second.ready && payload.payload.size() >= sizeof(std::uint64_t) &&
               payload.payload.size() <= sizeof(std::uint64_t) +
                   (payload.commandID == GameplayInputCommand ? MaxGameplayInputBytes : 64) &&
               (payload.commandID == 0x4d430003 || payload.commandID == 0x4d430004 ||
                payload.commandID == GameplayInputCommand || (valkeyTerrain && payload.commandID == 0x4d430005)))
      {
        auto data = std::span(payload.payload);
        if (ReadBigEndian<std::uint64_t>(std::as_bytes(data)) != player->second.playEpoch)
          continue;
        player->second.hasPlayInput = true;
        if (std::exchange(player->second.awaitingAuthority, false))
        {
          // This command used the new play stream and arrived through the
          // committed AtlasNet authority route.
          SendRegion(player->second.client, player->second.playEpoch);
          SendShardMetrics(player->second.client, player->second.playEpoch);
          if (player->second.interest != AtlasNet::SpatialInterestHandle{})
          {
            ReconcileTransferredView(player->second);
            ObservePlayers(command.entity, player->second);
          }
          // AtlasNet preserves interest membership through migration. Refresh
          // observers on the old shard whose vanilla tracker has removed us.
          const auto& [x, y, z] = player->second.position;
          const double radius = player->second.trackingRange + (region ? region->hysteresisDistance : 0);
          if (player->second.pose.size() == PoseBytes)
            CallSpatial<ReconcilePlayerVisibility>({.world = Overworld, .shape =
                AtlasNet::Spatial::Sphere{.center = {x, y, z}, .radius = radius}}, {player->second.pose});
        }
        server.ReceivePacket(entity, data.subspan(sizeof(std::uint64_t)), payload.commandID);
      }
      else if (player->second.ready && payload.commandID == 0x4d430001 && payload.payload.size() <= 1026)
        Broadcast(0x4d430101, server.Chat(client, entity, payload.payload));
      else if (player->second.ready && payload.commandID == PlayerAttackCommand &&
               payload.payload.size() == 2 * sizeof(std::uint64_t))
      {
        const auto data = std::as_bytes(std::span(payload.payload));
        if (ReadBigEndian<std::uint64_t>(data) != player->second.playEpoch ||
            player->second.awaitingAuthority || IsEntityMigrationPending(command.entity))
          continue;
        const AtlasNet::AtlasNetEntityID target{ReadBigEndian<std::uint64_t>(data.subspan(sizeof(std::uint64_t)))};
        if (!player->second.visible.contains(target))
          continue;
        auto state = server.PrepareMelee(entity);
        if (!state.empty())
          pendingMelee.push_back({command.entity, CallEntity<PlayerMelee>(std::span(&target, 1), {std::move(state)})});
      }
    }
  }
  void PollEvents() override {}
  void Poll() override
  {
    std::erase_if(pendingMelee, [this](auto& swing) {
      if (swing.result.wait_for(std::chrono::seconds(0)) != std::future_status::ready)
        return false;
      auto result = swing.result.get();
      if (players.contains(swing.attacker))
      {
        const auto state = result.failures.empty() && result.responses.size() == 1
            ? std::span<const std::uint8_t>(result.responses.front().value) : std::span<const std::uint8_t>{};
        server.FinishMelee(swing.attacker.value(), state);
      }
      return true;
    });
    if (valkeyTerrain)
    {
      if (liveTerrain && region && region->hysteresisDistance != 0)
        throw std::runtime_error("Live Minecraft chunk migration requires zero AtlasNet region hysteresis");
      if (!TerrainAuthorityReady())
      {
        if (server.Started())
        {
          if (liveTerrain && !region)
          {
            server.SetTerrainRegion(std::nullopt);
            return;
          }
          server.RevokeTerrain();
          throw std::runtime_error("Minecraft Valkey terrain authority geometry changed");
        }
        if (region && !partitionedTerrain)
          throw std::runtime_error("Minecraft Valkey terrain requires one region covering the Overworld");
        return;
      }
      if (!server.Started())
        StartMinecraft();
      if (liveTerrain && terrainRegionChanged && server.SetTerrainRegion(region))
        terrainRegionChanged = false;
      ApplyChunkLifecycle();
      CheckChunkDemand();
      if (liveTerrain)
        worldActors.Publish(*this, server, Overworld);
    }
    if (std::exchange(seedMarkers, false))
    {
      seeded = true;
      markers.insert(RegisterEntity({.x = -16, .y = -60}));
      markers.insert(RegisterEntity({.x = 16, .y = -60}));
    }
    FinishSnapshots();
    const bool newRegion = std::exchange(regionChanged, false);
    const bool newDemandAuthority = std::exchange(demandAuthorityChanged, false);
    for (auto& [entity, player] : players)
    {
      if (!player.ready || IsEntityMigrationPending(entity))
        continue;
      if (!player.awaitingAuthority)
      {
        PublishPose(entity, player);
        if (partitionedTerrain)
          RefreshTerrainPlayerVisibility(player);
        if (valkeyTerrain)
          PublishChunkDemand(entity, player, newDemandAuthority);
        if (newRegion)
          SendRegion(player.client, player.playEpoch);
      }
      for (int index = 0; index < 4; ++index)
      {
        if (player.packet.empty())
        {
          auto packet = server.NextPacket(entity.value());
          if (!packet)
            break;
          if (packet->front() == std::byte{1})
          {
            SendTerrainUpdate(player, std::span<const std::byte>(*packet).subspan(1));
            continue;
          }
          player.packet = std::move(*packet);
          player.offset = 1;
        }
        const auto count = std::min(AtlasNet::Module::MaxSignalPayloadBytes - PlayFrameHeaderBytes,
                                    player.packet.size() - player.offset);
        const bool last = player.offset + count == player.packet.size();
        std::vector<std::byte> payload{last ? std::byte{1} : std::byte{0}};
        for (std::size_t i = sizeof(player.playEpoch); i > 0; --i)
          payload.push_back(static_cast<std::byte>(player.playEpoch >> ((i - 1) * 8)));
        payload.insert(payload.end(), player.packet.begin() + player.offset,
                       player.packet.begin() + player.offset + count);
        AtlasNet::Module::ClientSignal signal{
            .recipient = player.client, .signal = 0x4d430105, .payload = std::move(payload)};
        DispatchRawSignals(std::span(&signal, 1));
        player.offset += count;
        if (last)
          player.packet.clear();
      }
    }
    if (partitionedTerrain)
    {
      ProcessTerrainBreaks();
      SendTerrainSnapshots();
    }
  }

  void OnShutdown() override
  {
    if (valkeyTerrain)
      std::clog << "Minecraft chunk entities: " << registeredChunks << " registered, "
                << retiredChunks << " retired, " << chunks.size() << " active" << std::endl;
    server.Stop();
  }

private:
  MinecraftActorReplication worldActors;

  struct PendingMelee
  {
    AtlasNet::AtlasNetEntityID attacker;
    std::future<AtlasNet::EntityRPCResult<std::vector<std::uint8_t>>> result;
  };
  std::vector<PendingMelee> pendingMelee;

  static std::pair<int, int> ChunkCoordinates(std::uint64_t key)
  {
    return {std::bit_cast<std::int32_t>(static_cast<std::uint32_t>(key >> 32)),
            std::bit_cast<std::int32_t>(static_cast<std::uint32_t>(key))};
  }

  static int ChunkCoordinate(int block)
  {
    const auto remainder = block % 16;
    return block / 16 - (remainder < 0 ? 1 : 0);
  }

  void ProcessTerrainBreaks()
  {
    std::erase_if(terrainBreaks, [this](auto& call) {
      if (call.result.wait_for(std::chrono::seconds(0)) != std::future_status::ready)
        return false;
      const auto result = call.result.get();
      const int state = result.failures.empty() && result.responses.size() == 1
          ? result.responses.front().value : -1;
      server.CompleteTerrainBreak(call.action, state);
      return true;
    });
    for (int i = 0; i < 8; ++i)
    {
      const auto action = server.NextTerrainBreak();
      if (!action)
        break;
      AtlasNet::Spatial::Query target{.world = Overworld, .shape = AtlasNet::Spatial::Point{
          MinecraftTerrain::ChunkCenter(ChunkCoordinate(action->x), ChunkCoordinate(action->z))}};
      terrainBreaks.push_back({*action, CallSpatial<BreakTerrainBlock>(target,
          {action->x, action->y, action->z})});
    }
  }

  struct PendingTerrainBreak
  {
    FabricServerHost::TerrainBreak action;
    std::future<AtlasNet::SpatialRPCResult<int>> result;
  };
  std::vector<PendingTerrainBreak> terrainBreaks;

  void StartMinecraft()
  {
    if (partitionedTerrain)
      terrainRegion = region;
    server.Start(shardName, valkeyTerrain, terrainRegion, liveTerrain);
    std::clog << "Minecraft shard bootstrap ready: " << shardName << std::endl;
  }

  bool OwnsWholeOverworld() const
  {
    if (!region || !region->planes.empty())
      return false;
    for (std::size_t axis = 0; axis < 2; ++axis)
      if (region->min[axis] != -std::numeric_limits<double>::infinity() ||
          region->max[axis] != std::numeric_limits<double>::infinity())
        return false;
    return true;
  }

  bool TerrainAuthorityReady() const
  {
    if (!partitionedTerrain)
      return OwnsWholeOverworld();
    if (!region)
      return false;
    if (liveTerrain)
      return region->hysteresisDistance == 0;
    if (OwnsWholeOverworld())
      return false;
    if (!terrainRegion)
      return true;
    if (region->planes != terrainRegion->planes)
      return false;
    for (const auto axis : {0, 1})
    {
      using MinecraftTerrain::FirstChunkCenterAtOrAbove;
      if (FirstChunkCenterAtOrAbove(region->min[axis]) != FirstChunkCenterAtOrAbove(terrainRegion->min[axis]) ||
          FirstChunkCenterAtOrAbove(region->max[axis]) != FirstChunkCenterAtOrAbove(terrainRegion->max[axis]))
        return false;
    }
    return true;
  }

  void ApplyChunkLifecycle()
  {
    const auto events = server.TakeChunkLifecycle();
    for (std::size_t index = 0; index < events.size(); index += 3)
    {
      const auto x = events[index];
      const auto z = events[index + 1];
      const auto key = (std::uint64_t(static_cast<std::uint32_t>(x)) << 32) |
                       static_cast<std::uint32_t>(z);
      auto existing = chunks.find(key);
      if (events[index + 2] != 0 && existing == chunks.end())
      {
        const bool first = chunks.empty();
        const auto [worldX, worldY, worldZ] = MinecraftTerrain::ChunkCenter(x, z);
        const auto entity = RegisterEntity({.x = worldX, .y = worldY, .z = worldZ});
        chunks.emplace(key, entity);
        chunkKeys.emplace(entity, key);
        ++registeredChunks;
        if (first)
          std::clog << "Registered Minecraft chunk authority at " << x << ", " << z << std::endl;
      }
      else if (events[index + 2] == 0 && existing != chunks.end())
      {
        RemoveEntity(existing->second);
        ++retiredChunks;
        if (liveTerrain)
        {
          server.TerrainVacated(x, z, true);
          CallSpatial<TerrainVacated>({.world = Overworld, .shape =
              AtlasNet::Spatial::Point{MinecraftTerrain::ChunkCenter(x, z)}}, {x, z});
        }
      }
    }
  }

  void SendRegion(AtlasNet::AtlasNetClientID client, std::uint64_t epoch)
  {
    auto payload = server.Region(region.value_or(AtlasNet::Module::RegionShape{}));
    if (payload)
      SendAuthorityNotice(client, 0x4d430104, epoch, *payload);
  }

  void SendShardMetrics(AtlasNet::AtlasNetClientID client, std::uint64_t epoch)
  {
    if (auto payload = server.ShardMetrics(shardName))
      SendAuthorityNotice(client, 0x4d43010c, epoch, *payload);
  }

  void SendAuthorityNotice(AtlasNet::AtlasNetClientID client, AtlasNet::CmdSig::SignalID id,
                           std::uint64_t epoch, std::span<const std::byte> data)
  {
    AtlasNet::Module::ClientSignal signal{.recipient = client, .signal = id};
    for (std::size_t i = sizeof(epoch); i > 0; --i)
      signal.payload.push_back(static_cast<std::byte>(epoch >> ((i - 1) * 8)));
    signal.payload.insert(signal.payload.end(), data.begin(), data.end());
    DispatchRawSignals(std::span(&signal, 1));
  }

  void Broadcast(AtlasNet::CmdSig::SignalID id, std::optional<std::vector<std::byte>> payload)
  {
    if (payload)
      DispatchSignal(AtlasNet::CmdSig::Audience::Broadcast{}, id, *payload);
  }

  FabricServerHost server;
  bool valkeyTerrain = false;
  bool partitionedTerrain = false;
  bool liveTerrain = false;
  bool terrainRegionChanged = false;
  std::optional<AtlasNet::Module::RegionShape> terrainRegion;
  std::vector<MinecraftTerrain::PendingUpdate> pendingDemand;
  std::string shardName;
  std::unordered_map<std::uint64_t, AtlasNet::AtlasNetEntityID> chunks;
  std::unordered_map<AtlasNet::AtlasNetEntityID, std::uint64_t> chunkKeys;
  std::unordered_map<AtlasNet::AtlasNetEntityID, std::uint64_t> incomingChunks;
  std::size_t registeredChunks = 0;
  std::size_t retiredChunks = 0;
  std::optional<AtlasNet::Module::RegionShape> region;
  bool regionChanged = false;
  bool demandAuthorityChanged = false;
  struct Player
  {
    AtlasNet::AtlasNetClientID client;
    bool ready = false;
    bool hasPlayInput = false;
    bool awaitingAuthority = false;
    std::uint64_t playEpoch = 0;
    double trackingRange = 0;
    std::vector<std::byte> packet;
    std::size_t offset = 0;
    AtlasNet::SpatialInterestHandle interest{};
    std::unordered_map<AtlasNet::AtlasNetEntityID, std::uint64_t> visible;
    std::vector<std::uint8_t> pose;
    std::array<double, 3> position{};
    std::optional<std::array<double, 3>> publishedPosition;
    bool poseDirty = false;
    std::uint64_t revision = 0;
    std::optional<MinecraftTerrain::Window> demand;
    std::uint64_t demandRevision = 0;
    using PoseQuery = AtlasNet::SpatialRPCResult<std::vector<std::uint8_t>>;
    std::optional<std::future<PoseQuery>> terrainPoses;
    bool terrainPosesDirty = false;
    bool resetDemand = false;
  };

  bool PreparePlayerTransfer(AtlasNet::AtlasNetEntityID entity, AtlasNet::EntityPayload& payload)
  {
    auto record = players.find(entity);
    if (record == players.end() || !record->second.hasPlayInput || record->second.awaitingAuthority)
      return false;
    auto& player = record->second;
    const auto viewBytes = player.visible.size() * sizeof(std::uint64_t);
    if (viewBytes + TransferHeaderBytes >= MaxPlayerTransferBytes)
      return false;
    auto state = server.FreezePlayer(entity.value(), MaxPlayerTransferBytes - TransferHeaderBytes - viewBytes);
    if (!state)
      return false;
    CapturePose(entity, player);
    std::vector<std::uint8_t> header;
    AppendBigEndian(header, player.client.value());
    AppendBigEndian(header, player.playEpoch + 1);
    AppendBigEndian(header, player.revision);
    AppendBigEndian(header, player.demandRevision);
    const auto* interest = static_cast<const std::uint8_t*>(player.interest.data());
    header.insert(header.end(), interest, interest + player.interest.size());
    const auto view = player.demand.value_or(MinecraftTerrain::Window{});
    for (auto coordinate : {view.x, view.z, view.viewDistance, view.simulationDistance})
      AppendBigEndian(header, static_cast<std::uint32_t>(coordinate));
    AppendBigEndian(header, static_cast<std::uint32_t>(player.visible.size()));
    for (const auto& [visible, generation] : player.visible)
      AppendBigEndian(header, visible.value());
    const auto bytes = std::as_bytes(std::span(header));
    payload.assign(bytes.begin(), bytes.end());
    payload.insert(payload.end(), state->begin(), state->end());
    return true;
  }

  bool StagePlayerTransfer(AtlasNet::AtlasNetEntityID entity, std::span<const std::byte> payload)
  {
    if (!server.Started() || IsEntityRegistered(entity) || payload.size() <= TransferHeaderBytes ||
        payload.size() > MaxPlayerTransferBytes)
      return false;
    Player player{AtlasNet::AtlasNetClientID{ReadBigEndian<std::uint64_t>(payload)}};
    player.ready = true;
    player.awaitingAuthority = true;
    player.playEpoch = ReadBigEndian<std::uint64_t>(payload.subspan(8));
    player.revision = ReadBigEndian<std::uint64_t>(payload.subspan(16));
    player.demandRevision = ReadBigEndian<std::uint64_t>(payload.subspan(24));
    std::memcpy(player.interest.data(), payload.data() + 32, player.interest.size());
    const auto coordinate = [&](std::size_t offset) {
      return std::bit_cast<std::int32_t>(ReadBigEndian<std::uint32_t>(payload.subspan(offset)));
    };
    if (const auto view = coordinate(56); view != 0)
      player.demand = MinecraftTerrain::Window{coordinate(48), coordinate(52), view, coordinate(60)};
    player.trackingRange = server.TrackingRange();
    player.resetDemand = true;
    const auto count = ReadBigEndian<std::uint32_t>(payload.subspan(64));
    auto state = payload.subspan(TransferHeaderBytes);
    if (count > state.size() / sizeof(std::uint64_t))
      return false;
    for (std::uint32_t i = 0; i < count; ++i)
    {
      const AtlasNet::AtlasNetEntityID visible{ReadBigEndian<std::uint64_t>(state)};
      player.visible.emplace(visible, ++visibilityGeneration);
      state = state.subspan(sizeof(std::uint64_t));
    }
    if (state.empty() || !server.StagePlayer(player.client.value(), entity.value(), state))
      return false;
    stagedPlayers.emplace(entity, std::move(player));
    return true;
  }

  void ReconcileTransferredView(Player& player)
  {
    const auto current = GetSpatialInterestEntities(player.interest);
    const std::unordered_set<AtlasNet::AtlasNetEntityID> members(current.begin(), current.end());
    std::erase_if(player.visible, [this, &player, &members](const auto& entry) {
      if (!players.contains(entry.first) && members.contains(entry.first))
        return false;
      HidePlayer(player.client, entry.first);
      return true;
    });
  }

  void SendTerrainUpdate(const Player& player, std::span<const std::byte> payload)
  {
    if (payload.size() <= TerrainUpdateHeaderBytes)
      return;
    const auto x = std::bit_cast<std::int32_t>(ReadBigEndian<std::uint32_t>(payload));
    const auto z = std::bit_cast<std::int32_t>(ReadBigEndian<std::uint32_t>(payload.subspan(sizeof(x))));
    const auto view = player.demand ? player.demand->viewDistance : MinecraftTerrain::MaxDistance;
    DispatchSignal(TerrainAudience(x, z, view), TerrainUpdateSignal, payload);
  }

  void SendTerrainSnapshots()
  {
    const auto now = std::chrono::steady_clock::now();
    if (now < nextTerrainSend)
      return;
    nextTerrainSend = now + std::chrono::milliseconds(4);
    for (int fragment = 0; fragment < 2; ++fragment)
    {
      if (snapshot.empty())
      {
        auto packet = server.NextTerrainSnapshot();
        if (!packet)
          return;
        if (packet->size() <= SnapshotQueueHeaderBytes)
          continue;
        snapshot = std::move(*packet);
        snapshotOffset = 0;
        ++snapshotSequence;
      }
      const auto data = std::span<const std::byte>(snapshot);
      const auto viewer = ReadBigEndian<std::uint64_t>(data);
      const auto x = ReadBigEndian<std::uint32_t>(data.subspan(8));
      const auto z = ReadBigEndian<std::uint32_t>(data.subspan(12));
      const auto view = ReadBigEndian<std::uint32_t>(data.subspan(16));
      const auto packet = data.subspan(SnapshotQueueHeaderBytes);
      const auto count = std::min(AtlasNet::Module::MaxSignalPayloadBytes - SnapshotWireHeaderBytes,
                                  packet.size() - snapshotOffset);
      std::vector<std::uint8_t> payload;
      payload.reserve(SnapshotWireHeaderBytes + count);
      AppendBigEndian(payload, viewer);
      AppendBigEndian(payload, snapshotSequence);
      AppendBigEndian(payload, x);
      AppendBigEndian(payload, z);
      AppendBigEndian(payload, static_cast<std::uint32_t>(snapshotOffset));
      AppendBigEndian(payload, static_cast<std::uint32_t>(packet.size()));
      const auto* first = reinterpret_cast<const std::uint8_t*>(packet.data()) + snapshotOffset;
      payload.insert(payload.end(), first, first + count);
      DispatchSignal(TerrainAudience(std::bit_cast<std::int32_t>(x), std::bit_cast<std::int32_t>(z),
                                     static_cast<int>(view)), TerrainSnapshotSignal,
                     std::as_bytes(std::span(payload)));
      snapshotOffset += count;
      if (snapshotOffset == packet.size())
        snapshot.clear();
    }
  }

  std::vector<std::byte> snapshot;
  std::size_t snapshotOffset = 0;
  std::uint64_t snapshotSequence = 0;
  std::chrono::steady_clock::time_point nextTerrainSend;

  void PublishChunkDemand(AtlasNet::AtlasNetEntityID entity, Player& player, bool regionChanged)
  {
    const auto pending = server.TakeChunkDemand(entity.value());
    auto next = player.demand;
    if (pending)
      next = MinecraftTerrain::Window{(*pending)[0], (*pending)[1], (*pending)[2], (*pending)[3]};
    if (next)
    {
      const bool refresh = std::exchange(player.resetDemand, false);
      if (next != player.demand || regionChanged || refresh)
        MinecraftTerrain::Publish(*this, pendingDemand, entity, ++player.demandRevision,
                                  player.demand, next, refresh);
    }
    player.demand = next;
  }

  void CheckChunkDemand()
  {
    std::erase_if(pendingDemand, [](auto& pending) {
      if (pending.wait_for(std::chrono::seconds(0)) != std::future_status::ready)
        return false;
      const auto result = pending.get();
      if (result.responses.empty() || !result.failures.empty() ||
          std::ranges::any_of(result.responses, [](const auto& response) { return !response.value; }))
      {
        std::clog << "Minecraft chunk demand did not reach all owners";
        for (const auto& failure : result.failures)
          std::clog << " (AtlasNet RPC error " << static_cast<int>(failure.error) << ')';
        std::clog << std::endl;
      }
      return true;
    });
  }
  void CapturePose(AtlasNet::AtlasNetEntityID entity, Player& player)
  {
    auto pose = server.TakePose(entity.value());
    if (!pose)
      return;
    const auto& [x, y, z, yaw, pitch, head, flags] = *pose;
    player.position = std::array{x, y, z};
    player.poseDirty = true;
    auto& bytes = player.pose;
    bytes.clear();
    AppendBigEndian(bytes, entity.value());
    AppendBigEndian(bytes, player.client.value());
    AppendBigEndian(bytes, ++player.revision);
    for (double coordinate : {x, y, z})
      AppendBigEndian(bytes, std::bit_cast<std::uint64_t>(coordinate));
    for (double angle : {yaw, pitch, head})
      AppendBigEndian(bytes, std::bit_cast<std::uint32_t>(static_cast<float>(angle)));
    bytes.push_back(static_cast<std::uint8_t>(flags));
  }

  void PublishPose(AtlasNet::AtlasNetEntityID entity, Player& player)
  {
    CapturePose(entity, player);
    if (!std::exchange(player.poseDirty, false))
      return;
    const auto& [x, y, z] = player.position;
    if (!player.publishedPosition || *player.publishedPosition != player.position)
    {
      player.publishedPosition = player.position;
      UpdateEntityTransform(entity, {.x = x, .y = y, .z = z});
    }
    AtlasNet::Spatial::Query audience{.world = Overworld,
        .shape = AtlasNet::Spatial::Sphere{.center = {x, y, z}, .radius = player.trackingRange}};
    DispatchSignal(audience, 0x4d430106, std::as_bytes(std::span(player.pose)));
  }

  void ObservePlayers(AtlasNet::AtlasNetEntityID observer, Player& player)
  {
    if (player.interest == AtlasNet::SpatialInterestHandle{})
      player.interest = CreateSpatialInterest(observer,
          AtlasNet::Spatial::Sphere{.radius = player.trackingRange});
    OnSpatialInterest(player.interest, [this, observer](const AtlasNet::SpatialInterestEvent& event) {
      auto found = players.find(observer);
      if (found == players.end() || players.contains(event.entity) || markers.contains(event.entity) ||
          chunkKeys.contains(event.entity))
        return;
      auto& visible = found->second.visible;
      if (event.type == AtlasNet::SpatialInterestEventType::Exit)
      {
        if (visible.erase(event.entity))
          HidePlayer(found->second.client, event.entity);
        return;
      }
      if (partitionedTerrain)
      {
        // The interest also encounters remote chunk actors. Discover the
        // nearby real players in one spatial call and keep the interest as
        // the source of membership, without issuing RPCs on chunk actors.
        found->second.terrainPosesDirty = true;
        return;
      }
      const auto generation = ++visibilityGeneration;
      visible[event.entity] = generation;
      snapshots.push_back({observer, event.entity, generation,
          CallEntity<PoseSnapshot>(std::span(&event.entity, 1), {})});
    });
  }

  void RefreshTerrainPlayerVisibility(Player& observer)
  {
    if (observer.terrainPoses)
    {
      if (observer.terrainPoses->wait_for(std::chrono::seconds(0)) != std::future_status::ready)
        return;
      auto result = observer.terrainPoses->get();
      observer.terrainPoses.reset();
      auto current = GetSpatialInterestEntities(observer.interest);
      const std::unordered_set<AtlasNet::AtlasNetEntityID> members(current.begin(), current.end());
      for (const auto& response : result.responses)
      {
        auto records = std::span(response.value);
        while (records.size() >= sizeof(std::uint32_t))
        {
          const auto size = ReadBigEndian<std::uint32_t>(std::as_bytes(records));
          records = records.subspan(sizeof(std::uint32_t));
          if (size > records.size() || size < sizeof(std::uint64_t))
            break;
          const auto pose = records.first(size);
          records = records.subspan(size);
          const AtlasNet::AtlasNetEntityID entity{ReadBigEndian<std::uint64_t>(std::as_bytes(pose))};
          if (members.contains(entity) && !players.contains(entity) &&
              observer.visible.try_emplace(entity, 0).second)
            SendVisibility(observer.client, size == PoseBytes ? 0x4d430107 : MinecraftActorReplication::Show, pose);
        }
      }
    }
    if (std::exchange(observer.terrainPosesDirty, false))
    {
      // A player may still be simulated on their old shard until they cross
      // AtlasNet's migration hysteresis. Include that shard in discovery.
      const auto& [x, y, z] = observer.position;
      AtlasNet::Spatial::Query area{.world = Overworld, .shape = AtlasNet::Spatial::Sphere{
          .center = {x, y, z},
          .radius = observer.trackingRange + (region ? region->hysteresisDistance : 0)}};
      observer.terrainPoses = CallSpatial<TerrainPlayerPoses>(area, {x, y, z, observer.trackingRange});
    }
  }

  void SendVisibility(AtlasNet::AtlasNetClientID client, AtlasNet::CmdSig::SignalID signal,
                      std::span<const std::uint8_t> payload)
  {
    // This SDK routes direct recipients, Broadcast and Spatial Signals.
    auto bytes = std::as_bytes(payload);
    AtlasNet::Module::ClientSignal directed{
        .recipient = client, .signal = signal, .payload = {bytes.begin(), bytes.end()}};
    DispatchRawSignals(std::span(&directed, 1));
  }

  void HidePlayer(AtlasNet::AtlasNetClientID client, AtlasNet::AtlasNetEntityID entity)
  {
    std::vector<std::uint8_t> payload;
    AppendBigEndian(payload, entity.value());
    SendVisibility(client, 0x4d430108, payload);
  }

  void FinishSnapshots()
  {
    std::erase_if(snapshots, [this](Snapshot& request) {
      auto observer = players.find(request.observer);
      if (observer == players.end())
        return true;
      auto membership = observer->second.visible.find(request.subject);
      if (membership == observer->second.visible.end() || membership->second != request.generation)
        return true;
      if (request.future.wait_for(std::chrono::seconds(0)) != std::future_status::ready)
        return false;
      auto result = request.future.get();
      for (const auto& response : result.responses)
        if (!response.value.empty())
          SendVisibility(observer->second.client, 0x4d430107, response.value);
      return true;
    });
  }

  struct Snapshot
  {
    AtlasNet::AtlasNetEntityID observer;
    AtlasNet::AtlasNetEntityID subject;
    std::uint64_t generation;
    std::future<AtlasNet::EntityRPCResult<std::vector<std::uint8_t>>> future;
  };
  std::vector<Snapshot> snapshots;
  std::uint64_t visibilityGeneration = 0;
  std::unordered_map<AtlasNet::AtlasNetEntityID, Player> players;
  std::unordered_map<AtlasNet::AtlasNetEntityID, Player> stagedPlayers;
  std::unordered_set<AtlasNet::AtlasNetEntityID> markers;
  bool seedMarkers = false;
  bool seeded = false;
};

class MinecraftShardProvider final : public AtlasNet::Module::ShardProvider
{
public:
  std::unique_ptr<AtlasNet::Module::IAtlasNetShardAPI> CreateShardLogic() override
  {
    return std::make_unique<MinecraftShard>();
  }
};

void Register(AtlasNet::Module::ModuleRegistry& registry)
{
  registry.RegisterShardProvider(std::make_shared<MinecraftShardProvider>());
}

const AtlasNet::Module::ModuleDescriptor descriptor{
    .name = "AtlasNetMinecraft",
    .version = "0.1.0",
    .capabilities = AtlasNet::Module::Capability::ShardProvider,
    .Register = Register};
}

extern "C" const AtlasNet::Module::ModuleDescriptor* AtlasNetGetModule()
{
  return &descriptor;
}
