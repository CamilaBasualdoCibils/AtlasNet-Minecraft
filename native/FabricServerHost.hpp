#pragma once

#include "AtlasNet/Module/Region.hpp"

#include <jni.h>
#include <algorithm>
#include <array>
#include <cstddef>
#include <optional>
#include <span>
#include <cstdint>
#include <cstdlib>

#include <filesystem>
#include <fstream>
#include <format>
#include <limits>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

class FabricServerHost
{
public:
  void Start(const std::string& shardId, bool valkeyTerrain = false,
             const std::optional<AtlasNet::Module::RegionShape>& terrainBounds = {}, bool liveTerrain = false)
  {
    std::ifstream launch(MINECRAFT_SHARD_LAUNCH_FILE);
    std::string runDirectory;
    std::string mainClass;
    if (!std::getline(launch, runDirectory) || !std::getline(launch, mainClass))
      throw std::runtime_error("Missing Fabric shard launch configuration. Run BuildServer.");
    std::replace(mainClass.begin(), mainClass.end(), '.', '/');

    std::vector<std::string> vmOptions;
    std::vector<std::string> arguments;
    bool readingOptions = true;
    for (std::string line; std::getline(launch, line);)
    {
      if (readingOptions && line == "--")
        readingOptions = false;
      else if (readingOptions)
        vmOptions.push_back(std::move(line));
      else
        arguments.push_back(std::move(line));
    }
    if (readingOptions)
      throw std::runtime_error("Invalid Fabric shard launch configuration");
    if (valkeyTerrain)
      vmOptions.emplace_back("-Datlasnet.minecraft.valkeyTerrain=true");
    if (liveTerrain)
      vmOptions.emplace_back("-Datlasnet.minecraft.liveTerrain=true");
    if (terrainBounds)
    {
      const auto coordinate = [](double value) -> std::string {
        if (std::isinf(value))
          return value < 0 ? "-Infinity" : "Infinity";
        return std::format("{:.17g}", value);
      };
      std::string option = "-Datlasnet.minecraft.terrainBounds=";
      const auto values = TerrainBounds(*terrainBounds);
      for (std::size_t index = 0; index < values.size(); ++index)
      {
        if (index != 0)
          option += ',';
        option += coordinate(values[index]);
      }
      vmOptions.push_back(std::move(option));
    }
    else if (liveTerrain)
      vmOptions.emplace_back("-Datlasnet.minecraft.terrainBounds=0,0,0,0");

    namespace fs = std::filesystem;
    const auto shared = fs::path(runDirectory);
    const auto* preset = std::getenv("MINECRAFT_TERRAIN_PRESET");
    if (preset && std::string_view(preset) != "normal" && std::string_view(preset) != "flat")
      throw std::runtime_error("MINECRAFT_TERRAIN_PRESET must be normal or flat");
    const bool normal = !preset || std::string_view(preset) == "normal";
    const auto instance = shared / (valkeyTerrain
        ? (normal ? "valkey-normal-shards" : "valkey-shards")
        : (normal ? "normal-shards" : "shards")) / shardId;
    fs::create_directories(instance);
    if (fs::exists(shared / "eula.txt"))
      fs::copy_file(shared / "eula.txt", instance / "eula.txt", fs::copy_options::overwrite_existing);
    if (!fs::exists(instance / "server.properties"))
    {
      std::ofstream settings(instance / "server.properties");
      settings << "view-distance=5\nsimulation-distance=5\n";
      if (normal)
        settings << "level-type=minecraft:normal\n";
      else
        settings << "level-type=minecraft:flat\n"
                 << R"(generator-settings={"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}]})"
                 << '\n';
      if (valkeyTerrain)
        settings << (normal ? "level-seed=3955\n" : "level-seed=0\n");
    }
    fs::current_path(instance);

    std::vector<JavaVMOption> options;
    for (auto& value : vmOptions)
      options.push_back({value.data(), nullptr});
    JavaVMInitArgs init{JNI_VERSION_21, static_cast<jint>(options.size()), options.data(), JNI_FALSE};
    if (JNI_CreateJavaVM(&vm, reinterpret_cast<void**>(&env), &init) != JNI_OK)
      throw std::runtime_error("Could not start the Fabric server JVM");

    auto launcher = env->FindClass(mainClass.c_str());
    CheckJava();
    auto main = env->GetStaticMethodID(launcher, "main", "([Ljava/lang/String;)V");
    CheckJava();
    auto stringClass = env->FindClass("java/lang/String");
    auto args = env->NewObjectArray(static_cast<jsize>(arguments.size()), stringClass, nullptr);
    CheckJava();
    for (jsize index = 0; index < static_cast<jsize>(arguments.size()); ++index)
    {
      auto value = env->NewStringUTF(arguments[index].c_str());
      env->SetObjectArrayElement(args, index, value);
      env->DeleteLocalRef(value);
      CheckJava();
    }
    env->CallStaticVoidMethod(launcher, main, args);
    CheckJava();
  }

  bool Spawn(jlong clientId, jlong entityId, const std::string& dimension, double x, double y, double z)
  {
    const auto bridge = PlayerBridge();
    auto spawn = env->GetStaticMethodID(bridge, "spawn", "(JJLjava/lang/String;DDD)Z");
    CheckJava();
    auto dimensionName = env->NewStringUTF(dimension.c_str());
    CheckJava();
    const auto result = env->CallStaticBooleanMethod(bridge, spawn, clientId, entityId, dimensionName, x, y, z);
    env->DeleteLocalRef(dimensionName);
    CheckJava();
    return result;
  }

  void Remove(jlong entityId)
  {
    const auto bridge = PlayerBridge();
    auto remove = env->GetStaticMethodID(bridge, "remove", "(J)V");
    CheckJava();
    env->CallStaticVoidMethod(bridge, remove, entityId);
    CheckJava();
  }

  std::optional<std::vector<std::byte>> FreezePlayer(jlong entity, jint budget)
  {
    const auto bridge = PlayerBridge();
    auto method = env->GetStaticMethodID(bridge, "freezePlayer", "(JI)[B");
    CheckJava();
    auto data = static_cast<jbyteArray>(env->CallStaticObjectMethod(bridge, method, entity, budget));
    CheckJava();
    return CopyPacket(data);
  }

  bool StagePlayer(jlong client, jlong entity, std::span<const std::byte> state)
  {
    const auto bridge = PlayerBridge();
    auto method = env->GetStaticMethodID(bridge, "stagePlayer", "(JJ[B)Z");
    CheckJava();
    auto data = env->NewByteArray(static_cast<jsize>(state.size()));
    CheckJava();
    env->SetByteArrayRegion(data, 0, static_cast<jsize>(state.size()),
                           reinterpret_cast<const jbyte*>(state.data()));
    const auto staged = env->CallStaticBooleanMethod(bridge, method, client, entity, data);
    env->DeleteLocalRef(data);
    CheckJava();
    return staged;
  }

  bool ActivatePlayer(jlong entity) { return FinishPlayerTransfer("activatePlayer", entity); }
  bool ExtractPlayer(jlong entity) { return FinishPlayerTransfer("extractPlayer", entity); }

  void AbortPlayerTransfer(jlong entity, jboolean source)
  {
    const auto bridge = PlayerBridge();
    auto method = env->GetStaticMethodID(bridge, "abortPlayerTransfer", "(JZ)V");
    CheckJava();
    env->CallStaticVoidMethod(bridge, method, entity, source);
    CheckJava();
  }

  bool Started() const { return env != nullptr; }

  std::optional<std::array<jint, 4>> TakeChunkDemand(jlong entity)
  {
    auto bridge = PlayerBridge();
    auto method = env->GetStaticMethodID(bridge, "takeChunkDemand", "(J)[I");
    CheckJava();
    auto data = static_cast<jintArray>(env->CallStaticObjectMethod(bridge, method, entity));
    CheckJava();
    if (!data)
      return std::nullopt;
    std::array<jint, 4> demand;
    env->GetIntArrayRegion(data, 0, demand.size(), demand.data());
    env->DeleteLocalRef(data);
    CheckJava();
    return demand;
  }

  void ApplyChunkDemand(jlong entity, jlong revision, jint x, jint z, jint view, jint simulation,
                        std::span<const jboolean> owned, jboolean refresh)
  {
    auto bridge = PlayerBridge();
    auto method = env->GetStaticMethodID(bridge, "applyChunkDemand", "(JJIIII[ZZ)V");
    CheckJava();
    auto mask = env->NewBooleanArray(static_cast<jsize>(owned.size()));
    CheckJava();
    if (!owned.empty())
      env->SetBooleanArrayRegion(mask, 0, static_cast<jsize>(owned.size()), owned.data());
    env->CallStaticVoidMethod(bridge, method, entity, revision, x, z, view, simulation, mask, refresh);
    env->DeleteLocalRef(mask);
    CheckJava();
  }

  std::vector<jint> TakeChunkLifecycle()
  {
    auto bridge = PlayerBridge();
    auto method = env->GetStaticMethodID(bridge, "takeChunkLifecycle", "()[I");
    CheckJava();
    auto data = static_cast<jintArray>(env->CallStaticObjectMethod(bridge, method));
    CheckJava();
    if (!data)
      return {};
    std::vector<jint> events(env->GetArrayLength(data));
    env->GetIntArrayRegion(data, 0, static_cast<jsize>(events.size()), events.data());
    env->DeleteLocalRef(data);
    CheckJava();
    return events;
  }

  void RevokeTerrain()
  {
    auto bridge = PlayerBridge();
    auto method = env->GetStaticMethodID(bridge, "revokeTerrain", "()V");
    CheckJava();
    env->CallStaticVoidMethod(bridge, method);
    CheckJava();
  }

  bool SetTerrainRegion(const std::optional<AtlasNet::Module::RegionShape>& region)
  {
    auto bridge = PlayerBridge();
    auto method = env->GetStaticMethodID(bridge, "terrainRegion", "([D)Z");
    CheckJava();
    jdoubleArray bounds = nullptr;
    if (region)
    {
      const auto values = TerrainBounds(*region);
      const auto count = static_cast<jsize>(values.size());
      bounds = env->NewDoubleArray(count);
      CheckJava();
      env->SetDoubleArrayRegion(bounds, 0, count, values.data());
      CheckJava();
    }
    const auto applied = env->CallStaticBooleanMethod(bridge, method, bounds);
    env->DeleteLocalRef(bounds);
    CheckJava();
    return applied;
  }

  void TerrainVacated(jint x, jint z, bool previousOwner)
  {
    auto bridge = PlayerBridge();
    auto method = env->GetStaticMethodID(bridge, "terrainVacated", "(IIZ)V");
    CheckJava();
    env->CallStaticVoidMethod(bridge, method, x, z, static_cast<jboolean>(previousOwner));
    CheckJava();
  }

  std::optional<std::vector<std::byte>> PrepareChunk(jint x, jint z, const std::string& transfer)
  {
    auto bridge = PlayerBridge();
    auto method = env->GetStaticMethodID(bridge, "prepareChunk", "(IILjava/lang/String;)[B");
    CheckJava();
    const auto id = env->NewStringUTF(transfer.c_str());
    CheckJava();
    auto data = static_cast<jbyteArray>(env->CallStaticObjectMethod(bridge, method, x, z, id));
    env->DeleteLocalRef(id);
    CheckJava();
    return CopyPacket(data);
  }

  bool StageChunk(const std::string& transfer, std::span<const std::byte> checkpoint)
  {
    auto bridge = PlayerBridge();
    auto method = env->GetStaticMethodID(bridge, "stageChunk", "(Ljava/lang/String;[B)Z");
    CheckJava();
    const auto id = env->NewStringUTF(transfer.c_str());
    CheckJava();
    auto data = env->NewByteArray(static_cast<jsize>(checkpoint.size()));
    CheckJava();
    env->SetByteArrayRegion(data, 0, static_cast<jsize>(checkpoint.size()),
                           reinterpret_cast<const jbyte*>(checkpoint.data()));
    CheckJava();
    const auto staged = env->CallStaticBooleanMethod(bridge, method, id, data);
    env->DeleteLocalRef(data);
    env->DeleteLocalRef(id);
    CheckJava();
    return staged;
  }

  bool CommitChunk(const std::string& transfer)
  {
    auto bridge = PlayerBridge();
    auto method = env->GetStaticMethodID(bridge, "commitChunk", "(Ljava/lang/String;)Z");
    CheckJava();
    const auto id = env->NewStringUTF(transfer.c_str());
    CheckJava();
    const auto result = env->CallStaticBooleanMethod(bridge, method, id);
    env->DeleteLocalRef(id);
    CheckJava();
    return result;
  }

  void ExtractChunk(jint x, jint z, const std::string& transfer, bool commit)
  {
    auto bridge = PlayerBridge();
    auto method = env->GetStaticMethodID(bridge, "extractChunk", "(IILjava/lang/String;Z)V");
    CheckJava();
    const auto id = env->NewStringUTF(transfer.c_str());
    CheckJava();
    env->CallStaticVoidMethod(bridge, method, x, z, id, static_cast<jboolean>(commit));
    env->DeleteLocalRef(id);
    CheckJava();
  }

  void AbortChunk(const std::string& transfer)
  {
    auto bridge = PlayerBridge();
    auto method = env->GetStaticMethodID(bridge, "abortChunk", "(Ljava/lang/String;)V");
    CheckJava();
    const auto id = env->NewStringUTF(transfer.c_str());
    CheckJava();
    env->CallStaticVoidMethod(bridge, method, id);
    env->DeleteLocalRef(id);
    CheckJava();
  }

  std::optional<std::vector<std::byte>> NextPacket(jlong entityId)
  {
    auto bridge = PlayerBridge();
    auto next = env->GetStaticMethodID(bridge, "nextPacket", "(J)[B");
    CheckJava();
    auto data = static_cast<jbyteArray>(env->CallStaticObjectMethod(bridge, next, entityId));
    CheckJava();
    return CopyPacket(data);
  }

  std::optional<std::vector<std::byte>> NextTerrainSnapshot()
  {
    auto bridge = PlayerBridge();
    auto next = env->GetStaticMethodID(bridge, "nextTerrainSnapshot", "()[B");
    CheckJava();
    auto data = static_cast<jbyteArray>(env->CallStaticObjectMethod(bridge, next));
    CheckJava();
    return CopyPacket(data);
  }

  struct TerrainBreak
  {
    jlong entity;
    jint x, y, z, sequence;
  };

  std::optional<TerrainBreak> NextTerrainBreak()
  {
    auto bridge = PlayerBridge();
    auto method = env->GetStaticMethodID(bridge, "nextRemoteBreak", "()[J");
    CheckJava();
    auto data = static_cast<jlongArray>(env->CallStaticObjectMethod(bridge, method));
    CheckJava();
    if (!data)
      return std::nullopt;
    std::array<jlong, 5> values;
    env->GetLongArrayRegion(data, 0, values.size(), values.data());
    env->DeleteLocalRef(data);
    CheckJava();
    return TerrainBreak{values[0], static_cast<jint>(values[1]), static_cast<jint>(values[2]),
                        static_cast<jint>(values[3]), static_cast<jint>(values[4])};
  }

  jint BreakTerrainBlock(jint x, jint y, jint z)
  {
    auto bridge = PlayerBridge();
    auto method = env->GetStaticMethodID(bridge, "breakTerrainBlock", "(III)I");
    CheckJava();
    const auto result = env->CallStaticIntMethod(bridge, method, x, y, z);
    CheckJava();
    return result;
  }

  void CompleteTerrainBreak(const TerrainBreak& action, jint state)
  {
    auto bridge = PlayerBridge();
    auto method = env->GetStaticMethodID(bridge, "completeRemoteBreak", "(JIIIII)V");
    CheckJava();
    env->CallStaticVoidMethod(bridge, method, action.entity, action.x, action.y, action.z,
                              action.sequence, state);
    CheckJava();
  }

  jint TrackingRange()
  {
    auto bridge = PlayerBridge();
    auto method = env->GetStaticMethodID(bridge, "trackingRange", "()I");
    CheckJava();
    auto range = env->CallStaticIntMethod(bridge, method);
    CheckJava();
    return range;
  }

  std::optional<std::array<double, 7>> TakePose(jlong entityId)
  {
    auto bridge = PlayerBridge();
    auto method = env->GetStaticMethodID(bridge, "takePose", "(J)[D");
    CheckJava();
    auto data = static_cast<jdoubleArray>(env->CallStaticObjectMethod(bridge, method, entityId));
    CheckJava();
    if (!data)
      return std::nullopt;
    std::array<double, 7> pose;
    env->GetDoubleArrayRegion(data, 0, pose.size(), pose.data());
    env->DeleteLocalRef(data);
    CheckJava();
    return pose;
  }

  void ReceivePacket(jlong entityId, std::span<const uint8_t> bytes, jint command)
  {
    auto bridge = PlayerBridge();
    auto receive = env->GetStaticMethodID(bridge, "receivePacket", "(J[BI)V");
    CheckJava();
    auto data = env->NewByteArray(static_cast<jsize>(bytes.size()));
    CheckJava();
    env->SetByteArrayRegion(data, 0, static_cast<jsize>(bytes.size()),
                            reinterpret_cast<const jbyte*>(bytes.data()));
    env->CallStaticVoidMethod(bridge, receive, entityId, data, command);
    env->DeleteLocalRef(data);
    CheckJava();
  }

  std::vector<std::uint8_t> PrepareMelee(jlong entity)
  {
    const auto bridge = PlayerBridge();
    const auto method = env->GetStaticMethodID(bridge, "prepareMelee", "(J)[B");
    CheckJava();
    return CopyBytes(static_cast<jbyteArray>(env->CallStaticObjectMethod(bridge, method, entity)));
  }

  std::vector<std::uint8_t> ApplyMelee(jlong target, std::span<const std::uint8_t> state)
  {
    const auto bridge = PlayerBridge();
    const auto method = env->GetStaticMethodID(bridge, "applyMelee", "(J[B)[B");
    CheckJava();
    const auto data = JavaBytes(state);
    const auto result = static_cast<jbyteArray>(env->CallStaticObjectMethod(bridge, method, target, data));
    env->DeleteLocalRef(data);
    return CopyBytes(result);
  }

  void FinishMelee(jlong entity, std::span<const std::uint8_t> state)
  {
    const auto bridge = PlayerBridge();
    const auto method = env->GetStaticMethodID(bridge, "finishMelee", "(J[B)V");
    CheckJava();
    const auto data = JavaBytes(state);
    env->CallStaticVoidMethod(bridge, method, entity, data);
    env->DeleteLocalRef(data);
    CheckJava();
  }

  std::vector<std::vector<std::uint8_t>> ActorFrames()
  {
    const auto bridge = PlayerBridge();
    const auto method = env->GetStaticMethodID(bridge, "actorFrames", "()[[B");
    CheckJava();
    const auto frames = static_cast<jobjectArray>(env->CallStaticObjectMethod(bridge, method));
    CheckJava();
    std::vector<std::vector<std::uint8_t>> result;
    const auto count = env->GetArrayLength(frames);
    result.reserve(count);
    for (jsize i = 0; i < count; ++i)
      result.push_back(CopyBytes(static_cast<jbyteArray>(env->GetObjectArrayElement(frames, i))));
    env->DeleteLocalRef(frames);
    return result;
  }

  std::vector<std::uint8_t> FreezeActor(jint id)
  {
    const auto bridge = PlayerBridge();
    const auto method = env->GetStaticMethodID(bridge, "freezeActor", "(I)[B");
    CheckJava();
    return CopyBytes(static_cast<jbyteArray>(env->CallStaticObjectMethod(bridge, method, id)));
  }

  void ReleaseActor(jint id, jboolean commit)
  {
    const auto bridge = PlayerBridge();
    const auto method = env->GetStaticMethodID(bridge, "releaseActor", "(IZ)V");
    CheckJava();
    env->CallStaticVoidMethod(bridge, method, id, commit);
    CheckJava();
  }

  bool StageActor(const std::string& transfer, std::span<const std::uint8_t> checkpoint)
  {
    const auto bridge = PlayerBridge();
    const auto method = env->GetStaticMethodID(bridge, "stageActor", "(Ljava/lang/String;[B)Z");
    CheckJava();
    const auto id = env->NewStringUTF(transfer.c_str());
    const auto data = JavaBytes(checkpoint);
    const auto result = env->CallStaticBooleanMethod(bridge, method, id, data);
    env->DeleteLocalRef(id);
    env->DeleteLocalRef(data);
    CheckJava();
    return result;
  }

  jint ActivateActor(const std::string& transfer)
  {
    const auto bridge = PlayerBridge();
    const auto method = env->GetStaticMethodID(bridge, "activateActor", "(Ljava/lang/String;)I");
    CheckJava();
    const auto id = env->NewStringUTF(transfer.c_str());
    const auto result = env->CallStaticIntMethod(bridge, method, id);
    env->DeleteLocalRef(id);
    CheckJava();
    return result;
  }

  void AbortActor(const std::string& transfer)
  {
    const auto bridge = PlayerBridge();
    const auto method = env->GetStaticMethodID(bridge, "abortActor", "(Ljava/lang/String;)V");
    CheckJava();
    const auto id = env->NewStringUTF(transfer.c_str());
    env->CallStaticVoidMethod(bridge, method, id);
    env->DeleteLocalRef(id);
    CheckJava();
  }

  std::optional<std::vector<std::byte>> Chat(jlong client, jlong entity, std::span<const uint8_t> bytes)
  {
    auto bridge = PlayerBridge();
    auto method = env->GetStaticMethodID(bridge, "chat", "(JJ[B)[B");
    CheckJava();
    auto data = env->NewByteArray(static_cast<jsize>(bytes.size()));
    CheckJava();
    env->SetByteArrayRegion(data, 0, static_cast<jsize>(bytes.size()),
                            reinterpret_cast<const jbyte*>(bytes.data()));
    auto result = static_cast<jbyteArray>(env->CallStaticObjectMethod(bridge, method, client, entity, data));
    env->DeleteLocalRef(data);
    CheckJava();
    return CopyPacket(result);
  }

  std::optional<std::vector<std::byte>> Presence(jlong client, jlong entity, jboolean joined)
  {
    auto bridge = PlayerBridge();
    auto method = env->GetStaticMethodID(bridge, "presence", "(JJZ)[B");
    CheckJava();
    auto result = static_cast<jbyteArray>(env->CallStaticObjectMethod(bridge, method, client, entity, joined));
    CheckJava();
    return CopyPacket(result);
  }

  std::optional<std::vector<std::byte>> ShardMetrics(const std::string& shardId)
  {
    auto bridge = PlayerBridge();
    auto method = env->GetStaticMethodID(bridge, "shardMetrics", "(Ljava/lang/String;)[B");
    CheckJava();
    auto id = env->NewStringUTF(shardId.c_str());
    CheckJava();
    auto result = static_cast<jbyteArray>(env->CallStaticObjectMethod(bridge, method, id));
    env->DeleteLocalRef(id);
    CheckJava();
    return CopyPacket(result);
  }

  std::optional<std::vector<std::byte>> Region(const AtlasNet::Module::RegionShape& region)
  {
    // RegionShape packs the plane into slots 0 and 1. The signal uses world XYZ.
    constexpr auto infinity = std::numeric_limits<double>::infinity();
    std::vector<jdouble> geometry{
        region.min[0], -infinity, region.min[1], region.max[0], infinity, region.max[1]};
    for (const auto& plane : region.planes)
    {
      geometry.insert(geometry.end(), {plane.normal[0], 0.0, plane.normal[1]});
      geometry.push_back(plane.offset);
      geometry.push_back(plane.exclusive ? 1 : 0);
    }
    auto bridge = PlayerBridge();
    auto method = env->GetStaticMethodID(bridge, "region", "(JJI[D)[B");
    CheckJava();
    auto data = env->NewDoubleArray(static_cast<jsize>(geometry.size()));
    CheckJava();
    env->SetDoubleArrayRegion(data, 0, static_cast<jsize>(geometry.size()), geometry.data());
    auto result = static_cast<jbyteArray>(env->CallStaticObjectMethod(bridge, method,
        static_cast<jlong>(region.id.value), static_cast<jlong>(region.generation),
        static_cast<jint>(region.dimension), data));
    env->DeleteLocalRef(data);
    CheckJava();
    return CopyPacket(result);
  }

  void Stop()
  {
    if (!env)
      return;
    // Minecraft installs its server shutdown hook in Main.
    auto system = env->FindClass("java/lang/System");
    auto exit = env->GetStaticMethodID(system, "exit", "(I)V");
    env->CallStaticVoidMethod(system, exit, 0);
    CheckJava();
  }

