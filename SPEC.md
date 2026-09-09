# OptiFerry：WSL → Android 高速离线光学大文件传输系统实施规格

## 0. Work 的任务

请完整实施并交付一个名为 **OptiFerry** 的双端项目。

最终必须交付两个真正可运行的产品：

1. **WSL Sender**
   - 用户命令名：`qsend`
   - 运行于 WSL2 Linux。
   - 通过 WSLg 打开原生发送窗口。
   - 不允许依赖 Windows 浏览器。
   - 不允许依赖 Windows `.exe` 发送程序。
   - 用户日常操作必须简化为：

```bash
qsend /path/to/file
```

2. **Android Receiver**
   - 基于开源项目 `shuipashui/beamferry` 的原生 Android receiver 改造。
   - 保留 CameraX Y-plane、zxing-cpp、多 QR ROI、AFL2、LT fountain 等成熟部分。
   - 增加真正的大文件 batch receiver、自动保存、自动继续、断点恢复和最终文件校验。
   - 最终必须产出一个可以直接侧载安装的 APK。
   - 必须使用不同于 stock BeamFerry 的 `applicationId`，确保可以和原 BeamFerry 同时安装。

本项目的首要目标不是做实验 Demo，而是做一个用户可以长期实际使用的工具。

---

# 1. 产品目标

OptiFerry 的核心用户流程必须是：

电脑：

```bash
qsend ~/Downloads/large-file.iso
```

WSL 自动：

```text
读取文件
→ SHA-256
→ 逻辑分段
→ AFL2 / LT fountain
→ 四 QR
→ WSLg 原生全屏窗口
→ 循环发送
```

手机：

```text
打开 OptiFerry
→ 开始扫描 / 自动启动扫描
→ 对准电脑屏幕
→ 不再触碰手机
→ 自动接收各 segment
→ 自动落盘
→ 自动继续扫描下一 segment
→ 自动断点记录
→ 自动整文件 SHA-256
→ 完成
```

**P0 硬性体验要求：**

从手机开始扫描以后，到整个文件完成以前：

> 不得要求用户点击“保存”“继续”“下一卷”“确认”“选择目录”或任何类似按钮。

一个 500 MB / 1 GB 文件不得表现成几十个需要人工操作的小文件。

---

# 2. 明确不做的事情

V1 不得主动扩大项目范围。

以下内容明确不属于 V1：

- 不重新设计二维码识别算法。
- 不自己重新实现 Android 相机栈。
- 不用六 QR、八 QR 等新布局。
- 不把 LT fountain 换成 RaptorQ。
- 不使用网络作为必需的数据或 ACK 通道。
- 不使用 Wi-Fi、蓝牙、ADB 或 USB 作为必需通道。
- 不要求电脑有摄像头。
- 不要求手机扬声器或电脑麦克风。
- 不实现端到端加密。
- 不开发 iOS 版本。
- 不开发 Windows 原生 Sender。
- 不使用 Electron。
- 不使用 Chromium/Chrome/Edge 作为 WSL Sender 的 runtime。
- 不为了“清理设计”而修改 AFL2 wire protocol。
- 不把几 GB 文件一次性载入手机 RAM。
- 不把几 GB 文件一次性载入 WSL RAM。

V1 的原则是：

> 保留 BeamFerry 已经证明可用的高速光学层，只解决原系统真正缺失的大文件工程层。

---

# 3. 上游 BeamFerry 基线

第一步必须 clone：

```text
shuipashui/beamferry
```

开始开发时立即记录：

```bash
git rev-parse HEAD
```

将结果写入：

```text
UPSTREAM_BASELINE.md
```

一旦开始实施，本次任务期间不得再次无条件 pull 最新 upstream。

必须记录：

```text
repository
commit hash
Android versionName
Android versionCode
日期
```

当前公开 baseline 大致为 Android：

```text
0.8.135-quad-stall-classifier
versionCode 148
```

但实施时必须以实际 clone 的 commit 为准。

---

# 4. AFL2 兼容性的 Source of Truth

AFL2 不允许凭感觉重新实现。

兼容性优先级必须严格按：

```text
1. 锁定 commit 下 BeamFerry 实际 sender/receiver implementation
2. BeamFerry automated tests
3. protocol/SPEC.md
4. README
```

如果代码行为和 `protocol/SPEC.md` 的自然语言描述存在差异：

> 为保证实际 interoperability，以代码 + tests 为准。

不得擅自“修正”上游 wire behavior。

必须新增：

```text
UPSTREAM_COMPAT.md
```

记录所有发现的：

```text
documented behavior
actual implementation behavior
OptiFerry compatibility decision
```

---

# 5. AFL2 Golden Vector 测试

在写 native sender 之前，必须使用锁定版本的官方 BeamFerry sender 生成 golden vectors。

至少覆盖：

```text
payload:
0 B
1 B
2048 B
2049 B
1 MiB

layout:
single
quad

block length:
2048
2933

sequence:
0
1
k-1
k
k+1
多个 repair seq
```

保存为：

```text
protocol/test-vectors/afl2/
```

Native WSL AFL2 encoder 对相同输入：

> 必须产生 byte-for-byte 等价的 AFL2 frame。

