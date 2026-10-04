
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
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `square_post` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '广场发布主键ID',
  `trip_id` bigint unsigned NOT NULL COMMENT '关联源路书ID',
  `user_id` bigint unsigned NOT NULL COMMENT '作者用户ID',
  `author_name` varchar(50) DEFAULT '旅行探险家' COMMENT '作者昵称快照',
  `author_avatar` varchar(255) DEFAULT NULL COMMENT '作者头像快照',
  `title` varchar(100) NOT NULL COMMENT '发布标题',
  `destination` varchar(100) NOT NULL COMMENT '目的地',
  `total_days` int NOT NULL DEFAULT '1' COMMENT '总天数',
  `total_budget` decimal(10,2) DEFAULT '0.00' COMMENT '总预估预算',
  `trip_style` varchar(50) DEFAULT '休闲度假' COMMENT '旅行风格偏好',
  `cover_image` varchar(255) DEFAULT NULL COMMENT '封面图',
  `summary` varchar(500) DEFAULT NULL COMMENT '行程总览寄语快照',
  `snapshot_json` longtext NOT NULL COMMENT '冻结的完整行程树状结构JSON快照',
  `like_count` int NOT NULL DEFAULT '0' COMMENT '点赞数',
  `clone_count` int NOT NULL DEFAULT '0' COMMENT '克隆数',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标识(0:正常发布中, 1:已下架设为私密)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次发布时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最新同步时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_trip_id` (`trip_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_dest_style_del` (`destination`,`trip_style`,`is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='广场公开路书不可变快照表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `sys_user` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `username` varchar(50) NOT NULL COMMENT '登录用户名',
  `password` varchar(100) NOT NULL COMMENT '加密后的登录密码',
  `nickname` varchar(50) NOT NULL DEFAULT '旅行探险家' COMMENT '用户昵称',
  `avatar` varchar(255) DEFAULT NULL COMMENT '用户头像URL',
  `email` varchar(100) DEFAULT NULL COMMENT '联系邮箱',
  `home_city` varchar(50) DEFAULT NULL COMMENT '常住城市',
  `ai_quota_daily` int NOT NULL DEFAULT '10' COMMENT '每日AI生成总配额',
  `ai_quota_used` int NOT NULL DEFAULT '0' COMMENT '当日已使用AI次数',
  `quota_reset_date` date DEFAULT NULL COMMENT '配额重置日期',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '账号状态(0:禁用, 1:正常)',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除(0:否, 1:是)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户基础信息表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `trip_day` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `trip_id` bigint unsigned NOT NULL COMMENT '关联路书ID',
  `day_index` int NOT NULL COMMENT '第几天',
  `day_date` date DEFAULT NULL COMMENT '公历日期',
  `day_theme` varchar(100) DEFAULT NULL COMMENT '当天主题',
  `day_summary` text COMMENT '当天简述',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_trip_day` (`trip_id`,`day_index`),
  KEY `idx_trip_id` (`trip_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='行程日程天数表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `trip_expense` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `trip_id` bigint unsigned NOT NULL COMMENT '关联路书ID',
  `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
  `category` varchar(30) NOT NULL COMMENT '消费分类',
  `amount` decimal(10,2) NOT NULL COMMENT '金额',
  `expense_name` varchar(100) NOT NULL COMMENT '费用说明',
  `expense_date` date NOT NULL COMMENT '消费日期',
  `remark` varchar(255) DEFAULT NULL COMMENT '备注',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_trip_category` (`trip_id`,`category`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='旅行记账开销表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `trip_interaction` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
  `trip_id` bigint unsigned NOT NULL COMMENT '路书ID',
  `action_type` varchar(20) NOT NULL COMMENT '行为类型(LIKE/FAVORITE/CLONE)',
  `is_active` tinyint NOT NULL DEFAULT '1' COMMENT '状态',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '交互时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_trip_action` (`user_id`,`trip_id`,`action_type`),
  KEY `idx_trip_action` (`trip_id`,`action_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='社区互动表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `trip_item` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `day_id` bigint unsigned NOT NULL COMMENT '关联日程天数ID',
  `trip_id` bigint unsigned NOT NULL COMMENT '关联路书ID',
  `item_type` varchar(20) NOT NULL DEFAULT 'SCENIC' COMMENT '节点类型',
  `item_name` varchar(100) NOT NULL COMMENT '地点名称',
  `start_time` varchar(20) DEFAULT NULL COMMENT '建议开始时间',
  `cost_time_hours` decimal(3,1) DEFAULT '2.0' COMMENT '建议游玩时长',
  `cost_estimate` decimal(10,2) DEFAULT '0.00' COMMENT '预估花费',
  `address` varchar(255) DEFAULT NULL COMMENT '详细地址',
  `longitude` decimal(10,6) DEFAULT NULL COMMENT '经度',
  `latitude` decimal(10,6) DEFAULT NULL COMMENT '纬度',
  `transport_tips` varchar(255) DEFAULT NULL COMMENT '交通建议',
  `ai_tips` varchar(500) DEFAULT NULL COMMENT 'AI小贴士',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '排序值',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_day_id_sort` (`day_id`,`sort_order`),
  KEY `idx_trip_id` (`trip_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='行程节点明细表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `trip_member` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `trip_id` bigint unsigned NOT NULL COMMENT '关联路书ID',
  `user_id` bigint unsigned NOT NULL COMMENT '成员用户ID',
  `role` varchar(20) NOT NULL DEFAULT 'EDITOR' COMMENT '成员角色 (OWNER:创建者, EDITOR:编辑者, VIEWER:只读者)',
  `nickname_snapshot` varchar(50) DEFAULT '同行探险家' COMMENT '加入时的昵称快照',
  `avatar_snapshot` varchar(255) DEFAULT NULL COMMENT '加入时的头像快照',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '是否退出/移除(0:正常成员, 1:已退出)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_trip_user` (`trip_id`,`user_id`),
  KEY `idx_user_trips` (`user_id`,`is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='行程协作者成员关联表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `trip_packing_item` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `trip_id` bigint unsigned NOT NULL COMMENT '关联路书ID',
  `category` varchar(30) NOT NULL DEFAULT 'COMMON' COMMENT '物品类别',
  `item_name` varchar(100) NOT NULL COMMENT '物品名称',
  `is_checked` tinyint NOT NULL DEFAULT '0' COMMENT '是否勾选',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_trip_id` (`trip_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI智能行李清单表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `trip_plan` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '路书主键ID',
  `user_id` bigint unsigned NOT NULL COMMENT '创建者用户ID',
  `title` varchar(100) NOT NULL COMMENT '路书标题',
  `destination` varchar(100) NOT NULL COMMENT '目的地城市',
  `start_date` date DEFAULT NULL COMMENT '出发日期',
  `total_days` int NOT NULL DEFAULT '1' COMMENT '出行总天数',
  `total_budget` decimal(10,2) DEFAULT '0.00' COMMENT '总预算金额',
  `trip_style` varchar(50) DEFAULT '休闲度假' COMMENT '旅行风格偏好',
  `travel_group` varchar(50) DEFAULT '独自一人' COMMENT '出行人群',
  `cover_image` varchar(255) DEFAULT NULL COMMENT '路书封面图',
  `summary` varchar(500) DEFAULT NULL COMMENT 'AI生成的行程总览',
  `is_public` tinyint NOT NULL DEFAULT '0' COMMENT '是否公开至广场(0:私密, 1:公开)',
  `is_featured` tinyint NOT NULL DEFAULT '0' COMMENT '是否为官方精选主题模板(0:常规路书, 1:官方精选)',
  `is_ai_generated` tinyint NOT NULL DEFAULT '1' COMMENT '是否AI生成',
  `like_count` int NOT NULL DEFAULT '0' COMMENT '点赞数',
  `clone_count` int NOT NULL DEFAULT '0' COMMENT '克隆数',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_public_destination` (`is_public`,`destination`,`is_deleted`),
  KEY `idx_create_time` (`create_time`),
  KEY `idx_featured_del` (`is_featured`,`is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='路书主表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

