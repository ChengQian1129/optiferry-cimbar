#include "display.hpp"
#include "log.hpp"
#include "selftest.hpp"
#include "sessions.hpp"
#include <csignal>
#include <fcntl.h>
#include <filesystem>
#include <fstream>
#include <future>
#include <iostream>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/sysinfo.h>
#include <sys/utsname.h>
#include <unistd.h>
using namespace optiferry;
volatile std::sig_atomic_t interrupted = 0;
void onSignal(int) { interrupted = 1; }
struct File {
  int fd = -1;
  struct stat original {};
  File(const std::string &p) {
    fd = open(p.c_str(), O_RDONLY);
    if (fd < 0 || fstat(fd, &original) != 0 || !S_ISREG(original.st_mode))
      throw std::runtime_error("Source must be a readable regular file");
  }
  ~File() {
    if (fd >= 0)
      close(fd);
  }
  Bytes read(uint64_t offset, size_t size) {
    Bytes out(size);
    size_t done = 0;
    while (done < size) {
      auto n = pread(fd, out.data() + done, size - done, off_t(offset + done));
      if (n <= 0)
        throw std::runtime_error("Source read failed or file changed");
      done += n;
    }
    return out;
  }
  void unchanged() {
    struct stat s {};
    if (fstat(fd, &s) || s.st_size != original.st_size ||
        s.st_mtim.tv_sec != original.st_mtim.tv_sec ||
        s.st_mtim.tv_nsec != original.st_mtim.tv_nsec)
      throw std::runtime_error("Source changed during transfer; restart qsend");
  }
  Hash hash() {
    Sha h;
    uint64_t size = original.st_size;
    for (uint64_t p = 0; p < size; p += 1048576) {
      auto b = read(p, std::min<uint64_t>(1048576, size - p));
      h.add(b.data(), b.size());
      if (interrupted)
        throw std::runtime_error("Stopped");
    }
    unchanged();
    return h.finish();
  }
};
int main(int argc, char **argv) {
  try {
    std::signal(SIGINT, onSignal);
    std::signal(SIGTERM, onSignal);
    bool windowed = false, legacy = false, diagnose = false;
    uint32_t segmentSize = 8388608, passes = 0;
    double factor = 1.65;
    std::string path, exportPath;
    for (int i = 1; i < argc; i++) {
      std::string a = argv[i];
      auto value = [&]() {
        if (++i >= argc)
          throw std::runtime_error("Missing value for " + a);
        return std::string(argv[i]);
      };
      if (a == "--help") {
        std::cout << "OptiFerry 0.1.0\nqsend FILE [--segment-size 8MiB] "
                     "[--attempt-factor 1.65] [--passes N | --loop] "
                     "[--windowed] [--legacy-beamferry]\nqsend --diagnose | "
                     "--selftest | --version\nESC/Q Stop; Space Pause; I "
                     "Diagnostics; F Fullscreen\n";
        return 0;
      } else if (a == "--version") {
        std::cout << "OptiFerry 0.1.0\n";
        return 0;
      } else if (a == "--windowed")
        windowed = true;
      else if (a == "--legacy-beamferry")
        legacy = true;
      else if (a == "--loop")
        passes = 0;
      else if (a == "--passes") {
        auto v = value();
        size_t n;
        auto x = std::stoull(v, &n);
        if (n != v.size() || x > UINT32_MAX || v[0] == '-')
          throw std::runtime_error("Invalid passes");
        passes = x;
      } else if (a == "--attempt-factor") {
        auto v = value();
        size_t n;
        factor = std::stod(v, &n);
        if (n != v.size() || !std::isfinite(factor) || factor < 1 ||
            factor > 10)
          throw std::runtime_error("Attempt factor must be 1..10");
      } else if (a == "--segment-size") {
        auto v = value();
        size_t n;
        uint64_t x = std::stoull(v, &n);
        if (v.substr(n) == "MiB")
          x *= 1048576;
        else if (n != v.size())
          throw std::runtime_error("Use segment size such as 8MiB");
        if (x < 1048576 || x > 33554432)
          throw std::runtime_error("Segment size must be 1..32MiB");
        segmentSize = x;
      } else if (a == "--diagnose")
        diagnose = true;
      else if (a == "--export-frames")
        exportPath = value();
      else if (a == "--selftest") {
        wireSelftest();
        Bytes d = {'a', 'b', 'c'};
        auto h = sha(d);
        if (h[0] != 0xba || h[31] != 0xad)
          throw std::runtime_error("SHA selftest failed");
        Meta m;
        m.name = "selftest.bin";
        m.fileSize = 3;
        m.whole = h;
        m.batch = batchId(3, h, m.name);
        auto b = envelope(m, d);
        parse(b);
        auto ids = sessions(m);
        Encoder enc(b, 2048, ids[0]);
        auto qr = qrcodegen::QrCode::encodeSegments(
            {qrcodegen::QrSegment::makeBytes(enc.frame(0x80000000u))},
            qrcodegen::QrCode::Ecc::LOW, 33, 33, 4, false);
        if (qr.getSize() != 149 || (2160 - 160) / 2 / 157 != 6)
          throw std::runtime_error("QR geometry selftest failed");
        std::cout << "PASS: 136 upstream golden vectors, LT repair decoding "
                     "with erasure/reordering, SHA256, BFB1, deterministic "
                     "IDs, binary QR V33, 4K geometry\n";
        return 0;
      } else if (a.starts_with("--"))
        throw std::runtime_error("Unknown option " + a);
      else if (path.empty())
        path = a;
      else
        throw std::runtime_error("Only one source file is allowed");
    }
    utsname os{};
    uname(&os);
    bool wsl = std::string(os.release).find("microsoft") != std::string::npos;
    bool gui = std::getenv("WAYLAND_DISPLAY") || std::getenv("DISPLAY");
    if (diagnose) {
      std::cout << "WSL=" << wsl
                << " WSLg=" << std::filesystem::exists("/mnt/wslg")
                << " CPU=" << os.machine << '\n';
      for (auto key : {"WAYLAND_DISPLAY", "DISPLAY"})
        std::cout << key << '='
                  << (std::getenv(key) ? std::getenv(key) : "unset") << '\n';
      struct sysinfo info {};
      sysinfo(&info);
      std::cout << "available RAM=" << uint64_t(info.freeram) * info.mem_unit
                << '\n';
      Display display(true);
      for (int i = 0; i < 120 && !display.stopped; i++)
        display.present("Presentation diagnostics (compositor cadence, not "
                        "physical scanout)");
      display.statistics(std::cout);
      return 0;
    }
    if (path.empty())
      throw std::runtime_error("Use qsend FILE, or qsend --help");
    if (exportPath.empty() && (!wsl || !gui))
      throw std::runtime_error("WSLg display is not available. Run qsend from "
                               "a WSL session with GUI application support.");
    logLine("Starting transfer");
    File source(path);
    Meta meta;
    meta.name = std::filesystem::path(path).filename().string();
    if (!validName(meta.name))
      throw std::runtime_error(
          "Filename must be valid UTF-8, at most 240 bytes, without "
          "path/control characters or '..'");
    meta.fileSize = source.original.st_size;
    meta.segmentSize = segmentSize;
    uint64_t count =
        meta.fileSize == 0 ? 1 : 1 + (meta.fileSize - 1) / segmentSize;
    if (count > 65535)
      throw std::runtime_error("Too many logical segments");
    meta.count = count;
    if (legacy && (meta.fileSize == 0 || meta.fileSize > 67108864))
      throw std::runtime_error(
          "Legacy BeamFerry supports nonempty files up to 64MiB");
    std::cout << "OptiFerry 0.1.0\nFile " << meta.name << "\nSize "
              << meta.fileSize << " bytes\nComputing SHA-256..." << std::flush;
    meta.whole = source.hash();
    meta.batch = batchId(meta.fileSize, meta.whole, meta.name);
    std::cout << " done\nAllocating stable sessions..." << std::flush;
    auto ids = legacy ? std::vector<uint16_t>{0x1234} : sessions(meta);
    std::cout << " done\nSegments " << (legacy ? 1 : meta.count)
              << " | Quad V33-L | 2068B | 30 sets/s\n";
    std::ofstream exported;
    if (!exportPath.empty()) {
      if (std::filesystem::exists(exportPath) &&
          std::filesystem::equivalent(path, exportPath))
        throw std::runtime_error(
            "Frame export must not overwrite the source file");
      exported.open(exportPath, std::ios::binary);
      if (!exported)
        throw std::runtime_error("Cannot open frame export output");
      if (!passes)
        passes = 1;
    }
    std::unique_ptr<Display> display;
    if (exportPath.empty())
      display = std::make_unique<Display>(windowed);
    for (uint64_t pass = 0; (!passes || pass < passes) && !interrupted; pass++)
      for (uint32_t index = 0;
           index < (legacy ? 1 : meta.count) && !interrupted; index++) {
        source.unchanged();
        meta.index = index;
        meta.offset = uint64_t(index) * segmentSize;
        auto data = source.read(
            legacy ? 0 : meta.offset,
            legacy
                ? meta.fileSize
                : std::min<uint64_t>(segmentSize, meta.fileSize - meta.offset));
        Bytes payload;
        if (legacy) {
          std::string mime = "application/octet-stream";
          payload.resize(49 + meta.name.size() + mime.size() + data.size());
          std::copy_n("DCF2", 4, payload.begin());
          put(payload, 5, meta.name.size(), 2);
          put(payload, 7, mime.size(), 2);
          put(payload, 9, data.size(), 4);
          put(payload, 13, data.size(), 4);
          std::copy(meta.whole.begin(), meta.whole.end(), payload.begin() + 17);
          std::copy(meta.name.begin(), meta.name.end(), payload.begin() + 49);
          std::copy(mime.begin(), mime.end(),
                    payload.begin() + 49 + meta.name.size());
          std::copy(data.begin(), data.end(),
                    payload.begin() + 49 + meta.name.size() + mime.size());
        } else
          payload = envelope(meta, data);
        data.clear();
        data.shrink_to_fit();
        Encoder encoder(std::move(payload), 2048, ids[legacy ? 0 : index]);
        uint32_t attempts = uint32_t(std::ceil(encoder.k * factor));
        for (uint32_t ordinal = 0; ordinal < attempts && !interrupted;
             ordinal += 4) {
          std::array<Bytes, 4> frames;
          for (uint32_t slot = 0; slot < 4; slot++) {
            uint32_t n = std::min(ordinal + slot, attempts - 1),
                     seq =
                         n < encoder.k
                             ? (0x80000000u | n)
                             : uint32_t((pass * uint64_t(attempts - encoder.k) +
                                         (n - encoder.k)) %
                                        0x80000000ull);
            frames[slot] = encoder.frame(seq);
            if (exported.is_open()) {
              uint32_t length = frames[slot].size();
              char prefix[4];
              for (int b = 0; b < 4; b++)
                prefix[b] = char(length >> (8 * b));
              exported.write(prefix, 4);
              exported.write(reinterpret_cast<char *>(frames[slot].data()),
                             length);
            }
          }
          if (display) {
            display->symbols(frames);
            std::string status =
                meta.name + " | Pass " + std::to_string(pass + 1) +
                " | Segment " + std::to_string(index + 1) + "/" +
                std::to_string(legacy ? 1 : meta.count) + " | " +
                std::to_string(ordinal) + "/" + std::to_string(attempts);
            for (int repeat = 0; repeat < 2; repeat++)
              display->present(status);
            while (display->paused && !display->stopped && !interrupted)
              display->present(status);
            if (display->stopped)
              interrupted = 1;
          }
        }
        source.unchanged();
      }
    if (display) {
      display->statistics(std::cout);
      std::ostringstream record;
      display->statistics(record);
      logLine(record.str());
    }
    if (exported.is_open()) {
      exported.flush();
      if (!exported)
        throw std::runtime_error("Frame export write failed");
    }
    return 0;
  } catch (const std::exception &e) {
    logLine(std::string("ERROR: ") + e.what());
    std::cerr << "ERROR: " << e.what() << '\n';
    return 1;
  }
}
