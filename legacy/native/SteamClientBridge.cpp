#include "AtlasNet/Client/AtlasNetClient.hpp"
#include "MinecraftWire.hpp"
#include "SteamIngressTransport.hpp"

#include <jni.h>
#include <memory>
#include <deque>
#include <stdexcept>

namespace
{
using Client = AtlasNet::Client::AtlasNetClient;

std::uint64_t ReadEpoch(std::span<const std::byte> bytes)
{
  std::uint64_t epoch = 0;
  for (auto byte : bytes.first(sizeof(epoch)))
    epoch = (epoch << 8) | std::to_integer<std::uint64_t>(byte);
  return epoch;
}

struct Session
{
  Client client{std::make_unique<Minecraft::SteamIngressTransport>()};
  std::deque<std::pair<std::uint32_t, std::vector<std::byte>>> packets;
  std::vector<std::byte> partialPacket;
  std::vector<std::byte> partialTerrainPacket;
  std::uint64_t playEpoch = 0;

  void AcceptPlayFragment(std::span<const std::byte> payload, bool terrain)
  {
    constexpr auto header = 1 + sizeof(std::uint64_t);
    if (payload.size() <= header || (payload.front() != std::byte{0} && payload.front() != std::byte{1}))
      throw std::runtime_error("Invalid AtlasNet Minecraft packet fragment");
    const auto epoch = ReadEpoch(payload.subspan(1));
    if (epoch < playEpoch || (terrain && epoch != playEpoch))
      return;
    if (epoch > playEpoch)
    {
      playEpoch = epoch;
      partialPacket.clear();
      partialTerrainPacket.clear();
      packets.emplace_back(0x4d43010du,
          std::vector<std::byte>(payload.begin() + 1, payload.begin() + header));
    }
    auto& partial = terrain ? partialTerrainPacket : partialPacket;
    if (partial.size() + payload.size() - header > 8 * 1024 * 1024)
      throw std::runtime_error("Invalid AtlasNet Minecraft packet fragment");
    partial.insert(partial.end(), payload.begin() + header, payload.end());
    if (payload.front() == std::byte{1})
    {
      packets.emplace_back(0x4d430105, std::move(partial));
      partial.clear();
    }
  }

  Session()
  {
    client.OnSignal(0x4d430105, [this](std::span<const std::byte> payload) { AcceptPlayFragment(payload, false); });
    client.OnSignal(MinecraftWire::PlayTerrain, [this](std::span<const std::byte> payload) { AcceptPlayFragment(payload, true); });
    for (auto id : {0x4d430101u, 0x4d430102u, 0x4d430103u, 0x4d430106u, 0x4d430107u,
                    0x4d430108u, 0x4d430109u, 0x4d43010au, 0x4d43010bu,
                    MinecraftWire::WorldActorShow, MinecraftWire::WorldActorUpdate})
      client.OnSignal(id, [this, id](std::span<const std::byte> payload) {
        packets.emplace_back(id, std::vector<std::byte>(payload.begin(), payload.end()));
      });
    for (auto id : {0x4d430104u, 0x4d43010cu})
      client.OnSignal(id, [this, id](std::span<const std::byte> payload) {
        if (payload.size() <= sizeof(playEpoch) || ReadEpoch(payload) != playEpoch)
          return;
        packets.emplace_back(id, std::vector<std::byte>(payload.begin() + sizeof(playEpoch), payload.end()));
      });
  }
};

void Report(JNIEnv* env, const std::exception& error)
{
  if (!env->ExceptionCheck())
    env->ThrowNew(env->FindClass("java/io/IOException"), error.what());
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_net_atlasnet_minecraft_fabric_client_AtlasNetConnection_open(
    JNIEnv* env, jclass, jstring address)
{
  const char* endpoint = env->GetStringUTFChars(address, nullptr);
  if (!endpoint)
    return 0;
  try
  {
    const AtlasNet::Network::SocketAddress resolved(endpoint);
    auto session = std::make_unique<Session>();
    if (!session->client.Connect(resolved))
      throw std::runtime_error(session->client.GetLastError());
    env->ReleaseStringUTFChars(address, endpoint);
    return reinterpret_cast<jlong>(session.release());
  }
  catch (const std::exception& error)
  {
    env->ReleaseStringUTFChars(address, endpoint);
    Report(env, error);
    return 0;
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_atlasnet_minecraft_fabric_client_AtlasNetConnection_poll(
    JNIEnv* env, jclass, jlong handle)
{
  try
  {
    auto& client = reinterpret_cast<Session*>(handle)->client;
    client.PollEvents();
    return client.IsConnected();
  }
  catch (const std::exception& error)
  {
    Report(env, error);
    return JNI_FALSE;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_net_atlasnet_minecraft_fabric_client_AtlasNetConnection_close(
    JNIEnv*, jclass, jlong handle)
{
  delete reinterpret_cast<Session*>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_net_atlasnet_minecraft_fabric_client_AtlasNetConnection_ready(
    JNIEnv* env, jclass, jlong handle)
{
  try
  {
    reinterpret_cast<Session*>(handle)->client.DispatchRawCommand(0x4d430002, {});
  }
  catch (const std::exception& error)
  {
    Report(env, error);
  }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_net_atlasnet_minecraft_fabric_client_AtlasNetConnection_nextPacket(
    JNIEnv* env, jclass, jlong handle)
{
  auto& packets = reinterpret_cast<Session*>(handle)->packets;
  if (packets.empty())
    return nullptr;
  const auto& [id, packet] = packets.front();
  auto bytes = env->NewByteArray(static_cast<jsize>(packet.size() + 4));
  if (bytes)
  {
    const jbyte header[] = {static_cast<jbyte>(id >> 24), static_cast<jbyte>(id >> 16),
                            static_cast<jbyte>(id >> 8), static_cast<jbyte>(id)};
    env->SetByteArrayRegion(bytes, 0, 4, header);
    env->SetByteArrayRegion(bytes, 4, static_cast<jsize>(packet.size()),
                           reinterpret_cast<const jbyte*>(packet.data()));
  }
  packets.pop_front();
  return bytes;
}

extern "C" JNIEXPORT void JNICALL
Java_net_atlasnet_minecraft_fabric_client_AtlasNetConnection_sendCommand(
    JNIEnv* env, jclass, jlong handle, jint id, jbyteArray payload)
{
  try
  {
    std::vector<std::byte> bytes(env->GetArrayLength(payload));
    env->GetByteArrayRegion(payload, 0, static_cast<jsize>(bytes.size()),
                            reinterpret_cast<jbyte*>(bytes.data()));
    if (!env->ExceptionCheck())
      // Input and teleport acknowledgements must not wait behind a gameplay
      // batch during handoff. Retain their shared reliable ordered stream.
      reinterpret_cast<Session*>(handle)->client.DispatchRawCommand(static_cast<std::uint32_t>(id), bytes,
          {.batching = AtlasNet::Module::GameplayBatching::Immediate});
  }
  catch (const std::exception& error)
  {
    Report(env, error);
  }
}
