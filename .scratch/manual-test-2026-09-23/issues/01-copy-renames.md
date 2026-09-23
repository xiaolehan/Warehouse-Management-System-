# 01 · 五处文案/检索改名（商品→成品/物料）

Type: task
Status: ready-for-human
Blocked by: 无

用户手测（2026-09-23）提出 5 处文案口径统一：生产/销售/销售退货/预警中心的「商品」措辞改为按语境的「成品」或「物料」，检索同步。纯前端改动（后端检索参数已支持）。

## 改动清单

### ① 生产入库（ProductionView.vue）
- 表头「商品名称」→「成品名称」（goodsName 列）。
- 检索「入库单号」→「成品名称」：搜索项 label/placeholder 改，loadList 参数 `productionNo` → `goodsName`（ProductionQueryDTO 已有 goodsName like 过滤，ProductionService.java:87-88）。
- 新建弹窗「商品名称」label →「成品名称」（D67 生产入库只选成品，语境为成品）。

### ② 物料管理（GoodsView.vue，isProduct=false 分支）
- 检索「产品名称」→「成品名称」（productName 搜索项，含 placeholder）。
- 表头「产品名称」→「成品名称」（productName 列=归属成品列，用户已确认语义：该物料用于哪个成品）。
- 编辑弹窗「产品名称」label →「成品名称」。

### ③ 销售出库确认（SalesView.vue）
- 表头「出库商品」→「出库成品」（goodsSummary 列）。
- 检索「出库商品」→「出库成品」（label + placeholder，keywords 参数不变）。

### ④ 销售退货出库确认（SalesReturnView.vue）
- 表头「退回商品」→「退回成品」。
- 检索「退回商品」→「退回成品」（keywords 参数不变）。

### ⑤ 预警中心（StockWarningView.vue）
- 表头「商品名称」→「物料名称」（goodsName 列）。
- 检索「商品名称」→「物料名称」（goodsName 参数不变）。

## 验收
- 五个页面表头/检索文案与上述一致；生产入库按成品名称可检索；物料管理「成品名称」列显示归属成品。
- `npm run build` 通过；手工页面过一遍无残留「商品」措辞（这五个界面范围内）。
