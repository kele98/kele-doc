package com.kele.core.db.migration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V2 共享/权限/群组 migration 静态校验。详见 spec §3.1 + docs/db/V2。
 *
 * <p>由于本 migration 使用 MySQL 9 专属的 generated virtual column 语法
 * （H2 / PostgreSQL 都不支持），无法用嵌入式数据库端到端跑。
 * 本测试在静态层校验：
 * <ol>
 *   <li>迁移文件存在且可读</li>
 *   <li>9 个 step 标记齐全且按顺序</li>
 *   <li>新表/新列/新索引/外键已声明</li>
 *   <li>关键数据回填（admin role=ADMIN、owner_id 回填）已声明</li>
 *   <li>迁移版本号唯一（不与 V1 冲突）</li>
 * </ol>
 *
 * <p>运行时端到端验证留给 smoke test（Task 34）。
 */
@DisplayName("V2__sharing.sql 静态校验")
class V2SharingMigrationTest {

    private static final Path V2_PATH = Paths.get(
            "src/main/resources/db/migration/V2__sharing.sql");

    private static String readSql() throws IOException {
        return new String(Files.readAllBytes(V2_PATH), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("迁移文件存在且非空")
    void fileExistsAndNotEmpty() throws IOException {
        assertTrue(Files.exists(V2_PATH), "V2__sharing.sql 必须存在: " + V2_PATH.toAbsolutePath());
        String sql = readSql();
        assertFalse(sql.trim().isEmpty(), "V2__sharing.sql 不应为空");
    }

    @Test
    @DisplayName("9 个 step 标记齐全（Step 0 / 0.5 / 0.7 / 1 / 2 / 3 / 4 / 5 / 6 / 7 / 8）")
    void allStepMarkersPresent() throws IOException {
        String sql = readSql();
        String[] expectedSteps = {
                "-- Step 0:",   // role + is_root
                "-- Step 0.5:", // is_root 虚拟列
                "-- Step 0.7:", // doc_recycle.folder_id
                "-- Step 1:",   // owner_id 列
                "-- Step 2:",   // owner_id 回填
                "-- Step 3:",   // owner_id NOT NULL + idx
                "-- Step 4:",   // isPublic → ORG ACL
                "-- Step 5:",   // drop is_public
                "-- Step 6:",   // doc_file_folder_acl + active_marker + uk
                "-- Step 7:",   // group 表
                "-- Step 8:"    // group_member 表
        };
        int prevIdx = -1;
        for (String marker : expectedSteps) {
            int idx = sql.indexOf(marker);
            assertTrue(idx > prevIdx,
                    "缺少或顺序错乱的 step: " + marker
                            + "（应在上一个 step 之后出现）");
            prevIdx = idx;
        }
    }

    @Test
    @DisplayName("Step 0: sys_user_info.role + id=1 是 ADMIN")
    void step0_roleAddedAndAdminSeeded() throws IOException {
        String sql = readSql();
        assertTrue(sql.contains("ALTER TABLE sys_user_info")
                        && sql.contains("ADD COLUMN role"),
                "Step 0: sys_user_info 必须加 role 列");
        assertTrue(sql.contains("idx_role"),
                "Step 0: role 列需索引 idx_role");
        assertTrue(sql.contains("UPDATE sys_user_info SET role = 'ADMIN' WHERE id = 1"),
                "Step 0: 必须把 id=1 提为 ADMIN（spec §10.2 B3）");
    }

    @Test
    @DisplayName("Step 0.5: doc_file_folder.is_root 虚拟列")
    void step0_5_isRootGeneratedColumn() throws IOException {
        String sql = readSql();
        assertTrue(sql.contains("GENERATED ALWAYS AS"),
                "Step 0.5: 需用 MySQL generated column 语法");
        assertTrue(sql.contains("is_root"),
                "Step 0.5: 需声明 is_root 虚拟列");
    }

    @Test
    @DisplayName("Step 0.7: doc_recycle.folder_id + 数据回填 + uk_folder_user")
    void step0_7_docRecycleFolderId() throws IOException {
        String sql = readSql();
        assertTrue(sql.contains("ALTER TABLE doc_recycle")
                        && sql.contains("ADD COLUMN folder_id"),
                "Step 0.7: doc_recycle 必须加 folder_id 列");
        assertTrue(sql.contains("UPDATE doc_recycle SET folder_id = id WHERE folder_id IS NULL"),
                "Step 0.7: 必须回填 folder_id = id");
        assertTrue(sql.contains("uk_folder_user"),
                "Step 0.7: 必须有 (folder_id, user_id) 唯一索引");
    }

    @Test
    @DisplayName("Step 1-3: doc_file_folder.owner_id NOT NULL + idx_owner")
    void step1To3_ownerIdNotNullWithIndex() throws IOException {
        String sql = readSql();
        assertTrue(sql.contains("ALTER TABLE doc_file_folder ADD COLUMN owner_id"),
                "Step 1: owner_id 列需声明");
        assertTrue(sql.contains("MODIFY COLUMN owner_id BIGINT NOT NULL"),
                "Step 3: owner_id 需 NOT NULL");
        assertTrue(sql.contains("idx_owner"),
                "Step 3: owner_id 需索引 idx_owner");
        // 回填必须存在并使用 COALESCE 兜底（应对 creator_id 已注销的情况）
        assertTrue(sql.contains("SET f.owner_id = COALESCE(u.id, 1)"),
                "Step 2: owner_id 回填需用 COALESCE 兜底到 1（系统用户）");
    }

    @Test
    @DisplayName("Step 4: 老 isPublic=true → ORG READ ACL")
    void step4_isPublicMigratedToOrgAcl() throws IOException {
        String sql = readSql();
        assertTrue(sql.contains("INSERT INTO doc_file_folder_acl"),
                "Step 4: 需把老公开文件夹导入 ACL 表");
        assertTrue(sql.contains("'ORG'"),
                "Step 4: 老 isPublic 应转为 ORG principal_type");
        assertTrue(sql.contains("'READ'"),
                "Step 4: 公开级别应为 READ");
    }

    @Test
    @DisplayName("Step 6: doc_file_folder_acl 表 + active_marker + uk_acl_active")
    void step6_aclTableWithActiveMarkerUnique() throws IOException {
        String sql = readSql();
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS doc_file_folder_acl"),
                "Step 6: doc_file_folder_acl 表需声明");
        for (String col : new String[] {
                "id", "folder_id", "principal_type", "principal_id",
                "permission", "granted_by", "created_at", "revoked_at"
        }) {
            assertTrue(sql.contains(col),
                    "Step 6: doc_file_folder_acl 需列 " + col);
        }
        assertTrue(sql.contains("active_marker"),
                "Step 6: 需 active_marker 虚拟列");
        assertTrue(sql.contains("CREATE UNIQUE INDEX uk_acl_active"),
                "Step 6: 需 uk_acl_active 唯一索引（防同主体重复授权）");
    }

    @Test
    @DisplayName("Step 7: group 表 + uk_name")
    void step7_groupTableWithUniqueName() throws IOException {
        String sql = readSql();
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `group`"),
                "Step 7: `group` 表（MySQL 保留字需反引号）");
        for (String col : new String[] {
                "id", "name", "description", "created_by", "created_at", "status"
        }) {
            assertTrue(sql.contains(col),
                    "Step 7: `group` 表需列 " + col);
        }
        assertTrue(sql.contains("uk_name"),
                "Step 7: name 列需唯一索引（spec §3.1 防重名）");
    }

