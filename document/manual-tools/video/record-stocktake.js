// 录制 WMS 视频教程第 6 集：库存盘点全流程五幕（建单指派→员工盲盘→审核生效→核对库存→回看档案）
// 用法：WMS_PWD=<密码> node record-stocktake.js
// 前置：后端 8080（教程库）/ 前端 5173 运行中；先跑 node build.js tts narration-stocktake.json
const { chromium } = require('playwright');
const {
  BASE, apiLogin, openScene, clickDemo, typeDemo, gotoMenu,
  runBeats, cleanupBeforeRecord, saveArtifacts,
} = require('./record-common');

// ---------- 第一幕：仓储管理员创建盘点单并指派负责人 ----------
async function act1(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act1', [
    { line: null, hold: 1400 },
    { line: 0, do: async () => {
        await gotoMenu(page, '库存盘点', 2200, true);
        await page.locator('.el-table__empty-block, .el-table__row').first().waitFor({ timeout: 8000 });
        await clickDemo(page, page.getByRole('button', { name: '新建盘点单' }), 1600, '新建盘点单', true);
        await page.locator('.el-dialog:visible', { hasText: '新建盘点单' }).waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(900); // 商品选择表加载
    } },
    { line: 1, do: async () => {
        // 不用过滤框：过滤重建表格数据会丢掉未勾 reserve-selection 的已选行，直接整表勾选
        await clickDemo(page, page.locator('.el-dialog:visible .el-table').nth(0).locator('.el-table__row', { hasText: 'SMC105' }).locator('.el-checkbox'), 900, '勾选SMC105');
        await page.waitForTimeout(800); // 负责人分配块出现
    } },
    { line: 2, do: async () => {
        await clickDemo(page, page.locator('.el-dialog:visible .el-table').nth(0).locator('.el-table__row', { hasText: '轴套 01' }).locator('.el-checkbox'), 900, '勾选轴套01');
        await page.waitForTimeout(800);
    } },
    { line: 3, do: async () => {
        const pick = name => page.locator('.el-dialog:visible .el-table').nth(1).locator('.el-table__row', { hasText: name }).locator('.el-select');
        await clickDemo(page, pick('SMC105'), 1000, 'SMC105选负责人', true);
        await clickDemo(page, page.locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: '仓储员工' }).first(), 900, '仓储员工');
        await clickDemo(page, pick('轴套 01'), 1000, '轴套01选负责人', true);
        await clickDemo(page, page.locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: '仓储员工' }).first(), 900, '仓储员工');
        await typeDemo(page, page.getByPlaceholder('备注'), '月末例行盘点', 700);
    } },
    { line: 4, do: async () => {
        await clickDemo(page, page.getByRole('button', { name: /创建/ }), 800, '创建盘点单', true);
        await page.locator('.el-message', { hasText: '盘点单已创建' }).first().waitFor({ timeout: 5000 });
    } },
    { line: 5 }, // 指派消息解说（画面停留列表）
  ]);
}

// ---------- 第二幕：仓储员工盲盘录入实盘数 ----------
async function act2(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act2', [
    { line: null, hold: 1200 },
    { line: 0, do: async () => {
        await clickDemo(page, page.locator('.message-button'), 1500, '消息邮箱', true);
        await page.locator('.message-dialog').waitFor({ state: 'visible', timeout: 5000 });
    } },
    { line: 1, do: async () => {
        await page.locator('.message-card', { hasText: '盘点任务指派' }).first().waitFor({ timeout: 5000 });
        await clickDemo(page, page.locator('.message-card', { hasText: '盘点任务指派' }).first(), 1500, '点击指派消息', true);
        await page.locator('.el-dialog:visible', { hasText: '盘点单' }).waitFor({ state: 'visible', timeout: 8000 });
        await page.locator('.el-alert', { hasText: '盲盘模式' }).waitFor({ state: 'visible', timeout: 5000 });
    } },
    { line: 2 }, // 盲盘模式解说（画面停留详情弹窗）
    { line: 3, do: async () => {
        const row = page.locator('.el-dialog:visible .el-table__row', { hasText: 'SMC105' }).first();
        await typeDemo(page, row.locator('.el-input-number input'), '3', 800);
    } },
    { line: 4, do: async () => {
        const row = page.locator('.el-dialog:visible .el-table__row', { hasText: '轴套 01' }).first();
        await typeDemo(page, row.locator('.el-input-number input'), '1', 800);
    } },
    { line: 5, do: async () => {
        await clickDemo(page, page.locator('.el-dialog:visible').getByRole('button', { name: '保存录入' }), 800, '保存录入', true);
        await page.locator('.el-message', { hasText: '已保存' }).first().waitFor({ timeout: 5000 });
        await page.waitForTimeout(400);
        await clickDemo(page, page.locator('.el-dialog:visible').getByRole('button', { name: '提交审核' }), 1300, '提交审核', true);
        await page.locator('.el-message-box').waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(900);
        await clickDemo(page, page.locator('.el-message-box__btns .el-button--primary'), 800, '确定');
        await page.locator('.el-message', { hasText: '已提交' }).first().waitFor({ timeout: 5000 });
    } },
  ]);
}

