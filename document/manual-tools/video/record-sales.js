// 录制 WMS 视频教程样片：销售全流程四幕（Playwright 驱动真实系统 + 模拟光标 + 点击高亮）
// 用法：WMS_PWD=<密码> node record-sales.js
// 前置：后端 8080 / 前端 5173 运行中；先跑 build-tts 生成 /tmp/wms-video/timeline.json
// 密码只经 WMS_PWD 环境变量注入，绝不写入本文件或任何日志
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const BASE = 'http://localhost:5173';
const RAW = '/tmp/wms-video/raw';
const PWD = process.env.WMS_PWD;
if (!PWD) { console.error('ERROR: 需要 WMS_PWD 环境变量（密码不落盘）'); process.exit(1); }

// ---------- 点击放大记录：zoom=true 的点击记下 时刻+坐标，build.js 合成时做 zoompan 推镜 ----------
const clicksByAct = {};
let scene = null; // { id, t0, clicks }

// ---------- API 登录（录制不拍登录页，密码零入镜） ----------
async function apiLogin(username) {
  const r = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: PWD }),
  });
  const j = await r.json();
  const d = j.data || j;
  const u = d.userInfo || d;
  if (!d.token || !u.id) throw new Error(`登录失败 ${username}: ${j.message || j.msg || r.status}`);
  console.log(`LOGIN_OK ${username} dept=${u.deptCode}`);
  return {
    token: d.token, role: u.role, deptId: u.deptId, deptCode: u.deptCode,
    deptName: u.deptName, username: u.username, realName: u.realName, userId: u.id,
  };
}

// ---------- 场景上下文：预注入 localStorage（不拍登录）+ 模拟光标 + 点击高亮 ----------
async function openScene(browser, name, user) {
  const dir = `${RAW}/${name}`;
  fs.mkdirSync(dir, { recursive: true });
  const ctx = await browser.newContext({
    viewport: { width: 1920, height: 1080 },
    recordVideo: { dir, size: { width: 1920, height: 1080 } },
  });
  await ctx.addInitScript(cfg => {
    localStorage.setItem('token', cfg.token);
    localStorage.setItem('role', String(cfg.role));
    localStorage.setItem('deptId', String(cfg.deptId));
    localStorage.setItem('deptCode', cfg.deptCode);
    localStorage.setItem('deptName', cfg.deptName);
    localStorage.setItem('username', cfg.username);
    localStorage.setItem('realName', cfg.realName);
    localStorage.setItem('userId', String(cfg.userId));
    const mk = () => {
      if (document.getElementById('demo-cursor')) return;
      const c = document.createElement('div');
      c.id = 'demo-cursor';
      c.style.cssText = 'position:fixed;z-index:99999;left:0;top:0;width:24px;height:24px;'
        + 'pointer-events:none;transition:transform .45s cubic-bezier(.4,0,.2,1);'
        + "background:url('data:image/svg+xml;utf8,<svg xmlns=%22http://www.w3.org/2000/svg%22 width=%2224%22 height=%2224%22><path d=%22M4 2 L4 20 L9 15 L12 22 L15 20 L12 13 Z%22 fill=%22%23111%22 stroke=%22white%22 stroke-width=%221.5%22/></svg>') no-repeat;";
      (document.body || document.documentElement).appendChild(c);
    };
    document.addEventListener('DOMContentLoaded', mk);
    if (document.body) mk();
    window.__demoMove = (x, y) => {
      const c = document.getElementById('demo-cursor');
      if (c) c.style.transform = `translate(${x}px, ${y}px)`;
    };
    window.__demoClick = el => {
      const r = el.getBoundingClientRect();
      const ring = document.createElement('div');
      ring.style.cssText = `position:fixed;z-index:99998;left:${r.x - 6}px;top:${r.y - 6}px;`
        + `width:${r.width + 12}px;height:${r.height + 12}px;border:3px solid #3f8cff;border-radius:8px;`
        + 'pointer-events:none;box-shadow:0 0 0 6px rgba(63,140,255,.25);transition:opacity .5s;';
      document.body.appendChild(ring);
      setTimeout(() => { ring.style.opacity = '0'; setTimeout(() => ring.remove(), 600); }, 500);
    };
  }, user);
  const page = await ctx.newPage();
  scene = { id: name, t0: Date.now(), clicks: [] }; // 视频时间轴 0 点 ≈ 首页创建时刻
  clicksByAct[name] = scene.clicks;
  return { ctx, page };
}

// ---------- 交互助手：光标滑动 → 高亮 → 点击 / 打字 ----------
async function moveTo(page, el) {
  await el.scrollIntoViewIfNeeded().catch(() => {});
  const b = await el.boundingBox();
  if (!b) throw new Error('element not visible');
  await page.evaluate(([x, y]) => window.__demoMove(x, y), [b.x + b.width / 2, b.y + b.height / 2]);
  await page.waitForTimeout(700); // 光标过渡 .45s + 停顿
  return b;
}

