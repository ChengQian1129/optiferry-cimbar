#pragma once
#include "afl2.hpp"
#include <array>
#include <memory>
#include <openssl/evp.h>
#include <string>
namespace optiferry {
using Hash = std::array<uint8_t, 32>;
class Sha {
  std::unique_ptr<EVP_MD_CTX, decltype(&EVP_MD_CTX_free)> ctx{EVP_MD_CTX_new(),
                                                              EVP_MD_CTX_free};

public:
  Sha() {
    if (!ctx || EVP_DigestInit_ex(ctx.get(), EVP_sha256(), nullptr) != 1)
      throw std::runtime_error("SHA-256 initialization failed");
  }
  void add(const uint8_t *p, size_t n) {
    if (EVP_DigestUpdate(ctx.get(), p, n) != 1)
      throw std::runtime_error("SHA-256 update failed");
  }
  Hash finish() {
    Hash h;
    unsigned n;
    if (EVP_DigestFinal_ex(ctx.get(), h.data(), &n) != 1 || n != 32)
      throw std::runtime_error("SHA-256 final failed");
    return h;
  }
};
inline Hash sha(const uint8_t *p, size_t n) {
  Sha h;
  h.add(p, n);
  return h.finish();
}
inline Hash sha(const Bytes &b) { return sha(b.data(), b.size()); }
inline uint64_t get(const Bytes &b, size_t p, int n) {
  if (p > b.size() || size_t(n) > b.size() - p)
    throw std::runtime_error("BAD_BATCH_METADATA: truncated field");
  uint64_t v = 0;
  for (int i = 0; i < n; i++)
    v |= uint64_t(b[p + i]) << (8 * i);
  return v;
}
inline bool validName(const std::string &n) {
  if (n.empty() || n.size() > 240 || n == "." ||
      n.find("..") != std::string::npos)
    return false;
  for (size_t i = 0; i < n.size();) {
    uint8_t c = n[i++];
    if (c < 32 || c == 127 || c == '/' || c == '\\')
      return false;
    if (c < 128)
      continue;
    unsigned more;
    uint32_t v, min;
    if (c >= 0xc2 && c <= 0xdf) {
      more = 1;
      v = c & 31;
      min = 128;
    } else if (c >= 0xe0 && c <= 0xef) {
      more = 2;
      v = c & 15;
      min = 2048;
    } else if (c >= 0xf0 && c <= 0xf4) {
      more = 3;
      v = c & 7;
      min = 65536;
    } else
      return false;
    if (i + more > n.size())
      return false;
    while (more--) {
      c = n[i++];
      if ((c & 0xc0) != 0x80)
        return false;
      v = (v << 6) | (c & 63);
    }
    if (v < min || v > 0x10ffff || (v >= 0xd800 && v <= 0xdfff) ||
        (v >= 128 && v <= 159))
      return false;
  }
  return true;
}
struct Meta {
  std::array<uint8_t, 16> batch{};
  uint32_t salt = 0, index = 0, count = 1, segmentSize = 8 * 1024 * 1024;
  uint64_t fileSize = 0, offset = 0;
  Hash whole{};
  std::string name;
};
inline std::array<uint8_t, 16> batchId(uint64_t size, const Hash &whole,
                                       const std::string &name) {
  std::string tag = "OptiFerry-BFB1-v1";
  Bytes b(tag.begin(), tag.end());
  size_t p = b.size();
  b.resize(p + 8);
  put(b, p, size, 8);
  b.insert(b.end(), whole.begin(), whole.end());
  b.insert(b.end(), name.begin(), name.end());
  auto h = sha(b);
  std::array<uint8_t, 16> id;
  std::copy_n(h.begin(), 16, id.begin());
  return id;
}
inline void validate(const Meta &m, uint64_t len) {
  if (!validName(m.name) || m.segmentSize < 1048576 ||
      m.segmentSize > 33554432 || m.fileSize > uint64_t(INT64_MAX) ||
      m.count == 0 || m.count > 65535 || m.index >= m.count)
    throw std::runtime_error("BAD_BATCH_METADATA: invalid name, size or count");
  uint64_t count = m.fileSize == 0 ? 1 : 1 + (m.fileSize - 1) / m.segmentSize;
  if (m.count != count || m.offset != uint64_t(m.index) * m.segmentSize ||
      m.offset > m.fileSize ||
      len != std::min<uint64_t>(m.segmentSize, m.fileSize - m.offset))
    throw std::runtime_error("BAD_BATCH_METADATA: invalid segment geometry");
  if (m.batch != batchId(m.fileSize, m.whole, m.name))
    throw std::runtime_error("BAD_BATCH_METADATA: invalid batch ID");
}
inline Bytes envelope(const Meta &m, const Bytes &data) {
  validate(m, data.size());
  Bytes b(132 + m.name.size() + data.size());
  std::copy_n("BFB1", 4, b.begin());
  b[4] = 1;
  put(b, 6, 132 + m.name.size(), 2);
  std::copy(m.batch.begin(), m.batch.end(), b.begin() + 8);
  put(b, 24, m.salt, 4);
  put(b, 28, m.index, 4);
  put(b, 32, m.count, 4);
  put(b, 36, m.segmentSize, 4);
  put(b, 40, m.fileSize, 8);
  put(b, 48, m.offset, 8);
  put(b, 56, data.size(), 4);
  std::copy(m.whole.begin(), m.whole.end(), b.begin() + 64);
  auto h = sha(data);
  std::copy(h.begin(), h.end(), b.begin() + 96);
  put(b, 128, m.name.size(), 2);
  std::copy(m.name.begin(), m.name.end(), b.begin() + 132);
  std::copy(data.begin(), data.end(), b.begin() + 132 + m.name.size());
  return b;
}
inline Meta parse(const Bytes &b) {
  if (b.size() < 132 || !std::equal(b.begin(), b.begin() + 4, "BFB1") ||
      b[4] != 1 || b[5] != 0 || get(b, 60, 4) || get(b, 130, 2))
    throw std::runtime_error("BAD_BATCH_METADATA: invalid BFB1 header");
  auto n = get(b, 128, 2), header = get(b, 6, 2), len = get(b, 56, 4);
  if (n > 240 || header != 132 + n || header > b.size() ||
      len != b.size() - header)
    throw std::runtime_error("BAD_BATCH_METADATA: invalid lengths");
  Meta m;
  std::copy_n(b.begin() + 8, 16, m.batch.begin());
  m.salt = get(b, 24, 4);
  m.index = get(b, 28, 4);
  m.count = get(b, 32, 4);
  m.segmentSize = get(b, 36, 4);
  m.fileSize = get(b, 40, 8);
  m.offset = get(b, 48, 8);
  std::copy_n(b.begin() + 64, 32, m.whole.begin());
  m.name = std::string(b.begin() + 132, b.begin() + header);
  validate(m, len);
  auto h = sha(b.data() + header, len);
  if (!std::equal(h.begin(), h.end(), b.begin() + 96))
    throw std::runtime_error("BAD_SEGMENT_HASH");
  return m;
}
} // namespace optiferry
