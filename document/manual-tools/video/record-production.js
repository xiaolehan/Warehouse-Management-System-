// 录制 WMS 视频教程第 4 集：生产全流程七幕（BOM→下达与齐套→申请领料→发料→收货开工打卡→成品测与入库申请→成品入库核对）
// 用法：WMS_PWD=<密码> node record-production.js
// 前置：后端 8080（教程库）/ 前端 5173 运行中；先跑 node build.js tts narration-production.json
const { chromium } = require('playwright');
const {
  BASE, apiLogin, openScene, clickDemo, typeDemo, gotoMenu,
  runBeats, cleanupBeforeRecord, saveArtifacts,
} = require('./record-common');

// 工序打卡通用步：详情工序表行 → 打卡 → 确认 → toast
async function punch(page, stepName) {
  const row = page.locator('.el-dialog:visible .el-table__row', { hasText: stepName }).first();
  await row.waitFor({ timeout: 8000 });
  await clickDemo(page, row.locator('.el-button', { hasText: '打卡' }).first(), 1000, `打卡:${stepName}`, true);
  await page.locator('.el-message-box').waitFor({ state: 'visible', timeout: 5000 });
  await page.waitForTimeout(800);
  await clickDemo(page, page.locator('.el-message-box__btns .el-button--primary'), 700, '确认打卡');
  await page.locator('.el-message', { hasText: '已打卡' }).first().waitFor({ timeout: 5000 });
}

// 质检录入通用步：按任务单成品名定位行 → 质检 → 测点/结果 → 提交 → toast
// 注意：keep-alive 下残留在 DOM 的其他弹窗会污染未限定的 .el-dialog 查询，一律限定 :visible + hasText 生产质检
async function qcRecord(page, goodsName, point, result) {
  const row = page.locator('.el-table__row', { hasText: goodsName }).first();
  await row.waitFor({ timeout: 8000 });
  await clickDemo(page, row.locator('.el-button', { hasText: '质检' }), 1200, `质检:${point}`, true);
  const dlg = page.locator('.el-dialog:visible', { hasText: '生产质检' });
  await dlg.waitFor({ state: 'visible', timeout: 5000 });
  await page.waitForTimeout(700);
  await clickDemo(page, dlg.locator('.el-select').nth(0), 900, '打开测点下拉');
  await page.locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: point }).first().click({ timeout: 8000 });
  await page.waitForTimeout(600);
  await clickDemo(page, dlg.locator('.el-select').nth(1), 900, '打开结果下拉');
  await page.locator('.el-select-dropdown:visible .el-select-dropdown__item').filter({ hasText: new RegExp(`^${result} OK$`) }).first().click({ timeout: 8000 }).catch(() => page.locator('.el-select-dropdown:visible .el-select-dropdown__item').filter({ hasText: new RegExp(`^${result} OK$`) }).first().click({ force: true, timeout: 5000 }));
  await page.waitForTimeout(600);
  await clickDemo(page, dlg.getByRole('button', { name: '提交', exact: true }), 900, '提交质检', true);
  await page.locator('.el-message', { hasText: '测试结果已录入' }).first().waitFor({ timeout: 5000 });
  // 关键：录入完成后关闭弹窗，否则 overlay 罩住全屏，后续 gotoMenu/行点击全被拦截
  await page.keyboard.press('Escape');
  await dlg.waitFor({ state: 'hidden', timeout: 5000 });
  await page.waitForTimeout(400);
}

// ---------- 第一幕：生产管理员·BOM 物料清单维护 ----------
async function act1(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act1', [
    { line: null, hold: 1400 },
    { line: 0 }, // BOM 楔子（画面停在首页）
    { line: 1, do: async () => {
        await gotoMenu(page, 'BOM 管理', 1800, true);
        await clickDemo(page, page.getByRole('button', { name: '新建 BOM' }), 1400, '新建 BOM', true);
    } },
    { line: 2, do: async () => {
        await typeDemo(page, page.getByPlaceholder('如 PTO153（一个 BOM 即一种成品）'), '演示成品', 700);
        await typeDemo(page, page.getByPlaceholder('如 台（可空）'), '台', 600);
        await clickDemo(page, page.getByRole('button', { name: '添加明细行' }), 900, '添加明细行', true);
        await typeDemo(page, page.locator('.el-dialog .el-table .el-input input').first(), '测试物料1', 700);
        await typeDemo(page, page.locator('.el-dialog .el-table input[type="number"]').first(), '1', 800);
    } },
    { line: 3, do: async () => {
        await clickDemo(page, page.locator('.el-dialog__footer .el-button--primary'), 800, '确认新增', true);
        await page.locator('.el-message', { hasText: '新增成功' }).first().waitFor({ timeout: 5000 });
    } },
    { line: 4, do: async () => {
        await page.waitForTimeout(600);
        const row = page.locator('.el-table__row', { hasText: 'test1' }).first();
        await row.waitFor({ timeout: 8000 });
        await clickDemo(page, row.locator('.el-button', { hasText: '详情' }).first(), 1500, 'test1 BOM 详情', true);
        await page.locator('.el-drawer', { hasText: 'BOM 详情' }).waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(1500);
    } },
  ]);
}

