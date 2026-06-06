# kele-doc 共享/权限/群组 设计方案

> 日期：2026-06-06
> 版本：v0.5（第二轮 agent 评审 3 BLOCKER + 6 MAJOR + MINOR/问题全部修完）
> 状态：等用户最终确认 → 移交 writing-plans
> 项目：kele-doc（原 lx-doc-java）

---

## 1. 背景与目标

### 1.1 现状
- 系统是**单租户**个人文档管理，所有者就是创建者
- 现有"共享"是**临时的**：`doc_file_folder.isPublic` Boolean 字段，**实际不被任何查询使用**（半成品）
- 数据库表里**没有** `is_public` 列
- 所有 `DocFileFolderAOImpl` 查询硬编码 `eq(creatorId, currentUserId)`，等于"完全私有"

### 1.2 目标
- 把"共享"做成**正式功能**，含：群组、权限、共享、组织内公开
- **保留老数据**：迁移脚本自动转换，不丢任何数据
- 设计可演进：未来加多租户时只需在表上加 `org_id`

### 1.3 范围
- **后端**：API + 数据模型 + 权限引擎 + 迁移
- **前端**：API 契约 + 关键组件 + Pinia store 扩展（详见 §10）
- **第三方**：未涉及

### 1.4 文件 vs 文件夹（统一 ACL 域）

`DocFileFolder` 用 `format` 字段（1=文件夹，2=文件）区分，**文件和文件夹共用同一套 ACL**：
- ACL 挂在 DocFileFolder.id 上，无论是文件夹还是文件
- 权限矩阵（§2.1）对两者**完全一致**
- 区别只在 UI 表达：文件的"分享"按钮放在文件菜单上
- 文件**内容**的读取/编辑（白板/Markdown/电子表格等）走的是各编辑器内部 API，**沿用 doc_file_folder 的 ACL 结果**

---

## 2. 需求（已与用户确认）

| 维度 | 决策 |
|---|---|
| 场景 | 企业/团队协作 |
| 租户 | 单租户（无 org 层，预留扩展位） |
| 群组 | **扁平**群组（不嵌套），**仅管理员**可建 |
| 共享范围 | 指定用户 / 指定群组 / 组织内全员 |
| 权限级别 | **3 级**：READ / WRITE / MANAGE |
| 级联策略 | **完全继承**（子 = 父的权限，不可打破） |
| 所有者（Owner） | 创建者 = Owner；可**转让**；管理员可**强制重设** |
| 老数据 | 保留 + 自动转换 |
| 数据库 | MySQL 9.3.0（用 8.0+ 语法） |

### 2.1 三级权限的真实含义

| 能力 | READ | WRITE | MANAGE |
|---|:-:|:-:|:-:|
| 查看 | ✓ | ✓ | ✓ |
| 编辑内容 | | ✓ | ✓ |
| 改结构（增删子项/改名/移动） | | | ✓ |
| 修改 ACL / 邀请 / 改角色 | | | ✓ |
| 分享 / 转让 | | | ✓ |
| 从回收站还原 | | | ✓ |
| 永久删除 | | | ✓ |
| **Owner 额外** | | | 可撤销其他 MANAGE、不可被覆盖 |

> 关键：WRITE 不能删除/分享/改 ACL；MANAGE 才能动结构。

---

## 3. 数据模型

### 3.1 新增/修改的表