async function clickDemo(page, el, wait = 1200, label = '', zoom = false) {
  const b = await moveTo(page, el);
  if (zoom && scene) {
    scene.clicks.push({
      t: +((Date.now() - scene.t0) / 1000).toFixed(2),
      x: Math.round(b.x + b.width / 2), y: Math.round(b.y + b.height / 2),
    });
  }
  await el.evaluate(n => window.__demoClick(n));
  await page.waitForTimeout(320);
  await el.click();
  await page.waitForTimeout(wait);
  console.log('STEP', label, zoom ? '(zoom)' : '');
}

async function typeDemo(page, el, text, wait = 800) {
  await moveTo(page, el);
  await el.click();
  await el.press('Control+a').catch(() => {});
  await el.pressSequentially(text, { delay: 90 });
  await page.waitForTimeout(wait);
  console.log('TYPE', text);
}

async function gotoMenu(page, text, wait = 2200, zoom = false) {
  await clickDemo(page, page.locator('.el-menu-item', { hasText: text }).first(), wait, `menu:${text}`, zoom);
}

// ---------- 节拍执行器：动作后按解说时长驻留（音频先行对齐） ----------
// timeline.json: { act1: [dur, ...], ... } 每行解说音频秒数
// beats: [{ line: 索引|null, do: fn|null, est: 动作耗时估计(默认1.2) }]
// 驻留 = line时长 + 0.45(间隙) - est，使下一步动作恰好落在该行解说结束时附近
async function runBeats(page, sceneId, beats) {
  const tl = JSON.parse(fs.readFileSync('/tmp/wms-video/timeline.json', 'utf8'));
  const durs = tl[sceneId] || [];
  let i = 0;
  for (const b of beats) {
    if (b.do) await b.do();
    if (b.line == null) {
      await page.waitForTimeout(b.hold ?? 1200);
      continue;
    }
    const est = b.est ?? 1.2;
    const wait = Math.max(0.8, (durs[b.line] ?? 3) + 0.45 - est) * 1000;
    console.log(`BEAT ${sceneId}#${b.line} hold=${(wait / 1000).toFixed(1)}s`);
    await page.waitForTimeout(wait);
    i++;
  }
}

// ---------- 第一幕：销售管理员新建销售单（触发价格偏离审批） ----------
async function act1(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act1', [
    { line: null, hold: 1400 },
    { line: 0, do: () => gotoMenu(page, '商品销售', 2200, true) },
    { line: 1, do: () => page.locator('.el-table__empty-block, .el-table__row').first().waitFor({ timeout: 8000 }) },
    { line: 2, do: () => clickDemo(page, page.getByRole('button', { name: '新建销售单' }), 1600, '新建销售单', true) },
    { line: 3, do: () => typeDemo(page, page.getByPlaceholder('请输入客户公司名（可选）'), '演示客户·华东机械', 700), est: 2.0 },
    { line: 4, do: () => clickDemo(page, page.locator('.el-dialog .el-table .el-select').first(), 1100, '打开成品下拉') },
    { line: 5, do: () => clickDemo(page, page.locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: 'SMC105' }).first(), 1600, '选择SMC105') },
    { line: 6, do: () => clickDemo(page, page.locator('.el-dialog .el-table .el-input-number input').first(), 500, '数量=1') },
    { line: 7, do: () => typeDemo(page, page.locator('.el-dialog .el-table .el-input-number input').nth(1), '16000', 900), est: 2.2 },
    { line: 8, do: () => page.locator('.price-deviation-hint').first().waitFor({ state: 'visible', timeout: 6000 }) },
    { line: 9, do: async () => {
        await clickDemo(page, page.getByRole('button', { name: '确定新增' }), 800, '确定新增', true);
        await page.locator('.el-message', { hasText: '销售单' }).first().waitFor({ timeout: 5000 });
    } },
    { line: 10 }, // 停留画面，等本行解说播完（toast 已在上一拍确认）
  ]);
}

