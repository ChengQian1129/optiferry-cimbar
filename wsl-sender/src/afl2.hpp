#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <numeric>
#include <stdexcept>
#include <vector>
namespace optiferry {
using Bytes = std::vector<uint8_t>;
inline void put(Bytes &b, size_t p, uint64_t n, int len) {
  for (int i = 0; i < len; i++)
    b.at(p + i) = uint8_t(n >> (8 * i));
}
inline uint32_t fnv(const Bytes &b) {
  uint32_t h = 2166136261u;
  for (auto x : b)
    h = (h ^ x) * 16777619u;
  return h;
}
// Port of locked BeamFerry/Decimen fountain.ts. Do not replace with std::log.
inline double dlog(double x) {
  int e = 0;
  while (x >= 1.5) {
    x /= 2;
    e++;
  }
  while (x < .75) {
    x *= 2;
    e--;
  }
  double z = (x - 1) / (x + 1), z2 = z * z, t = z, s = 0;
  for (int n = 1; n <= 21; n += 2) {
    s += t / n;
    t *= z2;
  }
  return e * .6931471805599453 + 2 * s;
}
struct Random {
  uint32_t state;
  uint32_t next() {
    state += 0x9e3779b9u;
    uint32_t z = state ^ (state >> 16);
    z *= 0x21f0aaadu;
    z ^= z >> 15;
    z *= 0x735a2d97u;
    return z ^ (z >> 15);
  }
};
class Encoder {
  Bytes data;
  std::vector<double> cdf;
  uint32_t payloadFnv;

public:
  uint16_t blockLen, session;
  uint32_t k;
  Encoder(Bytes payload, uint16_t len, uint16_t sid)
      : data(std::move(payload)), blockLen(len), session(sid) {
    payloadFnv = fnv(data);
    if (!len)
      throw std::runtime_error("zero block length");
    k = std::max<size_t>(1, (data.size() + len - 1) / len);
    if (k > 65535)
      throw std::runtime_error("too many AFL2 blocks");
    cdf.resize(k);
    if (k == 1) {
      cdf[0] = 1;
      return;
    }
    double r = std::max(1., .1 * dlog(k / .5) * std::sqrt(double(k))), sum = 0;
    uint32_t spike = std::min(k, uint32_t(std::ceil(k / r)));
    for (uint32_t d = 1; d <= k; d++) {
      double rho = d == 1 ? 1. / k : 1. / (double(d) * (d - 1)), tau = 0;
      if (d < spike)
        tau = r / (double(d) * k);
      else if (d == spike)
        tau = r * std::max(0., dlog(r / .5)) / k;
      sum += rho + tau;
      cdf[d - 1] = sum;
    }
    for (auto &v : cdf) {
      v /= sum;
    }
    cdf.back() = 1;
  }
  std::vector<uint32_t> indices(uint32_t seq) const {
    if (seq & 0x80000000u)
      return {(seq & 0x7fffffffu) % k};
    uint32_t seed = (uint32_t(session + 1) * 0x9e3779b1u) ^ (seq + 0x85ebca6bu);
    seed = (seed ^ (seed >> 13)) * 0xc2b2ae35u;
    Random rnd{seed ^ (seed >> 16)};
    double sample = rnd.next() * 0x1p-32;
    uint32_t degree =
        std::lower_bound(cdf.begin(), cdf.end(), sample) - cdf.begin() + 1;
    std::vector<uint32_t> out;
    out.reserve(degree);
    if (degree > (k >> 3)) {
      std::vector<uint32_t> pool(k);
      std::iota(pool.begin(), pool.end(), 0);
      for (uint32_t i = 0; i < degree; i++) {
        uint32_t j = i + rnd.next() % (k - i);
        std::swap(pool[i], pool[j]);
        out.push_back(pool[i]);
      }
    } else {
      while (out.size() < degree) {
        auto x = rnd.next() % k;
        if (std::find(out.begin(), out.end(), x) == out.end())
          out.push_back(x);
      }
    }
    return out;
  }
  Bytes frame(uint32_t seq, int layout = 4) const {
    Bytes out(20 + blockLen);
    out[0] = 0xd1;
    out[1] = layout == 4 ? 0x0f : 0x0e;
    put(out, 2, session, 2);
    put(out, 4, seq, 4);
    put(out, 8, k, 2);
    put(out, 10, blockLen, 2);
    put(out, 12, data.size(), 4);
    put(out, 16, payloadFnv, 4);
    for (auto b : indices(seq)) {
      size_t start = size_t(b) * blockLen;
      for (size_t i = 0; i < blockLen && start + i < data.size(); i++)
        out[20 + i] ^= data[start + i];
    }
    return out;
  }
};
} // namespace optiferry
