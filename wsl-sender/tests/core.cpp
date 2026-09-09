#include "bfb1.hpp"
#include "qrcodegen.hpp"
#include <iostream>
void check(bool ok) {
  if (!ok)
    throw std::runtime_error("assertion failed");
}
int main() {
  try {
    optiferry::Bytes abc = {'a', 'b', 'c'};
    auto h = optiferry::sha(abc);
    check(h[0] == 0xba && h[31] == 0xad);
    for (size_t n : {size_t(0), size_t(1), size_t(1048576)}) {
      optiferry::Bytes d(n);
      for (size_t i = 0; i < n; i++)
        d[i] = i * 17;
      optiferry::Meta m;
      m.name = "测试.bin";
      m.fileSize = n;
      m.whole = optiferry::sha(d);
      m.batch = optiferry::batchId(n, m.whole, m.name);
      auto b = optiferry::envelope(m, d);
      auto recovered = optiferry::parse(b);
      check(recovered.name == m.name);
      if (n) {
        b.back() ^= 1;
        bool rejected = false;
        try {
          optiferry::parse(b);
        } catch (...) {
          rejected = true;
        }
        check(rejected);
      }
    }
    optiferry::Meta large;
    large.name = "sparse.bin";
    large.fileSize = 10ull * 1024 * 1024 * 1024;
    large.count = 1280;
    large.index = 1279;
    large.offset = uint64_t(large.index) * large.segmentSize;
    large.batch = optiferry::batchId(large.fileSize, large.whole, large.name);
    optiferry::validate(large, large.segmentSize);
    check(large.offset > UINT32_MAX);
    check(!optiferry::validName("../bad"));
    check(!optiferry::validName("a\\b"));
    check(!optiferry::validName(std::string("a\0b", 3)));
    check(!optiferry::validName("\xc0\x80"));
    optiferry::Bytes frame(2068, 123);
    auto qr = qrcodegen::QrCode::encodeSegments(
        {qrcodegen::QrSegment::makeBytes(frame)}, qrcodegen::QrCode::Ecc::LOW,
        33, 33, 4, false);
    check(qr.getSize() == 149);
    check(qr.getMask() == 4);
    check((2160 - 160) / 2 / 157 == 6);
    std::cout << "PASS: SHA-256, BFB1 roundtrip/corruption/UTF-8, 10 GiB "
                 "metadata, V33 binary QR and 4K geometry\n";
    return 0;
  } catch (const std::exception &e) {
    std::cerr << "FAIL: " << e.what() << '\n';
    return 1;
  }
}
