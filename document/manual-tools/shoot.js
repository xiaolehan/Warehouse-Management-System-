// WMS 多角色逐页自动截图（playwright chromium, 1600×900 @2x）
// 用法: node shoot.js [role ...]   不带参数=全部角色
// 账号: ./accounts.json  { "role": "username", ... }（脚本不内置任何密码——密码统一占位由环境注入）
// 输出: shots/<role>/<name>.png
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const BASE = 'http://localhost:5173';
const OUT = path.join(__dirname, 'shots');
const VIEW = { width: 1600, height: 900 };
const PWD = process.env.WMS_PWD || '';
if (!PWD) { console.error('缺 WMS_PWD 环境变量（统一密码）'); process.exit(1); }
const ACC = JSON.parse(fs.readFileSync(path.join(__dirname, 'accounts.json'), 'utf8'));
const PLAN = {};

// ===== 每角色截图清单（路径对照 front/src/router/index.js 实测）=====
PLAN.warehouse_admin = [
  ['home', '/home'],
  ['goods', '/base/goods'], ['products', '/base/products'], ['bom', '/base/bom'],
  ['production-inbound', '/business/production'], ['pick-list', '/business/pick-list'],
  ['split-order', '/business/split-order'],
  ['sales-confirm', '/business/sales'], ['sales-return-confirm', '/business/sales-return'],
  ['purchase-confirm', '/business/purchase'], ['purchase-return-confirm', '/business/purchase-return'],
  ['purchase-request', '/business/purchase-request'],
  ['stocktake', '/business/stocktake'], ['stock-warning', '/business/stock-warning'],
  ['void-approval', '/system/void-approval'],
];
PLAN.production_admin = [
  ['home', '/home'],
  ['goods', '/base/goods'], ['products', '/base/products'], ['bom', '/base/bom'],
  ['production-order', '/business/production-order'], ['qc', '/business/qc'],
  ['production-inbound', '/business/production'], ['pick-list', '/business/pick-list'],
  ['split-order', '/business/split-order'],
  ['purchase-request', '/business/purchase-request'], ['stock-warning', '/business/stock-warning'],
];
PLAN.purchase_admin = [
  ['home', '/home'],
  ['supplier', '/base/supplier'], ['goods', '/base/goods'], ['products', '/base/products'],
  ['purchase', '/business/purchase'], ['purchase-return', '/business/purchase-return'],
  ['purchase-request', '/business/purchase-request'], ['stock-warning', '/business/stock-warning'],
];
PLAN.sales_admin = [
  ['home', '/home'],
  ['goods', '/base/goods'], ['products', '/base/products'],
  ['sales', '/business/sales'], ['sales-return', '/business/sales-return'],
  ['stock-warning', '/business/stock-warning'],
  ['void-approval', '/system/void-approval'], ['sys-config', '/system/config'],
];
PLAN.finance_admin = [
  ['home', '/home'],
  ['sales-chart', '/business/sales-chart'], ['annual-stats', '/business/annual-stats'],
];
PLAN.hr_admin = [
  ['home', '/home'],
  ['dept', '/system/dept'], ['employee', '/system/employee'], ['hr-chart', '/system/hr-chart'],
  ['work-requirement', '/system/work-requirement'], ['notice', '/system/notice'],
];
PLAN.superadmin = [
  ['home', '/home'],
  ['sa-dashboard', '/system/super-admin'], ['sa-security-ip-policy', '/system/security-ip-policy'],
  ['sa-login-log', '/system/login-log'], ['sa-operation-log', '/system/operation-log'],
  ['sa-dept-approval', '/system/dept-approval'],
  ['user-manage', '/system/user'], ['notice', '/system/notice'],
  ['work-requirement', '/system/work-requirement'], ['ai-assistant', '/assistant/project'],
];
PLAN.warehouse_user = [   // 员工视角示例：盲盘可见性差异
  ['home', '/home'], ['stocktake-emp', '/business/stocktake'],
];

// ===== 登录与截图 =====
async function shoot(page, role, pages) {
  const u = ACC[role];
  if (!u) { console.log(`跳过 ${role}（accounts.json 未配置）`); return; }
  await page.goto(BASE + '/login', { waitUntil: 'load' });
  await page.evaluate(() => localStorage.clear());
  await page.reload({ waitUntil: 'load' });
  await page.locator('input').first().fill(u);
  await page.fill('input[type="password"]', PWD);
  await page.click('.submit-btn');
  await page.waitForURL('**/home**', { timeout: 15000 });
  for (const [name, p] of pages) {
    await page.goto(BASE + p, { waitUntil: 'networkidle' });
    await page.waitForTimeout(1200);
    await page.screenshot({ path: path.join(OUT, role, name + '.png') });
    console.log(`${role}/${name} ok`);
  }
}

(async () => {
  const roles = process.argv.slice(2);
  const list = roles.length ? roles : Object.keys(PLAN);
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ viewport: VIEW, deviceScaleFactor: 2 });
  const page = await ctx.newPage();
  fs.mkdirSync(OUT, { recursive: true });
  for (const role of list) {
    if (!PLAN[role]) { console.log(`未知角色 ${role}，跳过`); continue; }
    fs.mkdirSync(path.join(OUT, role), { recursive: true });
    try { await shoot(page, role, PLAN[role]); }
    catch (e) { console.error(`${role} 失败: ${e.message.split('\n')[0]}`); }
  }
  await browser.close();
  console.log('全部完成');
})();
