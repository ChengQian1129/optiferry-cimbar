# Cimbar 彩码多分片 manifest

`cimbar-send` 使用 libcimbar 的普通 fountain 文件流传输多个独立输入。每个输入先被 CFC 解压到 Android 应用私有目录；`manifest.json` 描述这些文件如何组成原始文件。这个 manifest 是应用层 JSON，不是新的光学编码格式，也不再使用旧版 OPM1 固定二进制头。

## 流程

```text
cimbar-send FILE
  -> 计算完整文件 SHA-256
  -> 按默认 10 MiB（最大 19 MiB）生成 .partNN.bin
  -> 生成 manifest.json
  -> libcimbar 依次循环播放 manifest 和所有分片
  -> OptiFerry 彩码接收端校验分片并移入私有暂存目录
  -> 流式写入 Download 并校验完整文件 SHA-256 后显示保存成功
```

发送器没有 ACK。所有 `cimbar-send` 传输都要求 cadence-v2 受管 `cimbar_send`；它为每个输入分配稳定的 stream ID，每轮至少播放两倍所需 fountain block 数，并在后续轮播中延续 16 位 block 编号。block 编号空间耗尽时发送器会明确停止。接收器最多保留 8 条活动 stream，活动输入总量不超过 64 MiB；达到上限时暂缓新 stream，保留已有进度，待已有 stream 完成后再接纳后续分片。

接收端的分片数只在一个完整 fountain stream 解码并校验后增长。界面上的“解码帧率”表示相机帧处理节奏，不是文件字节速度。默认 10 MiB、不可压缩分片在 15 FPS 下约需 3 分钟完成两轮块预算；最大 19 MiB 分片约需 6 分钟，丢帧和相机识别失败会继续拉长时间。因此分片传输期间分片计数可能长时间不变，但只要解码帧率仍在变化，接收端仍在扫描。

接收前会检查私有存储空间，至少预留尚未收到的分片数据、导出到 Download 的完整文件副本和 64 MiB 余量。全新传输最坏约需原文件大小的两倍再加余量；分片已存在时会扣除匹配的已存分片。保存前会再次检查可用空间，避免在空间不足时开始复制大文件。

## manifest

文件名固定为 `manifest.json`，UTF-8 JSON 对象字段如下：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `version` | integer | 当前为 `1` |
| `tool` | string | 固定为 `cimbar-bigfile` |
| `filename` | string | 最终保存名，只取 basename |
| `total_size` | integer | 原文件字节数 |
| `sha256` | string | 原文件小写 SHA-256 |
| `chunk_size` | integer | 发送端目标分片大小 |
| `chunk_count` | integer | 分片数量，当前 1 到 120 |
| `encode_id_base` | integer | 发送端 stream ID 起始值，当前为 110 |
| `generated_at` | string | UTC 时间，仅供诊断 |
| `chunks` | array | 按 `index` 升序排列的分片描述 |

每个 `chunks` 元素包含：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `index` | integer | 从 0 开始且连续 |
| `file` | string | 例如 `archive.part00.bin`，必须是 basename |
| `size` | integer | 分片字节数 |
| `sha256` | string | 分片小写 SHA-256 |

接收端要求所有分片大小之和等于 `total_size`，逐个重新计算分片 SHA-256，再按序流式计算完整文件 SHA-256。校验通过后，私有暂存中的分片会直接流式复制到 Download；只有 MediaStore 写入成功且完整哈希再次匹配后才清理暂存数据。manifest、分片文件名和校验值不满足约束时不会交付文件。

## 发送和接收

首次在 WSL2 安装或更新受管的 sender：

```bash
bash scripts/install-cimbar-wsl.sh
```

准备并播放：

```bash
cimbar-send /mnt/f/path/to/file.zip
```

常用参数：

```bash
cimbar-send FILE --prepare-only       # 只生成并校验 manifest/分片
cimbar-send FILE --chunk-size 16MiB   # 更小的独立 stream
cimbar-send FILE --fps 20             # 需结合屏幕和相机实测
cimbar-send FILE --mode 4C            # 只准备分片；OptiFerry APK 当前固定接收 Mode B
cimbar-send FILE --new-transfer       # 使用新的准备目录
```

手机安装 `dist/OptiFerry-debug.apk` 后，选择「彩码 Cimbar」并对准发送窗口。首次使用或更新后在 WSL2 重新运行 `scripts/install-cimbar-wsl.sh`，发送器会核验自身 cadence-v2 标识。发送期间不要修改源文件；相同文件和分片大小会复用准备目录，便于重试。输入文件名不能是 `manifest.json`，这个名字由接收端保留给控制 manifest。空文件当前不支持，因为上游 sender 会跳过 0 字节输入。

`protocol/OPM1.md` 文件名保留是为了兼容旧链接；当前实现以本页的 `cimbar-bigfile` manifest 为准。
