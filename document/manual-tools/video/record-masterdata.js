// 录制 WMS 视频教程第 7 集：主数据维护全流程五幕（仓储建档→采购供应商与进价→销售售价→生产 BOM→仓储只读对照）
// 用法：WMS_PWD=<密码> node record-masterdata.js
// 前置：后端 8080（教程库）/ 前端 5173 运行中；先跑 node build.js tts narration-masterdata.json
const { chromium } = require('playwright');
const {
  BASE, apiLogin, openScene, clickDemo, typeDemo, gotoMenu,
  runBeats, cleanupBeforeRecord, saveArtifacts,
} = require('./record-common');

// 弹窗内按 label 精确定位表单项（el-form-item 的 label 文本全等匹配）
const formItem = (page, label) =>
  page.locator(`.el-dialog:visible .el-form-item:has(.el-form-item__label:text-is("${label}"))`);

// 物料建档公共流程：名称/种类/规格(可选)/绑定供应商
async function fillMaterial(page, f) {
  await typeDemo(page, formItem(page, '物料名称').locator('input').first(), f.name, 700);
  await typeDemo(page, formItem(page, '物料种类').locator('input').first(), f.cat, 700);
  if (f.spec) await typeDemo(page, formItem(page, '规格').locator('input').first(), f.spec, 600);
  await clickDemo(page, formItem(page, '供应商').locator('.el-select'), 900, '打开供应商下拉');
  await clickDemo(page, page.locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: '德州旺旺公司' }).first(), 700, '选德州旺旺公司');
  await clickDemo(page, page.locator('.el-dialog:visible').getByRole('button', { name: '确认' }), 800, '确认保存', true);
  await page.locator('.el-message', { hasText: '新增成功' }).first().waitFor({ timeout: 5000 });
}

// ---------- 第一幕：仓储管理员物料与成品建档 ----------
async function act1(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act1', [
    { line: null, hold: 1400 },
    { line: 0, do: async () => {
        await gotoMenu(page, '物料管理', 2200, true);
        await page.locator('.el-table__row').first().waitFor({ timeout: 8000 });
        await clickDemo(page, page.getByRole('button', { name: '新增物料' }), 1400, '新增物料', true);
        await page.locator('.el-dialog:visible', { hasText: '新增物料' }).waitFor({ state: 'visible', timeout: 5000 });
        await fillMaterial(page, { name: '测试垫圈', cat: '辅料' });
    } },
    { line: 1, do: async () => {
        const row = page.locator('.el-table__row', { hasText: '测试垫圈' }).first();
        await row.waitFor({ timeout: 8000 });
        await page.waitForTimeout(400);
        await clickDemo(page, row.locator('.el-button', { hasText: '删除' }), 1200, '删除临时物料', true);
        await page.locator('.el-message-box').waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(900);
        await clickDemo(page, page.locator('.el-message-box__btns .el-button--primary'), 800, '确定删除');
        await page.locator('.el-message', { hasText: '删除成功' }).first().waitFor({ timeout: 5000 });
    } },
    { line: 2, do: async () => {
        await clickDemo(page, page.getByRole('button', { name: '新增物料' }), 1400, '新增物料', true);
        await page.locator('.el-dialog:visible', { hasText: '新增物料' }).waitFor({ state: 'visible', timeout: 5000 });
        await fillMaterial(page, { name: '不锈钢法兰盘', cat: '紧固件', spec: 'DN50' });
    } },
    { line: 3 }, // 「名称+规格唯一」解说（画面停留列表新行）
    { line: 4, do: async () => {
        await gotoMenu(page, '成品管理', 2000, true);
        await page.locator('.el-table__row').first().waitFor({ timeout: 8000 });
        await clickDemo(page, page.getByRole('button', { name: '新增成品' }), 1400, '新增成品', true);
        await page.locator('.el-dialog:visible', { hasText: '新增成品' }).waitFor({ state: 'visible', timeout: 5000 });
        await typeDemo(page, formItem(page, '成品名称').locator('input').first(), '数控装配机 A1', 800);
        await typeDemo(page, formItem(page, '单位').locator('input').first(), '台', 600);
        await clickDemo(page, page.locator('.el-dialog:visible').getByRole('button', { name: '确认' }), 800, '确认保存', true);
        await page.locator('.el-message', { hasText: '新增成功' }).first().waitFor({ timeout: 5000 });
    } },
    { line: 5 }, // 成品建档收尾解说
  ]);
}

