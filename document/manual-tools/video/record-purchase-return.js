// 录制 WMS 视频教程第 3 集：采购退货全流程五幕（备料入库→确认入库→发起退货→确认出库→完成退货与核对）
// 用法：WMS_PWD=<密码> node record-purchase-return.js
// 前置：后端 8080（教程库）/ 前端 5173 运行中；先跑 node build.js tts narration-purchase-return.json
const { chromium } = require('playwright');
const {
  BASE, apiLogin, openScene, clickDemo, typeDemo, gotoMenu,
  runBeats, cleanupBeforeRecord, saveArtifacts,
} = require('./record-common');

// ---------- 第一幕：采购管理员备料——新建进货单 + 到货确认 ----------
async function act1(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act1', [
    { line: null, hold: 1400 },
    { line: 0 }, // 前提解说（画面停在采购首页）
    { line: 1, do: async () => {
        await gotoMenu(page, '物料进货', 1800, true);
        await clickDemo(page, page.getByRole('button', { name: '新增进货' }), 1400, '新增进货', true);
    } },
    { line: 2, do: async () => {
        await clickDemo(page, page.locator('.el-dialog .el-select').first(), 1000, '打开供应商下拉');
        await page.locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: '德州旺旺公司' }).first().click();
        await page.waitForTimeout(800);
        await clickDemo(page, page.locator('.el-dialog .el-table .el-select').first(), 1000, '打开物料下拉');
        await page.locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: 'PTO153 弹簧' }).first().click();
        await page.waitForTimeout(800);
        await typeDemo(page, page.locator('.el-dialog .el-table .el-input-number input').first(), '200', 900);
    } },
    { line: 3, do: async () => {
        await clickDemo(page, page.getByRole('button', { name: '确定新增' }), 800, '确定新增', true);
        await page.locator('.el-message', { hasText: '新增成功' }).first().waitFor({ timeout: 5000 });
    } },
    { line: 4, do: async () => {
        await page.waitForTimeout(600);
        await clickDemo(page, page.locator('.el-table__row').first().locator('.el-button', { hasText: '到货确认' }), 1200, '到货确认', true);
        await page.locator('.el-message-box').waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(900);
        await clickDemo(page, page.locator('.el-message-box__btns .el-button--primary'), 800, '确认到货');
        await page.locator('.el-message', { hasText: '已确认到货' }).first().waitFor({ timeout: 5000 });
    } },
  ]);
}

// ---------- 第二幕：仓储管理员确认入库 ----------
async function act2(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act2', [
    { line: null, hold: 1200 },
    { line: 0, do: () => gotoMenu(page, '物料入库确认', 2000, false) },
    { line: 1, do: async () => {
        await page.locator('.el-table__row').first().waitFor({ timeout: 8000 });
        await page.waitForTimeout(500);
        await clickDemo(page, page.locator('.el-table__row').first().locator('.el-button', { hasText: '确认入库' }), 1200, '确认入库', true);
        await page.locator('.el-message-box').waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(900);
        await clickDemo(page, page.locator('.el-message-box__btns .el-button--primary'), 800, '确定');
        await page.locator('.el-message', { hasText: '已确认入库' }).first().waitFor({ timeout: 5000 });
    } },
    { line: 2 }, // 库存 1→201 解说（画面停留在列表，状态已入库）
  ]);
}

// ---------- 第三幕：采购管理员发起退货 ----------
async function act3(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act3', [
    { line: null, hold: 1200 },
    { line: 0 }, // 质量问题背景解说（画面停在采购首页）
    { line: 1, do: () => gotoMenu(page, '物料退货', 2000, true) },
    { line: 2, do: () => clickDemo(page, page.getByRole('button', { name: '新建退货单' }), 1400, '新建退货单', true) },
    { line: 3, do: async () => {
        await clickDemo(page, page.locator('.el-dialog .el-select').first(), 1100, '打开来源进货单下拉');
        await page.locator('.el-select-dropdown:visible .el-select-dropdown__item').first().click();
        await page.locator('.el-dialog .el-table__row').first().waitFor({ timeout: 6000 });
        await page.waitForTimeout(700);
    } },
    { line: 4, do: async () => {
        await clickDemo(page, page.locator('.el-dialog .el-table .el-checkbox').first(), 600, '勾选明细行');
        await typeDemo(page, page.locator('.el-dialog .el-table .el-input-number input').first(), '5', 900);
    } },
    { line: 5, do: async () => {
        await typeDemo(page, page.locator('.el-dialog textarea').first(), '质检不合格，退回供应商', 700);
        await clickDemo(page, page.getByRole('button', { name: '确定发起' }), 800, '确定发起', true);
        await page.locator('.el-message', { hasText: '退货开单成功' }).first().waitFor({ timeout: 5000 });
    } },
    { line: 6 }, // 列表新行「待出库确认」解说
  ]);
}