如果不等价：

不得进入 QR renderer 阶段。

---

# 6. V1 光学 profile

用户目标硬件是：

```text
Display:
27"
3840×2160
60 Hz

Phone:
OPPO Find N5
Android
```

因此 V1 默认且唯一要求达到生产质量的高速 profile 为：

```text
Layout:       2×2 four-code
QR payload:   2068 bytes/frame/code
AFL2 block:   2048 bytes
QR version:   V33
QR ECC:       L
QR mask:      4
Symbol rate:  30 QR sets/s
Display rate: 60 presents/s
```

一组四码：

```text
┌───────────┬───────────┐
│ QR 0      │ QR 1      │
│           │           │
├───────────┼───────────┤
│ QR 2      │ QR 3      │
│           │           │
└───────────┴───────────┘
```

同一个 QR set 必须连续显示两个 display presents：

```text
Present 0 = Symbol Set A
Present 1 = Symbol Set A

Present 2 = Symbol Set B
Present 3 = Symbol Set B
```

不能简单：

```text
sleep(33.333 ms)
→ replace image
```

然后假设物理屏幕一定同步。

---

# 7. WSL Sender 技术栈

WSL sender 使用：

```text
C++20
CMake
SDL2
Wayland / WSLg
Nayuki QR Code Generator C/C++
OpenSSL EVP SHA-256
```

可以在开发/测试阶段使用 Node.js 来运行 BeamFerry upstream tests 或生成 golden vectors。

但最终：

```text
qsend
```

运行时不得需要：

```text
Node.js
npm
Chrome
Edge
Chromium
Electron
Python GUI
Windows PowerShell
Windows EXE
```

---

# 8. QR Generator

建议 vendoring：

```text
Nayuki QR Code Generator
```

必须使用 binary/byte mode。

对稳定四码 profile：

```text
ECC = LOW
version = 33
mask = 4
boostEcl = false
```

禁止：

```text
automatic mask
automatic ECC promotion
automatic QR version
Base64
text QR payload
```

AFL2 binary frame必须直接作为 QR binary data。

---

# 9. QR 像素级渲染

二维码必须按照 framebuffer physical pixels 计算。

禁止任何：

```text
bilinear filtering
antialiasing
fractional module scaling
CSS-style scaling
smooth texture filtering
subpixel module edges
```

要求：

```text
1 QR module = integer number of framebuffer pixels
```

V33 QR：

```text
149×149 modules
```

加标准 quiet zone：

```text
4 modules / side
```

总区域：

```text
157×157 modules
```

对于：

```text
3840×2160
2×2 layout
```

系统应优先选择：

```text
6 framebuffer pixels/module
```

因此每个二维码含 quiet zone：

```text
157 × 6 = 942 px
```

两行：

```text
1884 px
```

能够完整放入 2160px 高度，并留下安全 margin/gap。

3840×2160 下验收测试必须检查：

> renderer 最终选择 6 px/module，而不是 fractional scale。

---

# 10. WSLg Frame Pacing

WSLg 是级联 compositor，因此 sender 必须测量自己的实际 presentation cadence。

Renderer loop：

```text
目标 present cadence = 60 Hz
QR symbol cadence     = 30 Hz
```

优先：

```text
SDL_RENDERER_PRESENTVSYNC
```

实际测量每个 `SDL_RenderPresent()` 的时间。

维护：

```text
present interval average
p50
p95
p99
effective present Hz
missed/deferred presents
```

如果 VSYNC 不阻塞或表现异常：

使用：

```text
CLOCK_MONOTONIC_RAW
```

做 deadline-based pacing，而不是简单连续 `sleep()`。

Sender UI 应显示：

```text
Present: 59.9 Hz
Symbol: 29.9 Hz
Late presents: 0.3%
```

如果实际 presentation 明显低于：

```text
55 Hz
```

显示黄色警告。

低于：

```text
50 Hz
```

显示红色警告，但仍允许继续。

---

# 11. WSL 原生窗口

正常命令：

```bash
qsend FILE
```

必须自动：

1. 检查 WSL/WSLg。
2. 读取 display framebuffer。
3. 准备文件。
4. 打开原生 SDL fullscreen/window。
5. 开始发送。

默认：

```text
fullscreen/borderless
black background
white QR background
```

必须支持：

```text
ESC / Q = stop
Space   = pause/resume
I       = diagnostics overlay
F       = toggle fullscreen
```

不得自动打开 Windows 浏览器。

---

# 12. WSL CLI

最低必须实现：

```bash
qsend FILE
qsend --help
qsend --version
qsend --diagnose
qsend --selftest
```

高级：

```bash
qsend FILE --segment-size 8MiB
qsend FILE --attempt-factor 1.65
qsend FILE --passes 2
qsend FILE --loop
qsend FILE --windowed
qsend FILE --legacy-beamferry
```

默认等价：

```bash
qsend FILE \
  --segment-size 8MiB \
  --attempt-factor 1.65 \
  --loop
```

`--loop` 意味着：

> 发送完所有 segments 后从 segment 0 再循环。

一直到用户：

```text
ESC
```

停止。

这是为了补偿单向光链路没有 ACK 的事实。

---

# 13. 不允许物理生成“分卷文件”

