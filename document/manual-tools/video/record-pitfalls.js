// 录制 WMS 视频教程第 8 集：操作细节与避坑指南六幕（采购建档/到货备注 → 仓储匹配供应商 → 发货时机 → 删除/作废 → 行终止/已冲抵 → BOM 导入）
// 用法：WMS_PWD=<密码> node record-pitfalls.js
// 前置：后端 8080（教程库）/ 前端 5173 运行中；先跑 reset 教程库 + WMS_PWD=<密码> node seed-pitfalls.mjs + tts + gen-cards
const { chromium } = require('playwright');
const {
  BASE, apiLogin, openScene, moveTo, clickDemo, typeDemo, gotoMenu,
  runBeats, cleanupBeforeRecord, saveArtifacts,
} = require('./record-common');

const XLSX = '/tmp/wms-video/bom-import.xlsx';

// ---------- 第一幕：采购管理员（新供应商建档 + 到货备注斜杠契约） ----------
async function act1(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act1', [
    { line: null, hold: 1400 },
    { line: 0, do: async () => {
        await gotoMenu(page, '供应商管理', 2200, true);
        await page.locator('.el-table__row').first().waitFor({ timeout: 8000 });
        await clickDemo(page, page.getByRole('button', { name: '新增供应商' }), 1400, '新增供应商', true);
        await page.locator('.el-dialog:visible', { hasText: '新增供应商' }).waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(500);
        const D = page.locator('.el-dialog:visible');
        await typeDemo(page, D.locator('.el-form-item').first().locator('input').first(), '华信标准件', 800);
        await typeDemo(page, D.getByPlaceholder('姓名'), '赵工', 700);
        await typeDemo(page, D.getByPlaceholder('联系电话'), '13800001112', 700);
        await clickDemo(page, D.getByRole('button', { name: '确认' }), 800, '确认保存', true);
        await page.locator('.el-message', { hasText: '新增成功' }).first().waitFor({ timeout: 5000 });
    } },
    { line: 1, do: async () => {
        await gotoMenu(page, '采购申请', 2200, true);
        // 列表无备注列（列=单号/明细/状态/申请人/处理人/预计到货/申请时间），按唯一申请单首行定位
        const row = page.locator('.el-table__row').first();
        await row.waitFor({ timeout: 8000 });
        await clickDemo(page, row.locator('.el-button', { hasText: '修改到货计划' }), 1200, '打开到货计划', true);
        await page.locator('.el-dialog:visible', { hasText: '修改到货计划' }).waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(500);
        const D = page.locator('.el-dialog:visible');
        // 画面重点：到货备注输入框预填「华信标准件/汽运」——光标落框配解说
        await clickDemo(page, D.locator('.el-table__row').first().locator('.el-input__inner').last(), 700, '到货备注输入框');
        await page.waitForTimeout(900);
        // 弹窗保持打开，配合下一拍斜杠规则解说
    } },
    { line: 2 }, // 斜杠规则重点解说（画面=到货计划弹窗停留）
  ]);
}
// ---------- 第二幕：仓储管理员（物料匹配供应商） ----------
async function act2(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act2', [
    { line: null, hold: 1400 },
    { line: 0, do: async () => {
        await gotoMenu(page, '物料管理', 2200, true);
        const row = page.locator('.el-table__row', { hasText: '云母垫片' }).first();
        await row.waitFor({ timeout: 8000 });
        await clickDemo(page, row.locator('.el-button', { hasText: '匹配供应商' }), 1200, '匹配供应商', true);
        await page.locator('.el-message-box:visible').waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(1200); // messagebox 停留配解说
        await page.locator('.el-message-box__btns .el-button--primary').click();
    } },
    { line: 1, do: async () => {
        await page.locator('.el-message', { hasText: '匹配' }).first().waitFor({ timeout: 5000 });
        await page.waitForTimeout(900);
    } },
    { line: 2 }, // 匹配成功按钮消失收尾（画面=列表停留）
  ]);
}

