-- ===========================================
-- V1: kele-doc 初始化（全量建表 + 种子数据）
-- 合并原 V1~V4，用于全新空库一键初始化
-- 兼容性：MySQL 8.0+（含 8.4 LTS / 9.x）
-- ===========================================

-- -------------------------------------------
-- 系统表
-- -------------------------------------------

CREATE TABLE IF NOT EXISTS `sys_user_info`
(
    `id`        bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
    `user_name` varchar(64)           DEFAULT NULL COMMENT '昵称，最多64个字符',
    `account`   varchar(32)  NOT NULL COMMENT '账户名，2-20个字符',
    `password`  varchar(256) NOT NULL COMMENT '密码，通过AES对称加密',
    `avatar`    varchar(1024)         DEFAULT NULL COMMENT '头像地址',
    `create_at` DATETIME     NOT NULL COMMENT '注册时间',
    `version`   int(11) NOT NULL DEFAULT '0' COMMENT '版本号',
    `update_at` DATETIME     NOT NULL COMMENT '更新时间',
    `status`    int          NOT NULL DEFAULT '0' COMMENT '0：正常，-1：删除，1：禁用',
    `role`      VARCHAR(16)  NOT NULL DEFAULT 'USER' COMMENT 'USER=普通用户, ADMIN=管理员',
    PRIMARY KEY (`id`),
    UNIQUE `uk_account`(account),
    KEY `idx_role` (role)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='系统-用户信息';

CREATE TABLE IF NOT EXISTS `sys_attachment`
(
    `id`         bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
    `name`       varchar(256)          DEFAULT NULL COMMENT '附件名称',
    `path`       varchar(512) NOT NULL COMMENT '附件存储路径',
    `remark`     varchar(512)          DEFAULT NULL COMMENT '附件备注',
    `size`       bigint(20) NOT NULL DEFAULT '0' COMMENT '附件文件大小 M',
    `version`    int(11) NOT NULL DEFAULT '0' COMMENT '版本号',
    `creator_id` bigint(20) NOT NULL COMMENT '上传人ID',
    `create_at`  DATETIME     NOT NULL COMMENT '上传时间',
    `update_at`  DATETIME     NOT NULL COMMENT '更新时间',
    `status`     int          NOT NULL DEFAULT '0' COMMENT '0：正常，-1：删除',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='系统-附件';

CREATE TABLE IF NOT EXISTS `sys_user_config`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
    `config_type`    varchar(32) NOT NULL COMMENT '配置类型',
    `config_content` varchar(1024)        DEFAULT NULL COMMENT '配置规则JSON',
    `version`        int(11) NOT NULL DEFAULT '0' COMMENT '版本号',
    `user_id`        bigint(20) NOT NULL COMMENT '用户ID',
    `create_at`      DATETIME    NOT NULL COMMENT '创建时间',
    `update_at`      DATETIME    NOT NULL COMMENT '更新时间',
    `status`         int         NOT NULL DEFAULT '0' COMMENT '0：正常，-1：删除',
    PRIMARY KEY (`id`),
    unique `uk_user_id_config_type`(user_id, config_type)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='系统-用户配置';

-- -------------------------------------------
-- 文档表
-- -------------------------------------------

CREATE TABLE IF NOT EXISTS `doc_file_folder`
(
    `id`           bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
    `parent_id`    bigint(20) unsigned NOT NULL COMMENT '父文件夹ID，顶级节点为0',
    `name`         varchar(128) NOT NULL COMMENT '文件名',
    `file_count`   int(11) NOT NULL DEFAULT '0' COMMENT '文件计数',
    `folder_count` int(11) NOT NULL DEFAULT '0' COMMENT '菜单计数',
    `format`       int(11) NOT NULL COMMENT '文件格式，1：文件夹，2：文件',
    `file_type`    varchar(32) NOT NULL COMMENT '文件类型',
    `img`          varchar(1024) DEFAULT NULL COMMENT '封面图',
    `version`      int(11) NOT NULL DEFAULT '0' COMMENT '版本号',
    `creator_id`   bigint(20) NOT NULL COMMENT '创建人ID',
    `owner_id`     BIGINT       NOT NULL COMMENT '所有者ID',
    `create_at`    datetime    NOT NULL COMMENT '创建时间',
    `update_at`    datetime    NOT NULL COMMENT '更新时间',
    `status`       int(11) NOT NULL DEFAULT '0' COMMENT '0：正常，-1：删除',
    `is_root`      TINYINT GENERATED ALWAYS AS (CASE WHEN parent_id = 0 THEN 1 ELSE 0 END) VIRTUAL,
    PRIMARY KEY (`id`),
    key `idx_parent_creator_id`(parent_id, creator_id),
    key `idx_creator_id`(creator_id),
    key `idx_owner` (owner_id),
    key `idx_is_root` (is_root)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='文档-文件夹';

CREATE TABLE IF NOT EXISTS `doc_file_content`
(
    `id`         bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
    `content`    longtext COMMENT '文件数据',
    `version`    int(11) NOT NULL DEFAULT '0' COMMENT '版本号',
    `creator_id` bigint(20) NOT NULL COMMENT '创建人ID',
    `create_at`  datetime NOT NULL COMMENT '创建时间',
    `update_at`  datetime NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='文档-文件内容';

CREATE TABLE IF NOT EXISTS `doc_collect_folder`
(
    `id`         bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
    `user_id`    bigint(20)   NOT NULL COMMENT '收藏人ID',
    `folder_id`  bigint(20)   NOT NULL COMMENT '文档-文件ID',
    `create_at`  DATETIME     NOT NULL COMMENT '收藏时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_folder` (`user_id`, `folder_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='文档-文件收藏夹';

CREATE TABLE IF NOT EXISTS `doc_recycle`
(
    `id`                 bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
    `folder_id`          BIGINT       NULL COMMENT '文件夹ID',
    `name`               varchar(128) NOT NULL COMMENT '文件名',
    `user_id`            bigint(20)   NOT NULL COMMENT '回收人ID',
    `deleter_id`         BIGINT       NULL COMMENT '删除者ID',
    `owner_at_delete_id` BIGINT       NULL COMMENT '删除时的 folder ownerId 快照',
    `create_at`          DATETIME     NOT NULL COMMENT '回收时间',
    PRIMARY KEY (`id`),
    key `idx_user_id` (`user_id`),
    UNIQUE KEY `uk_folder_user` (`folder_id`, `user_id`),
    key `idx_owner_at_delete` (`owner_at_delete_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='文档-回收站';

CREATE TABLE IF NOT EXISTS `doc_relation_level`
(
    `id`        bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
    `parent_id` bigint(20) unsigned NOT NULL COMMENT '父id',
    `son_id`    bigint(20) unsigned NOT NULL COMMENT '子id',
    `user_id`   bigint(20)          NOT NULL COMMENT '回收人ID',
    PRIMARY KEY (`id`),
    key `idx_parent_id` (parent_id),
    key `idx_son_id` (son_id),
    key `idx_user_id` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='文档-关联层级';

-- -------------------------------------------
-- 共享/权限/群组（原 V2）
-- -------------------------------------------

CREATE TABLE IF NOT EXISTS `doc_file_folder_acl` (
    `id`              BIGINT AUTO_INCREMENT PRIMARY KEY,
    `folder_id`       BIGINT      NOT NULL,
    `principal_type`  VARCHAR(8)  NOT NULL,
    `principal_id`    BIGINT      NULL,
    `permission`      VARCHAR(8)  NOT NULL,
    `granted_by`      BIGINT      NOT NULL,
    `created_at`      DATETIME    NOT NULL,
    `revoked_at`      DATETIME    NULL,
    `revoke_reason`   VARCHAR(32) NULL COMMENT '撤销原因：GROUP_DISSOLVE/MANUAL/REPLACE',
    `active_marker`   TINYINT GENERATED ALWAYS AS (IF(revoked_at IS NULL, 1, NULL)) VIRTUAL,
    KEY `idx_folder` (folder_id),
    KEY `idx_principal` (principal_type, principal_id, revoked_at),
    UNIQUE KEY `uk_acl_active` (folder_id, principal_type, principal_id, active_marker),
    KEY `idx_principal_reason` (principal_type, principal_id, revoke_reason)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件夹权限控制表';

CREATE TABLE IF NOT EXISTS `group` (
    `id`          BIGINT AUTO_INCREMENT PRIMARY KEY,
    `name`        VARCHAR(64)  NOT NULL,
    `description` VARCHAR(255) NULL,
    `created_by`  BIGINT       NOT NULL,
    `created_at`  DATETIME     NOT NULL,
    `status`      TINYINT      NOT NULL DEFAULT 1,
    UNIQUE KEY `uk_name` (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户群组';

CREATE TABLE IF NOT EXISTS `group_member` (
    `group_id`  BIGINT   NOT NULL,
    `user_id`   BIGINT   NOT NULL,
    `joined_at` DATETIME NOT NULL,
    PRIMARY KEY (group_id, user_id),
    KEY `idx_user` (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='群组成员';

-- -------------------------------------------
-- 种子数据：默认管理员（admin / admin）
-- 密码 = BCrypt 哈希，对应明文 "admin"
-- -------------------------------------------
INSERT INTO `sys_user_info`
    (`id`, `user_name`, `account`, `password`, `create_at`, `version`, `update_at`, `status`, `role`)
VALUES
    (1, 'admin', 'admin', '$2a$10$/Tt19bYZy9CvZlXKSbZO3eIzGJxX1DYOvK3xBf5VSzSS.LfHpsM0u', NOW(), 0, NOW(), 0, 'ADMIN');