用户绝不能看到：

```text
file.part001
file.part002
...
```

WSL sender 的 segmentation 必须是逻辑分段。

例如：

```text
1 GB source file
```

sender 应：

```text
pread()
↓
读取 8 MiB logical segment
↓
encode
↓
发送
↓
释放
↓
读取下一段
```

不得为了发送先在磁盘制造：

```text
256 个 part 文件
```

WSL RAM 使用量必须与总文件大小无关。

---

# 14. Large File Outer Protocol：BFB1

OptiFerry V1 不修改 AFL2。

增加一个 AFL2 payload 上层协议：

```text
BeamFerry Batch Format v1
Magic: BFB1
```

每个 logical segment 自己作为一个独立 AFL2 transfer。

AFL2 recovered payload 的开头必须为 BFB1 envelope。

所有整数：

```text
little-endian
unsigned
```

固定 header：

```text
Offset Size Field

0      4    magic = ASCII "BFB1"
4      1    version = 1
5      1    flags = 0
6      2    header_length

8      16   batch_id

24     4    session_salt
28     4    segment_index
32     4    segment_count
36     4    nominal_segment_size

40     8    original_file_size
48     8    segment_offset

56     4    segment_data_length
60     4    reserved = 0

64     32   whole_file_sha256
96     32   segment_sha256

128    2    filename_length
130    2    reserved = 0

132    N    filename UTF-8
132+N  M    raw segment bytes
```

因此：

```text
header_length = 132 + filename_length
```

---

# 15. BFB1 文件名规则

只传输：

```text
basename
```

不得传：

```text
full Linux path
```

例如：

```text
/home/user/data/movie.mkv
```

BFB1 filename：

```text
movie.mkv
```

UTF-8。

最大：

```text
240 bytes UTF-8
```

超过时 sender 必须明确报错。

不得自动静默截断。

Receiver 必须拒绝：

```text
/
\
..
NUL
control path traversal
```

---

# 16. Segmentation

默认：

```text
nominal_segment_size = 8 MiB
```

支持范围：

```text
1 MiB ≤ segment_size ≤ 32 MiB
```

V1 禁止超过：

```text
32 MiB
```

即使 AFL2 receiver 内部安全上限更高。

理由：

- 控制 Android RAM。
- 控制 LT decoder 的 K。
- 出错重试成本有限。
- 总文件大小与 decoder RAM 解耦。

计算：

```text
segment_count =
    file_size == 0
    ? 1
    : ceil(file_size / segment_size)
```

segment：

```text
segment_offset = segment_index × nominal_segment_size
```

最后 segment：

```text
segment_data_length <= nominal_segment_size
```

其他 segment：

```text
segment_data_length == nominal_segment_size
```

---

# 17. Hashing

开始发送之前：

必须计算：

```text
whole_file_sha256
```

每个 segment：

```text
segment_sha256
```

不得只依赖：

```text
AFL2 FNV
```

最终成功定义：

```text
每 segment SHA-256 正确
AND
whole file SHA-256 正确
```

否则绝不能发布为完成文件。

---

# 18. Deterministic batch_id

batch id 必须允许：

```text
同一个文件重新执行 qsend
```

被手机识别为同一个 batch。

定义：

```text
batch_material =
  ASCII("OptiFerry-BFB1-v1")
  || LE64(original_file_size)
  || whole_file_sha256
  || filename_utf8
```

计算：

```text
batch_id = SHA256(batch_material)[0..15]
```

16 bytes。

同：

```text
content + size + filename
```

产生相同 batch id。

---

# 19. Deterministic AFL2 session_id

每个 BFB1 segment 必须有稳定且唯一的 AFL2 session ID。

定义：

```text
session_material =
  ASCII("OptiFerry-AFL2-session-v1")
  || batch_id
  || LE32(session_salt)
  || LE32(segment_index)
```

```text
session_id =
  LE32(SHA256(session_material)[0..3])
```

约束：

```text
session_id != 0
```

同一个 batch 所有 segment session_id 必须唯一。

Sender：

```text
session_salt = 0
```

计算全部 session IDs。

如果出现：

```text
0
或 collision
```

则：

```text
session_salt++
```

重新计算。

直到全部：

```text
non-zero + unique
```

BFB1 header 中保存：

```text
session_salt
```

这样 receiver 完成任意一个 BFB1 segment 后，即可计算整个 batch 所有 segment 的 expected session IDs。

---

# 20. Sender 的 segment optical attempt

单个 segment 对应：

```text
k = ceil(BFB1_payload_length / 2048)
```

默认：

```text
attempt_factor = 1.65
```

本次 attempt 应发送：

```text
ceil(k × 1.65)
```

个 unique AFL2 sequence symbols。

顺序：

```text
systematic sequence
→ repair sequence
```

具体 LT sequence generator 和 AFL2 flag/magic：

> 必须复用/移植 BeamFerry 实际 upstream behavior。

不得根据本规格自行创造另一种 LT distribution。

---

# 21. 无 ACK 条件下的可靠性策略

链路明确为单向。

因此绝不能设计：

```text
sender 等待 receiver ACK
```

V1 默认采用：

