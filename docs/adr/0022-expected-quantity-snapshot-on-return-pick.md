# ADR-0022: 退料单「应退量」快照列 — 建单时点定格而非读取时回算

日期：2026-10-08　|　状态：已采纳　|　关联：ADR-0020（成品拆分单）、Q16（差异备注）、D138/D139/D140（会话 68）

## 背景

退料单（biz_pick_list type=RETURN）详情此前只有「实际退了多少」（quantity），没有
「应该退多少」的参照。用户原话：「本该退 4 个现在退了 2 个，那缺少的 2 个是什么原因」——
判断差异是否合理，需要一个稳定的分母。该分母有两条实现路线：读取时实时回算
（对每张退料单重跑已领未退/BOM 净额），或建单时点落快照列。

## 决策

1. **biz_pick_list_detail 新增 `expected_quantity` 快照列（INT NULL），建单时定格。**
   两条 RETURN 建单路径各写各的口径，均为该单「被创建那一刻」的应退参照：
   - **拆分退料**（SplitOrderService.submitReturn）= 该物料在 BOM×拆分数量快照中的需求量；
   - **终止/生产退料**（ProductionPickService.insertReturnList）= 建单时点「已领未退」净额
     （computeNetReturnableItems 同口径，与 terminate 的 ensureWithinReturnable 守卫一致）。
2. **历史行不回填，保持 NULL，前端显示「—」。** 快照列的语义是「建单那一刻」；
   该功能上线前的历史单没有「当时」的值，用实时回算冒充快照反而会篡改留档。
3. **读取时直接透出，不做读取时 join 回算**（createReturn 不跑 ensureWithinReturnable，
   读取路径也无从重建「建单时点」的净额——时点语义只有快照能保真）。
4. **同场加映（D140）**：拆分单详情按 goodsId 对齐回填关联退料明细的实际退量与差异备注
   （returnedQuantity/returnDiffReason，退料单撤销被逻辑删时不回填显示「—」）；
   （D139）PickListView 弹窗标题/驳回标题/撤销确认文案按 pickTypeText 显示
   领料/补料/退料，RETURN 详情数量列改「退料数量」并新增「应退量」列。

## 后果

- 正向：详情页一列看齐「应退 vs 实退 + 差异原因」，仓储收料与对账不再需要口头核对；
  快照不可变，历史单据留档语义稳定；读取路径零额外查询。
- 负向：历史 RETURN 行 expected_quantity 为 NULL（显示「—」），新旧单据详情呈现不齐；
  若未来应退口径调整（如拆分退料改净额），快照列需区分来源或另加列。
- 备选否决：读取时回算——无「建单时点」可复现（库存与领料状态持续变化），且每行详情
  都要重算净额，代价高还失真。
