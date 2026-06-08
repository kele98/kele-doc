-- ===========================================
-- V3: doc_file_folder_acl 加 revoke_reason 列
-- 背景：restoreGroup 之前用"revoked_at IS NOT NULL"全量复活该群组的 ACL 行，
--      会误复活"用户手动撤销"或"grantAcl replace 撤销"的行——静默权限提升漏洞。
-- 修复：所有 revoke 写入原因，restoreGroup 只精确复活 GROUP_DISSOLVE 行。
--
-- 兼容性：MySQL 5.7+
-- ===========================================

-- Step 1: 加列
ALTER TABLE doc_file_folder_acl
  ADD COLUMN revoke_reason VARCHAR(32) NULL COMMENT '撤销原因：GROUP_DISSOLVE/MANUAL/REPLACE，NULL=未撤销或复活后清空'
  AFTER revoked_at;

-- Step 2: 回填历史 revoked 行
-- 保守策略：所有现存 revoked_at != NULL 的行打 MANUAL（不视为 GROUP_DISSOLVE）。
-- 原因：无法精确判断历史行是因解散还是其他原因被 revoke；
--       保守归为 MANUAL 可避免"已被手动撤销的 ACL 在下一次 restoreGroup 时被静默复活"。
-- 副作用：如果当前正存在"已解散但未恢复"的群组，对应 ACL 行复活时机往后推（须等下次重新 grant）；
--         这比"静默复活已被撤销的权限"安全得多。
UPDATE doc_file_folder_acl
SET revoke_reason = 'MANUAL'
WHERE revoked_at IS NOT NULL AND revoke_reason IS NULL;

-- Step 3: 加索引（principal_type + principal_id + revoke_reason 用于 restoreGroup 精确定位）
ALTER TABLE doc_file_folder_acl
  ADD INDEX idx_principal_reason (principal_type, principal_id, revoke_reason);