// ---------- 第三幕：仓储管理员（发货时机——整单出库被拦） ----------
async function act3(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act3', [
    { line: null, hold: 1400 },
    { line: 0, do: async () => {
        await gotoMenu(page, '销售出库确认', 2200, true);
        const row = page.locator('.el-table__row', { hasText: '数控装配机' }).first();
        await row.waitFor({ timeout: 8000 });
        await page.waitForTimeout(600); // 缺货明细列停留
    } },
    { line: 1, do: async () => {
        await page.waitForTimeout(700); // 整单出库不拆单解说
    } },
    { line: 2, do: async () => {
        const row = page.locator('.el-table__row', { hasText: '数控装配机' }).first();
        await clickDemo(page, row.locator('.el-button', { hasText: '确认出库' }), 1000, '确认出库', true);
        await page.locator('.el-message-box:visible').waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(1000); // 缺货预告停留
        await page.locator('.el-message-box__btns .el-button--primary').click();
        await page.locator('.el-message', { hasText: '库存不足' }).first().waitFor({ timeout: 5000 });
        await page.waitForTimeout(900);
    } },
    { line: 3 }, // 正确做法解说（画面=列表停留）
  ]);
}
// ---------- 第四幕：销售管理员（删除 vs 作废） ----------
async function act4(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act4', [
    { line: null, hold: 1400 },
    { line: 0, do: async () => {
        await gotoMenu(page, '商品销售', 2200, true);
        await page.locator('.el-table__row', { hasText: '宏远机械' }).first().waitFor({ timeout: 8000 });
        // 删除目标：含「删除」按钮的宏远机械行（只会命中 A/B，不会命中 D 或已出库的 C）
        // 注意 :text-is 对换行缩进文本零容忍（实测匹配 0），必须用 hasText 子串
        const row = page.locator('.el-table__row', { hasText: '宏远机械' })
          .filter({ has: page.locator('.el-button', { hasText: '删除' }) }).first();
        await clickDemo(page, row.locator('.el-button', { hasText: '删除' }), 1000, '删除', true);
        await page.locator('.el-message-box:visible', { hasText: '删除' }).waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(900);
        await page.locator('.el-message-box__btns .el-button--primary').click();
        await page.locator('.el-message', { hasText: '删除成功' }).first().waitFor({ timeout: 5000 });
        await page.waitForTimeout(600);
    } },
    { line: 1, do: async () => {
        await page.waitForTimeout(700); // 已生效单只剩作废入口解说
    } },
    { line: 2, do: async () => {
        // 作废目标：唯一含「作废」按钮的行（C 已出库）
        const row = page.locator('.el-table__row', { hasText: '宏远机械' })
          .filter({ has: page.locator('.el-button', { hasText: '作废' }) }).first();
        await moveTo(page, row.locator('.el-button', { hasText: '作废' }), 700, '悬停作废按钮');
        await row.locator('.el-button', { hasText: '作废' }).click();
        await page.locator('.el-dialog:visible', { hasText: '作废单据' }).waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(600);
        const D = page.locator('.el-dialog:visible');
        await typeDemo(page, D.getByPlaceholder('请填写作废原因'), '开单信息有误，申请作废', 700);
        await clickDemo(page, D.getByRole('button', { name: '确认提交审批' }), 800, '确认提交审批', true);
        await page.locator('.el-message', { hasText: '已提交作废审批' }).first().waitFor({ timeout: 5000 });
        await page.waitForTimeout(700);
    } },
    { line: 3, do: async () => {
        await page.waitForTimeout(600);
        await moveTo(page, page.locator('.el-table__row', { hasText: '宏远机械' }).first()
          .locator('.el-tag', { hasText: '作废审批中' }).first(), 1100).catch(() => {}); // tag 未及时渲染不炸录制
        await page.waitForTimeout(700);
    } },
    { line: 4 }, // 口径总结（画面=列表停留）
  ]);
}
// ---------- 第五幕：销售管理员（行级终止 + 已冲抵） ----------
async function act5(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act5', [
    { line: 0, do: async () => {
        await gotoMenu(page, '商品销售', 2200, true);
        const row = page.locator('.el-table__row', { hasText: '恒达工贸' }).first();
        await row.waitFor({ timeout: 8000 });
        await clickDemo(page, row.locator('.el-button', { hasText: '终止' }), 1200, '终止', true);
        await page.locator('.el-dialog:visible', { hasText: '终止销售明细行' }).waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(500);
        const D = page.locator('.el-dialog:visible');
        await D.locator('.el-table__row').first().locator('.el-checkbox').first().click();
        await page.waitForTimeout(600);
        // placeholder 全文带后缀须正则；且两行各有一个同 placeholder 输入框（strict mode），必须限定首行
        await typeDemo(page, D.locator('.el-table__row').first().getByPlaceholder(/勾选后填写原因/), '客户取消该型号', 700);
        await clickDemo(page, D.getByRole('button', { name: '确认终止' }), 800, '确认终止', true);
        await page.locator('.el-message-box:visible').waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(800);
        await page.locator('.el-message-box__btns .el-button--primary').click();
        await page.locator('.el-message', { hasText: '已终止' }).first().waitFor({ timeout: 5000 });
        await page.waitForTimeout(800);
    } },
    { line: 1, do: async () => {
        // 已冲抵行 tooltip 演示
        const row = page.locator('.el-table__row', { hasText: '蓝天铸造厂' }).first();
        await row.waitFor({ timeout: 8000 });
        await moveTo(page, row.locator('.el-tag', { hasText: '已冲抵' }).first(), 1400, '已冲抵 tooltip');
        await page.waitForTimeout(800);
    } },
    { line: 2 }, // 已冲抵解说
    { line: 3 }, // 三种处理分清总结
  ]);
}

