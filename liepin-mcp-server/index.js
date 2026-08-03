/**
 * 猎聘 MCP Server — Playwright 浏览器自动化
 *
 * 通过 MCP 协议暴露工具给 Spring AI Agent 调用：
 *   - liepin_login_check    : 检查猎聘登录状态
 *   - liepin_search_jobs    : 搜索岗位
 *   - liepin_apply_job      : 自动投递单个岗位
 *   - liepin_batch_apply    : 批量投递
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
import { execSync } from "child_process";

// ==================== 配置 ====================
const USER_DATA_DIR = "./liepin-profile";
const STORAGE_STATE_FILE = "./liepin-profile/state.json";
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

// ==================== 浏览器管理 ====================

async function getBrowser() {
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

  // 优先使用系统 Chrome/Edge（无需下载 Playwright 浏览器）
  if (SYSTEM_CHROME) {
    console.error(`[浏览器] 使用系统 ${SYSTEM_CHROME} 浏览器`);
    launchOptions.channel = SYSTEM_CHROME;
    browser = await chromium.launch(launchOptions);
    // 加载持久化的登录状态
    const state = loadStorageState();
    context = await browser.newContext({
      viewport: { width: 1280, height: 900 },
      storageState: state || undefined,
    });
    // 定期保存登录状态
    context.on("close", () => saveStorageState(context));
  } else {
    console.error("[浏览器] 使用 Playwright 内置 Chromium（需预装）");
    browser = await chromium.launchPersistentContext(USER_DATA_DIR, {
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
    if (fs.existsSync(STORAGE_STATE_FILE)) {
      return JSON.parse(fs.readFileSync(STORAGE_STATE_FILE, "utf-8"));
    }
  } catch {}
  return null;
}

async function saveStorageState(ctx) {
  try {
    const state = await ctx.storageState();
    fs.mkdirSync(USER_DATA_DIR, { recursive: true });
    fs.writeFileSync(STORAGE_STATE_FILE, JSON.stringify(state));
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
      // 登录成功后立即保存状态，确保持久化
      try {
        const state = await context.storageState();
        fs.mkdirSync(USER_DATA_DIR, { recursive: true });
        fs.writeFileSync(STORAGE_STATE_FILE, JSON.stringify(state));
        console.error("[猎聘] 登录状态已保存");
      } catch (e) {
        console.error("[猎聘] 保存登录状态失败:", e.message);
      }
      return { loggedIn: true, nickname: "已登录用户" };
    }
    return { loggedIn: false, message: "未登录，请在浏览器中扫码登录猎聘" };
  } catch (e) {
    return { loggedIn: false, error: e.message };
  }
}

// ==================== 工具：搜索岗位 ====================

async function searchJobs({ keyword, city = "", salary = "", pageNum = 1 }) {
  await getBrowser();

  try {
    // 第一步：只用关键词搜索（不用城市参数，URL编码不可靠）
    const searchUrl = buildSearchUrl(keyword, "", salary, pageNum);
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

    // 第二步：在页面上点击城市筛选器选择目标城市
    if (city && typeof city === "string" && city.trim()) {
      const citySelected = await selectCityOnPage(city.trim());
      if (citySelected) {
        console.error(`[猎聘搜索] 城市筛选「${city}」已应用`);
        await page.waitForTimeout(2000); // 等结果刷新
      } else {
        console.error(`[猎聘搜索] 城市筛选「${city}」失败，显示全国结果`);
      }
    }

    // 截图调试
    try { await page.screenshot({ path: "./debug-search.png" }); } catch {}

    // 检查登录状态
    currentUrl = page.url();
    if (currentUrl.includes("login") || currentUrl.includes("passport")) {
      return { error: "NOT_LOGGED_IN", message: "需要登录猎聘，请先扫码登录" };
    }

    // 提取岗位信息
    const jobs = await page.evaluate(() => {
      const results = [];
      const allLinks = document.querySelectorAll("a[href]");
      const seen = new Set();

      for (const a of allLinks) {
        const href = a.href;
        if (!href.includes("liepin.com")) continue;
        if (!(href.includes("/job/") || href.includes("/a/") || href.includes("lpt") || href.includes("detail"))) continue;
        if (seen.has(href)) continue;
        seen.add(href);

        const title = (a.textContent || "").replace(/\s+/g, " ").trim();
        if (!title || title.length < 4 || title.length > 80) continue;
        if (/^(登录|注册|首页|职位|校园|海归|APP|更多|不限|清空|订阅|扫一扫|验证码)$/.test(title)) continue;
        if (/^(\d|公司|城市|行业|学历|薪资|经验|区域|热门|推荐|相关|周边|其他|当前位置)/.test(title)) continue;

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
    try { await page.screenshot({ path: "./debug-search-error.png" }); } catch {}
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
      try { await page.screenshot({ path: "./debug-city-panel.png" }); } catch {}
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

function encodeCity(city) {
  // 猎聘城市编码（常用城市映射）
  const cityMap = {
    "北京": "010", "上海": "020", "广州": "050", "深圳": "060",
    "杭州": "080", "成都": "090", "南京": "070", "武汉": "170",
    "西安": "200", "苏州": "120", "重庆": "040", "长沙": "190",
    "天津": "030", "郑州": "180", "东莞": "100", "青岛": "160",
    "合肥": "150", "厦门": "110", "大连": "140", "宁波": "130",
  };
  return cityMap[city] || city;
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

      // 查找"立即沟通"按钮（猎聘通过聊天投递）
      const chatBtn = document.querySelector(
        "button:has-text('立即沟通'), a:has-text('立即沟通'), button:has-text('沟通'), [class*='chat-btn'], [class*='im-btn']"
      );

      return {
        title: title ? title.textContent.trim() : "",
        company: company ? company.textContent.trim() : "",
        salary: salary ? salary.textContent.trim() : "",
        description: desc ? desc.textContent.trim() : "",
        requirements: requirements ? requirements.textContent.trim() : "",
        chatBtnExists: !!chatBtn,
      };
    });

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

    await page.screenshot({ path: "./debug-apply-page.png" });

    // 3. 先尝试直接找「发简历」（如果已经是聊天页）
    let resumeSent = await clickResumeDelivery();

    // 4. 如果没找到，点击「立即沟通」打开聊天，再找「发简历」
    if (!resumeSent) {
      console.error("[猎聘投递] 当前页面未找到发简历按钮，尝试先点击立即沟通...");
      const chatOpened = await clickChatButton();
      if (chatOpened) {
        await page.waitForTimeout(4000);
        await page.screenshot({ path: "./debug-chat-panel.png" });

        // 重试 3 次（聊天面板可能异步渲染）
        for (let attempt = 0; attempt < 3; attempt++) {
          resumeSent = await clickResumeDelivery();
          if (resumeSent) break;
          await page.waitForTimeout(1500);
        }
      }
    }

    if (!resumeSent) {
      await page.screenshot({ path: "./debug-no-resume-delivery.png" });
      return { success: false, error: "NO_RESUME_DELIVERY", message: "未找到「发简历」按钮，页面布局可能与预期不同" };
    }

    // 5. 等待发送完成（猎聘用自己的平台简历，无需额外操作）
    await page.waitForTimeout(2000);

    // 6. 检查结果
    const result = await checkDeliveryResult();
    return result;
  } catch (e) {
    console.error(`[猎聘投递] 异常: ${e.message}`);
    return { success: false, error: e.message };
  }
}

/** 点击「立即沟通」按钮打开聊天面板（岗位详情页 → 聊天） */
async function clickChatButton() {
  return await page.evaluate(() => {
    const selectors = [
      "button:has-text('立即沟通')",
      "a:has-text('立即沟通')",
      "button:has-text('沟通')",
      "span:has-text('立即沟通')",
      "[class*='chat-btn']",
      "[class*='im-btn']",
      ".btn-chat",
      ".btn-communication",
      "button:has-text('聊一聊')",
      "button:has-text('在线沟通')",
    ];
    for (const sel of selectors) {
      try {
        const btn = document.querySelector(sel);
        if (btn && btn.offsetHeight > 0) {
          btn.click();
          return true;
        }
      } catch (e) { /* continue */ }
    }
    // fallback: 搜索包含"沟通"的可点击元素
    const allEls = document.querySelectorAll("button, a, span, div[class*='btn']");
    for (const el of allEls) {
      if (el.textContent && el.textContent.includes("沟通") && el.offsetHeight > 0) {
        el.click();
        return true;
      }
    }
    return false;
  });
}