    @Test
    @DisplayName("Step 8: group_member 表 + 联合主键")
    void step8_groupMemberTable() throws IOException {
        String sql = readSql();
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS group_member"),
                "Step 8: group_member 表需声明");
        for (String col : new String[] {"group_id", "user_id", "joined_at"}) {
            assertTrue(sql.contains(col),
                    "Step 8: group_member 需列 " + col);
        }
        assertTrue(sql.contains("PRIMARY KEY (group_id, user_id)"),
                "Step 8: 需 (group_id, user_id) 联合主键");
    }

    @Test
    @DisplayName("无重复 step 标记（防脚本被破坏）")
    void noDuplicateStepMarkers() throws IOException {
        String sql = readSql();
        // 一些短标记可能在注释中被反复提到，但 step header 应只出现 1 次
        for (String marker : new String[] {
                "-- Step 1:", "-- Step 2:", "-- Step 3:",
                "-- Step 6:", "-- Step 7:", "-- Step 8:"
        }) {
            int first = sql.indexOf(marker);
            assertTrue(first >= 0, "缺少标记 " + marker);
            int second = sql.indexOf(marker, first + 1);
            assertTrue(second < 0, "step 标记重复 " + marker + " @ 偏移 " + second);
        }
    }

    @Test
    @DisplayName("文件内无 TODO / 占位符（spec §6.4 no-placeholder）")
    void noPlaceholdersInSql() throws IOException {
        String sql = readSql();
        for (String bad : new String[] {"TODO", "FIXME", "TBD", "PLACEHOLDER", "${ADMIN_USER_ID}"}) {
            assertFalse(sql.contains(bad), "迁移文件内不应含占位符: " + bad);
        }
    }

    @Test
    @DisplayName("迁移目录里 V2 是 V1 之后的唯一版本（不与未来迁移冲突）")
    void v2IsAfterV1AndUnique() throws IOException {
        Path migrationDir = V2_PATH.getParent();
        assertNotNull(migrationDir);
        List<String> versions = new ArrayList<>();
        try (Stream<Path> stream = Files.list(migrationDir)) {
            stream.filter(p -> p.getFileName().toString().matches("V\\d+__.+\\.sql"))
                    .forEach(p -> versions.add(p.getFileName().toString()));
        }
        assertTrue(versions.contains("V2__sharing.sql"),
                "V2__sharing.sql 应在迁移目录中: " + versions);
        // 同一目录里 V2 应唯一
        long v2Count = versions.stream().filter(n -> n.startsWith("V2__")).count();
        assertTrue(v2Count == 1, "V2__* 应唯一，当前 = " + v2Count);
    }
}
