# 猎聘 MCP Server

基于 Playwright + MCP 协议的猎聘网自动投递服务。

## 安装

```bash
cd liepin-mcp-server
npm install
npx playwright install chromium
```

## 启动

```bash
node index.js
```

## MCP 工具列表

| 工具名 | 功能 |
|--------|------|
| `liepin_login_check` | 检查登录状态，未登录则打开浏览器供扫码 |
| `liepin_list_accounts` | 列出已配置账号及其登录状态、当前账号 |
| `liepin_switch_account` | 切换投递账号（每个账号独立登录态） |
| `liepin_search_jobs` | 搜索岗位（关键词/城市/薪资） |
| `liepin_get_job_detail` | 获取岗位详情 |
| `liepin_apply_job` | 投递单个岗位 |
| `liepin_batch_apply` | 批量搜索并投递 |

## 多账号

每个账号一份独立的登录态，账号间 cookie 完全隔离、互不影响：

```
liepin-profile/
├── state.json        # default 账号（沿用旧单账号登录态）
├── accountA/state.json   # 账号A（扫码一次后持久化）
└── accountB/state.json   # 账号B
```

- 在 `accounts.json` 中维护账号列表（`{ id, name }`）
- 调用 `liepin_list_accounts` 查看账号和登录状态
- 调用 `liepin_switch_account`（传 `accountId`）切换；目标账号未登录会自动弹出浏览器供扫码，登录态存到该账号自己的文件
- `default` 是内置账号，直接沿用旧的 `liepin-profile/state.json`，无需迁移
- ⚠️ 切换账号会重建浏览器上下文，请在确认当前账号正确后再投递；多账号交替猛投易触发风控

## 首次使用

1. 启动 MCP Server 后，会自动打开 Chromium 浏览器窗口
2. 在浏览器中手动访问猎聘并扫码登录
3. 登录状态会持久化到 `liepin-profile/` 目录
4. 后续启动无需重新登录（除非 cookie 过期）
