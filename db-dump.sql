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
  `title` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '会话标题（首条问题前50字）',
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
  `role` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '消息角色: user/assistant',
  `content` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '消息内容',
  `sources_json` text COLLATE utf8mb4_unicode_ci COMMENT '来源文档JSON（仅assistant消息）',
  `hit_type` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '命中类型（仅assistant消息）',
  `provider_code` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '模型供应商编码（仅assistant消息）',
  `model_code` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '模型编码（仅assistant消息）',
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
  `role_code` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '发起调用的角色编码',
  `dept_code` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '发起调用的部门编码',
  `conversation_id` bigint DEFAULT NULL COMMENT '所属会话ID',
  `assistant_message_id` bigint DEFAULT NULL COMMENT '对应assistant消息ID',
  `scene_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'project-assistant' COMMENT '调用场景编码',
  `question_type` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '问题类型：project/general',
  `requested_model_code` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户请求模型编码',
  `provider_code` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '模型供应商编码',
  `model_code` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '实际模型编码',
  `fallback_used` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否触发模型回退',
  `hit_type` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '命中类型',
  `result_status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'success' COMMENT '结果状态',
  `latency_ms` bigint DEFAULT NULL COMMENT '模型调用耗时（毫秒）',
  `question_excerpt` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '问题摘要，不记录完整请求体',
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
  `goods_code` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '商品编码',
  `type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'material' COMMENT '货品类型: material-物料/零件, product-成品',
  `goods_name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '商品名称',
  `product_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '产品名称',
  `category` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '商品类别',
  `brand` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '商品品牌(用于图表聚合)',
  `supplier_id` bigint NOT NULL COMMENT '供应商ID',
  `purchase_price` decimal(10,2) DEFAULT NULL COMMENT '进价',
  `sale_price` decimal(10,2) DEFAULT NULL COMMENT '售价',
  `stock` int NOT NULL DEFAULT '0' COMMENT '当前库存量',
  `warning_stock` int NOT NULL DEFAULT '10' COMMENT '库存预警阈值',
  `unit` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '单位',
  `spec` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '规格(物料固有属性,ADR-0003;物料按名称+规格唯一)',
  `material` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '材质(物料固有属性,ADR-0003)',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态: 1-上架, 0-下架',
  `description` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述',
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
) ENGINE=InnoDB AUTO_INCREMENT=112 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `base_goods`
--

LOCK TABLES `base_goods` WRITE;
/*!40000 ALTER TABLE `base_goods` DISABLE KEYS */;
INSERT INTO `base_goods` VALUES (1,'GD260915232910164','material','1','1','1',NULL,3,10.00,NULL,0,10,'1','1','1',1,'1','2026-09-15 23:29:10','2026-09-23 13:22:06',0),(2,'GD260916212342163','material','顶盖01','PTO153','顶盖',NULL,2,NULL,NULL,0,10,'台','25*3','PA66',1,'黄色、平纹','2026-09-16 21:23:42','2026-09-23 13:22:06',0),(9,'GD260919121413849','product','PTO153',NULL,'成品',NULL,1,NULL,18599.00,0,0,'台','',NULL,1,'','2026-09-19 12:14:13','2026-09-23 13:22:06',0),(30,'GD260920214821719','product','SMC105',NULL,'成品',NULL,1,NULL,12999.00,0,0,'台','',NULL,1,'','2026-09-20 21:48:21','2026-09-23 13:22:06',0),(38,'GD260920215500018','material','手柄帽 01',NULL,NULL,NULL,2,1.00,NULL,0,10,NULL,'19*13.4*21.4','PC+TPU',1,'生产补料自动建档','2026-09-20 21:55:00','2026-09-22 12:02:40',0),(39,'GD260920215500997','material','手柄底座 01',NULL,NULL,NULL,2,1.00,NULL,0,10,NULL,'19.1*13.5*16.3','PC',1,'生产补料自动建档','2026-09-20 21:55:00','2026-09-22 12:02:40',0),(40,'GD260920215500808','material','装饰盖 01',NULL,NULL,NULL,2,1.00,NULL,0,10,NULL,'46.8*25.2*7.8','PC',1,'生产补料自动建档','2026-09-20 21:55:00','2026-09-21 19:51:59',0),(41,'GD260920215500581','material','底座上盖 01',NULL,NULL,NULL,2,1.00,NULL,0,10,NULL,'43.6*21.8*12.6','POM',1,'生产补料自动建档','2026-09-20 21:55:00','2026-09-21 19:51:59',0),(42,'GD260920215500843','material','底座底壳 01',NULL,NULL,NULL,2,1.00,NULL,0,10,NULL,'26*17*16.6','POM',1,'生产补料自动建档','2026-09-20 21:55:00','2026-09-21 19:51:59',0),(43,'GD260920215500259','material','防尘套 01',NULL,NULL,NULL,2,1.00,NULL,0,10,NULL,'43*21.3*32','硅胶',1,'生产补料自动建档','2026-09-20 21:55:00','2026-09-21 19:51:59',0),(44,'GD260920215500821','material','防水垫片 01',NULL,NULL,NULL,2,1.00,NULL,0,10,NULL,'42.8*21*1','硅胶',1,'生产补料自动建档','2026-09-20 21:55:00','2026-09-21 19:51:59',0),(45,'GD260920215500054','material','齿轮 01',NULL,NULL,NULL,2,1.00,NULL,0,10,NULL,'Ø11.8*5.7','POM',1,'生产补料自动建档','2026-09-20 21:55:00','2026-09-21 19:51:59',0),(46,'GD260920215500198','material','齿轮 02',NULL,NULL,NULL,2,1.00,NULL,0,10,NULL,'10.9*8.9*7','POM',1,'生产补料自动建档','2026-09-20 21:55:00','2026-09-21 19:51:59',0),(47,'GD260920215500706','material','主轴 01',NULL,NULL,NULL,2,1.00,NULL,0,10,NULL,'Ø4*50','SUS304',1,'生产补料自动建档','2026-09-20 21:55:00','2026-09-21 19:51:59',0),(48,'GD260920215500223','material','滑块 01',NULL,NULL,NULL,2,1.00,NULL,0,10,NULL,'15*10.2*9.1','黄铜',1,'生产补料自动建档','2026-09-20 21:55:00','2026-09-21 19:51:59',0),(49,'GD260920215500095','material','轴套 01',NULL,NULL,NULL,2,1.00,NULL,0,10,NULL,'Ø4.5*3.6-Ø2','黄铜',1,'生产补料自动建档','2026-09-20 21:55:00','2026-09-21 19:51:59',0),(50,'GD260920215500963','material','弹簧卡座 01',NULL,NULL,NULL,2,1.00,NULL,0,10,NULL,'Ø7.7*2-Ø4','铝合金',1,'生产补料自动建档','2026-09-20 21:55:00','2026-09-21 19:51:59',0),(51,'GD260920215500806','material','磁铁',NULL,NULL,NULL,2,1.00,NULL,0,10,NULL,'Ø6*2.5','钕铁硼',1,'生产补料自动建档','2026-09-20 21:55:00','2026-09-21 19:51:59',0),(52,'GD260920215500101','material','销钉 01',NULL,NULL,NULL,2,1.00,NULL,0,10,NULL,'Ø2*7.3','SUS304',1,'生产补料自动建档','2026-09-20 21:55:00','2026-09-21 19:51:59',0),(53,'GD260920215500226','material','销钉 02',NULL,NULL,NULL,2,1.00,NULL,0,10,NULL,'Ø2*13.9','SUS304',1,'生产补料自动建档','2026-09-20 21:55:00','2026-09-21 19:51:59',0),(54,'GD260920215500050','material','弹簧',NULL,NULL,NULL,2,1.00,NULL,0,10,NULL,'Ø9.6*Ø5.6*35-Ø0.8','SUS304',1,'生产补料自动建档','2026-09-20 21:55:00','2026-09-21 19:51:59',0),(55,'GD260920215500814','material','PCB组件',NULL,NULL,NULL,2,1.00,NULL,0,10,NULL,'22.5*11*1.6','STD',1,'生产补料自动建档','2026-09-20 21:55:00','2026-09-21 19:51:59',0),(56,'GD260920215500477','material','手柄固定螺丝（圆头机牙）',NULL,NULL,NULL,2,1.00,NULL,0,10,NULL,'M2*8','SUS304',1,'生产补料自动建档','2026-09-20 21:55:00','2026-09-21 19:51:59',0),(57,'GD260920215500977','material','底座底壳固定螺丝（圆头平尾自攻）',NULL,NULL,NULL,2,1.00,NULL,0,10,NULL,'M2*8','SUS304',1,'生产补料自动建档','2026-09-20 21:55:00','2026-09-21 19:51:59',0),(58,'GD260920215518184','material','PTO153 顶盖 01',NULL,NULL,NULL,9,10.00,NULL,0,10,NULL,'Ø37.5*14','PA66',1,'生产补料自动建档','2026-09-20 21:55:18','2026-09-21 19:46:47',0),(59,'GD260920215518216','material','PTO153 锁定盖 01',NULL,NULL,NULL,9,5.00,NULL,0,10,NULL,'Ø40*27.5','PA66',1,'生产补料自动建档','2026-09-20 21:55:18','2026-09-21 19:46:47',0),(60,'GD260920215518648','material','PTO153 底座 01',NULL,NULL,NULL,9,54.00,NULL,0,10,NULL,'Ø38.5*74.2','PA66',1,'生产补料自动建档','2026-09-20 21:55:18','2026-09-21 15:50:55',0),(61,'GD260920215518131','material','PTO153 螺母 01',NULL,NULL,NULL,9,4.00,NULL,0,10,NULL,'M30*1.5*6-Ø40.5*D37','PA66',1,'生产补料自动建档','2026-09-20 21:55:18','2026-09-21 15:50:55',0),(62,'GD260920215518245','material','PTO153 主轴 01',NULL,NULL,NULL,9,5.00,NULL,0,10,NULL,'Ø10.8*86.3','PA66',1,'生产补料自动建档','2026-09-20 21:55:18','2026-09-21 15:50:55',0),(63,'GD260920215518044','material','PTO153 主轴导向块 01','','',NULL,9,4.00,NULL,0,10,'','Ø23.8*35','PA66',1,'生产补料自动建档','2026-09-20 21:55:18','2026-09-21 15:50:55',0),(64,'GD260920215518326','material','PTO153 PCB固定筒 01',NULL,NULL,NULL,9,64.00,NULL,0,10,NULL,'Ø24.9*32.5-25.5','PA66',1,'生产补料自动建档','2026-09-20 21:55:18','2026-09-21 15:50:55',0),(65,'GD260920215518493','material','PTO153 PCB端口 01',NULL,NULL,NULL,9,56.00,NULL,0,10,NULL,'28.8*28*32.35','PA66',1,'生产补料自动建档','2026-09-20 21:55:18','2026-09-21 15:50:55',0),(66,'GD260920215518505','material','PTO153 弹簧 01',NULL,NULL,NULL,9,10.00,NULL,0,10,NULL,'Ø10.1*17.5-Ø0.8-6','SUS304',1,'生产补料自动建档','2026-09-20 21:55:18','2026-09-21 15:50:55',0),(67,'GD260920215518430','material','PTO153 开口销钉 01',NULL,NULL,NULL,9,4.00,NULL,0,10,NULL,'Ø2.6*12','65MN锰钢',1,'生产补料自动建档','2026-09-20 21:55:18','2026-09-21 15:50:55',0),(68,'GD260920215518498','material','PTO153 轴承珠 01',NULL,NULL,NULL,9,56.00,NULL,0,10,NULL,'Ø5','SUS304',1,'生产补料自动建档','2026-09-20 21:55:18','2026-09-21 15:50:55',0),(69,'GD260920215518259','material','PTO153 O型圈 大','','',NULL,10,46.00,NULL,0,10,'','Ø20*1.2','橡胶',1,'生产补料自动建档','2026-09-20 21:55:18','2026-09-23 13:22:06',0),(70,'GD260920215518706','material','PTO153 O型圈 小',NULL,NULL,NULL,9,10.00,NULL,0,10,NULL,'Ø10*1.2','橡胶',1,'生产补料自动建档','2026-09-20 21:55:18','2026-09-23 13:22:06',0),(71,'GD260920215518796','material','PTO153 PCB组件 01',NULL,NULL,NULL,9,78.00,NULL,0,10,NULL,'30.8*14*1.2','STD',1,'生产补料自动建档','2026-09-20 21:55:18','2026-09-21 15:50:55',0),(72,'GD260920215518282','material','PTO153 PCB端口插针',NULL,NULL,NULL,9,7.77,NULL,0,10,NULL,'14.5*11.5*1.5-0.8','铜镀银',1,'生产补料自动建档','2026-09-20 21:55:18','2026-09-21 19:10:44',0),(73,'GD260920215518845','material','PTO153 磁铁 01',NULL,NULL,NULL,9,9.99,NULL,0,10,NULL,'4*2.8*1.5','钕铁硼',1,'生产补料自动建档','2026-09-20 21:55:18','2026-09-21 19:03:46',0),(74,'GD260921113509802','product','D113E2E新品成品',NULL,'成品',NULL,1,NULL,50.00,0,0,'台',NULL,NULL,1,NULL,'2026-09-21 11:35:09','2026-09-21 11:35:55',1),(75,'GD260921121623079','product','D112E2E新品成品',NULL,'成品',NULL,1,NULL,50.00,0,0,'台',NULL,NULL,1,NULL,'2026-09-21 12:16:23','2026-09-21 12:16:25',1),(76,'GD260921121758325','product','D112E2E新品成品',NULL,'成品',NULL,1,NULL,50.00,0,0,'台',NULL,NULL,1,NULL,'2026-09-21 12:17:58','2026-09-21 12:17:59',1),(77,'GD260921172659466','product','E2E快速建品锅D121',NULL,'成品',NULL,1,NULL,1999.00,0,0,'台','5L',NULL,1,'销售快速建品','2026-09-21 17:26:59','2026-09-21 17:28:36',1),(78,'GD260921215000798','product','SMC186S',NULL,'成品',NULL,1,NULL,25999.00,0,0,'台',NULL,NULL,1,'销售快速建品','2026-09-21 21:50:00','2026-09-21 22:23:40',0),(79,'GD260921221442027','material','面板',NULL,NULL,NULL,2,1.00,NULL,0,10,NULL,'100*96*8','铝合金',1,'生产补料自动建档','2026-09-21 22:14:42','2026-09-21 22:20:08',0),(80,'GD260921221442697','material','半圆盖A',NULL,NULL,NULL,2,1.00,NULL,0,10,NULL,'86*55*20','铝合金',1,'生产补料自动建档','2026-09-21 22:14:42','2026-09-21 22:20:08',0),(81,'GD260921221442327','material','半圆盖B',NULL,NULL,NULL,2,1.00,NULL,0,10,NULL,'86*55*20','铝合金',1,'生产补料自动建档','2026-09-21 22:14:42','2026-09-21 22:20:08',0),(82,'GD260921221442195','material','主架',NULL,NULL,NULL,2,1.00,NULL,0,10,NULL,'83*91.5*27','铝合金',1,'生产补料自动建档','2026-09-21 22:14:42','2026-09-21 22:20:08',0),(83,'GD260921221442887','material','手柄',NULL,NULL,NULL,2,2.00,NULL,0,10,NULL,'102*17*8','铝合金',1,'生产补料自动建档','2026-09-21 22:14:42','2026-09-21 22:20:08',0),(84,'GD260921221442872','material','把手',NULL,NULL,NULL,2,2.00,NULL,0,10,NULL,'ɸ17*26','铝合金',1,'生产补料自动建档','2026-09-21 22:14:42','2026-09-21 22:20:08',0),(85,'GD260921221442649','material','指针',NULL,NULL,NULL,2,34.00,NULL,0,10,NULL,'ɸ4*29','铝合金',1,'生产补料自动建档','2026-09-21 22:14:42','2026-09-21 22:20:08',0),(86,'GD260921221442366','material','限位块',NULL,NULL,NULL,2,3.00,NULL,0,10,NULL,'11.4*4.8*7','铝合金',1,'生产补料自动建档','2026-09-21 22:14:42','2026-09-21 22:20:08',0),(87,'GD260921221442553','material','齿轮架上',NULL,NULL,NULL,2,543.00,NULL,0,10,NULL,'35*18*3.6','铝合金',1,'生产补料自动建档','2026-09-21 22:14:42','2026-09-21 22:20:08',0),(88,'GD260921221442149','material','齿轮架下',NULL,NULL,NULL,2,43.00,NULL,0,10,NULL,'48*21*5','铝合金',1,'生产补料自动建档','2026-09-21 22:14:42','2026-09-21 22:20:08',0),(89,'GD260921221442595','material','大齿轮',NULL,NULL,NULL,2,34.00,NULL,0,10,NULL,'ɸ58*5.5','铝合金',1,'生产补料自动建档','2026-09-21 22:14:42','2026-09-21 22:20:08',0),(90,'GD260921221442800','material','小齿轮',NULL,NULL,NULL,2,465.00,NULL,0,10,NULL,'ɸ20*17.5','黄铜',1,'生产补料自动建档','2026-09-21 22:14:42','2026-09-21 22:20:08',0),(91,'GD260921221442648','material','钢板',NULL,NULL,NULL,2,54.00,NULL,0,10,NULL,'ɸ54.3*2','不锈钢',1,'生产补料自动建档','2026-09-21 22:14:42','2026-09-21 22:20:08',0),(92,'GD260921221442154','material','主轴',NULL,NULL,NULL,2,4.00,NULL,0,10,NULL,'ɸ15*34.5','不锈钢',1,'生产补料自动建档','2026-09-21 22:14:42','2026-09-21 22:20:08',0),(93,'GD260921221442973','material','外壳',NULL,NULL,NULL,2,4.00,NULL,0,10,NULL,'86*63*49.7','钣金',1,'生产补料自动建档','2026-09-21 22:14:42','2026-09-21 22:20:08',0),(94,'GD260921221442394','material','六角钢柱',NULL,NULL,NULL,2,3.00,NULL,0,10,NULL,'M4*22+6','不锈钢',1,'生产补料自动建档','2026-09-21 22:14:42','2026-09-21 22:20:08',0),(95,'GD260921221442116','material','PVC夜光膜',NULL,NULL,NULL,2,3.00,NULL,0,10,NULL,'177.6*26.5*0.4','PVC',1,'生产补料自动建档','2026-09-21 22:14:42','2026-09-21 22:20:08',0),(96,'GD260921221442843','material','机米螺丝',NULL,NULL,NULL,2,5.00,NULL,0,10,NULL,'M8*6','不锈钢',1,'生产补料自动建档','2026-09-21 22:14:42','2026-09-21 22:20:08',0),(97,'GD260921221442753','material','六角钢柱',NULL,NULL,NULL,2,5.00,NULL,0,10,NULL,'M4*35+6','不锈钢',1,'生产补料自动建档','2026-09-21 22:14:42','2026-09-21 22:20:08',0),(98,'GD260921221443629','material','六角钢柱',NULL,NULL,NULL,2,5.00,NULL,0,10,NULL,'M4*12+6','不锈钢',1,'生产补料自动建档','2026-09-21 22:14:43','2026-09-21 22:20:08',0),(99,'GD260921221443470','material','机米螺丝',NULL,NULL,NULL,2,6.00,NULL,0,10,NULL,'M5*30','不锈钢',1,'生产补料自动建档','2026-09-21 22:14:43','2026-09-21 22:20:08',0),(100,'GD260921221443430','material','防滑螺母',NULL,NULL,NULL,2,76.00,NULL,0,10,NULL,'M5','不锈钢',1,'生产补料自动建档','2026-09-21 22:14:43','2026-09-21 22:20:08',0),(101,'GD260921221443771','material','六角钢柱',NULL,NULL,NULL,2,54.00,NULL,0,10,NULL,'M3*12+6','不锈钢',1,'生产补料自动建档','2026-09-21 22:14:43','2026-09-21 22:20:08',0),(102,'GD260921221443581','material','大轴承',NULL,NULL,NULL,2,46.00,NULL,0,10,NULL,'12*18*4','不锈钢',1,'生产补料自动建档','2026-09-21 22:14:43','2026-09-21 22:20:08',0),(103,'GD260921221443269','material','中轴承',NULL,NULL,NULL,2,64.00,NULL,0,10,NULL,'8*16*5','不锈钢',1,'生产补料自动建档','2026-09-21 22:14:43','2026-09-21 22:20:08',0),(104,'GD260921221443919','material','小轴承',NULL,NULL,NULL,2,4.00,NULL,0,10,NULL,'6*12*4','不锈钢',1,'生产补料自动建档','2026-09-21 22:14:43','2026-09-21 22:20:08',0),(105,'GD260921221443723','material','垫圈',NULL,NULL,NULL,2,343.00,NULL,0,10,NULL,'3*6*2.5','铝合金',1,'生产补料自动建档','2026-09-21 22:14:43','2026-09-21 22:20:08',0),(106,'GD260921221443267','material','磁铁',NULL,NULL,NULL,2,3.00,NULL,0,10,NULL,'ɸ6*3',NULL,1,'生产补料自动建档','2026-09-21 22:14:43','2026-09-21 22:20:08',0),(107,'GD260921221443950','material','内六角螺丝',NULL,NULL,NULL,2,3.00,NULL,0,10,NULL,'M3*10','不锈钢',1,'生产补料自动建档','2026-09-21 22:14:43','2026-09-21 22:20:08',0),(108,'GD260923133855702','material','测试物料1','HXL','测试',NULL,3,NULL,NULL,20,10,'个','1.0','电子',1,'','2026-09-23 13:38:55','2026-09-23 13:38:55',0),(109,'GD260923134024298','product','测试成品1',NULL,'成品',NULL,1,NULL,NULL,1,0,'台','',NULL,1,'','2026-09-23 13:40:24','2026-09-23 13:40:24',0),(110,'GD260923151449720','material','E2E批删A1790147689',NULL,'测试',NULL,1,NULL,NULL,0,10,'个','E2E',NULL,1,NULL,'2026-09-23 15:14:49','2026-09-23 15:15:32',1),(111,'GD260923151449435','material','E2E批删B1790147689',NULL,'测试',NULL,1,NULL,NULL,0,10,'个','E2E',NULL,1,NULL,'2026-09-23 15:14:49','2026-09-23 15:15:32',1);
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
  `supplier_code` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '供应商编码',
  `supplier_name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '供应商名称',
  `address` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '地址',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态: 1-启用, 0-禁用',
  `description` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_supplier_code` (`supplier_code`),
  KEY `idx_status` (`status`),
  KEY `idx_is_deleted` (`is_deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='供应商表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `base_supplier`
