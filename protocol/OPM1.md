# Cimbar 彩码多分片 manifest

`cimbar-send` 使用 libcimbar 的普通 fountain 文件流传输多个独立输入。每个输入先被 CFC 解压到 Android 应用私有目录；`manifest.json` 描述这些文件如何组成原始文件。这个 manifest 是应用层 JSON，不是新的光学编码格式，也不再使用旧版 OPM1 固定二进制头。

## 流程

```text
cimbar-send FILE
  -> 计算完整文件 SHA-256
  -> 按默认 10 MiB（最大 24 MiB）生成 .partNN.bin
  -> 生成 manifest.json
  -> libcimbar 依次循环播放 manifest 和所有分片
  -> OptiFerry 彩码接收端按 manifest 校验并合并
  -> 校验完整文件 SHA-256 后显示保存按钮
```

发送器没有 ACK。多输入播放时，修正版 `cimbar_send` 为每个输入分配稳定的 stream ID，并在重复轮播时继续使用新的 fountain block 编号；每次出现至少播放约两倍所需块数。block 编号是 16 位，空间耗尽后发送器会停止并要求重新开始传输。接收器最多保留 8 条活动 stream，活动输入总量不超过 64 MiB；达到上限时暂缓新 stream，保留已有进度，待已有 stream 完成后再接纳后续分片。

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

接收端要求所有分片大小之和等于 `total_size`，并在写入临时拼接文件时重新计算每个分片和完整文件的 SHA-256。manifest、分片文件名和校验值不满足约束时不会交付文件。

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

手机安装 `dist/OptiFerry-debug.apk` 后，选择「彩码 Cimbar」并对准发送窗口。发送期间不要修改源文件；相同文件和分片大小会复用准备目录，便于重试。输入文件名不能是 `manifest.json`，这个名字由接收端保留给控制 manifest。空文件当前不支持，因为上游 sender 会跳过 0 字节输入。

`protocol/OPM1.md` 文件名保留是为了兼容旧链接；当前实现以本页的 `cimbar-bigfile` manifest 为准。
