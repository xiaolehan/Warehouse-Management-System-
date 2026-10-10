// 补充截图：顶栏用户下拉展开 + 修改密码弹窗（表单留空，不含任何密码内容）
// 用法：WMS_PWD=<密码> node shoot-password.js
const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');

const PWD = process.env.WMS_PWD || '';
if (!PWD) { console.error('WMS_PWD not set'); process.exit(1); }

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({
    viewport: { width: 1600, height: 900 },
    deviceScaleFactor: 2,
  });
  const out = path.join(__dirname, 'shots', 'warehouse_admin');
  fs.mkdirSync(out, { recursive: true });

  // 登录（与 shoot.js 同口径：清 localStorage 后重进）
  await page.goto('http://localhost:5173/login', { waitUntil: 'networkidle' });
  await page.evaluate(() => localStorage.clear());
  await page.reload({ waitUntil: 'networkidle' });
  await page.locator('input').first().fill('warehouse_admin');
  await page.locator('input[type=password]').first().fill(PWD);
  await page.locator('.submit-btn').click();
  await page.waitForURL('**/home', { timeout: 20000 });
  await page.waitForTimeout(3500); // 等「登录成功」toast 消失再截图

  // 1) 顶栏用户下拉展开
  await page.locator('.user-dropdown-trigger').click();
  await page.waitForTimeout(700);
  await page.screenshot({ path: path.join(out, 'user-menu.png') });
  console.log('user-menu OK');

  // 2) 修改密码弹窗（空表单）
  await page.locator('.el-dropdown-menu__item').filter({ hasText: '修改密码' }).click();
  await page.waitForTimeout(900);
  await page.screenshot({ path: path.join(out, 'change-password.png') });
  console.log('change-password OK');

  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
