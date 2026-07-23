# Admin 控制台增强 — 详细设计

> 关联：P3-29 | 前置：P0-1（XSS 防护）、P3-23（对话记忆）、P3-28（可观测性）

## 目标

将当前单页 Bot 管理界面升级为带分区面板的综合控制台，增强运维效率，同时修复安全隐患。

---

## 1. 现状分析

当前 `bot.html` 的功能：
- 二维码展示 + 登录状态
- 消息列表（轮询显示）
- 手动回复（选用户 + 编辑文本）
- 运行日志（滚动显示）

**痛点**：
- 所有功能挤在一个 700px 宽的容器中
- `innerHTML` 未转义（XSS 风险）
- 没有消息过滤/搜索
- 看不到语音处理状态
- 看不到服务统计
- 没有对话历史管理
- 移动端几乎不可用

---

## 2. 目标布局

### 2.1 桌面端

```
┌──────────────────────────────────────────────────────┐
│  iLink Bot 控制台                         [⚙ 设置]   │
├────────────┬─────────────────────┬────────────────────┤
│  登录面板  │   消息面板           │   详情面板          │
│            │                     │                    │
│  [二维码]  │ 搜索: [_______]    │  状态: 已登录 ✅    │
│            │ 过滤: [全部 ▾]     │  运行: 2h 15m       │
│  已登录    │                     │  消息: 1234 条      │
│  Bot ID:   │  ┌───────────────┐ │  用户: 56 人        │
│  xxx...    │  │ user_a: 你好  │ │                    │
│            │  │ Bot: 你好!    │ │  ── 快速操作 ──    │
│  [重新扫码]│  │ user_b: [图片] │ │  [清除对话历史]     │
│            │  │ Bot: 这是...  │ │  [导出日志]        │
│            │  └───────────────┘ │  [重置统计]        │
│            │                     │                    │
│            │  回复: [______]    │  ── 系统日志 ──    │
│            │  对象: [user_a ▾] │  ┌──────────────┐  │
│            │  [发送]            │  │ 14:23:15 收..│  │
│            │                     │  │ 14:23:18 回..│  │
│            │                     │  └──────────────┘  │
├────────────┴─────────────────────┴────────────────────┤
│  底部状态栏: 队列 3/200 | 限速桶 12 | 内存 234MB     │
└──────────────────────────────────────────────────────┘
```

### 2.2 移动端

```
┌───────────────────┐
│  iLink Bot 控制台 │
├───────────────────┤
│  [状态: 已登录]   │
│  [消息面板  ▾]    │
│  [详情面板  ▸]    │
│  [快速操作  ▸]    │
│  [系统日志  ▸]    │
├───────────────────┤
│ 队列 3/200 | 56人 │
└───────────────────┘
```

---

## 3. 文件结构

```
src/main/resources/
├── templates/
│   └── bot.html              → 简化为入口，<iframe> 或直接重写
├── static/
│   ├── css/
│   │   └── bot-console.css   → 提取自 bot.html <style> + 新增
│   └── js/
│       ├── bot-api.js         → API 调用封装
│       ├── bot-panels.js      → 面板管理器
│       ├── bot-messages.js    → 消息列表组件
│       └── bot-stats.js       → 统计/指标组件
```

---

## 4. 面板设计

### 4.1 登录面板（左侧，固定）

| 元素 | 说明 |
|------|------|
| 二维码 | 240x240，点击可放大 |
| 状态文字 | 未启动 / 等待扫码 / 已登录 |
| Bot ID | 登录后显示（掩码） |
| 重新扫码按钮 | 确认对话框 |
| 上次登录时间 | 相对时间 |

### 4.2 消息面板（中间，可滚动）

**功能增强**：

| 功能 | 当前 | 目标 |
|------|------|------|
| 消息渲染 | `innerHTML` | `textContent`（XSS 修复） |
| 消息类型图标 | 无 | 📝 文字 / 🖼 图片 / 🎤 语音 |
| 消息过滤 | 无 | 下拉：全部 / 文字 / 图片 / 语音 |
| 消息搜索 | 无 | 文本输入框实时过滤 |
| 语音处理状态 | 无 | 🔊 → 📝 → 🤖 → 🔊 进度指示 |
| 语音时长 | 无 | 显示语音消息的估计/实际时长 |
| 图片缩略图 | 无 | Base64 缩略图（< 10KB） |
| 消息时间 | 无 | 相对时间（刚刚/1分钟前/1小时前） |
| 自动滚动 | 无 | 新消息到达自动滚动到底部 |
| 加载更多 | 无 | 滚动到顶部加载历史（从持久化存储） |

**语音处理状态展示**：

```
[14:23:15] 🎤 user_a: [语音消息]
           状态: ⏳ SILK解码中...
           ↓
[14:23:16] 🎤 user_a: [语音消息]
           状态: 📝 ASR识别中...
           ↓
[14:23:18] 🎤 user_a: [语音消息]
           转写: "帮我查天气"
           状态: 🤖 LLM思考中...
           ↓
[14:23:20] 🎤 user_a: [语音消息]
           回复: 🔊 语音回复 (3.2s) | 转写: "帮我查天气"
```

### 4.3 详情面板（右侧）

**服务状态卡片**：
```
┌─────────────────────┐
│ 服务状态             │
│ iLink:   ✅ 已登录   │
│ LLM:     ✅ 正常     │
│ ASR:     ✅ 正常     │
│ TTS:     ✅ 正常     │
│ 生图API: ⚠️ 未配置  │
│ 识图API: ✅ 正常     │
└─────────────────────┘
```

