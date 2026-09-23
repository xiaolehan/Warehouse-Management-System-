# 02 · 批量删除—主数据（商品/物料、BOM、供应商）

Type: task
Status: ready-for-human
Blocked by: 无

用户手测（2026-09-23）问题 1：所有有「删除」的界面都加「批量删除」。grilling 定案：
- **范围**：所有删除页（15 页，拆主数据/业务单据/系统页三张票）。
- **权限**：谁可编辑谁可删——批量删除权限 = 该页单个删除现有权限（BOM 仓储+生产可见但仅生产可删）。
- **失败策略**：尽力而为——逐行校验，能删的删，失败的返回明细。
- **成品删除权**：售价编辑不算删除权；删除权=主数据编辑权（物料/成品=仓储、BOM=生产）。
- UI 复刻 LoginLogView 范式：表格多选列 + 工具栏「批量删除（N）」按钮（无选中禁用）+ 确认弹窗。

## 范围（本票：主数据 3 页）

### 商品/物料管理（GoodsView.vue，/base/goods 与 /base/products 两路由共用）
- 现状：删除按钮 v-permission warehouse admin（:96-98）；GoodsService.delete（:466）= requireNotSuperAdminForBusinessWrite + requireDeptAdminOrSuperAdmin(WAREHOUSE) + 成品可删性校验（isProductDeletable：有库存/有效 BOM/被单据引用则拒）。
- 后端：`POST /base/goods/batch-delete`（body `{ids:[...]}`），守卫一次，逐行跑 isProductDeletable + 名称唯一校验等单删同款校验；尽力而为聚合返回。
- 前端：多选列 + 批量删除按钮（v-permission 同删除）+ handleBatchDelete。

### BOM 管理（BomView.vue）
- 现状：仅生产可编辑/删；BomService.delete 守卫生产 admin。
- 后端 batch-delete 同口径；前端同范式（仓储可见页面但按钮仅生产显示）。

### 供应商管理（SupplierView.vue）
- 现状：SupplierService.delete 守卫同页面编辑权；批量镜像。
- 有联系人子表/被引用校验的逐行沿用。

## 统一约定（三张批量删除票共用）
- 后端新增通用返回 `BatchDeleteResultVO { successCount, failureCount, failures: [{id, name, reason}] }`。
- 批量端点守卫调一次；逐行执行与单删完全相同的校验（含 D21 revokeUnreadByBiz 消息撤销，如有）。
- 失败明细文案格式：「成功 X 条，失败 Y 条：<name>：原因；…」；全成功只报「成功 X 条」。
- 撤销/作废按钮不动（不在本票范围）。
- 单测：守卫负测 + 尽力而为聚合（混合成败）+ 全败/全成。

## 验收
- 三页出现批量删除按钮，权限与单删一致；混合选中（可删+不可删）删除后页面刷新，成功行消失，失败行保留并弹出明细。
