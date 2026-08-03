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
| `liepin_search_jobs` | 搜索岗位（关键词/城市/薪资） |
| `liepin_get_job_detail` | 获取岗位详情 |
| `liepin_apply_job` | 投递单个岗位 |
| `liepin_batch_apply` | 批量搜索并投递 |

## 首次使用

1. 启动 MCP Server 后，会自动打开 Chromium 浏览器窗口
2. 在浏览器中手动访问猎聘并扫码登录
3. 登录状态会持久化到 `liepin-profile/` 目录
4. 后续启动无需重新登录（除非 cookie 过期）