// ---------- 第六幕：生产管理员（BOM 导入） ----------
async function act6(page) {
  await page.goto(`${BASE}/home`, { waitUntil: 'networkidle' });
  await runBeats(page, 'act6', [
    { line: null, hold: 1400 },
    { line: 0, do: async () => {
        await gotoMenu(page, 'BOM 管理', 2200, true);
        await clickDemo(page, page.getByRole('button', { name: 'BOM导入' }), 1400, 'BOM导入', true);
        await page.locator('.el-dialog:visible', { hasText: '导入 BOM 明细' }).waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(500);
        const D = page.locator('.el-dialog:visible');
        await typeDemo(page, D.locator('.el-form-item').first().locator('input').first(), '数控装配机 B2', 800);
        await D.locator('input[type=file]').setInputFiles(XLSX);
        await page.locator('.el-message', { hasText: '导入成功' }).first().waitFor({ timeout: 15000 });
        await page.waitForTimeout(900);
        await D.getByRole('button', { name: '关闭' }).click({ timeout: 3000 }).catch(() => {});
    } },
    { line: 1, do: async () => {
        await page.waitForTimeout(700);
        const row = page.locator('.el-table__row', { hasText: '数控装配机 B2' }).first();
        await row.waitFor({ timeout: 8000 });
        await clickDemo(page, row.locator('.el-button', { hasText: '详情' }), 1000, 'BOM 详情', true);
        await page.locator('.el-drawer', { hasText: 'BOM 详情' }).waitFor({ state: 'visible', timeout: 5000 });
        await page.waitForTimeout(1200);
        await page.keyboard.press('Escape');
        await page.waitForTimeout(600);
    } },
    { line: 2 }, // 导入覆盖警示解说
    { line: 3 }, // 参考行解说
  ]);
}
// ---------- 主流程：教程库 seeds 就绪后一键录制六幕 ----------
(async () => {
  cleanupBeforeRecord(['act1', 'act2', 'act3', 'act4', 'act5', 'act6']);
  const browser = await chromium.launch();

  // apiLogin 结果对象（含 token/deptId 等）传给 openScene——传用户名串会注入空 token
  const users = {
    purchase: await apiLogin('purchase_admin'),
    warehouse: await apiLogin('warehouse_admin'),
    sales: await apiLogin('sales_admin'),
    production: await apiLogin('production_admin'),
  };
  const plan = [
    ['act1', users.purchase],
    ['act2', users.warehouse],
    ['act3', users.warehouse],
    ['act4', users.sales],
    ['act5', users.sales],
    ['act6', users.production],
  ];
  for (const [scene, user] of plan) {
    console.log(`== ${scene} ==`);
    const s = await openScene(browser, scene, user);
    await ({ act1, act2, act3, act4, act5, act6 }[scene])(s.page);
    await s.ctx.close();
  }

  await browser.close();
  saveArtifacts();
  console.log('REC_ALL_OK');
})().catch((e) => { console.error(e); process.exit(1); });
