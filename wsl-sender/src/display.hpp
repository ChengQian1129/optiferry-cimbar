#pragma once
#include "afl2.hpp"
#include "qrcodegen.hpp"
#include <SDL.h>
#include <SDL_ttf.h>
#include <chrono>
#include <ctime>
#include <deque>
#include <iomanip>
#include <map>
#include <sstream>
#include <thread>
namespace optiferry {
inline double rawTime() {
  timespec t{};
  clock_gettime(CLOCK_MONOTONIC_RAW, &t);
  return t.tv_sec + t.tv_nsec * 1e-9;
}
struct Display {
  struct CachedText {
    std::string value;
    SDL_Color color{};
    SDL_Texture *texture = nullptr;
    int width = 0, height = 0;
  };
  std::map<int, CachedText> textCache;
  SDL_Window *window = nullptr;
  SDL_Renderer *renderer = nullptr;
  TTF_Font *font = nullptr;
  std::array<SDL_Texture *, 4> textures{};
  bool stopped = false, paused = false, overlay = true, fullscreen;
  std::deque<double> intervals;
  double previous = 0, deadline = 0;
  uint64_t late = 0, presents = 0;
  int width = 0, height = 0, module = 0;
  std::string rendererName;
  Display(bool windowed) : fullscreen(!windowed) {
    SDL_SetHint(SDL_HINT_RENDER_SCALE_QUALITY, "0");
    if (SDL_Init(SDL_INIT_VIDEO) != 0)
      throw std::runtime_error(std::string("WSLg display is unavailable: ") +
                               SDL_GetError());
    window = SDL_CreateWindow(
        "OptiFerry", SDL_WINDOWPOS_CENTERED, SDL_WINDOWPOS_CENTERED, 1280, 900,
        SDL_WINDOW_ALLOW_HIGHDPI | SDL_WINDOW_RESIZABLE |
            (windowed ? 0 : SDL_WINDOW_FULLSCREEN_DESKTOP));
    if (!window)
      throw std::runtime_error(SDL_GetError());
    renderer = SDL_CreateRenderer(
        window, -1, SDL_RENDERER_ACCELERATED | SDL_RENDERER_PRESENTVSYNC);
    if (!renderer)
      renderer = SDL_CreateRenderer(window, -1, SDL_RENDERER_SOFTWARE);
    if (!renderer)
      throw std::runtime_error(SDL_GetError());
    SDL_RendererInfo info{};
    SDL_GetRendererInfo(renderer, &info);
    rendererName = info.name;
    if (TTF_Init() != 0)
      throw std::runtime_error(TTF_GetError());
    font = TTF_OpenFont("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 22);
    if (!font)
      throw std::runtime_error("DejaVu Sans font missing; run install-wsl.sh");
    geometry();
  }
  ~Display() {
    for (auto &[y, t] : textCache)
      if (t.texture)
        SDL_DestroyTexture(t.texture);
    for (auto t : textures)
      if (t)
        SDL_DestroyTexture(t);
    if (font)
      TTF_CloseFont(font);
    if (renderer)
      SDL_DestroyRenderer(renderer);
    if (window)
      SDL_DestroyWindow(window);
    TTF_Quit();
    SDL_Quit();
  }
  void geometry() {
    SDL_GetRendererOutputSize(renderer, &width, &height);
    module = std::min((width - 80) / 2, (height - 160) / 2) / 157;
    if (module < 1)
      throw std::runtime_error("Display is too small for four QR codes");
  }
  void events() {
    SDL_Event e;
    while (SDL_PollEvent(&e)) {
      if (e.type == SDL_QUIT)
        stopped = true;
      if (e.type == SDL_KEYDOWN) {
        switch (e.key.keysym.sym) {
        case SDLK_ESCAPE:
        case SDLK_q:
          stopped = true;
          break;
        case SDLK_SPACE:
          paused = !paused;
          break;
        case SDLK_i:
          overlay = !overlay;
          break;
        case SDLK_f:
          fullscreen = !fullscreen;
          SDL_SetWindowFullscreen(
              window, fullscreen ? SDL_WINDOW_FULLSCREEN_DESKTOP : 0);
          geometry();
          break;
        }
      }
      if (e.type == SDL_WINDOWEVENT)
        geometry();
    }
  }
  void symbols(const std::array<Bytes, 4> &frames) {
    for (int slot = 0; slot < 4; slot++) {
      auto qr = qrcodegen::QrCode::encodeSegments(
          {qrcodegen::QrSegment::makeBytes(frames[slot])},
          qrcodegen::QrCode::Ecc::LOW, 33, 33, 4, false);
      std::vector<uint32_t> pixels(157 * 157, 0xffffffffu);
      for (int y = 0; y < 149; y++)
        for (int x = 0; x < 149; x++)
          if (qr.getModule(x, y))
            pixels[(y + 4) * 157 + x + 4] = 0xff000000u;
      if (!textures[slot])
        textures[slot] = SDL_CreateTexture(renderer, SDL_PIXELFORMAT_ARGB8888,
                                           SDL_TEXTUREACCESS_STATIC, 157, 157);
      if (!textures[slot] || SDL_UpdateTexture(textures[slot], nullptr,
                                               pixels.data(), 157 * 4) != 0)
        throw std::runtime_error(SDL_GetError());
      SDL_SetTextureScaleMode(textures[slot], SDL_ScaleModeNearest);
    }
  }
  double hz() const {
    double sum = 0;
    for (auto d : intervals)
      sum += d;
    return sum > 0 ? intervals.size() / sum : 0;
  }
  void text(const std::string &s, int y,
            SDL_Color color = {210, 220, 230, 255}) {
    auto &entry = textCache[y];
    if (entry.value != s || entry.color.r != color.r ||
        entry.color.g != color.g || entry.color.b != color.b ||
        !entry.texture) {
      auto surface = TTF_RenderUTF8_Blended(font, s.c_str(), color);
      if (!surface)
        return;
      if (entry.texture)
        SDL_DestroyTexture(entry.texture);
      entry.texture = SDL_CreateTextureFromSurface(renderer, surface);
      entry.value = s;
      entry.color = color;
      entry.width = surface->w;
      entry.height = surface->h;
      SDL_FreeSurface(surface);
    }
    SDL_Rect r{20, y, entry.width, entry.height};
    SDL_RenderCopy(renderer, entry.texture, nullptr, &r);
  }
  void present(const std::string &status) {
    events();
    SDL_SetRenderDrawColor(renderer, 0, 0, 0, 255);
    SDL_RenderClear(renderer);
    int side = 157 * module, gap = 20, left = (width - 2 * side - gap) / 2,
        top = 100 + (height - 100 - 2 * side - gap) / 2;
    for (int s = 0; s < 4; s++)
      if (textures[s]) {
        SDL_Rect r{left + (s % 2) * (side + gap), top + (s / 2) * (side + gap),
                   side, side};
        SDL_RenderCopy(renderer, textures[s], nullptr, &r);
      }
    text("OptiFerry  |  " + status, 8);
    if (overlay) {
      std::ostringstream s;
      s << std::fixed << std::setprecision(1) << "Present " << hz()
        << " Hz | Symbol " << hz() / 2 << " sets/s | Late "
        << (presents ? 100. * late / presents : 0) << "% | " << module
        << " px/module | "
        << (paused ? "PAUSED"
                   : "ESC Stop / Space Pause / I Info / F Fullscreen");
      text(s.str(), 40,
           hz() > 55   ? SDL_Color{160, 200, 170, 255}
           : hz() > 50 ? SDL_Color{240, 205, 80, 255}
                       : SDL_Color{245, 100, 100, 255});
    }
    // Pace against absolute raw-clock deadlines when compositor vsync does not
    // block.
    if (presents == 0 && std::getenv("OPTIFERRY_SNAPSHOT")) {
      auto surface = SDL_CreateRGBSurfaceWithFormat(0, width, height, 32,
                                                    SDL_PIXELFORMAT_ARGB8888);
      if (surface) {
        if (SDL_RenderReadPixels(renderer, nullptr, surface->format->format,
                                 surface->pixels, surface->pitch) == 0)
          SDL_SaveBMP(surface, std::getenv("OPTIFERRY_SNAPSHOT"));
        SDL_FreeSurface(surface);
      }
    }
    double before = rawTime();
    SDL_RenderPresent(renderer);
    double now = rawTime();
    if (!deadline)
      deadline = now;
    deadline += 1. / 60;
    if (now - before < .008 && now < deadline) {
      std::this_thread::sleep_for(
          std::chrono::duration<double>(deadline - now));
      now = rawTime();
    }
    if (previous) {
      double dt = now - previous;
      intervals.push_back(dt);
      if (intervals.size() > 600)
        intervals.pop_front();
      if (dt > 1. / 55)
        late++;
    }
    if (now > deadline + 1. / 60)
      deadline = now;
    previous = now;
    presents++;
  }
  void statistics(std::ostream &out) {
    auto v = std::vector<double>(intervals.begin(), intervals.end());
    std::sort(v.begin(), v.end());
    out << "framebuffer=" << width << 'x' << height << " module=" << module
        << " SDL=" << SDL_GetCurrentVideoDriver()
        << " renderer=" << rendererName << " effectiveHz=" << hz();
    if (!v.empty())
      out << " p50=" << v[v.size() / 2] * 1000
          << " p95=" << v[size_t((v.size() - 1) * .95)] * 1000
          << " p99=" << v[size_t((v.size() - 1) * .99)] * 1000;
    out << " ms late=" << late << '/' << presents << '\n';
  }
};
} // namespace optiferry
