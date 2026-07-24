# 语音功能开发前探测记录

> 状态：**阶段 1 探测完成，已进入语音输入一期实现**
> 记录日期：2026-07-18  
> 范围：本文件只记录探测证据，不代表已经确定 ASR、转码或语音发送方案。

## 1. 当前基线

- 项目运行环境：Windows 11，本地 IntelliJ IDEA。
- Maven：3.9.11。
- 项目实际编译 JDK：Oracle JDK 21.0.11。
- 微信 SDK：`io.github.lith0924:wechat-ilink-sdk:1.0.1`。
- 项目中未发现 Dockerfile，因此当前不需要修改容器镜像。
- 2026-07-18 的基线测试共 79 个测试，0 failure、0 error。
- 测试账号已经能够登录 Bot，并于 2026-07-18 14:42～14:43 收到三条真实语音。

## 2. VoiceContent 字段探测

### 2.1 Java SDK 1.0.1 的真实字段

通过检查本机 Maven 缓存中的 SDK 1.0.1 class 文件，确认语音实体
`com.lth.wechat.ilink.entity.message.content.VoiceContent` 包含以下字段：

```text
encryptQueryParam
aesKey
encryptType
encodeType
bitsPerSample
sampleRate
playtime
text
```

`MessageItemDto` 提供 `isVoice()` 和 `getVoice()`。SDK 的实体转换代码会把协议层
`VoiceItem.getText()` 直接复制到 `VoiceContent.text`，因此 Java SDK 1.0.1
**确实保留了微信侧提供的官方语音转写字段**。

### 2.2 官方文本字段可用性

目前只能确认字段存在，以下问题必须通过真实消息回答：

- 三类测试语音的 `text` 是否均非空；
- 文本是在首次收到消息时就存在，还是后续才更新；
- 安静环境、噪声环境、数字/英文场景的准确率；
- 空值比例以及是否能完全替代自建 ASR。

待采集的三条样本：

| 样本 | 内容类型 | `text` 是否有值 | 字符数 | 准确率/现象 |
| --- | --- | --- | ---: | --- |
| 1 | 第一次真实发送 | 有值 | 2 | 尚未保存文本，无法核对准确率 |
| 2 | 第二次真实发送 | 有值 | 2 | 尚未保存文本，无法核对准确率 |
| 3 | 第三次真实发送 | 有值 | 2 | 尚未保存文本，无法核对准确率 |

首次真实探测证明官方文本在消息第一次到达时已经存在，三条样本的空值率为 0/3。
但临时探测代码当时只记录“是否存在”和字符数，没有保存文本，因此还不能判断识别
准确率。探测代码现已改为把转写单独保存在 Git 忽略的
`target/voice-probe/*.transcript.txt` 文件中，且不向应用日志打印完整转写。

**决策：两者都保留，官方字段作为降级兜底。**

理由：三条真实样本证明官方 `text` 字段在首次到达时均非空，但历史探测没有保存原文，
无法证明数字、英文和噪声场景下的准确率，因此不能完全依赖官方文本。SiliconFlow 已提供
`POST /v1/audio/transcriptions` multipart ASR 接口，支持
`FunAudioLLM/SenseVoiceSmall` 和 `TeleAI/TeleSpeechASR`，单文件上限 50MB、时长上限 1 小时。
一期使用 SILK→PCM→WAV→SiliconFlow ASR 主链；转换工具未配置、ASR 未配置或调用失败时，
如果官方 `text` 非空则使用它继续进入 LLM，否则向用户发送友好失败提示。

平台证据：SiliconFlow 官方接口文档
<https://docs.siliconflow.cn/cn/api-reference/audio/create-audio-transcriptions>。

## 3. 真实语音字节流格式探测

### 3.1 临时探测代码

`BotService` 中已经加入临时诊断分支。收到语音后，它会在 JDK 21 虚拟线程中调用：

```java
client.downloadMedia(
    voice.getEncryptQueryParam(),
    voice.getAesKey()
);
```

下载结果保存到 Git 已忽略的目录：

```text
target/voice-probe/
```

日志会记录 `encodeType`、`sampleRate`、`bitsPerSample`、`playtime`、字节数、
估算码率、前 16 字节十六进制、官方文本是否存在以及下载耗时。日志不会打印
AES Key、完整媒体参数或完整转写文本。

