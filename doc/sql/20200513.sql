/*
Navicat MySQL Data Transfer

Source Server         : localhost
Source Server Version : 50624
Source Host           : localhost:3306
Source Database       : novel_plus

Target Server Type    : MYSQL
Target Server Version : 50624
File Encoding         : 65001

Date: 2020-05-13 21:42:20
*/

SET FOREIGN_KEY_CHECKS=0;

-- ----------------------------
-- Table structure for author
-- ----------------------------
DROP TABLE IF EXISTS `author`;
CREATE TABLE `author` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint(20) DEFAULT NULL COMMENT '用户ID',
  `invite_code` varchar(20) DEFAULT NULL COMMENT '邀请码',
  `pen_name` varchar(20) DEFAULT NULL COMMENT '笔名',
  `tel_phone` varchar(20) DEFAULT NULL COMMENT '手机号码',
  `chat_account` varchar(50) DEFAULT NULL COMMENT 'QQ或微信账号',
  `email` varchar(50) DEFAULT NULL COMMENT '电子邮箱',
  `work_direction` tinyint(4) DEFAULT NULL COMMENT '作品方向，0：男频，1：女频',
  `status` tinyint(4) DEFAULT '0' COMMENT '0：正常，1：封禁',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COMMENT='作者表';

-- ----------------------------
-- Records of author
-- ----------------------------
             
             

-- ----------------------------
-- Table structure for author_code
-- ----------------------------
DROP TABLE IF EXISTS `author_code`;
CREATE TABLE `author_code` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `invite_code` varchar(100) DEFAULT NULL COMMENT '邀请码',
  `validity_time` datetime DEFAULT NULL COMMENT '有效时间',
  `is_use` tinyint(1) DEFAULT '0' COMMENT '是否使用过，0：未使用，1:使用过',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `create_user_id` bigint(20) DEFAULT NULL COMMENT '创建人ID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `key_code` (`invite_code`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COMMENT='作家邀请码表';

-- ----------------------------
-- Records of author_code
-- ----------------------------
         
         
         



-- ----------------------------
-- Table structure for user_buy_record
-- ----------------------------
DROP TABLE IF EXISTS `user_buy_record`;
CREATE TABLE `user_buy_record` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint(20) NOT NULL COMMENT '用户ID',
  `book_id` bigint(20) DEFAULT NULL COMMENT '购买的小说ID',
  `book_name` varchar(50) DEFAULT NULL COMMENT '购买的小说名',
  `book_index_id` bigint(20) DEFAULT NULL COMMENT '购买的章节ID',
  `book_index_name` varchar(100) DEFAULT NULL COMMENT '购买的章节名',
  `buy_amount` int(11) DEFAULT NULL COMMENT '购买使用的屋币数量',
  `create_time` datetime DEFAULT NULL COMMENT '购买时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `key_userId_indexId` (`user_id`,`book_index_id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COMMENT='用户消费记录表';

-- ----------------------------
-- Records of user_buy_record
-- ----------------------------
           
           
