-- ===========================================
-- V2: kele-doc 共享/权限/群组
-- 对应 spec: docs/superpowers/specs/2026-06-06-kele-doc-sharing-design.md v0.12
-- 依赖：V1__init.sql 已建好 sys_user_info / doc_file_folder / doc_recycle 等基表
--
-- 兼容性：MySQL 5.7.6+（含 8.0/9.x）
--  * 5.7 不支持 DROP COLUMN IF EXISTS（8.0.2+ 才有），Step 5 改用
--    information_schema + PREPARE/EXECUTE 动态 SQL 兼容
--  * 5.7.6+ 支持 GENERATED ALWAYS AS (...) VIRTUAL（Step 0.5 + active_marker）
--  * 5.7+ 唯一索引把 NULL 视为 distinct（active_marker 走 NULL 的多行不冲突）
-- ===========================================

-- Step 0: sys_user_info 加 role 字段
ALTER TABLE sys_user_info
  ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'USER' COMMENT 'USER=普通用户, ADMIN=管理员' AFTER status,
  ADD INDEX idx_role (role);

UPDATE sys_user_info SET role = 'ADMIN' WHERE id = 1;

-- Step 0.5: doc_file_folder 加 is_root 虚拟列
ALTER TABLE doc_file_folder
  ADD COLUMN is_root TINYINT
  GENERATED ALWAYS AS (CASE WHEN parent_id = 0 THEN 1 ELSE 0 END) VIRTUAL,
  ADD INDEX idx_is_root (is_root);

-- Step 0.7: doc_recycle 加 folder_id + 数据回填
ALTER TABLE doc_recycle
  ADD COLUMN folder_id BIGINT NULL AFTER id,
  ADD UNIQUE KEY uk_folder_user (folder_id, user_id);

UPDATE doc_recycle SET folder_id = id WHERE folder_id IS NULL;

-- Step 1: doc_file_folder 加 owner_id
ALTER TABLE doc_file_folder ADD COLUMN owner_id BIGINT NULL AFTER creator_id;

-- Step 2: 同步 owner_id
UPDATE doc_file_folder f
LEFT JOIN sys_user_info u ON u.id = f.creator_id AND u.status <> -1
SET f.owner_id = COALESCE(u.id, 1)
WHERE f.owner_id IS NULL;

-- Step 3: NOT NULL + 索引
ALTER TABLE doc_file_folder
  MODIFY COLUMN owner_id BIGINT NOT NULL,
  ADD INDEX idx_owner (owner_id);

-- Step 6（原 Step 4 之前的步骤，但表必须先建）：新表 doc_file_folder_acl + 唯一索引
-- 注：Step 4 依赖本表，所以必须先建表。
CREATE TABLE IF NOT EXISTS doc_file_folder_acl (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  folder_id       BIGINT      NOT NULL,
  principal_type  VARCHAR(8)  NOT NULL,
  principal_id    BIGINT      NULL,
  permission      VARCHAR(8)  NOT NULL,
  granted_by      BIGINT      NOT NULL,
  created_at      DATETIME    NOT NULL,
  revoked_at      DATETIME    NULL,
  KEY idx_folder (folder_id),
  KEY idx_principal (principal_type, principal_id, revoked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE doc_file_folder_acl
  ADD COLUMN active_marker TINYINT
  GENERATED ALWAYS AS (IF(revoked_at IS NULL, 1, NULL)) VIRTUAL;

CREATE UNIQUE INDEX uk_acl_active
  ON doc_file_folder_acl (folder_id, principal_type, principal_id, active_marker);

-- Step 4: 转换老 isPublic=true → ORG ACL（依赖上一步已建表）
-- 注：V1__init.sql 不建 is_public 列（v0.5 spec §1.1 说"isPublic 不被任何查询使用"）
-- 所以本步要探测 is_public 列是否存在；存在才跑迁移
-- 用 information_schema + PREPARE/EXECUTE 动态 SQL 兼容 5.7+（同 Step 5 模式）
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'doc_file_folder'
    AND column_name = 'is_public'
);
SET @ddl = IF(@col_exists > 0,
  'INSERT INTO doc_file_folder_acl (folder_id, principal_type, principal_id, permission, granted_by, created_at, revoked_at)
   SELECT f.id, ''ORG'', NULL, ''READ'', COALESCE(u.id, 1), NOW(), NULL
   FROM doc_file_folder f
   LEFT JOIN sys_user_info u ON u.id = f.creator_id AND u.status <> -1
   WHERE f.is_public = 1 AND f.status <> -1',
  'DO 0'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Step 5: 删 is_public 列（如果存在）
-- MySQL 5.7 不支持 DROP COLUMN IF EXISTS（8.0.2+ 才有）
-- 用 information_schema 探测 + PREPARE/EXECUTE 动态 SQL 兼容 5.7+
-- DO 0 是 5.7+ 通用的 no-op 占位（保证 EXECUTE 拿到非空 SQL）
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'doc_file_folder'
    AND column_name = 'is_public'
);
SET @ddl = IF(@col_exists > 0,
  'ALTER TABLE doc_file_folder DROP COLUMN is_public',
  'DO 0'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Step 7: 新表 group
CREATE TABLE IF NOT EXISTS `group` (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  name            VARCHAR(64)  NOT NULL,
  description     VARCHAR(255) NULL,
  created_by      BIGINT       NOT NULL,
  created_at      DATETIME     NOT NULL,
  status          TINYINT      NOT NULL DEFAULT 1,
  UNIQUE KEY uk_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Step 8: 新表 group_member
CREATE TABLE IF NOT EXISTS group_member (
  group_id        BIGINT   NOT NULL,
  user_id         BIGINT   NOT NULL,
  joined_at       DATETIME NOT NULL,
  PRIMARY KEY (group_id, user_id),
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