// ---------- 第二幕：生产管理员·下达任务单与齐套预警 ----------
async function act2(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act2', [
    { line: null, hold: 1200 },
    { line: 0, do: () => gotoMenu(page, '生产任务单', 2000, false) },
    { line: 1, do: async () => {
        await clickDemo(page, page.getByRole('button', { name: '下达生产任务单' }), 1400, '下达生产任务单', true);
        await clickDemo(page, page.locator('.el-dialog .el-select').first(), 1000, '打开成品下拉');
        await page.locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: 'test1' }).first().click();
        await page.waitForTimeout(700);
        await typeDemo(page, page.locator('.el-dialog .el-input-number input').first(), '1', 700);
        await clickDemo(page, page.locator('.el-dialog__footer .el-button--primary'), 900, '下达并预警', true);
        await page.locator('.el-message', { hasText: '生产任务单已下达' }).first().waitFor({ timeout: 5000 });
    } },
    { line: 2, do: async () => {
        await page.locator('.el-dialog .el-alert--success').waitFor({ state: 'visible', timeout: 8000 });
        await page.waitForTimeout(1400);
    } },
    { line: 3, do: async () => {
        await clickDemo(page, page.locator('.el-dialog__footer .el-button', { hasText: '关闭' }), 800, '关闭弹窗');
        await page.waitForTimeout(600);
        await clickDemo(page, page.getByRole('button', { name: '下达生产任务单' }), 1400, '再次下达', true);
        await clickDemo(page, page.locator('.el-dialog .el-select').first(), 1000, '打开成品下拉');
        await page.locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: 'PTO153' }).first().click();
        await page.waitForTimeout(700);
        await typeDemo(page, page.locator('.el-dialog .el-input-number input').first(), '1', 700);
        await clickDemo(page, page.locator('.el-dialog__footer .el-button--primary'), 900, '下达并预警(缺料)', true);
        await page.locator('.el-dialog .el-alert--error').waitFor({ state: 'visible', timeout: 8000 });
    } },
    { line: 4, do: async () => {
        await page.waitForTimeout(1200);
        await clickDemo(page, page.locator('.el-dialog__footer .el-button', { hasText: '关闭' }), 900, '关闭弹窗');
    } },
  ]);
}

// ---------- 第三幕：生产管理员·申请领料（缺料拦截 + 齐套成功） ----------
async function act3(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act3', [
    { line: null, hold: 1200 },
    { line: 0, do: () => gotoMenu(page, '生产任务单', 2000, false) },
    { line: 1, do: async () => {
        const row = page.locator('.el-table__row', { hasText: 'PTO153' }).first();
        await row.waitFor({ timeout: 8000 });
        await clickDemo(page, row.locator('.el-button', { hasText: '详情' }), 1400, 'PTO153 详情', true);
        await page.locator('.el-dialog', { hasText: '生产任务单详情' }).waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(700);
        await clickDemo(page, page.locator('.el-dialog .el-button', { hasText: '申请领料' }), 1200, '申请领料(缺料拦截)', true);
        await page.locator('.el-message-box').waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(900);
        await clickDemo(page, page.locator('.el-message-box__btns .el-button--primary'), 800, '确认申请领料');
        await page.locator('.el-message', { hasText: '库存不足' }).first().waitFor({ timeout: 6000 });
    } },
    { line: 2, do: async () => {
        await page.waitForTimeout(900);
        await page.keyboard.press('Escape');
        await page.waitForTimeout(700);
        const row = page.locator('.el-table__row', { hasText: 'test1' }).first();
        await row.waitFor({ timeout: 8000 });
        await clickDemo(page, row.locator('.el-button', { hasText: '详情' }), 1400, 'test1 详情', true);
        await page.locator('.el-dialog', { hasText: '生产任务单详情' }).waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(700);
        await clickDemo(page, page.locator('.el-dialog .el-button', { hasText: '申请领料' }), 1200, '申请领料(齐套成功)', true);
        await page.locator('.el-message-box').waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(900);
        await clickDemo(page, page.locator('.el-message-box__btns .el-button--primary'), 900, '确认申请领料');
        await page.locator('.el-message', { hasText: '领料申请已提交' }).first().waitFor({ timeout: 6000 });
    } },
    { line: 3 }, // 领料明细生成、待出库状态解说（详情弹窗保持打开）
  ]);
}

