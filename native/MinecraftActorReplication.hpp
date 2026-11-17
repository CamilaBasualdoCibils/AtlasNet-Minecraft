#pragma once

#include "AtlasNet/Module/ModuleAPI.hpp"
#include "FabricServerHost.hpp"
#include "MinecraftWire.hpp"

#include <bit>
#include <cmath>
#include <unordered_map>

class MinecraftActorReplication
{
public:
  static constexpr AtlasNet::CmdSig::SignalID Show = MinecraftWire::WorldActorShow;
  static constexpr AtlasNet::CmdSig::SignalID Update = MinecraftWire::WorldActorUpdate;
  static constexpr std::size_t SampleHeader = sizeof(std::uint32_t);
  static constexpr std::size_t PoseHeader = 2 * sizeof(std::uint64_t);
  static constexpr std::size_t MinStateBytes = 3 * sizeof(double) + 2 * sizeof(std::uint64_t) + 2 * sizeof(float) + 1;

  bool Contains(AtlasNet::AtlasNetEntityID entity) const { return actors.contains(entity); }
  bool HasIncoming(AtlasNet::AtlasNetEntityID entity) const { return incoming.contains(entity); }

  void Unregister(AtlasNet::AtlasNetEntityID entity)
  {
    if (auto actor = actors.find(entity); actor != actors.end())
    {
      identities.erase(actor->second.localId);
      actors.erase(actor);
    }
  }

  void Publish(AtlasNet::Module::IAtlasNetShardAPI& shard, FabricServerHost& server, AtlasNet::WorldID world)
  {
    for (const auto& frame : server.ActorFrames())
    {
      if (frame.size() < SampleHeader)
        continue;
      const auto state = std::span(frame).subspan(SampleHeader);
      const auto localId = std::bit_cast<std::int32_t>(
          MinecraftWire::ReadBigEndian<std::uint32_t>(std::as_bytes(std::span(frame))));
      auto found = identities.find(localId);
      if (state.empty())
      {
        if (found != identities.end())
        {
          const auto entity = found->second;
          Unregister(entity);
          shard.RemoveEntity(entity);
        }
        continue;
      }
      if (state.size() < MinStateBytes)
        continue;
      const auto x = Coordinate(state, 0);
      const auto y = Coordinate(state, 1);
      const auto z = Coordinate(state, 2);
      if (!std::isfinite(x) || !std::isfinite(y) || !std::isfinite(z))
        continue;
      AtlasNet::AtlasNetEntityID entity;
      if (found == identities.end())
      {
        entity = shard.RegisterEntity({.x = x, .y = y, .z = z});
        identities.emplace(localId, entity);
        actors.emplace(entity, Actor{localId});
      }
      else
        entity = found->second;
      if (shard.IsEntityMigrationPending(entity))
        continue;
      auto& actor = actors.at(entity);
      const std::array position{x, y, z};
      if (actor.position != position || actor.pose.empty())
      {
        actor.position = position;
        shard.UpdateEntityTransform(entity, {.x = x, .y = y, .z = z});
      }
      actor.pose.clear();
      MinecraftWire::AppendBigEndian(actor.pose, entity.value());
      MinecraftWire::AppendBigEndian(actor.pose, ++actor.revision);
      actor.pose.insert(actor.pose.end(), state.begin(), state.end());
      const AtlasNet::Spatial::Query audience{.world = world,
          .shape = AtlasNet::Spatial::Sphere{.center = position, .radius = 80}};
      shard.DispatchSignal(audience, Update, std::as_bytes(std::span(actor.pose)));
    }
  }

  void AppendNearby(std::vector<std::uint8_t>& result, double x, double y, double z, double radius) const
  {
    for (const auto& [entity, actor] : actors)
    {
      const auto dx = actor.position[0] - x;
      const auto dy = actor.position[1] - y;
      const auto dz = actor.position[2] - z;
      if (!actor.pose.empty() && dx * dx + dy * dy + dz * dz <= radius * radius)
      {
        MinecraftWire::AppendBigEndian(result, static_cast<std::uint32_t>(actor.pose.size()));
        result.insert(result.end(), actor.pose.begin(), actor.pose.end());
      }
    }
  }

  bool Prepare(FabricServerHost& server, AtlasNet::AtlasNetEntityID entity, std::vector<std::byte>& payload)
  {
    const auto& actor = actors.at(entity);
    auto checkpoint = server.FreezeActor(actor.localId);
    if (checkpoint.empty())
      return false;
    std::vector<std::uint8_t> header;
    MinecraftWire::AppendBigEndian(header, CheckpointMagic);
    MinecraftWire::AppendBigEndian(header, actor.revision);
    const auto headerBytes = std::as_bytes(std::span(header));
    const auto checkpointBytes = std::as_bytes(std::span(checkpoint));
    payload.assign(headerBytes.begin(), headerBytes.end());
    payload.insert(payload.end(), checkpointBytes.begin(), checkpointBytes.end());
    return true;
  }

  static bool IsCheckpoint(std::span<const std::byte> payload)
  {
    return payload.size() > PoseHeader && MinecraftWire::ReadBigEndian<std::uint64_t>(payload) == CheckpointMagic;
  }

  bool Stage(FabricServerHost& server, AtlasNet::AtlasNetEntityID entity, const std::string& transfer,
             std::span<const std::byte> payload)
  {
    if (Contains(entity) || HasIncoming(entity) ||
        !server.StageActor(transfer, {reinterpret_cast<const std::uint8_t*>(payload.data()) + PoseHeader,
                                      payload.size() - PoseHeader}))
      return false;
    incoming.emplace(entity, MinecraftWire::ReadBigEndian<std::uint64_t>(payload.subspan(sizeof(std::uint64_t))));
    return true;
  }

  void Extract(FabricServerHost& server, AtlasNet::AtlasNetEntityID entity, bool commit)
  {
    server.ReleaseActor(actors.at(entity).localId, commit ? JNI_TRUE : JNI_FALSE);
    if (commit)
      Unregister(entity);
  }

  bool Restore(FabricServerHost& server, AtlasNet::AtlasNetEntityID entity, const std::string& transfer)
  {
    const auto localId = server.ActivateActor(transfer);
    if (localId == 0)
      return false;
    identities.emplace(localId, entity);
    actors.emplace(entity, Actor{.localId = localId, .revision = incoming.at(entity)});
    incoming.erase(entity);
    return true;
  }

  void Abort(FabricServerHost& server, AtlasNet::AtlasNetEntityID entity, const std::string& transfer)
  {
    if (incoming.erase(entity))
      server.AbortActor(transfer);
  }

private:
  static constexpr std::uint64_t CheckpointMagic = 0x4d434143544f5201;

  static double Coordinate(std::span<const std::uint8_t> state, std::size_t axis)
  {
    return std::bit_cast<double>(MinecraftWire::ReadBigEndian<std::uint64_t>(
        std::as_bytes(state.subspan(axis * sizeof(double)))));
  }

  struct Actor
  {
    std::int32_t localId;
    std::uint64_t revision = 0;
    std::array<double, 3> position{};
    std::vector<std::uint8_t> pose;
  };
  std::unordered_map<AtlasNet::AtlasNetEntityID, Actor> actors;
  std::unordered_map<std::int32_t, AtlasNet::AtlasNetEntityID> identities;
  std::unordered_map<AtlasNet::AtlasNetEntityID, std::uint64_t> incoming;
};
