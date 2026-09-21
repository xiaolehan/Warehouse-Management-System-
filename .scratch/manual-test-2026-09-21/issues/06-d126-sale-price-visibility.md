# 06 · D126 成品售价可见性收紧（销售端+超管）

Type: bug
Status: ready-for-agent
Blocked by: 无

用户实测：成品售价本应只有销售端和超管可见，现仓储/采购也能看到。探查确认泄漏面共 3 处（后端 2 + 前端 1）：
1. `GoodsController` 的 page / options / {id} 三个读接口经 GoodsVO/GoodsOptionVO 无过滤返回 salePrice；
2. `GoodsView.vue:68` 售价列仅 `v-if="isProduct"`，无部门判断；
3. 详情弹窗 isView 模式展示全部字段含售价。
（SalesView 的 showPrice 已正确：`dept==='sales' || isSuperAdmin`。）

## 范围

### 后端
- GoodsController 读链路按调用方部门脱敏：非销售部门成员且非超管 → salePrice 置 null（VO 序列化阶段抹除，选项接口 GoodsOptionVO 同样处理）。
- 脱敏只针对成品售价；物料进价口径不变（进价本就仅限采购/仓储相关端）。
- 注意 D121 快速建品返回体含售价——仅销售部门+超管可调该端点，不受脱敏影响，但需确认脱敏实现不会误伤。

### 前端
- GoodsView 售价列：`v-if` 加部门判断（销售部门成员或超管才渲染）。
- 详情弹窗：售价字段对非销售端隐藏（isView 模式同样生效）。

### 测试
- 后端单测：仓储/采购管理员调 page/getById → salePrice 为 null；销售管理员/超管 → 正常返回。
- E2E（权限改写后必须覆盖目标角色读路径）：仓储管理员加载物料 page（200 且 salePrice=null）、物料 getById；销售管理员加载同接口（salePrice 正常）；负测写端点不受影响。

## 验收
- 仓储/采购任何页面（物料列表、详情、下拉选项）都看不到成品售价；销售端与超管不受影响。
