# ADR-0006: 站内消息自带跳转目标 target_route，不做 bizType 细分

> 状态：accepted（2026-09-11 会话 28, D75）

## 背景

站内消息点击跳转（会话 27）最初由前端按 bizType + 角色猜路由：`BIZ_ROUTE_MAP` 候选数组按权限取首个可达。病根：bizType 被多意图复用——`sales` 同时承载 D21 生命周期消息（给仓储）、D70 销售需求（给生产）、价格偏离审批（给超管），同 bizType 不同接收方该去不同页面；前端只能靠「接收方角色」反推，新意图复用旧 bizType 时猜错风险高。会话 27 评审将此列为 defer 架构债。

备选方案：
- **bizType 细分**（sales → sales_lifecycle / sales_demand / price_deviation）：`revokeUnreadByBiz(bizType, id)` 按 bizType 匹配撤销（D21 消息生命周期），22 处调用点都要同步评估改撤哪种 intent，漏改 = 消息悬挂（有通知无单据）；
- **仅前端映射收口**：治标不治本，新意图仍需改映射表；
- **消息表加 target_route 列**：发送方显式指定跳转目标，前端优先使用，空则旧映射兜底。

## 决策

1. `sys_message` 加 `target_route VARCHAR(100) NULL`；18 个带 biz 的发送点按「接收方该去哪处理」逐一显式传入（如销售需求→/business/production-order、价格偏离→/system/void-approval、齐套→/business/production-order）；无 biz 的人事/工作单消息传 NULL。路由字面值在 MessageService 内常量化收口。
2. 前端跳转 `msg.targetRoute && canAccessPath(targetRoute)` 优先；为空或不可达时回落 `BIZ_ROUTE_MAP` 候选数组（存量消息兼容）。
3. **bizType 不动**——D21 生命周期撤销语义零影响（`revokeUnreadByBiz` 全部 22 处调用点不改）。

## Consequence

- 新增消息发送点必须同时给出 target_route（确无跳转目标传 NULL 并说明）。
- target_route 是前端路由字面值，前后端靠约定对齐；前端对不可达/不存在的路由始终回落映射兜底，不会跳死链。
- `BIZ_ROUTE_MAP` 保留为存量消息兜底，不再新增候选。