--

LOCK TABLES `base_supplier` WRITE;
/*!40000 ALTER TABLE `base_supplier` DISABLE KEYS */;
INSERT INTO `base_supplier` VALUES (1,'SUP260915144952028','系统默认供应商','测试地址',1,'系统缺省供应商锚点（成品建档/未知物料自动建档挂靠），禁止删除（D100）','2026-09-15 14:49:52','2026-09-19 11:53:12',0),(2,'SUP260915221921258','德州旺旺公司','德州市旺旺公司',1,NULL,'2026-09-15 22:19:21','2026-09-15 22:19:21',0),(3,'SUP260915232842309','1','111',1,NULL,'2026-09-15 23:28:42','2026-09-15 23:28:42',0),(9,'SUP260920221256539','胖牛有限公司','北京市朝阳区松榆里社区10号楼4单元306\n',1,NULL,'2026-09-20 22:12:56','2026-09-20 22:12:56',0),(10,'SUP260921223443343','胖乐责任公司','2147 Oakridge Drive',1,NULL,'2026-09-21 22:34:43','2026-09-21 22:34:43',0);
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
  `contact_person` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `contact_phone` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `position` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `is_default` tinyint NOT NULL DEFAULT '0',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_supplier_id` (`supplier_id`),
  KEY `idx_is_deleted` (`is_deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=13 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `base_supplier_contact`
