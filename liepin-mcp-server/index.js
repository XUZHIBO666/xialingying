/**
 * 猎聘 MCP Server — Playwright 浏览器自动化
 *
 * 通过 MCP 协议暴露工具给 Spring AI Agent 调用：
 *   - liepin_login_check    : 检查猎聘登录状态
 *   - liepin_list_accounts  : 列出已配置账号
 *   - liepin_switch_account : 切换投递账号
 *   - liepin_search_jobs    : 搜索岗位
 *   - liepin_apply_job      : 自动投递单个岗位
 *   - liepin_batch_apply    : 批量投递
 *
 * 多账号：每个账号一个独立的登录态目录（liepin-profile/<accountId>/state.json），
 *         切换账号 = 换一份登录态重建浏览器上下文，账号间 cookie 完全隔离。
 *
 * 使用方式：
 *   1. npm install
 *   2. npx playwright install chromium
 *   3. node index.js
 *
 * 首次使用需手动扫码登录猎聘（浏览器会弹出窗口，扫码后 cookie 持久化到 userDataDir）
 */

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";
import { chromium } from "playwright";
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import { execSync } from "child_process";

// 所有路径基于本文件所在目录（liepin-mcp-server/），不依赖启动时的工作目录。
// 因为 server 可能被 Java 应用（cwd=项目根）或手动 cd 到别处拉起，相对路径会漂移。
const __dirname = path.dirname(fileURLToPath(import.meta.url));

// ==================== 配置 ====================
const USER_DATA_DIR = path.join(__dirname, "liepin-profile");
const STORAGE_STATE_FILE = path.join(__dirname, "liepin-profile", "state.json");
const ACCOUNTS_FILE = path.join(__dirname, "accounts.json");
const DEFAULT_ACCOUNT = "default"; // 沿用旧 liepin-profile 登录态
const BASE_URL = "https://www.liepin.com";
const DEFAULT_TIMEOUT = 30000;

// ==================== 全局状态 ====================
let browser = null;
let context = null;
let page = null;
// 自动检测系统可用浏览器
const SYSTEM_CHROME = (() => {
  try { execSync('reg query "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\chrome.exe" /ve 2>nul', { stdio: 'pipe' }); return "chrome"; } catch {}
  try { execSync('reg query "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\msedge.exe" /ve 2>nul', { stdio: 'pipe' }); return "msedge"; } catch {}
  return null;
})();

// ==================== 多账号管理 ====================
// 当前账号：所有工具操作的登录态都属于该账号
let currentAccountId = DEFAULT_ACCOUNT;
let accounts = [];

/** 从 accounts.json 读取账号列表（{ id, name }[]） */
function loadAccounts() {
  try {
    if (fs.existsSync(ACCOUNTS_FILE)) {
      const data = JSON.parse(fs.readFileSync(ACCOUNTS_FILE, "utf-8"));
      if (data && Array.isArray(data.accounts)) accounts = data.accounts;
    }
  } catch (e) {
    console.error("[账号] 读取 accounts.json 失败:", e.message);
  }
}

/** 账号的浏览器数据目录（内置 Chromium 分支用） */
function accountProfileDir(id) {
  return id === DEFAULT_ACCOUNT ? USER_DATA_DIR : `${USER_DATA_DIR}/${id}`;
}

/** 账号的登录态文件（系统 Chrome 分支用） */
function accountStorageFile(id) {
  return id === DEFAULT_ACCOUNT ? STORAGE_STATE_FILE : `${USER_DATA_DIR}/${id}/state.json`;
}

// ==================== 浏览器管理 ====================

async function getBrowser(accountId = currentAccountId) {
  // 账号切换：先关闭旧账号的浏览器会话，防止 cookie 串号
  if (accountId !== currentAccountId) {
    console.error(`[账号] 切换账号: ${currentAccountId} -> ${accountId}`);
    await closeBrowser();
    currentAccountId = accountId;
  }

  // 检查当前浏览器/页面是否仍然可用
  try {
    if (browser && browser.isConnected() && page && !page.isClosed()) {
      return browser;
    }
  } catch (e) {
    console.error("[浏览器] 连接已断开，重新启动...");
  }

  // 清理旧连接
  try { if (page && !page.isClosed()) await page.close(); } catch {}
  try { if (context) await context.close(); } catch {}
  try { if (browser && browser.isConnected()) await browser.close(); } catch {}
  browser = null;
  context = null;
  page = null;

  const launchOptions = {
    headless: false,
    viewport: { width: 1280, height: 900 },
    args: [
      "--disable-blink-features=AutomationControlled",
      "--no-sandbox",
      "--disable-gpu",
    ],
  };

  const profileDir = accountProfileDir(currentAccountId);
  console.error(`[账号] 当前账号: ${currentAccountId}，登录态文件: ${accountStorageFile(currentAccountId)}`);

  // 优先使用系统 Chrome/Edge（无需下载 Playwright 浏览器）
  if (SYSTEM_CHROME) {
    console.error(`[浏览器] 使用系统 ${SYSTEM_CHROME} 浏览器`);
    launchOptions.channel = SYSTEM_CHROME;
    browser = await chromium.launch(launchOptions);
    // 加载当前账号的持久化登录状态
    const state = loadStorageState();
    const ctx = await browser.newContext({
      viewport: { width: 1280, height: 900 },
      storageState: state || undefined,
    });
    context = ctx;
    // 上下文关闭时保存登录状态到当前账号（捕获 ctx，避免 close 时 context 已被重置为 null）
    ctx.on("close", () => saveStorageState(ctx));
  } else {
    console.error("[浏览器] 使用 Playwright 内置 Chromium（需预装）");
    browser = await chromium.launchPersistentContext(profileDir, {
      ...launchOptions,
      userAgent:
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    });
    context = browser;
  }

  // 注入反检测脚本
  await context.addInitScript(() => {
    Object.defineProperty(navigator, "webdriver", { get: () => false });
  });

  const pages = context.pages();
  page = pages.length > 0 ? pages[0] : await context.newPage();
  console.error("[浏览器] 浏览器已就绪");
  return browser;
}

function loadStorageState() {
  try {
    const file = accountStorageFile(currentAccountId);
    if (fs.existsSync(file)) {
      return JSON.parse(fs.readFileSync(file, "utf-8"));
    }
  } catch {}
  return null;
}

