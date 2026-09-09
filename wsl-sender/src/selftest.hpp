#pragma once
#include "afl2.hpp"
#include "golden_data.hpp"
#include <bitset>
#include <cstdio>
namespace optiferry {
inline void wireSelftest() {
  if (std::size(goldenData) != 136)
    throw std::runtime_error("Missing AFL2 golden vectors");
  for (const auto &v : goldenData) {
    unsigned n, l, b, s;
    if (std::sscanf(v.name, "n%u-l%u-b%u-s%u.bin", &n, &l, &b, &s) != 4)
      throw std::runtime_error("Bad vector name");
    Bytes data(n);
    for (size_t i = 0; i < n; i++)
      data[i] = (i * 31 + 17) & 255;
    Encoder e(std::move(data), b, 0x1234);
    auto frame = e.frame(s, l);
    for (size_t i = 0; i < frame.size(); i++) {
      auto nib = [](char c) { return c >= 'a' ? c - 'a' + 10 : c - '0'; };
      if (frame[i] != uint8_t(nib(v.hex[i * 2]) * 16 + nib(v.hex[i * 2 + 1])))
        throw std::runtime_error(std::string("Golden mismatch: ") + v.name);
    }
  }
  // Independent GF(2) test decoder: all input symbols are repair symbols,
  // with deterministic erasure and reversed sequence order.
  constexpr size_t k = 32, block = 2048;
  Bytes original(k * block);
  for (size_t i = 0; i < original.size(); i++)
    original[i] = (i * 31 + i / 17) & 255;
  Encoder e(original, block, 173);
  struct Row {
    std::bitset<k> indices;
    Bytes data;
  };
  std::array<Row, k> basis;
  int rank = 0;
  for (uint32_t seq = 255; seq > 0; seq--) {
    if (seq % 5 == 0)
      continue;
    Row r;
    for (auto i : e.indices(seq))
      r.indices.set(i);
    auto f = e.frame(seq);
    r.data = Bytes(f.begin() + 20, f.end());
    for (size_t p = 0; p < k; p++)
      if (r.indices[p]) {
        if (basis[p].data.empty()) {
          basis[p] = std::move(r);
          rank++;
          break;
        }
        r.indices ^= basis[p].indices;
        for (size_t j = 0; j < block; j++)
          r.data[j] ^= basis[p].data[j];
      }
    if (rank == int(k))
      break;
  }
  if (rank != int(k))
    throw std::runtime_error("LT repair decoding failed");
  Bytes recovered(original.size());
  for (int p = int(k) - 1; p >= 0; p--) {
    auto data = basis[p].data;
    for (size_t next = p + 1; next < k; next++)
      if (basis[p].indices[next])
        for (size_t j = 0; j < block; j++)
          data[j] ^= recovered[next * block + j];
    std::copy(data.begin(), data.end(), recovered.begin() + p * block);
  }
  if (recovered != original)
    throw std::runtime_error("LT recovered payload mismatch");
}
} // namespace optiferry