/**
 * 点击聊天页顶部的「简历投递」按钮。
 *
 * 猎聘聊天页（IM页面）布局：
 * ┌──────────────────────────────┐
 * │  ← 返回    招聘顾问名称       │
 * │  岗位名称                     │
 * │  [简历投递] [常用语] ...      │  ← 顶部工具栏
 * ├──────────────────────────────┤
 * │                              │
 * │    聊天消息区域               │
 * │                              │
 * ├──────────────────────────────┤
 * │ [输入框________________] [发送]│
 * └──────────────────────────────┘
 */
async function clickResumeDelivery() {
  return await page.evaluate(() => {
    // 优先精确匹配：聊天页顶部工具栏的「发简历」/「简历投递」
    const primarySelectors = [
      "button:has-text('发简历')",
      "span:has-text('发简历')",
      "a:has-text('发简历')",
      "div:has-text('发简历')",
      "button:has-text('简历投递')",
      "span:has-text('简历投递')",
      "a:has-text('简历投递')",
      "div:has-text('简历投递')",
      "button:has-text('投递简历')",
      "span:has-text('投递简历')",
      "button:has-text('发送简历')",
      "span:has-text('发送简历')",
      "[class*='resume-delivery']",
      "[class*='resumeDelivery']",
      "[class*='send-resume']",
      "[class*='sendResume']",
    ];
    for (const sel of primarySelectors) {
      try {
        const el = document.querySelector(sel);
        if (el && el.offsetHeight > 0) {
          el.click();
          return true;
        }
      } catch (e) { /* continue */ }
    }

    // fallback: 扫描页面顶部区域中所有含「发简历」「简历投递」「投递简历」的元素
    const keywords = ["发简历", "简历投递", "投递简历", "发送简历"];
    const allElements = document.querySelectorAll("button, span, a, div, li");
    for (const el of allElements) {
      if (!el.textContent || el.offsetHeight <= 0) continue;
      const text = el.textContent.trim();
      if (keywords.some(k => text.includes(k))) {
        const rect = el.getBoundingClientRect();
        if (rect.width > 0 && rect.height > 0 && rect.top < window.innerHeight * 0.5) {
          el.click();
          return true;
        }
      }
    }

    // 兜底1：扫描 title / aria-label 属性
    const attrEls = document.querySelectorAll("[title*='简历'], [title*='发简历'], [aria-label*='简历'], [aria-label*='发简历']");
    for (const el of attrEls) {
      if (el.offsetHeight > 0) {
        const rect = el.getBoundingClientRect();
        if (rect.width > 0 && rect.top < window.innerHeight * 0.6) {
          el.click();
          return true;
        }
      }
    }

    // 兜底2：聊天工具栏中任何含「简历」文字的元素（放宽到全页60%区域）
    const allEls = document.querySelectorAll("button, span, a, div, li, i, em, svg, img");
    for (const el of allEls) {
      const text = (el.textContent || el.getAttribute("title") || el.getAttribute("aria-label") || "").trim();
      if (text && (text.includes("简历") || text.includes("发简历")) && el.offsetHeight > 0) {
        const rect = el.getBoundingClientRect();
        if (rect.width > 0 && rect.height > 0 && rect.top < window.innerHeight * 0.6) {
          el.click();
          return true;
        }
      }
    }

    // 兜底3：查找所有可见元素，打印前20个含"简历"的用于调试
    const debugEls = [];
    const allVisible = document.querySelectorAll("button, span, a, div, li, i");
    for (const el of allVisible) {
      const text = (el.textContent || "").trim();
      if (text && text.includes("简历") && el.offsetHeight > 0) {
        debugEls.push({
          tag: el.tagName,
          text: text.substring(0, 50),
          class: el.className?.substring?.(0, 50) || "",
          top: el.getBoundingClientRect().top,
          visible: el.offsetHeight > 0,
        });
      }
    }
    if (debugEls.length > 0) {
      console.error("[DEBUG] 页面中找到含'简历'的元素:", JSON.stringify(debugEls.slice(0, 20)));
    } else {
      console.error("[DEBUG] 页面中未找到任何含'简历'的可见元素");
    }
    return false;
  });
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

/** 检查投递/简历发送结果（纯 JS，不依赖 Playwright 选择器语法） */
async function checkDeliveryResult() {
  return await page.evaluate(() => {
    const successKeywords = ["发送成功", "投递成功", "已发送", "简历已发送", "简历投递成功", "已投递"];
    const failKeywords = ["发送失败", "投递失败", "今日沟通已达上限", "请完善简历", "简历不完整", "对方已设置"];

    // 扫描所有可见元素
    const allEls = document.querySelectorAll("div, span, p, button, [class*='toast'], [class*='tip'], [class*='msg'], [class*='message']");
    for (const el of allEls) {
      const text = (el.textContent || "").trim();
      if (!text) continue;
      for (const kw of successKeywords) {
        if (text.includes(kw)) return { success: true, message: text.substring(0, 100) };
      }
      for (const kw of failKeywords) {
        if (text.includes(kw)) return { success: false, message: text.substring(0, 100) };
      }
    }

    // 「发简历」已点击但无明确反馈 → 乐观认为成功
    return { success: true, message: "简历发送请求已提交，等待对方查看" };
  });
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
        "检查猎聘网登录状态。如果未登录，会打开浏览器窗口供用户扫码登录。在投递简历前必须先调用此工具确认登录状态。",
      inputSchema: {
        type: "object",
        properties: {},
        required: [],
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
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error("[猎聘MCP] Server 已启动，等待 Spring AI 连接...");
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