async function saveStorageState(ctx) {
  try {
    const state = await ctx.storageState();
    const dir = accountProfileDir(currentAccountId);
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(accountStorageFile(currentAccountId), JSON.stringify(state));
  } catch (e) {
    console.error("[浏览器] 保存登录状态失败:", e.message);
  }
}

async function closeBrowser() {
  try {
    if (context && context !== browser) await context.close();
    if (browser) await browser.close();
  } catch (e) {
    // ignore
  }
  browser = null;
  context = null;
  page = null;
}

// ==================== 工具：检查登录状态 ====================

async function checkLogin() {
  await getBrowser();
  try {
    await page.goto(BASE_URL, { waitUntil: "domcontentloaded", timeout: DEFAULT_TIMEOUT });
    await page.waitForTimeout(2000);

    // 方法1：检查当前 URL（猎聘未登录会重定向到 passport/login）
    const currentUrl = page.url();
    if (currentUrl.includes("login") || currentUrl.includes("passport")) {
      return { loggedIn: false, message: "未登录，请在浏览器中扫码登录猎聘" };
    }

    // 方法2：检查页面是否有登录按钮（Playwright locator 支持 :has-text）
    const loginLink = page.locator('a:has-text("登录"), button:has-text("登录"), :has-text("立即登录")').first();
    const loginVisible = await loginLink.isVisible().catch(() => false);

    // 方法3：检查页面是否有已登录标识（昵称/头像/消息/投递记录等）
    const userElements = page.locator('[class*="nickname"], [class*="user-avatar"], [class*="user-info"], .header-user, .login-user, :has-text("我的投递"), :has-text("消息中心")');
    const userCount = await userElements.count().catch(() => 0);

    // 已登录：没有登录按钮 OR 有用户元素
    const loggedIn = (!loginVisible) || (userCount > 0);

    if (loggedIn) {
      // 登录成功后立即保存状态到当前账号，确保持久化
      try {
        await saveStorageState(context);
        console.error(`[猎聘] 登录状态已保存到账号 ${currentAccountId}`);
      } catch (e) {
        console.error("[猎聘] 保存登录状态失败:", e.message);
      }
      return { loggedIn: true, accountId: currentAccountId, nickname: "已登录用户" };
    }
    return { loggedIn: false, accountId: currentAccountId, message: "未登录，请在浏览器中扫码登录猎聘" };
  } catch (e) {
    return { loggedIn: false, accountId: currentAccountId, error: e.message };
  }
}

// ==================== 工具：账号管理 ====================

/** 列出已配置账号及其登录状态 */
async function listAccounts() {
  loadAccounts();
  const list = [];

  // default 账号（沿用旧 liepin-profile 的登录态，无需扫码重新登录）
  list.push({
    id: DEFAULT_ACCOUNT,
    name: "默认账号",
    isDefault: true,
    loggedIn: fs.existsSync(STORAGE_STATE_FILE),
  });

  for (const a of accounts) {
    if (a.id === DEFAULT_ACCOUNT) continue;
    list.push({
      id: a.id,
      name: a.name || a.id,
      isDefault: false,
      loggedIn: fs.existsSync(accountStorageFile(a.id)),
    });
  }

  return { current: currentAccountId, accounts: list };
}

/** 切换投递账号：关闭旧会话 → 打开目标账号会话 → 检查/扫码登录 */
async function switchAccount({ accountId }) {
  const id = (accountId || "").trim();
  if (!id) {
    return { error: "NO_ACCOUNT_ID", message: "请指定要切换的账号 accountId，可用账号见 liepin_list_accounts" };
  }

  loadAccounts();
  const known = accounts.some((a) => a.id === id) || id === DEFAULT_ACCOUNT;
  if (!known) {
    return {
      error: "UNKNOWN_ACCOUNT",
      message: `账号「${id}」不在 accounts.json 中，可用账号: ${[DEFAULT_ACCOUNT, ...accounts.map((a) => a.id)].join(", ")}`,
    };
  }

  // 已在目标账号：无需重建浏览器，直接确认登录态
  if (id === currentAccountId) {
    const result = await checkLogin();
    return { switched: false, ...result };
  }

  // 切换账号：关闭旧会话，防止 cookie 串号
  console.error(`[账号] 切换: ${currentAccountId} -> ${id}`);
  await closeBrowser();
  currentAccountId = id;
  const result = await checkLogin(); // 会按新账号重建上下文；未登录则弹浏览器供扫码，扫完自动存进该账号
  return { switched: true, ...result };
}

// ==================== 工具：搜索岗位 ====================