```text
Segment 0 attempt
Segment 1 attempt
Segment 2 attempt
...
Segment N attempt

↓
下一 batch pass

Segment 0 attempt
Segment 1 attempt
...
```

无限循环。

Receiver：

- 已完成 segment：直接忽略其已知 AFL2 session。
- 未完成 segment：继续尝试接收。
- 一个 segment 本 pass 未恢复：不影响之后 segments。
- 下一 batch pass 再重新尝试该 segment。

因此：

> 即使一次 optical attempt 失败，也不会导致整个大文件从头报废。

---

# 22. Android Receiver 基础

Android 项目应基于 BeamFerry native receiver。

必须保留：

```text
CameraX
Y-plane analysis
zxing-cpp JNI
single / quad decoder architecture
quad calibration
ROI tracking
AFL2 parser
LT fountain decoder
existing diagnostics
```

不得为了快速开发替换为：

```text
ZXing Java
MLKit
browser WebView receiver
RGBA camera conversion
```

V1 重点不是重写 optical decoder。

---

# 23. Android applicationId

OptiFerry 必须可以和 BeamFerry 同时安装。

因此：

```text
applicationId != BeamFerry applicationId
```

推荐：

```text
org.optiferry.receiver
```

App display name：

```text
OptiFerry
```

Minimum Android：

```text
Android 10 / API 29
```

Target/compile SDK 可以沿用 upstream 当前 Android 配置。

---

# 24. Android 权限

只申请真正需要的权限。

必要：

```text
CAMERA
```

不得为了方便直接申请：

```text
MANAGE_EXTERNAL_STORAGE
```

不得要求“所有文件访问”。

Android 10+ 最终输出使用：

```text
MediaStore.Downloads
```

因为 App 对自己创建的 Downloads entry 不需要传统 storage permission。

---

# 25. Android 大文件处理原则

绝对禁止：

```text
500MB ByteArray
1GB ByteArray
whole-file in-memory assembly
```

现有 BeamFerry HighSpeedAssembler 可以继续负责：

```text
单个 ≤32 MiB optical segment
```

但完成 segment 后必须立即：

```text
validate BFB1
↓
validate segment SHA256
↓
persistent storage
↓
release assembler memory
↓
continue camera scanning
```

总 Android memory 使用必须与：

```text
total file size
```

基本无关。

---

# 26. BFB1 completion hook

当原 BeamFerry AFL2 assembler 返回一个成功恢复且通过内部校验的文件时：

首先检查：

```text
payload[0..3] == "BFB1"
```

如果不是：

> 进入 legacy BeamFerry 行为。

如果是：

不得执行原来的：

```text
pause scanner
pendingSave
show Save button
show Continue button
```

而必须：

```text
parse BFB1
validate
commit segment
reset only AFL2 transfer state
preserve camera/quad calibration where safe
immediately continue scanning
```

这是本项目最核心的 Android 改造点。

---

# 27. Android Scanner 状态机

必须明确实现：

```text
IDLE
↓
SCANNING
↓
RECEIVING_SEGMENT
↓
COMMITTING_SEGMENT
↓
SCANNING
...
↓
VERIFYING_WHOLE_FILE
↓
COMPLETE
```

错误状态：

```text
STORAGE_FULL
BAD_SEGMENT_HASH
BAD_BATCH_METADATA
WHOLE_HASH_FAILED
CAMERA_ERROR
```

不得用普通异常直接让 Activity crash。

---

# 28. Segment transition

Sender 从：

```text
session A
```

切换到：

```text
session B
```

时，如果 A 未完成：

Receiver 应：

```text
discard current in-memory LT attempt for A
record A = incomplete
begin B
```

不得：

```text
freeze app
show confirmation
reject all later segments
```

下一 batch pass 遇到 A：

重新尝试。

---

# 29. 已完成 segment 快速跳过

一旦 receiver 获得第一个有效 BFB1 segment：

它知道：

```text
batch_id
session_salt
segment_count
```

因此能够推导该 batch 所有 expected AFL2 session IDs。

对于：

```text
completed segment
```

如果收到它对应的 AFL2 session ID：

应在尽可能早的阶段：

```text
drop frame
```

不得重新运行完整 LT decoder。

这对 batch 第二轮/第三轮性能非常重要。

---

# 30. Persistent Batch State

必须在每完成一个 segment 后立即持久化。

状态至少：

```text
BFB1 version
batch_id
filename
file_size
whole_sha256
segment_count
segment_size
session_salt

completed bitmap

output URI / staging path
created time
updated time
status
```

存储必须 atomic：

```text
write state.tmp
fsync
rename → state
```

应用崩溃或被系统杀掉以后：

已完成 segment 不得丢失。

---

# 31. Android final-file storage

首选实现：

```text
MediaStore.Downloads
RELATIVE_PATH = "Download/OptiFerry"
```

当第一次识别 batch 后：

尝试创建一个 App-owned pending output entry。

建议：

```text
DISPLAY_NAME = original filename
IS_PENDING = 1
```

然后使用：

```text
ParcelFileDescriptor "rw"
```

测试 provider 是否支持 seek/random write。

如果支持：

> 使用 direct sparse/random-access output mode。

每个 segment：

