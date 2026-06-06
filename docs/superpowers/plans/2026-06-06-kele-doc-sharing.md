# kele-doc 共享/权限/群组 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement v0.12 spec — kele-doc sharing/permission/group feature with 3-level permission (READ/WRITE/MANAGE), flat groups, ACL inheritance, soft-delete via doc_recycle, single-tenant.

**Architecture:** Spring Boot 2.2.6 + MyBatis-Plus 3.5.3.1 + MySQL 9.3.0 backend with CTE-based permission resolution; Vue 3 + Pinia + Element Plus frontend. Migration via Flyway 8.5.13. The full design is in `docs/superpowers/specs/2026-06-06-kele-doc-sharing-design.md` (v0.12) — every task below references that spec rather than restating details.

**Tech Stack:**
- Java 8 (target 1.8), Spring Boot 2.2.6.RELEASE, MyBatis-Plus 3.5.3.1
- Flyway 8.5.13, flyway-mysql 8.5.13
- MySQL 9.3.0 (CTE, generated columns, `IF(revoked_at IS NULL, 1, NULL)`)
- Lombok 1.18.20 (requires JDK 8 to build — see "Build environment" below)
- Vue 3 + Pinia 2 + Element Plus (frontend)
- Build env: `JAVA_HOME=D:/SDKs/JDK/jdk1.8.0_202` (Lombok 1.18.20 incompatible with JDK 25)

**Reference spec:** `docs/superpowers/specs/2026-06-06-kele-doc-sharing-design.md` (v0.12)
- §3 数据模型, §4 API, §5 权限引擎, §6 迁移, §6.2 代码变更清单 (52 行), §7 软删, §10 前端

**§6.2 变更清单 is the ground truth for every Java file change** — this plan sequences and elaborates, never contradicts.

---

## Phase 1: Backend Foundation (DB + Entities + Config)

### Task 1: Add Flyway 8.5.13 dependencies

**Files:**
- Modify: `pom.xml:23-38` (properties)
- Modify: `pom.xml:40-252` (dependencyManagement)
- Modify: `kele-core/pom.xml:1-254`

- [ ] **Step 1: Add flyway.version property to root `pom.xml`**

In root `pom.xml` `<properties>` block (around line 23-38), add:
```xml
<flyway.version>8.5.13</flyway.version>
<flyway-mysql.version>8.5.13</flyway-mysql.version>
```

- [ ] **Step 2: Add flyway-core + flyway-mysql to root `pom.xml` `dependencyManagement`** (after line 251, before `</dependencies>`)

```xml
<!-- Flyway 8.5.13 (Spring Boot 2.2.6 BOM 默认 5.x 不支持 MySQL 9.3.0，需显式锁定) -->
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-core</artifactId>
  <version>${flyway.version}</version>
</dependency>
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-mysql</artifactId>
  <version>${flyway-mysql.version}</version>
</dependency>
```

- [ ] **Step 3: Verify build compiles**

```bash
cd D:/Projects/Github/kele-doc
export JAVA_HOME="D:/SDKs/JDK/jdk1.8.0_202"
export PATH="D:/SDKs/JDK/jdk1.8.0_202/bin:$PATH"
mvn -B clean compile
```

Expected: `BUILD SUCCESS`. (If fail: confirm `JAVA_HOME` is JDK 8, not JDK 25.)

- [ ] **Step 4: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add pom.xml kele-core/pom.xml
git commit -m "build: add flyway-core 8.5.13 and flyway-mysql for V2 migrations"
```

---

### Task 2: Add `kele.doc.startup.admin-check.enabled` config + Flyway config to `application.yml`

**Files:**
- Modify: `kele-core/src/main/resources/application.yml:1-104`

- [ ] **Step 1: Add Flyway + admin-check + permission-fallback config blocks**

Find the existing `spring:` block (line 52). After the existing spring config, add:
```yaml
spring:
  # ... existing spring config ...

  # Flyway (V2__sharing.sql migration)
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
    validate-on-migrate: false

kele:
  doc:
    # ... existing kele.doc.* config ...

    # v0.6 B3: 启动期校验 sys_user_info.id=1 存在且 role=ADMIN
    startup:
      admin-check:
        enabled: true

    # v0.6 M5: 权限引擎实现切换（false=CTE 主路径 / true=app-layer walk fallback）
    permission:
      fallback: false
```

(If `kele.doc.*` keys are still under `ty.doc.*` rename them to `kele.doc.*` per the v0.5 brand unification.)

- [ ] **Step 2: Verify YAML is valid**

```bash
cd D:/Projects/Github/kele-doc
export JAVA_HOME="D:/SDKs/JDK/jdk1.8.0_202"
mvn -B -pl kele-core process-resources
```

Expected: BUILD SUCCESS, no YAML parse errors.

- [ ] **Step 3: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-core/src/main/resources/application.yml
git commit -m "config: add flyway + admin-check + permission-fallback config"
```

---

### Task 3: Add `role` field to `SysUserInfo` entity + `UserInfoBO`

**Files:**
- Modify: `kele-core/.../buz/sys/dao/entity/SysUserInfo.java:1-58`
- Modify: `kele-core/.../buz/sys/model/bo/UserInfoBO.java` (find existing file)

- [ ] **Step 1: Add `role` field to `SysUserInfo.java`**

After line 55 (`private Integer status;`), add:
```java
@ApiModelProperty(value = "角色，USER=普通用户，ADMIN=管理员")
private String role;
```

- [ ] **Step 2: Add `role` field to `UserInfoBO.java`**

Locate the existing `UserInfoBO.java` (read it first via `find` if not already known). Add the same `role` field with getter/setter (Lombok `@Data` generates them).

- [ ] **Step 3: Verify compile**

```bash
cd D:/Projects/Github/kele-doc
export JAVA_HOME="D:/SDKs/JDK/jdk1.8.0_202"
mvn -B -pl kele-core compile
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-core/src/main/java/com/kele/core/buz/sys/dao/entity/SysUserInfo.java kele-core/src/main/java/com/kele/core/buz/sys/model/bo/UserInfoBO.java
git commit -m "feat(user): add role field to SysUserInfo entity and UserInfoBO (B3)"
```

---

### Task 4: Update `SysUserInfoMapper.xml` to SELECT `role` + login flow loads role

**Files:**
- Modify: `kele-core/.../buz/sys/dao/mapper/SysUserInfoMapper.xml` (find existing `select` blocks)
- Modify: `kele-core/.../buz/sys/ao/impl/SysUserInfoAOImpl.java` (find existing login method)

- [ ] **Step 1: Add `role` to all SELECT column lists in `SysUserInfoMapper.xml`**

Open the XML. Find every `<sql id="Base_Column_List">` or every `select *` literal. Add `role` to the column list. Example:
```xml
<sql id="Base_Column_List">
    id, user_name, account, password, avatar, create_at, version, update_at, status, role
</sql>
```

If there are multiple `Base_Column_List` definitions (Base_Where_Clause etc.), update all of them.

- [ ] **Step 2: In `SysUserInfoAOImpl.java` login method, populate `userInfoBO.role`**

Find the login method (likely `login(...)` or `getUserInfo(...)`). After setting other fields on the `UserInfoBO` instance, add:
```java
userInfoBO.setRole(sysUserInfo.getRole());
```

- [ ] **Step 3: Verify compile**

```bash
cd D:/Projects/Github/kele-doc
export JAVA_HOME="D:/SDKs/JDK/jdk1.8.0_202"
mvn -B -pl kele-core compile
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-core/src/main/java/com/kele/core/buz/sys/dao/mapper/SysUserInfoMapper.xml kele-core/src/main/java/com/kele/core/buz/sys/ao/impl/SysUserInfoAOImpl.java
git commit -m "feat(user): load role on login (B3)"
```

---

### Task 5: Create `KeleDocApplicationRunner` (startup admin check)

**Files:**
- Create: `kele-core/src/main/java/com/kele/core/KeleDocApplicationRunner.java`

- [ ] **Step 1: Create the runner class**

```java
package com.kele.core;

import com.kele.core.buz.sys.dao.entity.SysUserInfo;
import com.kele.core.buz.sys.dao.mapper.SysUserInfoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动期校验：sys_user_info.id=1 必须存在且 role='ADMIN'，否则拒绝启动。
 * 详见 spec §6.3。
 */
@Slf4j
@Component
@Order(0)
@ConditionalOnProperty(name = "kele.doc.startup.admin-check.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class KeleDocApplicationRunner implements ApplicationRunner {

    private final SysUserInfoMapper sysUserInfoMapper;

    @Override
    public void run(ApplicationArguments args) {
        SysUserInfo admin = sysUserInfoMapper.selectById(1L);
        if (admin == null || !"ADMIN".equals(admin.getRole())) {
            throw new IllegalStateException(
                "[Kele-Doc] 启动失败：sys_user_info.id=1 用户必须存在且 role='ADMIN'。" +
                "当前: " + (admin == null ? "NULL" : "id=" + admin.getId() + ", role=" + admin.getRole()) +
                "。请参考 docs/superpowers/specs/2026-06-06-kele-doc-sharing-design.md §6 修复。"
            );
        }
        log.info("[Kele-Doc] 启动校验通过：admin user id=1 role=ADMIN");
    }
}
```

- [ ] **Step 2: Verify compile**

```bash
cd D:/Projects/Github/kele-doc
export JAVA_HOME="D:/SDKs/JDK/jdk1.8.0_202"
mvn -B -pl kele-core compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-core/src/main/java/com/kele/core/KeleDocApplicationRunner.java
git commit -m "feat: KeleDocApplicationRunner startup admin check (B2)"
```

---

### Task 6: Create Flyway V2 migration SQL

**Files:**
- Create: `kele-core/src/main/resources/db/migration/V2__sharing.sql`

- [ ] **Step 1: Create the migration directory + file**

```bash
mkdir -p D:/Projects/Github/kele-doc/kele-core/src/main/resources/db/migration
```

- [ ] **Step 2: Write V2__sharing.sql**

(For full SQL content see spec §3.1 + §6. Step-by-step ordering matters — Step 0 must precede Step 1 because Step 1 references sys_user_info.role.)

Create `D:/Projects/Github/kele-doc/kele-core/src/main/resources/db/migration/V2__sharing.sql` with the following content. **Verify your spec PDF (v0.12) for exact DDL details — copy verbatim from §3.1 and §6 of the spec:**

```sql
-- ===========================================
-- V2: kele-doc 共享/权限/群组
-- 对应 spec: docs/superpowers/specs/2026-06-06-kele-doc-sharing-design.md v0.12
-- 依赖：V1__init.sql 已建好 sys_user_info / doc_file_folder / doc_recycle 等基表
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

-- Step 4: 转换老 isPublic=true → ORG ACL
INSERT INTO doc_file_folder_acl (folder_id, principal_type, principal_id, permission, granted_by, created_at, revoked_at)
SELECT f.id, 'ORG', NULL, 'READ', COALESCE(u.id, 1), NOW(), NULL
FROM doc_file_folder f
LEFT JOIN sys_user_info u ON u.id = f.creator_id AND u.status <> -1
WHERE f.is_public = 1 AND f.status <> -1;
-- 注：若 f.is_public 列不存在（schema 缺），本步是 noop；V3 之前由应用层负责兼容

-- Step 5: 删 is_public 列（如果存在）
ALTER TABLE doc_file_folder DROP COLUMN IF EXISTS is_public;

-- Step 6: 新表 doc_file_folder_acl + 唯一索引
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
```

