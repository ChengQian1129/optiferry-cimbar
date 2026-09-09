#pragma once
#include "bfb1.hpp"
#include <unordered_set>
namespace optiferry {
inline uint32_t sessionId32(const Meta &m, uint32_t index) {
  std::string tag = "OptiFerry-AFL2-session-v1";
  Bytes b(tag.begin(), tag.end());
  b.insert(b.end(), m.batch.begin(), m.batch.end());
  size_t p = b.size();
  b.resize(p + 8);
  put(b, p, m.salt, 4);
  put(b, p + 4, index, 4);
  auto h = sha(b);
  return uint32_t(h[0]) | (uint32_t(h[1]) << 8) | (uint32_t(h[2]) << 16) |
         (uint32_t(h[3]) << 24);
}
// AFL2 serializes 16 bits. Keep the specified 32-bit derivation internally and
// additionally reject collisions in the actual wire namespace.
inline std::vector<uint16_t> sessions(Meta &m) {
  if (m.count > 1536)
    throw std::runtime_error(
        "Too many segments for collision-free AFL2 sessions. Increase "
        "--segment-size (maximum 32MiB). This build supports at most 1536 "
        "segments.");
  std::vector<uint16_t> ids(m.count);
  std::array<uint32_t, 65536> seen{};
  for (uint32_t salt = 0; salt < 4000000; salt++) {
    m.salt = salt;
    bool valid = true;
    for (uint32_t i = 0; i < m.count; i++) {
      uint16_t id = uint16_t(sessionId32(m, i));
      if (!id || seen[id] == salt + 1) {
        valid = false;
        break;
      }
      seen[id] = salt + 1;
      ids[i] = id;
    }
    if (valid)
      return ids;
  }
  throw std::runtime_error("Unable to allocate AFL2 sessions within "
                           "preparation limit; increase --segment-size.");
}
} // namespace optiferry