async function searchJobs({ keyword, city = "", salary = "", pageNum = 1 }) {
  await getBrowser();

  try {
    // 第一步：关键词 + 城市一起放 URL。
    // 已知城市有编码（CITY_MAP）走 URL 的 dqs 参数，比页面点击筛选器稳定得多。
    const searchUrl = buildSearchUrl(keyword, city, salary, pageNum);
    console.error(`[猎聘搜索] 搜索URL: ${searchUrl} 城市=${city}`);
    await page.goto(searchUrl, { waitUntil: "domcontentloaded", timeout: DEFAULT_TIMEOUT });
    await page.waitForTimeout(3000);

    // 检查是否被重定向到登录页
    let currentUrl = page.url();
    if (currentUrl.includes("login") || currentUrl.includes("passport")) {
      return { error: "NOT_LOGGED_IN", message: "需要登录猎聘，请先扫码登录" };
    }

    // 如果 URL 搜索没生效，回退到首页手动搜索
    if (!currentUrl.includes("key=") && !currentUrl.includes("zhaopin")) {
      console.error("[猎聘搜索] URL搜索未生效，回退到手动搜索...");
      await page.goto(BASE_URL, { waitUntil: "domcontentloaded", timeout: DEFAULT_TIMEOUT });
      await page.waitForTimeout(2000);

      const searchInput = await page.$('input[placeholder*="搜索"], input[placeholder*="职位"], input[placeholder*="公司"], .search-input input, input.search, input[type="text"]');
      if (searchInput) {
        await searchInput.click();
        await searchInput.fill("");
        await searchInput.type(keyword, { delay: 50 });
        await page.waitForTimeout(500);
        await page.keyboard.press("Enter");
        await page.waitForTimeout(4000);
      } else {
        return { error: "SEARCH_FAILED", message: "无法执行搜索，页面结构已变化" };
      }
    }

    // 第二步：城市筛选 —— 只有城市没有编码（CITY_MAP 里没有）时才回退到页面点击筛选器
    if (city && typeof city === "string" && city.trim()) {
      const c = city.trim();
      const known = Object.prototype.hasOwnProperty.call(CITY_MAP, c);
      if (known) {
        console.error(`[猎聘搜索] 城市「${c}」已通过 URL 参数生效（dqs=${CITY_MAP[c]}）`);
      } else {
        const citySelected = await selectCityOnPage(c);
        if (citySelected) {
          console.error(`[猎聘搜索] 城市筛选「${c}」已应用`);
          await page.waitForTimeout(2000); // 等结果刷新
        } else {
          console.error(`[猎聘搜索] 城市筛选「${c}」失败，显示全国结果`);
        }
      }
    }

    // 截图调试
    try { await page.screenshot({ path: path.join(__dirname, "debug-search.png") }); } catch {}

    // 检查登录状态
    currentUrl = page.url();
    if (currentUrl.includes("login") || currentUrl.includes("passport")) {
      return { error: "NOT_LOGGED_IN", message: "需要登录猎聘，请先扫码登录" };
    }

    // 提取岗位信息 —— 优先从主搜索结果列表容器抓链接，避免推荐位/导航混入全国岗位
    const jobs = await page.evaluate(() => {
      const results = [];
      const seen = new Set();

      // 定位主搜索结果列表容器（按命中优先级）
      const listSelectors = [
        "[class*='job-list']", "[class*='job-card']", "[class*='search-list']",
        "[class*='sojob-list']", "[class*='list-box']", "[class*='result-list']",
        "[class*='jobInfo']", "[class*='job-box']",
      ];
      let listEl = null;
      for (const sel of listSelectors) {
        for (const el of document.querySelectorAll(sel)) {
          if (el.offsetHeight > 0 && el.querySelector("a[href*='/job/'], a[href*='/a/']")) {
            listEl = el;
            break;
          }
        }
        if (listEl) break;
      }

      // 容器内的链接优先；无容器时回退全页，但排除祖先含「推荐/相关/看了/热门」的元素
      const anchors = listEl
        ? Array.from(listEl.querySelectorAll("a[href]"))
        : Array.from(document.querySelectorAll("a[href]")).filter((a) => {
            let n = a.parentElement;
            while (n && n !== document.body) {
              const t = (n.textContent || "").trim();
              if (/推荐职位|看了该职位|相关职位|热门职位|为你推荐|更多职位|招聘专场/.test(t)) return false;
              n = n.parentElement;
            }
            return true;
          });

      for (const a of anchors) {
        const href = a.href;
        if (!href.includes("liepin.com")) continue;
        // 只保留岗位详情链接；去掉 "lpt" —— lpt.liepin.com 是招聘方入口域名，
        // 「我要招人」等导航链接指向它，会被误当成岗位。
        if (!(href.includes("/job/") || href.includes("/a/") || href.includes("detail"))) continue;
        if (seen.has(href)) continue;
        seen.add(href);

        const title = (a.textContent || "").replace(/\s+/g, " ").trim();
        if (!title || title.length < 4 || title.length > 80) continue;
        if (/^(登录|注册|首页|职位|校园|海归|APP|更多|不限|清空|订阅|扫一扫|验证码|查看全部)$/.test(title)) continue;
        if (/^(\d|公司|城市|行业|学历|薪资|经验|区域|热门|推荐|相关|周边|其他|当前位置)/.test(title)) continue;
        // 排除招聘方入口/广告类链接
        if (/我要招人|发布职位|进入招聘|免费发布|我要招聘|招聘顾问|猎头服务/.test(title)) continue;

        let card = a.closest("li, [class*='job'], div[class*='card'], div[class*='item'], tr");
        const cardText = (card ? card.textContent : a.parentElement.textContent) || "";

        const salaryMatch = cardText.match(/(\d+[kK千]?\s*[-~—]\s*\d+[kK千]?[月年]?|\d+[万Ww]\s*[-~—]\s*\d+[万Ww]|薪资面议)/);
        const salary = salaryMatch ? salaryMatch[0] : "";

        // 提取地点
        const locMatch = cardText.match(/([京津沪渝]|[a-zA-Z]+|[一-龥]{2,4}(?:市|[省区]|州|旗|盟)?(?:[-—][一-龥]{2,4}(?:区|县|市)?)?)/);
        const location = locMatch ? locMatch[1] : "";

        results.push({
          index: results.length + 1,
          title: title,
          company: "",
          salary: salary || "薪资面议",
          location: location,
          url: href,
          description: cardText.substring(0, 200).replace(/\s+/g, " ").trim(),
        });
      }

      return results.slice(0, 20);
    });

    console.error(`[猎聘搜索] 找到 ${jobs.length} 个候选链接`);
    return { jobs, total: jobs.length, keyword, city };
  } catch (e) {
    console.error(`[猎聘搜索] 异常: ${e.message}`);
    try { await page.screenshot({ path: path.join(__dirname, "debug-search-error.png") }); } catch {}
    return { error: e.message, jobs: [], total: 0 };
  }
}

/**
 * 在搜索页面上点击城市筛选器，选择目标城市。
 * 猎聘页面上的城市筛选器通常是页面顶部的一个按钮/链接，
 * 点击后展开城市选择面板，从中选择目标城市。
 */