```text
seek(segment_offset)
write(segment_data)
fsync
mark completed
```

这样：

```text
1GB file
```

不需要额外保存第二份 1GB staging copy。

---

# 32. Storage capability fallback

不能假设所有 Android Documents/Media provider 都支持 random seek。

因此第一次创建 output 后必须做 capability check。

如果 seek 不可用：

fallback：

```text
app-specific external storage
/batches/<batch_id>/segments/
```

每个 segment：

```text
000000.part
000001.part
...
```

用户不可见。

全部 segments 完成后：

1. 顺序读取所有 parts。
2. 计算 whole SHA-256。
3. SHA 正确后创建 MediaStore final file。
4. 顺序复制。
5. 完成以后再删除 staging。

Fallback 可能需要更多可用空间。

UI 必须明确显示：

```text
Storage mode:
Direct
```

或者：

```text
Storage mode:
Staging fallback
```

---

# 33. Whole-file completion

只有：

```text
completed_count == segment_count
```

才开始 whole-file verification。

Direct mode：

重新顺序读取最终 pending file：

```text
SHA-256
```

如果匹配：

```text
MediaStore IS_PENDING = 0
status = COMPLETE
```

如果不匹配：

```text
DO NOT publish
status = WHOLE_HASH_FAILED
```

不得把损坏文件暴露给用户。

---

# 34. Android 完成界面

完成以后：

```text
✓ Transfer complete

filename.iso
1.02 GiB

SHA-256 verified

Saved to:
Download/OptiFerry/
```

并显示：

```text
elapsed
useful average speed
optical bytes
recovered segments
batch passes observed
```

可：

```text
vibrate once
```

不得循环发通知或声音。

完成后停止 camera analysis。

---

# 35. Android Receiving UI

接收过程中至少显示：

```text
Filename
Total size

Completed:
12 / 128 segments

Logical:
96 MiB / 1.00 GiB

Current optical session
Current LT progress

Live speed
Rolling speed
Whole-session useful average

Camera FPS
QR hits/frame
calibrated slots
```

例如：

```text
ubuntu.iso

███░░░░░░░░░  23%

Segments    29 / 128
Received    232 / 1024 MiB

Live        181 KiB/s
Average     169 KiB/s

Camera      30.0 FPS
QR/frame    3.42
Slots       4/4
```

---

# 36. Android 零交互要求

首次安装可以：

```text
授权 Camera
```

这是允许的。

开始一次 transfer 可以：

```text
打开 App
点击 Start
```

也是允许的。

之后：

```text
Segment 1
Segment 2
...
Segment 128
```

整个过程中：

> 0 次用户点击。

---

# 37. 保持 Camera / Geometry 热状态

segment 完成时：

不得无必要：

```text
unbind CameraX
destroy Preview
restart Activity
clear all quad calibration
```

优先只清理：

```text
AFL2 assembler/session state
```

保留：

```text
camera stream
ROI geometry
quad calibration
decoder worker threads
```

以降低 segment boundary performance penalty。

---

# 38. Screen Awake

正在接收时：

```text
FLAG_KEEP_SCREEN_ON
```

防止 Find N5 自动熄屏。

停止/完成后释放。

不得要求永久系统 WakeLock。

---

# 39. 文件大小

所有：

```text
file size
offset
logical received bytes
```

必须内部使用至少：

```text
uint64 / Long
```

不得用 32-bit signed integer 保存文件尺寸。

V1 functional design minimum：

```text
10 GiB
```

不发生 integer overflow。

不要求真的用光学链路测试 10 GiB，但必须有 sparse-file / unit test。

---

# 40. Sender Preparation

`qsend FILE` 启动后终端显示：

```text
OptiFerry qsend

File:
  ubuntu.iso
  1024.0 MiB

Computing SHA-256...
████████████████ 100%

Batch:
  128 segments
  8 MiB each

Profile:
  four-code
  2068 B
  30 symbols/s

Opening transmitter...
```

SHA hashing 必须 streaming。

不得读完整文件进 RAM。

---

# 41. Sender Fullscreen UI

发送窗口不得只是裸二维码。

必须有状态区域，但不得侵入 QR quiet zone。

显示：

```text
OptiFerry

ubuntu.iso
1.00 GiB

Pass        1
Segment     17 / 128
Attempt     4120 / 6760 symbols

Profile     4× V33-L
Payload     2068 B
Symbols     30/s

Present     59.9 Hz
Late        0.2%

ESC Stop
Space Pause
```

二维码区域必须保持绝对纯净。

---

# 42. Sender batch cycling

默认：

```text
pass 1:
0→last

pass 2:
0→last

pass 3:
...
```

无限。

这是 P0 默认，因为没有 receiver→sender feedback。

Receiver 完成以后会停止扫描。

用户看到手机完成后：

```text
ESC
```

结束 Sender。

不得声称 sender 能知道 receiver 已经成功。

---

# 43. Finite passes

支持：

```bash
qsend FILE --passes 2
```

含义：

发送完整 batch：

```text
2 次
```

然后自动退出。

`--passes 0`：

```text
infinite
```

默认：

```text
0
```

---

# 44. Legacy compatibility mode

必须支持：

```bash
qsend --legacy-beamferry FILE
```

