#pragma once

#include "AtlasNet/Client/IClientTransport.hpp"
#include "AtlasNet/Core/Network/Transport/Steam/SteamPayloadSize.hpp"
#include "AtlasNet/Core/Serialization/NetBinarySerializer.hpp"

#include <steam/isteamnetworkingsockets.h>
#include <chrono>
#include <stdexcept>
#include <thread>

namespace Minecraft
{
// SteamNetSock ingress carries a stream of AtlasNet packets in Steam messages.
// AtlasNetClient owns the handshake, the command protocol and the signal codec.
class SteamIngressTransport final : public AtlasNet::Client::IClientTransport
{
public:
  SteamIngressTransport()
  {
    static const SteamRuntime runtime;
  }

  ~SteamIngressTransport() override { Disconnect(); }

  bool Connect(const AtlasNet::Network::SocketAddress& endpoint) override
  {
    Disconnect();
    auto resolved = endpoint.Resolve();
    SteamNetworkingIPAddr remote{};
    if (resolved.IsIPv4())
      remote.SetIPv4(resolved.get_ipv4().to_uint32(), resolved.get_port());
    else if (resolved.IsIPv6())
      remote.SetIPv6(resolved.get_ipv6().get_bytes().data(), resolved.get_port());
    else
      return false;

    handle = SteamNetworkingSockets()->ConnectByIPAddress(remote, 0, nullptr);
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
    while (handle != k_HSteamNetConnection_Invalid && std::chrono::steady_clock::now() < deadline)
    {
      Poll();
      if (connected)
        return true;
      std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    Disconnect();
    return false;
  }

  bool IsConnected() const override { return connected; }

  AtlasNet::Network::PayloadSize::Limit GetMaxPayloadSize() const override
  {
    return handle == k_HSteamNetConnection_Invalid
        ? AtlasNet::Network::SteamClientPayloadSize(k_ESteamNetworkingConfig_Global, 0)
        : AtlasNet::Network::SteamClientPayloadSize(k_ESteamNetworkingConfig_Connection, handle);
  }

  bool Send(std::span<const std::byte> packet) override { return Send(packet, {}); }

  bool Send(std::span<const std::byte> packet, AtlasNet::Module::GameplayOptions options) override
  {
    using namespace AtlasNet::Module;
    if (!connected || packet.size() < HeaderSize ||
        !AtlasNet::Network::PayloadSize::Fits(GetMaxPayloadSize(), packet.size() - HeaderSize))
      return false;
    const bool reliable = options.delivery == GameplayDelivery::Reliable;
    const bool immediate = options.batching == GameplayBatching::Immediate;
    const int flags = reliable
        ? (immediate ? k_nSteamNetworkingSend_ReliableNoNagle : k_nSteamNetworkingSend_Reliable)
        : (immediate ? k_nSteamNetworkingSend_UnreliableNoDelay : k_nSteamNetworkingSend_Unreliable);
    return SteamNetworkingSockets()->SendMessageToConnection(
        handle, packet.data(), static_cast<uint32_t>(packet.size()), flags, nullptr) == k_EResultOK;
  }

  void Poll() override
  {
    if (handle == k_HSteamNetConnection_Invalid)
      return;
    auto& sockets = *SteamNetworkingSockets();
    sockets.RunCallbacks();
    SteamNetConnectionInfo_t info{};
    if (!sockets.GetConnectionInfo(handle, &info) ||
        info.m_eState == k_ESteamNetworkingConnectionState_ClosedByPeer ||
        info.m_eState == k_ESteamNetworkingConnectionState_ProblemDetectedLocally)
      Disconnect();
    else
      connected = info.m_eState == k_ESteamNetworkingConnectionState_Connected;
  }

  std::optional<std::vector<std::byte>> Receive() override
  {
    while (connected)
    {
      if (wire.size() >= HeaderSize)
      {
        AtlasNet::Network::Client::V1::ClientPacketHeader header{};
        AtlasNet::NetBinaryReader reader(wire);
        reader(header);
        if (reader->adapter().error() != bitsery::ReaderError::NoError ||
            !AtlasNet::Network::PayloadSize::Fits(GetMaxPayloadSize(), header.payloadSize))
        {
          Disconnect();
          return std::nullopt;
        }
        if (const auto end = HeaderSize + header.payloadSize; wire.size() >= end)
        {
          std::vector<std::byte> result(wire.begin(), wire.begin() + end);
          wire.erase(wire.begin(), wire.begin() + end);
          return result;
        }
      }

      SteamNetworkingMessage_t* message = nullptr;
      const auto count = SteamNetworkingSockets()->ReceiveMessagesOnConnection(handle, &message, 1);
      if (count < 0)
        Disconnect();
      if (count <= 0)
        break;
      const std::unique_ptr<SteamNetworkingMessage_t, decltype(&Release)> owned(message, Release);
      if (owned->m_cbSize > 0)
      {
        const auto* first = static_cast<const std::byte*>(owned->m_pData);
        wire.insert(wire.end(), first, first + owned->m_cbSize);
      }
    }
    return std::nullopt;
  }

  void Disconnect() override
  {
    if (handle != k_HSteamNetConnection_Invalid)
      SteamNetworkingSockets()->CloseConnection(handle, 0, "Client disconnected", false);
    handle = k_HSteamNetConnection_Invalid;
    connected = false;
    wire.clear();
  }

private:
  static constexpr auto HeaderSize = AtlasNet::Network::Client::V1::ClientPacketHeaderWireSize;
  static void Release(SteamNetworkingMessage_t* message) { message->Release(); }

  struct SteamRuntime
  {
    SteamRuntime()
    {
      SteamNetworkingErrMsg error{};
      if (!GameNetworkingSockets_Init(nullptr, error))
        throw std::runtime_error(error);
    }
    ~SteamRuntime() { GameNetworkingSockets_Kill(); }
  };

  HSteamNetConnection handle = k_HSteamNetConnection_Invalid;
  bool connected = false;
  std::vector<std::byte> wire;
};
}
