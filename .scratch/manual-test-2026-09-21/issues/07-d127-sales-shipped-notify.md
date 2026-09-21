# 07 · D127 成品出库完成 → 销售端提醒

Type: task
Status: ready-for-agent
Blocked by: 无

用户定案（第一轮 Q7）：出库完成时提醒一次（推荐案）。现状：SalesService.confirm()（PUT /business/sales/{id}/confirm，CONFIRM_PENDING→CONFIRM_SHIPPED 整单确认出库）之后无任何消息发给销售端。

## 范围

### 后端
- SalesService.confirm() 成功路径追加站内消息：收件人=**销售部门管理员**（`sendToDeptAdminsWithBiz`，biz_type=`sales`、biz_id=销售单 id，符合 D21 生命周期范式）。
- 文案含销售单号与「已确认出库完成」，可点击回单（前端按 biz_type 跳转既有路由）。
- 不新增消息类型/不建新表，复用现有消息模板链路；仅确认动作发一次（confirm 幂等：非 CONFIRM_PENDING 状态本就拒绝，不会重复发）。

### 前端
- 消息点击跳转已有 sales biz_type 路由，确认可用（大概率零改动，验证即可）。

### 测试
- 单测：confirm 成功 → 消息发给销售管理员且带 biz_type/biz_id；重复 confirm / 非待确认状态不发。
- E2E：销售建单→仓储确认出库→销售管理员消息列表出现该提醒（带单号），点击可回单；清理。

## 验收
- 成品出库确认后，销售管理员收到带单号可回单的提醒。