该模式：

- 不使用 BFB1。
- 直接生成普通 BeamFerry-compatible AFL2 transfer。
- 用于 stock BeamFerry receiver。
- 不保证 >64 MiB。
- 不提供 OptiFerry batch resume。

这样用户保留原 BeamFerry APK 作为 fallback。

---

# 45. 新 Android Receiver 对普通 BeamFerry 的兼容

如果收到的 recovered AFL2 payload：

```text
不是 BFB1
```

OptiFerry Android 应尽可能维持 stock BeamFerry 的正常文件接收功能。

也就是说：

> 新 App 不应只能接自己的 qsend。

Legacy single-file receiver 属于兼容功能。

---

# 46. Compression

V1 BFB1：

```text
NO outer compression
```

不得默默 gzip。

原因：

- 避免大文件预压缩。
- 避免临时文件。
- 避免双端额外 RAM。
- 大多数大文件本来已压缩。
- 更容易 resume 和随机 offset。

以后可以增加 streaming zstd，但不属于 V1。

---

# 47. Security

OptiFerry V1 提供：

```text
integrity
```

不提供：

```text
confidentiality
authentication
```

任何看到屏幕的人都可以捕获光学码流。

README 必须明确：

> 敏感文件应在发送前自行加密。

不得误称光学传输“天然安全”。

---

# 48. BFB1 parser defensive requirements

Receiver 对 BFB1 必须做严格校验。

拒绝：

```text
unknown version
header_length < minimum
header beyond payload
filename too long
segment_count == 0
segment_index >= segment_count
segment_size > 32 MiB
segment_offset overflow
segment_offset + data_length > file_size
non-final illegal length
inconsistent batch metadata
wrong segment SHA
path traversal filename
unreasonable segment_count
integer overflow
```

同一个 `batch_id` 后续 segment 如果：

```text
file size
filename
whole SHA
segment count
segment size
session salt
```

任意一个不一致：

必须拒绝该 segment。

---

# 49. Resource limits

Android：

默认 8 MiB segments 时，整个 receiver process 应力争：

```text
< 512 MiB RSS
```

且不得随 total file size 线性增长。

WSL sender：

不得随 total file size 线性占用 RAM。

目标：

```text
< 512 MiB RSS
```

正常 profile 下。

---

# 50. Android process death

至少保证：

如果用户完成：

```text
40 / 128 segments
```

然后 Android App 被杀：

再次打开 OptiFerry：

```text
40 segments
```

仍然是 completed。

重新运行同一：

```bash
qsend file
```

应继续补剩余 segments。

不得要求重新从 segment 0 逻辑清零。

---

# 51. Sender restart

因为：

```text
batch_id
session_id
```

是 deterministic，

所以：

```bash
Ctrl+C

qsend same-file
```

必须被 receiver 识别为：

```text
same batch
```

而不是新的传输。

如果源文件内容发生变化：

whole SHA 改变：

```text
new batch
```

---

# 52. Multiple batches

Android 可以保存多个未完成 batch 的 persistent state。

至少：

```text
8
```

个。

如果扫描到新的 batch：

- 保存当前 batch state。
- 切换到新 batch。
- 不删除旧 batch。
- 不弹 destructive confirmation。

UI 可以提供：

```text
Incomplete transfers
```

列表。

---

# 53. Disk full

接收第一个 BFB1 segment 后即可知道：

```text
original_file_size
```

必须检查可用空间。

如果明显不足：

停止该 batch：

```text
Not enough storage

Required: ...
Available: ...
```

不得继续扫描几小时以后才失败。

---

# 54. Filename collision

若目标：

```text
Download/OptiFerry/file.ext
```

已经存在：

如果 persistent state 证明它就是相同完成 batch：

```text
do not duplicate
```

否则创建：

```text
file (1).ext
file (2).ext
...
```

不得覆盖未知已有文件。

---

# 55. 项目目录

最终 repository 推荐：

```text
optiferry/
├── README.md
├── LICENSE
├── THIRD_PARTY_NOTICES.md
├── SPEC.md
├── UPSTREAM_BASELINE.md
├── UPSTREAM_COMPAT.md
│
├── protocol/
│   ├── BFB1.md
│   └── test-vectors/
│
├── wsl-sender/
│   ├── CMakeLists.txt
│   ├── src/
│   ├── include/
│   ├── third_party/
│   └── tests/
│
├── android-receiver/
│   └── ...
│
├── scripts/
│   ├── install-wsl.sh
│   ├── uninstall-wsl.sh
│   ├── build-android.sh
│   └── run-tests.sh
│
├── tests/
│   ├── integration/
│   └── protocol/
│
└── dist/
```

---

# 56. WSL install script

必须交付：

```bash
./scripts/install-wsl.sh
```

目标支持：

```text
WSL2 Ubuntu 22.04+
WSL2 Ubuntu 24.04+
x86_64
WSLg
```

脚本负责检查/安装类似：

```text
build-essential
cmake
pkg-config
libsdl2-dev
libssl-dev
```

然后：

```text
Release build
```

默认安装：

```text
~/.local/bin/qsend
```

不要强制安装到：

```text
/usr/bin
```

最后验证：

```bash
qsend --selftest
```

