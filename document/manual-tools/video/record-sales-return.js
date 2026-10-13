// 录制 WMS 视频教程第 5 集：销售退货全流程五幕（备销售单→确认出库→创建退货单→确认退货入库→核对库存与时间线）
// 用法：WMS_PWD=<密码> node record-sales-return.js
// 前置：后端 8080（教程库）/ 前端 5173 运行中；先跑 node build.js tts narration-sales-return.json
const { chromium } = require('playwright');
const {
  BASE, apiLogin, openScene, clickDemo, typeDemo, gotoMenu,
  runBeats, cleanupBeforeRecord, saveArtifacts,
} = require('./record-common');

// ---------- 第一幕：销售管理员备一笔已出库销售单 ----------
async function act1(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act1', [
    { line: null, hold: 1400 },
    { line: 0 }, // 前提解说（画面停在销售首页）
    { line: 1, do: async () => {
        await gotoMenu(page, '商品销售', 2200, true);
        await page.locator('.el-table__empty-block, .el-table__row').first().waitFor({ timeout: 8000 });
        await clickDemo(page, page.getByRole('button', { name: '新建销售单' }), 1600, '新建销售单', true);
    } },
    { line: 2, do: async () => {
        await typeDemo(page, page.getByPlaceholder('请输入客户公司名（可选）'), '演示客户·华东机械', 700);
        await clickDemo(page, page.locator('.el-dialog .el-table .el-select').first(), 1100, '打开成品下拉');
        await clickDemo(page, page.locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: 'SMC105' }).first(), 1600, '选择SMC105');
        await typeDemo(page, page.locator('.el-dialog .el-table .el-input-number input').first(), '1', 800);
        await typeDemo(page, page.locator('.el-dialog .el-table .el-input-number input').nth(1), '12999', 900);
        await page.waitForTimeout(800); // 标准售价：无价格偏离审批，直入待出库
    } },
    { line: 3, do: async () => {
        await clickDemo(page, page.getByRole('button', { name: '确定新增' }), 800, '确定新增', true);
        await page.locator('.el-message', { hasText: '销售单' }).first().waitFor({ timeout: 5000 });
    } },
  ]);
}

// ---------- 第二幕：仓储管理员确认出库 ----------
async function act2(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act2', [
    { line: null, hold: 1200 },
    { line: 0, do: () => gotoMenu(page, '销售出库确认', 2000, true) },
    { line: 1, do: async () => {
        await page.locator('.el-table__row').first().waitFor({ timeout: 8000 });
        await page.waitForTimeout(500);
        await clickDemo(page, page.locator('.el-table__row').first().locator('.el-button', { hasText: '确认出库' }), 1300, '确认出库', true);
        await page.locator('.el-message-box').waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(900);
        await clickDemo(page, page.locator('.el-message-box__btns .el-button--primary'), 800, '确定');
        await page.locator('.el-message', { hasText: '已确认出库' }).first().waitFor({ timeout: 5000 });
    } },
    { line: 2 }, // 库存 4→3 解说（画面停留在列表，单据已出库）
  ]);
}

// ---------- 第三幕：销售管理员创建退货单 ----------
async function act3(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act3', [
    { line: null, hold: 1200 },
    { line: 0 }, // 质量问题背景解说（画面停在销售首页）
    { line: 1, do: () => gotoMenu(page, '销售退货', 2000, true) },
    { line: 2, do: async () => {
        await clickDemo(page, page.getByRole('button', { name: '新建销售退货单' }), 1400, '新建销售退货单', true);
        await page.locator('.el-dialog:visible', { hasText: '新增销售退货单' }).waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(700);
        await clickDemo(page, page.locator('.el-dialog .el-select').first(), 1100, '打开来源销售单下拉');
        await page.locator('.el-select-dropdown:visible .el-select-dropdown__item').first().click();
        await page.locator('.el-dialog .el-table__row').first().waitFor({ timeout: 6000 });
        await page.waitForTimeout(700); // 明细行自动带出原行数量与可退数量
    } },
    { line: 3, do: async () => {
        await typeDemo(page, page.locator('.el-dialog .el-table .el-input-number input').first(), '1', 900);
        await page.waitForTimeout(600); // 退货单价自动带出 12999（画面停留即展示）
    } },
    { line: 4, do: async () => {
        // 退货日期必填且默认空——先补填（不入解说，逐键输入+回车提交）
        await typeDemo(page, page.locator('.el-dialog .el-date-editor input').first(), '2026-10-12 10:00:00', 500);
        await page.keyboard.press('Enter');
        await page.waitForTimeout(700);
        await typeDemo(page, page.locator('.el-dialog textarea').first(), '质量问题，客户退回', 700);
        await clickDemo(page, page.getByRole('button', { name: '确定新增' }), 800, '确定新增', true);
        await page.locator('.el-message', { hasText: '销售退货新增成功' }).first().waitFor({ timeout: 5000 });
    } },
    { line: 5 }, // 列表新行「待仓库确认」解说
  ]);
}