--

LOCK TABLES `base_supplier_contact` WRITE;
/*!40000 ALTER TABLE `base_supplier_contact` DISABLE KEYS */;
INSERT INTO `base_supplier_contact` VALUES (1,1,'张三','111','总经理',1,'2026-09-15 14:49:52','2026-09-15 14:51:22',1),(2,1,'李四','222','销售',0,'2026-09-15 14:49:52','2026-09-15 14:51:22',1),(3,2,'王二','158874523961','生产经理',1,'2026-09-15 22:19:21','2026-09-15 22:19:21',0),(4,2,'张三','18655472301','生产专员',0,'2026-09-15 22:19:21','2026-09-15 22:19:21',0),(5,3,'1','1','1',1,'2026-09-15 23:28:42','2026-09-15 23:28:42',0),(6,4,'测试联系人','13800000000',NULL,1,'2026-09-19 11:50:36','2026-09-19 11:50:49',1),(11,9,'牛子','15899625410','采购经理',1,'2026-09-20 22:12:56','2026-09-20 22:12:56',0),(12,10,'胖乐','15520358894','销售经理',1,'2026-09-21 22:34:43','2026-09-21 22:34:43',0);
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
  `approval_no` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '审批单号',
  `biz_type` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '业务类型: purchase/purchase_return/sales/sales_return',
  `biz_id` bigint NOT NULL COMMENT '业务单据ID',
  `biz_no` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '业务单号(冗余)',
  `request_action` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '申请动作: void/void_red/price_deviation_confirm',
  `request_reason` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '申请原因',
  `before_biz_status` tinyint DEFAULT NULL COMMENT '审批前业务状态快照',
  `before_biz_snapshot` longtext COLLATE utf8mb4_unicode_ci COMMENT '审批前业务详情快照(JSON)',
  `after_biz_status` tinyint DEFAULT NULL COMMENT '审批后业务状态快照',
  `after_biz_snapshot` longtext COLLATE utf8mb4_unicode_ci COMMENT '审批后业务详情快照(JSON)',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态: 1-待审批, 2-已通过, 3-已驳回, 4-处理中',
  `requester_id` bigint NOT NULL COMMENT '申请人ID',
  `requester_name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '申请人姓名',
  `requester_role` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '申请人角色',
  `approver_id` bigint DEFAULT NULL COMMENT '审批人ID',
  `approver_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '审批人姓名',
  `approve_remark` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '审批备注',
  `approved_at` datetime DEFAULT NULL COMMENT '审批通过时间',
  `rejected_at` datetime DEFAULT NULL COMMENT '审批驳回时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  `pending_unique_key` varchar(100) COLLATE utf8mb4_unicode_ci GENERATED ALWAYS AS ((case when ((`status` = 1) and (`is_deleted` = 0)) then concat(`biz_type`,_utf8mb4'#',`biz_id`) else NULL end)) STORED COMMENT '待审批唯一键(仅status=1且未删除生效)',
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
  `bom_code` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `goods_id` bigint NOT NULL,
  `goods_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `remark` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` tinyint NOT NULL DEFAULT '0',
  `active_goods_id` bigint GENERATED ALWAYS AS (if((`is_deleted` = 0),`goods_id`,NULL)) VIRTUAL COMMENT '软删兼容唯一键载体(D66): 有效行=goods_id, 软删行=NULL',
  `active_bom_code` varchar(50) COLLATE utf8mb4_unicode_ci GENERATED ALWAYS AS (if((`is_deleted` = 0),`bom_code`,NULL)) VIRTUAL COMMENT '软删兼容唯一键载体(D66): 有效行=bom_code, 软删行=NULL',
  `lead_days` int DEFAULT NULL COMMENT '标准工期（天，D71）：生产建/编辑BOM时填写，可空；空=待生产评估',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_bom_active_goods` (`active_goods_id`),
  UNIQUE KEY `uk_bom_active_code` (`active_bom_code`),
  KEY `idx_bom_is_deleted` (`is_deleted`),
  KEY `idx_bom_goods` (`goods_id`),
  KEY `idx_bom_code` (`bom_code`)
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='BOM 主表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `biz_bom`
--

LOCK TABLES `biz_bom` WRITE;
/*!40000 ALTER TABLE `biz_bom` DISABLE KEYS */;
INSERT INTO `biz_bom` (`id`, `bom_code`, `goods_id`, `goods_name`, `remark`, `create_time`, `update_time`, `is_deleted`, `lead_days`) VALUES (4,'PTO153-BOM',9,'PTO153','xlsx 批量导入','2026-09-20 21:52:11','2026-09-20 21:52:11',0,NULL),(5,'SMC105-BOM',30,'SMC105','xlsx 批量导入','2026-09-20 21:52:31','2026-09-20 21:52:31',0,NULL),(6,'SMC186S-BOM',78,'SMC186S','xlsx 批量导入','2026-09-21 22:09:57','2026-09-21 22:09:57',0,NULL);
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
  `component_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `spec` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `quantity` decimal(12,4) NOT NULL DEFAULT '1.0000',
  `material` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `remark` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `image` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '组件图片路径(/uploads/...)',
  `is_reference` tinyint NOT NULL DEFAULT '0',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `is_deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_bomd_bom_id` (`bom_id`),
  KEY `idx_bomd_goods_id` (`goods_id`),
  KEY `idx_bomd_is_deleted` (`is_deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=72 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='BOM 明细表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `biz_bom_detail`
--

LOCK TABLES `biz_bom_detail` WRITE;
/*!40000 ALTER TABLE `biz_bom_detail` DISABLE KEYS */;
INSERT INTO `biz_bom_detail` VALUES (7,4,0,58,'PTO153 顶盖 01','Ø37.5*14',1.0000,'PA66','橙色、晒纹',NULL,0,'2026-09-20 21:52:11',0),(8,4,1,59,'PTO153 锁定盖 01','Ø40*27.5',1.0000,'PA66','黑色、晒纹',NULL,0,'2026-09-20 21:52:11',0),(9,4,2,60,'PTO153 底座 01','Ø38.5*74.2',1.0000,'PA66','黑色、晒纹',NULL,0,'2026-09-20 21:52:11',0),(10,4,3,61,'PTO153 螺母 01','M30*1.5*6-Ø40.5*D37',1.0000,'PA66','外购（黑色）',NULL,0,'2026-09-20 21:52:11',0),(11,4,4,62,'PTO153 主轴 01','Ø10.8*86.3',1.0000,'PA66','黑色、晒纹',NULL,0,'2026-09-20 21:52:11',0),(12,4,5,63,'PTO153 主轴导向块 01','Ø23.8*35',1.0000,'PA66','黑色、晒纹',NULL,0,'2026-09-20 21:52:11',0),(13,4,6,64,'PTO153 PCB固定筒 01','Ø24.9*32.5-25.5',1.0000,'PA66','黑色、晒纹',NULL,0,'2026-09-20 21:52:11',0),(14,4,7,65,'PTO153 PCB端口 01','28.8*28*32.35',1.0000,'PA66','黑色、晒纹',NULL,0,'2026-09-20 21:52:11',0),(15,4,8,66,'PTO153 弹簧 01','Ø10.1*17.5-Ø0.8-6',1.0000,'SUS304','黑色、晒纹',NULL,0,'2026-09-20 21:52:11',0),(16,4,9,67,'PTO153 开口销钉 01','Ø2.6*12',1.0000,'65MN锰钢','黑色（外购，实际尺寸为Ø2.5*12）',NULL,0,'2026-09-20 21:52:11',0),(17,4,10,68,'PTO153 轴承珠 01','Ø5',2.0000,'SUS304','',NULL,0,'2026-09-20 21:52:11',0),(18,4,11,69,'PTO153 O型圈 大','Ø20*1.2',1.0000,'橡胶','黑色',NULL,0,'2026-09-20 21:52:11',0),(19,4,12,70,'PTO153 O型圈 小','Ø10*1.2',1.0000,'橡胶','黑色',NULL,0,'2026-09-20 21:52:11',0),(20,4,13,71,'PTO153 PCB组件 01','30.8*14*1.2',1.0000,'STD','',NULL,0,'2026-09-20 21:52:11',0),(21,4,14,72,'PTO153 PCB端口插针','14.5*11.5*1.5-0.8',3.0000,'铜镀银','外购（放在PCB端口啤货）',NULL,0,'2026-09-20 21:52:11',0),(22,4,15,73,'PTO153 磁铁 01','4*2.8*1.5',1.0000,'钕铁硼','镀镍',NULL,0,'2026-09-20 21:52:11',0),(23,5,0,38,'手柄帽 01','19*13.4*21.4',1.0000,'PC+TPU','黑色晒纹，套啤',NULL,0,'2026-09-20 21:52:31',0),(24,5,1,39,'手柄底座 01','19.1*13.5*16.3',1.0000,'PC','黑色/橙色晒纹',NULL,0,'2026-09-20 21:52:31',0),(25,5,2,40,'装饰盖 01','46.8*25.2*7.8',1.0000,'PC','黑色晒纹',NULL,0,'2026-09-20 21:52:31',0),(26,5,3,41,'底座上盖 01','43.6*21.8*12.6',1.0000,'POM','黑色光面',NULL,0,'2026-09-20 21:52:31',0),(27,5,4,42,'底座底壳 01','26*17*16.6',1.0000,'POM','黑色光面',NULL,0,'2026-09-20 21:52:31',0),(28,5,5,43,'防尘套 01','43*21.3*32',1.0000,'硅胶','黑色晒纹',NULL,0,'2026-09-20 21:52:31',0),(29,5,6,44,'防水垫片 01','42.8*21*1',1.0000,'硅胶','黑色光面',NULL,0,'2026-09-20 21:52:31',0),(30,5,7,45,'齿轮 01','Ø11.8*5.7',1.0000,'POM','黑色光面',NULL,0,'2026-09-20 21:52:31',0),(31,5,8,46,'齿轮 02','10.9*8.9*7',1.0000,'POM','黑色光面',NULL,0,'2026-09-20 21:52:31',0),(32,5,9,47,'主轴 01','Ø4*50',1.0000,'SUS304','表面抛光',NULL,0,'2026-09-20 21:52:31',0),(33,5,10,48,'滑块 01','15*10.2*9.1',1.0000,'黄铜','表面抛光',NULL,0,'2026-09-20 21:52:31',0),(34,5,11,49,'轴套 01','Ø4.5*3.6-Ø2',2.0000,'黄铜','表面抛光',NULL,0,'2026-09-20 21:52:31',0),(35,5,12,50,'弹簧卡座 01','Ø7.7*2-Ø4',1.0000,'铝合金','',NULL,0,'2026-09-20 21:52:31',0),(36,5,13,51,'磁铁','Ø6*2.5',1.0000,'钕铁硼','表面镀镍',NULL,0,'2026-09-20 21:52:31',0),(37,5,14,52,'销钉 01','Ø2*7.3',1.0000,'SUS304','固定齿轮01用',NULL,0,'2026-09-20 21:52:31',0),(38,5,15,53,'销钉 02','Ø2*13.9',1.0000,'SUS304','',NULL,0,'2026-09-20 21:52:31',0),(39,5,16,54,'弹簧','Ø9.6*Ø5.6*35-Ø0.8',1.0000,'SUS304','',NULL,0,'2026-09-20 21:52:31',0),(40,5,17,55,'PCB组件','22.5*11*1.6',1.0000,'STD','',NULL,0,'2026-09-20 21:52:31',0),(41,5,18,56,'手柄固定螺丝（圆头机牙）','M2*8',1.0000,'SUS304','',NULL,0,'2026-09-20 21:52:31',0),(42,5,19,57,'底座底壳固定螺丝（圆头平尾自攻）','M2*8',4.0000,'SUS304','',NULL,0,'2026-09-20 21:52:31',0),(43,6,0,79,'面板','100*96*8',1.0000,'铝合金','黑色阳极氧化',NULL,0,'2026-09-21 22:09:57',0),(44,6,1,80,'半圆盖A','86*55*20',1.0000,'铝合金','黑色阳极氧化',NULL,0,'2026-09-21 22:09:57',0),(45,6,2,81,'半圆盖B','86*55*20',1.0000,'铝合金','黑色阳极氧化',NULL,0,'2026-09-21 22:09:57',0),(46,6,3,82,'主架','83*91.5*27',1.0000,'铝合金','黑色阳极氧化',NULL,0,'2026-09-21 22:09:57',0),(47,6,4,83,'手柄','102*17*8',2.0000,'铝合金','黑色阳极氧化',NULL,0,'2026-09-21 22:09:57',0),(48,6,5,84,'把手','ɸ17*26',2.0000,'铝合金','本色阳极氧化',NULL,0,'2026-09-21 22:09:57',0),(49,6,6,85,'指针','ɸ4*29',2.0000,'铝合金','黑色阳极氧化',NULL,0,'2026-09-21 22:09:57',0),(50,6,7,86,'限位块','11.4*4.8*7',4.0000,'铝合金','黑色阳极氧化',NULL,0,'2026-09-21 22:09:57',0),(51,6,8,87,'齿轮架上','35*18*3.6',2.0000,'铝合金','黑色阳极氧化',NULL,0,'2026-09-21 22:09:57',0),(52,6,9,88,'齿轮架下','48*21*5',2.0000,'铝合金','黑色阳极氧化',NULL,0,'2026-09-21 22:09:57',0),(53,6,10,89,'大齿轮','ɸ58*5.5',2.0000,'铝合金','本色氧化',NULL,0,'2026-09-21 22:09:57',0),(54,6,11,90,'小齿轮','ɸ20*17.5',2.0000,'黄铜','表面光面',NULL,0,'2026-09-21 22:09:57',0),(55,6,12,91,'钢板','ɸ54.3*2',2.0000,'不锈钢','表面抛光',NULL,0,'2026-09-21 22:09:57',0),(56,6,13,92,'主轴','ɸ15*34.5',2.0000,'不锈钢','表面光面',NULL,0,'2026-09-21 22:09:57',0),(57,6,14,93,'外壳','86*63*49.7',1.0000,'钣金','表面光面',NULL,0,'2026-09-21 22:09:57',0),(58,6,15,94,'六角钢柱','M4*22+6',6.0000,'不锈钢','',NULL,0,'2026-09-21 22:09:57',0),(59,6,16,95,'PVC夜光膜','177.6*26.5*0.4',1.0000,'PVC','带灯光',NULL,0,'2026-09-21 22:09:57',0),(60,6,17,96,'机米螺丝','M8*6',6.0000,'不锈钢','表面光滑',NULL,0,'2026-09-21 22:09:57',0),(61,6,18,97,'六角钢柱','M4*35+6',4.0000,'不锈钢','',NULL,0,'2026-09-21 22:09:57',0),(62,6,19,98,'六角钢柱','M4*12+6',4.0000,'不锈钢','',NULL,0,'2026-09-21 22:09:57',0),(63,6,20,99,'机米螺丝','M5*30',4.0000,'不锈钢','',NULL,0,'2026-09-21 22:09:57',0),(64,6,21,100,'防滑螺母','M5',4.0000,'不锈钢','',NULL,0,'2026-09-21 22:09:57',0),(65,6,22,101,'六角钢柱','M3*12+6',4.0000,'不锈钢','',NULL,0,'2026-09-21 22:09:57',0),(66,6,23,102,'大轴承','12*18*4',4.0000,'不锈钢','与SMC35H,SMC40共用',NULL,0,'2026-09-21 22:09:57',0),(67,6,24,103,'中轴承','8*16*5',2.0000,'不锈钢','',NULL,0,'2026-09-21 22:09:57',0),(68,6,25,104,'小轴承','6*12*4',2.0000,'不锈钢','',NULL,0,'2026-09-21 22:09:57',0),(69,6,26,105,'垫圈','3*6*2.5',4.0000,'铝合金','',NULL,0,'2026-09-21 22:09:57',0),(70,6,27,106,'磁铁','ɸ6*3',2.0000,'','',NULL,0,'2026-09-21 22:09:57',0),(71,6,28,107,'内六角螺丝','M3*10',8.0000,'不锈钢','',NULL,0,'2026-09-21 22:09:57',0);
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
  `pick_no` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '领料单号',
  `pick_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '领料类型: PICK-领料, SUPPLY-补料, RETURN-退料',
  `source_sales_id` bigint DEFAULT NULL COMMENT '关联销售单ID(可选)',
  `production_order_id` bigint DEFAULT NULL COMMENT '来源生产任务单id(生产端申请领料时写入)',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态: 1-待发料, 2-已发料, 3-已完成, 4-已驳回',
  `applicant_id` bigint NOT NULL COMMENT '申请人ID',
  `applicant_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '申请人姓名(冗余字段)',
  `operator_id` bigint DEFAULT NULL COMMENT '发料人ID(仓储管理员)',
  `operator_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '发料人姓名(冗余字段)',
  `operation_time` datetime DEFAULT NULL COMMENT '发料时间',
  `confirm_time` datetime DEFAULT NULL COMMENT '确认收货时间',
  `reject_reason` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '驳回原因',
  `remark` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
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
  `goods_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '商品名称(冗余字段)',
  `spec` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '规格快照(建单时自物料主数据带入,D63)',
  `material` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '材质快照(建单时自物料主数据带入,D63)',
  `remark` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注快照(建单时自物料主数据描述带入,D63)',
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
  `production_no` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '生产入库单号',
  `production_order_id` bigint DEFAULT NULL COMMENT '来源生产任务单ID(D107: 生产端提交入库申请时写入；仓储手动新增为空)',
  `goods_id` bigint NOT NULL COMMENT '商品ID',
  `goods_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '商品名称(冗余字段)',
  `quantity` int NOT NULL COMMENT '入库数量',
  `unit_price` decimal(10,2) DEFAULT NULL COMMENT '生产单价(可选,自产零件成本可能未知)',
  `total_price` decimal(10,2) DEFAULT NULL COMMENT '总金额(单价为空时为空)',
  `operator_id` bigint DEFAULT NULL COMMENT '操作人ID',
  `operator_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作人姓名(冗余字段)',
  `operation_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作发生时间',
  `remark` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `biz_status` tinyint NOT NULL DEFAULT '1' COMMENT '业务状态: 1-正常, 2-已作废, 3-红冲单',
  `confirm_status` tinyint NOT NULL DEFAULT '2' COMMENT '确认状态(D107): 1-待仓库确认, 2-已确认入库(存量行回填2), 3-已驳回',
  `confirmer_id` bigint DEFAULT NULL COMMENT '确认人ID(D107: 确认入库/驳回时写；手动新增=录入人)',
  `confirmer_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '确认人姓名(冗余)',
  `confirm_time` datetime DEFAULT NULL COMMENT '确认/驳回时间(D107)',
  `reject_reason` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '驳回原因(D107: confirm_status=3 时有值)',
  `source_id` bigint DEFAULT NULL COMMENT '红冲来源单ID',
  `void_time` datetime DEFAULT NULL COMMENT '作废时间',
  `void_reason` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '作废原因',
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
  `order_no` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '生产任务单号',
  `goods_id` bigint NOT NULL COMMENT '成品 goods_id(type=product)',
  `goods_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '成品名称(冗余)',
  `unit` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '成品单位(冗余)',
  `quantity` int NOT NULL COMMENT '生产数量',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态: 1-待生产, 2-生产中, 3-待质检, 4-已完成, 5-已作废, 6-已报废, 7-已终止',
  `kit_status` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '齐套状态: ok-齐套, partial-部分缺料, block-严重缺料',
  `source` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT 'MANUAL' COMMENT '来源: MANUAL-手动创建',
  `process_snapshot` text COLLATE utf8mb4_unicode_ci COMMENT '工序清单快照(8道装配工序静态 SOP，打印用)',
  `remark` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
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
  `step_name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '工序名称(建单时快照)',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '完成状态: 0-未完成, 1-已完成',
  `operator_id` bigint DEFAULT NULL COMMENT '打卡人 id(完成时)',
  `operator_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '打卡人姓名',
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
  `goods_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '成品名称(冗余)',
  `test_point` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '测点: first-首测, final-成品测',
  `tester_id` bigint DEFAULT NULL COMMENT '测试员 id',
  `tester_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '测试员姓名',
  `result` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '结果: OK-合格, NG-不合格',
  `reason` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'NG 原因/备注',
  `disposition` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '处置: REWORK-返工, SCRAP-报废(仅对 NG 记录)',
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
  `purchase_no` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '进货单号',
  `total_quantity` int NOT NULL DEFAULT '0' COMMENT '进货总数量(按明细行合计)',
  `total_amount` decimal(12,2) NOT NULL DEFAULT '0.00' COMMENT '进货总金额(按明细行合计)',
  `operator_id` bigint DEFAULT NULL COMMENT '操作人ID',
  `operator_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作人姓名(冗余字段)',
  `operation_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作发生时间',
  `remark` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `supplier_id` bigint DEFAULT NULL COMMENT '供应商ID(D123头级:手动进货必填,存量/采购申请渠道单据可空)',
  `biz_status` tinyint NOT NULL DEFAULT '1' COMMENT '业务状态: 1-正常, 2-已作废, 3-红冲单',
  `confirm_status` tinyint NOT NULL DEFAULT '3' COMMENT '入库确认: 1-待到货, 2-待入库确认, 3-已入库',
  `arrive_time` datetime DEFAULT NULL COMMENT '采购到货确认时间',
  `confirmer_id` bigint DEFAULT NULL COMMENT '入库确认人ID(仓储)',
  `confirmer_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '入库确认人姓名',
  `confirm_time` datetime DEFAULT NULL COMMENT '仓储确认入库时间',
  `source_id` bigint DEFAULT NULL COMMENT '红冲来源单ID',
  `void_time` datetime DEFAULT NULL COMMENT '作废时间',
  `void_reason` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '作废原因',
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
  `goods_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '商品名称(冗余快照)',
  `spec` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '规格(冗余快照)',
  `material` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '材质(冗余快照)',
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
  `request_no` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '采购申请单号(PR开头)',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态: 1-待采购, 2-采购中(部分入库派生文案「部分入库」), 3-已入库, 4-已驳回, 5-待入库确认',
  `source_type` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT 'warehouse' COMMENT '来源: production-生产缺料补料, warehouse-仓储手动',
  `production_order_id` bigint DEFAULT NULL COMMENT '来源生产任务单id(仅production来源有值)',
  `applicant_id` bigint NOT NULL COMMENT '申请人ID(仓储管理员)',
  `applicant_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '申请人姓名(冗余字段)',
  `operator_id` bigint DEFAULT NULL COMMENT '采购处理人ID(采购管理员)',
  `operator_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '采购处理人姓名(冗余字段)',
  `operation_time` datetime DEFAULT NULL COMMENT '认领(转采购中)时间',
  `arrive_time` datetime DEFAULT NULL COMMENT '采购到货提交时间',
  `receive_time` datetime DEFAULT NULL COMMENT '入库完成时间',
  `confirmer_id` bigint DEFAULT NULL COMMENT '入库确认人ID(仓储管理员)',
  `confirmer_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '入库确认人姓名',
  `confirm_time` datetime DEFAULT NULL COMMENT '仓储确认入库时间',
  `reject_reason` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '驳回原因',
  `remark` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
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
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采购申请单主表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `biz_purchase_request`
--

LOCK TABLES `biz_purchase_request` WRITE;
/*!40000 ALTER TABLE `biz_purchase_request` DISABLE KEYS */;
INSERT INTO `biz_purchase_request` VALUES (1,'PR260923151600602',1,'warehouse',NULL,15,'生产管理员',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'E2E批量撤销','2026-09-23 15:16:00','2026-09-23 15:16:22',1);
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
  `goods_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '商品名称(冗余字段)',
  `spec` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '规格快照(生产补料提交时自BOM行带入)',
  `material` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '材质快照(生产补料提交时自BOM行带入)',
  `remark` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注快照(生产补料提交时自BOM行带入)',
  `is_new_material` tinyint NOT NULL DEFAULT '0' COMMENT '新物料标记: 0-已有物料缺口, 1-未知物料自动建档(详情分组展示用)',
  `quantity` int NOT NULL COMMENT '申请采购数量',
  `expected_arrival_time` datetime DEFAULT NULL COMMENT '预计到货时间(采购认领时按行填写,采购中可改,D61)',
  `arrival_remark` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '到货备注(供应商/发货方式等采购口径,与remark物料描述快照独立,D61)',
  `arrive_quantity` int DEFAULT NULL COMMENT '到货数量(采购到货提交时填写,确认入库按此数量加库存)',
  `unit_price` decimal(10,2) DEFAULT NULL COMMENT '采购单价(到货时填写)',
  `supplier_id` bigint DEFAULT NULL COMMENT 'D131 行级供应商ID(到货提交时选定,确认入库复制到进货明细行)',
  `receive_status` tinyint NOT NULL DEFAULT '1' COMMENT 'D120 行级接收状态: 1-待到货, 2-本批待入库确认, 3-已入库',
  `arrive_batch_no` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'D120 到货批次号(同批同号,如B1/B2;驳回/撤回按批)',
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
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采购申请单明细表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `biz_purchase_request_detail`
--

LOCK TABLES `biz_purchase_request_detail` WRITE;
/*!40000 ALTER TABLE `biz_purchase_request_detail` DISABLE KEYS */;
INSERT INTO `biz_purchase_request_detail` VALUES (1,1,108,NULL,'测试物料1',NULL,NULL,NULL,0,3,NULL,NULL,NULL,NULL,NULL,1,NULL,NULL,NULL,0,'2026-09-23 15:16:00',1);
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
  `return_no` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '退货单号',
  `source_purchase_id` bigint DEFAULT NULL COMMENT '来源进货单ID',
  `source_purchase_no` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '来源进货单号',
  `total_quantity` int NOT NULL DEFAULT '0' COMMENT '退货总数量(按明细行合计)',
  `total_amount` decimal(12,2) NOT NULL DEFAULT '0.00' COMMENT '退货总金额(按明细行合计)',
  `operator_id` bigint DEFAULT NULL COMMENT '操作人ID',
  `operator_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作人姓名(冗余字段)',
  `operation_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作发生时间',
  `remark` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `biz_status` tinyint NOT NULL DEFAULT '1' COMMENT '业务状态: 1-正常, 2-已作废, 3-红冲单',
  `confirm_status` tinyint NOT NULL DEFAULT '3' COMMENT '退货确认: 1-待出库确认, 2-待退货确认, 3-已退货',
  `confirmer_id` bigint DEFAULT NULL COMMENT '出库确认人ID(仓储)',
  `confirmer_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '出库确认人姓名',
  `confirm_time` datetime DEFAULT NULL COMMENT '仓储确认出库时间',
  `completer_id` bigint DEFAULT NULL COMMENT '退货完成确认人ID(采购)',
  `completer_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '退货完成确认人姓名',
  `complete_time` datetime DEFAULT NULL COMMENT '采购确认退货成功时间',
  `source_id` bigint DEFAULT NULL COMMENT '红冲来源单ID',
  `void_time` datetime DEFAULT NULL COMMENT '作废时间',
  `void_reason` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '作废原因',
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
  `goods_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '商品名称(冗余快照)',
  `spec` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '规格(冗余快照)',
  `material` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '材质(冗余快照)',
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
  `sales_no` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '销售单号',
  `total_quantity` int NOT NULL DEFAULT '0' COMMENT '销售总数量(建单按明细行合计，单据不可编辑)',
  `total_amount` decimal(12,2) NOT NULL DEFAULT '0.00' COMMENT '销售总金额(建单按明细行合计，单据不可编辑)',
  `operator_id` bigint DEFAULT NULL COMMENT '操作人ID',
  `operator_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作人姓名(冗余字段)',
  `operation_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作发生时间',
  `remark` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `biz_status` tinyint NOT NULL DEFAULT '1' COMMENT '业务状态: 1-正常, 2-已作废, 3-红冲单',
  `source_id` bigint DEFAULT NULL COMMENT '红冲来源单ID',
  `void_time` datetime DEFAULT NULL COMMENT '作废时间',
  `void_reason` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '作废原因',
  `confirm_status` tinyint NOT NULL DEFAULT '2' COMMENT '仓库确认状态: 1-待仓库确认, 2-已确认出库',
  `confirm_time` datetime DEFAULT NULL COMMENT '仓库确认出库时间',
  `confirmer_id` bigint DEFAULT NULL COMMENT '确认人ID(仓储管理员)',
  `confirmer_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '确认人姓名(冗余字段)',
  `customer_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '客户公司名(对齐下单文档)',
  `contract_no` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '合同编号(对齐下单文档)',
  `customer_contact_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '客户联系人姓名(D128选填,自由文本)',
  `customer_phone` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '客户手机号(D128选填,不做格式校验)',
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
  `goods_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '商品名称(冗余字段)',
  `quantity` int NOT NULL COMMENT '行销售数量',
  `unit_price` decimal(10,2) NOT NULL COMMENT '行销售单价',
  `cost_unit_price` decimal(10,2) DEFAULT NULL COMMENT '成本单价快照',
  `cost_total_price` decimal(12,2) DEFAULT NULL COMMENT '成本总额快照',
  `cost_source` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '成本来源: RECENT_PURCHASE/GOODS_PRICE/ZERO_FALLBACK',
  `total_price` decimal(10,2) NOT NULL COMMENT '行总金额',
  `sort_no` int NOT NULL DEFAULT '1' COMMENT '行序号(同单从1递增)',
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
  `return_no` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '退货单号',
  `source_sales_id` bigint DEFAULT NULL COMMENT '来源销售单ID',
  `source_sales_no` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '来源销售单号',
  `customer_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '退货公司名快照(从来源销售单带入)',
  `customer_contact_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '客户联系人姓名快照(D128,可从来源销售单带出可改)',
  `customer_phone` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '客户手机号快照(D128,可从来源销售单带出可改)',
  `total_quantity` int NOT NULL DEFAULT '0' COMMENT '退货总数量(建单按明细行合计，单据不可编辑)',
  `total_amount` decimal(12,2) NOT NULL DEFAULT '0.00' COMMENT '退货总金额(建单按明细行合计，单据不可编辑)',
  `operator_id` bigint DEFAULT NULL COMMENT '操作人ID',
  `operator_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作人姓名(冗余字段)',
  `operation_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作发生时间',
  `remark` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注(退货原因)',
  `biz_status` tinyint NOT NULL DEFAULT '1' COMMENT '业务状态: 1-正常, 2-已作废, 3-红冲单',
  `source_id` bigint DEFAULT NULL COMMENT '红冲来源单ID',
  `void_time` datetime DEFAULT NULL COMMENT '作废时间',
  `void_reason` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '作废原因',
  `confirm_status` tinyint NOT NULL DEFAULT '2' COMMENT '仓库确认状态: 1-待仓库确认, 2-已确认入库',
  `confirm_time` datetime DEFAULT NULL COMMENT '仓库确认入库时间',
  `confirmer_id` bigint DEFAULT NULL COMMENT '确认人ID(仓储管理员)',
  `confirmer_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '确认人姓名(冗余字段)',
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
  `goods_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '商品名称(冗余字段)',
  `quantity` int NOT NULL COMMENT '行退货数量',
  `unit_price` decimal(10,2) NOT NULL COMMENT '行退货单价',
  `cost_unit_price` decimal(10,2) DEFAULT NULL COMMENT '成本单价快照',
  `cost_total_price` decimal(12,2) DEFAULT NULL COMMENT '成本总额快照',
  `cost_source` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '成本来源: SOURCE_SALE/RECENT_PURCHASE/GOODS_PRICE/ZERO_FALLBACK',
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
-- Table structure for table `biz_stocktake`
--

DROP TABLE IF EXISTS `biz_stocktake`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_stocktake` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `stocktake_no` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '盘点单号(ST开头)',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态: 1-盘点中, 2-待审核, 3-已完成, 4-已取消',
  `remark` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `operator_id` bigint DEFAULT NULL COMMENT '建单人ID(仓储管理员)',
  `operator_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '建单人姓名(冗余)',
  `operation_time` datetime DEFAULT NULL COMMENT '建单时间',
  `submit_time` datetime DEFAULT NULL COMMENT '提交审核时间',
  `submitter_id` bigint DEFAULT NULL COMMENT '提交人ID',
  `submitter_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '提交人姓名(冗余)',
  `reviewer_id` bigint DEFAULT NULL COMMENT '审核人ID(仓储管理员)',
  `reviewer_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '审核人姓名(冗余)',
  `review_time` datetime DEFAULT NULL COMMENT '审核时间',
  `reject_reason` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '驳回原因',
  `cancel_reason` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '取消原因',
  `canceler_id` bigint DEFAULT NULL COMMENT '取消人ID',
  `canceler_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '取消人姓名(冗余)',
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
  `goods_code` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '商品编码(建单快照, 导出/导入匹配键)',
  `goods_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '商品名称(建单快照)',
  `spec` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '规格(建单快照)',
  `material` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '材质(建单快照)',
  `unit` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '单位(建单快照)',
  `goods_type` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '商品类型快照: material-物料, product-成品',
  `book_qty` int NOT NULL COMMENT '建单账面快照(展示参考，非差异基准)',
  `assignee_id` bigint DEFAULT NULL COMMENT '负责人ID',
  `assignee_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '负责人姓名(冗余)',
  `actual_qty` int DEFAULT NULL COMMENT '实盘数(NULL=未盘)',
  `counter_id` bigint DEFAULT NULL COMMENT '实际录入人ID',
  `counter_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '实际录入人姓名(冗余)',
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
  `config_key` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '参数键(唯一)',
  `config_value` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '参数值',
  `config_name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '参数名称',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `updater_id` bigint DEFAULT NULL COMMENT '最近更新人ID',
  `updater_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最近更新人姓名',
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
INSERT INTO `sys_config` VALUES (1,'price_deviation_threshold','0.0500','销售价格偏离阈值','销售单价偏离标准售价超过此比例需超管审批(0.05=5%)',11,'超级管理员','2026-09-20 09:19:39','2026-09-22 10:27:57',0);
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
  `dept_name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '部门名称',
  `dept_code` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '部门编码',
  `leader` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '部门负责人',
  `phone` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '联系电话',
  `description` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述',
  `status` tinyint NOT NULL DEFAULT '2' COMMENT '状态: 1-待审批, 2-已生效, 3-已驳回',
  `requester_id` bigint DEFAULT NULL COMMENT '提交人ID',
  `requester_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '提交人姓名',
  `approver_id` bigint DEFAULT NULL COMMENT '审批人ID',
  `approver_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '审批人姓名',
  `approval_remark` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '审批备注',
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
  `emp_code` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '员工工号',
  `emp_name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '员工姓名',
  `dept_id` bigint NOT NULL COMMENT '部门ID',
  `position` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '职位',
  `phone` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
  `email` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '邮箱',
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
  `request_uri` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '请求路径',
  `method` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '请求方法',
  `status_code` int DEFAULT NULL COMMENT '响应状态码',
  `error_type` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '异常类型',
  `message` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '异常摘要',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
  PRIMARY KEY (`id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统错误日志表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_error_log`
--

LOCK TABLES `sys_error_log` WRITE;
/*!40000 ALTER TABLE `sys_error_log` DISABLE KEYS */;
INSERT INTO `sys_error_log` VALUES (1,'/api/base/goods/batch-delete','POST',500,'HttpMessageNotReadableException','JSON parse error: Unrecognized token \'ERR\': was expecting (JSON String, Number, Array, Object or token \'null\', \'true\' or \'false\')','2026-09-23 15:13:50'),(2,'/api/auth/login','POST',500,'HttpMessageNotReadableException','Required request body is missing: public org.example.back.common.result.Result<org.example.back.dto.LoginResponse> org.example.back.controller.AuthController.login(org.example.back.dto.LoginRequest,jakarta.servlet.http.HttpServletRequest)','2026-09-23 23:10:05');
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
  `policy_name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '策略名称',
  `ip_cidr` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'IP或CIDR网段',
  `allow_flag` tinyint NOT NULL DEFAULT '1' COMMENT '是否允许: 1-允许, 0-拒绝',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态: 1-启用, 0-禁用',
  `priority` int NOT NULL DEFAULT '100' COMMENT '优先级(数值越小优先级越高)',
  `remark` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
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
  `username` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
  `ip` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '登录IP',
  `user_agent` varchar(300) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '客户端标识',
  `success_flag` tinyint NOT NULL DEFAULT '1' COMMENT '登录结果: 1-成功, 0-失败',
  `fail_reason` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '失败原因',
  `login_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登录时间',
  PRIMARY KEY (`id`),
  KEY `idx_username` (`username`),
  KEY `idx_ip` (`ip`),
  KEY `idx_login_time` (`login_time`)
) ENGINE=InnoDB AUTO_INCREMENT=23 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='登录日志表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_login_log`
--

LOCK TABLES `sys_login_log` WRITE;
/*!40000 ALTER TABLE `sys_login_log` DISABLE KEYS */;
INSERT INTO `sys_login_log` VALUES (1,3,'sales_admin','127.0.0.1','curl/8.5.0',1,NULL,'2026-09-23 13:23:03'),(2,11,'superadmin','127.0.0.1','Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36 Edg/153.0.0.0',1,NULL,'2026-09-23 13:28:43'),(3,1,'hr_admin','127.0.0.1','Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36 Edg/153.0.0.0',1,NULL,'2026-09-23 13:29:15'),(4,6,'finance_admin','127.0.0.1','Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36 Edg/153.0.0.0',1,NULL,'2026-09-23 13:30:37'),(5,5,'warehouse_admin','127.0.0.1','Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36 Edg/153.0.0.0',1,NULL,'2026-09-23 13:30:59'),(6,3,'sales_admin','127.0.0.1','Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36 Edg/153.0.0.0',1,NULL,'2026-09-23 13:35:56'),(7,3,'sales_admin','127.0.0.1','Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36 Edg/153.0.0.0',1,NULL,'2026-09-23 13:36:58'),(8,2,'purchase_admin','127.0.0.1','Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36 Edg/153.0.0.0',1,NULL,'2026-09-23 13:37:09'),(9,15,'production_Admin','127.0.0.1','Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36 Edg/153.0.0.0',1,NULL,'2026-09-23 13:37:30'),(10,5,'warehouse_admin','127.0.0.1','Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36 Edg/153.0.0.0',1,NULL,'2026-09-23 13:38:07'),(11,2,'purchase_admin','127.0.0.1','Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36 Edg/153.0.0.0',1,NULL,'2026-09-23 13:47:45'),(12,5,'warehouse_admin','127.0.0.1','Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36 Edg/153.0.0.0',1,NULL,'2026-09-23 13:48:57'),(13,2,'purchase_admin','127.0.0.1','Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36 Edg/153.0.0.0',1,NULL,'2026-09-23 14:01:58'),(14,NULL,'sales_admin','127.0.0.1','Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36 Edg/153.0.0.0',0,'用户名或密码错误','2026-09-23 14:02:35'),(15,3,'sales_admin','127.0.0.1','Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36 Edg/153.0.0.0',1,NULL,'2026-09-23 14:02:39'),(16,15,'production_admin','127.0.0.1','Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36 Edg/153.0.0.0',1,NULL,'2026-09-23 14:03:00'),(17,5,'warehouse_admin','127.0.0.1','curl/8.5.0',1,NULL,'2026-09-23 15:13:50'),(18,5,'warehouse_admin','127.0.0.1','curl/8.5.0',1,NULL,'2026-09-23 15:14:31'),(19,15,'production_admin','127.0.0.1','curl/8.5.0',1,NULL,'2026-09-23 15:16:01'),(20,5,'warehouse_admin','127.0.0.1','Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36 Edg/153.0.0.0',1,NULL,'2026-09-23 23:05:58'),(21,NULL,'warehouse_admin','127.0.0.1','Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36 Edg/153.0.0.0',0,'用户名或密码错误','2026-09-23 23:06:55'),(22,5,'warehouse_admin','127.0.0.1','Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36 Edg/153.0.0.0',1,NULL,'2026-09-23 23:06:59');
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
  `title` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '消息标题',
  `content` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '消息正文',
  `is_read` tinyint NOT NULL DEFAULT '0' COMMENT '是否已读: 0-未读, 1-已读',
  `read_time` datetime DEFAULT NULL COMMENT '已读时间',
  `biz_type` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '关联业务类型: sales/sales_return 等(用于按单据撤销待办)',
  `biz_id` bigint DEFAULT NULL COMMENT '关联业务单据ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  `target_route` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '跳转目标路由（前端优先使用，空则按 bizType 映射兜底）',
  PRIMARY KEY (`id`),
  KEY `idx_recipient_user_read` (`recipient_user_id`,`is_read`,`create_time`),
  KEY `idx_recipient_dept` (`recipient_dept_id`),
  KEY `idx_is_deleted` (`is_deleted`),
  KEY `idx_biz` (`biz_type`,`biz_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='站内消息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_message`
--

LOCK TABLES `sys_message` WRITE;
/*!40000 ALTER TABLE `sys_message` DISABLE KEYS */;
INSERT INTO `sys_message` VALUES (1,2,4,'待处理采购申请','采购申请单 PR260923151600602 已由 生产管理员 提交，请尽快认领处理。',0,NULL,'purchase_request',1,'2026-09-23 15:16:01','2026-09-23 15:16:22',1,'/business/purchase-request');
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
  `title` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '公告标题',
  `content` text COLLATE utf8mb4_unicode_ci COMMENT '公告内容',
  `target_role` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'all' COMMENT '受众角色: admin/employee/all',
  `target_dept_id` bigint DEFAULT NULL COMMENT '目标部门ID，空表示该角色全体',
  `publisher` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '发布人',
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
  `username` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作人用户名',
  `module` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '模块名称',
  `action` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作动作',
  `target_type` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '目标类型',
  `target_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '目标ID',
  `detail` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作描述（人话摘要，写入时由 @AuditLog detail 表达式生成）',
  `before_data` text COLLATE utf8mb4_unicode_ci COMMENT '请求参数快照(JSON)',
  `after_data` text COLLATE utf8mb4_unicode_ci COMMENT '返回结果快照(JSON)',
  `request_uri` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '请求路径',
  `ip` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '来源IP',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
  PRIMARY KEY (`id`),
  KEY `idx_module_action` (`module`,`action`),
  KEY `idx_username` (`username`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作日志表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_operation_log`
--

LOCK TABLES `sys_operation_log` WRITE;
/*!40000 ALTER TABLE `sys_operation_log` DISABLE KEYS */;
INSERT INTO `sys_operation_log` VALUES (1,15,'production_admin','采购申请','建单','采购申请单','','生产建采购申请，共 1 行明细','{\"remark\":\"E2E批量撤销\",\"details\":[{\"goodsId\":108,\"quantity\":3,\"unitPrice\":null,\"sortNo\":null}]}','{\"code\":200,\"msg\":\"成功\"}','/api/business/purchase-requests','127.0.0.1','2026-09-23 15:16:01'),(2,15,'production_admin','采购申请','批量撤销','采购申请单','',NULL,'[1,99999]','{\"code\":200,\"msg\":\"成功\",\"data\":{\"successCount\":1,\"failureCount\":1,\"failures\":[{\"id\":99999,\"name\":\"99999\",\"reason\":\"采购申请单不存在\"}]}}','/api/business/purchase-requests/batch-delete','127.0.0.1','2026-09-23 15:16:23');
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
  `username` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户名',
  `password` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '密码(BCrypt加密)',
  `real_name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '真实姓名',
  `role` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '角色: superadmin-超级管理员, admin-管理员, employee-普通用户',
  `dept_id` bigint DEFAULT NULL COMMENT '所属部门ID，superadmin 允许为空',
  `is_superadmin` tinyint GENERATED ALWAYS AS ((case when (`role` = _utf8mb4'superadmin') then 1 else NULL end)) STORED COMMENT '超级管理员唯一约束辅助列',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态: 1-启用, 0-禁用',
  `phone` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
  `email` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '邮箱',
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
) ENGINE=InnoDB AUTO_INCREMENT=18 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_user`
--

LOCK TABLES `sys_user` WRITE;
/*!40000 ALTER TABLE `sys_user` DISABLE KEYS */;
INSERT INTO `sys_user` (`id`, `username`, `password`, `real_name`, `role`, `dept_id`, `status`, `phone`, `email`, `current_login_time`, `last_login_time`, `create_time`, `update_time`, `is_deleted`) VALUES (1,'hr_admin','$2a$10$yxRor5xgip624/ulGHfyxerZlyhK39FpoVlaTIeBmi1DTAGFD6tl6','人事管理员','admin',5,1,'13800138000','hr_admin@warehouse.com','2026-09-23 13:29:15','2026-09-22 12:27:38','2026-07-05 09:05:28','2026-09-23 13:29:15',0),(2,'purchase_admin','$2a$10$yxRor5xgip624/ulGHfyxerZlyhK39FpoVlaTIeBmi1DTAGFD6tl6','采购管理员','admin',4,1,'13800138001','purchase_admin@warehouse.com','2026-09-23 14:01:58','2026-09-23 13:47:45','2026-07-05 09:05:28','2026-09-23 14:01:58',0),(3,'sales_admin','$2a$10$yxRor5xgip624/ulGHfyxerZlyhK39FpoVlaTIeBmi1DTAGFD6tl6','销售管理员','admin',2,1,'13800138002','sales_admin@warehouse.com','2026-09-23 14:02:39','2026-09-23 13:36:57','2026-07-05 09:05:28','2026-09-23 14:02:39',0),(4,'sales_employee','$2a$10$yxRor5xgip624/ulGHfyxerZlyhK39FpoVlaTIeBmi1DTAGFD6tl6','李四','employee',2,1,'13800138003','sales_employee@warehouse.com','2026-09-16 15:48:45','2026-09-14 20:31:22','2026-07-05 09:05:28','2026-09-16 15:48:45',0),(5,'warehouse_admin','$2a$10$yxRor5xgip624/ulGHfyxerZlyhK39FpoVlaTIeBmi1DTAGFD6tl6','仓储管理员','admin',3,1,'13800138004','warehouse_admin@warehouse.com','2026-09-23 23:06:59','2026-09-23 23:05:58','2026-07-05 09:05:28','2026-09-23 23:06:59',0),(6,'finance_admin','$2a$10$yxRor5xgip624/ulGHfyxerZlyhK39FpoVlaTIeBmi1DTAGFD6tl6','财务管理员','admin',1,1,'13800138005','finance_admin@warehouse.com','2026-09-23 13:30:37','2026-09-16 15:48:46','2026-07-05 09:05:28','2026-09-23 13:30:37',0),(7,'hr_employee','$2a$10$yxRor5xgip624/ulGHfyxerZlyhK39FpoVlaTIeBmi1DTAGFD6tl6','人事员工','employee',5,1,'13800138006','hr_employee@warehouse.com','2026-09-16 15:48:46','2026-09-14 15:36:48','2026-07-05 09:05:28','2026-09-16 15:48:46',0),(8,'purchase_employee','$2a$10$yxRor5xgip624/ulGHfyxerZlyhK39FpoVlaTIeBmi1DTAGFD6tl6','采购员工','employee',4,1,'13800138007','purchase_employee@warehouse.com','2026-09-19 13:04:53','2026-09-19 13:01:32','2026-07-05 09:05:28','2026-09-19 13:04:53',0),(9,'warehouse_employee','$2a$10$yxRor5xgip624/ulGHfyxerZlyhK39FpoVlaTIeBmi1DTAGFD6tl6','仓储员工','employee',3,1,'13800138008','warehouse_employee@warehouse.com','2026-09-16 17:05:10','2026-09-16 17:03:58','2026-07-05 09:05:28','2026-09-16 17:05:10',0),(10,'finance_employee','$2a$10$yxRor5xgip624/ulGHfyxerZlyhK39FpoVlaTIeBmi1DTAGFD6tl6','财务员工','employee',1,1,'13800138009','finance_employee@warehouse.com','2026-09-16 15:48:46','2026-09-14 15:36:48','2026-07-05 09:05:28','2026-09-16 15:48:46',0),(11,'superadmin','$2a$10$yxRor5xgip624/ulGHfyxerZlyhK39FpoVlaTIeBmi1DTAGFD6tl6','超级管理员','superadmin',NULL,1,'13800138010','superadmin@warehouse.com','2026-09-23 13:28:43','2026-09-23 13:03:03','2026-07-05 09:05:28','2026-09-23 13:28:43',0),(15,'production_admin','$2a$10$yxRor5xgip624/ulGHfyxerZlyhK39FpoVlaTIeBmi1DTAGFD6tl6','生产管理员','admin',7,1,'13800138011','production_admin@warehouse.com','2026-09-23 15:16:01','2026-09-23 14:03:00','2026-08-31 11:11:50','2026-09-23 15:16:01',0),(16,'production_employee','$2a$10$yxRor5xgip624/ulGHfyxerZlyhK39FpoVlaTIeBmi1DTAGFD6tl6','生产员工','employee',7,1,'13800138012','production_employee@warehouse.com','2026-09-16 15:48:45','2026-09-14 23:25:46','2026-08-31 11:11:50','2026-09-16 15:48:45',0);
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
  `content` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '要求内容',
  `start_time` datetime NOT NULL COMMENT '要求开始时间',
  `end_time` datetime NOT NULL COMMENT '要求截止时间',
  `target_scope` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'all' COMMENT '对象范围: all-全体部门员工, selected-指定员工',
  `creator_id` bigint NOT NULL COMMENT '创建人用户ID',
  `creator_name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '创建人姓名',
  `dept_id` bigint NOT NULL COMMENT '所属部门ID',
  `dept_code` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '所属部门代码',
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
  `employee_name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '员工姓名',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '流程状态: 0-待接受, 1-执行中, 2-待审核, 3-已完成, 4-拒收, 5-已驳回',
  `overdue_flag` tinyint NOT NULL DEFAULT '0' COMMENT '是否已超时: 0-否, 1-是',
  `overdue_at` datetime DEFAULT NULL COMMENT '首次超时时间',
  `submitted_on_time` tinyint DEFAULT NULL COMMENT '是否按时提交: 1-按时, 0-逾期, NULL-未提交',
  `overdue_remind_count` int NOT NULL DEFAULT '0' COMMENT '已发送超时提醒次数',
  `last_remind_time` datetime DEFAULT NULL COMMENT '最近一次超时提醒时间',
  `completed_at` datetime DEFAULT NULL COMMENT '最终完成时间',
  `execute_result` text COLLATE utf8mb4_unicode_ci COMMENT '执行结果文本',
  `reject_count` int NOT NULL DEFAULT '0' COMMENT '驳回次数',
  `accepted_at` datetime DEFAULT NULL COMMENT '接受时间',
  `submitted_at` datetime DEFAULT NULL COMMENT '提交审核时间',
  `reviewed_at` datetime DEFAULT NULL COMMENT '审核完成时间',
  `reviewer_id` bigint DEFAULT NULL COMMENT '审核人ID',
  `reviewer_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '审核人姓名',
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
  `file_name` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '原始文件名',
  `file_path` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '服务端存储路径',
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

