#include "afl2.hpp"
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <iostream>
int main(int argc, char **argv) {
  if (argc != 2)
    return 2;
  int count = 0;
  for (auto &e : std::filesystem::directory_iterator(argv[1])) {
    if (e.path().extension() != ".bin")
      continue;
    unsigned n, l, b, s;
    if (std::sscanf(e.path().filename().string().c_str(), "n%u-l%u-b%u-s%u.bin",
                    &n, &l, &b, &s) != 4)
      return 3;
    optiferry::Bytes data(n);
    for (size_t i = 0; i < n; i++)
      data[i] = (i * 31 + 17) & 255;
    optiferry::Encoder enc(std::move(data), b, 0x1234);
    auto actual = enc.frame(s, l);
    std::ifstream f(e.path(), std::ios::binary);
    optiferry::Bytes expected((std::istreambuf_iterator<char>(f)), {});
    if (actual != expected) {
      std::cerr << "FAIL " << e.path() << '\n';
      return 1;
    }
    count++;
  }
  if (count != 136)
    return 4;
  std::cout << "PASS: " << count << " upstream AFL2 golden vectors\n";
}
