// 生成视频过场卡 PNG（1920×1080）：片头 / 四幕过场卡 / 片尾
// 用法：node gen-cards.js   （输出 /tmp/wms-video/cards/*.png）
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '.');
const OUT = '/tmp/wms-video/cards';
const NARR = process.argv[2] || 'narration.json'; // node gen-cards.js [narration-X.json]
const narr = JSON.parse(fs.readFileSync(path.join(ROOT, NARR), 'utf8'));
const TAG = narr.cards.tag || '销售全流程演示'; // 过场卡副标题随集走

const HTML = (title, subtitle, foot) => `<!DOCTYPE html>
<html><head><meta charset="utf-8"><style>
  * { margin:0; padding:0; box-sizing:border-box; }
  body { width:1920px; height:1080px; overflow:hidden;
    background:linear-gradient(135deg,#16222f 0%,#1f3244 55%,#16222f 100%);
    font-family:'Noto Sans CJK SC','Noto Sans SC',sans-serif; color:#fff; }
  .wrap { height:100%; display:flex; flex-direction:column; justify-content:center;
    align-items:center; text-align:center; }
  .bar { width:120px; height:6px; background:#3f8cff; border-radius:3px; margin:0 0 48px; }
  h1 { font-size:88px; font-weight:700; letter-spacing:6px; margin-bottom:36px; }
  h2 { font-size:52px; font-weight:500; color:#9fc3ea; letter-spacing:3px; }
  .foot { position:fixed; bottom:70px; left:0; right:0; text-align:center;
    font-size:30px; color:rgba(255,255,255,.45); letter-spacing:2px; }
</style></head><body>
<div class="wrap">
  <div class="bar"></div>
  <h1>${title}</h1>
  <h2>${subtitle}</h2>
</div>
<div class="foot">${foot}</div>
</body></html>`;

const FOOT = 'WMS 仓储管理系统 · 理想三寻';

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch();
  const page = await (await browser.newContext({
    viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1,
  })).newPage();

  const shots = [
    ['intro', narr.cards.intro.title, narr.cards.intro.subtitle],
    ...narr.cards.interludes.map((t, i) => [`inter${i + 1}`, t, TAG]),
    ['outro', narr.cards.outro.title, narr.cards.outro.subtitle],
  ];
  for (const [name, title, sub] of shots) {
    await page.setContent(HTML(title, sub, FOOT), { waitUntil: 'networkidle' });
    await page.evaluate(() => document.fonts.ready);
    await page.screenshot({ path: `${OUT}/${name}.png` });
    console.log('CARD_OK', name);
  }
  await browser.close();
  console.log('CARDS_ALL_OK');
})().catch(e => { console.error(e); process.exit(1); });