- [ ] **Step 3: Verify file is on classpath**

```bash
cd D:/Projects/Github/kele-doc
ls kele-core/src/main/resources/db/migration/V2__sharing.sql
```

Expected: file exists, ~70+ lines.

- [ ] **Step 4: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-core/src/main/resources/db/migration/V2__sharing.sql
git commit -m "feat(db): V2__sharing.sql migration (groups, ACL, owner_id, doc_recycle refactor)"
```

---

### Task 7: Add new error codes to `ErrorCodeEnum`

**Files:**
- Modify: `kele-common/.../enums/ErrorCodeEnum.java`

- [ ] **Step 1: Add the new error codes**

Read the file first. Add to the enum (preserving the existing pattern):
```java
PERMISSION_DENIED(403, "权限不足"),
RESOURCE_NOT_VISIBLE(404, "资源不存在或无权访问"),
GROUP_NAME_DUPLICATE(409, "群组名已存在"),
USER_ALREADY_IN_GROUP(409, "用户已在群组中"),
ACL_DUPLICATE(409, "该主体已被授权"),
CANNOT_REMOVE_LAST_MANAGE(409, "至少需要保留一个管理者"),
GROUP_HAS_MEMBERS(409, "请先清空群组成员"),
GROUP_NOT_FOUND(404, "群组不存在"),
GROUP_DISMISSED(409, "群组已解散"),
USER_NOT_FOUND(404, "用户不存在"),
ORG_PUBLIC_PERMISSION_INVALID(400, "组织内公开仅支持 READ 权限"),
INVALID_ACL_PRINCIPAL_TYPE(400, "非法的主体类型"),
```

- [ ] **Step 2: Verify compile**

```bash
cd D:/Projects/Github/kele-doc
export JAVA_HOME="D:/SDKs/JDK/jdk1.8.0_202"
mvn -B -pl kele-common install -DskipTests
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-common/src/main/java/com/kele/common/enums/ErrorCodeEnum.java
git commit -m "feat(common): add new error codes for ACL/group operations"
```

---

### Task 8: Create `DocFileFolderAcl` entity

**Files:**
- Create: `kele-core/.../buz/doc/dao/entity/DocFileFolderAcl.java`

- [ ] **Step 1: Create entity**

```java
package com.kele.core.buz.doc.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * 文档文件夹 ACL（统一 ACL 表）。详见 spec §3.1。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@ApiModel(value = "DocFileFolderAcl", description = "文档文件夹 ACL")
public class DocFileFolderAcl implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "id")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @ApiModelProperty(value = "资源ID")
    private Long folderId;

    @ApiModelProperty(value = "主体类型：USER / GROUP / ORG")
    private String principalType;

    @ApiModelProperty(value = "主体ID：USER=userId, GROUP=groupId, ORG=NULL")
    private Long principalId;

    @ApiModelProperty(value = "权限：READ / WRITE / MANAGE")
    private String permission;

    @ApiModelProperty(value = "授权人ID")
    private Long grantedBy;

    @ApiModelProperty(value = "创建时间")
    private LocalDateTime createdAt;

    @ApiModelProperty(value = "撤销时间，NULL=有效")
    @TableLogic
    private LocalDateTime revokedAt;
}
```

Note: `@TableLogic` on `revokedAt` enables MyBatis-Plus logical delete via `WHERE revoked_at IS NULL`. Configure `mybatis-plus.global-config.db-config.logic-delete-field` to use `revokedAt`. (Alternative: don't use `@TableLogic` and write custom queries with `revoked_at IS NULL`.)

- [ ] **Step 2: Create mapper interface**

Create `kele-core/.../buz/doc/dao/mapper/DocFileFolderAclMapper.java`:
```java
package com.kele.core.buz.doc.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kele.core.buz.doc.dao.entity.DocFileFolderAcl;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocFileFolderAclMapper extends BaseMapper<DocFileFolderAcl> {
}
```

- [ ] **Step 3: Create mapper XML**

Create `kele-core/src/main/resources/mybatis/DocFileFolderAclMapper.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.kele.core.buz.doc.dao.mapper.DocFileFolderAclMapper">
    <resultMap id="BaseResultMap" type="com.kele.core.buz.doc.dao.entity.DocFileFolderAcl">
        <id column="id" property="id"/>
        <result column="folder_id" property="folderId"/>
        <result column="principal_type" property="principalType"/>
        <result column="principal_id" property="principalId"/>
        <result column="permission" property="permission"/>
        <result column="granted_by" property="grantedBy"/>
        <result column="created_at" property="createdAt"/>
        <result column="revoked_at" property="revokedAt"/>
    </resultMap>
</mapper>
```

- [ ] **Step 4: Verify compile**

```bash
cd D:/Projects/Github/kele-doc
export JAVA_HOME="D:/SDKs/JDK/jdk1.8.0_202"
mvn -B -pl kele-core compile
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-core/src/main/java/com/kele/core/buz/doc/dao/entity/DocFileFolderAcl.java kele-core/src/main/java/com/kele/core/buz/doc/dao/mapper/DocFileFolderAclMapper.java kele-core/src/main/resources/mybatis/DocFileFolderAclMapper.xml
git commit -m "feat(doc): DocFileFolderAcl entity + mapper (B1)"
```

---

### Task 9: Create `KeleGroup` and `GroupMember` entities + mappers

**Files:**
- Create: `kele-core/.../buz/doc/dao/entity/KeleGroup.java`
- Create: `kele-core/.../buz/doc/dao/entity/GroupMember.java`
- Create: `kele-core/.../buz/doc/dao/mapper/KeleGroupMapper.java`
- Create: `kele-core/.../buz/doc/dao/mapper/GroupMemberMapper.java`
- Create: `kele-core/src/main/resources/mybatis/KeleGroupMapper.xml`
- Create: `kele-core/src/main/resources/mybatis/GroupMemberMapper.xml`

- [ ] **Step 1: Create KeleGroup entity**

```java
package com.kele.core.buz.doc.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * 群组。表名 `group` 是 MySQL 保留字，必须加反引号。
 * 类名 KeleGroup 避免与 java.lang.Object#getClass 反射冲突。
 * @TableName("`group`") 必须显式反引号。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("`group`")
@ApiModel(value = "KeleGroup", description = "群组")
public class KeleGroup implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "id")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @ApiModelProperty(value = "群组名")
    private String name;

    @ApiModelProperty(value = "群组描述")
    private String description;

    @ApiModelProperty(value = "创建人ID")
    private Long createdBy;

    @ApiModelProperty(value = "创建时间")
    private LocalDateTime createdAt;

    @ApiModelProperty(value = "1=正常，0=已解散")
    private Integer status;
}
```

- [ ] **Step 2: Create GroupMember entity**

```java
package com.kele.core.buz.doc.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.annotations.ApiModel;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("group_member")
@ApiModel(value = "GroupMember", description = "群组成员")
public class GroupMember implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long groupId;
    private Long userId;
    private LocalDateTime joinedAt;
}
```

- [ ] **Step 3: Create mappers + XML**

```java
// KeleGroupMapper.java
package com.kele.core.buz.doc.dao.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kele.core.buz.doc.dao.entity.KeleGroup;
import org.apache.ibatis.annotations.Mapper;
@Mapper
public interface KeleGroupMapper extends BaseMapper<KeleGroup> {}

// GroupMemberMapper.java
package com.kele.core.buz.doc.dao.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kele.core.buz.doc.dao.entity.GroupMember;
import org.apache.ibatis.annotations.Mapper;
@Mapper
public interface GroupMemberMapper extends BaseMapper<GroupMember> {}
```

XML files mirror the BaseResultMap pattern from Task 8 (use `group` with backticks in namespace).

- [ ] **Step 4: Verify compile**

```bash
cd D:/Projects/Github/kele-doc
export JAVA_HOME="D:/SDKs/JDK/jdk1.8.0_202"
mvn -B -pl kele-core compile
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-core/src/main/java/com/kele/core/buz/doc/dao/entity/KeleGroup.java kele-core/src/main/java/com/kele/core/buz/doc/dao/entity/GroupMember.java kele-core/src/main/java/com/kele/core/buz/doc/dao/mapper/KeleGroupMapper.java kele-core/src/main/java/com/kele/core/buz/doc/dao/mapper/GroupMemberMapper.java kele-core/src/main/resources/mybatis/KeleGroupMapper.xml kele-core/src/main/resources/mybatis/GroupMemberMapper.xml
git commit -m "feat(doc): KeleGroup + GroupMember entities (M4)"
```

---

### Task 10: Update `DocFileFolder` entity — add ownerId, isRoot(), remove isPublic

**Files:**
- Modify: `kele-core/.../buz/doc/dao/entity/DocFileFolder.java`

- [ ] **Step 1: Add `ownerId` field**

After line 70 (`private Long creatorId;`), add:
```java
@ApiModelProperty(value = "所有者用户ID")
private Long ownerId;
```

- [ ] **Step 2: Remove `isPublic` field**

Delete the lines containing `isPublic` field (around line 59-60 in the original):
```java
// DELETE THESE LINES:
@ApiModelProperty(value = "是否公开")
private Boolean isPublic;
```

- [ ] **Step 3: Add `isRoot()` method**

Add after the class fields (or in a logical place):
```java
public boolean isRoot() {
    return parentId == null || parentId == 0L;
}
```

- [ ] **Step 4: Verify compile**

```bash
cd D:/Projects/Github/kele-doc
export JAVA_HOME="D:/SDKs/JDK/jdk1.8.0_202"
mvn -B -pl kele-core compile
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-core/src/main/java/com/kele/core/buz/doc/dao/entity/DocFileFolder.java
git commit -m "feat(doc): add ownerId + isRoot() to DocFileFolder, remove isPublic (B1, m13)"
```

---

### Task 11: Update `DocRecycle` entity — add folderId, deprecate id-as-folderId

**Files:**
- Modify: `kele-core/.../buz/doc/dao/entity/DocRecycle.java`

- [ ] **Step 1: Add `folderId` field + `idList` field (existing)**

Open the file. Confirm `idList: List<Long>` exists (it does per the existing code). Add `folderId`:
```java
@ApiModelProperty(value = "被回收的文件夹ID")
private Long folderId;
```

Note: the existing `id` field semantics change — it is now the recycle record's auto-increment PK, NOT the folder id. Do not rename or remove.

- [ ] **Step 2: Update Mapper XML**

Open `DocRecycleMapper.xml`. Add `folder_id` column to BaseResultMap.

- [ ] **Step 3: Verify compile**

```bash
cd D:/Projects/Github/kele-doc
export JAVA_HOME="D:/SDKs/JDK/jdk1.8.0_202"
mvn -B -pl kele-core compile
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-core/src/main/java/com/kele/core/buz/doc/dao/entity/DocRecycle.java kele-core/src/main/resources/mybatis/DocRecycleMapper.xml
git commit -m "feat(doc): add folderId to DocRecycle (B5)"
```

---

## Phase 2: Permission Engine

### Task 12: Create `PermissionLevel` enum and `PermissionResult` class

**Files:**
- Create: `kele-core/.../buz/doc/permission/PermissionLevel.java`
- Create: `kele-core/.../buz/doc/permission/PermissionResult.java`

- [ ] **Step 1: Create `PermissionLevel`**

```java
package com.kele.core.buz.doc.permission;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PermissionLevel {
    NONE(0),
    READ(1),
    WRITE(2),
    MANAGE(3);

    private final int value;

    public boolean atLeast(PermissionLevel other) {
        return this.value >= other.value;
    }
}
```

- [ ] **Step 2: Create `PermissionResult`**

```java
package com.kele.core.buz.doc.permission;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class PermissionResult {
    public enum Source { OWNER, DIRECT, GROUP, INHERITED, ORG, NONE }

    private final PermissionLevel level;
    private final Source source;
    private final Long sourceNodeId;  // which folder id this came from (for UI debug)

    public static PermissionResult none() {
        return new PermissionResult(PermissionLevel.NONE, Source.NONE, null);
    }

    public static PermissionResult owner(Long nodeId) {
        return new PermissionResult(PermissionLevel.MANAGE, Source.OWNER, nodeId);
    }

    public static PermissionResult acl(PermissionLevel level, Source source, Long nodeId) {
        return new PermissionResult(level, source, nodeId);
    }
}
```

- [ ] **Step 3: Verify compile**

```bash
cd D:/Projects/Github/kele-doc
export JAVA_HOME="D:/SDKs/JDK/jdk1.8.0_202"
mvn -B -pl kele-core compile
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-core/src/main/java/com/kele/core/buz/doc/permission/PermissionLevel.java kele-core/src/main/java/com/kele/core/buz/doc/permission/PermissionResult.java
git commit -m "feat(perm): PermissionLevel enum and PermissionResult class (B1)"
```

---

### Task 13: Create `PermissionService` (CTE primary, app-layer fallback)

**Files:**
- Create: `kele-core/.../buz/doc/permission/PermissionService.java`
- Test: `kele-core/src/test/java/com/kele/core/buz/doc/permission/PermissionServiceTest.java`

- [ ] **Step 1: Write the failing test first (TDD)**

```java
package com.kele.core.buz.doc.permission;