```sql
-- ===========================================
-- 修改：doc_file_folder
-- 加 owner_id；删 is_public（迁移后）
-- ===========================================
ALTER TABLE doc_file_folder
  ADD COLUMN owner_id BIGINT NOT NULL DEFAULT 0 COMMENT '所有者用户ID' AFTER creator_id,
  ADD INDEX idx_owner (owner_id);
-- is_public 在迁移步骤里删（见 §6）

-- ===========================================
-- 新表：doc_file_folder_acl  统一 ACL
-- ===========================================
CREATE TABLE doc_file_folder_acl (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  folder_id       BIGINT      NOT NULL COMMENT '资源ID',
  principal_type  VARCHAR(8)  NOT NULL COMMENT 'USER / GROUP / ORG',
  principal_id    BIGINT      NULL     COMMENT 'USER=userId / GROUP=groupId / ORG=NULL',
  permission      VARCHAR(8)  NOT NULL COMMENT 'READ / WRITE / MANAGE',
  granted_by      BIGINT      NOT NULL,
  created_at      DATETIME    NOT NULL,
  revoked_at      DATETIME    NULL     COMMENT 'NULL=有效',
  KEY idx_folder (folder_id),
  KEY idx_principal (principal_type, principal_id, revoked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 唯一约束：同一资源对同一主体只能有一条有效记录
-- 关键：MySQL 的唯一索引对 NULL 按"互不相等"处理，
-- 直接用 (folder_id, principal_type, principal_id, revoked_at) 无法阻止
-- revoked_at=NULL 的重复行。用生成列把 NULL 折叠成具体值。
ALTER TABLE doc_file_folder_acl
  ADD COLUMN active_marker TINYINT
  GENERATED ALWAYS AS (IF(revoked_at IS NULL, 1, NULL)) VIRTUAL;

CREATE UNIQUE INDEX uk_acl_active
  ON doc_file_folder_acl (folder_id, principal_type, principal_id, active_marker);

-- ===========================================
-- 新表：group  群组（关键字 group 是保留字，加反引号）
-- ===========================================
CREATE TABLE `group` (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  name            VARCHAR(64)  NOT NULL,
  description     VARCHAR(255) NULL,
  created_by      BIGINT       NOT NULL,
  created_at      DATETIME     NOT NULL,
  status          TINYINT      NOT NULL DEFAULT 1 COMMENT '1=正常，0=已解散',
  UNIQUE KEY uk_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===========================================
-- 新表：group_member
-- ===========================================
CREATE TABLE group_member (
  group_id        BIGINT   NOT NULL,
  user_id         BIGINT   NOT NULL,
  joined_at       DATETIME NOT NULL,
  PRIMARY KEY (group_id, user_id),
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 3.2 实体关系

```
┌──────────────────┐       ┌──────────────────────┐
│ doc_file_folder  │1─────*│ doc_file_folder_acl  │
│ ──────────────── │       │ ──────────────────── │
│ id               │       │ folder_id (FK)       │
│ parent_id        │       │ principal_type       │
│ owner_id ★       │       │ principal_id         │
│ name, format...  │       │ permission           │
│ status           │       │ granted_by           │
└──────────────────┘       │ revoked_at           │
                           └──────────────────────┘

