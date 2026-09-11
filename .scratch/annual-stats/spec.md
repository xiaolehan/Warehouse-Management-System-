# 年度经营统计（财务端）

Status: ready-for-agent

> 来源：2026-09-11 grilling 会话定案（三轮设计树共 16 题全部确认，测试缝已确认）。口径决策将同步留档 ADR-0008 与 CONTEXT.md 术语增补。

## Problem Statement

系统历经多轮业务改造（采购申请多品流程、销售/采购退货、红冲机制、成本快照等），但财务端仍只有一个「销售统计图表」页，且最粗粒度只到「按天」。财务管理员无法回答最基本的年度经营问题：**今年采购花了多少、销售收了多少、赚了多少**。采购侧目前没有任何统计代码，年度维度完全空白。

## Solution

财务端新增独立页面「**年度经营统计**」：按自然年汇总五个金额指标——采购总支出、销售总营收、销售成本、毛利、毛利率，一年一行（年份倒序），配分组柱状图（每年三根柱：采购/销售/毛利），支持一键导出 xlsx。数据口径与系统既有毛利视角完全对齐（可实现毛利、严过滤、退货冲减），数字可与销售统计图表页交叉验证。仅财务管理员可见，超管也禁（与利润分析同级）。

## User Stories

1. As a 财务管理员, I want 看到按自然年汇总的采购总支出/销售总营收/销售成本/毛利/毛利率表格, so that 我能一眼回答每年花了多少、收了多少、赚了多少
2. As a 财务管理员, I want 表格按年份倒序排列（最新年在最上）, so that 我最关心的近期数据最先看到
3. As a 财务管理员, I want 采购总支出同时覆盖旧进货单和采购申请入库两条渠道（实施确认：两条渠道都落在 biz_purchase 入库交易表，单一来源聚合即可完整覆盖且不重复计数）, so that 历史年份的数字不会因流程切换而偏小或翻倍
4. As a 财务管理员, I want 采购退货/销售退货按退货单自身发生时间冲减对应年份的金额, so that 退货不会虚增发生年的采购/营收
5. As a 财务管理员, I want 作废单和红冲单一律不计入统计, so that 报表数字不被无效单据污染
6. As a 财务管理员, I want 销售额按销售单 operation_time 归年、采购额按入库确认时间归年, so that 口径与销售统计图表页一致、采购按钱货两清时点计入
7. As a 财务管理员, I want 毛利 = 销售额 − 销售成本快照（已实现销售毛利）, so that 报表数字与既有毛利视角一致、可交叉验证
8. As a 财务管理员, I want 某年只有采购没有销售（或反之）时该行照常列出、缺侧为 0、无销售年毛利率显示「—」, so that 信息完整且不产生误导性的 0% 毛利率
9. As a 财务管理员, I want 表格上方/旁边有分组柱状图（每年采购/销售/毛利三根柱）, so that 年度趋势一目了然
10. As a 财务管理员, I want 一键导出当前年度统计为 xlsx 文件, so that 我可以离线存档或转发给管理层
11. As a 财务管理员, I want 导出的 xlsx 内容与页面表格完全一致（同口径同列）, so that 离线数据可信
12. As a 超级管理员, I want 我也无法访问年度经营统计（含导出接口）, so that 采购进价等敏感数据的可见范围与利润分析一致、被严格限制
13. As a 非财务部门的管理员/员工, I want 访问该页面和接口时被拒绝, so that 经营数据不外泄
14. As a 财务管理员, I want 页面挂在财务菜单「销售统计图表」下方, so that 我能按既有习惯找到入口

## Implementation Decisions

### 模块与接口

- **后端新增 AnnualStats 模块**（Controller + Service，无新表、无 schema 变更）：
  - `GET /business/annual-stats` — 返回全部年份的年度汇总行列表（年份倒序）
  - `GET /business/annual-stats/export` — 返回同口径 xlsx 字节流（POI 生成，Content-Disposition 附件文件名含「年度经营统计」与导出日期）
  - Controller 类级 `@SaCheckRole(value = {"admin", "superadmin"}, mode = SaMode.OR)`；Service 层权限守卫**仅财务部门管理员**（超管显式拒绝），仿 `SalesChartService.requireFinanceProfitAccess` 范式；两个接口共用同一守卫
- **聚合查询**（新增于各既有 Mapper，纯 SQL 聚合、按年 GROUP BY）：
  - 销售额/销售成本：`biz_sales` 按 `YEAR(operation_time)` 聚合 `SUM(total_price)` / `SUM(cost_total_price)`，过滤 `is_deleted=0 AND biz_status=1 AND confirm_status=2`（严口径，与毛利视角一致）
  - 销售退货冲减：`biz_sales_return` 按 `YEAR(operation_time)` 聚合，严口径（`biz_status=1`、已确认入库、未删除），Service 层从对应年份扣减
  - 采购额来源一：`biz_purchase` 按 `YEAR(COALESCE(confirm_time, operation_time))` 聚合 `SUM(total_price)`，过滤 `biz_status=1 AND confirm_status=3 AND is_deleted=0`
  - ~~采购额来源二：`biz_purchase_request_detail` 求和~~ **（实施期 code-review 修正，作废）**：采购申请「确认入库」会逐明细写入 `biz_purchase`（`confirmReceive` → `createInternal`，`confirm_status=3`、金额为 `unit_price × arrive_quantity`），来源一已完整覆盖采购申请渠道；若再加来源二，该渠道金额精确翻倍。采购额 = 来源一 − 采购退货冲减
  - 采购退货冲减：`biz_purchase_return` 按退货完成/确认时间归年聚合，从严过滤（已退货终态、未删除、非红冲/作废），Service 层从对应年份扣减