// ---------- 第二幕：采购管理员供应商与进价 ----------
async function act2(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act2', [
    { line: null, hold: 1200 },
    { line: 0, do: async () => {
        await gotoMenu(page, '供应商管理', 2200, true);
        await page.locator('.el-table__row').first().waitFor({ timeout: 8000 });
        await clickDemo(page, page.getByRole('button', { name: '新增供应商' }), 1400, '新增供应商', true);
        await page.locator('.el-dialog:visible', { hasText: '新增供应商' }).waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(500);
    } },
    { line: 1, do: async () => {
        const D = page.locator('.el-dialog:visible');
        await typeDemo(page, D.locator('.el-form-item').first().locator('input').first(), '华信五金供应站', 800);
        await typeDemo(page, D.getByPlaceholder('姓名'), '赵工', 700);
        await typeDemo(page, D.getByPlaceholder('联系电话'), '13800001111', 700);
        await clickDemo(page, D.getByRole('button', { name: '确认' }), 800, '确认保存', true);
        await page.locator('.el-message', { hasText: '新增成功' }).first().waitFor({ timeout: 5000 });
    } },
    { line: 2, do: async () => {
        await gotoMenu(page, '物料管理', 2000, true);
        await page.locator('.el-table__row').first().waitFor({ timeout: 8000 });
        await typeDemo(page, page.getByPlaceholder('请输入物料名称'), '不锈钢法兰盘', 700);
        await clickDemo(page, page.getByRole('button', { name: '查询' }), 1800, '查询', true);
        const row = page.locator('.el-table__row', { hasText: '不锈钢法兰盘' }).first();
        await row.waitFor({ timeout: 8000 });
        await page.waitForTimeout(400);
        await clickDemo(page, row.locator('.el-button', { hasText: '进价编辑' }), 1400, '进价编辑', true);
        await page.locator('.el-dialog:visible', { hasText: '进价' }).waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(500);
    } },
    { line: 3, do: async () => {
        await typeDemo(page, page.locator('.el-dialog:visible .el-input-number input').first(), '45.5', 900);
        await clickDemo(page, page.locator('.el-dialog:visible').getByRole('button', { name: '确认' }), 800, '确认修改', true);
        await page.locator('.el-message', { hasText: '修改成功' }).first().waitFor({ timeout: 5000 });
    } },
    { line: 4 }, // 进价列仅采购可见、进货回写解说（画面停留列表）
  ]);
}

// ---------- 第三幕：销售管理员标准售价 ----------
async function act3(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act3', [
    { line: null, hold: 1200 },
    { line: 0, do: async () => {
        await gotoMenu(page, '成品管理', 2200, true);
        await page.locator('.el-table__row').first().waitFor({ timeout: 8000 });
    } },
    { line: 1, do: async () => {
        await typeDemo(page, page.getByPlaceholder('请输入成品名称'), '数控装配机 A1', 700);
        await clickDemo(page, page.getByRole('button', { name: '查询' }), 1800, '查询', true);
        const row = page.locator('.el-table__row', { hasText: '数控装配机 A1' }).first();
        await row.waitFor({ timeout: 8000 });
        await page.waitForTimeout(400);
        await clickDemo(page, row.locator('.el-button', { hasText: '售价编辑' }), 1400, '售价编辑', true);
        await page.locator('.el-dialog:visible', { hasText: '售价' }).waitFor({ state: 'visible', timeout: 5000 });
        await typeDemo(page, page.locator('.el-dialog:visible .el-input-number input').first(), '88000', 1000);
        await clickDemo(page, page.locator('.el-dialog:visible').getByRole('button', { name: '确认' }), 800, '确认修改', true);
        await page.locator('.el-message', { hasText: '修改成功' }).first().waitFor({ timeout: 5000 });
    } },
    { line: 2 }, // 标准售价列更新解说（画面停留列表）
    { line: 3 }, // 价格权限分离收尾解说
  ]);
}

