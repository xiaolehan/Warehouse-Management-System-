# 进货单头行结构改造（一单多物料，整单粒度，进货退货对称多行化）

> 状态：accepted（D111，2026-09-20 grilling；用户手测第 1、8 题。与 ADR-0013 销售头行改造同构、与 ADR-0016 按行分批到货配套）

## 背景

`biz_purchase`（物料进货单）历史上是**单头单物料**设计：goods_id/quantity/unit_price/total_price 全在头表，全项目无 `biz_purchase_detail`。两个痛点：

1. 手动进货一次只能进一种物料，与销售单（ADR-0013 已多行化）操作体验不对称；
2. 采购申请确认入库时**逐明细行各生成一张进货单**（`PurchaseRequestService.confirmReceive` 循环 `createInternal`）——一张补料申请 20 个物料就在「物料进货」页产生 20 张单号，用户原话「这是没有必要的」。

## 决策

1. **头行结构**：新建 `biz_purchase_detail`（goods_id/goods_name/spec/material 快照、quantity、unit_price、total_price、sort_no），头表保留单据级字段（purchase_no、operator、operation_time、biz_status、confirm_status、arrive/confirm 时间与人、void 字段、source_id、remark、total_quantity/total_amount 汇总）。头表 goods_id/quantity/unit_price/total_price 废弃（迁移后不留双写）。
2. **一单 N 物料行，同一物料一单只允许一行**（唯一键 `uk_purchase_goods(purchase_id, goods_id)`，与销售 `uk_sales_goods` 同构）；手动新建改多行编辑器（选物料带出名称/最近进价参考，数量/单价逐行填，头表自动汇总）。
3. **整单操作，不做分行作废/删除**（用户在 grill 中拍板 A 方案）：
   - 到货确认、入库确认、作废、删除全部以整单为单位；
   - 删除=当天+未生效（待到货）无痕删；作废=已生效/历史单走仓储审批（D94/D95 口径不变），作废时**逐行回冲库存 + 逐商品重算最近进价**（D101 `refreshPurchasePriceAfterVoid` 泛化为按行）；
   - 分行作废明确不做（单据状态/审批/库存/进价全要按行拆，等真实业务痛点再议）。
4. **采购申请确认入库合并生成一张多行进货单**：每次「到货提交→确认入库」生成**一张**进货单（本批到了哪些行就是哪几行），不再一行一单。与 ADR-0016 配套：一张申请来 N 批货 → N 张进货单，每张对应一次实际到货。
5. **进货退货对称多行化**：`biz_purchase_return` 改头行（新建 `biz_purchase_return_detail`），发起退货时选**来源进货单**后按其明细行勾选退货、逐行填退货数量（行级可退量=该行入库量−该行已退累计），单价按行带出。作废/删除粒度与进货单一致（整单）。旧一单一物料退货迁移为一头一行。
6. **进价回写按行**：确认入库时逐行回写 `base_goods.purchase_price`（与现有「每批采购价」口径一致，CONTEXT「进价」词条不变）；「物料管理-进价历史」数据源从「头表批次」改读「进货单明细行」（有效已入库行，最近在上）。
7. **旧数据迁移**：存量进货单/退货单全部迁移为「一头一行」，单据号、时间、状态、库存语义完全不变；迁移后旧单只读语义与新单一致（同为一行）。db.sql 规范段重写 + 增量段一条迁移 DDL/DML。
8. **连带适配**（实现时不可漏）：
   - D104 进货/进货退货时间线：节点不变，「入库确认」等节点的库存影响描述按行汇总；
   - 作废审批（ApprovalService）meta 读取从头表改明细聚合；统计 Mapper（v_purchase_detail 视图/年报）下沉 detail JOIN head（对标 D110 销售统计改造）；
   - GoodsReferenceService 商品引用统计下沉到明细行；
   - 库存增减仍只走 PurchaseService 私有 increase/decreaseStock（CLAUDE.md 铁律不变），逐行调用。

## Consequence

- 「物料进货」页单号数量回归业务真实批次（一次到货一张单）；手动进货与手动销售的多行交互一致。
- 一次到货中某一物料录错 → v1 只能整单作废重录（用户已接受）；真实痛点出现后再议分行作废。
- 采购单价的最小记录粒度从「头表」变为「明细行」，进价历史、统计、成本核算的读取点全部迁移；迁移期旧一头一行数据保证口径连续。
- 工作量集中在 PurchaseService/PurchaseReturnService 重写 + 两个前端视图多行编辑器 + 迁移，与 ADR-0013 销售改造同量级；单测重写 PurchaseServiceTest/PurchaseReturnServiceTest，E2E 覆盖多行入库/整单缺货式失败语义不适用（进货无缺货概念）/作废逐行回冲/退货行级可退量。