import org.junit.Test;
import static org.junit.Assert.*;

public class PermissionServiceTest {

    @Test
    public void testOwnerHasManage() {
        // Setup: 模拟 PermissionService（mock Mapper）
        // Expect: Owner 命中返回 MANAGE/OWNER
    }

    @Test
    public void testDirectAclGrants() {
        // Setup: USER ACL 直接命中
        // Expect: 返回该 permission 等级 + source=DIRECT
    }

    @Test
    public void testInheritanceCascades() {
        // Setup: 父有 ORG ACL，子有 ACL 行
        // Expect: 子通过继承也算命中
    }

    @Test
    public void testHigherWins() {
        // Setup: 同 folder 上有 ORG READ + USER WRITE
        // Expect: 选 WRITE 优先
    }
}
```

(占位 stub —— full implementation needs Mockito mocks for the mappers. 在 Phase 6 (Task 26) 一次性把 4 个 unit test 实写完整。)

- [ ] **Step 2: Run tests to verify they fail (compile-error)**

```bash
cd D:/Projects/Github/kele-doc
export JAVA_HOME="D:/SDKs/JDK/jdk1.8.0_202"
mvn -B -pl kele-core test -Dtest=PermissionServiceTest
```

Expected: compile failure (PermissionService doesn't exist yet).

- [ ] **Step 3: Implement `PermissionService`**

Create `kele-core/.../buz/doc/permission/PermissionService.java`:
```java
package com.kele.core.buz.doc.permission;

import com.kele.core.buz.doc.dao.entity.DocFileFolder;
import com.kele.core.buz.doc.dao.entity.DocFileFolderAcl;
import com.kele.core.buz.doc.dao.mapper.DocFileFolderAclMapper;
import com.kele.core.buz.doc.dao.mapper.DocFileFolderMapper;
import com.kele.core.buz.doc.dao.mapper.GroupMemberMapper;
import com.kele.core.other.context.LoginContext;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

/**
 * 权限解析引擎。详见 spec §5.2 (app-layer fallback) + §5.3 (CTE primary)。
 * 默认走 CTE；kele.doc.permission.fallback=true 切 app-layer walk。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "kele.doc.permission.fallback", havingValue = "true")
public class PermissionService {

    @Autowired private DocFileFolderMapper docFileFolderMapper;
    @Autowired private DocFileFolderAclMapper docFileFolderAclMapper;
    @Autowired private GroupMemberMapper groupMemberMapper;

    public PermissionResult resolve(Long userId, Long folderId) {
        if (userId == null || folderId == null) return PermissionResult.none();
        List<Long> groups = groupMemberMapper.selectGroupIdsByUserId(userId);
        return resolveAppLayer(userId, folderId, groups == null ? Collections.emptyList() : groups);
    }

    /** App-layer walk（fallback）—— 完整算法见 spec §5.2 */
    private PermissionResult resolveAppLayer(Long userId, Long folderId, List<Long> groupIds) {
        PermissionResult best = PermissionResult.none();
        Long cursor = folderId;
        while (cursor != null) {
            DocFileFolder folder = docFileFolderMapper.selectById(cursor);
            if (folder == null) break;

            if (userId.equals(folder.getOwnerId())) {
                return PermissionResult.owner(cursor);
            }
            List<DocFileFolderAcl> acls = docFileFolderAclMapper.selectActiveByFolder(cursor);
            for (DocFileFolderAcl acl : acls) {
                if (!match(acl, userId, groupIds)) continue;
                PermissionLevel level = PermissionLevel.valueOf(acl.getPermission());
                PermissionResult.Source source = (cursor.equals(folderId))
                    ? PermissionResult.Source.DIRECT
                    : ("GROUP".equals(acl.getPrincipalType())
                        ? PermissionResult.Source.GROUP
                        : PermissionResult.Source.INHERITED);
                PermissionResult candidate = PermissionResult.acl(level, source, cursor);
                if (betterOf(best, candidate) == candidate) best = candidate;
                if (best.getLevel() == PermissionLevel.MANAGE && best.getSource() == PermissionResult.Source.DIRECT) {
                    return best;
                }
            }
            cursor = folder.getParentId();
        }
        return best;
    }

    private boolean match(DocFileFolderAcl acl, Long userId, List<Long> groupIds) {
        if ("USER".equals(acl.getPrincipalType())) return userId.equals(acl.getPrincipalId());
        if ("GROUP".equals(acl.getPrincipalType())) return groupIds.contains(acl.getPrincipalId());
        return "ORG".equals(acl.getPrincipalType());
    }

    private PermissionResult betterOf(PermissionResult a, PermissionResult b) {
        int rankA = sourceRank(a.getSource());
        int rankB = sourceRank(b.getSource());
        if (rankA != rankB) return rankA > rankB ? a : b;
        return a.getLevel().getValue() >= b.getLevel().getValue() ? a : b;
    }

    private int sourceRank(PermissionResult.Source s) {
        switch (s) {
            case OWNER: return 4;
            case DIRECT: return 3;
            case GROUP: return 2;
            case INHERITED: return 1;
            default: return 0;
        }
    }

    /** 鉴权并抛错——MANAGE 级别以上 */
    public void requireManage(Long folderId) {
        PermissionResult r = resolve(LoginContext.getUserId(), folderId);
        if (!r.getLevel().atLeast(PermissionLevel.MANAGE)) {
            throw new com.kele.common.exception.BusinessException(
                com.kele.common.enums.ErrorCodeEnum.PERMISSION_DENIED.getCode(),
                "权限不足");
        }
    }
    public void requireRead(Long folderId) { /* 同上，检查 READ */ }
    public void requireWrite(Long folderId) { /* 同上，检查 WRITE */ }
}
```

- [ ] **Step 4: Verify compile**

```bash
cd D:/Projects/Github/kele-doc
export JAVA_HOME="D:/SDKs/JDK/jdk1.8.0_202"
mvn -B -pl kele-core compile
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-core/src/main/java/com/kele/core/buz/doc/permission/PermissionService.java kele-core/src/test/java/com/kele/core/buz/doc/permission/PermissionServiceTest.java
git commit -m "feat(perm): PermissionService with app-layer walk (M5 fallback)"
```

> **Note for CTE primary**: 生产主路径走 CTE。Task 13 实现的是 fallback。要在生产启用 CTE，需要在 `PermissionService` 之上加一个 `@Primary` 的 bean，使用单 SQL 调 `permissionMapper.findMyPermittedFolders(userId, groupIds)` —— 此 mapper SQL 详见 spec §5.3。这个生产主实现可在 Phase 6 性能验证后单独加 bean（如果 app-layer 性能不够）。

- [ ] **Step 6: Add a stub for the CTE primary path (mark as todo)**

Create a `PermissionServiceCtePrimary.java` sibling class with @Primary annotation, throwing `UnsupportedOperationException("not yet implemented")`. This way the @ConditionalOnProperty allows runtime switching:

```java
@Service
@Primary
@ConditionalOnProperty(name = "kele.doc.permission.fallback", havingValue = "false", matchIfMissing = true)
public class PermissionServiceCtePrimary extends PermissionService {
    @Override
    public PermissionResult resolve(Long userId, Long folderId) {
        throw new UnsupportedOperationException("CTE primary not yet implemented; set kele.doc.permission.fallback=true");
    }
    // requireManage/requireRead/requireWrite inherited from base
}
```

- [ ] **Step 7: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-core/src/main/java/com/kele/core/buz/doc/permission/PermissionServiceCtePrimary.java
git commit -m "feat(perm): PermissionServiceCtePrimary stub (M5)"
```

---

### Task 14: Add `userGroupIds` to `LoginContext`

**Files:**
- Modify: `kele-core/.../other/context/LoginContext.java:1-35`

- [ ] **Step 1: Add `userGroupIds` field**

```java
package com.kele.core.other.context;

import com.kele.core.buz.sys.model.bo.UserInfoBO;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;

public class LoginContext {

    private static final ThreadLocal<UserInfoBO> USER_INFO = new ThreadLocal<>();
    private static final ThreadLocal<List<Long>> USER_GROUP_IDS = new ThreadLocal<>();

    public static void setUserInfo(UserInfoBO userInfoBO) {
        USER_INFO.set(userInfoBO);
    }

    public static UserInfoBO getUserInfo() {
        return USER_INFO.get();
    }

    public static Long getUserId() {
        UserInfoBO userInfoBO = LoginContext.getUserInfo();
        return Objects.isNull(userInfoBO) ? null : userInfoBO.getId();
    }

    public static String getAccount() {
        UserInfoBO userInfoBO = LoginContext.getUserInfo();
        return Objects.isNull(userInfoBO) ? null : userInfoBO.getAccount();
    }

    public static boolean isAdmin() {
        UserInfoBO userInfoBO = LoginContext.getUserInfo();
        return userInfoBO != null && "ADMIN".equals(userInfoBO.getRole());
    }

    public static void setUserGroupIds(List<Long> groupIds) {
        USER_GROUP_IDS.set(groupIds == null ? Collections.emptyList() : groupIds);
    }

    public static List<Long> getUserGroupIds() {
        List<Long> ids = USER_GROUP_IDS.get();
        return ids == null ? Collections.emptyList() : ids;
    }

    public static void remove() {
        USER_INFO.remove();
        USER_GROUP_IDS.remove();
    }
}
```

- [ ] **Step 2: Wire groupId loading into the login interceptor**

Find `LoginHandlerInterceptor` (`kele-core/.../other/interceptor/LoginHandlerInterceptor.java`). After setting `LoginContext.setUserInfo(...)`, add:
```java
List<Long> groupIds = groupMemberMapper.selectGroupIdsByUserId(userId);
LoginContext.setUserGroupIds(groupIds);
```

You may need to add `@Autowired GroupMemberMapper` to the interceptor.

