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
qsend --legacy-beamferry small-file.bin
qsend --selftest
qsend --diagnose
```

默认 8 MiB 逻辑段、1.65 倍符号尝试量、无限循环；不生成分卷文件。
后续轮次更换 repair 序列，提高丢包时补段机会。链路没有 ACK，电脑不知道手机是否完成。

按键：ESC / Q 停止，Space 暂停，I 诊断信息，F 全屏。日志：`~/.local/state/optiferry/qsend.log`。
文件必须在发送期间保持不变；不要原地改写正在发送的文件。

手机被杀后，已落盘且提交记录的段会保留。重新打开、开始扫描，再发送同一个文件即可补齐。不要清除 App 数据或删除其未完成文件。
同一文件改变分段大小会与现有批次元数据冲突；恢复时保持原来的 `--segment-size`。

## 规格差异和限制

实际 AFL2 session 字段为 16 位，原规格写成 32 位。实现保留指定的 32 位 SHA 派生，再按照原 wire 字段序列化低 16 位；salt 搜索同时保证 wire ID 非零、不冲突，详见 [UPSTREAM_COMPAT.md](UPSTREAM_COMPAT.md)。
为避免 16 位命名空间的碰撞搜索失控，首版每批最多 1536 段；8 MiB 默认支持到 12 GiB。更多数据请增加段大小，最大 32 MiB。准备阶段有搜索上限，未找到时明确报错，不发送冲突会话。

WSLg 自动选用可用 SDL 驱动。本机 XWayland 比原生 Wayland 稳定；它仍是 WSL 原生程序，不是 Windows 浏览器。诊断是应用提交节奏测量，不等于物理显示器扫描时序。

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
[TEST_RESULTS.md](TEST_RESULTS.md) 区分已执行的软件测试和未执行的目标硬件测试。
