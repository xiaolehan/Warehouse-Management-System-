# 02 · D122 盘点导出无反应修复

Type: bug
Status: ready-for-agent
Blocked by: 无

用户实测（2026-09-21）：仓储管理员盘点详情弹窗点击「导出盘点表」，无论盲盘勾选与否，**完全无反应**（无报错、无下载）。

探查事实（勿重复调研）：前后端导出链路完整——`StocktakeView.vue` handleExport → `exportStocktakeAPI`（GET `/business/stocktake/{id}/export?blind=`，blob）→ `StocktakeController:144` → `StocktakeService.export`（POI xlsx，requireReadAccess 仓储部门+超管）。列表页 D118 另有导出按钮（明盘）。`saveBlobAs` 会探测 JSON 错误体并抛错；但 `handleExport` 对 `isAxiosError` 静默不提示。

## 范围

### 排查（先复现定位，再修）
- API 层复现：warehouse_admin 登录 → 取/建一张盘点单 → 直调 export 接口看响应（200 xlsx？还是 JSON 错误？）。
- 前端复现：详情弹窗点按钮看 Network/Console（请求是否发出、blob 是否到达、saveBlobAs 是否抛错被吞）。

### 修复（无论根因）
- 根因修复。
- **错误呈现兜底**：handleExport 任何失败路径（axios 错误/JSON 错误体/未知异常）都必须 ElMessage 明确提示，杜绝「点了没反应」。
- 列表页导出按钮（handleListExport）同步检查同一问题。

### 测试
- 后端：export 服务层单测（生成 xlsx 非空、blind 列差异、权限）。
- E2E：建盘点单→详情/列表两入口各导出一次（200 + Content-Type xlsx），负测权限。

## 验收
- 仓储点导出必得文件；失败必有明确错误提示，不再无声。