private:
  jbyteArray JavaBytes(std::span<const std::uint8_t> state)
  {
    const auto data = env->NewByteArray(static_cast<jsize>(state.size()));
    CheckJava();
    if (!state.empty())
      env->SetByteArrayRegion(data, 0, static_cast<jsize>(state.size()), reinterpret_cast<const jbyte*>(state.data()));
    CheckJava();
    return data;
  }

  std::vector<std::uint8_t> CopyBytes(jbyteArray data)
  {
    CheckJava();
    if (!data)
      return {};
    std::vector<std::uint8_t> state(env->GetArrayLength(data));
    env->GetByteArrayRegion(data, 0, static_cast<jsize>(state.size()), reinterpret_cast<jbyte*>(state.data()));
    env->DeleteLocalRef(data);
    CheckJava();
    return state;
  }

  static std::vector<double> TerrainBounds(const AtlasNet::Module::RegionShape& region)
  {
    std::vector<double> values{region.min[0], region.min[1], region.max[0], region.max[1]};
    values.reserve(4 + region.planes.size() * 4);
    for (const auto& plane : region.planes)
    {
      values.insert(values.end(), {plane.normal[0], plane.normal[1]});
      values.push_back(plane.offset);
      values.push_back(plane.exclusive ? 1.0 : 0.0);
    }
    return values;
  }

  std::optional<std::vector<std::byte>> CopyPacket(jbyteArray data)
  {
    if (!data)
      return std::nullopt;
    std::vector<std::byte> bytes(env->GetArrayLength(data));
    env->GetByteArrayRegion(data, 0, static_cast<jsize>(bytes.size()),
                            reinterpret_cast<jbyte*>(bytes.data()));
    env->DeleteLocalRef(data);
    CheckJava();
    return bytes;
  }

  bool FinishPlayerTransfer(const char* operation, jlong entity)
  {
    const auto bridge = PlayerBridge();
    auto method = env->GetStaticMethodID(bridge, operation, "(J)Z");
    CheckJava();
    const auto finished = env->CallStaticBooleanMethod(bridge, method, entity);
    CheckJava();
    return finished;
  }

  jclass PlayerBridge()
  {
    if (playerBridge)
      return playerBridge;
    auto launcherBase = env->FindClass("net/fabricmc/loader/impl/launch/FabricLauncherBase");
    CheckJava();
    auto launcherMethod = env->GetStaticMethodID(
        launcherBase, "getLauncher", "()Lnet/fabricmc/loader/impl/launch/FabricLauncher;");
    CheckJava();
    auto launcher = env->CallStaticObjectMethod(launcherBase, launcherMethod);
    CheckJava();
    auto loaderMethod = env->GetMethodID(env->GetObjectClass(launcher), "getTargetClassLoader",
                                        "()Ljava/lang/ClassLoader;");
    CheckJava();
    auto loader = env->CallObjectMethod(launcher, loaderMethod);
    CheckJava();
    auto loadClass = env->GetMethodID(env->GetObjectClass(loader), "loadClass",
                                      "(Ljava/lang/String;)Ljava/lang/Class;");
    CheckJava();
    auto name = env->NewStringUTF("net.atlasnet.minecraft.fabric.server.ShardPlayers");
    auto bridge = env->CallObjectMethod(loader, loadClass, name);
    CheckJava();
    playerBridge = static_cast<jclass>(env->NewGlobalRef(bridge));
    return playerBridge;
  }

  void CheckJava()
  {
    if (env->ExceptionCheck())
    {
      env->ExceptionDescribe();
      throw std::runtime_error("Fabric server JVM raised an exception");
    }
  }

  JavaVM* vm = nullptr;
  JNIEnv* env = nullptr;
  jclass playerBridge = nullptr;
};
