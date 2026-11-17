#pragma once

#include <cstddef>
#include <cstdint>
#include <span>
#include <vector>

namespace MinecraftWire
{
inline constexpr std::uint32_t WorldActorShow = 0x4d43010e;
inline constexpr std::uint32_t WorldActorUpdate = 0x4d43010f;

template <typename Integer>
void AppendBigEndian(std::vector<std::uint8_t>& bytes, Integer value)
{
  for (std::size_t index = sizeof(Integer); index > 0; --index)
    bytes.push_back(static_cast<std::uint8_t>(value >> ((index - 1) * 8)));
}

template <typename Integer>
Integer ReadBigEndian(std::span<const std::byte> bytes)
{
  Integer value = 0;
  for (const auto byte : bytes.first(sizeof(value)))
    value = (value << 8) | std::to_integer<Integer>(byte);
  return value;
}
}