// ---------- 第三幕：仓储管理员审核生效，差异调整库存 ----------
async function act3(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act3', [
    { line: null, hold: 1200 },
    { line: 0, do: async () => {
        await clickDemo(page, page.locator('.message-button'), 1500, '消息邮箱', true);
        await page.locator('.message-dialog').waitFor({ state: 'visible', timeout: 5000 });
    } },
    { line: 1, do: async () => {
        // 待审核消息的 jumpPath 是 /business/stocktake（不带 stocktakeId 深链）→ 点击后落列表页，需手动开详情
        await page.locator('.message-card', { hasText: '盘点单待审核' }).first().waitFor({ timeout: 5000 });
        await clickDemo(page, page.locator('.message-card', { hasText: '盘点单待审核' }).first(), 1500, '点击待审核消息', true);
        await page.locator('.el-table__row').first().waitFor({ timeout: 8000 });
        await page.waitForTimeout(500);
        await clickDemo(page, page.locator('.el-table__row').first().locator('.el-button', { hasText: '详情' }), 1600, '查看详情', true);
        await page.locator('.el-dialog:visible', { hasText: '盘点单' }).waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(800); // 管理员视角：账面快照列可见
    } },
    { line: 2 }, // 管理员视角解说（账面快照与预估差异）
    { line: 3, do: async () => {
        await clickDemo(page, page.locator('.el-dialog:visible').getByRole('button', { name: '审核生效' }), 1300, '审核生效', true);
        await page.locator('.el-message-box').waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(900);
        await clickDemo(page, page.locator('.el-message-box__btns .el-button--primary'), 900, '确认生效');
        await page.locator('.el-message', { hasText: '盘点已生效' }).first().waitFor({ timeout: 5000 });
    } },
    { line: 4 }, // 生效结果解说（详情刷新为已生效视角：生效时账面/差异列）
  ]);
}

// ---------- 第四幕：核对成品库存 4→3 ----------
async function act4(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act4', [
    { line: null, hold: 1200 },
    { line: 0, do: async () => {
        await gotoMenu(page, '成品管理', 1800, true);
        await page.locator('.el-table__row').first().waitFor({ timeout: 8000 });
        await typeDemo(page, page.getByPlaceholder('请输入成品名称'), 'SMC105', 700);
        await clickDemo(page, page.getByRole('button', { name: '查询' }), 1800, '查询', true);
    } },
    { line: 1, do: async () => {
        const row = page.locator('.el-table__row', { hasText: 'SMC105' }).first();
        await row.waitFor({ timeout: 8000 });
        await page.evaluate(() => window.__demoMove(1300, 420)); // 光标移向库存列
        await page.waitForTimeout(1600);
    } },
    { line: 2 }, // 盘点不产生业务单据解说（画面停留在库存行）
  ]);
}

// ---------- 第五幕：回看盘点档案 ----------
async function act5(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act5', [
    { line: null, hold: 1200 },
    { line: 0, do: async () => {
        await gotoMenu(page, '库存盘点', 1800, true);
        await page.locator('.el-table__row').first().waitFor({ timeout: 8000 });
        await page.waitForTimeout(500);
        await clickDemo(page, page.locator('.el-table__row').first().locator('.el-button', { hasText: '详情' }), 1600, '查看详情', true);
        await page.locator('.el-dialog:visible', { hasText: '盘点单' }).waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(1600); // 生效时账面/差异列展示
    } },
    { line: 1 }, // 盘点档案信息解说
    { line: 2, do: async () => {
        await page.keyboard.press('Escape');
        await page.locator('.el-dialog:visible', { hasText: '盘点单' }).waitFor({ state: 'hidden', timeout: 5000 });
        await page.waitForTimeout(600); // 列表盈0/亏1/平1 汇总入镜
    } },
    { line: 3 }, // 收尾解说（画面停留在盘点列表）
  ]);
}

// ---------- 主流程 ----------
(async () => {
  // 防串集：清掉上一集的点击/锚点记录与原始录像，缺失幕不会被旧素材静默顶替
  cleanupBeforeRecord(['act1', 'act2', 'act3', 'act4', 'act5']);
  const browser = await chromium.launch();

  const admin = await apiLogin('warehouse_admin');
  const employee = await apiLogin('warehouse_employee');

  // 每幕独立上下文独立 webm，幕间由过场卡衔接
  let s = await openScene(browser, 'act1', admin);
  await act1(s.page);
  await s.ctx.close();

  s = await openScene(browser, 'act2', employee);
  await act2(s.page);
  await s.ctx.close();

  s = await openScene(browser, 'act3', admin);
  await act3(s.page);
  await s.ctx.close();

  s = await openScene(browser, 'act4', admin);
  await act4(s.page);
  await s.ctx.close();

  s = await openScene(browser, 'act5', admin);
  await act5(s.page);
  await s.ctx.close();

  await browser.close();
  saveArtifacts();
  console.log('REC_ALL_OK');
})().catch(e => { console.error(e); process.exit(1); });
