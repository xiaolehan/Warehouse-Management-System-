-- =============================================
-- 仓库管理系统 - 全库数据快照（脚本自动导出，勿手改）
-- 内容：结构+数据+视图+存储过程+触发器（含 CREATE DATABASE/USE）
-- 恢复：mysql -u wms_user -pwms_pass < db-dump.sql
-- 更新：bash sync-db-dump.sh（覆盖本文件并推送）
-- =============================================
-- MySQL dump 10.13  Distrib 8.0.46, for Linux (x86_64)
--
-- Host: localhost    Database: warehouse_management
-- ------------------------------------------------------
-- Server version	8.0.46-0ubuntu0.24.04.4

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Current Database: `warehouse_management`
--

CREATE DATABASE /*!32312 IF NOT EXISTS*/ `warehouse_management` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci */ /*!80016 DEFAULT ENCRYPTION='N' */;

USE `warehouse_management`;

--
-- Table structure for table `ai_conversation`
--

DROP TABLE IF EXISTS `ai_conversation`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_conversation` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `title` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '会话标题（首条问题前50字）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`,`created_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI助手对话会话表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `ai_conversation`
--

LOCK TABLES `ai_conversation` WRITE;
/*!40000 ALTER TABLE `ai_conversation` DISABLE KEYS */;
/*!40000 ALTER TABLE `ai_conversation` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `ai_message`
--

DROP TABLE IF EXISTS `ai_message`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_message` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `conversation_id` bigint NOT NULL COMMENT '所属会话ID',
  `role` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '消息角色: user/assistant',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '消息内容',
  `sources_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '来源文档JSON（仅assistant消息）',
  `hit_type` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '命中类型（仅assistant消息）',
  `provider_code` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '模型供应商编码（仅assistant消息）',
  `model_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '模型编码（仅assistant消息）',
  `fallback_used` tinyint(1) DEFAULT NULL COMMENT '是否发生模型回退（仅assistant消息）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_conv` (`conversation_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI助手对话消息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `ai_message`
--