async function selectCityOnPage(targetCity) {
  try {
    // ① 找到城市筛选器按钮（可能显示"全国"、"北京"等当前城市名，或者是一个"城市"标签）
    const cityBtnClicked = await page.evaluate((city) => {
      const selectors = [
        "[class*='city']", "[class*='area']", "[class*='location']",
        "[class*='dqs']", "[class*='region']",
        "span:has-text('全国')", "button:has-text('全国')",
        "span:has-text('城市')", "button:has-text('城市')",
        ".city-selector", ".area-selector",
        "[class*='city-select']", "[class*='area-select']",
      ];
      for (const sel of selectors) {
        try {
          const el = document.querySelector(sel);
          if (el && el.offsetHeight > 0) {
            el.click();
            return true;
          }
        } catch (e) {}
      }
      // fallback: 找任意含"城市"或"全国"的可见元素
      const all = document.querySelectorAll("span, button, a, div");
      for (const el of all) {
        const t = (el.textContent || "").trim();
        if ((t === "全国" || t === "城市" || t.includes("城市")) && el.offsetHeight > 0) {
          el.click();
          return true;
        }
      }
      return false;
    }, targetCity);

    if (!cityBtnClicked) {
      console.error("[猎聘搜索] 未找到城市筛选器按钮");
      return false;
    }

    await page.waitForTimeout(1500);

    // ② 在城市选择面板中点击目标城市
    const cityClicked = await page.evaluate((city) => {
      // 精确匹配城市名
      const all = document.querySelectorAll("span, button, a, li, div, dd, dt");
      for (const el of all) {
        const t = (el.textContent || "").trim();
        // 精确匹配（如"郑州"、不匹配"郑州新区"这种包含关系要看情况）
        if (t === city || t.startsWith(city)) {
          if (el.offsetHeight > 0) {
            el.click();
            return true;
          }
        }
      }
      // 宽松匹配
      for (const el of all) {
        const t = (el.textContent || "").trim();
        if (t.includes(city) && el.offsetHeight > 0) {
          el.click();
          return true;
        }
      }
      return false;
    }, targetCity);

    if (!cityClicked) {
      console.error(`[猎聘搜索] 在城市面板中未找到「${targetCity}」`);
      // 截图查看城市面板
      try { await page.screenshot({ path: path.join(__dirname, "debug-city-panel.png") }); } catch {}
      return false;
    }

    return true;
  } catch (e) {
    console.error(`[猎聘搜索] 城市筛选异常: ${e.message}`);
    return false;
  }
}

function buildSearchUrl(keyword, city, salary, pageNum) {
  // 猎聘搜索 URL 格式
  const params = new URLSearchParams();
  params.set("key", keyword);
  if (city) params.set("dqs", encodeCity(city));
  if (salary) params.set("salary", salary);
  if (pageNum > 1) params.set("currentPage", String(pageNum));
  return `${BASE_URL}/zhaopin/?${params.toString()}`;
}

/** 猎聘城市编码（常用城市映射） */
const CITY_MAP = {
  "北京": "010", "上海": "020", "广州": "050", "深圳": "060",
  "杭州": "080", "成都": "090", "南京": "070", "武汉": "170",
  "西安": "200", "苏州": "120", "重庆": "040", "长沙": "190",
  "天津": "030", "郑州": "180", "东莞": "100", "青岛": "160",
  "合肥": "150", "厦门": "110", "大连": "140", "宁波": "130",
};

function encodeCity(city) {
  return CITY_MAP[city] || city;
}

// ==================== 工具：获取岗位详情 ====================

async function getJobDetail({ url }) {
  await getBrowser();
  try {
    await page.goto(url, { waitUntil: "domcontentloaded", timeout: DEFAULT_TIMEOUT });
    await page.waitForTimeout(3000);

    const detail = await page.evaluate(() => {
      const title = document.querySelector("h1, .job-title, .job-name, [class*='job-title']");
      const company = document.querySelector(".company-name, [class*='company']");
      const salary = document.querySelector(".salary, [class*='salary']");
      const desc = document.querySelector(
        ".job-description, .job-detail, [class*='job-desc'], .content-word, .job-main-content"
      );
      const requirements = document.querySelector(
        ".job-requirements, [class*='require'], .job-qualifications"
      );

      return {
        title: title ? title.textContent.trim() : "",
        company: company ? company.textContent.trim() : "",
        salary: salary ? salary.textContent.trim() : "",
        description: desc ? desc.textContent.trim() : "",
        requirements: requirements ? requirements.textContent.trim() : "",
      };
    });

    // 用 Playwright locator 检测沟通按钮（:has-text 在浏览器 evaluate 里无效，必须放这里）
    const chatBtnExists = await page
      .locator("button:has-text('立即沟通'), button:has-text('沟通'), a:has-text('立即沟通')")
      .count()
      .then((c) => c > 0)
      .catch(() => false);
    detail.chatBtnExists = chatBtnExists;

    return detail;
  } catch (e) {
    return { error: e.message };
  }
}

// ==================== 工具：投递岗位（聊天页顶部「简历投递」） ====================

/**
 * 猎聘投递流程（根据实际 UI）：
 *   1. 从搜索结果页点击岗位 → 直接进入聊天页（猎聘1）
 *   2. 聊天页顶部有「简历投递」按钮
 *   3. 点击「简历投递」发送简历
 *   4. 可选：在输入框发一段问候语
 *
 * 注意：猎聘点击搜索结果中的岗位后直接进入 IM 聊天页面，
 *       不是传统的岗位详情页，投递通过聊天顶部的「简历投递」完成。
 */
