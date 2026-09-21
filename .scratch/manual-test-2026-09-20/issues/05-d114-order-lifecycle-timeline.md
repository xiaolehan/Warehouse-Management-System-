# 05 · D114 生产任务单生命周期时间线

Type: task
Status: ready-for-agent
Blocked by: 02（到货/入库节点按批次语义合成）

Q10=推荐：全动线一条线，工序合并节点。

## 范围

### 后端
- 新建 ProductionOrderTimelineService（仿 DocumentTimelineService 合成模式，不读日志表）+ `GET /business/production-orders/{id}/timeline`，读守卫沿用任务单详情权限（生产/仓储部门成员；超管 ADR-0009）。
- 节点链（有则显、无则隐；进行中 current、已完成 done、未到 pending）：
  下达 → 补料申请（仅缺料单；含认领→到货批次（票 02 按批重复）→入库）→ 领料申请 → 仓储发料 → 生产确认收货 → 开工 →
  **生产中（单节点，文案「生产中（已完成 X/N 道工序）」，X 来自 biz_production_order_step 打卡数）** →
  完工报工 → 入库申请（D107；驳回旁支带原因）→ 仓储确认入库·完成。
  终止(status=7)/作废(5)/报废(6) → 红色终态节点带原因；质检 NG→返工作为旁支节点。
- 人名字段有则用人名、角色锁定步骤用部门名（沿用 D104 口径）。
- 补料段数据：经 purchase_request.production_order_id 关联（在途+已入库都取）；领料段：biz_pick_list（PICK/SUPPLY）。

### 前端
- 任务单详情弹窗底部嵌入通用 DocumentTimeline.vue（props nodes）；零新组件。
- 补料弹窗/查看入口不变；本票解决的是「查看任务单能看到整条动线」。

### 测试
- 单测：齐套直接生产（无补料段）、缺料→补料→分批到货→领料→打卡→两段式入库 全链节点；终止/驳回旁支；工序进度计数。
- E2E：用票 03 批量下达的单跑一条完整链，校验节点数与文案。

## 验收
- 生产点任务单「查看」即可见谁在哪一步做了什么；补料采购进度在任务单内可见，不必切换部门视角。
