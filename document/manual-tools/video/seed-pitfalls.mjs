// 避坑指南集种子脚本：reset 教程库后跑（WMS_PWD=<密码> node seed-pitfalls.mjs），用 API 造幕1–幕5 所需数据
// 前置：后端 8080（教程库）在线；密码仅经 WMS_PWD 环境变量注入，不落盘
// 产物：挂默认供应商的物料 + 采购申请（已认领+到货备注）+ 销售单 A/B/C/D + BOM 导入 xlsx
const BASE = 'http://localhost:8080/api';
const PWD = process.env.WMS_PWD;
if (!PWD) throw new Error('WMS_PWD 环境变量未设置');

const login = async (username) => {
  const r = await fetch(`${BASE}/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: PWD }),
  });
  const j = await r.json();
  if (j.code !== 200) throw new Error(`login ${username} fail: ${JSON.stringify(j).slice(0, 300)}`);
  return j.data.token;
};

const mkApi = (token) => async (method, path, body) => {
  const r = await fetch(`${BASE}${path}`, {
    method, headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: body ? JSON.stringify(body) : undefined,
  });
  const j = await r.json().catch(() => ({}));
  if (j.code && j.code !== 200) throw new Error(`${method} ${path} biz-fail: ${JSON.stringify(j).slice(0, 300)}`);
  return j;
};

const main = async () => {
  const Wp = mkApi(await login('warehouse_admin'));
  const P = mkApi(await login('purchase_admin'));
  const S = mkApi(await login('sales_admin'));
  const Pr = mkApi(await login('production_admin')); // 采购申请由生产部门识别缺货创建（D109 链路）

  // ---------- 公共：按名查物料/成品（仓储 token 全库可读） ----------
  const findGoods = async (name) => {
    const j = await Wp('GET', `/base/goods/page?pageNo=1&pageSize=100&goodsName=${encodeURIComponent(name)}`);
    return (j.data?.records || []).find((x) => x.goodsName === name) || null;
  };

  // ---------- 幕5：BOM 导入 xlsx（第1行表头、第2行空、第3行起数据） ----------
  const { execFileSync } = await import('node:child_process');
  execFileSync('/tmp/ttsvenv/bin/python3', ['-c', `
import openpyxl
wb = openpyxl.Workbook()
ws = wb.active
ws.append(["序号","图片","组件名称","规格","数量","材质","备注"])
ws.append([None]*7)
ws.append([1, None, "不锈钢法兰盘 DN50", "DN50", 4, "304不锈钢", "外购"])
ws.append([2, None, "控制主板 V2.1", "V2.1", 1, "-", "外购"])
wb.save("/tmp/wms-video/bom-import.xlsx")
print("xlsx saved")
  `], { stdio: 'inherit' });
  console.log('SEED xlsx OK');

  // ---------- 幕1：物料挂默认供应商 + 采购申请认领带备注 ----------
  let mica = await findGoods('云母垫片 M6');
  if (!mica) {
    await Wp('POST', '/base/goods', {
      goodsName: '云母垫片 M6', type: 'material', unit: '个', spec: 'M6',
      category: '辅料', stock: 0, status: 1, supplierId: 1, // 系统默认供应商锚点（幕2 演示起点）
    });
    mica = await findGoods('云母垫片 M6');
  }
  if (!mica) throw new Error('seed: 云母垫片 M6 建档失败');
  console.log('SEED 物料 ok id=', mica.id);

  await Pr('POST', '/business/purchase-requests', {
    remark: '避坑演示申请',
    details: [{ goodsId: mica.id, quantity: 100 }],
  });
  const prPage = await P('GET', '/business/purchase-requests/page?pageNo=1&pageSize=50');
  const pr = (prPage.data?.records || []).find((x) => x.remark === '避坑演示申请');
  if (!pr) throw new Error('seed: 采购申请未找到');
  const prd = await P('GET', `/business/purchase-requests/${pr.id}`);
  const detailId = prd.data?.details?.[0]?.id;
  if (!detailId) throw new Error('seed: 明细 id 未找到');
  await P('PUT', `/business/purchase-requests/${pr.id}/process`, {
    items: [{ detailId, expectedArrivalTime: '2026-10-15T10:00:00', arrivalRemark: '华信标准件/汽运' }],
  });
  console.log('SEED 采购申请 ok id=', pr.id);

  // ---------- 幕2/3/4：销售单 A/B/C/D ----------
  const smc = await findGoods('SMC105');
  let ncA1 = await findGoods('数控装配机 A1');
  if (!ncA1) {
    // A1 仅存在于主流程演示库（reset 后缺失），教程库重建零库存成品——幕3 正需要缺货态
    await Wp('POST', '/base/goods', { goodsName: '数控装配机 A1', type: 'product', unit: '台', stock: 0, status: 1 });
    ncA1 = await findGoods('数控装配机 A1');
  }
  const pto = await findGoods('PTO153');
  for (const [k, g] of Object.entries({ smc, ncA1, pto })) {
    if (!g) throw new Error(`seed: ${k} 未找到（reset 后主数据缺失）`);
  }
  // 单价=库内 sale_price（分页 DTO 字段名不可靠，偏离校验正对着 sale_price 比对，走兜底价必触发审批）
  const price = (g) => Number(execFileSync('mysql', ['-uwms_user', '-pwms_pass', '-N', '-B',
    'warehouse_management_tutorial', '-e', `SELECT IFNULL(sale_price,0) FROM base_goods WHERE id=${g.id}`])
    .toString().trim()) || 16000;

  // 幕2 单 A：SMC105×1 + 数控装配机 A1×1（A1 零库存 → 确认出库被拦）
  await S('POST', '/business/sales', {
    customerName: '宏远机械',
    items: [
      { goodsId: smc.id, quantity: 1, unitPrice: price(smc) },
      { goodsId: ncA1.id, quantity: 1, unitPrice: price(ncA1) },
    ],
  });
  // 幕3 单 B（当天待出库，录制中删除）+ 单 C（出库成功 → 作废演示）
  // 同 body 背靠背会撞防重窗口（intervalMs=1800，key 含参数指纹），单间必须留间隔
  await S('POST', '/business/sales', { customerName: '宏远机械', items: [{ goodsId: smc.id, quantity: 1, unitPrice: price(smc) }] });
  await new Promise((r) => setTimeout(r, 2000));
  await S('POST', '/business/sales', { customerName: '宏远机械', items: [{ goodsId: smc.id, quantity: 1, unitPrice: price(smc) }] });
  // 幕4 单 D：SMC105×1 + PTO153×1（行终止演示）
  await S('POST', '/business/sales', {
    customerName: '恒达工贸',
    items: [
      { goodsId: smc.id, quantity: 1, unitPrice: price(smc) },
      { goodsId: pto.id, quantity: 1, unitPrice: price(pto) },
    ],
  });

  // 单 C 推进：仓储确认出库（成功），供作废演示
  const salesPage = await S('GET', '/business/sales/page?pageNo=1&pageSize=50');
  const rows = salesPage.data?.records || [];
  const withSmcOnly = rows.filter((x) => x.customerName === '宏远机械' && x.confirmStatus === 1);
  if (withSmcOnly.length < 2) throw new Error(`seed: 宏远机械待出库单不足 2 张（实际 ${withSmcOnly.length}）`);
  const orderC = withSmcOnly[0];
  await Wp('PUT', `/business/sales/${orderC.id}/confirm`);
  console.log('SEED 销售单 ok：A/B 待出库、C 已出库(id=', orderC.id + ')、D 待出库');

  // ---------- 幕5：历史「已冲抵」行（红冲已停用无 API 路径，直插教程库伪造历史单） ----------
  const salesNo = 'SAL261005090000001';
  execFileSync('mysql', ['-uwms_user', '-pwms_pass', 'warehouse_management_tutorial', '-e',
    `INSERT INTO biz_sales (sales_no, total_quantity, total_amount, operator_name, operation_time, biz_status, confirm_status, customer_name, tax_included, create_time, update_time, is_deleted)
     VALUES ('${salesNo}', 1, 16000.00, '历史导入', '2026-09-28 09:30:00', 3, 2, '蓝天铸造厂', 1, '2026-09-28 09:30:00', '2026-09-28 09:30:00', 0);`]);
  console.log('SEED 已冲抵历史行 ok:', salesNo);

  console.log('SEED_ALL_OK');
};

main().catch((e) => { console.error(e.message); process.exit(1); });