┌──────────┐       ┌───────────────┐
│  group   │1─────*│ group_member  │*─────1 user
└──────────┘       └───────────────┘
```

### 3.3 关键设计取舍

- **owner_id 不进 ACL 表**：转让/查询 O(1)；ACL 不需特殊 OWNER 枚举
- **revoked_at 软撤销**：保留审计可追溯；解散群组时级联软撤销
- **不用 is_public 标志**：组织内公开表达为 `principal_type=ORG, principal_id=NULL` 的 ACL 行
- **ORG public 仅支持 READ**（产品决策）：避免全员可删/改结构，UI 写死；后端也校验拒绝 ORG + WRITE/MANAGE
- **isPublic 字段从实体删除**：迁移后 DDL `DROP COLUMN`
- **MySQL 9.3.0**：直接用 8.0+ 语法（`WHERE` 唯一索引、CTE、JSON 函数）

---

## 4. API 设计

### 4.1 群组管理（仅管理员 `hasRole('ADMIN')`）

```
POST   /api/admin/groups                       body: {name, description, memberIds[]}
GET    /api/admin/groups                       ?page&size&keyword
GET    /api/admin/groups/{id}
PUT    /api/admin/groups/{id}                  body: {name?, description?}
DELETE /api/admin/groups/{id}                  soft: status=0（可恢复，见 §5.5）
POST   /api/admin/groups/{id}/restore          恢复（见 §5.5）
POST   /api/admin/groups/{id}/members          body: {userIds[]}
DELETE /api/admin/groups/{id}/members/{userId}
GET    /api/admin/groups/{id}/members
GET    /api/admin/groups/my                    当前用户加入的群组（前端 loadMyGroups 用）
```

### 4.1.1 用户搜索（ShareDialog 用）

```
GET    /api/users/search?keyword=xxx&limit=20    任意登录用户可用
返回：[{ id, username, nickname, avatar }, ...]
- 按 username/nickname 模糊匹配
- 限 limit ≤ 50
- 不返回已注销用户 (status=-1)
- 不返回管理员以外的敏感字段（邮箱、手机号）
```

### 4.2 文件夹 ACL 管理

```
GET    /api/doc/folders/{id}/acl               本级显式授权（不含继承）— 需 READ+
POST   /api/doc/folders/{id}/acl               批量授权 — 需 MANAGE
DELETE /api/doc/folders/{id}/acl/{aclId}       撤销单条 — 需 MANAGE
PUT    /api/doc/folders/{id}/acl/{aclId}       修改单条权限 — 需 MANAGE
PUT    /api/doc/folders/{id}/owner             转让 Owner — 需 Owner（admin 旁路）
```

> GET /acl 鉴权：READ 权限即可查看，但响应里 `ownerId` 字段所有 READ+ 都返回（owner 身份本身是公开的）。MANAGE+ 才能看到 `grantedBy / revokedAt` 等敏感字段。

### 4.3 文件夹操作（受权限保护）

```
GET    /api/doc/folders?scope=mine|shared|public|all
GET    /api/doc/folders/{id}
POST   /api/doc/folders                        创建
PUT    /api/doc/folders/{id}                   改名/移动
DELETE /api/doc/folders/{id}                   软删
POST   /api/doc/folders/{id}/restore           还原
```

### 4.4 关键 API 形态

**POST /api/doc/folders/{id}/acl** — 批量授权
```json
{
  "entries": [
    { "principalType": "USER",  "principalId": 1001, "permission": "READ"  },
    { "principalType": "USER",  "principalId": 1002, "permission": "WRITE" },
    { "principalType": "GROUP", "principalId": 5,    "permission": "READ"  }
  ],
  "replace": false
}
```

`replace` 字段语义：
- `false`（默认）：**追加 + 覆盖**。新增 entries 中 (folder, principal) 已存在的有效行 → 更新 permission；不存在的 → 插入
- `true`：**全量替换**。先撤销该 folder 的所有有效 ACL 行（`revoked_at=now()`），再写入 entries
- 两种模式下都受 MANAGE 权限控制
- 重复请求（重试）走 `false` 即可幂等

**GET /api/doc/folders/{id}/acl** — 本级显式授权
```json
{
  "ownerId": 7,
  "ownerName": "张三",
  "isOrgPublic": true,
  "orgPublicPermission": "READ",
  "entries": [
    { "id": 11, "principalType": "USER",  "principalId": 1001, "principalName": "李四", "permission": "READ",  "grantedBy": 7, "createdAt": "..." },
    { "id": 12, "principalType": "GROUP", "principalId": 5,    "principalName": "产品组", "permission": "WRITE", "grantedBy": 7, "createdAt": "..." }
  ]
}
```

### 4.5 错误码（ErrorCodeEnum 新增）

```java
PERMISSION_DENIED(403, "权限不足"),
RESOURCE_NOT_VISIBLE(404, "资源不存在或无权访问"),  // 鉴权失败的 404 屏蔽存在性
GROUP_NAME_DUPLICATE(409, "群组名已存在"),
USER_ALREADY_IN_GROUP(409, "用户已在群组中"),
ACL_DUPLICATE(409, "该主体已被授权"),
CANNOT_REMOVE_LAST_MANAGE(409, "至少需要保留一个管理者"),
GROUP_HAS_MEMBERS(409, "请先清空群组成员"),
// 注：原本有 OWNER_REQUIRED(403) 但因会泄露资源存在性（响应码差异攻击）已移除（见 §10.7）
// 转让 Owner 失败统一返回 PERMISSION_DENIED
```

### 4.6 API 设计决策表

| 开放问题 | 决定 | 理由 |
|---|---|---|
| 列表 scope | `?scope=mine / shared / public / all` | 1 个端点 4 视图，Notion/Google Drive 通用 |
| 撤销授权 | `DELETE /acl/{aclId}` 单条 | REST 幂等；批量前端循环 |
| 组织内公开 | 普通 ACL 行（`ORG, NULL`），UI 渲染特殊 | API 形态完全统一 |

---

## 5. 权限校验引擎

### 5.1 核心服务

```java
public class PermissionService {
  /**
   * 解析"用户 U 对资源 R 的有效权限"
   * @return 有效权限 + 来源 (OWNER/DIRECT/INHERITED/GROUP/ORG) + 来源节点
   *         sourceNode 用于 UI 展示"权限来自父级 X"等调试信息；当前仅 OWNER/INHERITED/... 标志位够用
   *         → 暂时不在响应里返 sourceNode，仅保留枚举（4 值）；后续审计/UX 需求再扩
   */
  PermissionResult resolve(Long userId, Long folderId);
}
```

### 5.2 算法（伪代码）

**关键修正（BLOCKER #3）**：先走完所有祖先链，收集全部候选，最后按 **source 优先级 + permission 大小** 选 best。**不早退**——否则祖先 Owner 检查会被本层 ACL 早退跳过，导致 `source` 错（OWNER 不可被覆盖的强语义失效）。

```
resolve(userId, folderId):
  groups = groupMemberDao.findGroupIdsByUserId(userId)
  best = (NONE, null, null)  # (permission, source, sourceNode)
  
  cursor = folderId
  while cursor != null:
    folder = folderDao.getById(cursor)
    if folder == null: break
    
    # 1. Owner 隐式 MANAGE (source=OWNER)
    if folder.ownerId == userId:
      candidate = (MANAGE, OWNER, cursor)
      best = betterOf(best, candidate)
    
    # 2. 本层 ACL 命中
    acls = aclDao.findActiveByFolder(cursor)
    for acl in acls:
      p = match(acl, userId, groups)
      if p != null:
        isDirect = (cursor == folderId)
        source = if isDirect: DIRECT
                 else if acl.principalType == GROUP: GROUP
                 else: INHERITED
        candidate = (p, source, cursor)
        best = betterOf(best, candidate)
    
    cursor = folder.parentId
  
  return best