- [ ] **Step 3: Verify compile**

```bash
cd D:/Projects/Github/kele-doc
export JAVA_HOME="D:/SDKs/JDK/jdk1.8.0_202"
mvn -B -pl kele-core compile
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-core/src/main/java/com/kele/core/other/context/LoginContext.java kele-core/src/main/java/com/kele/core/other/interceptor/LoginHandlerInterceptor.java
git commit -m "feat(perm): LoginContext userGroupIds + isAdmin + interceptor wiring (B3)"
```

---

## Phase 3: AO Refactoring (the bulk of §6.2)

This phase executes ALL 50+ §6.2 changes. Tasks are grouped by file.

### Task 15: Refactor `AbstractDocFileFolderAO` (4 changes)

**Files:**
- Modify: `kele-core/.../buz/doc/ao/AbstractDocFileFolderAO.java`

Apply these in one commit (the file is small and the changes are tightly coupled):

- [ ] **Step 1: `getById` line 50-60 — replace creatorId check with PermissionService**

```java
protected DocFileFolder getById(Long id) {
    DocFileFolder docFileFolder = docFileFolderService.getById(id);
    if (Objects.isNull(docFileFolder) || !DelStatusEnum.NORMAL.getStatus().equals(docFileFolder.getStatus())) {
        throw new BusinessException(ErrorCodeEnum.RESOURCE_NOT_VISIBLE.getCode(),
            String.format("id为%s的文件夹不存在或无权访问", id));
    }
    permissionService.requireRead(id);
    return docFileFolder;
}
```

You'll need to add `@Autowired PermissionService` at the top of the class.

- [ ] **Step 2: `selectByIdList` line 62-87 — replace creatorId check with PermissionService**

```java
protected List<DocFileFolder> selectByIdList(List<Long> idList) {
    List<DocFileFolder> docFileFolderList = docFileFolderService.list(Wrappers.<DocFileFolder>lambdaQuery()
        .in(DocFileFolder::getId, idList)
        .eq(DocFileFolder::getStatus, DelStatusEnum.NORMAL.getStatus()));
    if (CollectionUtils.isEmpty(docFileFolderList)) {
        throw new BusinessException(ErrorCodeEnum.RESOURCE_NOT_VISIBLE.getCode(),
            String.format("id为%s的文件夹不存在或无权访问", idList));
    }
    Set<Long> idSet = docFileFolderList.stream().map(DocFileFolder::getId).collect(Collectors.toSet());
    List<Long> noExitsIdList = idList.stream().filter(id -> !idSet.contains(id)).collect(Collectors.toList());
    if (!CollectionUtils.isEmpty(noExitsIdList)) {
        throw new BusinessException(ErrorCodeEnum.RESOURCE_NOT_VISIBLE.getCode(),
            String.format("id为%s的文件夹不存在或无权访问", noExitsIdList));
    }
    // Permission check for each
    List<DocFileFolder> forbidList = new ArrayList<>();
    for (DocFileFolder f : docFileFolderList) {
        try { permissionService.requireRead(f.getId()); }
        catch (BusinessException e) { forbidList.add(f); }
    }
    if (!CollectionUtils.isEmpty(forbidList)) {
        throw new BusinessException(ErrorCodeEnum.PERMISSION_DENIED.getCode(),
            String.format("id为%s的资源禁止访问", forbidList.stream().map(f -> f.getId().toString()).collect(Collectors.joining(","))));
    }
    return docFileFolderList;
}
```

Also fix the bonus bug at line 83-84 (was using `noExitsIdList`, should be `forbidList`).

- [ ] **Step 3: `saveRecycleLevel` line 106-122 — getId → getFolderId**

Line 113: change `level.setParentId(docRecycle.getId())` to `level.setParentId(docRecycle.getFolderId())`.

- [ ] **Step 4: `getRecycleById` (helper, line 157-170) — refactor to lookup recycle first**

```java
private DocFileFolder getRecycleById(Long recycleId) {
    DocRecycle docRecycle = docRecycleService.getById(recycleId);
    if (Objects.isNull(docRecycle)) {
        throw new BusinessException(ErrorCodeEnum.RESOURCE_NOT_VISIBLE.getCode(),
            "回收记录不存在或已清空");
    }
    DocFileFolder docFileFolder = docFileFolderService.getById(docRecycle.getFolderId());
    if (Objects.isNull(docFileFolder)) {
        throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "该文件不存在或已被删除！");
    }
    Long userId = LoginContext.getUserId();
    if (!docFileFolder.getCreatorId().equals(userId)) {
        throw new BusinessException(ErrorCodeEnum.PERMISSION_DENIED.getCode(), "禁止访问");
    }
    if (DelStatusEnum.NORMAL.getStatus().equals(docFileFolder.getStatus())) {
        throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "该文件未被删除，无需重复恢复");
    }
    return docFileFolder;
}
```

Note: the helper's caller signature changes from passing folderId to passing recycleId. The callers in DocRecycleAOImpl (restore:93, completelyDelete:141) need adjustment (see Task 18).

- [ ] **Step 5: Verify compile**

```bash
cd D:/Projects/Github/kele-doc
export JAVA_HOME="D:/SDKs/JDK/jdk1.8.0_202"
mvn -B -pl kele-core compile
```

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-core/src/main/java/com/kele/core/buz/doc/ao/AbstractDocFileFolderAO.java
git commit -m "refactor(doc): AbstractDocFileFolderAO use PermissionService (B1, m7, bonus)"
```

---

### Task 16: Refactor `DocFileFolderAOImpl` (8 changes)

**Files:**
- Modify: `kele-core/.../buz/doc/ao/impl/DocFileFolderAOImpl.java`

Apply ALL changes per §6.2 in one commit:

- [ ] **Step 1: `getFolderTree` line 51-69 — remove creatorId, use scope=mine**

The current code filters by `creatorId == userId`. Replace with the scope=mine SQL from spec §10.5, called via a new method on `docFileFolderService` (or inline `service.listByMap`):
```java
@Override
public List<DocFileFolderResVO> getFolderTree(Long folderId, Integer format) {
    if (Objects.isNull(folderId) || folderId <= 0L) folderId = 0L;
    Long userId = LoginContext.getUserId();
    // scope=mine: owner_id = userId AND id NOT IN (recycle by me)
    List<DocFileFolder> fileFolders = docFileFolderService.list(Wrappers.<DocFileFolder>lambdaQuery()
        .eq(DocFileFolder::getParentId, folderId)
        .eq(DocFileFolder::getOwnerId, userId)  // owner, not creator
        .eq(DocFileFolder::getFormat, format)
        .eq(DocFileFolder::getStatus, DelStatusEnum.NORMAL.getStatus())
        .notIn(DocFileFolder::getId,
            Wrappers.<DocRecycle>lambdaQuery().eq(DocRecycle::getUserId, userId).select(DocRecycle::getFolderId)));
    // ... existing mapping code
}
```

(Add `@Autowired IDocRecycleService docRecycleService;` if not present.)

- [ ] **Step 2: `searchFolderAndFile` line 138-173 — remove creatorId, use scope=shared/all**

```java
List<DocFileFolder> fileFolders = docFileFolderService.list(Wrappers.<DocFileFolder>lambdaQuery()
    .like(DocFileFolder::getName, name)
    // remove .eq(creatorId, userId)
    .and(wrapper -> wrapper.and(w1 -> w1.eq(DocFileFolder::getOwnerId, userId))
        .or(w2 -> w2.exists(aclSubQueryForUser(userId, groupIds))))
    .notIn(DocFileFolder::getId, /* recycle */)
    .eq(DocFileFolder::getStatus, DelStatusEnum.NORMAL.getStatus())
    /* fileType filter */);
```

Define `aclSubQueryForUser(userId, groupIds)` as a private helper that returns a `Wrappers.lambdaQuery()` on `doc_file_folder_acl` matching `principal_type=USER AND principal_id=userId OR principal_type=GROUP AND principal_id IN groupIds`.

- [ ] **Step 3: `getFolderAndFileList` line 78-136 — rewrite as scope=all**

This is the biggest change. The current code uses `eq(creatorId, userId).or().eq(isPublic, 1)`. Replace entirely with the scope=all SQL from spec §10.5, parameterizing for `folderId`. Keep the `isTop` flag for the inner `filterFileList` logic.

- [ ] **Step 4: `createFolder` line 175-224 — set owner_id, use isRoot()**

```java
// Around line 186: remove the "我的文件" hardcoded + isPublic check
// Around line 203: setCreatorId(...) → keep; also add setOwnerId(LoginContext.getUserId())
docFileFolder.setCreatorId(LoginContext.getUserId());
docFileFolder.setOwnerId(LoginContext.getUserId());  // NEW
// Inherit ORG public from parent (if parent is public root, child is too)
DocFileFolder parent = (parentFolderId > 0) ? docFileFolderService.getById(parentFolderId) : null;
if (parent != null && !parent.isRoot()) {
    // non-root: require WRITE on parent
    permissionService.requireWrite(parentFolderId);
} else {
    // root: anyone can create
}
if (parent != null) {
    // inherit parent's ACL? No — child gets a fresh ACL
}
// Then save. If parent.isRoot() or parent has ORG public, also create an ORG ACL on the new folder.
```

- [ ] **Step 5: `deleteFolder` line 242-270 — remove setId, remove setStatus(DEL), add userId filter**

```java
// Around line 244-270: remove setId(docFileFolder.getId())
// Around line 257-260: remove .set(DocFileFolder::getStatus, DelStatusEnum.DEL.getStatus())
// In the docRecycle creation loop: docRecycle.setId(e.getId()) → docRecycle.setFolderId(e.getId())
// Add userId filter to docRecycleService.remove in any restore/restore-related code (if present)
```

(Combined with the soft-delete: this is a big change, please read the spec §6.2 entry for deleteFolder carefully.)

- [ ] **Step 6: `copyFolder` line 361-404 — refactor for v0.7 §5.4.a**

```java
@Override
public void copyFolder(FileFolderCopyVO copyVO) {
    // Source READ check
    permissionService.requireRead(copyVO.getId());
    // Target parent MANAGE check
    permissionService.requireManage(copyVO.getFolderId());
    // Source in recycle check (v0.8)
    Long userId = LoginContext.getUserId();
    boolean sourceInRecycle = docRecycleService.lambdaQuery()
        .eq(DocRecycle::getFolderId, copyVO.getId())
        .eq(DocRecycle::getUserId, userId).count() > 0;
    if (sourceInRecycle) {
        throw new BusinessException(ErrorCodeEnum.PERMISSION_DENIED.getCode(), "源文件夹在回收站中，无法复制");
    }
    // Existing copy logic, but set new owner = current user
    // Also: v1 only copies the folder itself, NOT subtree (per v0.7 §5.4.a)
    // Remove the super.getChild(...) recursion
    // ...
}
```

(For "v1 only copies single node" — set new docFileFolder with new id, no children, ACL empty.)

- [ ] **Step 7: `moveFolder` line 272-310 — refactor for v0.7 §5.4.b + circular ref check via CTE**

```java
@Override
public void moveFolder(FileFolderMoveVO moveVO) {
    // Source MANAGE check
    permissionService.requireManage(moveVO.getId());
    // Target parent MANAGE check
    permissionService.requireManage(moveVO.getNewFolderId());
    // Existing isDescendantOf check (already in place)
    // ACL rows move with the folder (no change)
    // Existing UPDATE parent_id logic
}
```

- [ ] **Step 8: `getAllFolderTree` line 414-431 (deprecated) — remove creatorId, add recycle filter**

```java
List<DocFileFolder> docFileFolderList = docFileFolderService.list(Wrappers.<DocFileFolder>lambdaQuery()
    .eq(DocFileFolder::getOwnerId, LoginContext.getUserId())  // owner, not creator
    .eq(DocFileFolder::getStatus, DelStatusEnum.NORMAL.getStatus())
    .notIn(DocFileFolder::getId, /* recycle by current user */));
