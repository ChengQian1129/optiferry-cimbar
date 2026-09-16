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
#include <numeric>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/sysinfo.h>
#include <sys/utsname.h>
#include <unistd.h>
using namespace optiferry;
volatile std::sig_atomic_t interrupted = 0;
void onSignal(int) { interrupted = 1; }
uint32_t coprimeReplayStep(uint32_t total) {
  if (total <= 1)
    return 1;
  uint32_t step = std::max<uint32_t>(1, uint32_t(total * 0.61803398875)) | 1u;
  while (step > 1 && std::gcd(step, total) != 1)
    step -= 2;
  return std::max<uint32_t>(1, step);
}
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
    bool windowed = false, legacy = false, diagnose = false, fast = false,
         stableLanes = false, dual = false, repeatSet = false;
    uint32_t segmentSize = 8388608, passes = 0;
    uint32_t repeats = 2;
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
                     "[--windowed] [--fast] [--stable-lanes] [--dual] [--repeat N] [--legacy-beamferry]\nqsend --diagnose | "
                     "--selftest | --version\nESC/Q Stop; Space Pause; I "
                     "Diagnostics; F Fullscreen\n";
        return 0;
      } else if (a == "--version") {
        std::cout << "OptiFerry 0.1.0\n";
        return 0;
      } else if (a == "--windowed")
        windowed = true;
      else if (a == "--fast")
        fast = true;
      else if (a == "--stable-lanes")
        stableLanes = true;
      else if (a == "--dual")
        dual = true;
      else if (a == "--repeat") {
        auto v = value();
        size_t n;
        auto x = std::stoull(v, &n);
        if (n != v.size() || x < 1 || x > 8 || v[0] == '-')
          throw std::runtime_error("Repeat count must be 1..8");
        repeats = uint32_t(x);
        repeatSet = true;
      }
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
        Encoder fastEnc(b, 1445, ids[0]);
        auto fastQr = qrcodegen::QrCode::encodeSegments(
            {qrcodegen::QrSegment::makeBytes(fastEnc.frame(0x80000000u, 6))},
            qrcodegen::QrCode::Ecc::LOW, 27, 27, 4, false);
        Encoder dualEnc(b, 2048, ids[0]);
        auto dualFrame = dualEnc.frame(0x80000000u, 2);
        auto dualQr = qrcodegen::QrCode::encodeSegments(
            {qrcodegen::QrSegment::makeBytes(dualFrame)},
            qrcodegen::QrCode::Ecc::LOW, 33, 33, 4, false);
        if (qr.getSize() != 149 || (2160 - 160) / 2 / 157 != 6 ||
            fastQr.getSize() != 125 || (2160 - 160) / 2 / 129 != 7 ||
            dualFrame.size() != 2068 || dualFrame[1] != 0x1d ||
            dualQr.getSize() != 149 ||
            std::min((3840 - 80) / 2, 2160 - 160) / 157 != 11)
          throw std::runtime_error("QR geometry selftest failed");
        std::cout << "PASS: 136 upstream golden vectors, LT repair decoding "
                     "with erasure/reordering, SHA256, BFB1, deterministic "
                     "IDs, binary QR V33/V27 dual-layout geometry\n";
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
    if (fast && legacy)
      throw std::runtime_error("--fast is only available for AFL2 quad mode");
    if (stableLanes && (fast || legacy || dual))
      throw std::runtime_error(
          "--stable-lanes is only available for the normal AFL2 quad mode");
    if (dual && (fast || legacy))
      throw std::runtime_error(
          "--dual is only available for normal AFL2 mode");
    if (fast && !repeatSet)
      repeats = 1;
    if (stableLanes && !repeatSet)
      repeats = 1;
    if (dual && !repeatSet)
      repeats = 1;
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
    const uint32_t frameBytes = fast ? 1465 : 2068;
    const uint16_t blockLength = uint16_t(frameBytes - 20);
    const uint32_t codesPerSet = dual ? 2 : 4;
    std::cout << " done\nSegments " << (legacy ? 1 : meta.count)
              << " | " << (dual ? "Dual" : "Quad") << " V"
              << (fast ? 27 : 33) << "-L | " << frameBytes
              << "B | " << repeats
              << " refreshes/set"
              << (stableLanes ? " | stable diagonal pair lanes" : "")
              << (dual ? " | Android horizontal pair" : "") << "\n";
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
    if (exportPath.empty()) {
      display = std::make_unique<Display>(windowed, fast, repeats, stableLanes,
                                          dual);
      if (fast && display->pacingHz < 59.0)
        throw std::runtime_error("--fast requires a display refresh rate of at least 60 Hz");
      if (fast)
        std::cout << "FAST mode: V27-L/1465B, full-refresh marker, rotating quad slots; "
                  << repeats
                  << " display refresh(es) per QR set; optical stability is not guaranteed\n";
      if (stableLanes)
        std::cout << "STABLE-LANES mode: V33-L/2068B, alternate diagonal pairs; "
                  << repeats
                  << " display refresh(es) per pair update; protocol marker remains quad-compatible\n";
      if (dual)
        std::cout << "DUAL mode: V33-L/2068B, two enlarged horizontal codes; "
                  << repeats
                  << " display refresh(es) per pair update; Android receiver path\n";
    }
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
        Encoder encoder(std::move(payload), blockLength, ids[legacy ? 0 : index]);
        uint32_t attempts = uint32_t(std::ceil(encoder.k * factor));
        const uint32_t replayStep = coprimeReplayStep(encoder.k);
        auto sequenceFor = [&](uint32_t n) {
          uint32_t seq;
          if (fast) {
            if (n < encoder.k) {
              seq = 0x80000000u | n;
            } else {
              const uint64_t tail = uint64_t(n) - encoder.k;
              if ((tail & 63u) == 0) {
                const uint32_t replay =
                    uint32_t(((tail / 64) * uint64_t(replayStep) +
                              encoder.k / 2) % encoder.k);
                seq = 0x80000000u | replay;
              } else {
                seq = uint32_t(tail - tail / 64 - 1);
              }
            }
          } else {
            seq = n < encoder.k
                      ? (0x80000000u | n)
                      : uint32_t((pass * uint64_t(attempts - encoder.k) +
                                  (n - encoder.k)) %
                                 0x80000000ull);
          }
          return seq;
        };
        auto exportFrame = [&](const Bytes &frame) {
          if (!exported.is_open())
            return;
          uint32_t length = frame.size();
          char prefix[4];
          for (int b = 0; b < 4; b++)
            prefix[b] = char(length >> (8 * b));
          exported.write(prefix, 4);
          exported.write(reinterpret_cast<const char *>(frame.data()), length);
        };
        auto present = [&](const std::array<Bytes, 4> &physical,
                           uint32_t ordinal) {
          if (!display)
            return;
          display->symbols(physical);
          std::string status =
              meta.name + " | Pass " + std::to_string(pass + 1) +
              " | Segment " + std::to_string(index + 1) + "/" +
              std::to_string(legacy ? 1 : meta.count) + " | " +
              std::to_string(ordinal) + "/" + std::to_string(attempts);
          for (uint32_t repeat = 0; repeat < repeats; repeat++)
            display->present(status);
          while (display->paused && !display->stopped && !interrupted)
            display->present(status);
          if (display->stopped)
            interrupted = 1;
        };
        if (stableLanes) {
          // Fill all four physical positions once, then change only one
          // diagonal pair per display update. The aggregate symbol rate is
          // unchanged from the normal 30-set profile, but two codes remain
          // optically stable while the other two transition.
          std::array<Bytes, 4> physical;
          for (uint32_t slot = 0; slot < 4; slot++) {
            const uint32_t n = std::min(slot, attempts - 1);
            physical[slot] = encoder.frame(sequenceFor(n), 4);
            exportFrame(physical[slot]);
          }
          present(physical, 0);
          uint32_t ordinal = std::min<uint32_t>(attempts, 4);
          bool firstPair = true;
          while (ordinal < attempts && !interrupted) {
            const std::array<uint32_t, 2> slots =
                firstPair ? std::array<uint32_t, 2>{0, 3}
                          : std::array<uint32_t, 2>{1, 2};
            const uint32_t updates = std::min<uint32_t>(2, attempts - ordinal);
            for (uint32_t update = 0; update < updates; update++) {
              const uint32_t n = ordinal + update;
              physical[slots[update]] = encoder.frame(sequenceFor(n), 4);
              exportFrame(physical[slots[update]]);
            }
            present(physical, ordinal);
            ordinal += updates;
            firstPair = !firstPair;
          }
        } else {
          for (uint32_t ordinal = 0; ordinal < attempts && !interrupted;
               ordinal += codesPerSet) {
            std::array<Bytes, 4> frames;
            for (uint32_t slot = 0; slot < codesPerSet; slot++) {
              const uint32_t n = std::min(ordinal + slot, attempts - 1);
              frames[slot] = encoder.frame(
                  sequenceFor(n), dual ? 2 : (fast ? 6 : 4));
              exportFrame(frames[slot]);
            }
            std::array<Bytes, 4> physical;
            if (dual) {
              physical[0] = std::move(frames[0]);
              physical[1] = std::move(frames[1]);
            } else {
              // Match the upstream quad path: logical sequence lanes rotate
              // across physical tiles so a camera-blind tile does not
              // permanently lose one modulo-4 lane.
              const uint32_t rotation = (ordinal / 4) & 3u;
              for (uint32_t slot = 0; slot < 4; slot++)
                physical[(slot + rotation) & 3u] = std::move(frames[slot]);
            }
            present(physical, ordinal);
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