match(acl, userId, groups):
  if acl.principalType == USER  and acl.principalId == userId: return acl.permission
  if acl.principalType == GROUP and groups.contains(acl.principalId): return acl.permission
  if acl.principalType == ORG:   return acl.permission
  return null

# source 优先级 OWNER > DIRECT > GROUP > INHERITED
# 同 source 等级时按 permission 大小 (MANAGE > WRITE > READ)
betterOf(a, b):
  sourceRank = {OWNER:4, DIRECT:3, GROUP:2, INHERITED:1, null:0}
  if sourceRank[a.source] != sourceRank[b.source]:
    return sourceRank[a.source] > sourceRank[b.source] ? a : b
  return a.permission >= b.permission ? a : b
```

### 5.3 性能

- 最坏 N 层祖先（嵌套深度），每层 1 次 ACL 查
- **生产优化（MySQL 9.3.0 CTE）**：
```sql
-- depth 限 32（远大于合理目录深度；老数据可能因历史 bug 有环，深度限制可截断）
WITH RECURSIVE ancestors AS (
  SELECT id, parent_id, owner_id, 1 AS depth FROM doc_file_folder WHERE id = :R
  UNION ALL
  SELECT f.id, f.parent_id, f.owner_id, a.depth + 1
  FROM doc_file_folder f
  JOIN ancestors a ON f.id = a.parent_id
  WHERE a.depth < 32
)
SELECT a.id, a.owner_id, acl.principal_type, acl.principal_id, acl.permission
FROM ancestors a
LEFT JOIN doc_file_folder_acl acl ON acl.folder_id = a.id AND acl.revoked_at IS NULL
   AND ((acl.principal_type='USER'  AND acl.principal_id = :uid)
     OR (acl.principal_type='GROUP' AND acl.principal_id IN :gids)
     OR (acl.principal_type='ORG'))