- **Service 汇总逻辑**：以「销售年 ∪ 采购年」的并集构造年份列表，缺侧补 0；毛利 = 销售额 − 销售成本；毛利率 = 毛利 ÷ 销售额，销售额为 0 时毛利率返回 `null`；金额保留 2 位小数
- **导出**：POI `XSSFWorkbook` 生成单 sheet，列与页面一致，数字用数值单元格（非字符串）便于 Excel 再计算；返回字节流，前端 `downloadBlob` 下载，完全照搬 BOM 导出范式

### 前端

- 新增页面「年度经营统计」，路由 `/business/annual-stats`，`meta: { roles: ['admin'], deptCodes: ['finance'] }`
- 财务管理员侧边栏在「销售统计图表」下方加菜单项
- 页面构成：顶部工具栏（右侧「导出」按钮，遵守 ADR-0007 按钮规范——工具栏实心按钮带图标，导出用 `primary`）+ ECharts 分组柱状图 + 年度表格（el-table，年份倒序，金额右对齐千分位、2 位小数，毛利率 1 位小数 + `%`，无销售年显示 `—`）
- 图表与表格共用同一份接口数据，不做二次计算

### 留档（随实现一并提交）

- 新增 **ADR-0008 年度经营统计口径**：已实现销售毛利（而非收支差）、严过滤、退货按自身年份冲减、双采购来源、归年时间字段、仅财务管理员可见
- CONTEXT.md 增补术语：「已实现销售毛利」「年度归属（operation_time / 入库确认时间）」

### 明确约束

- 不加任何筛选器/参数：接口无入参，一次返回全部年份
- 不做下钻：无按商品/品牌/客户/供应商的年度明细
- 不做商品价格趋势（进价历史散在单据上，口径复杂，本次不碰）

## Testing Decisions

好测试的标准：只测外部行为（给定 mapper 聚合结果 → 汇总输出；给定身份 → 允许/拒绝），不测 SQL 文本、不测内部私有方法、不测框架渲染。

- **AnnualStatsServiceTest**（单元测试，Mockito mock 各 Mapper + AuthzService，先例行 [HrChartServiceTest](back/src/test/java/org/example/back/service/HrChartServiceTest.java)）：
  - 同年采购双来源金额正确相加
  - 销售退货/采购退货按自身年份冲减（跨年退货冲到退货年、不回溯原单年）
  - 只有单侧数据的年份照常出现、缺侧为 0、销售额为 0 时毛利率为 `null`
  - 毛利 = 销售额 − 销售成本；多行年份按倒序输出
  - 权限守卫被调用（mock AuthzService 抛 BusinessException 时向上传播）
- **AnnualStatsAuthTest**（Controller 权限测试，MockMvc + mock Service，先例行 [SalesChartAuthTest](back/src/test/java/org/example/back/controller/SalesChartAuthTest.java)）：
  - 财务 admin → 200；超管 → 拒绝；非财务部门 admin → 拒绝；employee → 拒绝
  - 导出接口同套身份断言 + 响应头为 xlsx 附件
- **不做**：DB 集成测试（无先例，聚合正确性靠 Service 测试 + 部署后 curl E2E 验证）、前端测试（无先例）、ECharts 渲染测试

## Out of Scope

- 商品/品牌/客户/供应商维度的年度下钻与明细
- 商品价格水平趋势（各商品年度平均进价/售价走势）
- 年份区间筛选、同比/环比计算、「本年迄今」高亮
- CSV 导出、按月/按季度聚合
- 首页看板接入年度数据
- 现金流口径的收支差报表

## Further Notes

- 销售侧聚合 SQL 可直接扩展自 `BizSalesMapper` 现有按天聚合范式（GROUP BY 换成 YEAR），采购侧为全新查询
- `biz_purchase` 无供应商字段、金额即单价×数量总额，聚合无需 JOIN 商品表
- 采购申请明细的金额口径以实表字段为准：若明细行存了金额快照则直接 SUM，否则用 `unit_price * arrive_quantity` 计算——实现时先核对实体
- 红冲单与被红冲原单的互斥由严口径 `biz_status=1` 保证（与毛利视角同一套过滤，已被生产验证）
- 金额口径均为含税/不含税混合原值（`tax_included` 纯记录标志，不参与计算，CONTEXT.md 已定义）