LOCK TABLES `ai_message` WRITE;
/*!40000 ALTER TABLE `ai_message` DISABLE KEYS */;
/*!40000 ALTER TABLE `ai_message` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `ai_model_call_log`
--

DROP TABLE IF EXISTS `ai_model_call_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_model_call_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `role_code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '发起调用的角色编码',
  `dept_code` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '发起调用的部门编码',
  `conversation_id` bigint DEFAULT NULL COMMENT '所属会话ID',
  `assistant_message_id` bigint DEFAULT NULL COMMENT '对应assistant消息ID',
  `scene_code` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'project-assistant' COMMENT '调用场景编码',
  `question_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '问题类型：project/general',
  `requested_model_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户请求模型编码',
  `provider_code` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '模型供应商编码',
  `model_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '实际模型编码',
  `fallback_used` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否触发模型回退',
  `hit_type` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '命中类型',
  `result_status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'success' COMMENT '结果状态',
  `latency_ms` bigint DEFAULT NULL COMMENT '模型调用耗时（毫秒）',
  `question_excerpt` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '问题摘要，不记录完整请求体',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`,`created_at` DESC),
  KEY `idx_conv_created` (`conversation_id`,`created_at` DESC),
  KEY `idx_message` (`assistant_message_id`),
  KEY `idx_model_status_created` (`model_code`,`result_status`,`created_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI模型调用审计表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `ai_model_call_log`
--

LOCK TABLES `ai_model_call_log` WRITE;
/*!40000 ALTER TABLE `ai_model_call_log` DISABLE KEYS */;
/*!40000 ALTER TABLE `ai_model_call_log` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `base_goods`
--

DROP TABLE IF EXISTS `base_goods`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `base_goods` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `goods_code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '商品编码',
  `type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'material' COMMENT '货品类型: material-物料/零件, product-成品',
  `goods_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '商品名称',
  `product_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '产品名称',
  `category` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '商品类别',
  `brand` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '商品品牌(用于图表聚合)',
  `supplier_id` bigint NOT NULL COMMENT '供应商ID',
  `purchase_price` decimal(10,2) DEFAULT NULL COMMENT '进价',
  `sale_price` decimal(10,2) DEFAULT NULL COMMENT '售价',
  `stock` int NOT NULL DEFAULT '0' COMMENT '当前库存量',
  `warning_stock` int NOT NULL DEFAULT '10' COMMENT '库存预警阈值',
  `unit` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '单位',
  `spec` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '规格(物料固有属性,ADR-0003;物料按名称+规格唯一)',
  `material` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '材质(物料固有属性,ADR-0003)',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态: 1-上架, 0-下架',
  `description` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_goods_code` (`goods_code`),
  KEY `idx_supplier_id` (`supplier_id`),
  KEY `idx_supplier_status` (`supplier_id`,`status`),
  KEY `idx_brand` (`brand`),
  KEY `idx_stock` (`stock`),
  KEY `idx_warning_stock` (`warning_stock`),
  KEY `idx_category` (`category`),
  KEY `idx_is_deleted` (`is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `base_goods`
--

LOCK TABLES `base_goods` WRITE;
/*!40000 ALTER TABLE `base_goods` DISABLE KEYS */;
/*!40000 ALTER TABLE `base_goods` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `base_supplier`
--

DROP TABLE IF EXISTS `base_supplier`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `base_supplier` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `supplier_code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '供应商编码',
  `supplier_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '供应商名称',
  `address` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '地址',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态: 1-启用, 0-禁用',
  `description` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_supplier_code` (`supplier_code`),
  KEY `idx_status` (`status`),
  KEY `idx_is_deleted` (`is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='供应商表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `base_supplier`
--

LOCK TABLES `base_supplier` WRITE;
/*!40000 ALTER TABLE `base_supplier` DISABLE KEYS */;
/*!40000 ALTER TABLE `base_supplier` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `base_supplier_contact`
--

DROP TABLE IF EXISTS `base_supplier_contact`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `base_supplier_contact` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `supplier_id` bigint NOT NULL,
  `contact_person` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `contact_phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `position` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `is_default` tinyint NOT NULL DEFAULT '0',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_supplier_id` (`supplier_id`),
  KEY `idx_is_deleted` (`is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `base_supplier_contact`
--

LOCK TABLES `base_supplier_contact` WRITE;
/*!40000 ALTER TABLE `base_supplier_contact` DISABLE KEYS */;
/*!40000 ALTER TABLE `base_supplier_contact` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `biz_approval_order`
--

DROP TABLE IF EXISTS `biz_approval_order`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_approval_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `approval_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '审批单号',
  `biz_type` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '业务类型: purchase/purchase_return/sales/sales_return',
  `biz_id` bigint NOT NULL COMMENT '业务单据ID',
  `biz_no` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '业务单号(冗余)',
  `request_action` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '申请动作: void/void_red/price_deviation_confirm',
  `request_reason` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '申请原因',
  `request_detail` text COLLATE utf8mb4_unicode_ci COMMENT '价格偏离行快照(JSON,会话68/D137): {thresholdPercent, rows:[lineNo/goodsName/quantity/unitPrice/standardSalePrice/deviationAmount/deviationPercent]}，建单时点快照',
  `before_biz_status` tinyint DEFAULT NULL COMMENT '审批前业务状态快照',
  `before_biz_snapshot` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '审批前业务详情快照(JSON)',
  `after_biz_status` tinyint DEFAULT NULL COMMENT '审批后业务状态快照',
  `after_biz_snapshot` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '审批后业务详情快照(JSON)',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态: 1-待审批, 2-已通过, 3-已驳回, 4-处理中',
  `requester_id` bigint NOT NULL COMMENT '申请人ID',
  `requester_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '申请人姓名',
  `requester_role` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '申请人角色',
  `approver_id` bigint DEFAULT NULL COMMENT '审批人ID',
  `approver_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '审批人姓名',
  `approve_remark` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '审批备注',
  `approved_at` datetime DEFAULT NULL COMMENT '审批通过时间',
  `rejected_at` datetime DEFAULT NULL COMMENT '审批驳回时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  `pending_unique_key` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci GENERATED ALWAYS AS ((case when ((`status` = 1) and (`is_deleted` = 0)) then concat(`biz_type`,_utf8mb4'#',`biz_id`) else NULL end)) STORED COMMENT '待审批唯一键(仅status=1且未删除生效)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_approval_no` (`approval_no`),
  UNIQUE KEY `uk_pending_unique_key` (`pending_unique_key`),
  KEY `idx_biz` (`biz_type`,`biz_id`),
  KEY `idx_status_create_time` (`status`,`create_time`),
  KEY `idx_requester` (`requester_id`,`requester_role`),
  KEY `idx_is_deleted` (`is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='作废审批单表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `biz_approval_order`
--

LOCK TABLES `biz_approval_order` WRITE;
/*!40000 ALTER TABLE `biz_approval_order` DISABLE KEYS */;
/*!40000 ALTER TABLE `biz_approval_order` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `biz_bom`
--

DROP TABLE IF EXISTS `biz_bom`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_bom` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `bom_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `goods_id` bigint NOT NULL,
  `goods_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `remark` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` tinyint NOT NULL DEFAULT '0',
  `active_goods_id` bigint GENERATED ALWAYS AS (if((`is_deleted` = 0),`goods_id`,NULL)) VIRTUAL COMMENT '软删兼容唯一键载体(D66): 有效行=goods_id, 软删行=NULL',
  `active_bom_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci GENERATED ALWAYS AS (if((`is_deleted` = 0),`bom_code`,NULL)) VIRTUAL COMMENT '软删兼容唯一键载体(D66): 有效行=bom_code, 软删行=NULL',
  `lead_days` int DEFAULT NULL COMMENT '标准工期（天，D71）：生产建/编辑BOM时填写，可空；空=待生产评估',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_bom_active_goods` (`active_goods_id`),
  UNIQUE KEY `uk_bom_active_code` (`active_bom_code`),
  KEY `idx_bom_is_deleted` (`is_deleted`),
  KEY `idx_bom_goods` (`goods_id`),
  KEY `idx_bom_code` (`bom_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='BOM 主表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `biz_bom`
--

LOCK TABLES `biz_bom` WRITE;
/*!40000 ALTER TABLE `biz_bom` DISABLE KEYS */;
/*!40000 ALTER TABLE `biz_bom` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `biz_bom_detail`
--

DROP TABLE IF EXISTS `biz_bom_detail`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_bom_detail` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `bom_id` bigint NOT NULL,
  `sort_no` int NOT NULL DEFAULT '0',
  `goods_id` bigint DEFAULT NULL,
  `component_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `spec` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `quantity` decimal(12,4) NOT NULL DEFAULT '1.0000',
  `material` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `remark` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `image` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '组件图片路径(/uploads/...)',
  `is_reference` tinyint NOT NULL DEFAULT '0',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `is_deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_bomd_bom_id` (`bom_id`),
  KEY `idx_bomd_goods_id` (`goods_id`),
  KEY `idx_bomd_is_deleted` (`is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='BOM 明细表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `biz_bom_detail`
--

LOCK TABLES `biz_bom_detail` WRITE;
/*!40000 ALTER TABLE `biz_bom_detail` DISABLE KEYS */;
/*!40000 ALTER TABLE `biz_bom_detail` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `biz_pick_list`
--

DROP TABLE IF EXISTS `biz_pick_list`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_pick_list` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `pick_no` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '领料单号',
  `pick_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '领料类型: PICK-领料, SUPPLY-补料, RETURN-退料',
  `source_sales_id` bigint DEFAULT NULL COMMENT '关联销售单ID(可选)',
  `production_order_id` bigint DEFAULT NULL COMMENT '来源生产任务单id(生产端申请领料时写入)',
  `split_order_id` bigint DEFAULT NULL COMMENT '关联成品拆分单ID(拆分退料RETURN单专用,ADR-0020)',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态: 1-待发料, 2-已发料, 3-已完成, 4-已驳回',
  `applicant_id` bigint NOT NULL COMMENT '申请人ID',
  `applicant_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '申请人姓名(冗余字段)',
  `operator_id` bigint DEFAULT NULL COMMENT '发料人ID(仓储管理员)',
  `operator_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '发料人姓名(冗余字段)',
  `operation_time` datetime DEFAULT NULL COMMENT '发料时间',
  `confirm_time` datetime DEFAULT NULL COMMENT '确认收货时间',
  `reject_reason` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '驳回原因',
  `remark` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  `pick_prod_key` bigint GENERATED ALWAYS AS ((case when (`pick_type` = _utf8mb4'PICK') then `production_order_id` end)) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pick_no` (`pick_no`),
  UNIQUE KEY `uk_pick_prod_key` (`pick_prod_key`),
  KEY `idx_pick_status` (`status`),
  KEY `idx_pick_applicant` (`applicant_id`),
  KEY `idx_pick_source_sales` (`source_sales_id`),
  KEY `idx_pick_is_deleted` (`is_deleted`),
  KEY `idx_pick_production_order` (`production_order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='生产领料单主表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `biz_pick_list`
--

LOCK TABLES `biz_pick_list` WRITE;
/*!40000 ALTER TABLE `biz_pick_list` DISABLE KEYS */;
/*!40000 ALTER TABLE `biz_pick_list` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `biz_pick_list_detail`
--

DROP TABLE IF EXISTS `biz_pick_list_detail`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_pick_list_detail` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `pick_list_id` bigint NOT NULL COMMENT '领料单主表ID',
  `goods_id` bigint NOT NULL COMMENT '商品ID',
  `goods_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '商品名称(冗余字段)',
  `spec` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '规格快照(建单时自物料主数据带入,D63)',
  `material` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '材质快照(建单时自物料主数据带入,D63)',
  `remark` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注快照(建单时自物料主数据描述带入,D63)',
  `diff_reason` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '差异备注(Q16/Q7:RETURN行退料量<已领未退时的损耗/丢失原因)',
  `expected_quantity` int DEFAULT NULL COMMENT '应退量快照(会话68/D138,ADR-0022): RETURN行建单时点的「应该退多少」参照量——拆分退料=BOM需求量快照, 终止/生产退料=建单时点已领未退净额; 该功能前的历史行为NULL(前端显示—)',
  `quantity` int NOT NULL COMMENT '数量',
  `sort_no` int NOT NULL DEFAULT '0' COMMENT '行序号',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  PRIMARY KEY (`id`),
  KEY `idx_pld_pick_list_id` (`pick_list_id`),
  KEY `idx_pld_goods_id` (`goods_id`),
  KEY `idx_pld_is_deleted` (`is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='生产领料单明细表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `biz_pick_list_detail`
--

LOCK TABLES `biz_pick_list_detail` WRITE;
/*!40000 ALTER TABLE `biz_pick_list_detail` DISABLE KEYS */;
/*!40000 ALTER TABLE `biz_pick_list_detail` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `biz_production`
--

DROP TABLE IF EXISTS `biz_production`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_production` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `production_no` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '生产入库单号',
  `production_order_id` bigint DEFAULT NULL COMMENT '来源生产任务单ID(D107: 生产端提交入库申请时写入；仓储手动新增为空)',
  `goods_id` bigint NOT NULL COMMENT '商品ID',
  `goods_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '商品名称(冗余字段)',
  `quantity` int NOT NULL COMMENT '入库数量',
  `unit_price` decimal(10,2) DEFAULT NULL COMMENT '生产单价(可选,自产零件成本可能未知)',
  `total_price` decimal(10,2) DEFAULT NULL COMMENT '总金额(单价为空时为空)',
  `operator_id` bigint DEFAULT NULL COMMENT '操作人ID',
  `operator_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作人姓名(冗余字段)',
  `operation_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作发生时间',
  `remark` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `biz_status` tinyint NOT NULL DEFAULT '1' COMMENT '业务状态: 1-正常, 2-已作废, 3-红冲单',
  `confirm_status` tinyint NOT NULL DEFAULT '2' COMMENT '确认状态(D107): 1-待仓库确认, 2-已确认入库(存量行回填2), 3-已驳回',
  `confirmer_id` bigint DEFAULT NULL COMMENT '确认人ID(D107: 确认入库/驳回时写；手动新增=录入人)',
  `confirmer_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '确认人姓名(冗余)',
  `confirm_time` datetime DEFAULT NULL COMMENT '确认/驳回时间(D107)',
  `reject_reason` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '驳回原因(D107: confirm_status=3 时有值)',
  `source_id` bigint DEFAULT NULL COMMENT '红冲来源单ID',
  `void_time` datetime DEFAULT NULL COMMENT '作废时间',
  `void_reason` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '作废原因',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  `pending_order_key` bigint GENERATED ALWAYS AS ((case when ((`confirm_status` = 1) and (`is_deleted` = 0)) then `production_order_id` else NULL end)) STORED COMMENT '待确认申请唯一键生成列(D107)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_production_no` (`production_no`),
  UNIQUE KEY `uk_production_pending_order` (`pending_order_key`),
  KEY `idx_production_goods_id` (`goods_id`),
  KEY `idx_production_status` (`biz_status`),
  KEY `idx_production_source_id` (`source_id`),
  KEY `idx_production_operator_id` (`operator_id`),
  KEY `idx_production_is_deleted` (`is_deleted`),
  KEY `idx_production_confirm_status` (`confirm_status`),
  KEY `idx_production_order_id` (`production_order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='生产入库表(自产零件入库,库存增加)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `biz_production`
--

LOCK TABLES `biz_production` WRITE;
/*!40000 ALTER TABLE `biz_production` DISABLE KEYS */;
/*!40000 ALTER TABLE `biz_production` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `biz_production_order`
--

DROP TABLE IF EXISTS `biz_production_order`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_production_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `order_no` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '生产任务单号',
  `goods_id` bigint NOT NULL COMMENT '成品 goods_id(type=product)',
  `goods_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '成品名称(冗余)',
  `unit` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '成品单位(冗余)',
  `quantity` int NOT NULL COMMENT '生产数量',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态: 1-待生产, 2-生产中, 3-待质检, 4-已完成, 5-已作废, 6-已报废, 7-已终止',
  `kit_status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '齐套状态: ok-齐套, partial-部分缺料, block-严重缺料',
  `source` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'MANUAL' COMMENT '来源: MANUAL-手动创建',
  `process_snapshot` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '工序清单快照(8道装配工序静态 SOP，打印用)',
  `remark` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  `sales_order_id` bigint DEFAULT NULL COMMENT '关联销售单id（D70），可空；一张生产单最多关联一张销售单',
  `sales_detail_id` bigint DEFAULT NULL COMMENT '关联的销售明细行ID(D110: 由(销售单,成品)唯一解析；前端仍只传销售单ID)',
  `expected_completion_time` datetime DEFAULT NULL COMMENT '生产手工修正的预计完工时间（D71），优先于系统推算',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_goods_id` (`goods_id`),
  KEY `idx_order_is_deleted` (`is_deleted`),
  KEY `idx_po_sales` (`sales_order_id`),
  KEY `idx_sales_detail_id` (`sales_detail_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='生产任务单（D42 齐套预警）';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `biz_production_order`
--

LOCK TABLES `biz_production_order` WRITE;
/*!40000 ALTER TABLE `biz_production_order` DISABLE KEYS */;
/*!40000 ALTER TABLE `biz_production_order` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `biz_production_order_step`
--

DROP TABLE IF EXISTS `biz_production_order_step`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_production_order_step` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `order_id` bigint NOT NULL COMMENT '生产任务单 id',
  `step_no` int NOT NULL COMMENT '工序序号(人工工序: 1/2/3/4/5/7/9)',
  `step_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '工序名称(建单时快照)',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '完成状态: 0-未完成, 1-已完成',
  `operator_id` bigint DEFAULT NULL COMMENT '打卡人 id(完成时)',
  `operator_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '打卡人姓名',
  `operate_time` datetime DEFAULT NULL COMMENT '打卡时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_step_order_no` (`order_id`,`step_no`),
  KEY `idx_step_is_deleted` (`is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='生产工序实例（D64 打卡追踪，仅 7 道人工装配工序）';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `biz_production_order_step`
--

LOCK TABLES `biz_production_order_step` WRITE;
/*!40000 ALTER TABLE `biz_production_order_step` DISABLE KEYS */;
/*!40000 ALTER TABLE `biz_production_order_step` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `biz_production_qc`
--

DROP TABLE IF EXISTS `biz_production_qc`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_production_qc` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `order_id` bigint NOT NULL COMMENT '生产任务单 id',
  `goods_id` bigint DEFAULT NULL COMMENT '成品 goods_id(冗余)',
  `goods_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '成品名称(冗余)',
  `test_point` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '测点: first-首测, final-成品测',
  `tester_id` bigint DEFAULT NULL COMMENT '测试员 id',
  `tester_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '测试员姓名',
  `result` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '结果: OK-合格, NG-不合格',
  `reason` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'NG 原因/备注',
  `disposition` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '处置: REWORK-返工, SCRAP-报废(仅对 NG 记录)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '测试时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  PRIMARY KEY (`id`),
  KEY `idx_qc_order` (`order_id`,`is_deleted`),
  KEY `idx_qc_is_deleted` (`is_deleted`),
  KEY `idx_qc_goods` (`goods_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='生产质检记录（D40 首测/成品测）';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `biz_production_qc`
--

LOCK TABLES `biz_production_qc` WRITE;
/*!40000 ALTER TABLE `biz_production_qc` DISABLE KEYS */;
/*!40000 ALTER TABLE `biz_production_qc` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `biz_purchase`
--

DROP TABLE IF EXISTS `biz_purchase`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_purchase` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `purchase_no` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '进货单号',
  `total_quantity` int NOT NULL DEFAULT '0' COMMENT '进货总数量(按明细行合计)',
  `total_amount` decimal(12,2) NOT NULL DEFAULT '0.00' COMMENT '进货总金额(按明细行合计)',
  `operator_id` bigint DEFAULT NULL COMMENT '操作人ID',
  `operator_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作人姓名(冗余字段)',
  `operation_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作发生时间',
  `remark` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `supplier_id` bigint DEFAULT NULL COMMENT '供应商ID(D123头级:手动进货必填,存量/采购申请渠道单据可空)',
  `biz_status` tinyint NOT NULL DEFAULT '1' COMMENT '业务状态: 1-正常, 2-已作废, 3-红冲单',
  `confirm_status` tinyint NOT NULL DEFAULT '3' COMMENT '入库确认: 1-待到货, 2-待入库确认, 3-已入库',
  `arrive_time` datetime DEFAULT NULL COMMENT '采购到货确认时间',
  `confirmer_id` bigint DEFAULT NULL COMMENT '入库确认人ID(仓储)',
  `confirmer_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '入库确认人姓名',
  `confirm_time` datetime DEFAULT NULL COMMENT '仓储确认入库时间',
  `source_id` bigint DEFAULT NULL COMMENT '红冲来源单ID',
  `void_time` datetime DEFAULT NULL COMMENT '作废时间',
  `void_reason` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '作废原因',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_purchase_no` (`purchase_no`),
  KEY `idx_biz_status` (`biz_status`),
  KEY `idx_purchase_confirm_status` (`confirm_status`),
  KEY `idx_source_id` (`source_id`),
  KEY `idx_operator_id` (`operator_id`),
  KEY `idx_operation_time` (`operation_time`),
  KEY `idx_is_deleted` (`is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='进货表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `biz_purchase`
--

LOCK TABLES `biz_purchase` WRITE;
/*!40000 ALTER TABLE `biz_purchase` DISABLE KEYS */;
/*!40000 ALTER TABLE `biz_purchase` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `biz_purchase_detail`
--

DROP TABLE IF EXISTS `biz_purchase_detail`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_purchase_detail` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `purchase_id` bigint NOT NULL COMMENT '进货单ID(biz_purchase.id)',
  `goods_id` bigint NOT NULL COMMENT '商品ID',
  `goods_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '商品名称(冗余快照)',
  `spec` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '规格(冗余快照)',
  `material` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '材质(冗余快照)',
  `quantity` int NOT NULL COMMENT '行进货数量',
  `unit_price` decimal(10,2) NOT NULL COMMENT '行进货单价',
  `total_price` decimal(10,2) NOT NULL COMMENT '行总金额',
  `supplier_id` bigint DEFAULT NULL COMMENT 'D131 行级供应商ID(手动单=头级统一填入;申请单=到货提交逐行选定)',
  `sort_no` int NOT NULL DEFAULT '1' COMMENT '行序号(同单从1递增)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_purchase_goods` (`purchase_id`,`goods_id`),
  KEY `idx_goods_id` (`goods_id`),
  KEY `idx_is_deleted` (`is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='进货明细行表(同一物料一单仅一行)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `biz_purchase_detail`
--

LOCK TABLES `biz_purchase_detail` WRITE;
/*!40000 ALTER TABLE `biz_purchase_detail` DISABLE KEYS */;
/*!40000 ALTER TABLE `biz_purchase_detail` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `biz_purchase_request`
--

DROP TABLE IF EXISTS `biz_purchase_request`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_purchase_request` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `request_no` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '采购申请单号(PR开头)',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态: 1-待采购, 2-采购中(部分入库派生文案「部分入库」,不另设状态值), 3-已入库, 4-已驳回, 5-待入库确认, 6-已撤销(终态,会话66)',
  `freeze_exempt` tinyint NOT NULL DEFAULT '0' COMMENT '销售冻结豁免(D114): 0-不豁免, 1-生产终止未勾选撤销→豁免销售冻结(采购可继续认领/到货/入库)',
  `source_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'warehouse' COMMENT '来源: production-生产缺料补料, warehouse-仓储手动',
  `production_order_id` bigint DEFAULT NULL COMMENT '来源生产任务单id(仅production来源有值)',
  `applicant_id` bigint NOT NULL COMMENT '申请人ID(仓储管理员)',
  `applicant_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '申请人姓名(冗余字段)',
  `operator_id` bigint DEFAULT NULL COMMENT '采购处理人ID(采购管理员)',
  `operator_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '采购处理人姓名(冗余字段)',
  `operation_time` datetime DEFAULT NULL COMMENT '认领(转采购中)时间',
  `arrive_time` datetime DEFAULT NULL COMMENT '采购到货提交时间',
  `receive_time` datetime DEFAULT NULL COMMENT '入库完成时间',
  `confirmer_id` bigint DEFAULT NULL COMMENT '入库确认人ID(仓储管理员)',
  `confirmer_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '入库确认人姓名',
  `confirm_time` datetime DEFAULT NULL COMMENT '仓储确认入库时间',
  `reject_reason` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '驳回原因',
  `revoke_reason` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '撤销原因(会话66): 一键撤销自动生成/申请人自行撤销',
  `revoker_id` bigint DEFAULT NULL COMMENT '撤销人ID(申请人本人或生产管理员,会话66)',
  `revoker_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '撤销人姓名(冗余,会话66)',
  `revoke_time` datetime DEFAULT NULL COMMENT '撤销时间(会话66)',
  `remark` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_request_no` (`request_no`),
  KEY `idx_pr_status` (`status`),
  KEY `idx_pr_applicant` (`applicant_id`),
  KEY `idx_pr_operator` (`operator_id`),
  KEY `idx_pr_is_deleted` (`is_deleted`),
  KEY `idx_pr_source` (`source_type`),
  KEY `idx_pr_production_order` (`production_order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采购申请单主表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `biz_purchase_request`
--

LOCK TABLES `biz_purchase_request` WRITE;
/*!40000 ALTER TABLE `biz_purchase_request` DISABLE KEYS */;
/*!40000 ALTER TABLE `biz_purchase_request` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `biz_purchase_request_detail`
--

DROP TABLE IF EXISTS `biz_purchase_request_detail`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_purchase_request_detail` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `request_id` bigint NOT NULL COMMENT '采购申请单主表ID',
  `goods_id` bigint DEFAULT NULL COMMENT '物料id(草稿可空, 转正时仓储补齐)',
  `bom_detail_id` bigint DEFAULT NULL COMMENT '对应BOM明细id(确认入库时回挂goods_id)',
  `goods_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '商品名称(冗余字段)',
  `spec` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '规格快照(生产补料提交时自BOM行带入)',
  `material` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '材质快照(生产补料提交时自BOM行带入)',
  `remark` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注快照(生产补料提交时自BOM行带入)',
  `is_new_material` tinyint NOT NULL DEFAULT '0' COMMENT '新物料标记: 0-已有物料缺口, 1-未知物料自动建档(详情分组展示用)',
  `quantity` int NOT NULL COMMENT '申请采购数量',
  `expected_arrival_time` datetime DEFAULT NULL COMMENT '预计到货时间(采购认领时按行填写,采购中可改,D61)',
  `arrival_remark` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '到货备注(供应商/发货方式等采购口径,与remark物料描述快照独立,D61)',
  `arrive_quantity` int DEFAULT NULL COMMENT '到货数量(采购到货提交时填写,确认入库按此数量加库存)',
  `unit_price` decimal(10,2) DEFAULT NULL COMMENT '采购单价(到货时填写)',
  `supplier_id` bigint DEFAULT NULL COMMENT 'D131 行级供应商ID(到货提交时选定,确认入库复制到进货明细行)',
  `receive_status` tinyint NOT NULL DEFAULT '1' COMMENT 'D120 行级接收状态: 1-待到货, 2-本批待入库确认, 3-已入库',
  `arrive_batch_no` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'D120 到货批次号(同批同号,如B1/B2;驳回/撤回按批)',
  `arrive_batch_time` datetime DEFAULT NULL COMMENT 'D120 本批到货提交时间(时间线按批展示)',
  `receive_batch_time` datetime DEFAULT NULL COMMENT 'D120 本批入库确认时间(时间线按批展示)',
  `sort_no` int NOT NULL DEFAULT '0' COMMENT '行序号',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  PRIMARY KEY (`id`),
  KEY `idx_prd_request_id` (`request_id`),
  KEY `idx_prd_goods_id` (`goods_id`),
  KEY `idx_prd_is_deleted` (`is_deleted`),
  KEY `idx_prd_batch_no` (`arrive_batch_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采购申请单明细表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `biz_purchase_request_detail`
--

LOCK TABLES `biz_purchase_request_detail` WRITE;
/*!40000 ALTER TABLE `biz_purchase_request_detail` DISABLE KEYS */;
/*!40000 ALTER TABLE `biz_purchase_request_detail` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `biz_purchase_return`
--

DROP TABLE IF EXISTS `biz_purchase_return`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_purchase_return` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `return_no` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '退货单号',
  `source_purchase_id` bigint DEFAULT NULL COMMENT '来源进货单ID',
  `source_purchase_no` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '来源进货单号',
  `total_quantity` int NOT NULL DEFAULT '0' COMMENT '退货总数量(按明细行合计)',
  `total_amount` decimal(12,2) NOT NULL DEFAULT '0.00' COMMENT '退货总金额(按明细行合计)',
  `operator_id` bigint DEFAULT NULL COMMENT '操作人ID',
  `operator_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作人姓名(冗余字段)',
  `operation_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作发生时间',
  `remark` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `biz_status` tinyint NOT NULL DEFAULT '1' COMMENT '业务状态: 1-正常, 2-已作废, 3-红冲单',
  `confirm_status` tinyint NOT NULL DEFAULT '3' COMMENT '退货确认: 1-待出库确认, 2-待退货确认, 3-已退货',
  `confirmer_id` bigint DEFAULT NULL COMMENT '出库确认人ID(仓储)',
  `confirmer_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '出库确认人姓名',
  `confirm_time` datetime DEFAULT NULL COMMENT '仓储确认出库时间',
  `completer_id` bigint DEFAULT NULL COMMENT '退货完成确认人ID(采购)',
  `completer_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '退货完成确认人姓名',
  `complete_time` datetime DEFAULT NULL COMMENT '采购确认退货成功时间',
  `source_id` bigint DEFAULT NULL COMMENT '红冲来源单ID',
  `void_time` datetime DEFAULT NULL COMMENT '作废时间',
  `void_reason` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '作废原因',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_return_no` (`return_no`),
  KEY `idx_source_purchase_id` (`source_purchase_id`),
  KEY `idx_source_purchase_no` (`source_purchase_no`),
  KEY `idx_biz_status` (`biz_status`),
  KEY `idx_return_confirm_status` (`confirm_status`),
  KEY `idx_source_id` (`source_id`),
  KEY `idx_operator_id` (`operator_id`),
  KEY `idx_operation_time` (`operation_time`),
  KEY `idx_is_deleted` (`is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='退货表(商品退给供应商)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `biz_purchase_return`
--

LOCK TABLES `biz_purchase_return` WRITE;
/*!40000 ALTER TABLE `biz_purchase_return` DISABLE KEYS */;
/*!40000 ALTER TABLE `biz_purchase_return` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `biz_purchase_return_detail`
--

DROP TABLE IF EXISTS `biz_purchase_return_detail`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_purchase_return_detail` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `return_id` bigint NOT NULL COMMENT '退货单ID(biz_purchase_return.id)',
  `source_purchase_id` bigint NOT NULL COMMENT '来源进货单ID(行级冗余)',
  `source_detail_id` bigint NOT NULL COMMENT '来源进货明细行ID(biz_purchase_detail.id)',
  `goods_id` bigint NOT NULL COMMENT '商品ID',
  `goods_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '商品名称(冗余快照)',
  `spec` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '规格(冗余快照)',
  `material` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '材质(冗余快照)',
  `quantity` int NOT NULL COMMENT '行退货数量',
  `unit_price` decimal(10,2) NOT NULL COMMENT '行退货单价',
  `total_price` decimal(10,2) NOT NULL COMMENT '行总金额',
  `sort_no` int NOT NULL DEFAULT '1' COMMENT '行序号(同单从1递增)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_return_source_line` (`return_id`,`source_detail_id`),
  KEY `idx_source_purchase_id` (`source_purchase_id`),
  KEY `idx_source_detail_id` (`source_detail_id`),
  KEY `idx_goods_id` (`goods_id`),
  KEY `idx_is_deleted` (`is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='进货退货明细行表(一退货单N行，按进货明细行退)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `biz_purchase_return_detail`
--

LOCK TABLES `biz_purchase_return_detail` WRITE;
/*!40000 ALTER TABLE `biz_purchase_return_detail` DISABLE KEYS */;
/*!40000 ALTER TABLE `biz_purchase_return_detail` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `biz_sales`
--

DROP TABLE IF EXISTS `biz_sales`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_sales` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `sales_no` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '销售单号',
  `total_quantity` int NOT NULL DEFAULT '0' COMMENT '销售总数量(建单按明细行合计，单据不可编辑)',
  `total_amount` decimal(12,2) NOT NULL DEFAULT '0.00' COMMENT '销售总金额(建单按明细行合计，单据不可编辑)',
  `operator_id` bigint DEFAULT NULL COMMENT '操作人ID',
  `operator_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作人姓名(冗余字段)',
  `operation_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作发生时间',
  `remark` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `biz_status` tinyint NOT NULL DEFAULT '1' COMMENT '业务状态: 1-正常, 2-已作废, 3-红冲单(停用), 4-已终止(全部明细行终止派生,ADR-0019)',
  `source_id` bigint DEFAULT NULL COMMENT '红冲来源单ID',
  `void_time` datetime DEFAULT NULL COMMENT '作废时间',
  `void_reason` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '作废原因',
  `confirm_status` tinyint NOT NULL DEFAULT '2' COMMENT '仓库确认状态: 1-待仓库确认, 2-已确认出库',
  `confirm_time` datetime DEFAULT NULL COMMENT '仓库确认出库时间',
  `confirmer_id` bigint DEFAULT NULL COMMENT '确认人ID(仓储管理员)',
  `confirmer_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '确认人姓名(冗余字段)',
  `customer_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '客户公司名(对齐下单文档)',
  `contract_no` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '合同编号(对齐下单文档)',
  `customer_contact_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '客户联系人姓名(D128选填,自由文本)',
  `customer_phone` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '客户手机号(D128选填,不做格式校验)',
  `tax_included` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否含税: 0-不含税, 1-含税(仅记录标志)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sales_no` (`sales_no`),
  KEY `idx_biz_status` (`biz_status`),
  KEY `idx_confirm_status` (`confirm_status`),
  KEY `idx_source_id` (`source_id`),
  KEY `idx_operator_id` (`operator_id`),
  KEY `idx_operation_time` (`operation_time`),
  KEY `idx_is_deleted` (`is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='销售表(头单，成品行见biz_sales_detail)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `biz_sales`
--

LOCK TABLES `biz_sales` WRITE;
/*!40000 ALTER TABLE `biz_sales` DISABLE KEYS */;
/*!40000 ALTER TABLE `biz_sales` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `biz_sales_detail`
--

DROP TABLE IF EXISTS `biz_sales_detail`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_sales_detail` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `sales_id` bigint NOT NULL COMMENT '销售单ID(biz_sales.id)',
  `goods_id` bigint NOT NULL COMMENT '商品ID',
  `goods_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '商品名称(冗余字段)',
  `quantity` int NOT NULL COMMENT '行销售数量',
  `unit_price` decimal(10,2) NOT NULL COMMENT '行销售单价',
  `cost_unit_price` decimal(10,2) DEFAULT NULL COMMENT '成本单价快照',
  `cost_total_price` decimal(12,2) DEFAULT NULL COMMENT '成本总额快照',
  `cost_source` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '成本来源: RECENT_PURCHASE/GOODS_PRICE/ZERO_FALLBACK',
  `total_price` decimal(10,2) NOT NULL COMMENT '行总金额',
  `sort_no` int NOT NULL DEFAULT '1' COMMENT '行序号(同单从1递增)',
  `terminate_status` tinyint NOT NULL DEFAULT '1' COMMENT '行终止状态: 1-正常, 2-已终止(需求一 Q21)',
  `terminate_reason` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '行终止原因(必填留痕,对齐生产终止口径)',
  `terminate_time` datetime DEFAULT NULL COMMENT '行终止时间',
  `terminator_id` bigint DEFAULT NULL COMMENT '行终止操作人ID',
  `terminator_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '行终止操作人姓名',
  `split_status` tinyint DEFAULT NULL COMMENT '成品处置状态(行终止后,ADR-0020): NULL-未触发(旧数据/未终止行), 1-待处置, 2-已保留成品, 3-已发起拆分, 4-拆分完成(含放弃回库完成)',
  `split_order_id` bigint DEFAULT NULL COMMENT '当前关联拆分单ID(已发起拆分后,ADR-0020)',
  `split_keep_time` datetime DEFAULT NULL COMMENT '保留成品时间(ADR-0020)',
  `split_keep_by` bigint DEFAULT NULL COMMENT '保留成品操作人ID(ADR-0020)',
  `split_keep_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '保留成品操作人姓名(冗余,ADR-0020)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sales_goods` (`sales_id`,`goods_id`),
  KEY `idx_goods_id` (`goods_id`),
  KEY `idx_is_deleted` (`is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='销售明细行表(同一成品一单仅一行)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `biz_sales_detail`
--

LOCK TABLES `biz_sales_detail` WRITE;
/*!40000 ALTER TABLE `biz_sales_detail` DISABLE KEYS */;
/*!40000 ALTER TABLE `biz_sales_detail` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `biz_sales_return`
--

DROP TABLE IF EXISTS `biz_sales_return`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_sales_return` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `return_no` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '退货单号',
  `source_sales_id` bigint DEFAULT NULL COMMENT '来源销售单ID',
  `source_sales_no` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '来源销售单号',
  `customer_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '退货公司名快照(从来源销售单带入)',
  `customer_contact_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '客户联系人姓名快照(D128,可从来源销售单带出可改)',
  `customer_phone` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '客户手机号快照(D128,可从来源销售单带出可改)',
  `total_quantity` int NOT NULL DEFAULT '0' COMMENT '退货总数量(建单按明细行合计，单据不可编辑)',
  `total_amount` decimal(12,2) NOT NULL DEFAULT '0.00' COMMENT '退货总金额(建单按明细行合计，单据不可编辑)',
  `operator_id` bigint DEFAULT NULL COMMENT '操作人ID',
  `operator_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作人姓名(冗余字段)',
  `operation_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作发生时间',
  `remark` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注(退货原因)',
  `biz_status` tinyint NOT NULL DEFAULT '1' COMMENT '业务状态: 1-正常, 2-已作废, 3-红冲单',
  `source_id` bigint DEFAULT NULL COMMENT '红冲来源单ID',
  `void_time` datetime DEFAULT NULL COMMENT '作废时间',
  `void_reason` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '作废原因',
  `confirm_status` tinyint NOT NULL DEFAULT '2' COMMENT '仓库确认状态: 1-待仓库确认, 2-已确认入库',
  `confirm_time` datetime DEFAULT NULL COMMENT '仓库确认入库时间',
  `confirmer_id` bigint DEFAULT NULL COMMENT '确认人ID(仓储管理员)',
  `confirmer_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '确认人姓名(冗余字段)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_return_no` (`return_no`),
  KEY `idx_source_sales_id` (`source_sales_id`),
  KEY `idx_return_confirm_status` (`confirm_status`),
  KEY `idx_source_sales_no` (`source_sales_no`),
  KEY `idx_biz_status` (`biz_status`),
  KEY `idx_source_id` (`source_id`),
  KEY `idx_operator_id` (`operator_id`),
  KEY `idx_operation_time` (`operation_time`),
  KEY `idx_is_deleted` (`is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='客退表(头单，退货行见biz_sales_return_detail)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `biz_sales_return`
--

LOCK TABLES `biz_sales_return` WRITE;
/*!40000 ALTER TABLE `biz_sales_return` DISABLE KEYS */;
/*!40000 ALTER TABLE `biz_sales_return` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `biz_sales_return_detail`
--

DROP TABLE IF EXISTS `biz_sales_return_detail`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_sales_return_detail` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `return_id` bigint NOT NULL COMMENT '退货单ID(biz_sales_return.id)',
  `source_sales_detail_id` bigint NOT NULL COMMENT '来源销售明细行ID(biz_sales_detail.id)',
  `goods_id` bigint NOT NULL COMMENT '商品ID',
  `goods_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '商品名称(冗余字段)',
  `quantity` int NOT NULL COMMENT '行退货数量',
  `unit_price` decimal(10,2) NOT NULL COMMENT '行退货单价',
  `cost_unit_price` decimal(10,2) DEFAULT NULL COMMENT '成本单价快照',
  `cost_total_price` decimal(12,2) DEFAULT NULL COMMENT '成本总额快照',
  `cost_source` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '成本来源: SOURCE_SALE/RECENT_PURCHASE/GOODS_PRICE/ZERO_FALLBACK',
  `total_price` decimal(10,2) NOT NULL COMMENT '行总金额',
  `sort_no` int NOT NULL DEFAULT '1' COMMENT '行序号(同单从1递增)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_return_source_line` (`return_id`,`source_sales_detail_id`),
  KEY `idx_source_detail_id` (`source_sales_detail_id`),
  KEY `idx_goods_id` (`goods_id`),
  KEY `idx_is_deleted` (`is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='客退明细行表(一退货单N行，按销售明细行退)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `biz_sales_return_detail`
--

LOCK TABLES `biz_sales_return_detail` WRITE;
/*!40000 ALTER TABLE `biz_sales_return_detail` DISABLE KEYS */;
/*!40000 ALTER TABLE `biz_sales_return_detail` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `biz_split_order`
--

DROP TABLE IF EXISTS `biz_split_order`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_split_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `split_no` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '拆分单号(SPO开头)',
  `sales_order_id` bigint NOT NULL COMMENT '关联销售单ID',
  `sales_order_no` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '关联销售单号(冗余)',
  `sales_detail_id` bigint NOT NULL COMMENT '关联销售明细行ID',
  `goods_id` bigint NOT NULL COMMENT '成品商品ID',
  `goods_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '成品名称(冗余)',
  `spec` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '成品规格(冗余)',
  `material` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '成品材质(冗余)',
  `quantity` int NOT NULL COMMENT '拆分数量',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态: 1-待生产领取, 2-待仓储确认成品出库, 3-待生产确认收货, 4-拆分中, 5-已完成, 6-已作废, 7-待仓储确认成品回库',
  `initiator_id` bigint NOT NULL COMMENT '发起人ID(仓储管理员)',
  `initiator_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '发起人姓名(冗余)',
  `init_time` datetime DEFAULT NULL COMMENT '发起时间',
  `claim_user_id` bigint DEFAULT NULL COMMENT '生产领取人ID',
  `claim_user_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '生产领取人姓名(冗余)',
  `claim_time` datetime DEFAULT NULL COMMENT '领取时间',
  `outbound_confirm_user_id` bigint DEFAULT NULL COMMENT '仓储确认成品出库人ID',
  `outbound_confirm_user_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '仓储确认成品出库人姓名(冗余)',
  `outbound_confirm_time` datetime DEFAULT NULL COMMENT '仓储确认成品出库时间(此时扣成品库存)',
  `receipt_confirm_user_id` bigint DEFAULT NULL COMMENT '生产确认收货人ID',
  `receipt_confirm_user_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '生产确认收货人姓名(冗余)',
  `receipt_confirm_time` datetime DEFAULT NULL COMMENT '生产确认收货时间',
  `abandon_user_id` bigint DEFAULT NULL COMMENT '生产放弃拆分操作人ID',
  `abandon_user_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '生产放弃拆分操作人姓名(冗余)',
  `abandon_time` datetime DEFAULT NULL COMMENT '生产放弃拆分时间',
  `return_pick_list_id` bigint DEFAULT NULL COMMENT '关联RETURN退料单ID(biz_pick_list,拆分退料专用)',
  `finish_type` tinyint DEFAULT NULL COMMENT '完成方式: 1-退料完成, 2-放弃回库',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_split_no` (`split_no`),
  KEY `idx_so_status` (`status`),
  KEY `idx_so_sales_detail` (`sales_detail_id`),
  KEY `idx_so_is_deleted` (`is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='成品拆分单头表(ADR-0020)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `biz_split_order`
--

LOCK TABLES `biz_split_order` WRITE;
/*!40000 ALTER TABLE `biz_split_order` DISABLE KEYS */;
/*!40000 ALTER TABLE `biz_split_order` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `biz_split_order_detail`
--

DROP TABLE IF EXISTS `biz_split_order_detail`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_split_order_detail` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `split_order_id` bigint NOT NULL COMMENT '拆分单头表ID',
  `goods_id` bigint NOT NULL COMMENT '物料商品ID',
  `goods_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '物料名称(冗余)',
  `spec` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '规格(冗余)',
  `material` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '材质(冗余)',
  `required_quantity` int NOT NULL COMMENT '需求量(BOM×拆分数量)',
  `sort_no` int NOT NULL DEFAULT '0' COMMENT '行序号',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  PRIMARY KEY (`id`),
  KEY `idx_sod_split_order_id` (`split_order_id`),
  KEY `idx_sod_goods_id` (`goods_id`),
  KEY `idx_sod_is_deleted` (`is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='成品拆分单明细表(BOM×数量快照,ADR-0020)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `biz_split_order_detail`
--

LOCK TABLES `biz_split_order_detail` WRITE;
/*!40000 ALTER TABLE `biz_split_order_detail` DISABLE KEYS */;
/*!40000 ALTER TABLE `biz_split_order_detail` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `biz_stocktake`
--

DROP TABLE IF EXISTS `biz_stocktake`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_stocktake` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `stocktake_no` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '盘点单号(ST开头)',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态: 1-盘点中, 2-待审核, 3-已完成, 4-已取消',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `operator_id` bigint DEFAULT NULL COMMENT '建单人ID(仓储管理员)',
  `operator_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '建单人姓名(冗余)',
  `operation_time` datetime DEFAULT NULL COMMENT '建单时间',
  `submit_time` datetime DEFAULT NULL COMMENT '提交审核时间',
  `submitter_id` bigint DEFAULT NULL COMMENT '提交人ID',
  `submitter_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '提交人姓名(冗余)',
  `reviewer_id` bigint DEFAULT NULL COMMENT '审核人ID(仓储管理员)',
  `reviewer_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '审核人姓名(冗余)',
  `review_time` datetime DEFAULT NULL COMMENT '审核时间',
  `reject_reason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '驳回原因',
  `cancel_reason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '取消原因',
  `canceler_id` bigint DEFAULT NULL COMMENT '取消人ID',
  `canceler_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '取消人姓名(冗余)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stocktake_no` (`stocktake_no`),
  KEY `idx_stocktake_status` (`status`),
  KEY `idx_stocktake_is_deleted` (`is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存盘点单';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `biz_stocktake`
--

LOCK TABLES `biz_stocktake` WRITE;
/*!40000 ALTER TABLE `biz_stocktake` DISABLE KEYS */;
/*!40000 ALTER TABLE `biz_stocktake` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `biz_stocktake_detail`
--

DROP TABLE IF EXISTS `biz_stocktake_detail`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_stocktake_detail` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `stocktake_id` bigint NOT NULL COMMENT '盘点单主表ID',
  `goods_id` bigint NOT NULL COMMENT '商品ID',
  `goods_code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '商品编码(建单快照, 导出/导入匹配键)',
  `goods_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '商品名称(建单快照)',
  `spec` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '规格(建单快照)',
  `material` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '材质(建单快照)',
  `unit` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '单位(建单快照)',
  `goods_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '商品类型快照: material-物料, product-成品',
  `book_qty` int NOT NULL COMMENT '建单账面快照(展示参考，非差异基准)',
  `assignee_id` bigint DEFAULT NULL COMMENT '负责人ID',
  `assignee_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '负责人姓名(冗余)',
  `actual_qty` int DEFAULT NULL COMMENT '实盘数(NULL=未盘)',
  `counter_id` bigint DEFAULT NULL COMMENT '实际录入人ID',
  `counter_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '实际录入人姓名(冗余)',
  `count_time` datetime DEFAULT NULL COMMENT '录入时间',
  `final_book_qty` int DEFAULT NULL COMMENT '生效时账面(审核时写)',
  `diff_qty` int DEFAULT NULL COMMENT '差异=实盘-生效时账面(审核时写)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  PRIMARY KEY (`id`),
  KEY `idx_sd_stocktake_id` (`stocktake_id`),
  KEY `idx_sd_goods_id` (`goods_id`),
  KEY `idx_sd_is_deleted` (`is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存盘点单明细表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `biz_stocktake_detail`
--

LOCK TABLES `biz_stocktake_detail` WRITE;
/*!40000 ALTER TABLE `biz_stocktake_detail` DISABLE KEYS */;
/*!40000 ALTER TABLE `biz_stocktake_detail` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_config`
--

DROP TABLE IF EXISTS `sys_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `config_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '参数键(唯一)',
  `config_value` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '参数值',
  `config_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '参数名称',
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `updater_id` bigint DEFAULT NULL COMMENT '最近更新人ID',
  `updater_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最近更新人姓名',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_config_key` (`config_key`),
  KEY `idx_is_deleted` (`is_deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统参数表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_config`
--

LOCK TABLES `sys_config` WRITE;
/*!40000 ALTER TABLE `sys_config` DISABLE KEYS */;
INSERT INTO `sys_config` VALUES (1,'price_deviation_threshold','0.05','销售价格偏离阈值','销售单价偏离标准售价超过此比例需销售管理员审批(0.05=5%)',11,'超级管理员','2026-09-20 09:19:39','2026-10-08 21:13:29',0);
/*!40000 ALTER TABLE `sys_config` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_dept`
--

DROP TABLE IF EXISTS `sys_dept`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_dept` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `dept_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '部门名称',
  `dept_code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '部门编码',
  `leader` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '部门负责人',
  `phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '联系电话',
  `description` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述',
  `status` tinyint NOT NULL DEFAULT '2' COMMENT '状态: 1-待审批, 2-已生效, 3-已驳回',
  `requester_id` bigint DEFAULT NULL COMMENT '提交人ID',
  `requester_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '提交人姓名',
  `approver_id` bigint DEFAULT NULL COMMENT '审批人ID',
  `approver_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '审批人姓名',
  `approval_remark` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '审批备注',
  `approved_at` datetime DEFAULT NULL COMMENT '审批通过时间',
  `rejected_at` datetime DEFAULT NULL COMMENT '审批驳回时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dept_code` (`dept_code`),
  KEY `idx_status_rejected_at` (`status`,`rejected_at`),
  KEY `idx_is_deleted` (`is_deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='部门表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_dept`
--

LOCK TABLES `sys_dept` WRITE;
/*!40000 ALTER TABLE `sys_dept` DISABLE KEYS */;
INSERT INTO `sys_dept` VALUES (1,'财务部','finance','孙经理','021-12345682','负责财务报表与经营分析',2,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-07-05 09:05:28','2026-07-05 09:05:28',0),(2,'销售部','sales','李经理','021-12345680','负责销售业务管理',2,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-07-05 09:05:28','2026-07-05 09:05:28',0),(3,'仓储部','warehouse','赵经理','021-12345681','负责仓储、库存与作废审批管理',2,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-07-05 09:05:28','2026-07-05 09:05:28',0),(4,'采购部','purchase','王经理','021-12345679','负责采购与退货业务管理',2,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-07-05 09:05:28','2026-07-05 09:05:28',0),(5,'人事部','hr','张总','021-12345678','负责组织与人事管理',2,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-07-05 09:05:28','2026-07-05 09:05:28',0),(6,'系统管理部','system_management','平台管理员','021-12345677','用于展示系统管理员与超级管理员信息',2,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-07-05 09:05:28','2026-07-05 09:05:28',0),(7,'生产研发部','production','生产负责人','021-12345683','负责生产与研发（BOM 建档、生产任务、质检）',2,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-08-31 11:11:25','2026-08-31 11:11:25',0);
/*!40000 ALTER TABLE `sys_dept` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_employee`
--

DROP TABLE IF EXISTS `sys_employee`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_employee` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint DEFAULT NULL COMMENT '关联用户ID',
  `emp_code` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '员工工号',
  `emp_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '员工姓名',
  `dept_id` bigint NOT NULL COMMENT '部门ID',
  `position` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '职位',
  `phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
  `email` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '邮箱',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态: 1-在职, 0-离职',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_emp_code` (`emp_code`),
  UNIQUE KEY `uk_user_id` (`user_id`),
  KEY `idx_dept_id` (`dept_id`),
  KEY `idx_dept_status` (`dept_id`,`status`),
  KEY `idx_is_deleted` (`is_deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='员工表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_employee`
--

LOCK TABLES `sys_employee` WRITE;
/*!40000 ALTER TABLE `sys_employee` DISABLE KEYS */;
INSERT INTO `sys_employee` VALUES (1,10,'EMP001','财务员工',1,'财务专员','13800138009','finance_employee@warehouse.com',1,'2026-07-05 09:05:28','2026-07-05 09:05:28',0),(2,4,'EMP002','李四',2,'销售代表','13800138003','sales_employee@warehouse.com',1,'2026-07-05 09:05:28','2026-07-05 09:05:28',0),(3,9,'EMP003','仓储员工',3,'仓管员','13800138008','warehouse_employee@warehouse.com',1,'2026-07-05 09:05:28','2026-07-05 09:05:28',0),(4,8,'EMP004','采购员工',4,'采购专员','13800138007','purchase_employee@warehouse.com',1,'2026-07-05 09:05:28','2026-07-05 09:05:28',0),(5,7,'EMP005','人事员工',5,'人事专员','13800138006','hr_employee@warehouse.com',1,'2026-07-05 09:05:28','2026-07-05 09:05:28',0),(7,16,'EMP260913122017285','生产员工',7,'普通员工','13800138012','production_employee@warehouse.com',1,'2026-09-13 12:20:17','2026-09-13 12:20:17',0);
/*!40000 ALTER TABLE `sys_employee` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_error_log`
--

DROP TABLE IF EXISTS `sys_error_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_error_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `request_uri` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '请求路径',
  `method` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '请求方法',
  `status_code` int DEFAULT NULL COMMENT '响应状态码',
  `error_type` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '异常类型',
  `message` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '异常摘要',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
  PRIMARY KEY (`id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统错误日志表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_error_log`
--

LOCK TABLES `sys_error_log` WRITE;
/*!40000 ALTER TABLE `sys_error_log` DISABLE KEYS */;
/*!40000 ALTER TABLE `sys_error_log` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_ip_policy`
--

DROP TABLE IF EXISTS `sys_ip_policy`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_ip_policy` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `policy_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '策略名称',
  `ip_cidr` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'IP或CIDR网段',
  `allow_flag` tinyint NOT NULL DEFAULT '1' COMMENT '是否允许: 1-允许, 0-拒绝',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态: 1-启用, 0-禁用',
  `priority` int NOT NULL DEFAULT '100' COMMENT '优先级(数值越小优先级越高)',
  `remark` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  PRIMARY KEY (`id`),
  KEY `idx_ip_cidr` (`ip_cidr`),
  KEY `idx_priority_status` (`priority`,`status`),
  KEY `idx_is_deleted` (`is_deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='IP策略表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_ip_policy`
--

LOCK TABLES `sys_ip_policy` WRITE;
/*!40000 ALTER TABLE `sys_ip_policy` DISABLE KEYS */;
INSERT INTO `sys_ip_policy` VALUES (1,'本机回环地址','127.0.0.1/32',1,1,1,'开发环境白名单','2026-07-05 09:05:28','2026-07-05 09:05:28',0);
/*!40000 ALTER TABLE `sys_ip_policy` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_login_log`
--

DROP TABLE IF EXISTS `sys_login_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_login_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint DEFAULT NULL COMMENT '用户ID',
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
  `ip` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '登录IP',
  `user_agent` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '客户端标识',
  `success_flag` tinyint NOT NULL DEFAULT '1' COMMENT '登录结果: 1-成功, 0-失败',
  `fail_reason` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '失败原因',
  `login_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登录时间',
  PRIMARY KEY (`id`),
  KEY `idx_username` (`username`),
  KEY `idx_ip` (`ip`),
  KEY `idx_login_time` (`login_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='登录日志表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_login_log`
--

LOCK TABLES `sys_login_log` WRITE;
/*!40000 ALTER TABLE `sys_login_log` DISABLE KEYS */;
/*!40000 ALTER TABLE `sys_login_log` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_message`
--

DROP TABLE IF EXISTS `sys_message`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_message` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `recipient_user_id` bigint NOT NULL COMMENT '接收人用户ID',
  `recipient_dept_id` bigint DEFAULT NULL COMMENT '接收人所属部门ID',
  `title` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '消息标题',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '消息正文',
  `is_read` tinyint NOT NULL DEFAULT '0' COMMENT '是否已读: 0-未读, 1-已读',
  `read_time` datetime DEFAULT NULL COMMENT '已读时间',
  `biz_type` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '关联业务类型: sales/sales_return 等(用于按单据撤销待办)',
  `biz_id` bigint DEFAULT NULL COMMENT '关联业务单据ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  `target_route` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '跳转目标路由（前端优先使用，空则按 bizType 映射兜底）',
  PRIMARY KEY (`id`),
  KEY `idx_recipient_user_read` (`recipient_user_id`,`is_read`,`create_time`),
  KEY `idx_recipient_dept` (`recipient_dept_id`),
  KEY `idx_is_deleted` (`is_deleted`),
  KEY `idx_biz` (`biz_type`,`biz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='站内消息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_message`
--

LOCK TABLES `sys_message` WRITE;
/*!40000 ALTER TABLE `sys_message` DISABLE KEYS */;
/*!40000 ALTER TABLE `sys_message` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_notice`
--

DROP TABLE IF EXISTS `sys_notice`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_notice` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `title` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '公告标题',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '公告内容',
  `target_role` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'all' COMMENT '受众角色: admin/employee/all',
  `target_dept_id` bigint DEFAULT NULL COMMENT '目标部门ID，空表示该角色全体',
  `publisher` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '发布人',
  `publish_time` datetime DEFAULT NULL COMMENT '发布时间',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态: 1-已发布, 0-草稿',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  PRIMARY KEY (`id`),
  KEY `idx_target_role_dept_status` (`target_role`,`target_dept_id`,`status`),
  KEY `idx_status` (`status`),
  KEY `idx_publish_time` (`publish_time`),
  KEY `idx_is_deleted` (`is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='公告表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_notice`
--

LOCK TABLES `sys_notice` WRITE;
/*!40000 ALTER TABLE `sys_notice` DISABLE KEYS */;
/*!40000 ALTER TABLE `sys_notice` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_operation_log`
--

DROP TABLE IF EXISTS `sys_operation_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_operation_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint DEFAULT NULL COMMENT '操作人ID',
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作人用户名',
  `module` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '模块名称',
  `action` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作动作',
  `target_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '目标类型',
  `target_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '目标ID',
  `detail` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作描述（人话摘要，写入时由 @AuditLog detail 表达式生成）',
  `before_data` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '请求参数快照(JSON)',
  `after_data` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '返回结果快照(JSON)',
  `request_uri` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '请求路径',
  `ip` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '来源IP',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
  PRIMARY KEY (`id`),
  KEY `idx_module_action` (`module`,`action`),
  KEY `idx_username` (`username`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作日志表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_operation_log`
--

LOCK TABLES `sys_operation_log` WRITE;
/*!40000 ALTER TABLE `sys_operation_log` DISABLE KEYS */;
/*!40000 ALTER TABLE `sys_operation_log` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_user`
--

DROP TABLE IF EXISTS `sys_user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户名',
  `password` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '密码(BCrypt加密)',
  `real_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '真实姓名',
  `role` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '角色: superadmin-超级管理员, admin-管理员, employee-普通用户',
  `dept_id` bigint DEFAULT NULL COMMENT '所属部门ID，superadmin 允许为空',
  `is_superadmin` tinyint GENERATED ALWAYS AS ((case when (`role` = _utf8mb4'superadmin') then 1 else NULL end)) STORED COMMENT '超级管理员唯一约束辅助列',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态: 1-启用, 0-禁用',
  `phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
  `email` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '邮箱',
  `current_login_time` datetime DEFAULT NULL COMMENT '本次登录时间',
  `last_login_time` datetime DEFAULT NULL COMMENT '上次登录时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  UNIQUE KEY `uk_only_one_superadmin` (`is_superadmin`),
  KEY `idx_role_dept_status` (`role`,`dept_id`,`status`),
  KEY `idx_dept_id` (`dept_id`),
  KEY `idx_is_deleted` (`is_deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=19 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_user`
--

LOCK TABLES `sys_user` WRITE;
/*!40000 ALTER TABLE `sys_user` DISABLE KEYS */;
INSERT INTO `sys_user` (`id`, `username`, `password`, `real_name`, `role`, `dept_id`, `status`, `phone`, `email`, `current_login_time`, `last_login_time`, `create_time`, `update_time`, `is_deleted`) VALUES (1,'hr_admin','$2a$10$yxRor5xgip624/ulGHfyxerZlyhK39FpoVlaTIeBmi1DTAGFD6tl6','人事管理员','admin',5,1,'13800138000','hr_admin@warehouse.com','2026-10-03 12:06:54','2026-09-23 13:29:15','2026-07-05 09:05:28','2026-10-03 12:06:54',0),(2,'purchase_admin','$2a$10$yxRor5xgip624/ulGHfyxerZlyhK39FpoVlaTIeBmi1DTAGFD6tl6','采购管理员','admin',4,1,'13800138001','purchase_admin@warehouse.com','2026-10-03 12:03:15','2026-10-03 11:33:05','2026-07-05 09:05:28','2026-10-03 12:03:15',0),(3,'sales_admin','$2a$10$yxRor5xgip624/ulGHfyxerZlyhK39FpoVlaTIeBmi1DTAGFD6tl6','销售管理员','admin',2,1,'13800138002','sales_admin@warehouse.com','2026-10-08 21:15:01','2026-10-08 21:14:05','2026-07-05 09:05:28','2026-10-08 21:15:01',0),(4,'sales_employee','$2a$10$yxRor5xgip624/ulGHfyxerZlyhK39FpoVlaTIeBmi1DTAGFD6tl6','李四','employee',2,1,'13800138003','sales_employee@warehouse.com','2026-10-08 20:53:48','2026-09-16 15:48:45','2026-07-05 09:05:28','2026-10-08 20:53:48',0),(5,'warehouse_admin','$2a$10$yxRor5xgip624/ulGHfyxerZlyhK39FpoVlaTIeBmi1DTAGFD6tl6','仓储管理员','admin',3,1,'13800138004','warehouse_admin@warehouse.com','2026-10-08 21:15:24','2026-10-08 20:51:50','2026-07-05 09:05:28','2026-10-08 21:15:24',0),(6,'finance_admin','$2a$10$yxRor5xgip624/ulGHfyxerZlyhK39FpoVlaTIeBmi1DTAGFD6tl6','财务管理员','admin',1,1,'13800138005','finance_admin@warehouse.com','2026-10-03 12:12:57','2026-09-23 13:30:37','2026-07-05 09:05:28','2026-10-03 12:12:57',0),(7,'hr_employee','$2a$10$yxRor5xgip624/ulGHfyxerZlyhK39FpoVlaTIeBmi1DTAGFD6tl6','人事员工','employee',5,1,'13800138006','hr_employee@warehouse.com','2026-09-16 15:48:46','2026-09-14 15:36:48','2026-07-05 09:05:28','2026-09-16 15:48:46',0),(8,'purchase_employee','$2a$10$yxRor5xgip624/ulGHfyxerZlyhK39FpoVlaTIeBmi1DTAGFD6tl6','采购员工','employee',4,1,'13800138007','purchase_employee@warehouse.com','2026-09-19 13:04:53','2026-09-19 13:01:32','2026-07-05 09:05:28','2026-09-19 13:04:53',0),(9,'warehouse_employee','$2a$10$yxRor5xgip624/ulGHfyxerZlyhK39FpoVlaTIeBmi1DTAGFD6tl6','仓储员工','employee',3,1,'13800138008','warehouse_employee@warehouse.com','2026-10-03 10:44:49','2026-10-02 11:08:05','2026-07-05 09:05:28','2026-10-03 10:44:49',0),(10,'finance_employee','$2a$10$yxRor5xgip624/ulGHfyxerZlyhK39FpoVlaTIeBmi1DTAGFD6tl6','财务员工','employee',1,1,'13800138009','finance_employee@warehouse.com','2026-09-16 15:48:46','2026-09-14 15:36:48','2026-07-05 09:05:28','2026-09-16 15:48:46',0),(11,'superadmin','$2a$10$yxRor5xgip624/ulGHfyxerZlyhK39FpoVlaTIeBmi1DTAGFD6tl6','超级管理员','superadmin',NULL,1,'13800138010','superadmin@warehouse.com','2026-10-08 21:16:05','2026-10-03 12:07:51','2026-07-05 09:05:28','2026-10-08 21:16:05',0),(15,'production_admin','$2a$10$yxRor5xgip624/ulGHfyxerZlyhK39FpoVlaTIeBmi1DTAGFD6tl6','生产管理员','admin',7,1,'13800138011','production_admin@warehouse.com','2026-10-03 12:02:07','2026-10-03 11:37:21','2026-08-31 11:11:50','2026-10-03 12:02:07',0),(16,'production_employee','$2a$10$yxRor5xgip624/ulGHfyxerZlyhK39FpoVlaTIeBmi1DTAGFD6tl6','生产员工','employee',7,1,'13800138012','production_employee@warehouse.com','2026-09-16 15:48:45','2026-09-14 23:25:46','2026-08-31 11:11:50','2026-09-16 15:48:45',0);
/*!40000 ALTER TABLE `sys_user` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Temporary view structure for view `v_goods_detail`
--

DROP TABLE IF EXISTS `v_goods_detail`;
/*!50001 DROP VIEW IF EXISTS `v_goods_detail`*/;
SET @saved_cs_client     = @@character_set_client;
/*!50503 SET character_set_client = utf8mb4 */;
/*!50001 CREATE VIEW `v_goods_detail` AS SELECT 
 1 AS `id`,
 1 AS `goods_code`,
 1 AS `goods_name`,
 1 AS `category`,
 1 AS `brand`,
 1 AS `supplier_id`,
 1 AS `supplier_name`,
 1 AS `supplier_code`,
 1 AS `purchase_price`,
 1 AS `sale_price`,
 1 AS `stock`,
 1 AS `unit`,
 1 AS `status`,
 1 AS `description`,
 1 AS `create_time`,
 1 AS `update_time`*/;
SET character_set_client = @saved_cs_client;

--
-- Temporary view structure for view `v_purchase_detail`
--

DROP TABLE IF EXISTS `v_purchase_detail`;
/*!50001 DROP VIEW IF EXISTS `v_purchase_detail`*/;
SET @saved_cs_client     = @@character_set_client;
/*!50503 SET character_set_client = utf8mb4 */;
/*!50001 CREATE VIEW `v_purchase_detail` AS SELECT 
 1 AS `detail_id`,
 1 AS `purchase_id`,
 1 AS `purchase_no`,
 1 AS `goods_id`,
 1 AS `goods_name`,
 1 AS `goods_code`,
 1 AS `category`,
 1 AS `brand`,
 1 AS `quantity`,
 1 AS `unit_price`,
 1 AS `total_price`,
 1 AS `total_quantity`,
 1 AS `total_amount`,
 1 AS `biz_status`,
 1 AS `confirm_status`,
 1 AS `operator_id`,
 1 AS `operator_name`,
 1 AS `operator_real_name`,
 1 AS `operation_time`,
 1 AS `remark`,
 1 AS `create_time`,
 1 AS `update_time`*/;
SET character_set_client = @saved_cs_client;

--
-- Temporary view structure for view `v_sales_detail`
--

DROP TABLE IF EXISTS `v_sales_detail`;
/*!50001 DROP VIEW IF EXISTS `v_sales_detail`*/;
SET @saved_cs_client     = @@character_set_client;
/*!50503 SET character_set_client = utf8mb4 */;
/*!50001 CREATE VIEW `v_sales_detail` AS SELECT 
 1 AS `detail_id`,
 1 AS `sales_id`,
 1 AS `sales_no`,
 1 AS `goods_id`,
 1 AS `goods_name`,
 1 AS `goods_code`,
 1 AS `category`,
 1 AS `brand`,
 1 AS `quantity`,
 1 AS `unit_price`,
 1 AS `total_price`,
 1 AS `total_quantity`,
 1 AS `total_amount`,
 1 AS `operator_id`,
 1 AS `operator_name`,
 1 AS `operator_real_name`,
 1 AS `operation_time`,
 1 AS `remark`,
 1 AS `create_time`,
 1 AS `update_time`*/;
SET character_set_client = @saved_cs_client;

--
-- Table structure for table `work_requirement`
--

DROP TABLE IF EXISTS `work_requirement`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `work_requirement` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '要求内容',
  `start_time` datetime NOT NULL COMMENT '要求开始时间',
  `end_time` datetime NOT NULL COMMENT '要求截止时间',
  `target_scope` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'all' COMMENT '对象范围: all-全体部门员工, selected-指定员工',
  `creator_id` bigint NOT NULL COMMENT '创建人用户ID',
  `creator_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '创建人姓名',
  `dept_id` bigint NOT NULL COMMENT '所属部门ID',
  `dept_code` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '所属部门代码',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  PRIMARY KEY (`id`),
  KEY `idx_dept_id` (`dept_id`),
  KEY `idx_creator_id` (`creator_id`),
  KEY `idx_is_deleted` (`is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作要求主表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `work_requirement`
--

LOCK TABLES `work_requirement` WRITE;
/*!40000 ALTER TABLE `work_requirement` DISABLE KEYS */;
/*!40000 ALTER TABLE `work_requirement` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `work_requirement_assign`
--

DROP TABLE IF EXISTS `work_requirement_assign`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `work_requirement_assign` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `requirement_id` bigint NOT NULL COMMENT '关联工作要求ID',
  `employee_user_id` bigint NOT NULL COMMENT '被分配的员工用户ID',
  `employee_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '员工姓名',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '流程状态: 0-待接受, 1-执行中, 2-待审核, 3-已完成, 4-拒收, 5-已驳回',
  `overdue_flag` tinyint NOT NULL DEFAULT '0' COMMENT '是否已超时: 0-否, 1-是',
  `overdue_at` datetime DEFAULT NULL COMMENT '首次超时时间',
  `submitted_on_time` tinyint DEFAULT NULL COMMENT '是否按时提交: 1-按时, 0-逾期, NULL-未提交',
  `overdue_remind_count` int NOT NULL DEFAULT '0' COMMENT '已发送超时提醒次数',
  `last_remind_time` datetime DEFAULT NULL COMMENT '最近一次超时提醒时间',
  `completed_at` datetime DEFAULT NULL COMMENT '最终完成时间',
  `execute_result` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '执行结果文本',
  `reject_count` int NOT NULL DEFAULT '0' COMMENT '驳回次数',
  `accepted_at` datetime DEFAULT NULL COMMENT '接受时间',
  `submitted_at` datetime DEFAULT NULL COMMENT '提交审核时间',
  `reviewed_at` datetime DEFAULT NULL COMMENT '审核完成时间',
  `reviewer_id` bigint DEFAULT NULL COMMENT '审核人ID',
  `reviewer_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '审核人姓名',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_requirement_employee` (`requirement_id`,`employee_user_id`),
  KEY `idx_requirement_id` (`requirement_id`),
  KEY `idx_employee_user_id_status` (`employee_user_id`,`status`),
  KEY `idx_status_overdue_employee` (`status`,`overdue_flag`,`employee_user_id`),
  KEY `idx_is_deleted` (`is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作要求分配表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `work_requirement_assign`
--

LOCK TABLES `work_requirement_assign` WRITE;
/*!40000 ALTER TABLE `work_requirement_assign` DISABLE KEYS */;
/*!40000 ALTER TABLE `work_requirement_assign` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `work_requirement_attachment`
--

DROP TABLE IF EXISTS `work_requirement_attachment`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `work_requirement_attachment` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `assign_id` bigint NOT NULL COMMENT '关联分配记录ID',
  `file_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '原始文件名',
  `file_path` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '服务端存储路径',
  `file_size` bigint DEFAULT NULL COMMENT '文件大小(字节)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  PRIMARY KEY (`id`),
  KEY `idx_assign_id` (`assign_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作要求执行附件表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `work_requirement_attachment`
--

LOCK TABLES `work_requirement_attachment` WRITE;
/*!40000 ALTER TABLE `work_requirement_attachment` DISABLE KEYS */;
/*!40000 ALTER TABLE `work_requirement_attachment` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Dumping events for database 'warehouse_management'
--

--
-- Dumping routines for database 'warehouse_management'
--
/*!50003 DROP PROCEDURE IF EXISTS `sp_purchase_add_stock` */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_0900_ai_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
CREATE DEFINER=`wms_user`@`localhost` PROCEDURE `sp_purchase_add_stock`(
    IN p_goods_id BIGINT,
    IN p_quantity INT,
    OUT p_result INT
)
BEGIN
    DECLARE v_stock INT;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        SET p_result = 0;
    END;

    START TRANSACTION;

    
    UPDATE `base_goods` SET stock = stock + p_quantity WHERE id = p_goods_id;

    
    SELECT stock INTO v_stock FROM `base_goods` WHERE id = p_goods_id;

    COMMIT;
    SET p_result = v_stock;
END ;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;

--
-- Current Database: `warehouse_management`
--

USE `warehouse_management`;

--
-- Final view structure for view `v_goods_detail`
--

/*!50001 DROP VIEW IF EXISTS `v_goods_detail`*/;
/*!50001 SET @saved_cs_client          = @@character_set_client */;
/*!50001 SET @saved_cs_results         = @@character_set_results */;
/*!50001 SET @saved_col_connection     = @@collation_connection */;
/*!50001 SET character_set_client      = utf8mb4 */;
/*!50001 SET character_set_results     = utf8mb4 */;
/*!50001 SET collation_connection      = utf8mb4_0900_ai_ci */;
/*!50001 CREATE ALGORITHM=UNDEFINED */
/*!50013 DEFINER=`wms_user`@`localhost` SQL SECURITY DEFINER */
/*!50001 VIEW `v_goods_detail` AS select `g`.`id` AS `id`,`g`.`goods_code` AS `goods_code`,`g`.`goods_name` AS `goods_name`,`g`.`category` AS `category`,`g`.`brand` AS `brand`,`g`.`supplier_id` AS `supplier_id`,`s`.`supplier_name` AS `supplier_name`,`s`.`supplier_code` AS `supplier_code`,`g`.`purchase_price` AS `purchase_price`,`g`.`sale_price` AS `sale_price`,`g`.`stock` AS `stock`,`g`.`unit` AS `unit`,`g`.`status` AS `status`,`g`.`description` AS `description`,`g`.`create_time` AS `create_time`,`g`.`update_time` AS `update_time` from (`base_goods` `g` left join `base_supplier` `s` on((`g`.`supplier_id` = `s`.`id`))) where (`g`.`is_deleted` = 0) */;
/*!50001 SET character_set_client      = @saved_cs_client */;
/*!50001 SET character_set_results     = @saved_cs_results */;
/*!50001 SET collation_connection      = @saved_col_connection */;

--
-- Final view structure for view `v_purchase_detail`
--

/*!50001 DROP VIEW IF EXISTS `v_purchase_detail`*/;
/*!50001 SET @saved_cs_client          = @@character_set_client */;
/*!50001 SET @saved_cs_results         = @@character_set_results */;
/*!50001 SET @saved_col_connection     = @@collation_connection */;
/*!50001 SET character_set_client      = utf8mb4 */;
/*!50001 SET character_set_results     = utf8mb4 */;
/*!50001 SET collation_connection      = utf8mb4_0900_ai_ci */;
/*!50001 CREATE ALGORITHM=UNDEFINED */
/*!50013 DEFINER=`wms_user`@`localhost` SQL SECURITY DEFINER */
/*!50001 VIEW `v_purchase_detail` AS select `d`.`id` AS `detail_id`,`p`.`id` AS `purchase_id`,`p`.`purchase_no` AS `purchase_no`,`d`.`goods_id` AS `goods_id`,`d`.`goods_name` AS `goods_name`,`g`.`goods_code` AS `goods_code`,`g`.`category` AS `category`,`g`.`brand` AS `brand`,`d`.`quantity` AS `quantity`,`d`.`unit_price` AS `unit_price`,`d`.`total_price` AS `total_price`,`p`.`total_quantity` AS `total_quantity`,`p`.`total_amount` AS `total_amount`,`p`.`biz_status` AS `biz_status`,`p`.`confirm_status` AS `confirm_status`,`p`.`operator_id` AS `operator_id`,`p`.`operator_name` AS `operator_name`,`u`.`real_name` AS `operator_real_name`,`p`.`operation_time` AS `operation_time`,`p`.`remark` AS `remark`,`d`.`create_time` AS `create_time`,`d`.`update_time` AS `update_time` from (((`biz_purchase_detail` `d` join `biz_purchase` `p` on((`d`.`purchase_id` = `p`.`id`))) left join `base_goods` `g` on((`d`.`goods_id` = `g`.`id`))) left join `sys_user` `u` on((`p`.`operator_id` = `u`.`id`))) where ((`d`.`is_deleted` = 0) and (`p`.`is_deleted` = 0)) */;
/*!50001 SET character_set_client      = @saved_cs_client */;
/*!50001 SET character_set_results     = @saved_cs_results */;
/*!50001 SET collation_connection      = @saved_col_connection */;

--
-- Final view structure for view `v_sales_detail`
--

/*!50001 DROP VIEW IF EXISTS `v_sales_detail`*/;
/*!50001 SET @saved_cs_client          = @@character_set_client */;
/*!50001 SET @saved_cs_results         = @@character_set_results */;
/*!50001 SET @saved_col_connection     = @@collation_connection */;
/*!50001 SET character_set_client      = utf8mb4 */;
/*!50001 SET character_set_results     = utf8mb4 */;
/*!50001 SET collation_connection      = utf8mb4_0900_ai_ci */;
/*!50001 CREATE ALGORITHM=UNDEFINED */
/*!50013 DEFINER=`wms_user`@`localhost` SQL SECURITY DEFINER */
/*!50001 VIEW `v_sales_detail` AS select `sd`.`id` AS `detail_id`,`s`.`id` AS `sales_id`,`s`.`sales_no` AS `sales_no`,`sd`.`goods_id` AS `goods_id`,`sd`.`goods_name` AS `goods_name`,`g`.`goods_code` AS `goods_code`,`g`.`category` AS `category`,`g`.`brand` AS `brand`,`sd`.`quantity` AS `quantity`,`sd`.`unit_price` AS `unit_price`,`sd`.`total_price` AS `total_price`,`s`.`total_quantity` AS `total_quantity`,`s`.`total_amount` AS `total_amount`,`s`.`operator_id` AS `operator_id`,`s`.`operator_name` AS `operator_name`,`u`.`real_name` AS `operator_real_name`,`s`.`operation_time` AS `operation_time`,`s`.`remark` AS `remark`,`sd`.`create_time` AS `create_time`,`sd`.`update_time` AS `update_time` from (((`biz_sales_detail` `sd` join `biz_sales` `s` on((`sd`.`sales_id` = `s`.`id`))) left join `base_goods` `g` on((`sd`.`goods_id` = `g`.`id`))) left join `sys_user` `u` on((`s`.`operator_id` = `u`.`id`))) where ((`sd`.`is_deleted` = 0) and (`s`.`is_deleted` = 0)) */;
/*!50001 SET character_set_client      = @saved_cs_client */;
/*!50001 SET character_set_results     = @saved_cs_results */;
/*!50001 SET collation_connection      = @saved_col_connection */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

