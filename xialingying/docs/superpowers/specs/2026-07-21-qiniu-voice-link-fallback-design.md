# 七牛语音链接备选通道设计

**日期：** 2026-07-21  
**状态：** 已确认设计，待实施  
**关联计划：** `docs/superpowers/plans/2026-07-21-post-claude-review-remediation.md`

## 目标

原生微信语音回复仍是唯一默认通道：

```text
最终回复文本 → SiliconFlow TTS PCM 16 kHz → Tencent SILK → iLink sendVoiceMessage
```

只有原生 SILK 编码、媒体上传或 `sendVoiceMessage` 失败时，系统才可以把同一份最终回复重新生成为 MP3，上传到七牛私有 bucket，并返回短期签名 HTTPS 播放链接。七牛通道默认关闭，最终失败时降级为纯文字回复。

## 非目标

- 不使用七牛链接替代正常的微信语音气泡。
- 不为每条消息额外调用 LLM 判断语音意图。
- 不创建 `fromUser + "_voice_intent"` 之类的虚拟会话。
- 不保存公开永久 URL。
- 不实现多云存储、复杂重试队列或后台转码平台。

## 触发条件

同时满足以下条件时才允许进入七牛备选通道：

1. `VOICE_LINK_FALLBACK_ENABLED=true`；
2. 当前回复原本需要语音交付；
3. 最终回复文本已经由正常对话流程生成；
4. 原生 SILK 编码、iLink 媒体上传或原生语音发送失败；
5. 回复文本长度没有超过配置上限。

ASR 失败、LLM 失败、用户没有请求语音、应用关闭中或限流拒绝时，不触发七牛上传。

## 数据流

```text
最终回复文本
  ├─ TTS PCM → SILK → iLink 原生语音成功 → 结束
  └─ 原生语音失败
       ├─ fallback 未启用 → 发送纯文字
       └─ fallback 已启用
            → TTS MP3
            → 大小/格式校验
            → 七牛私有 bucket
            → 生成短期签名 HTTPS URL
            → 发送“文字 + 临时播放链接”
                 └─ 任一步失败 → 发送纯文字
```

备选通道直接复用最终回复文本，不接受分类器生成的 `YES|内容`，也不重新生成回复内容。

## 组件边界

### `VoiceLinkFallbackService`

对上层暴露单一接口：

```java
Optional<URI> createTemporaryLink(String text);
```

职责：

- 检查功能开关和文本长度；
- 请求 MP3 格式 TTS；
- 校验 MP3 非空且不超过大小上限；
- 调用对象存储组件；
- 返回短期签名 HTTPS URI；
- 对外只返回成功 URI 或空结果，不泄露供应商异常详情。

### `QiniuVoiceObjectStore`

对语音层暴露：

```java
URI uploadTemporaryMp3(byte[] mp3Bytes);
```

职责：

- 校验 AK、SK、bucket、domain 和输入字节；
- 生成不可预测的 `voice/<uuid>.mp3` key；
- 使用七牛官方 SDK 完成上传和私有下载 URL 签名；
- 确保 URL 使用 HTTPS，并设置有限有效期；
- 不向调用方返回 token、响应正文或底层异常。

### 配置

配置前缀为 `ai.voice.link-fallback`：

```yaml
ai:
  voice:
    link-fallback:
      enabled: ${VOICE_LINK_FALLBACK_ENABLED:false}
      max-text-length: ${VOICE_LINK_FALLBACK_MAX_TEXT_LENGTH:1000}
      max-audio-bytes: ${VOICE_LINK_FALLBACK_MAX_AUDIO_BYTES:20971520}
      url-ttl: ${VOICE_LINK_FALLBACK_URL_TTL:15m}
      qiniu:
        access-key: ${QINIU_ACCESS_KEY:}
        secret-key: ${QINIU_SECRET_KEY:}
        bucket: ${QINIU_BUCKET:}
        domain: ${QINIU_DOMAIN:}
```

所有凭证只允许来自环境变量或本地未提交配置。

## 错误处理

- 原生发送失败：记录阶段、异常类型和掩码用户 ID，然后尝试备选通道。
- MP3 TTS 失败：发送纯文字。
- 七牛配置不完整：发送纯文字；健康详情只显示 `configured=false`。
- 七牛上传或签名失败：发送纯文字。
- 发送链接文本失败：沿用已有文本发送失败处理，不重复上传。
- 应用关闭或任务被中断：立即终止，不创建新对象，不发送降级消息。

备选路径不自动重试 TTS 或上传，避免重复计费和产生多个孤立对象。

## 安全与隐私

- bucket 必须为私有；下载使用短期签名 URL。
- URL TTL 默认 15 分钟，配置上限为 60 分钟。
- bucket 配置生命周期规则，自动删除 `voice/` 对象，建议保留不超过 24 小时。
- 强制 HTTPS domain；拒绝空 domain、userinfo 和非 HTTPS URI。
- 不记录 AK、SK、上传 token、供应商响应正文、完整对象 key、完整 URL、用户消息或语音内容。
- 日志仅允许阶段、HTTP 状态码、字节数、耗时、异常类型和 key 后 6 位。
- 用户可见错误统一为固定文案，不拼接 `Exception.getMessage()`。

## 用户体验

原生语音成功时只发送微信语音气泡。

原生语音失败且七牛备选成功时发送：

```text
<最终回复文本>

原生语音发送失败，可在 15 分钟内通过以下链接播放：
<短期签名 HTTPS URL>
```

两个语音通道都失败时只发送 `<最终回复文本>`，不向普通用户展示供应商或配置细节。

## 测试策略

单元测试必须覆盖：

- 开关关闭时不调用 MP3 TTS 和七牛；
- 原生语音成功时不调用备选服务；
- 原生语音失败且备选启用时返回链接；
- 备选服务复用最终回复文本，不调用 AIService；
- MP3 为空、过大、TTS 异常、上传异常和签名异常都降级纯文字；
- domain 非 HTTPS、配置缺失和 URL TTL 超限时拒绝配置；
- 中断或 shutdown 后不上传；
- 日志不包含凭证、token、响应正文、完整 key、完整 URL 或消息正文。

集成测试使用本地 mock HTTP/SDK adapter，不访问真实七牛。真实七牛与微信联调只放在人工验收清单中。

## 验收标准

1. 默认配置下七牛代码路径完全不执行。
2. 原生 SILK 成功时行为不变。
3. 只有原生语音失败才会生成并上传 MP3。
4. 成功链接使用 HTTPS、带过期签名且来自私有 bucket。
5. 所有备选失败路径最终发送同一份纯文字回复。
6. 自动化测试不访问真实 LLM、TTS、七牛或微信服务。
7. 服务端日志和管理页面均不出现敏感内容或完整播放 URL。