```
- 主体条件推进 JOIN（避免每层返回所有 ACL 再应用层过滤）
- 主体谓词：`principal_id IS NULL`（ORG）会被 ORG 命中；USER/GROUP 走 `principal_id = X` 命中索引
- **循环引用防护**：depth 限制 32 + `WHERE depth < 32` 截断。老数据若真有环，CTE 静默截断到深度 32；resolve 引擎在主循环遇到 `null` parent 即停，应用层 walk fallback（§5.2 算法是这种）
- 用户群组映射在请求入口一次性查，缓存到现有 `LoginContext.userGroupIds` 字段（沿用 `LoginContext`，不引入新概念）

### 5.4 写操作二次校验

- 所有写操作入口调 `resolve(userId, folderId)` 检查 ≥ WRITE 或 MANAGE
- MANAGE 子集：删除、改结构（重命名/移动）、分享、转让、改 ACL
- WRITE 子集：仅**编辑内容**（不动结构、不动 ACL）
- **管理员旁路的具体语义**（`SecurityContext` 检测 `hasRole('ADMIN')`）：
  - ✅ 可以**查看**任意文件夹（含已软删）
  - ✅ 可以**查看**任意 ACL
  - ✅ 可以**解散 / 恢复**群组（含非自己建的）
  - ✅ 可以**强制重设** Owner（替代 Owner 本人）
  - ✅ 可以**永久删除**任意已软删项
  - ❌ **不能**创建内容、编辑内容、改 ACL（除非有具体 MANAGE 授权）
  - ❌ **不能**绕过 MANAGE 改权限结构（避免管理员误操作）
  - ❌ **不能**直接给他人文件夹加 MANAGE（管理员也是"非 MANAGE 角色"，必须有 Owner 显式授权）
- 旁路**只用于"管理态"操作**（清回收站、解散群组、强制重设 Owner），不进入普通业务流

### 5.5 误授权防护 + 并发安全

- **owner_id 字段保护**：owner 根本不在 ACL 表里，**任何人（包括 Owner 自己）都不能通过 ACL API 影响 owner 字段**
- **撤销/修改 ACL**：单条 SQL 软撤销 + 唯一索引兜底（防并发撤销 + 重新授权同主体的竞态 → 唯一冲突返回 409 ACL_DUPLICATE）
- **删除文件夹**：校验**至少保留一个 MANAGE**（除 Owner 永远保留）
- **解散群组**：该群组所有 ACL 行级联 `revoked_at=now()`（一次性 UPDATE 走索引）；管理员可调用 §4.1 的 `POST /api/admin/groups/{id}/restore` 恢复群组；恢复时把 `revoked_at IS NOT NULL` 的 ACL 行复活（`revoked_at = NULL`），但保留原始 `created_at` 审计
- **强制重设 Owner**（admin）：单条 UPDATE，受行级事务保护

**并发锁策略**：
- 涉及"撤销最后一条 MANAGE"等"检查-修改"型操作：用 `SELECT ... FOR UPDATE`（悲观锁）锁住 folder 行
- "群组级联撤销"：单条 UPDATE，**不查后改**——靠 `affected_rows == expected_count` 校验
- "Owner 转让"：用 `UPDATE ... WHERE id = ? AND owner_id = ?` 乐观更新（CAS 风格），失败重试或报错

---

## 6. 数据迁移（Flyway V2__sharing.sql）

```sql
-- Step 1: 加 owner_id，先允许 NULL
ALTER TABLE doc_file_folder ADD COLUMN owner_id BIGINT NULL AFTER creator_id;

