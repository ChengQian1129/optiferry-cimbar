# OptiFerry

WSL 安装、发送：

```bash
cd /mnt/c/Users/ADMIN/Desktop/二维码通信
bash scripts/install-wsl.sh
# 安装后重新打开 WSL 终端
qsend /path/to/file
```

安卓：安装 `dist/OptiFerry-debug.apk` → 授权相机 → 点「接收文件」→ 对准电脑四个二维码。
接收过程中自动保存和继续；完成文件在 `Download/OptiFerry/`。看到手机显示 SHA-256 verified 后，在电脑按 ESC 停止。

这是可安装、可构建的 0.1.0 首版。真实 Find N5 的光学速度、MediaStore 行为和端到端零操作体验仍须按 [HARDWARE_TEST.md](HARDWARE_TEST.md) 实测，不能将软件测试当成硬件验收通过。

## Cimbar 彩色码路径

仓库同时保留两条互不兼容的发送链路：

| 命令 | 屏幕码 | Android 接收端 |
| --- | --- | --- |
| `qsend` | OptiFerry AFL2 / 四 QR | `dist/OptiFerry-debug.apk` |
| `cimbar-send` | libcimbar Cimbar / 彩码单码循环 | `dist/OptiFerry-debug.apk` 的「彩码 Cimbar」 |

手机 APK 是 `org.optiferry.receiver` / OptiFerry 0.1.0。它现在同时包含 libcimbar/CFC 彩码解码器和 manifest 分片合并器；启动后默认选择「彩码 Cimbar」。发送端会先播放 `manifest.json`，再播放每个 `.partNN.bin`，手机按 manifest 顺序校验并合并。

首次在某个 WSL 环境安装发送端：

```bash
bash scripts/install-cimbar-wsl.sh
```

此前安装过旧版本的 WSL 环境也要重新运行一次。多分片轮播修复在原生 `cimbar_send` 中：同一分片在重复轮播时沿用 stream ID 和 fountain block 编号，接收端才能跨轮累积有效块。安装脚本会构建新版、记录二进制哈希并放到受管路径；发送时发现 16 位 block 编号空间耗尽会明确停止并提示重新开始传输。

以后发送安全上限内的文件只需要：

```bash
cimbar-send /mnt/f/wang_ct/CT_DICOM_complete.zip
```

这个命令会自动计算哈希、按默认 10 MiB 生成彩码分片和 manifest，并调用原生 `cimbar_send` 循环播放。手机会校验每个分片和完整文件 SHA-256，完成后显示保存按钮。

默认是 Mode B / 15 fps；需要实测时可以覆盖：`cimbar-send FILE --fps 20` 或 `--chunk-size 16MiB`。APK 诊断区会显示接收端实际接纳的帧率和被 native 队列丢弃的帧数；如果丢帧持续增长，应降低发送 FPS。接收端最多同时保留 8 条流且活动输入总量不超过 64 MiB，超额的新流会等待空位，不会淘汰已有分片进度。拼接和保存使用文件流，不把完整大文件读入 Java 堆。当前 OptiFerry APK 固定接收 Mode B，`Bm`、`Bu`、`4C` 仅可用于 `--prepare-only`，不能直接播放给这个 APK。分片和 manifest 默认保存在 WSL 原生文件系统的 `~/.cache/optiferry-cimbar/transfers/`。详细格式见 [protocol/OPM1.md](protocol/OPM1.md)。

不要用普通 `split` 手动切分；裸分片没有 manifest，手机无法知道顺序、大小和完整文件哈希。输入文件名不能是 `manifest.json`，因为这个名字保留给接收端的控制 manifest。空文件暂不支持发送。

## 已交付

- `dist/qsend`：x86_64 Linux ELF，WSL 原生 SDL 窗口；没有浏览器或 Windows EXE runtime。
- `dist/OptiFerry-debug.apk`：Android 10+，arm64-v8a / armeabi-v7a，包名 `org.optiferry.receiver`；可与原 BeamFerry 并存。
- 双端源码、安装/卸载/构建脚本、锁定的上游、协议对照样本和测试。

## 日常使用

```bash
qsend ~/Downloads/large-file.iso
qsend file.bin --passes 2
qsend file.bin --segment-size 16MiB
qsend file.bin --attempt-factor 2.5
qsend file.bin --windowed
qsend file.bin --windowed --fast
qsend file.bin --stable-lanes
qsend file.bin --dual
qsend file.bin --repeat 3
qsend --legacy-beamferry small-file.bin
qsend --selftest
qsend --diagnose
```

