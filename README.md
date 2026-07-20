# xialingying

这是一个基于 Spring Boot 的微信 iLink Bot 项目。

## 项目功能分析

- 登录：访问 `/bot` 后自动获取 iLink 登录二维码，扫码确认后保存登录凭证。
- 消息监听：登录成功后轮询接收微信用户文本消息，消息和运行日志会显示在网页控制台。
- 自动回复：收到普通文本后调用 DeepSeek 兼容的聊天接口回复。
- 内置工具：支持天气查询、当前时间/日期查询。
- 手动回复：网页控制台可以选择最近发消息的用户并手动发送文本。
- 图片生成：收到图片触发消息后，调用 OpenAI 兼容的图片生成接口生成图片，并通过 iLink SDK 上传、发送图片消息。
- 图片识别：收到用户发送的图片后，下载图片并调用视觉模型识别，再用文本回复图片内容。
- 语音助手：微信 SILK 语音经过 SiliconFlow ASR、现有 LLM 和 SiliconFlow TTS 后，以可下载播放的 MP3 文件回复。

## 图片生成用法

用户发送下面这类消息会触发图片生成：

- `生成图片：一只穿宇航服的猫`
- `画一张海边日落`
- `/image cyberpunk city at night`
- `/draw a small robot`

如果图片生成成功，Bot 会直接回复图片；如果没有配置图片 API，会回复配置提示。

## 配置

建议复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml`，然后填入真实密钥。

也可以直接使用环境变量：

```bash
AI_API_KEY=你的聊天模型密钥
IMAGE_API_KEY=你的图片生成密钥
IMAGE_API_URL=https://api.openai.com
IMAGE_MODEL=gpt-image-1
IMAGE_SIZE=1024x1024
VISION_API_KEY=你的视觉模型密钥
VISION_API_URL=https://api.siliconflow.cn
VISION_MODEL=Qwen/Qwen3-VL-8B-Instruct
VOICE_ASR_API_KEY=你的SiliconFlow密钥
VOICE_TTS_API_KEY=你的SiliconFlow密钥
VOICE_SILK_DECODER_PATH=本地decoder可执行文件路径
```

`IMAGE_API_URL` 需要支持 OpenAI 兼容的 `POST /v1/images/generations`，响应可以返回 `data[0].b64_json` 或 `data[0].url`。

`VISION_API_URL` 需要支持 OpenAI 兼容的 `POST /v1/chat/completions`，并支持 `image_url` 多模态输入。使用 SiliconFlow 时，`VISION_API_KEY` 可以和 `IMAGE_API_KEY` 使用同一个 Key。

### 微信语音 MVP 配置

ASR 和 TTS 默认使用 SiliconFlow；两者可以使用同一个 Key。可选配置包括：

```text
VOICE_ASR_API_URL=https://api.siliconflow.cn
VOICE_ASR_MODEL=FunAudioLLM/SenseVoiceSmall
VOICE_TTS_API_URL=https://api.siliconflow.cn
VOICE_TTS_MODEL=FunAudioLLM/CosyVoice2-0.5B
VOICE_TTS_VOICE=FunAudioLLM/CosyVoice2-0.5B:anna
VOICE_AUDIO_PROCESS_TIMEOUT=30s
```

本地解码器需兼容 [silk-v3-decoder](https://github.com/kn007/silk-v3-decoder) 的命令行参数。构建该项目的 `decoder`，并把绝对路径配置到 `VOICE_SILK_DECODER_PATH`。入站音频在服务内部转换为 PCM S16LE、16000 Hz、单声道；出站 MP3 由 SiliconFlow 直接生成，不需要 FFmpeg 或本地 SILK encoder。

## 运行

```bash
mvn spring-boot:run
```

启动后访问：

```text
http://localhost:8080/bot
```

## 测试

```bash
mvn test
```

新增测试覆盖：

- 图片触发词识别和提示词提取。
- 图片接口返回 base64 或 URL 时的解析。
- 收到图片请求后自动生成图片，并调用 iLink SDK 的 `uploadMedia` + `sendImageMessage`。
- 图片 API 未配置时回复文本提示，并继续使用正确的 `contextToken`。
- 收到图片消息后自动调用视觉模型识别，并用文本回复识别结果。
