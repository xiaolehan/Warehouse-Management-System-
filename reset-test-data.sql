-- ============================================================
-- reset-test-data.sql — 测试轮数据重置脚本（2026-09-23 定案）
--
-- 用途：每轮手工测试前一键清空业务数据，恢复干净起步状态。
-- 用法：mysql -u wms_user -pwms_pass warehouse_management < reset-test-data.sql
--
-- 范围决策（2026-09-23 与用户确认）：
--   清空：全部业务单据（采购申请/进货/进货退货/销售/销售退货/
--         生产/领料/质检/盘点/审批流）、站内信、日志三类（登录/
--         操作/错误）、公告、AI 会话、工作要求
--   归零：base_goods.stock（库存随单据清空归零，否则出现
--         「无单据有库存」脏状态）；warning_stock 是主数据阈值，保留
--   保留：登录与组织（sys_user/sys_dept）、员工档案（sys_employee，
--         userId 关联账号）、主数据（base_goods/base_supplier/
--         base_supplier_contact/BOM biz_bom+biz_bom_detail）、
--         系统配置（sys_config/sys_ip_policy）、视图（v_* 非数据）
-- 备注：单号由 CodeGenerator 时间戳+随机生成，不依赖表内计数，
--       TRUNCATE 重置自增无副作用。执行前建议先全库备份：
--       mysqldump -u wms_user -pwms_pass --single-transaction \
--         warehouse_management > ~/wms-backups/wms-full-$(date +%Y%m%d-%H%M%S).sql
-- ============================================================

SET FOREIGN_KEY_CHECKS = 0;

-- ---------- 业务单据：采购链 ----------
TRUNCATE TABLE biz_purchase_request;
TRUNCATE TABLE biz_purchase_request_detail;
TRUNCATE TABLE biz_purchase;
TRUNCATE TABLE biz_purchase_detail;
TRUNCATE TABLE biz_purchase_return;
TRUNCATE TABLE biz_purchase_return_detail;

-- ---------- 业务单据：销售链 ----------
TRUNCATE TABLE biz_sales;
TRUNCATE TABLE biz_sales_detail;
TRUNCATE TABLE biz_sales_return;
TRUNCATE TABLE biz_sales_return_detail;

-- ---------- 业务单据：生产/仓储链 ----------
TRUNCATE TABLE biz_production;
TRUNCATE TABLE biz_production_order;
TRUNCATE TABLE biz_production_order_step;
TRUNCATE TABLE biz_production_qc;
TRUNCATE TABLE biz_pick_list;
TRUNCATE TABLE biz_pick_list_detail;
TRUNCATE TABLE biz_stocktake;
TRUNCATE TABLE biz_stocktake_detail;

-- ---------- 业务单据：审批流 ----------
TRUNCATE TABLE biz_approval_order;

-- ---------- 库存归零（主数据保留，仅清库存数量） ----------
UPDATE base_goods SET stock = 0;

-- ---------- 消息/日志/公告 ----------
TRUNCATE TABLE sys_message;
TRUNCATE TABLE sys_login_log;
TRUNCATE TABLE sys_operation_log;
TRUNCATE TABLE sys_error_log;
TRUNCATE TABLE sys_notice;

-- ---------- AI 会话（当前为空表，纳入以防残留） ----------
TRUNCATE TABLE ai_conversation;
TRUNCATE TABLE ai_message;
TRUNCATE TABLE ai_model_call_log;

-- ---------- 工作要求（当前为空表，纳入以防残留） ----------
TRUNCATE TABLE work_requirement;
TRUNCATE TABLE work_requirement_assign;
TRUNCATE TABLE work_requirement_attachment;

SET FOREIGN_KEY_CHECKS = 1;
