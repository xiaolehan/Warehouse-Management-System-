# 01 · D128 销售单客户联系人/手机号

Type: task
Status: ready-for-human
Blocked by: 无

用户定案（2026-09-22 grilling，第三轮手测问题 1）：销售单头表加「客户联系人」「手机号」两个字段，**均非必填**；退货建单自动带出、可编辑（完全对齐 customerName 既有行为）；列表不加列，仅建单弹窗 + 详情弹窗显示。

## 范围

### 数据库
- `biz_sales` 加 `customer_contact_name`、`customer_phone`（均可空 varchar）。
- `biz_sales_return` 加同两列（退货快照）。db.sql 规范段 + 增量段，本地执行。

### 后端
- `SalesSaveDTO` 加两字段（无校验注解——自由文本，手机号不做格式校验，对齐系统极简风格）；`SalesService.create` 透传（镜像 customerName/contractNo 的 443-444 行写法）。
- `SalesReturnSaveDTO` 加两字段；`SalesReturnService.create` 复制逻辑镜像 customerName（SalesReturnService.java:235-236 范式）：**dto 传入优先，否则从来源销售单带出**。
- 列表 VO / 详情 VO 透出两字段；可见性跟随 customerName（客户信息非价格，不做 D126 式脱敏）。
- 消息文案**不携带**联系人（保持现有按单汇总文案不变）。

### 前端
- `SalesView.vue`：建单弹窗客户公司名/合同号旁加「客户联系人」「手机号」两个选填输入框；详情弹窗头部显示（无值显示 —）；列表不加列。
- `SalesReturnView.vue`：建单弹窗加两字段，选中来源单后自动带出（镜像 customerName 的 handleSourceSalesChange 418-420 行范式）、可编辑；退货详情显示。

### 测试
- 单测：创建带/不带联系人；退货不带参时从源单带出、显式传参覆盖源单值。
- E2E：建单→退货链路字段断言 + 清理。

## 验收
- 建单填联系人/手机号 → 详情显示；退货建单自动带出且可改；留空全链不报错。