**统计卡片**（每 N 秒刷新）：
```
┌─────────────────────┐
│ 今日统计             │
│ 接收消息:  1,234    │
│ 发送回复:  1,200    │
│ 语音处理:  89       │
│ 图片生成:  12       │
│ 图片识别:  45       │
│ 平均延迟:  1.2s     │
└─────────────────────┘
```

**快速操作**：
- `[清除某用户对话历史]` — 选用户 → 确认 → 调 API
- `[手动重登]` — 相当于 `/bot/restart`
- `[下载今日日志]` — 导出日志为文本文件

### 4.4 系统日志（底部）

- 保留现有黑底绿字风格
- 增加日志级别过滤（INFO/WARN/ERROR）
- 增加 "自动滚动" 开关
- 最多保留 500 条（超过后移除最旧的）

### 4.5 底部状态栏

```
队列: 3 / 200 (1.5%) | 活跃限速桶: 12 | 内存: 234MB / 512MB | 运行时间: 2h 15m
```

---

## 5. API 设计

### 5.1 新增 REST 端点

| 方法 | 路径 | 说明 | 参数 |
|------|------|------|------|
| GET | `/bot/health` | 服务健康状态 | — |
| GET | `/bot/health/metrics` | 运行指标 | — |
| GET | `/bot/history/{userId}` | 某用户的对话历史 | userId（路径参数） |
| DELETE | `/bot/history/{userId}` | 清除某用户的对话历史 | userId（路径参数） |
| GET | `/bot/voice-status` | 当前语音处理任务状态 | — |
| POST | `/bot/context/clear` | 清除某用户的感知上下文 | userId |
| GET | `/bot/logs/download` | 下载日志文件 | — |

### 5.2 现有端点增强

`GET /bot/messages` 增加字段：

```json
{
  "messages": [
    {
      "fromUser": "user_xxx",
      "replyId": "uuid",
      "content": "[语音] 帮我查天气",
      "type": "voice",           // 新增: "text" | "image" | "voice"
      "voiceStatus": "completed", // 新增: "decoding" | "transcribing" | "thinking" | "synthesizing" | "completed" | "failed"
      "voiceStageLatencyMs": {   // 新增（completed 时填充）
        "decode": 120,
        "asr": 800,
        "llm": 1500,
        "tts": 600,
        "total": 3020
      },
      "time": 1753001234567
    }
  ],
  "stats": {                     // 新增
    "totalMessages": 1234,
    "totalUsers": 56,
    "queueSize": 3,
    "queueCapacity": 200,
    "uptime": "2h 15m"
  },
  "logs": ["..."]
}
```

---

## 6. 前端技术选型

保持简单——不引入 React/Vue 构建工具链：

- **原生 JavaScript (ES6)** —— 模块化 JS 文件
- **CSS Grid / Flexbox** —— 响应式布局
- **Fetch API** —— 轮询 + REST 调用
- **No build step** —— 直接由 Spring Boot 提供静态文件

---

## 7. 安全加固

### 7.1 XSS 防护

```javascript
// bot-messages.js
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;  // 使用 textContent，不是 innerHTML
    return div.innerHTML;     // 返回已转义的 HTML
}

function renderMessage(msg) {
    const escapedUser = escapeHtml(msg.fromUser);
    const escapedContent = escapeHtml(msg.content);
    const escapedTime = escapeHtml(formatRelativeTime(msg.time));

    return `<div class="msg-item">
        <span class="user">${escapedUser}</span>
        <span class="text">${escapedContent}</span>
        <span class="time">${escapedTime}</span>
    </div>`;
}
```

### 7.2 Admin Token 管理

- 登录时读取 `BOT_ADMIN_TOKEN` 环境变量
- 所有 API 请求携带 `X-Bot-Admin-Token` 头
- Token 不对前端暴露在 DOM 中（由 JS 闭包持有）

```javascript
// bot-api.js
const ADMIN_TOKEN = getAdminTokenFromMeta();  // <meta name="admin-token" ...>

async function apiGet(path) {
    const resp = await fetch(path, {
        headers: { 'X-Bot-Admin-Token': ADMIN_TOKEN }
    });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    return resp.json();
}
```

---

## 8. 测试策略

### 自动化测试

| # | 场景 | 验证点 |
|---|------|--------|
| 1 | XSS 测试 | 用户名为 `<script>alert(1)</script>` 时页面不弹窗 |
| 2 | 消息类型图标 | text/image/voice 类型消息显示对应图标 |
| 3 | 语音状态更新 | create → processing → completed 状态流转 |
| 4 | 消息搜索 | 输入关键词后只显示匹配消息 |
| 5 | 类型过滤 | 选择"图片"后只显示图片消息 |
| 6 | 对话历史 API | GET /bot/history/{userId} 返回正确历史 |
| 7 | 清除历史 | DELETE 后 GET 返回空 |

### 手工验证

- 扫码登录流程
- 接收微信真实文字/图片/语音消息 — 显示状态
- 手动回复
- 移动端浏览器
- 长时间运行稳定性（内存不泄漏）

---

## 9. 实施阶段

| 阶段 | 内容 | 预计工作量 |
|------|------|-----------|
| 1 | 提取 CSS + JS 文件，修复 XSS | 2h |
| 2 | 三栏布局 + 消息面板增强（类型图标、过滤、搜索、时间） | 4h |
| 3 | 详情面板（服务状态、统计、快速操作） | 3h |
| 4 | 语音处理状态实时展示 | 2h |
| 5 | 底部状态栏 + 移动端适配 | 2h |
| 6 | 新增 REST API 端点 | 3h |

总计：约 16 小时（2 个工作日）