默认 8 MiB 逻辑段、1.65 倍符号尝试量、无限循环；不生成分卷文件。
后续轮次更换 repair 序列，提高丢包时补段机会。链路没有 ACK，电脑不知道手机是否完成。
`--fast` 是实验模式：使用上游同款四码高速档（V27、1465 B、专用全刷标记、每次刷新换四码、物理格位轮换和稀疏系统码重播）。60 Hz 下目标是每秒 60 组四码，但要求显示器、屏幕链路和手机相机都能稳定跟上；识别不稳定时去掉该参数回到默认 V33/2068 B、每组保持 2 刷新的稳定档。
`--stable-lanes` 是光学稳定性实验档：仍使用四个 V33/2068 B 码，但每次显示更新只替换一组对角码，另一组保持不变。这样总的编码载荷不增加，却给滚动快门留下稳定参考；未指定 `--repeat` 时每个对角组保持 1 个刷新。它与 `--fast`、`--legacy-beamferry` 互斥。
`--dual` 是 Android 专用的双码实验档：使用两个单排横向的 V33/2068 B 码，利用原来四码下方的空白把每个码放大，未指定 `--repeat` 时每次刷新换一对。它减少同一画面内的二维码数量并提高单码成像尺寸，目标是降低四码同时换帧时的滚动快门冲突；它不改变单码数据容量，也不保证一定快于四码，网页接收端不支持该布局。
`--repeat N` 可把每组四码保持 N 个显示刷新周期（1–8，默认 2）。当手机能稳定读出当前画面但重复很多、有效新帧偏少时，可试 `--repeat 3`；它会降低理论组速率，但通常提高光学换组成功率。

按键：ESC / Q 停止，Space 暂停，I 诊断信息，F 全屏。日志：`~/.local/state/optiferry/qsend.log`。
文件必须在发送期间保持不变；不要原地改写正在发送的文件。

手机被杀后，已落盘且提交记录的段会保留。重新打开、开始扫描，再发送同一个文件即可补齐。不要清除 App 数据或删除其未完成文件。
同一文件改变分段大小会与现有批次元数据冲突；恢复时保持原来的 `--segment-size`。

## 规格差异和限制

实际 AFL2 session 字段为 16 位，原规格写成 32 位。实现保留指定的 32 位 SHA 派生，再按照原 wire 字段序列化低 16 位；salt 搜索同时保证 wire ID 非零、不冲突，详见 [UPSTREAM_COMPAT.md](UPSTREAM_COMPAT.md)。
为避免 16 位命名空间的碰撞搜索失控，首版每批最多 1536 段；8 MiB 默认支持到 12 GiB。更多数据请增加段大小，最大 32 MiB。准备阶段有搜索上限，未找到时明确报错，不发送冲突会话。

WSLg 下 qsend 默认选择 XWayland/OpenGL：本机实测约 60 Hz，直接 Wayland/Mesa 路径会退化到约 4.5 Hz；仍可用 `SDL_VIDEODRIVER` 手动覆盖。它仍是 WSL 原生程序，不需要 Windows 浏览器、Windows EXE、OBS 或虚拟摄像头。默认档在 60 Hz 下约 30 组/秒，`--fast` 约 60 组/秒；HUD/诊断是应用提交节奏，不等于物理显示器扫描时序。只使用 WSL 时，直接运行 qsend 即可；如果高速档速度仍受限，限制来自 WSLg 出屏、显示器或手机滚动快门，而不是安装方式。

默认 1.65 倍不能保证高丢包下单轮成功。想增加单轮余量可使用 `--attempt-factor 2.5`；默认值没有改动。

本版本不加密、不认证；敏感文件应在发送前自行加密。

## 开发和测试

Linux 构建依赖：C++20、CMake、SDL2、SDL2_ttf、OpenSSL、DejaVu Sans。QR 生成器已经 vendoring。
Android 构建：JDK 17、Android SDK 35、Build Tools 34、Gradle 8.9 wrapper。

```bash
export ANDROID_HOME=/path/to/android-sdk
bash scripts/build-android.sh
bash scripts/run-tests.sh
```

依赖仓库连接失败时，可选择 `OPTIFERRY_BUILD_MIRROR=1` 使用构建镜像；它不会修改上游源代码。
可用 `OPTIFERRY_GRADLE=/path/to/gradle` 指定已安装的 Gradle。
测试依赖 Node.js，但日常运行 qsend 不依赖 Node。

`qsend --selftest` 内置全部 136 个 AFL2 golden vectors，不依赖源码路径。
完整测试还覆盖 Kotlin 接收、100 MiB 丢包/乱序/重复、默认循环补段与模拟重启，以及原子状态记录的崩溃窗口。
`scripts/install-wsl.sh` 的 qsend 构建目录默认在 WSL 原生文件系统的 `$HOME/.cache/optiferry-build`，安装目标是 `$HOME/.local/bin/qsend`；不会因为安装 qsend 而写入 Windows 挂载盘。若从共享项目目录运行，脚本只读取 `/mnt/c/...` 下的源码。Android APK 构建默认也把 Gradle/Android 缓存和中间输出放在 WSL 原生 `$HOME`，只把最终 APK 复制到项目 `dist/`；Android SDK 仍可放在现有 `/mnt/f` 路径。
[TEST_RESULTS.md](TEST_RESULTS.md) 区分已执行的软件测试和未执行的目标硬件测试。
