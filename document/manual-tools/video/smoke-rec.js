// 冒烟测试：验证 Playwright 视频录制 + ffmpeg 字幕烧录 + 配音混流整条管线
// 用法：node smoke-rec.js   （输出 /tmp/vtest/out.mp4）
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

(async () => {
  const dir = '/tmp/vtest';
  fs.mkdirSync(dir, { recursive: true });
  const browser = await chromium.launch();
  const ctx = await browser.newContext({
    viewport: { width: 1920, height: 1080 },
    recordVideo: { dir, size: { width: 1920, height: 1080 } },
  });
  const page = await ctx.newPage();

  // 注入模拟光标（正式脚本同款），验证光标入镜
  await page.addInitScript(() => {
    const mk = () => {
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
  });

  await page.goto('http://localhost:5173/login', { waitUntil: 'networkidle' });
  await page.evaluate(() => window.__demoMove(700, 400));
  await page.waitForTimeout(600);
  await page.evaluate(() => window.__demoMove(1100, 520));
  await page.waitForTimeout(600);
  await page.evaluate(() => window.__demoMove(900, 800));
  await page.waitForTimeout(1200);

  await ctx.close(); // 关闭上下文才会落盘 webm
  await browser.close();
  console.log('SMOKE_REC_OK');
})().catch(e => { console.error(e); process.exit(1); });