```

- [ ] **Step 9: `filterFileList` line 458-474 — remove creatorId filter**

Remove the `.filter(!isTop || folder.getCreatorId()...)` line. The list is now filtered by resolve check at the call site.

- [ ] **Step 10: Verify compile**

```bash
cd D:/Projects/Github/kele-doc
export JAVA_HOME="D:/SDKs/JDK/jdk1.8.0_202"
mvn -B -pl kele-core compile
```

Expected: BUILD SUCCESS (or 1-2 type errors from the copyFolder/Move signature changes — fix as needed)

- [ ] **Step 11: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-core/src/main/java/com/kele/core/buz/doc/ao/impl/DocFileFolderAOImpl.java
git commit -m "refactor(doc): DocFileFolderAOImpl use PermissionService (v0.11 B1 + scope)"
```

---

### Task 17: Refactor `DocFileContentAOImpl` (5 changes)

**Files:**
- Modify: `kele-core/.../buz/doc/ao/impl/DocFileContentAOImpl.java`

- [ ] **Step 1: `createFile` line 53-112 — set ownerId, remove isPublic refs**

Line 72: add `fileFolder.setOwnerId(LoginContext.getUserId());` after `setCreatorId`.

- [ ] **Step 2: `moveFile` line 156-200 — add WRITE source / MANAGE target checks**

```java
@Override
public void moveFile(DocFileMoveReqVO reqVO) {
    // Source files: WRITE check
    for (Long id : reqVO.getIds()) permissionService.requireWrite(id);
    // Target parent: MANAGE check
    permissionService.requireManage(reqVO.getNewFolderId());
    // ... existing move logic
}
```

(Line 168 `super.selectByIdList(idList)` — the selectByIdList itself now uses PermissionService per Task 15.)

- [ ] **Step 3: `copyFile` line 202-257 — add READ source / MANAGE target checks**

```java
@Override
public void copyFile(DocFileCopyReqVO reqVO) {
    for (Long id : reqVO.getIds()) permissionService.requireRead(id);
    permissionService.requireManage(reqVO.getNewFolderId());
    // ... existing copy logic
}
```

- [ ] **Step 4: `deleteFile` line 259-300 — remove setId, remove setStatus(DEL)**

Line 278: `docRecycle.setId(e.getId())` → `docRecycle.setFolderId(e.getId())`.
Lines 286-288: remove the `.set(DocFileFolder::getStatus, DelStatusEnum.DEL.getStatus())` block.

- [ ] **Step 5: `getFileBaseInfo` line 308-317 — add READ permission check**

```java
@Override
public DocFileContentResVO getFileBaseInfo(Long id) {
    permissionService.requireRead(id);
    DocFileFolder docFileFolder = docFileFolderService.getById(id);
    // ... existing mapping
}
```

- [ ] **Step 6: Verify compile**

```bash
cd D:/Projects/Github/kele-doc
export JAVA_HOME="D:/SDKs/JDK/jdk1.8.0_202"
mvn -B -pl kele-core compile
```

Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-core/src/main/java/com/kele/core/buz/doc/ao/impl/DocFileContentAOImpl.java
git commit -m "refactor(doc): DocFileContentAOImpl use PermissionService (v0.11 B1, v0.12 MINOR)"
```

---

### Task 18: Refactor `DocRecycleAOImpl` (4 changes)

**Files:**
- Modify: `kele-core/.../buz/doc/ao/impl/DocRecycleAOImpl.java`

- [ ] **Step 1: `getRecycleFolderAndFileList` line 48-77 — getId → getFolderId**

Line 60: `DocRecycle::getId` → `DocRecycle::getFolderId`.

- [ ] **Step 2: `restore` line 79-135 — getId → getFolderId, add userId filter**

- Line 82: `Long id = reqVO.getId();` — this is the recycle id (not folderId) — keep as `id`.
- Line 87: `getParentId, id` → `getParentId, docRecycle.getFolderId()`.
- Line 93: `getRecycleById(id)` — signature change: takes recycleId, looks up docRecycle first.
- Line 116: add `.eq(DocRecycle::getUserId, docRecycle.getCreatorId())` to the remove predicate (defensive).
- Line 122: `getId, id` → `getId, docRecycle.getFolderId()`.

- [ ] **Step 3: `completelyDelete` line 137-145 — getId → getFolderId**

- Line 141: `getRecycleById(id)` — same signature change.
- Line 144: `getParentId, id` → `getParentId, docRecycle.getFolderId()` (only after refactoring line 141 to get `docRecycle` first; **OR** just use a fresh `docRecycleService.getById(id).getFolderId()`).

- [ ] **Step 4: `emptyRecycle` line 147-155 — full refactor (v0.12 MAJOR)**

```java
@Override
@Transactional(rollbackFor = Exception.class)
public void emptyRecycle() {
    Long userId = LoginContext.getUserId();
    // 1. 收集所有 folder_id
    List<DocRecycle> docRecycleList = docRecycleService.lambdaQuery()
        .eq(DocRecycle::getUserId, userId).list();
    if (CollectionUtils.isEmpty(docRecycleList)) return;
    List<Long> folderIds = docRecycleList.stream().map(DocRecycle::getFolderId).collect(Collectors.toList());
    // 2. 单事务批量硬删
    transactionTemplate.execute(status -> {
        // 删 doc_file_content (file_id = folder_id for files, or use JOIN for sub-files)
        docFileContentService.lambdaQuery()
            .in(DocFileContent::getFileId, /* files within these folderIds */)
            .remove();
        // 删 doc_file_folder_acl
        docFileFolderAclService.lambdaQuery()
            .in(DocFileFolderAcl::getFolderId, folderIds).remove();
        // 删 doc_file_folder
        docFileFolderService.lambdaQuery()
            .in(DocFileFolder::getId, folderIds).remove();
        // 删 doc_relation_level
        docRelationLevelService.lambdaQuery()
            .in(DocRelationLevel::getParentId, folderIds).remove();
        // 删 doc_recycle
        docRecycleService.lambdaQuery()
            .eq(DocRecycle::getUserId, userId).remove();
        return null;
    });
}
```

(Note: `docFileContent` table stores file content, with foreign key to folder/file. For simplicity, this implementation deletes only `doc_file_folder` rows that match `folderIds` — the actual file content deletion logic is more nuanced. Adjust per real schema — `doc_file_content` may not have a direct `file_id` link to `doc_file_folder.id`.)

- [ ] **Step 5: Verify compile**

```bash
cd D:/Projects/Github/kele-doc
export JAVA_HOME="D:/SDKs/JDK/jdk1.8.0_202"
mvn -B -pl kele-core compile
```

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-core/src/main/java/com/kele/core/buz/doc/ao/impl/DocRecycleAOImpl.java
git commit -m "refactor(doc): DocRecycleAOImpl getFolderId + emptyRecycle (v0.11, v0.12 MAJOR)"
```

---

### Task 19: Refactor `DocCollectFolderAOImpl` (2 changes)

**Files:**
- Modify: `kele-core/.../buz/doc/ao/impl/DocCollectFolderAOImpl.java`

- [ ] **Step 1: `getCollectFileList` line 33-51 — remove creatorId, use PermissionService**

```java
@Override
public List<DocFileResVO> getCollectFileList(String name) {
    Long userId = LoginContext.getUserId();
    // owner = me OR has READ+ (collected files I'm interested in)
    return docFileFolderService.list(Wrappers.<DocFileFolder>lambdaQuery()
        .eq(DocFileFolder::getCollected, true)
        .eq(DocFileFolder::getStatus, DelStatusEnum.NORMAL.getStatus())
        .and(w -> w.eq(DocFileFolder::getOwnerId, userId)
                  .or().exists(aclSubQueryForUser(userId, LoginContext.getUserGroupIds())))
        .like(StringUtils.hasText(name), DocFileFolder::getName, name))
        .stream().map(/* existing mapping */).collect(Collectors.toList());
}
```

You'll need to add `@Autowired PermissionService permissionService;` and define a private `aclSubQueryForUser` helper (or use a static method).

- [ ] **Step 2: `checkFilePermission` line 76-85 — replace with requireRead**

```java
private void checkFilePermission(Long fileId) {
    permissionService.requireRead(fileId);
    // Optionally still check existence if needed for error msg
    if (docFileFolderService.getById(fileId) == null) {
        throw new BusinessException(ErrorCodeEnum.RESOURCE_NOT_VISIBLE.getCode(), FILE_NOT_EXIST_MSG);
    }
}
```

- [ ] **Step 3: Verify compile**

```bash
cd D:/Projects/Github/kele-doc
export JAVA_HOME="D:/SDKs/JDK/jdk1.8.0_202"
mvn -B -pl kele-core compile
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-core/src/main/java/com/kele/core/buz/doc/ao/impl/DocCollectFolderAOImpl.java
git commit -m "refactor(doc): DocCollectFolderAOImpl use PermissionService (v0.11 B1)"
```

---

### Task 20: Refactor `DateBaseDocFileContentStorageServiceImpl` (1 change)

**Files:**
- Modify: `kele-core/.../buz/doc/service/impl/DateBaseDocFileContentStorageServiceImpl.java`

- [ ] **Step 1: `getByFileId` line 175-184 — replace creatorId check with requireRead**

```java
private DocFileContent getByFileId(Long fileId) {
    permissionService.requireRead(fileId);
    DocFileContent docFileContent = docFileContentService.getById(fileId);
    if (Objects.isNull(docFileContent)) {
        throw new BusinessException(ErrorCodeEnum.RESOURCE_NOT_VISIBLE.getCode(), String.format("id为%s的文件未找到", fileId));
    }
    return docFileContent;
}
```

Add `@Autowired PermissionService permissionService;` if not present.

(Also do the same for the other 2 storage impls — Local / Minio / Oss — to ensure consistency. Their `getFileContent` / `downloadFileContent` paths should also use `permissionService.requireRead`.)

- [ ] **Step 2: Verify compile**

```bash
cd D:/Projects/Github/kele-doc
export JAVA_HOME="D:/SDKs/JDK/jdk1.8.0_202"
mvn -B -pl kele-core compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-core/src/main/java/com/kele/core/buz/doc/service/impl/DateBaseDocFileContentStorageServiceImpl.java
git commit -m "refactor(doc): DateBaseDocFileContentStorageServiceImpl use requireRead (v0.11 MAJOR #7)"
```

---

### Task 21: Refactor `LocalDocFileContentStorageServiceImpl` + `MinioDocFileContentStorageServiceImpl` + `OssDocFileContentStorageServiceImpl`

**Files:**
- Modify: 3 storage service impl files

For each, find the `getFileContent` and `downloadFileContent` methods. Add `permissionService.requireRead(fileFolder.getId())` at the start.

- [ ] **Step 1-3: Apply the same change to each of the 3 impls**

```java
public DocFileContentResVO getFileContent(DocFileFolder docFileFolder) {
    permissionService.requireRead(docFileFolder.getId());
    // ... existing logic
}
public void downloadFileContent(DocFileFolder docFileFolder, HttpServletResponse response) {
    permissionService.requireRead(docFileFolder.getId());
    // ... existing logic
}
```