-- Step 2: 同步 owner_id = creator_id，creator_id 失效的指向 ADMIN
-- （避免"幽灵 ID"——已注销用户创建的文件夹变成孤儿）
UPDATE doc_file_folder f
LEFT JOIN sys_user_info u ON u.id = f.creator_id AND u.status <> -1
SET f.owner_id = COALESCE(u.id, :ADMIN_USER_ID)
WHERE f.owner_id IS NULL;

-- Step 3: NOT NULL + 索引
ALTER TABLE doc_file_folder
  MODIFY COLUMN owner_id BIGINT NOT NULL,
  ADD INDEX idx_owner (owner_id);

-- Step 4: 转换老 isPublic=true → ORG ACL（排除已软删的）
-- 排除 status=-1：已进回收站的项不应再被公开访问
-- granted_by 用 COALESCE 避免失效 creator_id 留幽灵 ID（同 Step 2）
INSERT INTO doc_file_folder_acl (folder_id, principal_type, principal_id, permission, granted_by, created_at, revoked_at)
SELECT f.id, 'ORG', NULL, 'READ', COALESCE(u.id, :ADMIN_USER_ID), NOW(), NULL
FROM doc_file_folder f
LEFT JOIN sys_user_info u ON u.id = f.creator_id AND u.status <> -1
WHERE f.is_public = 1 AND f.status <> -1;