async function applyJob({ url, resumeText = "" }) {
  await getBrowser();
  try {
    // 1. 打开岗位链接（可能是聊天页或详情页）
    await page.goto(url, { waitUntil: "domcontentloaded", timeout: DEFAULT_TIMEOUT });
    await page.waitForTimeout(3000);

    // 2. 检查是否已登录
    const currentUrl = page.url();
    if (currentUrl.includes("login") || currentUrl.includes("passport")) {
      return { success: false, error: "NOT_LOGGED_IN", message: "需要登录猎聘，请先在浏览器中扫码" };
    }

    // 2.5 岗位可能已暂停招聘（页面显示「该职位已暂停招聘」）
    const paused = await page
      .getByText("该职位已暂停招聘", { exact: false })
      .first()
      .isVisible()
      .catch(() => false);
    if (paused) {
      return { success: false, error: "JOB_PAUSED", message: "该职位已暂停招聘，无法投递" };
    }

    await page.screenshot({ path: path.join(__dirname, "debug-apply-page.png") });

    // 点击「发简历」→ 等待反馈 → 只有明确 success 才判成功，其余一律失败
    const tryDeliver = async () => {
      const clicked = await clickResumeDelivery();
      if (!clicked.clicked) return { clicked: false };

      // 先查结果；只有 unknown（没弹成功/失败提示）时，才可能是有「选择附件简历 → 立即投递」
      // 确认弹窗没点。IM 流程里 clickResumeDelivery 内部已点过确认按钮，这里若结果已明确就绝不重复点，
      // 避免二次投递。
      const settle = async () => {
        await page.waitForTimeout(2000);
        return checkDeliveryResult();
      };

      let r = await settle();
      if (r.status !== "unknown") return { clicked: true, result: r };

      // unknown：点掉确认弹窗（若有），再查一次
      await clickConfirmIfPresent();
      r = await settle();
      if (r.status !== "unknown") return { clicked: true, result: r };

      // 仍 unknown：弹窗可能渲染慢，再确认一次，然后等 toast / 按钮状态变化
      await page.waitForTimeout(1000);
      await clickConfirmIfPresent();
      r = await settle();
      if (r.status !== "unknown") return { clicked: true, result: r };

      const buttonState = await page.evaluate(() => {
        const els = document.querySelectorAll("button, a, span, div");
        for (const el of els) {
          const t = (el.textContent || "").trim();
          if (/已投递|已发送|投递成功|简历已投递/.test(t) && el.offsetHeight > 0) {
            const rect = el.getBoundingClientRect();
            if (rect.width > 0 && rect.height > 0 && rect.top < window.innerHeight * 0.35) {
              return t;
            }
          }
        }
        return "";
      });
      if (buttonState) {
        return { clicked: true, result: { status: "success", message: `按钮状态已变为「${buttonState}」` } };
      }
      // 点了投递但始终无明确反馈 → 判定为无法确认成功（不乐观判成功）
      return { clicked: true, result: { status: "unknown", message: "已点击投递但未检测到成功/失败反馈" } };
    };
    const confirmSuccess = (r) => r.status === "success";
    const confirmFail = (r) => r.status === "fail";

    // 3. 先尝试直接投递（如果已经是聊天页）
    let attempt = await tryDeliver();
    if (attempt.clicked && confirmSuccess(attempt.result)) {
      return { success: true, ...attempt.result };
    }
    if (attempt.clicked && confirmFail(attempt.result)) {
      await page.screenshot({ path: path.join(__dirname, "debug-delivery-fail.png") });
      return { success: false, ...attempt.result };
    }
    // clicked 但 unknown：可能点到非投递元素，降级走「立即沟通」完整流程
    if (!attempt.clicked) {
      await dumpInteractiveElements("打开岗位后未找到发简历按钮");
    }
    console.error("[猎聘投递] 直接投递未确认成功，尝试进入聊天流程...");

    // 4. 点击「立即沟通」打开聊天面板，再找「发简历」
    const chatOpened = await clickChatButton();
    if (chatOpened) {
      await page.waitForTimeout(4000);
      await page.screenshot({ path: path.join(__dirname, "debug-chat-panel.png") });

      // 重试 3 次（聊天面板可能异步渲染）
      for (let i = 0; i < 3; i++) {
        const retry = await tryDeliver();
        if (retry.clicked && confirmSuccess(retry.result)) {
          return { success: true, ...retry.result };
        }
        if (retry.clicked && confirmFail(retry.result)) {
          await page.screenshot({ path: path.join(__dirname, "debug-delivery-fail.png") });
          return { success: false, ...retry.result };
        }
        await page.waitForTimeout(1500);
      }
    }

    // 5. 仍无法确认成功 → 明确失败（不再乐观判成功）
    await dumpInteractiveElements("聊天流程后仍未找到发简历按钮");
    await page.screenshot({ path: path.join(__dirname, "debug-no-resume-delivery.png") });
    return {
      success: false,
      error: "NO_RESUME_DELIVERY",
      message: "未找到并确认「发简历」发送成功，页面布局可能变化，已截图 debug-no-resume-delivery.png",
    };
  } catch (e) {
    console.error(`[猎聘投递] 异常: ${e.message}`);
    return { success: false, error: e.message };
  }
}

/**
 * 诊断辅助：把当前页面的 URL、标题和所有可见可点击元素（含关键词匹配）输出到 stderr。
 * 用于定位猎聘真实页面上的投递/沟通按钮文本 —— 因为截图无法直接判读，只能靠 DOM dump。
 */
async function dumpInteractiveElements(reason) {
  try {
    const info = await page.evaluate(() => {
      const isVisible = (el) => {
        if (el.offsetHeight <= 0) return false;
        const r = el.getBoundingClientRect();
        return r.width > 0 && r.height > 0;
      };
      const norm = (s) => (s || "").replace(/\s+/g, " ").trim().substring(0, 60);

      // ① 所有 button / a / role=button / class 含 btn/deliver/apply/chat 的可点击元素
      const clickables = [];
      const seen = new Set();
      for (const el of document.querySelectorAll(
        "button, a, [role='button'], [class*='btn'], [class*='deliver'], [class*='apply'], [class*='chat']"
      )) {
        const text = norm(el.textContent);
        if (!text || text.length < 2 || !isVisible(el)) continue;
        const r = el.getBoundingClientRect();
        const key = `${el.tagName}|${text}|${Math.round(r.top)}`;
        if (seen.has(key)) continue;
        seen.add(key);
        clickables.push({
          tag: el.tagName.toLowerCase(),
          text,
          role: el.getAttribute("role") || "",
          cls: norm(typeof el.className === "string" ? el.className : ""),
          top: Math.round(r.top),
        });
      }

      // ② 含「简历/投递/沟通/应聘/申请」关键词的可见元素（短文本）
      const keywords = [];
      for (const el of document.querySelectorAll("button, a, span, div, li, [role='button']")) {
        const text = norm(el.textContent);
        if (!text || text.length > 30 || !isVisible(el)) continue;
        if (!/简历|投递|沟通|应聘|申请|聊一聊/.test(text)) continue;
        const r = el.getBoundingClientRect();
        keywords.push({ tag: el.tagName.toLowerCase(), text, top: Math.round(r.top) });
      }

      return { url: location.href, title: document.title, clickables, keywords };
    }).catch(() => null);

    if (!info) {
      console.error(`[DOM-DUMP] ${reason}: 页面 evaluate 失败`);
      return;
    }
    console.error(`[DOM-DUMP] ${reason} | url=${info.url} | title=${info.title}`);
    console.error(`[DOM-DUMP] 可点击元素(${info.clickables.length}): ${JSON.stringify(info.clickables.slice(0, 25))}`);
    console.error(`[DOM-DUMP] 含关键词元素(${info.keywords.length}): ${JSON.stringify(info.keywords.slice(0, 30))}`);
  } catch (e) {
    console.error(`[DOM-DUMP] ${reason}: ${e.message}`);
  }
}