// ---------- 第四幕：仓储管理员·领料发料（消息邮箱入场） ----------
async function act4(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act4', [
    { line: null, hold: 1200 },
    { line: 0 }, // 切换角色解说（画面停在仓储首页）
    { line: 1, do: async () => {
        await clickDemo(page, page.locator('.message-button'), 1500, '消息邮箱', true);
        await page.locator('.message-dialog').waitFor({ state: 'visible', timeout: 5000 });
        await page.locator('.message-card', { hasText: '待确认生产领料出库' }).first().waitFor({ timeout: 5000 });
        await clickDemo(page, page.locator('.message-card.unread', { hasText: '待确认生产领料出库' }).first(), 1500, '点击领料出库消息', true);
        await page.locator('.el-table__row').first().waitFor({ timeout: 8000 });
    } },
    { line: 2, do: async () => {
        await page.waitForTimeout(600);
        await clickDemo(page, page.locator('.el-table__row').first().locator('.el-button', { hasText: '发料' }), 1200, '发料', true);
        await page.locator('.el-message-box').waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(1300);
    } },
    { line: 3, do: async () => {
        await clickDemo(page, page.locator('.el-message-box__btns .el-button--primary'), 900, '确认发料');
        await page.locator('.el-message', { hasText: '发料成功' }).first().waitFor({ timeout: 6000 });
    } },
  ]);
}

// ---------- 第五幕：生产管理员·确认收货与开工打卡 ----------
async function act5(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act5', [
    { line: null, hold: 1200 },
    { line: 0, do: async () => {
        await gotoMenu(page, '生产任务单', 2000, false);
        const row = page.locator('.el-table__row', { hasText: 'test1' }).first();
        await row.waitFor({ timeout: 8000 });
        await clickDemo(page, row.locator('.el-button', { hasText: '详情' }), 1400, 'test1 详情', true);
        await page.locator('.el-dialog', { hasText: '生产任务单详情' }).waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(700);
    } },
    { line: 1, do: async () => {
        await clickDemo(page, page.locator('.el-dialog .el-button', { hasText: '确认收货' }), 1200, '确认收货', true);
        await page.locator('.el-message', { hasText: '已确认收货' }).first().waitFor({ timeout: 6000 });
        await page.waitForTimeout(500);
        await page.keyboard.press('Escape');
        await page.waitForTimeout(700);
        const row = page.locator('.el-table__row', { hasText: 'test1' }).first();
        await clickDemo(page, row.locator('.el-button', { hasText: '开工' }), 1200, '开工', true);
        await page.locator('.el-message-box').waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(900);
        await clickDemo(page, page.locator('.el-message-box__btns .el-button--primary'), 800, '确认开工');
        await page.locator('.el-message', { hasText: '已开工' }).first().waitFor({ timeout: 6000 });
    } },
    { line: 2, do: async () => {
        await page.waitForTimeout(800);
        const row = page.locator('.el-table__row', { hasText: 'test1' }).first();
        await clickDemo(page, row.locator('.el-button', { hasText: '详情' }), 1400, '重开详情(工序表)', true);
        await page.locator('.el-dialog', { hasText: '生产任务单详情' }).waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(1500);
    } },
    { line: 3, do: async () => {
        await punch(page, '磁性材料装配');
        await punch(page, '底座结构组装');
        await punch(page, '手柄机构装配');
        await punch(page, 'PCB板焊接及安装');
        await punch(page, '程序烧录');
    } },
    { line: 4, do: async () => {
        await page.keyboard.press('Escape');
        await page.waitForTimeout(700);
        await gotoMenu(page, '质检记录', 1800, false);
        await qcRecord(page, 'test1', '首测', '合格');
    } },
  ]);
}

// ---------- 第六幕：生产管理员·成品测与入库申请 ----------
async function act6(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act6', [
    { line: null, hold: 1200 },
    { line: 0, do: async () => {
        await gotoMenu(page, '生产任务单', 2000, false);
        const row = page.locator('.el-table__row', { hasText: 'test1' }).first();
        await row.waitFor({ timeout: 8000 });
        await clickDemo(page, row.locator('.el-button', { hasText: '详情' }), 1400, 'test1 详情', true);
        await page.locator('.el-dialog', { hasText: '生产任务单详情' }).waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(700);
        await punch(page, '屏蔽壳安装');
    } },
    { line: 1, do: async () => {
        await page.keyboard.press('Escape');
        await page.waitForTimeout(700);
        await gotoMenu(page, '质检记录', 1800, false);
        await qcRecord(page, 'test1', '成品测', '合格');
    } },
    { line: 2, do: async () => {
        await gotoMenu(page, '生产任务单', 1800, false);
        const row = page.locator('.el-table__row', { hasText: 'test1' }).first();
        await row.waitFor({ timeout: 8000 });
        await clickDemo(page, row.locator('.el-button', { hasText: '详情' }), 1400, 'test1 详情(待入库)', true);
        await page.locator('.el-dialog', { hasText: '生产任务单详情' }).waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(1500);
    } },
    { line: 3, do: async () => {
        await punch(page, '发合格证、条码、标签、配件及包装');
    } },
    { line: 4, do: async () => {
        await page.keyboard.press('Escape');
        await page.waitForTimeout(700);
        const row = page.locator('.el-table__row', { hasText: 'test1' }).first();
        await clickDemo(page, row.locator('.el-button', { hasText: '提交入库申请' }), 1200, '提交入库申请', true);
        await page.locator('.el-message-box').waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(900);
        await clickDemo(page, page.locator('.el-message-box__btns .el-button--primary'), 900, '确认提交入库申请');
        await page.locator('.el-message', { hasText: '已提交入库申请' }).first().waitFor({ timeout: 6000 });
    } },
  ]);
}