- [ ] **Step 4: Verify compile**

```bash
cd D:/Projects/Github/kele-doc
export JAVA_HOME="D:/SDKs/JDK/jdk1.8.0_202"
mvn -B -pl kele-core compile
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-core/src/main/java/com/kele/core/buz/doc/service/impl/LocalDocFileContentStorageServiceImpl.java kele-core/src/main/java/com/kele/core/buz/doc/service/impl/MinioDocFileContentStorageServiceImpl.java kele-core/src/main/java/com/kele/core/buz/doc/service/impl/OssDocFileContentStorageServiceImpl.java
git commit -m "refactor(doc): all storage impls use requireRead (v0.11 B1)"
```

---

## Phase 4: New APIs (Group / ACL / Search)

### Task 22: Create `GroupAO` + `GroupAOImpl` + `GroupController`

**Files:**
- Create: `kele-core/.../buz/doc/ao/GroupAO.java`
- Create: `kele-core/.../buz/doc/ao/impl/GroupAOImpl.java`
- Create: `kele-core/.../buz/doc/controller/GroupController.java`

- [ ] **Step 1: Create `GroupAO` interface**

Define: `createGroup(name, description, memberIds)`, `updateGroup(id, name, description)`, `dissolveGroup(id)`, `restoreGroup(id)`, `addMembers(id, userIds)`, `removeMember(id, userId)`, `listGroups(page, size, keyword)`, `getGroupDetail(id)`, `listMembers(id)`, `getMyGroups()`.

All methods check `LoginContext.isAdmin()` first (throw `PERMISSION_DENIED` if not admin).

- [ ] **Step 2: Create `GroupAOImpl`**

Implements all GroupAO methods. Uses KeleGroupMapper, GroupMemberMapper. Logic:
- `createGroup`: insert group row, then insert group_member rows. Single transaction.
- `dissolveGroup`: set `status=0` on group, then `revoked_at=now()` on all ACL rows where `principal_type='GROUP' AND principal_id=id`.
- `restoreGroup`: revert `status=1`, then `revoked_at=NULL` on the previously-revoked ACL rows.
- `getMyGroups`: query group_member where user_id = :currentUserId, join group where status=1.

- [ ] **Step 3: Create `GroupController`**

Map all GroupAO methods to HTTP endpoints per spec §4.1. Add `@PreAuthorize("hasRole('ADMIN')")` or use `@Authority` aspect on the controller class.

- [ ] **Step 4: Verify compile**

```bash
cd D:/Projects/Github/kele-doc
export JAVA_HOME="D:/SDKs/JDK/jdk1.8.0_202"
mvn -B -pl kele-core compile
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-core/src/main/java/com/kele/core/buz/doc/ao/GroupAO.java kele-core/src/main/java/com/kele/core/buz/doc/ao/impl/GroupAOImpl.java kele-core/src/main/java/com/kele/core/buz/doc/controller/GroupController.java
git commit -m "feat(doc): GroupAO + controller (B3 / §4.1)"
```

---

### Task 23: Create `AclAO` + `AclAOImpl` + `AclController`

**Files:**
- Create: `kele-core/.../buz/doc/ao/AclAO.java`
- Create: `kele-core/.../buz/doc/ao/impl/AclAOImpl.java`
- Create: `kele-core/.../buz/doc/controller/AclController.java`

- [ ] **Step 1: Create `AclAO` interface**

```java
public interface AclAO {
    DocFileFolderAclListVO listFolderAcl(Long folderId);  // GET /acl
    void grantAcl(Long folderId, List<AclEntryVO> entries, boolean replace);  // POST /acl
    void revokeAcl(Long folderId, Long aclId);  // DELETE /acl/{aclId}
    void updateAcl(Long folderId, Long aclId, String permission);  // PUT /acl/{aclId}
    void transferOwner(Long folderId, Long newOwnerId);  // PUT /owner
}
```

- [ ] **Step 2: Create `AclAOImpl`**

Uses `@Authority` aspect (see Task 24 for annotation extension). All methods require MANAGE on the folderId (owner transfer is Owner-only, admin bypassed).

Logic:
- `listFolderAcl`: query `doc_file_folder_acl WHERE folder_id = ? AND revoked_at IS NULL` ordered by FIELD(principal_type). Join `sys_user_info` for principal names.
- `grantAcl` with `replace=false`: upsert each entry (check existing valid row for (folder, principal); update or insert).
- `grantAcl` with `replace=true`: `UPDATE doc_file_folder_acl SET revoked_at=NOW() WHERE folder_id=? AND revoked_at IS NULL` first, then insert all entries.
- `revokeAcl`: `UPDATE doc_file_folder_acl SET revoked_at=NOW() WHERE id=? AND folder_id=? AND revoked_at IS NULL`. Also enforce "at least one MANAGE remains" (count remaining valid MANAGE rows; throw `CANNOT_REMOVE_LAST_MANAGE` if 0).
- `updateAcl`: similar but update permission column.
- `transferOwner`: `UPDATE doc_file_folder SET owner_id=:newOwnerId WHERE id=:folderId AND owner_id=:oldOwnerId` (CAS); if 0 rows updated, throw `OWNER_REQUIRED` (mapped to `PERMISSION_DENIED` per spec).

- [ ] **Step 3: Create `AclController`**

```java
@RestController
@RequestMapping("/api/doc/folders")
public class AclController {

    @Autowired private AclAO aclAO;

    @GetMapping("/{id}/acl")
    @Authority(expressionArgs = "#folderId", methodName = "requireRead", beanName = "permissionService")
    public ResponseResult<DocFileFolderAclListVO> listAcl(@PathVariable("id") Long folderId) {
        return ResponseResult.ok(aclAO.listFolderAcl(folderId));
    }

    @PostMapping("/{id}/acl")
    @Authority(expressionArgs = "#folderId", methodName = "requireManage", beanName = "permissionService")
    public ResponseResult<List<DocFileFolderAcl>> grantAcl(@PathVariable("id") Long folderId, @RequestBody GrantAclReqVO req) {
        aclAO.grantAcl(folderId, req.getEntries(), req.isReplace());
        return ResponseResult.ok();
    }
    // ... etc.
}
```

- [ ] **Step 4: Verify compile**

```bash
cd D:/Projects/Github/kele-doc
export JAVA_HOME="D:/SDKs/JDK/jdk1.8.0_202"
mvn -B -pl kele-core compile
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-core/src/main/java/com/kele/core/buz/doc/ao/AclAO.java kele-core/src/main/java/com/kele/core/buz/doc/ao/impl/AclAOImpl.java kele-core/src/main/java/com/kele/core/buz/doc/controller/AclController.java
git commit -m "feat(doc): AclAO + controller (B1 / §4.2)"
```

---

### Task 24: Extend `Authority` annotation with `expressionArgs` alias

**Files:**
- Modify: `kele-core/.../other/aspect/authority/Authority.java`

- [ ] **Step 1: Add `expressionArgs` field with deprecation alias**

```java
public @interface Authority {
    /**
     * @deprecated since v0.7 — name was misleading (engine is OGNL, not SpEL).
     * Use {@link #expressionArgs()} for new code.
     */
    @Deprecated
    String[] spelArgs() default {};

    /**
     * OGNL expression args. Semantic-neutral name. See spec §10.7.
     */
    String[] expressionArgs() default {};

    String methodName();
    String beanName();
}
```

- [ ] **Step 2: Update `AuthorityAspect.java` to read `expressionArgs` (with fallback to `spelArgs`)**

In `AuthorityAspect.before()`:
```java
String[] spelArgs = authority.spelArgs();
String[] expressionArgs = authority.expressionArgs();
String[] effectiveArgs = expressionArgs.length > 0 ? expressionArgs : spelArgs;
// ... use effectiveArgs in OnglUtils.evaluate
```

- [ ] **Step 3: Verify compile**

```bash
cd D:/Projects/Github/kele-doc
export JAVA_HOME="D:/SDKs/JDK/jdk1.8.0_202"
mvn -B -pl kele-core compile
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-core/src/main/java/com/kele/core/other/aspect/authority/Authority.java kele-core/src/main/java/com/kele/core/other/aspect/authority/AuthorityAspect.java
git commit -m "refactor(auth): add expressionArgs alias for Authority (M9)"
```

---

### Task 25: Add `/api/users/search` endpoint to `SysUserInfoController`

**Files:**
- Modify: `kele-core/.../buz/sys/controller/SysUserInfoController.java`

- [ ] **Step 1: Add search endpoint**

```java
@GetMapping("/api/users/search")
public ResponseResult<List<UserSearchVO>> searchUsers(
    @RequestParam("keyword") String keyword,
    @RequestParam(value = "limit", defaultValue = "20") Integer limit) {
    if (limit > 50) limit = 50;
    List<UserSearchVO> results = sysUserInfoService.lambdaQuery()
        .like(SysUserInfo::getAccount, keyword)
        .or().like(SysUserInfo::getUserName, keyword)
        .ne(SysUserInfo::getStatus, -1)
        .last("LIMIT " + limit)
        .list()
        .stream()
        .map(u -> new UserSearchVO(u.getId(), u.getAccount(), u.getUserName(), u.getAvatar()))
        .collect(Collectors.toList());
    return ResponseResult.ok(results);
}
```

Create `UserSearchVO` if not exists. Note: the spec says "任意登录用户可用" — no admin gate.

- [ ] **Step 2: Verify compile**

```bash
cd D:/Projects/Github/kele-doc
export JAVA_HOME="D:/SDKs/JDK/jdk1.8.0_202"
mvn -B -pl kele-core compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-core/src/main/java/com/kele/core/buz/sys/controller/SysUserInfoController.java
git commit -m "feat(user): /api/users/search endpoint (§4.1.1)"
```

---

## Phase 5: Frontend

### Task 26: Add 7 API methods to `kele-doc-web/workbench/src/api/index.js`

**Files:**
- Modify: `kele-doc-web/workbench/src/api/index.js`

- [ ] **Step 1: Add the 7 methods to the api object**

Insert after the existing `emptyRecycle` method (around line 304):

```js
// 共享/权限 API
getFolderAcl(folderId) {
  if (useMock) return getMockData('getFolderAcl', folderId)
  return http.get(`/api/doc/folders/${folderId}/acl`)
},
grantFolderAcl(folderId, entries, replace = false) {
  if (useMock) return getMockData('grantFolderAcl', { folderId, entries, replace })
  return http.post(`/api/doc/folders/${folderId}/acl`, { entries, replace })
},
revokeFolderAcl(folderId, aclId) {
  if (useMock) return getMockData('revokeFolderAcl', { folderId, aclId })
  return http.delete(`/api/doc/folders/${folderId}/acl/${aclId}`)
},
updateFolderAcl(folderId, aclId, permission) {
  if (useMock) return getMockData('updateFolderAcl', { folderId, aclId, permission })
  return http.put(`/api/doc/folders/${folderId}/acl/${aclId}`, { permission })
},
transferFolderOwner(folderId, newOwnerId) {
  if (useMock) return getMockData('transferFolderOwner', { folderId, newOwnerId })
  return http.put(`/api/doc/folders/${folderId}/owner`, { newOwnerId })
},
getMyGroups() {
  if (useMock) return getMockData('getMyGroups')
  return http.get('/api/admin/groups/my')
},
searchUsers(keyword, limit = 20) {
  if (useMock) return getMockData('searchUsers', { keyword, limit })
  return http.get('/api/users/search', { params: { keyword, limit } })
}
```

