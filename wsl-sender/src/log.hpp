#pragma once
#include <fcntl.h>
#include <filesystem>
#include <fstream>
#include <functional>
#include <string>
#include <unistd.h>
#include <vector>
namespace optiferry {
inline void logLine(const std::string &line) {
  try {
    const char *home = std::getenv("HOME");
    if (!home)
      return;
    auto dir = std::filesystem::path(home) / ".local/state/optiferry";
    std::filesystem::create_directories(dir);
    std::ofstream out(dir / "qsend.log", std::ios::app);
    out << std::time(nullptr) << ' ' << line << '\n';
  } catch (...) {
  }
}
} // namespace optiferry