---

# 57. WSL diagnostics

```bash
qsend --diagnose
```

至少输出：

```text
WSL detected
WSLg detected
WAYLAND_DISPLAY
DISPLAY

screen resolution
framebuffer size

SDL video driver
renderer
vsync behavior

effective presentation rate

CPU architecture
available RAM
source filesystem
```

不得包含隐私敏感的任意文件列表。

---

# 58. Self-test

```bash
qsend --selftest
```

必须在不需要手机情况下测试：

```text
SHA256
BFB1 parser/writer
batch ID
session ID uniqueness
AFL2 golden vectors
LT encode/decode
QR V33 generation
QR binary payload size
renderer module geometry
```

返回：

```text
PASS / FAIL
```

失败 exit code 非 0。

---

# 59. Automated protocol tests

必须覆盖：

### BFB1 round trip

随机生成：

```text
1 byte
1 MiB
100 MiB simulated
10 GiB sparse metadata
```

确保：

```text
segment → reassemble → SHA match
```

### Erasure

对 AFL2/segment symbol 流：

随机删除：

```text
5%
10%
20%
```

加入：

```text
duplicates
reordering
```

确保在足够 repair symbols 下恢复正确。

### Corruption

人为修改：

```text
1 bit
```

必须被：

```text
segment SHA
或 whole SHA
```

发现。

---

# 60. Cross-language interoperability test

必须有：

```text
WSL C++ encoder
↓
Android/Kotlin or JVM-side decoder test
```

无需相机。

直接把生成的 AFL2/BFB1 binary frames feeding 到 Android assembler test harness。

最终输出 SHA 必须一致。

---

# 61. Android unit/instrumentation tests

至少：

```text
BFB1 parser
bad header
bad offsets
bad filename
bad SHA

state persistence
resume

completed-session ignore

new-session transition

whole-file verify

MediaStore abstraction mocked/faked
```

Camera 部分可以保留 upstream tests。

---

# 62. Hardware acceptance test

Work 无法接触真实 OPPO Find N5 时：

不得虚构测试通过。

必须交付：

```text
HARDWARE_TEST.md
```

供用户实测。

目标硬件：

```text
27" 3840×2160 60Hz monitor
OPPO Find N5
```

测试：

```text
1 MiB random
20 MiB random
100 MiB random
512 MiB random
```

每个最终：

```text
SHA-256 exact match
```

---

# 63. 零操作验收

100 MiB 和 512 MiB：

手机：

```text
打开 App
开始扫描
```

之后直到：

```text
Transfer complete
```

用户不得操作手机。

任何：

```text
Save
Continue
Next
Confirm
Choose location
```

弹窗：

均判定 P0 FAILED。

---

# 64. 性能验收

由于实际 optical throughput 必须以目标硬件实测，不得在开发环境凭理论值宣称。

比较：

```text
A:
upstream BeamFerry sender
+ upstream Android BeamFerry

B:
OptiFerry qsend
+ OptiFerry Android
```

相同：

```text
4-code
2068 B
30 FPS
same screen
same phone
same test file
same approximate framing
```

用 20 MiB 文件：

```text
3 runs each
```

取 median completed useful throughput。

P0 要求：

```text
OptiFerry >= 90% upstream BeamFerry median
```

Stretch target：

```text
≥180 KiB/s useful completed-file throughput
```

Stretch target 不是功能失败门槛。

不得用：

```text
sender raw QR bytes/s
```

冒充：

```text
completed useful file throughput
```

---

# 65. Segment-boundary performance

连续 segments 期间：

不得每 8 MiB：

```text
重新请求 camera permission
重建 Activity
重新初始化整个 CameraX
显示 dialog
```

segment transition stall 目标：

```text
< 500 ms
```

最好：

```text
< 200 ms
```

---

# 66. Benchmark mode

建议作为 V1.1，但若时间允许应实现：

```bash
qsend --benchmark
```

依次显示固定测试 profile。

至少：

```text
quad 2068 / 30
single 2953 / 30
quad 1465 / 50 experimental
quad 1465 / 60 experimental
```

Android 自动记录：

```text
camera FPS
QR/frame
unique symbols/s
optical payload/s
estimated useful throughput
```

完成后手机显示排名。

Benchmark 不需要网络 ACK。

默认生产模式仍：

```text
quad 2068 / 30
```

除非真实 Find N5 benchmark 证明其它模式明显更快且稳定。

---

# 67. 禁止为了 benchmark 改生产默认

不得仅因为短 benchmark：

```text
peak speed
```

高，就自动把默认改成 experimental 60 FPS。

必须比较：

```text
full-session completed throughput
reliability
stalls
QR hit rate
```

---

# 68. Build artifacts

最终必须实际生成：

```text
dist/qsend
dist/OptiFerry-debug.apk
```

以及：

```text
source repository
install-wsl.sh
build-android.sh
README.md
HARDWARE_TEST.md
```

如果 release signing 未配置：

Debug APK 可以作为第一版 sideload artifact。

但：

> 不得只交源码而不交可安装 APK。

---

# 69. Android APK 安装并存

因为使用不同 applicationId：

用户应能同时拥有：

```text
BeamFerry
OptiFerry
```