// ---------- 第七幕：仓储管理员·成品入库与库存核对 ----------
async function act7(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act7', [
    { line: null, hold: 1200 },
    { line: 0, do: async () => {
        await clickDemo(page, page.locator('.message-button'), 1500, '消息邮箱', true);
        await page.locator('.message-dialog').waitFor({ state: 'visible', timeout: 5000 });
        await page.locator('.message-card', { hasText: '待确认成品入库' }).first().waitFor({ timeout: 5000 });
        await page.waitForTimeout(600);
    } },
    { line: 1, do: async () => {
        await clickDemo(page, page.locator('.message-card.unread', { hasText: '待确认成品入库' }).first(), 1500, '点击成品入库消息', true);
        await page.locator('.el-table__row').first().waitFor({ timeout: 8000 });
        await page.waitForTimeout(600);
        await clickDemo(page, page.locator('.el-table__row').first().locator('.el-button', { hasText: '确认入库' }), 1200, '确认入库', true);
        await page.locator('.el-message-box').waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(1000);
        await clickDemo(page, page.locator('.el-message-box__btns .el-button--primary'), 800, '确认入库');
        await page.locator('.el-message', { hasText: '已确认入库' }).first().waitFor({ timeout: 6000 });
    } },
    { line: 2, do: async () => {
        await gotoMenu(page, '成品管理', 1800, false);
        await page.locator('.el-table__row').first().waitFor({ timeout: 8000 });
        await typeDemo(page, page.getByPlaceholder('请输入成品名称'), 'test1', 700);
        await clickDemo(page, page.getByRole('button', { name: '查询' }), 1600, '查询', true);
        await page.locator('.el-table__row', { hasText: 'test1' }).first().waitFor({ timeout: 8000 });
        await page.evaluate(() => window.__demoMove(1300, 420));
        await page.waitForTimeout(1500);
    } },
    { line: 3, do: async () => {
        await gotoMenu(page, '物料管理', 1800, false);
        await page.locator('.el-table__row').first().waitFor({ timeout: 8000 });
        await typeDemo(page, page.getByPlaceholder('请输入物料名称'), '测试物料1', 700);
        await clickDemo(page, page.getByRole('button', { name: '查询' }), 1600, '查询', true);
        await page.locator('.el-table__row', { hasText: '测试物料1' }).first().waitFor({ timeout: 8000 });
        await page.evaluate(() => window.__demoMove(1300, 420));
        await page.waitForTimeout(1500);
    } },
    { line: 4, do: async () => {
        await page.evaluate(() => window.__demoMove(640, 360));
        await page.waitForTimeout(1800);
    } },
  ]);
}

// ---------- 主流程 ----------
(async () => {
  // 防串集：清掉上一集的点击/锚点记录与原始录像，缺失幕不会被旧素材静默顶替
  cleanupBeforeRecord(['act1', 'act2', 'act3', 'act4', 'act5', 'act6', 'act7']);
  const browser = await chromium.launch();

  const production = await apiLogin('production_admin');
  const warehouse = await apiLogin('warehouse_admin');

  // 每幕独立上下文独立 webm，幕间由过场卡衔接
  let s = await openScene(browser, 'act1', production);
  await act1(s.page);
  await s.ctx.close();

  s = await openScene(browser, 'act2', production);
  await act2(s.page);
  await s.ctx.close();

  s = await openScene(browser, 'act3', production);
  await act3(s.page);
  await s.ctx.close();

  s = await openScene(browser, 'act4', warehouse);
  await act4(s.page);
  await s.ctx.close();

  s = await openScene(browser, 'act5', production);
  await act5(s.page);
  await s.ctx.close();

  s = await openScene(browser, 'act6', production);
  await act6(s.page);
  await s.ctx.close();

  s = await openScene(browser, 'act7', warehouse);
  await act7(s.page);
  await s.ctx.close();

  await browser.close();
  saveArtifacts();
  console.log('REC_ALL_OK');
})().catch(e => { console.error(e); process.exit(1); });