/** 点击「立即沟通」按钮打开聊天面板（岗位详情页 → 聊天） */
async function clickChatButton() {
  // 详情页聊天按钮有两种文案：btn-main 的「继续聊」或「聊一聊」，以及左侧「继续聊」(btn-chat)
  const keywords = ["继续聊", "聊一聊", "立即沟通", "在线沟通"];
  for (const kw of keywords) {
    for (const role of ["button", "link"]) {
      const loc = page.getByRole(role, { name: kw });
      const count = await loc.count().catch(() => 0);
      for (let i = 0; i < count; i++) {
        const el = loc.nth(i);
        if (!(await el.isVisible().catch(() => false))) continue;
        try {
          await el.click({ timeout: 3000 });
          console.error(`[沟通] 已点击 <${role}>「${kw}」`);
          return true;
        } catch (e) {}
      }
    }
  }
  console.error("[沟通] 未找到「立即沟通」按钮");
  return false;
}

/**
 * 点击投递确认弹窗里的确认按钮。
 *
 * 猎聘点「投简历」/「发简历」后，常弹出「选择附件简历」对话框，
 * 底部是「立即投递」确认按钮（DOM dump 里 button top=516）。必须点它才算真正投递。
 * 找不到弹窗时静默返回 false，不影响主流程。
 */
async function clickConfirmIfPresent() {
  const confirmKeywords = ["立即投递", "确认投递", "确定投递", "确认"];
  for (const kw of confirmKeywords) {
    // 优先 button / link；「确认」太宽泛，只在弹窗容器内找，避免误点
    for (const role of ["button", "link"]) {
      const loc = page.getByRole(role, { name: kw });
      const count = await loc.count().catch(() => 0);
      for (let i = 0; i < count; i++) {
        const el = loc.nth(i);
        if (!(await el.isVisible().catch(() => false))) continue;
        // 「确认」关键字过宽，限定在弹窗/对话框容器内
        if (kw === "确认" && !(await insideDialog(el))) continue;
        try {
          await el.click({ timeout: 3000 });
          console.error(`[发简历] 已点击确认弹窗按钮 <${role}>「${kw}」`);
          return true;
        } catch (e) {}
      }
    }
  }
  return false;
}

/** 判断元素是否位于弹窗/对话框容器内（用于限定「确认」这类宽泛按钮） */
async function insideDialog(el) {
  return await el
    .evaluate((node) => {
      let n = node;
      while (n && n !== document.body) {
        const cls = (n.className && typeof n.className === "string" ? n.className : "") || "";
        if (/dialog|modal|popup|ant-modal|ant-drawer|confirm/.test(cls)) return true;
        n = n.parentElement;
      }
      return false;
    })
    .catch(() => false);
}

/**
 * 点击聊天页顶部的「简历投递」按钮。
 *
 * 精确定位策略（Playwright locator 层，`:has-text`/getByRole 才真正生效；
 * 浏览器原生 evaluate 里的 :has-text 是无效选择器，会导致找不到真按钮）：
 *   1. 先用 getByRole 按 accessible-name 找 button/link（substring 匹配）
 *   2. 兜底用 getByText，但限定页面顶部工具栏区域，避免点到导航/菜单/推荐卡片
 *   3. 只点可见元素，返回被点击元素的信息，供上层验证是否真投递成功
 */
async function clickResumeDelivery() {
  // 详情页投递按钮是「投简历」，IM 聊天窗口里的是「发简历」，两者都要覆盖
  const keywords = ["投简历", "发简历", "简历投递", "投递简历", "发送简历", "立即投递"];

  // ① 岗位详情页投递区（.job-apply-container / .apply-box）内的按钮 —— 详情页投递主按钮是「投简历」
  const applyZones = page.locator(".job-apply-container, .apply-box, [class*='job-apply']");
  const zoneCount = await applyZones.count().catch(() => 0);
  for (let z = 0; z < zoneCount; z++) {
    for (const kw of keywords) {
      const inner = applyZones.nth(z).getByText(kw, { exact: false });
      const c = await inner.count().catch(() => 0);
      for (let i = 0; i < c; i++) {
        const el = inner.nth(i);
        if (!(await el.isVisible().catch(() => false))) continue;
        try {
          await el.click({ timeout: 3000 });
          console.error(`[发简历] 详情页投递区已点击「${kw}」`);
          return { clicked: true, text: kw, role: "job-apply" };
        } catch (e) {}
      }
    }
  }

  // ② IM 聊天弹窗内的动作按钮（.chatwin-action / im-ui-basic-chat）—— 聊天窗口底部的「发简历」
  const imZones = page.locator(
    ".ant-im-modal-wrap, .im-ui-basic-chat-wrapper, .chatwin-action, [class*='im-ui-chat-input']"
  );
  const imCount = await imZones.count().catch(() => 0);
  for (let z = 0; z < imCount; z++) {
    for (const kw of keywords) {
      const inner = imZones.nth(z).getByText(kw, { exact: false });
      const c = await inner.count().catch(() => 0);
      for (let i = 0; i < c; i++) {
        const el = inner.nth(i);
        if (!(await el.isVisible().catch(() => false))) continue;
        try {
          await el.click({ timeout: 3000 });
          console.error(`[发简历] IM窗口已点击「${kw}」`);
          return { clicked: true, text: kw, role: "im" };
        } catch (e) {}
      }
    }
  }

  // ③ 全页兜底：button / link（区域放宽到 60%，之前 35% 把 IM 弹窗里的按钮挡掉了）
  const innerHeight = await page.evaluate(() => window.innerHeight).catch(() => 900);
  const region = Math.floor(innerHeight * 0.6);
  for (const role of ["button", "link"]) {
    for (const kw of keywords) {
      const loc = page.getByRole(role, { name: kw });
      const count = await loc.count().catch(() => 0);
      for (let i = 0; i < count; i++) {
        const el = loc.nth(i);
        if (!(await el.isVisible().catch(() => false))) continue;
        const box = await el.boundingBox().catch(() => null);
        if (box && box.y > region) continue;
        try {
          await el.click({ timeout: 3000 });
          console.error(`[发简历] 全页已点击 <${role}>「${kw}」`);
          return { clicked: true, text: kw, role };
        } catch (e) {}
      }
    }
  }

  console.error("[发简历] 未找到投递按钮（详情页无「投简历」，IM窗口无「发简历」）");
  return { clicked: false };
}