- [ ] **Step 2: Add corresponding mocks to `mock.js`**

For each method, add a mock returning plausible data. (The existing `mock.js` follows a `mockData.methodName = (...) => {...}` pattern.)

- [ ] **Step 3: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-doc-web/workbench/src/api/index.js kele-doc-web/workbench/src/api/mock.js
git commit -m "feat(fe): add 7 ACL/group/search API methods"
```

---

### Task 27: Extend Pinia store with ACL state

**Files:**
- Modify: `kele-doc-web/workbench/src/store.js`

- [ ] **Step 1: Add new state and actions**

```js
state: () => ({
  // ... existing
  currentFolderAcl: null,         // { ownerId, isOrgPublic, orgPublicPermission, entries: [] }
  myGroupIds: [],                 // [1, 2, 3]
  sharedFoldersCache: new Map()   // folderId -> { permission, source }
}),
actions: {
  // ... existing
  async loadCurrentFolderAcl(folderId) {
    const { data } = await api.getFolderAcl(folderId)
    this.currentFolderAcl = data
    return data
  },
  async loadMyGroups() {
    if (this.myGroupIds.length) return this.myGroupIds
    const { data } = await api.getMyGroups()
    this.myGroupIds = (data || []).map(g => g.id)
    return this.myGroupIds
  },
  async shareWithUser(folderId, userId, permission) {
    await api.grantFolderAcl(folderId, [{ principalType: 'USER', principalId: userId, permission }])
    await this.loadCurrentFolderAcl(folderId)
  },
  // ... shareWithGroup, setOrgPublic, revokeAcl, transferOwner similarly
}
```

- [ ] **Step 2: Wire `loadMyGroups` into app init**

In `Index.vue` (workspace shell), in the `init()` function, after `getUserInfo()` and `getUserConfig()`, add:
```js
await store.loadMyGroups()
```

- [ ] **Step 3: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-doc-web/workbench/src/store.js kele-doc-web/workbench/src/pages/workspace/Index.vue
git commit -m "feat(fe): Pinia store ACL state + loadMyGroups wiring"
```

---

### Task 28: Create `ShareDialog.vue` (new component)

**Files:**
- Create: `kele-doc-web/workbench/src/pages/workspace/components/content/ShareDialog.vue`

- [ ] **Step 1: Create the component**

This is the largest new frontend file. Structure (3 sections, see spec §10.4):

```vue
<template>
  <el-dialog
    v-model="visible"
    title="分享"
    width="600px"
    @close="onClose"
  >
    <!-- Section 1: Owner row (read-only) -->
    <div class="owner-row">
      <el-avatar :src="ownerAvatar" />
      <span class="name">{{ ownerName }} (Owner)</span>
    </div>

    <!-- Section 2: Current ACL list -->
    <div class="acl-list">
      <h4>已分享</h4>
      <div v-for="entry in aclEntries" :key="entry.id" class="acl-entry">
        <el-avatar v-if="entry.principalType==='USER'" :src="entry.principalAvatar" />
        <el-icon v-else-if="entry.principalType==='GROUP'"><User /></el-icon>
        <el-icon v-else><Globe /></el-icon>
        <span class="principal-name">{{ entry.principalName }}</span>
        <el-select v-model="entry.permission" @change="onPermissionChange(entry)" :disabled="entry.principalType==='ORG' && entry.permission==='READ'">
          <el-option label="可读" value="READ" />
          <el-option label="可读写" value="WRITE" />
          <el-option label="可管理" value="MANAGE" />
        </el-select>
        <el-button @click="onRevoke(entry)" type="danger" link>撤销</el-button>
      </div>
      <el-alert v-if="isOrgPublic" type="info" :closable="false">
        全员可见（READ） <el-button @click="onRevokeOrgPublic" link>关闭</el-button>
      </el-alert>
    </div>

    <!-- Section 3: Add new sharing -->
    <div class="add-sharing">
      <el-tabs v-model="addTab">
        <el-tab-pane label="用户" name="user">
          <el-input v-model="userKeyword" placeholder="搜索用户" @input="onSearchUsers" />
          <el-radio-group v-model="selectedUserId">
            <el-radio v-for="u in userSearchResults" :key="u.id" :label="u.id">
              <el-avatar :src="u.avatar" /> {{ u.nickname }}
            </el-radio>
          </el-radio-group>
          <el-select v-model="newPermission">
            <el-option label="可读" value="READ" />
            <el-option label="可读写" value="WRITE" />
            <el-option label="可管理" value="MANAGE" />
          </el-select>
          <el-button @click="onShareWithUser" type="primary" :disabled="!selectedUserId">添加</el-button>
        </el-tab-pane>
        <el-tab-pane label="群组" name="group">
          <el-select v-model="selectedGroupId" placeholder="选择群组">
            <el-option v-for="g in myGroups" :key="g.id" :label="g.name" :value="g.id" />
          </el-select>
          <el-select v-model="newPermission">
            <el-option label="可读" value="READ" />
            <el-option label="可读写" value="WRITE" />
            <el-option label="可管理" value="MANAGE" />
          </el-select>
          <el-button @click="onShareWithGroup" type="primary" :disabled="!selectedGroupId">添加</el-button>
        </el-tab-pane>
        <el-tab-pane label="全员" name="org">
          <el-button @click="onSetOrgPublic" type="primary">设为全员可读（READ）</el-button>
        </el-tab-pane>
      </el-tabs>
    </div>

    <!-- Section 4: Owner transfer (only if current user is owner) -->
    <div v-if="isOwner" class="owner-section">
      <el-button @click="onTransferOwner" type="warning">转让所有权</el-button>
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '@/api'
import { useStore } from '@/store'

const props = defineProps({
  folderId: { type: Number, required: true }
})
const store = useStore()

const visible = ref(true)
const addTab = ref('user')
const userKeyword = ref('')
const userSearchResults = ref([])
const selectedUserId = ref(null)
const selectedGroupId = ref(null)
const newPermission = ref('READ')

const currentUserId = computed(() => store.userInfo?.id)
const acl = ref(null)
const aclEntries = computed(() => acl.value?.entries || [])
const ownerName = computed(() => acl.value?.ownerName || '')
const ownerAvatar = computed(() => acl.value?.ownerAvatar || '')
const isOwner = computed(() => currentUserId.value === acl.value?.ownerId)
const isOrgPublic = computed(() => acl.value?.isOrgPublic || false)
const myGroups = ref([])

async function loadAcl() {
  acl.value = await store.loadCurrentFolderAcl(props.folderId)
}
async function loadMyGroups() {
  const groups = await store.loadMyGroups()
  myGroups.value = groups
}

onMounted(() => { loadAcl(); loadMyGroups() })

async function onPermissionChange(entry) {
  await api.updateFolderAcl(props.folderId, entry.id, entry.permission)
  ElMessage.success('已更新')
}
async function onRevoke(entry) {
  await api.revokeFolderAcl(props.folderId, entry.id)
  await loadAcl()
  ElMessage.success('已撤销')
}
async function onRevokeOrgPublic() {
  // Find the ORG acl row and revoke it
  const orgEntry = aclEntries.value.find(e => e.principalType === 'ORG')
  if (orgEntry) await api.revokeFolderAcl(props.folderId, orgEntry.id)
  await loadAcl()
}
async function onSearchUsers() {
  if (userKeyword.value.length < 2) return
  const { data } = await api.searchUsers(userKeyword.value)
  userSearchResults.value = data || []
}
async function onShareWithUser() {
  await api.grantFolderAcl(props.folderId,
    [{ principalType: 'USER', principalId: selectedUserId.value, permission: newPermission.value }])
  await loadAcl()
  ElMessage.success('已添加')
}
async function onShareWithGroup() {
  await api.grantFolderAcl(props.folderId,
    [{ principalType: 'GROUP', principalId: selectedGroupId.value, permission: newPermission.value }])
  await loadAcl()
  ElMessage.success('已添加')
}
async function onSetOrgPublic() {
  await api.grantFolderAcl(props.folderId,
    [{ principalType: 'ORG', principalId: null, permission: 'READ' }])
  await loadAcl()
}
async function onTransferOwner() {
  // ... prompt for newOwnerId via dialog, then call api.transferFolderOwner
}
function onClose() {
  visible.value = false
}
</script>
```

- [ ] **Step 2: Verify it builds**

(No real build for frontend at this stage — just verify Vue syntax via editor highlighting or `npm run lint` if configured.)

- [ ] **Step 3: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-doc-web/workbench/src/pages/workspace/components/content/ShareDialog.vue
git commit -m "feat(fe): ShareDialog component (B1 / §10.4)"
```

---

### Task 29: Modify `FolderCard.vue` — add 分享 menu item

**Files:**
- Modify: `kele-doc-web/workbench/src/pages/workspace/components/content/FolderCard.vue`

- [ ] **Step 1: Add 分享 to menuList**

In the existing `menuList` computed (around line 70-90), prepend 分享:
```js
const menuList = computed(() => {
  return props.coverFolderMenuList.length > 0
    ? props.coverFolderMenuList
    : [
        { name: '分享', value: 'share', icon: 'icon-share' },
        { name: '重命名', value: 'rename', icon: 'icon-zhongmingming' },
        { name: '复制/移动', value: 'copyOrMove', icon: 'icon-a-yidong2' },
        { name: '删除', value: 'delete', icon: 'icon-shanchu' }
      ]
})
```

- [ ] **Step 2: Add ShareDialog component + show on 'share' action**

```vue
<template>
  ...
  <ShareDialog v-if="shareDialogVisible" :folder-id="props.data.id" @close="shareDialogVisible = false" />
</template>

<script setup>
import ShareDialog from './ShareDialog.vue'
import emitter from '@/utils/eventBus'
const shareDialogVisible = ref(false)

const onMenuClick = item => {
  emits('actionClick', item.value)
  if (item.value === 'share') {
    shareDialogVisible.value = true
  }
  if (typeof item.onClick === 'function') {
    item.onClick(props.data, RESOURCE_TYPES.FOLDER)
  }
}
</script>
```

- [ ] **Step 3: Add permission-aware menu (only show 分享 for MANAGE)**

For now, show 分享 to all users (will refine in Phase 6 with proper permission gating). Add a TODO comment.

- [ ] **Step 4: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-doc-web/workbench/src/pages/workspace/components/content/FolderCard.vue
git commit -m "feat(fe): add 分享 menu item to FolderCard (B1 / §10.4)"
```

---

### Task 30: Update folder list views to show owner/permission metadata

**Files:**
- Modify: `kele-doc-web/workbench/src/pages/workspace/components/content/GridView.vue`
- Modify: `kele-doc-web/workbench/src/pages/workspace/components/content/ListView.vue`
- Modify: `kele-doc-web/workbench/src/pages/workspace/components/common/FolderTree.vue`

- [ ] **Step 1: Update `DocFileAndFolderResVO` (backend) to include permission fields**

The backend `DocFileAndFolderResVO` (response) needs new fields per spec §10.5:
- `effectivePermission` (READ/WRITE/MANAGE)
- `permissionSource` (OWNER/USER/GROUP/INHERITED/ORG)
- `principalId` (the user/group the permission comes from)

These should be computed by the SQL via the scope=mine/shared/public queries. Add the fields to the VO entity.

- [ ] **Step 2: Update `GridView.vue` and `ListView.vue` to display these fields**

Show an icon/label next to each folder card:
- OWNER: "我创建的"
- USER/GROUP/INHERITED: "来自 [name]"
- ORG: "全员公开"

