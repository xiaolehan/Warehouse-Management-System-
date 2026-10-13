// 视频录制公共设施：各集录制脚本（record-purchase.js / record-*.js）共用
// 密码只经 WMS_PWD 环境变量注入，绝不写入本文件或任何日志
const { chromium } = require('playwright');
const fs = require('fs');

const BASE = 'http://localhost:5173';
const RAW = '/tmp/wms-video/raw';
const PWD = process.env.WMS_PWD;
if (!PWD) { console.error('ERROR: 需要 WMS_PWD 环境变量（密码不落盘）'); process.exit(1); }

// ---------- 点击放大 / 起拍锚点记录 ----------
// clicks：zoom=true 的点击记下 时刻+坐标，build.js 合成时做 zoompan 推镜
// lineStarts：每个带 line 的拍子起拍时刻（视频秒），build.js 据此把该行解说
//   音频锚定到动作起拍点（视频驱动音频：音画同步、节拍误差不累积）
const clicksByAct = {};
const beatsByAct = {};
let scene = null; // { id, t0, clicks, lineStarts }

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
  scene = { id: name, t0: Date.now(), clicks: [], lineStarts: [] }; // 视频时间轴 0 点 ≈ 首页创建时刻
  clicksByAct[name] = scene.clicks;
  beatsByAct[name] = scene.lineStarts;
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
  // focus 键入而非 click：el-input-number「初始禁用→启用」后会残留 aria-disabled="true"，
  // playwright 的 actionability 检查会据此拒点（组件实际已启用），focus 无此检查
  await el.evaluate(n => n.focus());
  await page.keyboard.press('Control+a').catch(() => {});
  await page.keyboard.type(text, { delay: 90 });
  await page.waitForTimeout(wait);
  console.log('TYPE', text);
}

async function gotoMenu(page, text, wait = 2200, zoom = false) {
  await clickDemo(page, page.locator('.el-menu-item', { hasText: text }).first(), wait, `menu:${text}`, zoom);
}

// ---------- 节拍执行器（音画同步·视频驱动音频）----------
// 带 line 的拍子：起拍即记锚点 → 执行动作 → 驻留到「起拍 + 本行解说时长 + 1s」
// （动作超时则至少再停 1s）。mux 端 sceneAudio 按锚点插静音，使该行解说恰好
// 从动作起拍时播报——录制节拍误差不会累积成「字幕领先画面」。
// 依赖 timeline.json（各行解说时长），故录制前必跑 node build.js tts <解说词>。
async function runBeats(page, sceneId, beats) {
  const tl = JSON.parse(fs.readFileSync('/tmp/wms-video/timeline.json', 'utf8'));
  const durs = tl[sceneId] || [];
  const lineStarts = beatsByAct[sceneId];
  for (const b of beats) {
    if (b.line != null) lineStarts[b.line] = +((Date.now() - scene.t0) / 1000).toFixed(2);
    if (b.do) await b.do();
    if (b.line == null) { await page.waitForTimeout(b.hold ?? 1200); continue; }
    const target = lineStarts[b.line] + (durs[b.line] ?? 3) + 1.0;
    const wait = Math.max(1.0, target - (Date.now() - scene.t0) / 1000);
    console.log(`BEAT ${sceneId}#${b.line} hold=${wait.toFixed(1)}s`);
    await page.waitForTimeout(wait * 1000);
  }
}

// ---------- 防串集清理 / 收尾落盘 ----------
// 录制前清掉上一集的点击/锚点记录与原始录像，缺失幕不会被旧素材静默顶替
function cleanupBeforeRecord(acts) {
  fs.rmSync('/tmp/wms-video/clicks.json', { force: true });
  fs.rmSync('/tmp/wms-video/beats.json', { force: true });
  for (const a of acts) fs.rmSync(`${RAW}/${a}`, { recursive: true, force: true });
}

function saveArtifacts() {
  fs.writeFileSync('/tmp/wms-video/clicks.json', JSON.stringify(clicksByAct, null, 1));
  fs.writeFileSync('/tmp/wms-video/beats.json', JSON.stringify(beatsByAct, null, 1));
}

module.exports = {
  BASE, RAW, apiLogin, openScene, moveTo, clickDemo, typeDemo, gotoMenu,
  runBeats, cleanupBeforeRecord, saveArtifacts,
};