### 3.2 三条真实样本

| 样本 | `encodeType` | 采样率 | 位深 | 时长 | 字节数 | 估算码率 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 4 | 16000 Hz | 16 bit | 1388 ms | 2263 | 13043 bps |
| 2 | 4 | 16000 Hz | 16 bit | 1146 ms | 1863 | 13005 bps |
| 3 | 4 | 16000 Hz | 16 bit | 1112 ms | 1836 | 13208 bps |

三份文件的前 16 字节完全相同：

```text
02 23 21 53 49 4c 4b 5f 56 33 0c 00 a7 2b 74 f7
```

去掉首字节 `02` 后，后续 ASCII 为：

```text
#!SILK_V3
```

因此真实下载结果是**带微信/Tencent 前导字节的 SILK V3 数据**，不是根据扩展名
推测得出。三条样本的元数据均为 16 kHz、16 bit，平均压缩码率约 13 kbps。
`playtime` 与真实语音长度相符，因此本项目中接收侧 `playtime` 的单位可确认为毫秒。

需要特别注意：接收消息元数据显示 `encodeType=4`，但 SDK 旧版源码对发送 SILK
写的是 `encodeType=6`。这说明不能把接收侧数值未经验证直接复用于发送侧。

### 3.3 解码与 ffprobe 实测

使用校验通过的 FFmpeg 8.1.2 Essentials 对三条 `.bin` 直接执行 ffprobe，三次均
返回：

```text
Invalid data found when processing input
```

直接执行“输入 SILK、输出 WAV”也失败；该 FFmpeg 构建的格式和解码器列表中均
没有裸 SILK。因此可以确认：**标准 FFmpeg 不能直接解析或转码本次收到的微信
`#!SILK_V3` 数据**，只去掉首字节也不能解决缺少 demuxer/decoder 的问题。

随后使用 MIT 许可的 `kn007/silk-v3-decoder` 源码，以本机 GCC 14.2.0 编译
专用解码器，没有使用仓库内的预编译程序。执行链路为：

```text
微信 SILK V3
→ 专用 SILK decoder（输出 16 kHz s16le PCM）
→ FFmpeg（封装为 WAV）
→ ffprobe
```

三条全部解码成功，ffprobe 结果如下：

| 样本 | PCM/WAV 格式 | 采样率 | 声道 | 解码后时长 |
| --- | --- | ---: | ---: | ---: |
| 1 | PCM signed 16-bit little-endian | 16000 Hz | 1 | 1.22 s |
| 2 | PCM signed 16-bit little-endian | 16000 Hz | 1 | 0.98 s |
| 3 | PCM signed 16-bit little-endian | 16000 Hz | 1 | 0.98 s |

由此确认本次微信语音是单声道。若最终需要自建 ASR，`AudioConverter` 不能只封装
FFmpeg，必须先调用受控的专用 SILK 解码器，再由 FFmpeg 生成供应商要求的
16 kHz、单声道 WAV/PCM。专用解码器进程同样必须配置路径、超时和退出码检查。

Go 版 SDK 中的编码枚举只能作为交叉核对材料；本结论以三份真实 Java SDK
下载结果为准。

## 4. 语音发送接口约束探测

### 4.1 SDK 1.0.1 方法签名

本机 SDK 1.0.1 提供的相关方法为：

```java
byte[] downloadMedia(String encryptQueryParam, String aesKey);

ILinkClient.MediaInfo uploadMedia(
    LoginCredentials credentials,
    int mediaType,
    String toUser,
    byte[] bytes
);

void sendVoiceMessage(
    LoginCredentials credentials,
    String to,
    String contextToken,
    ILinkClient.MediaInfo mediaInfo,
    int playtime,
    int encodeType
);
```

检查 `sendVoiceMessage` 字节码后确认：

- 语音消息类型为 3；
- 第五个 `int` 写入 `VoiceItem.playtime`；
- 第六个 `int` 写入 `VoiceItem.encodeType`，不是文本长度；
- SDK 旧版源码注释明确 `duration` 单位为毫秒，SILK 发送编码值为 6；
- SDK 旧版源码注释明确媒体上传类型为：1 图片、2 视频、3 文件、4 语音；
- 当前 SDK 没有接收文件路径的 `sendVoice()` 简化方法，发送流程是先上传字节，
  再用返回的 `MediaInfo` 发送消息。