/** 在聊天输入框中填写问候语并发送 */
async function fillAndSendMessage(resumeText) {
  const greeting = generateGreeting(resumeText);

  // 先填写输入框
  const filled = await page.evaluate((msg) => {
    const inputSelectors = [
      "textarea",
      "[contenteditable='true']",
      "[class*='chat-input'] textarea",
      "[class*='chat-input'] input",
      "[class*='message-input']",
      "[class*='im-input'] textarea",
      "[placeholder*='请输入']",
      "[placeholder*='说点什么']",
      "[placeholder*='发送消息']",
    ];
    for (const sel of inputSelectors) {
      try {
        const input = document.querySelector(sel);
        if (input && input.offsetHeight > 0) {
          if (input.isContentEditable) {
            input.textContent = msg;
          } else {
            input.value = msg;
            input.dispatchEvent(new Event("input", { bubbles: true }));
            input.dispatchEvent(new Event("change", { bubbles: true }));
          }
          return true;
        }
      } catch (e) { /* continue */ }
    }
    return false;
  }, greeting);

  if (!filled) return;

  await new Promise((r) => setTimeout(r, 500));

  // 点击发送按钮
  const clicked = await page.evaluate(() => {
    const sendSelectors = [
      "button:has-text('发送')",
      "button:has-text('发 送')",
      "[class*='send-btn']",
      "[class*='sendBtn']",
      ".chat-send",
      ".im-send-btn",
    ];
    for (const sel of sendSelectors) {
      try {
        const btn = document.querySelector(sel);
        if (btn && btn.offsetHeight > 0) {
          btn.click();
          return true;
        }
      } catch (e) { /* continue */ }
    }
    return false;
  });

  if (!clicked) {
    await page.keyboard.press("Enter");
  }
}

/**
 * 检查投递/简历发送结果。
 *
 * 废除「乐观判成功」：只认明确的成功/失败反馈（toast/弹窗/提示条）。
 * 返回 { status: "success" | "fail" | "unknown", message }
 *   - success : 明确出现成功提示
 *   - fail    : 明确出现失败/受限提示
 *   - unknown : 无明确反馈，不能确认投递成功（宁可漏报，不可误报）
 *
 * 扫描范围限定在反馈类元素（toast/tip/message/notice/dialog/modal/popup），
 * 不再扫全页 —— 避免导航栏/历史投递记录里的"已投递"等文字造成假阳性。
 */
async function checkDeliveryResult() {
  const successKeywords = ["发送成功", "投递成功", "已发送", "简历已发送", "简历投递成功", "投递已成功"];
  const failKeywords = [
    "发送失败", "投递失败", "今日沟通已达上限", "今日投递已达上限",
    "请完善简历", "简历不完整", "对方已设置", "操作频繁", "频繁操作", "请先登录",
  ];

  const result = await page.evaluate(({ successKeywords, failKeywords }) => {
    const regions = document.querySelectorAll(
      "[class*='toast'], [class*='tip'], [class*='message'], [class*='notice'], " +
      "[class*='dialog'], [class*='modal'], [class*='popup'], [class*='Msg'], [class*='Toast']"
    );
    for (const el of regions) {
      const text = (el.textContent || "").trim();
      if (!text) continue;
      for (const kw of successKeywords) {
        if (text.includes(kw)) return { status: "success", message: text.substring(0, 120) };
      }
      for (const kw of failKeywords) {
        if (text.includes(kw)) return { status: "fail", message: text.substring(0, 120) };
      }
    }
    return null;
  }, { successKeywords, failKeywords });

  if (result) return result;
  return { status: "unknown", message: "未检测到明确的投递成功/失败反馈，无法确认投递成功" };
}

/** 生成简短问候语（通用版） */
function generateGreeting(resumeText) {
  if (!resumeText) return "您好，我对这个岗位很感兴趣，期待与您沟通！";

  const lines = resumeText.split("\n").filter((l) => l.trim()).slice(0, 10);
  const fullText = lines.join(" ");

  let expInfo = "";
  const expMatch = fullText.match(/(\d+[\s-]*年.*?经验|应届|[2-9]\d岁)/);
  if (expMatch) expInfo = expMatch[0];

  let eduInfo = "";
  const eduMatch = fullText.match(/(本科|硕士|博士|大专|研究生)/);
  if (eduMatch) eduInfo = eduMatch[0];

  const parts = [];
  if (expInfo) parts.push(`我有${expInfo}`);
  if (eduInfo) parts.push(`${eduInfo}学历`);
  const selfIntro = parts.length > 0 ? parts.join("，") + "。" : "";

  return `您好，我对这个岗位非常感兴趣。${selfIntro}我的背景与该岗位要求匹配，期待与您进一步沟通！`;
}

// ==================== 工具：批量投递 ====================

async function batchApply({ keyword, city, maxCount = 5, resumeText = "" }) {
  // 先搜索
  const searchResult = await searchJobs({ keyword, city, pageNum: 1 });
  if (searchResult.error) return searchResult;
  if (!searchResult.jobs || searchResult.jobs.length === 0) {
    return { applied: 0, total: 0, message: "没有找到匹配的岗位" };
  }

  const jobs = searchResult.jobs.slice(0, Math.min(maxCount, searchResult.jobs.length));
  const results = [];
  let successCount = 0;
  let failCount = 0;

  for (const job of jobs) {
    console.error(`[批量投递] ${job.title} @${job.company}`);
    const result = await applyJob({ url: job.url, resumeText });
    results.push({
      title: job.title,
      company: job.company,
      ...result,
    });

    if (result.success) {
      successCount++;
    } else {
      failCount++;
    }

    // 随机延迟 2-5 秒，避免被风控
    const delay = 2000 + Math.floor(Math.random() * 3000);
    await new Promise((r) => setTimeout(r, delay));
  }

  return {
    total: jobs.length,
    applied: successCount,
    failed: failCount,
    details: results,
  };
}

