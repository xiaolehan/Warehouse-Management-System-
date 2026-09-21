# 08 · D117 「确认收货」入口同步到生产任务单详情

Type: task
Status: ready-for-agent
Blocked by: 无

Q7=推荐：加入口，不动状态机。

## 事实
- 确认收货现仅在 PickListView.vue:67（row.status===2 && 申请人本人），调 confirmPickListAPI；PickListService.confirm（:187-209）校验 ISSUED→DONE；
- 开工 start() 只要求领料单 ISSUED/DONE，**不卡确认收货**——保持不变。

## 范围

### 前端（ProductionOrderView.vue 详情弹窗领料信息区）
- 展示该任务单各领料单（PICK/SUPPLY）状态；对 status=2 且当前用户为申请人的领料单显示「确认收货」按钮，调同一个 confirmPickListAPI，成功后刷新详情；
- 无领料单/已 DONE/非本人：不显示按钮或显状态文本。
- 列表行不加入口（避免行按钮膨胀），只进详情。

### 后端
- 无改动（复用现有接口与权限）。

### 测试
- build；手测：仓储发料后生产在任务单详情直接确认收货→领料单 DONE→开工链路正常；非申请人不见按钮；PickList 页原入口回归。

## 验收
- 生产从任务单详情开工前可顺手完成收货确认，不必绕到生产领料列表页。