// ---------- 第二幕：消息邮箱入场 → 价格偏离审批 ----------
async function act2(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act2', [
    { line: null, hold: 1200 },
    { line: 0, hold: 2200 }, // 开场白：仍以销售管理员身份（画面停留首页）
    { line: 1, hold: 1500 }, // 红点角标特写（画面停首页右上角有红点）
    { line: 2, do: async () => {
        await clickDemo(page, page.locator('.message-button'), 1500, '消息邮箱', true);
        await page.locator('.message-dialog').waitFor({ state: 'visible', timeout: 5000 });
    } },
    { line: 3, do: async () => {
        await page.locator('.message-card', { hasText: '待审批' }).first().waitFor({ timeout: 5000 });
        await clickDemo(page, page.locator('.message-card.unread').first(), 1500, '点击待审批消息', true);
        // D75 深链：跳转后自动打开该单审批详情弹窗（:visible 排除已关闭的邮箱弹窗 DOM）
        await page.locator('.el-dialog:visible', { hasText: 'SMC105' }).first().waitFor({ timeout: 8000 });
    }, est: 2.0 },
    { line: 4 }, // 快照特写（详情弹窗已自动打开）
    { line: 5, do: () => clickDemo(page, page.locator('.el-dialog:visible .el-dialog__headerbtn'), 1200, '关闭详情') },
    { line: 6, do: () => clickDemo(page, page.getByRole('button', { name: '通过' }).first(), 1400, '通过', true) },
    { line: 7, do: () => typeDemo(page, page.locator('.el-message-box__input input'), '同意按 16000 元成交。', 900), est: 2.2 },
    { line: 8, do: async () => {
        await clickDemo(page, page.locator('.el-message-box').getByRole('button', { name: '通过' }), 1500, '确认通过');
        await page.locator('.el-message', { hasText: '审批通过' }).first().waitFor({ timeout: 5000 });
    } },
  ]);
}

// ---------- 第三幕：仓储管理员确认出库 ----------
async function act3(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act3', [
    { line: null, hold: 1200 },
    { line: 0, hold: 2400 }, // 「切换到仓储管理员」——画面即仓储首页
    { line: 1, do: () => gotoMenu(page, '销售出库确认') },
    { line: 2, do: async () => {
        await page.locator('.el-table__row').first().waitFor({ timeout: 8000 });
        await page.waitForTimeout(600);
        await clickDemo(page, page.locator('.el-table__row').first().locator('.el-button', { hasText: '详情' }), 1800, '详情');
    }, est: 2.0 },
    { line: 3, do: () => page.locator('.el-dialog:visible', { hasText: 'SMC105' }).first().waitFor({ timeout: 6000 }) },
    { line: 4, do: async () => {
        await clickDemo(page, page.locator('.el-dialog:visible .el-dialog__headerbtn'), 1000, '关闭详情');
        await clickDemo(page, page.locator('.el-table__row').first().locator('.el-button', { hasText: '确认出库' }), 1300, '确认出库', true);
    }, est: 2.6 },
    { line: 5, do: async () => {
        await page.locator('.el-message-box').waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(900);
        await clickDemo(page, page.locator('.el-message-box__btns .el-button--primary'), 800, '确定');
        await page.locator('.el-message', { hasText: '已确认出库' }).first().waitFor({ timeout: 5000 });
    }, est: 2.4 },
    { line: 6 }, // 停留画面，等本行解说播完（toast 已在上一拍确认）
  ]);
}

// ---------- 第四幕：库存核对（成品管理 4→3） ----------
async function act4(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act4', [
    { line: null, hold: 1200 },
    { line: 0, do: () => gotoMenu(page, '成品管理') },
    { line: 1, do: async () => {
        await page.locator('.el-table__row').first().waitFor({ timeout: 8000 });
        await typeDemo(page, page.getByPlaceholder('请输入成品名称'), 'SMC105', 700);
        await clickDemo(page, page.getByRole('button', { name: '查询' }), 1800, '查询', true);
    }, est: 3.0 },
    { line: 2, do: async () => {
        await page.locator('.el-table__row', { hasText: 'SMC105' }).first().waitFor({ timeout: 8000 });
        const row = page.locator('.el-table__row', { hasText: 'SMC105' }).first();
        await page.evaluate(() => window.__demoMove(1300, 420)); // 光标移向库存列
        await page.waitForTimeout(1400);
        await row.waitFor({ state: 'visible' });
    } },
    { line: 3, hold: 3200 }, // 收尾定格
  ]);
}

// ---------- 主流程 ----------
(async () => {
  fs.mkdirSync(RAW, { recursive: true });
  const browser = await chromium.launch();

  const sales = await apiLogin('sales_admin');
  const warehouse = await apiLogin('warehouse_admin');

  // 每幕独立上下文独立 webm，幕间由过场卡衔接
  let s = await openScene(browser, 'act1', sales);
  await act1(s.page);
  await s.ctx.close();

  s = await openScene(browser, 'act2', sales);
  await act2(s.page);
  await s.ctx.close();

  s = await openScene(browser, 'act3', warehouse);
  await act3(s.page);
  await s.ctx.close();

  s = await openScene(browser, 'act4', warehouse);
  await act4(s.page);
  await s.ctx.close();

  await browser.close();
  fs.writeFileSync('/tmp/wms-video/clicks.json', JSON.stringify(clicksByAct, null, 1));
  console.log('REC_ALL_OK');
})().catch(e => { console.error(e); process.exit(1); });