// ==================== MCP Server ====================

const server = new Server(
  {
    name: "liepin-mcp-server",
    version: "1.0.0",
  },
  {
    capabilities: {
      tools: {},
    },
  }
);

// 注册工具列表
server.setRequestHandler(ListToolsRequestSchema, async () => ({
  tools: [
    {
      name: "liepin_login_check",
      description:
        "检查猎聘网登录状态。如果未登录，会打开浏览器窗口供用户扫码登录。在投递简历前必须先调用此工具确认登录状态。作用于当前账号。",
      inputSchema: {
        type: "object",
        properties: {},
        required: [],
      },
    },
    {
      name: "liepin_list_accounts",
      description:
        "列出已配置的投递账号及其登录状态。多账号场景下，投递前应先调用此工具，让用户选择用哪个账号投递。返回每个账号的 id、名称、是否已登录，以及当前使用的账号。",
      inputSchema: {
        type: "object",
        properties: {},
        required: [],
      },
    },
    {
      name: "liepin_switch_account",
      description:
        "切换投递账号。切换后所有搜索/投递操作都在该账号下进行。如果目标账号未登录，会打开浏览器供用户扫码登录，登录态会保存到该账号自己独立的文件里（账号间 cookie 隔离，不会串号）。投递前务必先确认已登录。",
      inputSchema: {
        type: "object",
        properties: {
          accountId: {
            type: "string",
            description: "目标账号 id，取值见 liepin_list_accounts 返回结果",
          },
        },
        required: ["accountId"],
      },
    },
    {
      name: "liepin_search_jobs",
      description:
        "在猎聘网上搜索岗位。当用户要求找工作、看岗位、搜索职位时使用。返回岗位名称、公司、薪资、地点、详情链接。",
      inputSchema: {
        type: "object",
        properties: {
          keyword: {
            type: "string",
            description: "岗位关键词，如 Java开发、产品经理、前端工程师",
          },
          city: {
            type: "string",
            description: "工作城市，如 杭州、北京、上海。不填则全国搜索",
          },
          salary: {
            type: "string",
            description: "薪资范围（可选），如 15k-25k、20k以上",
          },
          pageNum: {
            type: "integer",
            description: "页码，默认1",
          },
        },
        required: ["keyword"],
      },
    },
    {
      name: "liepin_get_job_detail",
      description:
        "获取猎聘岗位的详细信息，包括完整JD、任职要求等。投递前应调用此工具确认岗位匹配度。",
      inputSchema: {
        type: "object",
        properties: {
          url: {
            type: "string",
            description: "岗位详情页URL",
          },
        },
        required: ["url"],
      },
    },
    {
      name: "liepin_apply_job",
      description:
        "在猎聘上投递单个岗位。打开岗位链接 → 点击聊天页顶部的「发简历」按钮发送猎聘平台简历。" +
        "如果打开的是岗位详情页（非聊天页），会自动先点「立即沟通」进入聊天再发简历。" +
        "注意：猎聘使用平台自有的简历，不需要传数据库简历文本。",
      inputSchema: {
        type: "object",
        properties: {
          url: {
            type: "string",
            description: "岗位链接URL（从搜索结果中获取）",
          },
          resumeText: {
            type: "string",
            description: "已废弃，猎聘使用平台自有简历",
          },
        },
        required: ["url"],
      },
    },
    {
      name: "liepin_batch_apply",
      description:
        "在猎聘上批量搜索并投递。搜索 → 逐条打开岗位 → 点击「发简历」按钮发送猎聘平台简历。" +
        "自动处理：详情页先点「立即沟通」→ 聊天页点「发简历」。投递间隔2-5秒，模拟人工操作。",
      inputSchema: {
        type: "object",
        properties: {
          keyword: {
            type: "string",
            description: "岗位关键词，如 销售、产品经理",
          },
          city: {
            type: "string",
            description: "工作城市",
          },
          maxCount: {
            type: "integer",
            description: "最多投递数量，默认5个",
          },
          resumeText: {
            type: "string",
            description: "已废弃，猎聘使用平台自有简历",
          },
        },
        required: ["keyword", "city"],
      },
    },
  ],
}));

// 处理工具调用
server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: args } = request.params;

  console.error(`[MCP] 收到调用: ${name} args=${JSON.stringify(args)}`);

  try {
    let result;
    switch (name) {
      case "liepin_login_check":
        result = await checkLogin();
        break;
      case "liepin_list_accounts":
        result = await listAccounts();
        break;
      case "liepin_switch_account":
        result = await switchAccount(args || {});
        break;
      case "liepin_search_jobs":
        result = await searchJobs(args);
        break;
      case "liepin_get_job_detail":
        result = await getJobDetail(args);
        break;
      case "liepin_apply_job":
        result = await applyJob(args);
        break;
      case "liepin_batch_apply":
        result = await batchApply(args);
        break;
      default:
        return {
          content: [{ type: "text", text: `未知工具: ${name}` }],
          isError: true,
        };
    }

    return {
      content: [{ type: "text", text: JSON.stringify(result, null, 2) }],
    };
  } catch (e) {
    console.error(`[MCP] 工具调用异常 ${name}: ${e.message}`);
    return {
      content: [{ type: "text", text: JSON.stringify({ error: e.message }) }],
      isError: true,
    };
  }
});

// ==================== 启动 ====================

async function main() {
  loadAccounts();
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error(`[猎聘MCP] Server 已启动，等待 Spring AI 连接... 当前账号: ${currentAccountId}（配置 ${accounts.length} 个账号）`);
}

main().catch((e) => {
  console.error("[猎聘MCP] 启动失败:", e);
  process.exit(1);
});

// 进程退出时清理
process.on("SIGINT", async () => {
  console.error("[猎聘MCP] 正在关闭...");
  await closeBrowser();
  process.exit(0);
});

process.on("SIGTERM", async () => {
  await closeBrowser();
  process.exit(0);
});
