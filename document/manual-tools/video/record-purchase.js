// 录制 WMS 视频教程第 2 集：采购全流程四幕（新建进货单→到货确认→仓储确认入库→库存核对）
// 用法：WMS_PWD=<密码> node record-purchase.js
// 前置：后端 8080（教程库）/ 前端 5173 运行中；先跑 node build.js tts narration-purchase.json
const { chromium } = require('playwright');
const {
  BASE, apiLogin, openScene, clickDemo, typeDemo, gotoMenu,
  runBeats, cleanupBeforeRecord, saveArtifacts,
} = require('./record-common');

// ---------- 第一幕：采购管理员新建进货单 ----------
async function act1(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act1', [
    { line: null, hold: 1400 },
    { line: 0, do: () => gotoMenu(page, '物料进货', 2200, true) },
    { line: 1, do: () => page.locator('.el-table__empty-block, .el-table__row').first().waitFor({ timeout: 8000 }) },
    { line: 2, do: () => clickDemo(page, page.getByRole('button', { name: '新增进货' }), 1600, '新增进货', true) },
    { line: 3, do: async () => {
        await clickDemo(page, page.locator('.el-dialog .el-select').first(), 1100, '打开供应商下拉');
        await page.locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: '德州旺旺公司' }).first().click();
        await page.waitForTimeout(900);
    } },
    { line: 4, do: async () => {
        await clickDemo(page, page.locator('.el-dialog .el-table .el-select').first(), 1100, '打开物料下拉');
        await page.locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: 'PTO153 弹簧' }).first().click();
        await page.waitForTimeout(900);
    } },
    { line: 5, do: () => page.getByText(/当前库存：/).first().waitFor({ state: 'visible', timeout: 6000 }) },
    { line: 6, do: () => typeDemo(page, page.locator('.el-dialog .el-table .el-input-number input').first(), '200', 900) },
    { line: 7 }, // 进货日期/备注解说（默认值即可，无操作）
    { line: 8, do: async () => {
        await clickDemo(page, page.getByRole('button', { name: '确定新增' }), 800, '确定新增', true);
        await page.locator('.el-message', { hasText: '新增成功' }).first().waitFor({ timeout: 5000 });
    } },
    { line: 9 }, // 停留画面，等本行解说播完（toast 已在上一拍确认）
  ]);
}

// ---------- 第二幕：采购管理员到货确认 ----------
async function act2(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act2', [
    { line: null, hold: 1200 },
    { line: 0, do: async () => {
        await gotoMenu(page, '物料进货', 1800, false);
        await page.locator('.el-table__row').first().waitFor({ timeout: 8000 });
        await page.waitForTimeout(600);
    } },
    { line: 1, do: async () => {
        await clickDemo(page, page.locator('.el-table__row').first().locator('.el-button', { hasText: '到货确认' }), 1200, '到货确认', true);
        await page.locator('.el-message-box').waitFor({ state: 'visible', timeout: 5000 });
    } },
    { line: 2, do: async () => {
        await page.waitForTimeout(900);
        await clickDemo(page, page.locator('.el-message-box__btns .el-button--primary'), 800, '确认到货');
        await page.locator('.el-message', { hasText: '已确认到货' }).first().waitFor({ timeout: 5000 });
    } },
  ]);
}

// ---------- 第三幕：仓储管理员确认入库（消息邮箱入场） ----------
async function act3(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act3', [
    { line: null, hold: 1200 },
    { line: 0 }, // 「切换到仓储管理员」——画面即仓储首页
    { line: 1, do: async () => {
        await clickDemo(page, page.locator('.message-button'), 1500, '消息邮箱', true);
        await page.locator('.message-dialog').waitFor({ state: 'visible', timeout: 5000 });
    } },
    { line: 2, do: async () => {
        await page.locator('.message-card', { hasText: '待确认进货入库' }).first().waitFor({ timeout: 5000 });
        await clickDemo(page, page.locator('.message-card.unread').first(), 1500, '点击进货入库消息', true);
        // 采购深链仅跳列表页（不带 query、不自动开详情）：等列表行出现即可
        await page.locator('.el-table__row').first().waitFor({ timeout: 8000 });
    } },
    { line: 3, do: async () => {
        await page.waitForTimeout(600);
        await clickDemo(page, page.locator('.el-table__row').first().locator('.el-button', { hasText: '确认入库' }), 1200, '确认入库', true);
        await page.locator('.el-message-box').waitFor({ state: 'visible', timeout: 5000 });
    } },
    { line: 4, do: async () => {
        await page.waitForTimeout(900);
        await clickDemo(page, page.locator('.el-message-box__btns .el-button--primary'), 800, '确定');
        await page.locator('.el-message', { hasText: '已确认入库' }).first().waitFor({ timeout: 5000 });
    } },
    { line: 5 }, // 停留画面，等本行解说播完（toast 已在上一拍确认）
  ]);
}

// ---------- 第四幕：库存核对（物料管理 1→201） ----------
async function act4(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act4', [
    { line: null, hold: 1200 },
    { line: 0, do: () => gotoMenu(page, '物料管理') },
    { line: 1, do: async () => {
        await page.locator('.el-table__row').first().waitFor({ timeout: 8000 });
        await typeDemo(page, page.getByPlaceholder('请输入物料名称'), 'PTO153 弹簧', 700);
        await clickDemo(page, page.getByRole('button', { name: '查询' }), 1800, '查询', true);
    } },
    { line: 2, do: async () => {
        await page.locator('.el-table__row', { hasText: 'PTO153 弹簧' }).first().waitFor({ timeout: 8000 });
        const row = page.locator('.el-table__row', { hasText: 'PTO153 弹簧' }).first();
        await page.evaluate(() => window.__demoMove(1300, 420)); // 光标移向库存列
        await page.waitForTimeout(1400);
        await row.waitFor({ state: 'visible' });
    } },
    { line: 3 }, // 收尾定格（驻留由节拍执行器按本行解说时长给出）
  ]);
}

// ---------- 主流程 ----------
(async () => {
  // 防串集：清掉上一集的点击/锚点记录与原始录像，缺失幕不会被旧素材静默顶替
  cleanupBeforeRecord(['act1', 'act2', 'act3', 'act4']);
  const browser = await chromium.launch();

  const purchase = await apiLogin('purchase_admin');
  const warehouse = await apiLogin('warehouse_admin');

  // 每幕独立上下文独立 webm，幕间由过场卡衔接
  let s = await openScene(browser, 'act1', purchase);
  await act1(s.page);
  await s.ctx.close();

  s = await openScene(browser, 'act2', purchase);
  await act2(s.page);
  await s.ctx.close();

  s = await openScene(browser, 'act3', warehouse);
  await act3(s.page);
  await s.ctx.close();

  s = await openScene(browser, 'act4', warehouse);
  await act4(s.page);
  await s.ctx.close();

  await browser.close();
  saveArtifacts();
  console.log('REC_ALL_OK');
})().catch(e => { console.error(e); process.exit(1); });