// ---------- 第四幕：生产管理员 BOM 建档 ----------
async function act4(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act4', [
    { line: null, hold: 1200 },
    { line: 0, do: async () => {
        await gotoMenu(page, 'BOM 管理', 2200, true);
        await page.locator('.el-table__row').first().waitFor({ timeout: 8000 });
        await clickDemo(page, page.getByRole('button', { name: '新建 BOM' }), 1400, '新建 BOM', true);
        await page.locator('.el-dialog:visible', { hasText: '新建 BOM' }).waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(600);
    } },
    { line: 1, do: async () => {
        await typeDemo(page, page.getByPlaceholder('如 PTO153', { exact: false }).first(), '数控装配机 A1', 900);
        await typeDemo(page, page.getByPlaceholder('如 台', { exact: false }).first(), '台', 700);
        // 明细首行「关联物料」：placeholder 有 mustache bug 的组件名称输入框不可用，直接定位行内 select
        const firstRow = page.locator('.el-dialog:visible .el-table__row').first();
        await clickDemo(page, firstRow.locator('.el-select'), 1100, '打开关联物料下拉', true);
        await clickDemo(page, page.locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: '不锈钢法兰盘' }).first(), 1200, '选不锈钢法兰盘');
        await page.waitForTimeout(600); // 规格材质自动带出
    } },
    { line: 2, do: async () => {
        const firstRow = page.locator('.el-dialog:visible .el-table__row').first();
        await typeDemo(page, firstRow.locator('.el-input-number input').first(), '4', 900);
        await clickDemo(page, page.getByRole('button', { name: '添加明细行' }), 1100, '添加明细行', true);
        const secondRow = page.locator('.el-dialog:visible .el-table__row').nth(1);
        await secondRow.waitFor({ timeout: 5000 });
        await typeDemo(page, secondRow.locator('.el-input input').first(), '控制主板', 900);
        await typeDemo(page, secondRow.locator('.el-input-number input').first(), '1', 800);
    } },
    { line: 3, do: async () => {
        await clickDemo(page, page.locator('.el-dialog:visible').getByRole('button', { name: '确认' }), 800, '确认保存', true);
        await page.locator('.el-message', { hasText: '新增成功' }).first().waitFor({ timeout: 5000 });
    } },
    { line: 4 }, // 缺料待采购解说（画面停留 BOM 列表新行）
  ]);
}

// ---------- 第五幕：仓储管理员 BOM 只读对照 ----------
async function act5(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act5', [
    { line: null, hold: 1200 },
    { line: 0, do: async () => {
        await gotoMenu(page, 'BOM 管理', 2200, true);
        await page.locator('.el-table__row').first().waitFor({ timeout: 8000 });
        await page.waitForTimeout(400);
        // 仓储视角写操作禁用：悬停展示禁用态（clickDemo 的 actionability 会拒禁用按钮）
        const btn = page.getByRole('button', { name: '新建 BOM' });
        const b = await btn.boundingBox();
        if (b) { await page.evaluate(([x, y]) => window.__demoMove(x, y), [b.x + b.width / 2, b.y + b.height / 2]); }
        await page.waitForTimeout(1800);
    } },
    { line: 1 }, // 写操作禁用、只读解说
    { line: 2, do: async () => {
        const row = page.locator('.el-table__row', { hasText: '数控装配机 A1' }).first();
        await clickDemo(page, row.locator('.el-button', { hasText: '详情' }), 1500, '查看详情', true);
        await page.locator('.el-drawer:visible', { hasText: 'BOM 详情' }).waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(2000); // 成品/组件/用量一览
        await page.keyboard.press('Escape');
        await page.locator('.el-drawer:visible', { hasText: 'BOM 详情' }).waitFor({ state: 'hidden', timeout: 5000 });
        await page.waitForTimeout(500);
    } },
    { line: 3 }, // 四部门协作收尾解说（画面停留 BOM 列表）
  ]);
}

// ---------- 主流程 ----------
(async () => {
  // 防串集：清掉上一集的点击/锚点记录与原始录像，缺失幕不会被旧素材静默顶替
  cleanupBeforeRecord(['act1', 'act2', 'act3', 'act4', 'act5']);
  const browser = await chromium.launch();

  const warehouse = await apiLogin('warehouse_admin');
  const purchase = await apiLogin('purchase_admin');
  const sales = await apiLogin('sales_admin');
  const production = await apiLogin('production_admin');

  // 每幕独立上下文独立 webm，幕间由过场卡衔接
  let s = await openScene(browser, 'act1', warehouse);
  await act1(s.page);
  await s.ctx.close();

  s = await openScene(browser, 'act2', purchase);
  await act2(s.page);
  await s.ctx.close();

  s = await openScene(browser, 'act3', sales);
  await act3(s.page);
  await s.ctx.close();

  s = await openScene(browser, 'act4', production);
  await act4(s.page);
  await s.ctx.close();

  s = await openScene(browser, 'act5', warehouse);
  await act5(s.page);
  await s.ctx.close();

  await browser.close();
  saveArtifacts();
  console.log('REC_ALL_OK');
})().catch(e => { console.error(e); process.exit(1); });