-- Step 5: 删 is_public 列
ALTER TABLE doc_file_folder DROP COLUMN is_public;
```

### 6.1 回滚策略

- V1__init.sql 不动；新增 V2、V3...
- 迁移每步独立可执行（无外键依赖）
- `revoked_at` 软撤销 → 不丢数据
- 加 U2__revert_sharing.sql 反向迁移（V2 撤销脚本，含 `is_public` 还原）

### 6.2 代码层调整

| 文件 | 变更 |
|---|---|
| `DocFileFolder.java` | 加 `ownerId`；删 `isPublic` 字段 |
| `FileFolderCreateVO.java` | 删 `isPublic` |
| `DocFileFolderBaseResVO.java` | 删 `isPublic`（前端通过 ACL 接口获取"是否公开"） |
| `DocFileFolderAOImpl.java` | 全部查询加权限过滤（走 `PermissionService`） |
| `application.yml` | 不动 |

---

## 7. 回收站 / 软删（共享场景适配）

| 操作 | 谁能做 | 影响 |
|---|---|---|
| 软删文件夹 | 任何持有 **MANAGE** 的人 | 当前节点 + 所有后代 `status=-1` |
| 还原 | 任何祖先链上持有 **MANAGE** 的人 | 当前节点 + 所有后代 `status=0` |
| 永久删除 | **仅 Owner** 或 **管理员** | 真删 + 写 `doc_recycle` |
| 解散群组 | 仅管理员 | 该群组所有 ACL 行 `revoked_at=now()` |

### 7.1 共享+软删的边界

- 用户 A 软删一个**别人通过 ACL 共享给 A**的文件夹：只影响 A 自己视图（status=-1）
- 用户 B（被 A 共享的其他人）看到的是自己的视图（status=0 仍可见）
- 永久删除文件夹时：单条 SQL 同时清理 `doc_file_folder_acl` 中 `folder_id = :id` 的行（硬删，**不进 doc_recycle**——避免 ACL 历史数据回滚时找不到资源）

---

## 8. 实施步骤（概要）

1. **DB 迁移**（V2__sharing.sql + 新表）
2. **Entity / Mapper**（`Group`, `GroupMember`, `DocFileFolderAcl` + XML）
3. **PermissionService**（核心 resolve 逻辑）
4. **复用现有 AuthorityAspect**（详见 §10.7，不新建注解）
5. **GroupAO** + **GroupController**（管理员 API + `/api/admin/groups/my` + `/api/admin/users/search`）
6. **DocFileFolderAO 改造**：所有读查询走 PermissionService
7. **AclAO** + **AclController**（ACL 管理 API）
8. **前端 ShareDialog + FolderCard 修改 + store 扩展 + API 客户端 5 方法**（详见 §10）
9. **单元测试 + 集成测试**（权限矩阵、级联、群组级联撤销、迁移脚本）
10. **Code review + 回归测试**

---

## 9. 待办与风险

| 风险 | 缓解 |
|---|---|
| 老数据的 `creatorId` 不存在（用户已注销） | 迁移时把"找不到的 creatorId"指向系统管理员账号 |
| 嵌套层级过深导致递归慢 | CTE 限制递归深度（MySQL `cte_max_recursion_depth`） |
| 群组成员上限 | 设计上不限，但建议加 5000 上限 + 监控告警 |
| 转让 Owner 时被转让者没看到通知 | 后续加消息中心（不在本设计） |
| 多租户扩展 | 预留：表都加 `org_id` 是大改动；本设计先不动，留 follow-up |

---

## 10. 前端关键页面（kele-doc-web/）

### 10.1 新增 / 修改的 Vue 组件

| 组件 | 变更 | 说明 |
|---|---|---|
| `workbench/src/pages/workspace/components/content/ShareDialog.vue` | **新增** | 分享管理对话框（Owner/分享给谁/权限级别/全员公开 都在这里） |
| `workbench/src/pages/workspace/components/content/FolderCard.vue` | 修改 | `menuList` 在"重命名"前插入"分享"项；仅 MANAGE 可见 |
| `workbench/src/pages/workspace/components/content/NameEditDialog.vue` | 修 | `crateFolder` typo 已修 ✓ |
| `workbench/src/api/index.js` | 新增 5 方法 | ACL 端点（见 10.3） |
| `workbench/src/store.js` | 新增字段/动作 | ACL 状态缓存（见 10.2） |

### 10.2 Pinia store 新增

```js
state: () => ({
  // ... 现有字段（userInfo / currentFolder / currentFolderPath / currentDragData）
  currentFolderAcl: null,         // 当前文件夹的 ACL 列表（含 owner / 用户 / 群组 / ORG）
  myGroupIds: [],                 // 我加入的群组 ID 列表（请求入口一次性查，缓存）
  // 共享视图相关（scope=shared 用）
  sharedFoldersCache: new Map(),  // folderId -> {permission, source}
}),
actions: {
  // ... 现有 actions
  async loadCurrentFolderAcl(folderId) { /* 调 getFolderAcl */ },
  async loadMyGroups() { /* 调 admin/groups/my，返回 groupIds */ },
  async shareWithUser(folderId, userId, permission) { ... },
  async shareWithGroup(folderId, groupId, permission) { ... },
  async setOrgPublic(folderId, permission) { /* ORG ACL upsert */ },
  async revokeAcl(folderId, aclId) { ... },
  async transferOwner(folderId, newOwnerId) { ... },
}
```

### 10.3 API 客户端新增 5 方法

```js
// workbench/src/api/index.js 末尾追加（URL 与 §4 保持一致）
getFolderAcl(folderId) {
  return http.get(`/api/doc/folders/${folderId}/acl`)
},
grantFolderAcl(folderId, entries, replace = false) {
  return http.post(`/api/doc/folders/${folderId}/acl`, { entries, replace })
},
revokeFolderAcl(folderId, aclId) {
  return http.delete(`/api/doc/folders/${folderId}/acl/${aclId}`)
},
updateFolderAcl(folderId, aclId, permission) {
  return http.put(`/api/doc/folders/${folderId}/acl/${aclId}`, { permission })
},
transferFolderOwner(folderId, newOwnerId) {
  return http.put(`/api/doc/folders/${folderId}/owner`, { newOwnerId })
},
// 群组 + 用户搜索
getMyGroups() {
  return http.get('/api/admin/groups/my')
},
searchUsers(keyword, limit = 20) {
  return http.get('/api/admin/users/search', { params: { keyword, limit } })
}
```

`grantFolderAcl` 的 `replace: true` 走幂等 upsert（解决 MAJOR #5 重试问题）。

### 10.4 UI 交互流程

**触发**：FolderCard 右键 / "..." 菜单 → 看到"分享"项（仅当对当前文件夹有 MANAGE）→ 弹出 `ShareDialog`。

**ShareDialog 内部布局**（自上而下三段）：
1. **Owner 行**（不可改，由 `ownerId === currentUserId` 判定）：`<头像> 张三 (Owner)`
2. **当前 ACL 列表**：
   - 用户授权：`<头像> 李四 [READ ▾] [撤销]`
   - 群组授权：`<图标> 产品组 [READ ▾] [撤销]`
   - ORG 公开：`<图标> 全员 [READ ▾] [关闭]`（permission 仅 READ，UI 写死）
   - 每行权限下拉是 `<el-select>` 改 READ/WRITE/MANAGE → 实时调 `updateFolderAcl`
3. **添加新授权**（三个 tab + 操作）：
   - **用户 tab**：搜索用户（`api.searchUsers(keyword)`）→ 选 → 选权限 → "添加"
   - **群组 tab**：选已有群组（从 `store.myGroupIds` 加载）→ 选权限 → "添加"
   - **全员 tab**：选权限（仅 READ）→ "设为全员公开"
4. **底部 Owner 操作**（仅当前用户是 Owner 时可见）："转让所有权" → 强提示对话框

**关闭对话框**：刷新当前文件夹列表（因为权限变化影响显示）。

### 10.5 scope 语义（落实 MAJOR #4）

| scope | 含义 | 互斥 |
|---|---|---|
| `mine` | `owner_id = :userId` | — |
| `shared` | 命中 USER/GROUP ACL **且** `owner_id != :userId` | 排除 mine |
| `public` | 命中 ORG ACL **且** 不属于 mine/shared | 排除 mine, shared |
| `all` | `UNION(mine, shared, public)` | — |
| **缺省** | `mine` | — |

### 10.6 UI 权限边界（缺权时表现）

| 操作 | 触发点 | 权限要求 | 缺权 UI |
|---|---|---|---|
| 创建文件夹 | NameEditDialog "确定" | 总能（创建者 = Owner）| — |
| 分享/管 ACL | FolderCard "分享" 菜单 | MANAGE | 菜单项不显示 |
| 重命名 | FolderCard "重命名" | WRITE | 菜单项不显示 |
| 复制/移动 | FolderCard "复制/移动" | WRITE | 菜单项不显示 |
| 删除 | FolderCard "删除" | MANAGE | 菜单项不显示 |
| 软删 / 还原 | 列表行操作按钮 | MANAGE | 按钮 disable |
| 设为全员公开 | ShareDialog 全员 tab | MANAGE | tab 灰 |
| 转让 Owner | ShareDialog 底部 | Owner | 整段隐藏 |
| 访问他人资源 | GET 任意详情/列表 | 取决于有效权限 | 列表不显示 / 详情 404（屏蔽存在性） |

### 10.7 AuthorityAspect 复用（落实 MAJOR #8）

复用现有 `com.kele.core.other.aspect.authority.Authority` + `AuthorityAspect`，不新建 `@RequireFolderPermission`：
- 后端 controller 方法上贴 `@Authority(spelArgs = "#folderId", methodName = "requireManage", beanName = "permissionService")`
- 资源类型参数 + bean 方法名 → 拿到 `PermissionResult` → 与要求的 permission 等级比较
- 缺权统一抛 `PERMISSION_DENIED(403)`（落实 MAJOR #9，**移除**原计划的 `OWNER_REQUIRED`）

---

## 11. 不在本设计范围

- 通知中心（撤销/转让通知）
- 操作审计日志
- 外链共享（企业场景默认不做）
- 时间限制的临时授权
- 消息中心 / 邮件提醒
- 多租户（预留位，本期不实现）