// ---------- 第四幕：仓储管理员确认出库（消息邮箱入场） ----------
async function act4(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act4', [
    { line: null, hold: 1200 },
    { line: 0, do: async () => {
        await clickDemo(page, page.locator('.message-button'), 1500, '消息邮箱', true);
        await page.locator('.message-dialog').waitFor({ state: 'visible', timeout: 5000 });
    } },
    { line: 1, do: async () => {
        await page.locator('.message-card', { hasText: '待确认商品退货出库' }).first().waitFor({ timeout: 5000 });
        await clickDemo(page, page.locator('.message-card.unread').first(), 1500, '点击退货出库消息', true);
        await page.locator('.el-table__row').first().waitFor({ timeout: 8000 });
    } },
    { line: 2, do: async () => {
        await page.waitForTimeout(600);
        await clickDemo(page, page.locator('.el-table__row').first().locator('.el-button', { hasText: '确认出库' }), 1200, '确认出库', true);
        await page.locator('.el-message-box').waitFor({ state: 'visible', timeout: 5000 });
    } },
    { line: 3, do: async () => {
        await page.waitForTimeout(900);
        await clickDemo(page, page.locator('.el-message-box__btns .el-button--primary'), 800, '确定出库');
        await page.locator('.el-message', { hasText: '已确认出库' }).first().waitFor({ timeout: 5000 });
    } },
    { line: 4 }, // 「待退货确认」解说
  ]);
}

// ---------- 第五幕：采购管理员完成退货 + 时间线 + 库存/进价核对 ----------
async function act5(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act5', [
    { line: null, hold: 1200 },
    { line: 0, do: () => gotoMenu(page, '物料退货', 2000, false) },
    { line: 1, do: async () => {
        await page.locator('.el-table__row').first().waitFor({ timeout: 8000 });
        await page.waitForTimeout(500);
        await clickDemo(page, page.locator('.el-table__row').first().locator('.el-button', { hasText: '确认退货成功' }), 1200, '确认退货成功', true);
        await page.locator('.el-message-box').waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(900);
        await clickDemo(page, page.locator('.el-message-box__btns .el-button--primary'), 800, '确定');
        await page.locator('.el-message', { hasText: '已确认退货成功' }).first().waitFor({ timeout: 5000 });
    } },
    { line: 2, do: async () => {
        await page.waitForTimeout(600);
        await clickDemo(page, page.locator('.el-table__row').first().locator('.el-button', { hasText: '详情' }), 1400, '查看详情', true);
        await page.locator('.el-dialog', { hasText: '查看退货信息' }).waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(700);
        await page.locator('.el-dialog__body').first().evaluate(el => el.scrollTo(0, el.scrollHeight));
        await page.waitForTimeout(1200);
        await page.keyboard.press('Escape');
        await page.waitForTimeout(600);
    } },
    { line: 3, do: async () => {
        await gotoMenu(page, '物料管理', 1600, false);
        await page.locator('.el-table__row').first().waitFor({ timeout: 8000 });
        await typeDemo(page, page.getByPlaceholder('请输入物料名称'), 'PTO153 弹簧', 700);
        await clickDemo(page, page.getByRole('button', { name: '查询' }), 1800, '查询', true);
    } },
    { line: 4, do: async () => {
        const row = page.locator('.el-table__row', { hasText: 'PTO153 弹簧' }).first();
        await row.waitFor({ timeout: 8000 });
        await page.evaluate(() => window.__demoMove(1300, 420)); // 光标移向库存列
        await page.waitForTimeout(1400);
        await clickDemo(page, row.locator('.el-button', { hasText: '历史' }), 1400, '进价历史', true);
        await page.locator('.el-dialog', { hasText: '进价历史' }).waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(700);
    } },
    { line: 5 }, // 进价历史弹窗停留（退货不回改进价）
    { line: 6, do: async () => {
        await page.keyboard.press('Escape');
        await page.waitForTimeout(800);
    } },
  ]);
}

// ---------- 主流程 ----------
(async () => {
  // 防串集：清掉上一集的点击/锚点记录与原始录像，缺失幕不会被旧素材静默顶替
  cleanupBeforeRecord(['act1', 'act2', 'act3', 'act4', 'act5']);
  const browser = await chromium.launch();

  const purchase = await apiLogin('purchase_admin');
  const warehouse = await apiLogin('warehouse_admin');

  // 每幕独立上下文独立 webm，幕间由过场卡衔接
  let s = await openScene(browser, 'act1', purchase);
  await act1(s.page);
  await s.ctx.close();

  s = await openScene(browser, 'act2', warehouse);
  await act2(s.page);
  await s.ctx.close();

  s = await openScene(browser, 'act3', purchase);
  await act3(s.page);
  await s.ctx.close();

  s = await openScene(browser, 'act4', warehouse);
  await act4(s.page);
  await s.ctx.close();

  s = await openScene(browser, 'act5', purchase);
  await act5(s.page);
  await s.ctx.close();

  await browser.close();
  saveArtifacts();
  console.log('REC_ALL_OK');
})().catch(e => { console.error(e); process.exit(1); });
