# OPM1：Cimbar 大文件分片协议

OpticalReceiver 0.2.x 的大文件路径使用 OPM1。它不是新的光学编码格式，而是放在普通 libcimbar 文件流里面的一层小容器。

## 端到端流程

```text
cimbar-send FILE
  → 计算 SHA-256
  → 按 24 MiB（默认）切分
  → 给每片加 OPM1 头和校验值
  → 调用原来的 cimbar_send 播放所有分片
  → Android 逐片还原、持久化、去重
  → 自动合并并校验完整文件 SHA-256
```

用户不需要手动合并分片。发送器没有 ACK，因此会循环播放全部分片；手机显示完整文件校验通过后，用 ESC 或 Ctrl-C 停止发送窗口。

## 为什么需要发送端准备

普通 libcimbar fountain 流的压缩后大小约受 32 MiB 限制。接收端无法从多个普通流推断它们属于同一个原文件，也无法知道顺序、总大小和完整文件哈希。

因此，Android 端新增的是“识别并合并 OPM1”，而不是让一个普通 Cimbar 流无限变大。WSL 不需要修改 `cimbar_send` 的协议实现，只需要由 `cimbar-send` 在播放前准备容器。

## 容器格式

固定头为 116 字节，之后是 UTF-8 文件名和原始 payload：

| 偏移 | 长度 | 字段 |
| ---: | ---: | --- |
| 0 | 4 | `OPM1` magic |
| 4 | 1 | 版本，目前为 1 |
| 6 | 2 | 完整头长度 |
| 8 | 16 | transfer ID |
| 24 | 4 | 分片序号，从 0 开始 |
| 28 | 4 | 分片总数 |
| 32 | 8 | 原文件字节数 |
| 40 | 8 | 分片容量 |
| 48 | 32 | 原文件 SHA-256 |
| 80 | 32 | 当前 payload SHA-256 |
| 112 | 2 | 文件名 UTF-8 字节数 |
| 114 | 2 | 保留，必须为 0 |
| 116 | N | 文件名 |

`cimbar-send` 默认使用 24 MiB payload，最大也是 24 MiB，为 Cimbar 的压缩和头部留出余量。对同一个未改变的输入文件和相同分片大小，默认 transfer ID 可重复生成，便于 Android 端断点恢复；改变分片大小会自然得到新的 ID，需要强制新批次时使用 `--new-transfer`。

## 日常命令

首次在一个 WSL 环境安装：

```bash
bash scripts/install-cimbar-wsl.sh
```

以后发送大文件只需要：

```bash
cimbar-send /mnt/f/wang_ct/CT_DICOM_complete.zip
```

常用覆盖项：

```bash
cimbar-send FILE --fps 20             # 提高发送节奏，需实测相机是否跟得上
cimbar-send FILE --mode 4C            # 兼容性测试
cimbar-send FILE --chunk-size 16MiB   # 更保守的分片大小
cimbar-send FILE --prepare-only       # 只生成 OPM1 分片，不开窗口
cimbar-send FILE --new-transfer       # 相同文件也建立新的 transfer ID
```

原项目的 `qsend` 仍然是 OptiFerry 自己的 AFL2/四 QR 发送器；它和这里的 `cimbar-send` 属于两条不同协议，不能把两者的 APK 和发送命令混用。