源码证据来自 SDK 仓库历史提交 `243ca52`，并与本机 1.0.1 JAR 的反编译方法签名
和字段赋值交叉核对。

### 4.2 异常和超时

- SDK 的媒体下载失败会包装为未检查异常 `ILinkException`；
- SDK 默认 HTTP 客户端连接超时为 10 秒；
- `downloadMedia` 的 CDN 连接和读取超时均为 30 秒；
- 当前静态检查没有在上传 CDN 流程中发现明确的连接/读取超时设置，正式实现前
  需要通过受控测试进一步确认，并在必要时由业务层增加超时保护。

### 4.3 尚未确认的发送约束

对 SDK 1.0.1 的上传和发送实现做全文检查后，没有发现客户端侧的文件大小或时长
校验：`uploadMedia` 会直接加密调用方提供的整个 `byte[]`，`sendVoiceMessage`
会直接写入调用方提供的 `duration` 和 `encodeType`。因此可以确认：

- SDK 1.0.1 **自身不限制**语音文件大小和时长；
- 这不代表微信 CDN/服务端没有限制，服务端上限在现有 SDK 源码和文档中没有公开；
- 只有二期语音发送的边界受控测试才能测出服务端实际上限。

一期不实现 TTS 和语音发送。以下信息仍需要二期受控测试后才能下结论：

- 支持的具体音频编码；
- 最大文件大小；
- 最大时长；
- 服务端拒绝不支持格式时返回的具体异常/错误码。

另外，SDK 1.0.1 的 CDN 上传使用 `HttpURLConnection`，源码中没有调用
`setConnectTimeout` 或 `setReadTimeout`；即上传超时默认不受 SDK 控制。二期若
使用该接口，必须在业务层增加超时隔离，或先升级到具备可配置写入/读取超时的 SDK。

## 5. 服务器和 FFmpeg 环境

2026-07-18 初次检查结果：

```text
ffmpeg=NOT_FOUND
ffprobe=NOT_FOUND
Dockerfile=未发现
```

WinGet 安装因网络下载卡住未完成。随后从 FFmpeg 官方下载页推荐的 gyan.dev
获取便携版，下载文件的 SHA-256 与发布方提供的校验值一致：

```text
版本：FFmpeg / ffprobe 8.1.2 essentials
位置：E:\summer-projects\tools\ffmpeg\ffmpeg-8.1.2-essentials_build\bin
SHA-256：DB580001CAA24AC104C8CB856CD113A87B0A443F7BDF47D8C12B1D740584A2EC
系统 PATH：未修改
```

Windows 11 的可复现安装方式：

```powershell
winget install "FFmpeg (Essentials Build)"
```

FFmpeg 官方下载页将 gyan.dev 列为 Windows 构建提供方，gyan.dev 同时给出了上述
WinGet 安装命令。安装后需要重新打开 IDEA/终端，再执行 `ffmpeg -version` 和
`ffprobe -version` 验证 PATH。

专用 SILK 解码器从源码编译，探测用位置为：

```text
E:\summer-projects\silk-v3-decoder-reference\silk\decoder.exe
```

这些工具目前位于项目仓库之外，不会被提交到 Git。正式运行时通过
`VOICE_SILK_DECODER_PATH` 和 `VOICE_FFMPEG_PATH` 配置路径。

## 6. 一期实现后的真实验收步骤

1. 在 IDEA 中停止当前应用并重新运行，使保存本地转写的新探测代码生效。
2. 用测试账号重新扫码登录 Bot。
3. 依次发送三条不含隐私内容的中文语音：
   - 安静环境：“你好，请介绍一下杭州。”
   - 少量环境噪声：“今天天气怎么样？”
   - 数字和英文：“订单二零二六，项目名称 OpenClaw。”
4. 检查 IDEA 控制台中的 `downloadMs`、`convertMs`、`asrMs`、`llmAndReplyMs` 和 `totalMs`。
5. 确认三条语音均收到基于语音内容生成的文字回复。
6. 临时填错 `VOICE_ASR_API_KEY`，确认官方 `text` 非空时仍可降级回复。
7. 同时让 ASR 和官方文本不可用，确认微信收到友好失败提示，监听线程继续运行。