// ---------- 第四幕：仓储管理员消息邮箱入场 → 确认退货入库 ----------
async function act4(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act4', [
    { line: null, hold: 1200 },
    { line: 0, do: async () => {
        await clickDemo(page, page.locator('.message-button'), 1500, '消息邮箱', true);
        await page.locator('.message-dialog').waitFor({ state: 'visible', timeout: 5000 });
    } },
    { line: 1, do: async () => {
        await page.locator('.message-card', { hasText: '待确认销售退货入库' }).first().waitFor({ timeout: 5000 });
        await clickDemo(page, page.locator('.message-card', { hasText: '待确认销售退货入库' }).first(), 1500, '点击退货入库消息', true);
        await page.locator('.el-table__row').first().waitFor({ timeout: 8000 });
    } },
    { line: 2, do: async () => {
        await page.waitForTimeout(600);
        await clickDemo(page, page.locator('.el-table__row').first().locator('.el-button', { hasText: '确认入库' }), 1300, '确认入库', true);
        await page.locator('.el-message-box').waitFor({ state: 'visible', timeout: 5000 });
    } },
    { line: 3, do: async () => {
        await page.waitForTimeout(900);
        await clickDemo(page, page.locator('.el-message-box__btns .el-button--primary'), 800, '确定入库');
        await page.locator('.el-message', { hasText: '已确认入库' }).first().waitFor({ timeout: 5000 });
    } },
    { line: 4 }, // 「已确认入库」解说
  ]);
}

// ---------- 第五幕：仓储管理员核对时间线与库存 ----------
async function act5(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act5', [
    { line: null, hold: 1200 },
    { line: 0, do: async () => {
        await gotoMenu(page, '销售退货入库确认', 1600, false);
        await page.locator('.el-table__row').first().waitFor({ timeout: 8000 });
        await page.waitForTimeout(500);
        await clickDemo(page, page.locator('.el-table__row').first().locator('.el-button', { hasText: '详情' }), 1600, '查看详情', true);
        await page.locator('.el-dialog:visible', { hasText: '销售退货详情' }).waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(1600); // 时间线：创建退货单 / 仓储入库确认两节点
    } },
    { line: 1, do: async () => {
        await page.keyboard.press('Escape');
        await page.locator('.el-dialog:visible', { hasText: '销售退货详情' }).waitFor({ state: 'hidden', timeout: 5000 });
        await page.waitForTimeout(500);
        await gotoMenu(page, '成品管理', 1800, false);
        await page.locator('.el-table__row').first().waitFor({ timeout: 8000 });
        await typeDemo(page, page.getByPlaceholder('请输入成品名称'), 'SMC105', 700);
        await clickDemo(page, page.getByRole('button', { name: '查询' }), 1800, '查询', true);
    } },
    { line: 2, do: async () => {
        const row = page.locator('.el-table__row', { hasText: 'SMC105' }).first();
        await row.waitFor({ timeout: 8000 });
        await page.evaluate(() => window.__demoMove(1300, 420)); // 光标移向库存列
        await page.waitForTimeout(1600);
    } },
    { line: 3 }, // 收尾解说（画面停留在库存行）
  ]);
}

// ---------- 主流程 ----------
(async () => {
  // 防串集：清掉上一集的点击/锚点记录与原始录像，缺失幕不会被旧素材静默顶替
  cleanupBeforeRecord(['act1', 'act2', 'act3', 'act4', 'act5']);
  const browser = await chromium.launch();

  const sales = await apiLogin('sales_admin');
  const warehouse = await apiLogin('warehouse_admin');

  // 每幕独立上下文独立 webm，幕间由过场卡衔接
  let s = await openScene(browser, 'act1', sales);
  await act1(s.page);
  await s.ctx.close();

  s = await openScene(browser, 'act2', warehouse);
  await act2(s.page);
  await s.ctx.close();

  s = await openScene(browser, 'act3', sales);
  await act3(s.page);
  await s.ctx.close();

  s = await openScene(browser, 'act4', warehouse);
  await act4(s.page);
  await s.ctx.close();

  s = await openScene(browser, 'act5', warehouse);
  await act5(s.page);
  await s.ctx.close();

  await browser.close();
  saveArtifacts();
  console.log('REC_ALL_OK');
})().catch(e => { console.error(e); process.exit(1); });








