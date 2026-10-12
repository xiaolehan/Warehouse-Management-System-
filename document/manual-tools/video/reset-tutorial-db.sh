#!/bin/bash
# 教程录制专用库：从主库全量克隆后，清空业务单据/日志/消息/AI/用工表，
# 只保留登录账号（sys_user/sys_dept/sys_employee）、主数据（base_goods/base_supplier*/biz_bom*）
# 与系统配置（sys_config/sys_ip_policy）。每次录制前可重复跑，保证画面零历史杂数据。
set -e
MAIN=warehouse_management
TUT=warehouse_management_tutorial
M="mysql -u wms_user -pwms_pass"
D="mysqldump -u wms_user -pwms_pass"

$M -e "CREATE DATABASE IF NOT EXISTS $TUT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"

echo "克隆主库 → $TUT ..."
$D --routines --triggers "$MAIN" | $M "$TUT"

echo "清空业务/日志/消息表 ..."
$M "$TUT" -e "
SET FOREIGN_KEY_CHECKS=0;
TRUNCATE ai_conversation; TRUNCATE ai_message; TRUNCATE ai_model_call_log;
TRUNCATE biz_approval_order;
TRUNCATE biz_pick_list; TRUNCATE biz_pick_list_detail;
TRUNCATE biz_production; TRUNCATE biz_production_order; TRUNCATE biz_production_order_step; TRUNCATE biz_production_qc;
TRUNCATE biz_purchase; TRUNCATE biz_purchase_detail;
TRUNCATE biz_purchase_request; TRUNCATE biz_purchase_request_detail;
TRUNCATE biz_purchase_return; TRUNCATE biz_purchase_return_detail;
TRUNCATE biz_sales; TRUNCATE biz_sales_detail;
TRUNCATE biz_sales_return; TRUNCATE biz_sales_return_detail;
TRUNCATE biz_split_order; TRUNCATE biz_split_order_detail;
TRUNCATE biz_stocktake; TRUNCATE biz_stocktake_detail;
TRUNCATE sys_error_log; TRUNCATE sys_login_log; TRUNCATE sys_message; TRUNCATE sys_notice; TRUNCATE sys_operation_log;
TRUNCATE work_requirement; TRUNCATE work_requirement_assign; TRUNCATE work_requirement_attachment;
SET FOREIGN_KEY_CHECKS=1;"

echo "核对："
$M "$TUT" -N -e "
SELECT CONCAT('biz_sales=', COUNT(*)) FROM biz_sales;
SELECT CONCAT('sys_message=', COUNT(*)) FROM sys_message;
SELECT CONCAT('base_goods=', COUNT(*)) FROM base_goods;
SELECT CONCAT('biz_bom=', COUNT(*)) FROM biz_bom;
SELECT CONCAT('sys_user=', COUNT(*)) FROM sys_user;
SELECT CONCAT('sys_config=', COUNT(*)) FROM sys_config;"
echo "TUTORIAL_DB_OK"
