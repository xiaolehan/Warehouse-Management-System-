# 04 · D131 进货单供应商行级化

Type: task
Status: ready-for-human
Blocked by: 无

用户定案（2026-09-22 grilling，第三轮手测问题 4，ADR-0018）：进货单供应商落**明细行**（detail.supplier_id 为权威），申请渠道在**到货提交**逐行选供应商，物料「最新供应商」口径改读行级；按供应商拆单方案被否（会连锁改 D120 批次语义，代价大于收益）。

**根因（已查实）**：采购申请确认入库自动生成的进货单（`createInternal`）从不填头级供应商（进货 85/86 实证 supplier_id=NULL）；D123 的「最新供应商」SQL 显式排除头级为空的单（BizPurchaseMapper.java:76）→ 回退绑定供应商+「默认」tag。申请 35 更暴露结构矛盾：两行物料来自胖牛+胖乐两个供应商，头级单字段装不下。

## 范围

### 数据库
- `biz_purchase_detail` 加 `supplier_id`（可空——存量单无值）。
- `biz_purchase_request_detail` 加 `supplier_id`（到货提交时定格，确认入库时复制进货单明细行）。
- db.sql 规范段 + 增量段（仅 DDL）；**存量回填不入 db.sql**（85/86 是本环境演示数据），本地一次性执行：
  - 进货 85：头级+全部 29 行 = 德州旺旺公司（supplier_id=2）；
  - 进货 86：O型圈大（goods 69）行=胖牛（9）、O型圈小（goods 70）行=胖乐责任公司（10），头级留空。

### 后端
- `BizPurchaseDetail` +supplierId；`BizPurchaseRequestDetail` +supplierId。
- **手动建单**（PurchaseService.create）：头级 supplierId 必填校验保留（D123），写入时统一填入所有明细行（前端交互不变，一单一供应商；不做行级覆盖）。
- **到货提交**（PurchaseRequestService.arrive）：`ReceiveItemDTO` 加 `supplierId` **必填**（@NotNull）；写入本批行 `biz_purchase_request_detail.supplier_id`（驳回/撤回重置行为对齐 unitPrice——保留还是清空跟随现有单价语义）。预填由前端做（见前端节）。
- **确认入库生成**（createInternal）：进货明细行 supplier_id 从申请行复制；**头级留空**（不做「多数供应商」派生）。
- **最新供应商口径**（GoodsService.fillLatestSuppliers + BizPurchaseMapper.latestValidSuppliers）：改读 `COALESCE(d.supplier_id, p.supplier_id)`，过滤条件同步改为该 COALESCE 非空（最近一张「记录了供应商」的已入库正常单）；Java 回退链：行级→头级→绑定+「默认」tag。
- 进货退货：退货行供应商展示从来源**明细行**带出（原为头级带出，D123 语义随行级化收窄；行空回退头级）。
- 进价回写/历史/统计/作废回冲：不涉供应商，不动。

### 前端
- 到货提交弹窗（PurchaseRequestView.vue:287-314）：行加「供应商」必选下拉（数据源 getSupplierOptionsAPI）；预填=物料绑定 supplier_id；绑定=系统默认供应商（id=1）时留空必选手选（首次采购未知物料，货源采购知晓——D109 备注流程已兜过底）。
- 进货单详情明细行加「供应商」列；进货单列表头级供应商列保留（手动单显示该值；内部单显示「—」，看行进详情）。
- 手动建单弹窗（PurchaseView.vue:164-169）不变。

### 测试
- 单测：行级写入（手动统一填行/内部按行）；最新供应商回退链（行级命中→头级命中→绑定默认）；到货缺供应商 400；驳回重提后供应商语义。
- E2E **复现用户场景正向验证**：建两行申请（两物料绑定不同供应商）→ 到货提交分别选胖牛/胖乐 → 确认入库生成一张两行进货单（行级供应商各自正确、头级空）→ 物料列表「最新供应商」分别显示两家、不再是「默认」→ 清理恢复。

## 验收
- 用户原场景闭环：申请多行多供应商到货入库后，物料「最新供应商」如实反映最近一次实际货源；手动进货体验不变；85/86 存量回填后商品资料页立即显示正确。