这样 BeamFerry 保留作基准和 fallback。

---

# 70. README 用户说明必须极简

首页首先只给：

### WSL

```bash
./scripts/install-wsl.sh
qsend /path/to/file
```

### Android

```text
Install OptiFerry APK
Grant camera permission
Open receiver
Point phone at screen
```

不要让用户为了正常使用理解：

```text
LT fountain
AFL2
BFB1
QR version
segment count
```

这些是内部实现。

---

# 71. CLI 错误信息

错误必须是人能理解的。

例如：

```text
ERROR: WSLg display is not available.
Run qsend from a WSL session with GUI application support.
```

而不是：

```text
SDL Error 7
```

例如：

```text
ERROR: filename exceeds the BFB1 240-byte UTF-8 limit.
```

---

# 72. Logging

WSL 日志：

```text
~/.local/state/optiferry/qsend.log
```

Android 日志中不得记录：

```text
file payload
complete raw QR data
```

可以记录：

```text
session ID
segment index
sizes
performance metrics
hash prefix
```

---

# 73. Licensing

BeamFerry 是 MIT 项目。

Nayuki QR Code Generator 也是 permissive/MIT。

保留所有必要：

```text
LICENSE
copyright
THIRD_PARTY_NOTICES
```

不得删除上游 attribution。

如果新增其它 dependency：

先检查许可证。

---

# 74. 实施顺序

严格按以下 milestone 实施。

## Milestone 0 — Baseline

- clone BeamFerry
- lock commit
- run upstream tests
- build upstream Android APK
- record baseline

不得修改代码。

## Milestone 1 — AFL2 native compatibility

- golden vectors
- C++ AFL2
- C++ LT encoder
- exact interoperability

## Milestone 2 — BFB1

- formal `BFB1.md`
- encoder
- parser
- unit tests
- deterministic IDs

## Milestone 3 — WSL renderer

- SDL2
- V33
- integer scaling
- 60Hz present / 30Hz symbols
- fullscreen
- diagnostics

## Milestone 4 — Android batch receiver

- BFB1 intercept
- persistent batch store
- auto-continue
- MediaStore
- resume
- hash verification

## Milestone 5 — integration

- generated frames → Android assembler
- simulated losses
- multi-segment 100MiB
- process restart test

## Milestone 6 — artifacts

- `qsend`
- APK
- installer
- README
- hardware test instructions

---

# 75. Work 不得提前宣布完成

以下全部存在后才能说开发完成：

```text
qsend source
qsend built binary
WSL installer
Android source
installable APK
BFB1 spec
golden vectors
automated tests
integration tests
README
hardware test guide
```

如果真实 Find N5 没有被 Work 实机测试：

必须写：

```text
Real-device optical performance remains to be validated on the target OPPO Find N5 and 27" 4K 60Hz monitor.
```

不能声称：

```text
300 KB/s verified
```

除非真的测过。

---

# 76. P0 Definition of Done

V1 只有满足下列全部条件才算完成：

1. `qsend FILE` 在 WSL 内运行。
2. 不启动 Windows 浏览器。
3. WSLg 显示原生四 QR。
4. 兼容 BeamFerry AFL2。
5. 默认 2068B / quad / 30fps。
6. 文件自动逻辑分段。
7. 手机不出现每段保存按钮。
8. 手机自动继续下一段。
9. 完成段持久化。
10. App 崩溃后完成段仍存在。
11. 同一文件重新 qsend 可以恢复。
12. 最终 whole-file SHA-256 正确。
13. 自动输出到 `Download/OptiFerry/`。
14. 100 MiB 不需要手机中途操作。
15. 512 MiB 架构和实现不依赖整体 RAM。
16. stock BeamFerry 仍可作为独立 App 保留。
17. Android APK 实际交付。
18. WSL installer 实际交付。
19. 所有自动测试通过。
20. 未实测的物理性能不得虚构。

---

# 77. 最重要的架构约束

如果实施过程中出现困难，优先级如下：

```text
Correctness
>
data integrity
>
zero phone interaction
>
resume/recovery
>
stock AFL2 optical reliability
>
speed
>
cosmetic UI
```

不得为了提高 benchmark 数字破坏可靠性。

也不得为了赶进度退化成：

```text
用户手动分卷
用户手机逐卷点击
浏览器 sender
整个文件进 RAM
```

这些均属于明确的产品失败。

---

# 78. 最终用户体验目标

最终必须接近：

```bash
$ qsend ~/Downloads/ubuntu.iso

OptiFerry 0.1.0

File       ubuntu.iso
Size       1.02 GiB
SHA-256    8e0f...

Segments   131 × 8 MiB
Mode       Quad V33-L
Payload    2068 B
Symbols    30/s

Opening transmitter...
```

手机：

```text
OptiFerry

ubuntu.iso

██████████████░░░░░
742 MiB / 1.02 GiB

Segments
93 / 131

Average
171 KiB/s

Camera
30.0 fps
4/4 slots
```

直到：

```text
✓ Transfer complete
SHA-256 verified

Download/OptiFerry/ubuntu.iso
```

整个过程中用户没有处理任何：

```text
part001
part002
part003
```

这就是 V1 的最终产品定义。