- [ ] **Step 3: Update `FolderTree.vue` to use scope=mine (or all 4 scopes)**

The tree now shows folders accessible via any of the 4 scopes. Backend endpoint `/api/doc/folders?scope=mine` returns the user's owned folders; for the tree, you may need an additional endpoint or just use `scope=all` (limited to top-level).

- [ ] **Step 4: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-doc-web/workbench/src/pages/workspace/components/content/GridView.vue kele-doc-web/workbench/src/pages/workspace/components/content/ListView.vue kele-doc-web/workbench/src/pages/workspace/components/common/FolderTree.vue kele-core/src/main/java/com/kele/core/buz/doc/model/vo/DocFileAndFolderResVO.java
git commit -m "feat(fe+be): show permission metadata in folder list views (§10.5)"
```

---

## Phase 6: Testing + Cleanup

### Task 31: Write `PermissionServiceTest` unit tests

**Files:**
- Modify: `kele-core/src/test/java/com/kele/core/buz/doc/permission/PermissionServiceTest.java`

- [ ] **Step 1: Add JUnit + Mockito test setup**

Add dependencies to `kele-core/pom.xml`:
```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-test</artifactId>
  <scope>test</scope>
</dependency>
```

(This may already be there as a transitive dep.)

- [ ] **Step 2: Implement 4 test cases with Mockito mocks**

```java
public class PermissionServiceTest {

    @Mock private DocFileFolderMapper folderMapper;
    @Mock private DocFileFolderAclMapper aclMapper;
    @Mock private GroupMemberMapper groupMapper;
    @InjectMocks private PermissionService service;

    @Test
    public void testOwnerHasManage() {
        // Mock folder.ownerId == userId
        // Expect: result.level == MANAGE, result.source == OWNER
    }
    @Test
    public void testDirectAclGrants() {
        // Mock direct USER ACL
        // Expect: result.level == ACL's permission, result.source == DIRECT
    }
    @Test
    public void testInheritanceCascades() {
        // Mock parent has USER ACL, child has no ACL of its own
        // Expect: child's result is from parent (source == INHERITED)
    }
    @Test
    public void testHigherWins() {
        // Mock folder has ORG READ + USER WRITE
        // Expect: result.level == WRITE (USER > ORG)
    }
    @Test
    public void testRequireManageThrowsWhenInsufficient() {
        // User has READ on a folder
        // expectThrows: PERMISSION_DENIED when requireManage is called
    }

    @Before
    public void setup() {
        MockitoAnnotations.openMocks(this);
        // Setup LoginContext thread-local
        UserInfoBO u = new UserInfoBO();
        u.setId(100L);
        LoginContext.setUserInfo(u);
    }
}
```

- [ ] **Step 3: Run tests**

```bash
cd D:/Projects/Github/kele-doc
export JAVA_HOME="D:/SDKs/JDK/jdk1.8.0_202"
mvn -B -pl kele-core test -Dtest=PermissionServiceTest
```

Expected: All 5 tests PASS

- [ ] **Step 4: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-core/src/test/java/com/kele/core/buz/doc/permission/PermissionServiceTest.java kele-core/pom.xml
git commit -m "test(perm): PermissionService unit tests (TDD, M5)"
```

---

### Task 32: Integration test: V2 migration runs on a fresh DB

**Files:**
- Create: `kele-core/src/test/java/com/kele/core/db/V2MigrationIT.java`
- Test config: `kele-core/src/test/resources/application-test.yml` (H2 or testcontainers MySQL)

- [ ] **Step 1: Set up test DB**

Use H2 in MySQL compatibility mode, OR testcontainers for real MySQL 9.3.0. Easiest: H2 with `MODE=MySQL`.

- [ ] **Step 2: Write test**

```java
@SpringBootTest
@TestPropertySource(properties = {"spring.flyway.enabled=true"})
public class V2MigrationIT {

    @Autowired private DataSource dataSource;

    @Test
    public void v2MigrationRunsAndCreatesAllTables() throws SQLException {
        // Check that doc_file_folder has owner_id, is_root, role column
        // Check that doc_file_folder_acl exists with active_marker
        // Check that group, group_member exist
        // Check that doc_recycle has folder_id + UNIQUE constraint
    }
}
```

- [ ] **Step 3: Run test**

```bash
cd D:/Projects/Github/kele-doc
export JAVA_HOME="D:/SDKs/JDK/jdk1.8.0_202"
mvn -B -pl kele-core test -Dtest=V2MigrationIT
```

Expected: PASS

- [ ] **Step 4: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-core/src/test/java/com/kele/core/db/V2MigrationIT.java kele-core/src/test/resources/application-test.yml
git commit -m "test(db): V2 migration integration test (B1)"
```

---

### Task 33: E2E test — sharing flow across two users

**Files:**
- Create: `kele-core/src/test/java/com/kele/core/buz/doc/E2ESharingIT.java`

- [ ] **Step 1: Write the E2E test**

```java
@SpringBootTest
public class E2ESharingIT {

    @Autowired private GroupAO groupAO;
    @Autowired private AclAO aclAO;
    @Autowired private DocFileFolderAOImpl docFileFolderAO;
    @Autowired private PermissionService permissionService;

    @Test
    public void sharedFolderAppearsInReceiverTree() {
        // 1. Alice creates a folder
        // 2. Alice shares it with Bob (READ)
        // 3. Switch to Bob context
        // 4. Call docFileFolderAO.getFolderTree(root, FOLDER)
        // Expect: Alice's folder appears in Bob's tree
    }
    @Test
    public void permissionInheritanceCascades() {
        // 1. Alice creates parent folder P
        // 2. Alice shares P with Bob (READ)
        // 3. Alice creates child C inside P
        // Expect: Bob can READ C (via inheritance)
    }
    @Test
    public void softDeleteUsesDocRecycle() {
        // 1. Alice creates folder
        // 2. Alice soft-deletes it
        // 3. Check doc_file_folder.status == 1 (unchanged)
        // 4. Check doc_recycle has entry with folder_id + user_id=Alice
    }
    @Test
    public void emptyRecycleBatchHardDeletes() {
        // 1. Alice creates 3 folders, soft-deletes all
        // 2. Alice calls emptyRecycle
        // Expect: doc_file_folder rows gone, doc_recycle rows gone
    }
    @Test
    public void onlyOwnerCanTransferOwnership() {
        // 1. Alice creates folder
        // 2. Bob (with MANAGE ACL) tries to transfer to Carol
        // Expect: 403 PERMISSION_DENIED
    }
    @Test
    public void adminCanForceResetOwner() {
        // 1. Alice creates folder
        // 2. LoginContext as admin
        // 3. Call transferOwner
        // Expect: 200 OK, owner_id updated
    }
}
```

- [ ] **Step 2: Run tests**

```bash
cd D:/Projects/Github/kele-doc
export JAVA_HOME="D:/SDKs/JDK/jdk1.8.0_202"
mvn -B -pl kele-core test -Dtest=E2ESharingIT
```

Expected: All 6 tests PASS (if not, debug and fix; these tests catch real issues)

- [ ] **Step 3: Commit**

```bash
cd D:/Projects/Github/kele-doc
git add kele-core/src/test/java/com/kele/core/buz/doc/E2ESharingIT.java
git commit -m "test(e2e): 6 E2E tests for sharing flow"
```

---

### Task 34: Final E2E — `mvn clean compile` + manual smoke test

**Files:** (no file changes)

- [ ] **Step 1: Run full build with tests**

```bash
cd D:/Projects/Github/kele-doc
export JAVA_HOME="D:/SDKs/JDK/jdk1.8.0_202"
mvn -B clean compile test
```

Expected: BUILD SUCCESS, all tests PASS

- [ ] **Step 2: Run the application against the dev DB and manually verify**

```bash
# 1. Apply V2 to dev DB
mysql -h 192.168.66.110 -u root -p kele-doc < kele-core/src/main/resources/db/migration/V2__sharing.sql

# 2. Start the app
mvn -B -pl kele-core spring-boot:run

# 3. Smoke test via curl:
#    a. Login as admin
#    b. Create a group "产品组"
#    c. Create a folder via API
#    d. Share folder with the group
#    e. Login as a regular user in the group
#    f. Verify the folder shows up in their list
```

- [ ] **Step 3: Update `application.yml` secrets**

The existing `application.yml` has hardcoded `192.168.66.110` IP and `root123` password. Replace with `${KELE_DOC_DB_URL:jdbc:mysql://...}` style env var defaults. (See spec §11 for the secrets hygiene.)

- [ ] **Step 4: Commit any final cleanup**

```bash
cd D:/Projects/Github/kele-doc
git add kele-core/src/main/resources/application.yml
git commit -m "config: replace hardcoded secrets with env var defaults"
```

---

## Self-Review

After all 34 tasks, verify:

1. **Spec coverage** — walk through each spec section (§1-§11) and confirm a task implements it. If a section has no task, add one.
   - §1 背景与目标: covered by Phase 1-3 (foundation)
   - §2 需求: implemented in §3 (data model), §4 (API), §5 (engine)
   - §3 数据模型: Tasks 6, 8, 9, 10, 11
   - §4 API: Tasks 22 (Group), 23 (ACL), 25 (search), 17/18 (folder/file endpoints refactor)
   - §5 权限引擎: Tasks 12, 13, 14
   - §6 迁移: Task 6
   - §7 软删: Tasks 17, 18 (doc_recycle refactor + emptyRecycle)
   - §8 实施步骤: this plan
   - §9 待办: covered (perms, CTEs as future perf work)
   - §10 前端: Phase 5 (Tasks 26-30)
   - §11 不在本设计范围: not implemented (per spec)

2. **Placeholder scan** — search plan for "TBD", "implement later". The plan should have none.

3. **Type consistency** — `PermissionLevel` enum values (READ/WRITE/MANAGE), `PermissionResult.Source` (OWNER/DIRECT/GROUP/INHERITED/ORG), `principalType` (USER/GROUP/ORG) are all consistent with the spec.

4. **52 §6.2 items** — all covered in Phase 3 (Tasks 15-21) and Phase 4 (Tasks 22-25). Re-cross-reference:
   - Task 15: AbstractDocFileFolderAO (4 items: getById creatorId, selectByIdList, saveRecycleLevel, getRecycleById) ✓
   - Task 16: DocFileFolderAOImpl (8 items: getFolderTree, searchFolderAndFile, getAllFolderTree, filterFileList, createFolder setOwnerId, deleteFolder, copyFolder, moveFolder) ✓
   - Task 17: DocFileContentAOImpl (5 items: createFile setOwnerId, moveFile/copyFile perms, deleteFile setId+setStatus, getFileBaseInfo) ✓
   - Task 18: DocRecycleAOImpl (4 items: getRecycleFolderAndFileList, restore, completelyDelete, emptyRecycle) ✓
   - Task 19: DocCollectFolderAOImpl (2 items: getCollectFileList, checkFilePermission) ✓
   - Task 20-21: storage impls (1 + 3 files) ✓
   - Task 23: AclController ✓
   - Task 25: SysUserInfoController search ✓

5. **v0.6/v0.7/v0.8/v0.10/v0.11/v0.12 review items** — all §6.2 rows in the spec have a corresponding task step.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-06-kele-doc-sharing.md`. Two execution options:

1. **Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration. Best for this size (34 tasks, ~4-6 hours wall clock).

2. **Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints. Better if you want to watch and adjust.

**Which approach?**
