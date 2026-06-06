# kele-doc 共享/权限/群组 设计方案

> 日期：2026-06-06
> 版本：v0.12（第九轮 design-level review：emptyRecycle 行为未定义 + §5.4.a/b 文件级说明 + getFileBaseInfo 鉴权——3 处全部修完）
> 状态：等用户最终确认 → 移交 writing-plans
> 项目：kele-doc（原 lx-doc-java）

---

## 0. v0.5 → v0.6 修订记录（按 review 编号）

### BLOCKER
- **B1（isPublic 现有使用被低估）**：v0.5 §1.1 误称 isPublic 不被任何查询使用。实际在 3 处活跃使用：
  - `DocFileFolderAOImpl.java:108` 列表查询 `eq(isPublic, 1)` —— 重构为"命中 ORG ACL 行"语义
  - `AbstractDocFileFolderAO.java:56` `!isPublic && !creatorId.equals(userId)` 权限判断 —— 替换为 `PermissionService.resolve(userId, folderId) >= READ`
  - `DocFileFolderAOImpl.java:186` "我的文件" 硬编码 + `parent.isPublic` 强制继承 —— 替换为 `parent.isRootOrPublicEquiv()`，新文件夹继承 ORG public 状态（见 §6.2 / §7.1）
- **B2（Flyway 缺失 / JDBC 命名参数不可执行）**：pom.xml 无 flyway 依赖；V2 SQL 用 `:ADMIN_USER_ID` 命名参数 Flyway 不支持。**采用方案 B**：
  - 加 `flyway-core` 8.x（Spring Boot 2.2.6 默认 5.x 不支持 MySQL 9.3.0，需锁定） + `flyway-mysql`
  - V2 SQL 把 `:ADMIN_USER_ID` 全部改为**字面量 `1`**，并加启动校验：app 启动后 `SELECT id, role FROM sys_user_info WHERE id = 1 AND role = 'ADMIN'`，若失败抛异常拒绝启动
  - 前提：系统初始化时 id=1 用户是 admin（沿用项目现状 `doc.sql` 的 seed 模式）
- **B3（Admin 角色体系缺失）**：SysUserInfo 无 role 字段，登录只存 id+account，`hasRole('ADMIN')` 无意义。修复：
  - §3.1 `sys_user_info` 加 `role VARCHAR(16) NOT NULL DEFAULT 'USER'` 字段（默认 USER，老用户零侵入）
  - §6 V2 SQL `Step 0` 先 `ADD COLUMN role`，再把 `id=1` 用户的 role 设为 `'ADMIN'`
  - §5.4 重新定义管理员旁路：`SecurityContext` 通过 `LoginContext.userInfoBO.role == 'ADMIN'` 判定
  - §6.2 `UserInfoBO` 加 `role` 字段，登录流程同步存 Redis

### MAJOR
- **M4（`group` 表名是保留字）**：v0.5 SQL 已用反引号 ✓，但漏了 Entity 注解。补：`@TableName("`group`")` + `KeleGroup.java` 类名（MyBatis-Plus 3.5.3.1 默认 SQL 不加反引号会 `1064 syntax error`）
- **M5（CTE 应为主方案）**：v0.5 把 app-layer walk 当主、CTE 当"生产优化"。**反转**：
  - §5.2 改为"fallback"（用于单元测试、调试、单步回放）
  - §5.3 改为主方案，加 `EXPLAIN ANALYZE` 验证步骤
- **M6（`?scope=...` SQL 缺失）**：v0.5 §10.5 只有语义表，无 SQL。补完整 4 视图 SQL，集中在 §10.5
- **M7（copyFolder ACL 行为未设计）**：补 §5.4.a "copy 不继承源 ACL，副本 = 新资源 / Owner = 复制者 / ACL 起始为空（与新建文件夹等价）"
- **M8（moveFolder 跨权限域未设计）**：补 §5.4.b "move 需同时校验源父级和目标父级两侧都有 MANAGE；跨域时 ACL 行随节点迁移而保留（不重写）"
- **M9（AuthorityAspect 实际是 OGNL 不是 SpEL）**：v0.5 §10.7 用 `spelArgs` 表述错误。**修正**：
  - 注解字段 `spelArgs` → `expressionArgs`（语义中性）
  - 实现层 `OnglUtils.evaluate` 调用保留（OGNL 是项目既有栈，不替换引擎）
  - §10.7 表述改"基于 OGNL 表达式"
- **M10（DocRecycle.userId 共享场景语义模糊）**：补 §7.1 详细：
  - `DocRecycle.userId` = 回收操作人（不是原始 Owner）
  - 列表查询按 `userId = :currentUserId` 过滤
  - MANAGE 用户软删的项只在该用户的回收站可见；Owner / 其他 MANAGE 各自有独立视图
  - 永久删除时硬清 `doc_file_folder_acl` 对应行（已在 v0.5 §7.1 ✓，保留）

### MINOR
- **m11（active_marker 虚拟列开销）**：保留虚拟列方案。MySQL 8.0.16+ 虚拟列可被索引且无存储代价；sentinel 方案需权衡 `revoked_at='1970-01-01'` vs `0` 二选一都有副作用（审计可读性 vs NULL 折叠），当前选择简单可读
- **m12（403/404 混用泄露存在性）**：明确规则——404 用于"不知资源存在 + 无权访问"统一屏蔽，403 用于"已知资源 + 权限不足"。v0.5 §4.5 错误码已正确（`RESOURCE_NOT_VISIBLE(404)` + `PERMISSION_DENIED(403)` + 移除 `OWNER_REQUIRED`），本轮保留
- **m13（"我的文件" 硬编码 NPE 风险）**：§6.2 code change 表加：新增 `DocFileFolder.isRoot()` 字段（或在 V2 SQL 加 `is_root TINYINT GENERATED ALWAYS AS (CASE WHEN parent_id = 0 THEN 1 ELSE 0 END) VIRTUAL`）替代 `parent.getName().equals("我的文件")` 硬编码
- **m14（Java 8 + Spring Boot 2.2.6 语法兼容）**：§8 实施步骤加 NOTE：本项目 `source/target = 8`，禁止 `var` (11+) / Records (14+) / Text Blocks (13+) / 模式匹配 (16+)；示例代码统一用显式类型

---

## 1. 背景与目标

### 1.1 现状
- 系统是**单租户**个人文档管理，所有者就是创建者
- 现有"共享"是**临时的**：`doc_file_folder.isPublic` Boolean 字段，**实际已被 3 处使用**（修正 v0.5 描述）：
  - `DocFileFolderAOImpl.java:108` 列表查询 `eq(isPublic, 1)` —— 公开范围 = 全员
  - `AbstractDocFileFolderAO.java:56` `!isPublic && !creatorId.equals(userId)` 权限判断
  - `DocFileFolderAOImpl.java:186` "我的文件" 硬编码 + `parent.isPublic` 强制子节点继承
- **老数据中 `is_public=1` 的项**已隐式向所有人开放，但**没有任何审计/可追溯记录**（半成品）
- `DocFileFolderAOImpl` 的写操作/列表多数仍硬编码 `eq(creatorId, currentUserId)`，**读权限未统一**，混用 isPublic 和 creatorId 双重判断
- **本次重构目标**：把 isPublic 表达为标准 ACL 行（`principal_type=ORG, principal_id=NULL, permission=READ`），迁完即删 is_public 列

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
-- 对应实体类名 KeleGroup（避免与 java.lang.Object#getClass 等反射冲突）
-- @TableName("`group`") 必须加反引号，否则 MyBatis-Plus 默认生成的 SQL 会报 1064 syntax error
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

-- ===========================================
-- 修改：doc_recycle（v0.8 BLOCKER #5 落实）
-- 旧设计：id 字段 = folder_id（手动 setId），单用户独占文件夹安全
-- 新设计：id 字段 = 自增 PK；新增 folder_id 字段；多用户可软删同一文件夹
-- ===========================================
ALTER TABLE doc_recycle
  ADD COLUMN folder_id BIGINT NULL COMMENT '被回收的文件夹ID' AFTER id,
  ADD UNIQUE KEY uk_folder_user (folder_id, user_id),
  ADD KEY idx_folder (folder_id);
-- 注：旧 id 字段保留，含义改为"回收记录自增 PK"；
-- folder_id 字段迁移见 §6 doc_recycle 迁移步骤
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

┌──────────────────┐
│ doc_recycle      │*─────1 user     (v0.8: 多用户可软删同一 folder)
│ ──────────────── │
│ id (PK 自增)     │
│ folder_id (FK→dff)│
│ user_id (FK→user)│
│ create_at        │
│ UNIQUE(folder_id, user_id) │
└──────────────────┘
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
GET    /api/getFileBaseInfo?id={id}            文件基础信息（v0.12 新增）— 需 READ+
POST   /api/getFileContent?id={id}             文件内容（受 @Deprecated 影响保留）— 需 READ+
POST   /api/downloadFileContent?id={id}         文件下载 — 需 READ+
POST   /api/emptyRecycle                       清空回收站（v0.12 新增，详见 §7）
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

### 5.2 算法 — Fallback（app-layer walk，**仅供单元测试/调试/单步回放用**）

> **重要（落实 MAJOR #5）**：本节是**回退实现**，**生产路径请走 §5.3 的 CTE**。
> 单元测试用 app-layer walk 更容易构造断言（每步可观察 `cursor` 与 `best`），调试时可单步回放；CTE 是 SQL 黑盒，调试体验差。
> 两者语义完全等价——均实现"先走完所有祖先链 → 按 source 优先级 + permission 大小 选 best"的不早退语义（见算法不变量）。

**算法不变量**（v0.5 BLOCKER #3 修正，CTE 与 app-layer 共用）：
先走完所有祖先链，收集全部候选，最后按 **source 优先级 + permission 大小** 选 best。**不早退**——否则祖先 Owner 检查会被本层 ACL 早退跳过，导致 `source` 错（OWNER 不可被覆盖的强语义失效）。

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

### 5.3 性能 — Primary：MySQL 9.3.0 CTE 递归祖先链

> **落实 MAJOR #5**：本节是**生产主路径**。`PermissionService` 默认走此实现。
> App-layer walk（§5.2）仅保留作 fallback，主要用于单测/调试。
> 切 fallback 开关：`@ConditionalOnProperty(name = "kele.doc.permission.fallback", havingValue = "true")`

- **主方案：单次 SQL 取全链**（MySQL 9.3.0 CTE 递归）：
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

**CTE 性能验证步骤（实施期必做）**：
1. 在压测环境（≥ 10w 行 doc_file_folder，≥ 50w 行 doc_file_folder_acl）执行：
   ```sql
   EXPLAIN ANALYZE
   WITH RECURSIVE ancestors AS (...) -- 同上
   SELECT ... FROM ancestors a LEFT JOIN doc_file_folder_acl acl ...;
   ```
2. 验收标准：
   - 祖先链递归命中 `idx_parent` 索引（无 full table scan）
   - ACL JOIN 命中 `idx_folder`（`folder_id` 主谓词），不走 filesort
   - **单次 resolve 耗时 P99 < 5ms**（同等条件下对比 §5.2 app-layer walk 的 P99，应有 5-10x 提升）
3. 记录 EXPLAIN 输出到 `docs/superpowers/specs/2026-06-06-cte-perf-bench.md`（实施期新建），作为后续回归基线
4. 不达标 → 退化 §5.2 fallback，并记录到风险表（§9）

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

#### 5.4.a copyFolder（落实 MAJOR #7：副本不继承源 ACL）

```
POST /api/doc/folders/{id}/copy       body: { newName?, targetParentId }
```

**权限要求**：
- 源 folder `id`：当前用户对源 folder **有 READ** 即可（可读即能复制）
- 目标 `targetParentId`：当前用户对 targetParent **有 MANAGE**（只有 MANAGE 能改变父结构）
- 跨域复制（target 在别人 MANAGE 的文件夹下）：target 父的 MANAGE 满足即够

**副本语义（不继承源 ACL）**：
| 字段 | 副本取值 | 说明 |
|---|---|---|
| `id` | 新生成 | 新资源 |
| `owner_id` | 当前用户 | 复制者 = 新 Owner |
| `parent_id` | `targetParentId` | 调用方指定 |
| `format` | = 源 `format` | 文件夹/文件属性保留 |
| 内容数据 | 复制源 → 副本 | 走底层存储 Copy API（MinIO copy_object / DB INSERT…SELECT） |
| **ACL** | **空**（起始无显式授权） | **副本 = 新资源 / Owner = 复制者 / ACL 起始为空**（与新建文件夹等价） |
| `status` | `1`（正常） | 不复制源的 status（即使源在回收站，副本也是正常；通过 §7.1 单独处理源） |

**递归复制**（文件夹下含子项）：仅复制**当前节点本身**，不递归复制子树。
- 理由：子树是独立资源，复制时子树可能有不同 owner/ACL；语义不该是"资源结构复制"，而是"用户能选哪些要复制"
- v1 不实现"递归复制并保留所有子树 ACL"——留给未来增强
- **v0.7 UI 提示（落实 MAJOR #4）**：在 ShareDialog / CopyDialog 触发复制时，**前端必须显式提示用户**：
  > "复制文件夹仅复制当前节点本身，不会复制子项。子项需在目标位置单独选择复制。"
  该提示用 `<el-alert type="warning">` 一次性展示，不阻塞复制操作

**源在回收站的限制**（v0.8 修订：status 恒 1，改用 doc_recycle 查）：源 folder 在**当前用户的 doc_recycle**（即存在 `doc_recycle.user_id = :currentUserId AND doc_recycle.folder_id = :sourceFolderId`）时拒绝复制——软删资源对当前用户视为不可见

> **v0.12 文件级扩展**：本节 `copyFolder` 的权限要求对**文件级 `copyFile`**（API `/api/moveFile` `/api/copyFile`，由 `DocFileContentController` 暴露，详见 §4.3）同样适用——源 READ + 目标父 MANAGE 不变。`DocFileContentAOImpl.copyFile` 已在 §6.2 v0.11 列入重构项

**实现要点**：
- 事务：先 `INSERT doc_file_folder`（无 ACL） → 后再无操作（无 ACL 要写） → 提交
- 写完调 `permissionService.refresh(targetParentId)` 不必要（ORB public 状态是按 parent 动态解析的）
- 复制后返回新 `id`，前端刷新列表

#### 5.4.b moveFolder（落实 MAJOR #8：跨域需源/目标双 MANAGE）

```
PUT /api/doc/folders/{id}             body: { newName?, targetParentId? }
```

`targetParentId` 不为空时 = move 操作。

**权限要求（双侧 MANAGE）**：
- 源 folder `id`：当前用户对源 folder **有 MANAGE**（移动是改结构）
- 目标 `targetParentId`：当前用户对 targetParent **有 MANAGE**
- ⚠️ 跨域场景：用户 A 拥有源 folder 的 MANAGE 授权，target 在 A 没有 MANAGE 的另一个 folder 下 → **拒绝**（避免"借权限"逃逸）

**特殊拒绝场景**：
- `targetParentId` = 源 `id` 自身 / 源的任意后代：拒绝（**禁止将文件夹移动到自身或其子树**——避免环）
- `targetParentId` 的祖先链含源 folder（即把源挪到自己的子孙下）：拒绝（同上）

**ACL 行随节点迁移（不重写）**：
- `doc_file_folder_acl.folder_id = :id` 的所有行**保留原样**（permission / principal_type / principal_id / granted_by / created_at / revoked_at 全部不动）
- 理由：权限是挂在资源上的，跟资源走；新位置的用户视角通过 `resolve` 重新计算（"继承自父"vs"直接授权"会重新判定，**source 字段会变**——这是预期行为）
- 同样递归处理所有后代节点：每个后代 folder 的 ACL 行也**保留原样**

**owner_id 字段处理**：
- move **不改变** owner_id
- 若要换 Owner → 走 §4.2 `PUT /owner` 转让接口（额外一道 Owner 校验）
- 管理员可走旁路（§5.4）用 §4.2 强制重设

**实现要点**：
- 循环引用防护：先用 §5.3 同一份 CTE 自底向上展开 `targetParentId` 的祖先链（深度限 32）→ 断言 `源 id` 不在祖先集合里
- 事务：`UPDATE doc_file_folder SET parent_id = :newParent WHERE id = :id` 单条
- 移动后调 `permissionService.refresh(:id)` 不必要（resolve 是按需计算）
- 移动后返回新位置，前端刷新列表

> **v0.12 文件级扩展**：本节 `moveFolder` 的权限要求对**文件级 `moveFile`**（API `/api/moveFile`，由 `DocFileContentController` 暴露，详见 §4.3）同样适用——源 MANAGE + 目标父 MANAGE 不变。`DocFileContentAOImpl.moveFile` 已在 §6.2 v0.11 列入重构项

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

> **落实 BLOCKER #2**：本设计依赖 Flyway。pom.xml 加 `flyway-core 8.x`（Spring Boot 2.2.6 默认 5.x 不支持 MySQL 9.3.0，需锁定）+ `flyway-mysql`。
> V2 SQL 不使用 JDBC 命名参数（Flyway SQL 解析器不支持 `:ADMIN_USER_ID` 这种写法）——改用**字面量 `1`**作为系统初始化 admin id（与项目 `doc.sql` seed 模式一致）。
> 启动期强校验：app 启动后 `SELECT id, role FROM sys_user_info WHERE id = 1 AND role = 'ADMIN'`，若失败抛异常拒绝启动（见 §6.3）。

```sql
-- Step 0: sys_user_info 加 role 字段（落实 BLOCKER #3）
-- 默认 'USER'，老用户零侵入；Step 0.b 显式把 id=1 提升为 ADMIN
ALTER TABLE sys_user_info
  ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'USER' COMMENT 'USER=普通用户, ADMIN=管理员' AFTER status,
  ADD INDEX idx_role (role);

UPDATE sys_user_info SET role = 'ADMIN' WHERE id = 1;

-- Step 0.5: doc_file_folder 加 is_root 虚拟列（落实 MINOR #6，替代硬编码 "我的文件"）
ALTER TABLE doc_file_folder
  ADD COLUMN is_root TINYINT
  GENERATED ALWAYS AS (CASE WHEN parent_id = 0 THEN 1 ELSE 0 END) VIRTUAL,
  ADD INDEX idx_is_root (is_root);

-- Step 0.7: doc_recycle 加 folder_id + 数据迁移（v0.8 BLOCKER #5 落实）
-- 旧设计：id 字段被 Java 代码 setId(folderId) 强制设为 folder 的 id
-- 新设计：id 字段是 AUTO_INCREMENT PK；新增 folder_id 字段；多用户可软删同一 folder
-- 数据回填：旧 row 的 id 字段 = folder_id（业务事实），所以直接 UPDATE
ALTER TABLE doc_recycle
  ADD COLUMN folder_id BIGINT NULL AFTER id,
  ADD UNIQUE KEY uk_folder_user (folder_id, user_id);

UPDATE doc_recycle SET folder_id = id WHERE folder_id IS NULL;

-- Step 1: 加 owner_id，先允许 NULL
ALTER TABLE doc_file_folder ADD COLUMN owner_id BIGINT NULL AFTER creator_id;

-- Step 2: 同步 owner_id = creator_id，creator_id 失效的指向 ADMIN（id=1）
-- （避免"幽灵 ID"——已注销用户创建的文件夹变成孤儿）
-- 字面量 1 替换 :ADMIN_USER_ID（Flyway 不支持命名参数）
UPDATE doc_file_folder f
LEFT JOIN sys_user_info u ON u.id = f.creator_id AND u.status <> -1
SET f.owner_id = COALESCE(u.id, 1)
WHERE f.owner_id IS NULL;

-- Step 3: NOT NULL + 索引
ALTER TABLE doc_file_folder
  MODIFY COLUMN owner_id BIGINT NOT NULL,
  ADD INDEX idx_owner (owner_id);

-- Step 4: 转换老 isPublic=true → ORG ACL（排除已软删的）
-- 排除 status=-1：已进回收站的项不应再被公开访问
-- granted_by 用 COALESCE 避免失效 creator_id 留幽灵 ID（同 Step 2；用字面量 1）
INSERT INTO doc_file_folder_acl (folder_id, principal_type, principal_id, permission, granted_by, created_at, revoked_at)
SELECT f.id, 'ORG', NULL, 'READ', COALESCE(u.id, 1), NOW(), NULL
FROM doc_file_folder f
LEFT JOIN sys_user_info u ON u.id = f.creator_id AND u.status <> -1
WHERE f.is_public = 1 AND f.status <> -1;

-- Step 5: 删 is_public 列
ALTER TABLE doc_file_folder DROP COLUMN is_public;
```

### 6.3 启动期 Admin 强校验（落实 BLOCKER #2 闭环）

`KeleDocApplicationRunner`（`ApplicationRunner` 实现，`@Order(0)` 确保在 web 容器前）：

```java
@Override
public void run(ApplicationArguments args) {
  SysUserInfo admin = sysUserInfoDao.selectById(1);
  if (admin == null || !"ADMIN".equals(admin.getRole())) {
    throw new IllegalStateException(
      "[Kele-Doc] 启动失败：sys_user_info.id=1 用户必须存在且 role='ADMIN'。" +
      "当前: " + (admin == null ? "NULL" : "id=" + admin.getId() + ", role=" + admin.getRole()) +
      "。请参考 docs/superpowers/specs/2026-06-06-kele-doc-sharing-design.md §6 修复。"
    );
  }
  log.info("[Kele-Doc] 启动校验通过：admin user id=1 role=ADMIN");
}
```

- 校验失败 → Spring Boot 启动失败，进程退出，**不会**有半启动状态
- 校验时机：`ApplicationArguments` 在 `ContextRefreshedEvent` 之后、`WebServerInitializedEvent` 之前，**先于** `PermissionService` 被首次注入
- 仅生产环境开启：开发环境可用 `kele.doc.startup.admin-check.enabled=false` 关掉（仅方便新人首次跑空库）
- 不写单元测试：纯防御性代码，覆盖靠部署手册

### 6.1 回滚策略

- V1__init.sql 不动；新增 V2、V3...
- 迁移每步独立可执行（无外键依赖）
- `revoked_at` 软撤销 → 不丢数据
- 加 U2__revert_sharing.sql 反向迁移（V2 撤销脚本，含 `is_public` 还原）

### 6.2 代码层调整

> **落实 BLOCKER #1/BLOCKER #3 / MAJOR #4 / MINOR #13**：本节按 v0.5 现有 3 处 isPublic 落点 + admin 角色体系 + 关键字 group 反引号 + isRoot() 重构 全部列明。

| 文件 | 变更 | 落实点 |
|---|---|---|
| `pom.xml` | **新增** `flyway-core` 8.x + `flyway-mysql`（**显式 version override**） | B2（Flyway 缺失） |
| `application.yml` | 新增 `spring.flyway.enabled: true`、`spring.flyway.locations: classpath:db/migration`；新增 `kele.doc.startup.admin-check.enabled: true`（默认 true） | B2 / B3 |
| `SysUserInfo.java`（entity） | 加 `role` 字段（`@TableField("role")`） | B3 |
| `SysUserInfoAOImpl.java` 登录流程 | 登录成功查 user 时 `SELECT id, account, role` 写入 `UserInfoBO`；同步 `setAttribute("role", ...)` 到 `LoginContext`（Redis 已有） | B3 |
| `UserInfoBO.java` | 加 `role` 字段（getter/setter） | B3 |
| `KeleDocApplicationRunner.java`（**新文件**） | `ApplicationRunner` 实现，启动期校验 id=1 用户存在且 role='ADMIN'，否则抛 `IllegalStateException` 拒绝启动 | B2 闭环（§6.3） |
| `KeleGroup.java`（entity，**新文件**） | 类名 `KeleGroup`；**`@TableName("`group`")` 必须加反引号**——MyBatis-Plus 3.5.3.1 默认生成的 SQL 不会加反引号，否则 `1064 syntax error` | M4 |
| `GroupMember.java`（entity，**新文件**） | 普通表名，按 MyBatis-Plus 默认即可 | — |
| `DocFileFolderAcl.java`（entity，**新文件**） | 主表，PK=自增 id；与 §3.1 DDL 一一对应 | — |
| `DocFileFolder.java`（entity） | 加 `ownerId` 字段；**删 `isPublic` 字段**；**新增 `isRoot()` 方法**：`return parentId == null \|\| parentId == 0L;`（落实 MINOR #13 替代硬编码 "我的文件"） | B1 / m13 |
| `DocFileFolder.java` V2 SQL 替代 | V2 SQL 加 `is_root TINYINT GENERATED ALWAYS AS (CASE WHEN parent_id = 0 THEN 1 ELSE 0 END) VIRTUAL` + 索引 `idx_is_root (is_root)`——双轨方案：Java `isRoot()` 兜底，DB 列给 SQL `WHERE is_root = 1` 性能用 | m13 |
| `FileFolderCreateVO.java` | 删 `isPublic` | B1 |
| `DocFileFolderBaseResVO.java` | 删 `isPublic`；新增 `isOrgPublic`（boolean，从 ACL 接口 `GET /acl` 拿到） | B1 |
| `DocFileFolderAOImpl.java` 列表查询（原 `line 108` `eq(isPublic, 1)`） | **重构**：`eq(OwnerId, currentUserId).or().exists(aclSubQuery)`——子查询命中 `doc_file_folder_acl.principal_type='ORG' AND folder_id = doc.id AND revoked_at IS NULL`。原 `isPublic=1` 语义被 ORG ACL 行替代（V2 Step 4 迁移数据） | B1 |
| `DocFileFolderAOImpl.java` "我的文件" 创建（原 `line 186`） | **重构**：删 `parent.getName().equals("我的文件")` 硬编码 + `parent.isPublic` 判断；改为 `parent.isRoot() ? ACL_ORG_PUBLIC : resolveInherit(parent.acl)`——根节点下创建 = 默认公开组织；非根节点下创建 = 继承父 ACL（深拷贝）。v0.5 line 186 隐式行为现在通过 `isRoot()` 显式表达 | B1 / m13 |
| `AbstractDocFileFolderAO.java` 权限校验（原 `line 56`） | **重构**：`!isPublic && !creatorId.equals(userId)` → `permissionService.resolve(userId, folderId).getLevel().getValue() < PermissionLevel.READ.getValue()`——统一走 `PermissionService`（v0.5 §5）。`isPublic` 字段读取从代码层彻底删除 | B1 |
| `PermissionService.java`（**新文件**） | `resolve(userId, folderId): PermissionResult`，默认走 §5.3 CTE；`@ConditionalOnProperty(name = "kele.doc.permission.fallback", havingValue = "true")` 时切 §5.2 app-layer walk | M5 落地 |
| `GroupAO.java` / `GroupController.java`（**新文件**） | §4.1 群组管理 API；写操作前 `LoginContext.hasRole('ADMIN')` 校验 | B3 / §4.1 |
| `AclAO.java` / `AclController.java`（**新文件**） | §4.2 ACL API；`@Authority(expressionArgs = "#folderId", methodName = "requireManage", beanName = "permissionService")` 复用现有 `AuthorityAspect`（OGNL 而非 SpEL，见 §10.7） | M9 落地 |
| `application.yml` | `kele.doc.permission.fallback: false`（生产主路径走 CTE，§5.3） | M5 |
| `DocRecycle.java`（entity，**重构**） | **新增 `folderId` 字段**（`@TableField("folder_id")`）；`id` 字段含义从"folderId"改为"回收记录自增 PK"（保持 `@TableId(type=IdType.AUTO)` 即可，**不再**在 AO 层 `setId(folderId)`） | B5（多用户主键冲突） |
| `DocRecycleAOImpl.java`（**重构**） | 软删时 `INSERT` 只设 `folderId / userId / name / createAt`（**不再** `setId`）；**取消** `getOne({folderId: x})` 类的"一个 folder 只能一个回收记录"查询；改 `list({folderId: x, userId: y})` 唯一性校验（由 `UNIQUE(folder_id, user_id)` 兜底） | B5 |
| `DocFileFolderAOImpl.java` 软删（原 `deleteFolder` 调用 `docRecycle.setId(docFileFolder.getId())`） | **删除** `setId(docFileFolder.getId())` 那一行——现在 folder id 和 doc_recycle row id 是两个独立字段 | B5 |
| `DocFileFolderAOImpl.java` 软删 line 258-260 | **删除** `.set(DocFileFolder::getStatus, DelStatusEnum.DEL.getStatus())` 整段——v0.7 §7.1.a "status 恒为 1" 不变量要求软删不改 status，**仅**靠 INSERT `doc_recycle` 标记实现"软删"语义；保留 `.set(...)` 会让 `status=-1` 仍能查出，与 v0.7 软删模型直接矛盾，`doc_recycle` 标记机制完全失效 | B5（v0.10 MAJOR） |
| `AbstractDocFileFolderAO.java` `saveRecycleLevel` line 113 | `docRecycle.getId()` → `docRecycle.getFolderId()`（line 108 用了 `docRecycle.getIdList()` 是 folder id 集合——line 113 的 parent_id 应与 idList 同源；新模型下 id 是回收记录自增 PK，不是 folderId） | B5 / m7 |
| `AbstractDocFileFolderAO.java` `selectByIdList` line 79 | 删 `!e.getCreatorId().equals(userId)` 硬编码 creatorId 校验；改 `permissionService.requireRead(folderId)` 走 PermissionService（与 `getById` line 56 同一模式） | B1 / m8 |
| `AbstractDocFileFolderAO.java` `selectByIdList` line 83-84（**bonus bug**，我多扫到） | 错误消息 `String.format("id为%s的资源禁止访问！", noExitsIdList...)` 里写的是 `noExitsIdList`（此时为空），**应该是 `forbidList`**——否则 403 时错误消息不显示具体禁止访问的 id 列表 | — |
| `DocRecycle.java` SQL 索引 | 加 `KEY idx_folder (folder_id)` 与 V2 SQL 一致（V2 已加） | B5 |
| `DocRecycle.java` / `DocRecycleAOImpl.java` 列表查询 | 加 `WHERE user_id = :currentUserId` 强制过滤（v0.6 §7.1.a 落实） | M10 |
| `DocRecycleAOImpl.java` `getRecycleFolderAndFileList` line 60 | `DocRecycle::getId` → `DocRecycle::getFolderId`（v0.8 后 id 是回收记录自增 PK，**不再是** folderId） | B5 / m7 |
| `DocRecycleAOImpl.java` `restore` line 87 | `DocRelationLevel::getParentId, id` → `, docRecycle.getFolderId()`（line 82 的 `id = reqVO.getId()` 是回收记录 id，DocRelationLevel 关系表 parent_id 应是 folderId） | B5 / m7 |
| `DocRecycleAOImpl.java` `restore` line 122 | `DocFileFolder::getId, id` → `, docRecycle.getFolderId()`（同样的 `id` 误用：这里 id 是回收记录 id，但 update 的是 doc_file_folder 表） | B5 / m7（**我多扫到 1 处**） |
| `DocRecycleAOImpl.java` `restore` line 116 | 加 `.eq(DocRecycle::getUserId, docRecycle.getCreatorId())` 安全过滤（防御性；多用户场景下避免跨用户删回收记录） | B5（我多扫到） |
| `DocRecycleAOImpl.java` `getRecycleById`（helper，line 157） | **整个方法重构**：从 `docFileFolderService.getById(id)` 改为先 `docRecycleService.getById(id)` → `docFileFolderService.getById(docRecycle.getFolderId())`；404/403 提示也需调整为"找不到回收记录 / 找不到对应文件夹"。**调用方**（line 93 `getRecycleById(id)` 在 `restore`、line 141 在 `completelyDelete`）都需要确保传进来的是 recycle id | B5（我多扫到 1 处） |
| `DocFileContentAOImpl.java` `deleteFile` line 278 | `docRecycle.setId(e.getId())` → `docRecycle.setFolderId(e.getId())`（与 deleteFolder 同根因，v0.10 v0.11 双 B5 蔓延） | B5 |
| `DocFileContentAOImpl.java` `deleteFile` line 286-288 | **删除** `.set(DocFileFolder::getStatus, DelStatusEnum.DEL.getStatus())` 整段——v0.10 同类问题在文件级复发 | B5（v0.11 MAJOR） |
| `DocFileContentAOImpl.java` `moveFile` line 168 + `copyFile` line 214 | 都调 `super.selectByIdList(idList)`——selectByIdList 的 creatorId 校验已在 v0.10 改造；**这俩方法本身**还需加权限校验：move 源 WRITE + 目标父 MANAGE（§5.4.b 文件级）；copy 源 READ + 目标父 MANAGE（§5.4.a 文件级）。**§5.4.a/b 当前只标了文件夹级，漏标文件级** | B5 / m9（v0.11 MAJOR） |
| `DocFileContentAOImpl.java` `createFile` line 72 | `fileFolder.setCreatorId(...)` 之后**追加** `fileFolder.setOwnerId(LoginContext.getUserId())`（v0.7 资源创建者 = Owner） | B1 |
| `DocFileFolderAOImpl.java` `getFolderTree` line 59 | 删 `.eq(DocFileFolder::getCreatorId, userId)`；改走 `scope=mine` SQL（§10.5）— 当前只显示自己的文件夹树，共享文件夹不出现在树中 | B1（v0.11 MAJOR） |
| `DocFileFolderAOImpl.java` `searchFolderAndFile` line 148 | 删 `.eq(DocFileFolder::getCreatorId, userId)`；改走 `scope=shared` 或 `scope=all`（按用户输入决定） | B1（v0.11 MAJOR） |
| `DocFileFolderAOImpl.java` `getAllFolderTree` line 418（**@Deprecated**） | 删 `.in(DocFileFolder::getCreatorId, LoginContext.getUserId())`；改走 `scope=all`。方法已 @Deprecated 仍需修以防被误调 | B1（v0.11 MINOR） |
| `DocFileFolderAOImpl.java` `filterFileList` line 461 | 删 `.filter(!isTop \|\| folder.getCreatorId()...)` 硬编码；顶层目录下应显示"我可见"的所有文件——通过 resolve(userId, parentFolderId) >= READ 过滤 | B1（v0.11 MINOR） |
| `DocFileFolderAOImpl.java` `createFolder` line 203 | `docFileFolder.setCreatorId(...)` 之后**追加** `docFileFolder.setOwnerId(LoginContext.getUserId())`（v0.7 资源创建者 = Owner） | B1（v0.11 MINOR） |
| `DocCollectFolderAOImpl.java`（**新文件**——之前 §6.2 未列） | 整体重构两处 | B1（v0.11 MAJOR ×2） |
| `DocCollectFolderAOImpl.java` `getCollectFileList` line 36 | 删 `.eq(DocFileFolder::getCreatorId, userId)`；改走 PermissionService 过滤（`scope=mine + collected=true`）—— 当前只显示自己的收藏，共享+收藏的文件不显示 | B1（v0.11 MAJOR #5） |
| `DocCollectFolderAOImpl.java` `checkFilePermission` line 82 | 删 `!fileFolder.getCreatorId().equals(userId)` 硬编码；改 `permissionService.requireRead(folderId)`——READ 权限的共享用户应能收藏/取消收藏 | B1（v0.11 MAJOR #6） |
| `DateBaseDocFileContentStorageServiceImpl.java` `getByFileId` line 180 | 删 `!docFileContent.getCreatorId().equals(LoginContext.getUserId())` 硬编码；改走 `permissionService.requireRead(fileId)`——§1.4 明确"文件内容读取沿用 doc_file_folder 的 ACL 结果"，这是文件内容**是否对共享用户开放**的关键路径 | B1（v0.11 MAJOR #7） |
| `DocRecycleAOImpl.java` `emptyRecycle`（v0.12 MAJOR） | 当前代码**只删** `doc_recycle` + `doc_relation_level`，**不删** `doc_file_folder`——recycle 删了 folder 状态=1 仍存在，会重新出现在用户列表。**重构**：①查 `doc_recycle WHERE user_id = :currentUserId` 收所有 folder_id；②单事务批量硬删 `doc_file_folder`（by folder_id）+ `doc_file_folder_acl`（by folder_id）+ `doc_file_content`（by file_id JOIN doc_file_folder）+ `doc_relation_level`（by parent_id）；③删 `doc_recycle` + `doc_relation_level`（user_id 过滤）。事务原子，部分失败回滚 | B1（v0.12 MAJOR） |
| `DocFileContentAOImpl.java` `getFileBaseInfo` line 309-317（v0.12 MINOR） | 删 `docFileFolderService.getById(id)` 无鉴权直接读；改 `permissionService.requireRead(id)` 走 PermissionService——§4.3 已列该 API 需 READ+ 权限，§6.2 此条目落实 | B1（v0.12 MINOR） |

#### 6.2.a `pom.xml` Flyway 8.x 显式 version override（落实 MINOR #7）

Spring Boot 2.2.6 的 BOM 默认锁定 `flyway-core 5.2.4`，**不支持 MySQL 9.3.0**。需要在 `pom.xml` 显式覆盖：

```xml
<properties>
  <!-- 覆盖 Spring Boot 2.2.6 默认 flyway 5.2.4 -->
  <flyway.version>8.5.13</flyway.version>
  <flyway-mysql.version>8.5.13</flyway-mysql.version>
</properties>

<dependencies>
  <dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
    <!-- version 由 <flyway.version> 覆盖，禁删 -->
  </dependency>
  <dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
    <version>${flyway-mysql.version}</version>
  </dependency>
</dependencies>
```

**关键点**：
- `<flyway.version>8.5.13</flyway.version>` 是 Spring Boot 兼容矩阵内能正常工作的版本（8.5.x 系列对 MySQL 9.x 支持好）
- `flyway-mysql` 必须单独加（5.x 时代不需，8.x 后拆出来）
- 不要写 `<dependencyManagement>` 覆盖——`<properties>` 已经够，避免双重管理

---

## 7. 回收站 / 软删（共享场景适配，v0.8 修订：status 不变，doc_recycle 标记）

> **v0.8 重大修订**：v0.7 之前 summary 表写"软删后 status=-1"，与 §7.1.a "status 保持 1" 矛盾。**全表重写**采用 §7.1.a 的 "doc_recycle 标记 + 物理行 status 不变" 模型。

| 操作 | 谁能做 | 物理影响 | 视图变化 |
|---|---|---|---|
| 软删文件夹 | 任何持有 **MANAGE** 的人 | `status` **保持 1**；INSERT `doc_recycle` (`folder_id`, `user_id = 操作人`, ...) | 操作人自己的 `scope=mine` 列表里**看不到**这个文件夹（被 §10.5 NOT IN doc_recycle 过滤）；其他人视图不变 |
| 还原 | 回收操作人自己（`doc_recycle.user_id = currentUserId`） | DELETE 对应 `doc_recycle` 行 | 操作人列表里又看到 |
| 永久删除 | 回收操作人自己 或 管理员 | 硬删 `doc_file_folder` + 硬删 `doc_file_folder_acl` (folder_id=X) + 硬删 `doc_recycle`（userId = 操作人） | 该 folder_id 对所有人消失 |
| 解散群组 | 仅管理员 | 该群组所有 ACL 行 `revoked_at=now()` |
| **清空回收站**（v0.12 新增） | 回收操作人自己（批量"永久删除"自己软删的所有 folder） | 收集当前用户 `doc_recycle.user_id = :currentUserId` 的所有 `folder_id`；**批量硬删** `doc_file_folder` + `doc_file_folder_acl`（按 folder_id 删）+ `doc_file_content`（按 file_id 删）+ `doc_relation_level`（按 parent_id 删）+ 对应 `doc_recycle` 行；**单事务**，部分失败回滚 | 这些 folder 从所有人的所有视图消失（v0.12 MAJOR） | — |

**关键不变量**：物理行 `status` 恒为 1（不区分软删/正常）；区分靠 `doc_recycle` 存在与否。**因此所有列表 SQL 必须 `AND dff.id NOT IN (SELECT folder_id FROM doc_recycle WHERE user_id = :currentUserId)`**（见 §10.5）。

### 7.1 共享+软删的边界（落实 v0.7 修订：status 不变，由 doc_recycle 标记）

> **v0.7 重大修订**：v0.6 §7.1 的"只影响 A 自己视图（status=-1）"与 §7.1.a 的"物理行 status 不变"自相矛盾。v0.7 统一采用 §7.1.a 的"status 不变 + doc_recycle 标记"设计，理由见 §7.1.a 末尾。

- **核心规则**：软删 = 写一行 `doc_recycle` 标记（`user_id = 回收操作人`），物理行 `doc_file_folder.status` **保持 1** 不变
- 用户 A 软删一个**别人通过 ACL 共享给 A**的文件夹：A 自己的回收站多一行（`user_id=A, folder_id=X`），但 X 的 `status` 还是 1
- 用户 B（被共享的其他人）看到的视图：X 仍可见（status=1）—— B 没动过它，B 的回收站也没有 X
- 永久删除文件夹时：单条 SQL 同时清理 `doc_file_folder_acl` 中 `folder_id = :id` 的行（硬删，**不进 doc_recycle**——避免 ACL 历史数据回滚时找不到资源）+ 删 `doc_recycle` 中对应行（按 userId 删自己的）
- **状态唯一**：全仓库每个 folder 只有 1 个 `status` 值（不是 per-user 视图割裂）；DB 索引/缓存/同步不踩坑
- 列表查询：`GET /api/doc/recycle` 强制 `WHERE user_id = :currentUserId`，只看自己的回收操作

#### 7.1.a `doc_recycle.userId` 语义（落实 MAJOR #10）

`DocRecycle` 表的 `user_id` 字段 = **回收操作人**（执行软删/还原的人），**不是**原始 Owner。区分这两个角色对共享场景至关重要：

| 角色 | 含义 | 回收站可见性 |
|---|---|---|
| **回收操作人** (`doc_recycle.user_id`) | 实际点"删除"按钮的人 | 在自己回收站看到该条记录 |
| **资源 Owner** (`doc_file_folder.owner_id`) | 资源创建者（永不删） | **不在自己回收站看到**（除非自己也是回收操作人） |
| 其他 MANAGE 授权用户 | 持有 MANAGE 的非 Owner | **不在自己回收站看到**（除非自己也是回收操作人） |

**示例场景**：
- Owner = Alice，Alice 通过 ACL 共享给 Bob (MANAGE) 和 Carol (WRITE)
- Bob 软删 folder X → `doc_recycle` 写一行 `(user_id=Bob, folder_id=X, op_type=SOFT_DELETE, op_time=now())`
- 列表查询 `SELECT * FROM doc_recycle WHERE user_id = :currentUserId`
  - Bob 登录 → 看到 X 在自己回收站
  - Alice 登录 → 回收站**没有** X（Alice 没动过它）
  - Carol 登录 → 回收站**没有** X
- X 的 `doc_file_folder.status` 仍是 `1`（**不是** `-1`）——v0.5 软删策略调整为"标记进回收站 = 业务级删除，物理行 status 不变"。**避免** `status=-1` 多用户视图割裂（不同人对同一资源有不同 status 是大坑）

**误删防护与还原**：
- 任何人能"软删"自己可见的资源（MANAGE+），软删后其他人仍可见原资源——只是回收操作人自己的视图里"进回收站"了
- 还原 = 回收操作人从自己回收站点"还原"；不需要其他 MANAGE 同意（他只是撤回自己的"软删标记"）
- 永久删除 = 回收操作人点"永久删除"；**只有回收操作人自己**能永久删除自己产生的回收站记录
  - **例外**：管理员（`hasRole('ADMIN')`）可永久删除任意人的回收站记录（见 §5.4 旁路）

**代码层调整**：
- `DocRecycle.java` entity 已有 `userId` 字段（落实 v0.5 既有事实）——本设计**保留**该字段，但明确语义
- 列表 API：`GET /api/doc/recycle` SQL 强制带 `WHERE user_id = :currentUserId`——**禁止**"看所有 MANAGE 过的资源回收记录"
- 永久删除：先 `SELECT user_id FROM doc_recycle WHERE id = :recycleId` 校验 == currentUserId（或 admin 旁路）→ 通过后硬删 `doc_file_folder` + 关联 `doc_file_folder_acl` + `doc_recycle` 自身行（单事务）

**为什么这么设计**：
- 若 `doc_recycle` 用 `owner_id` 过滤 → Alice 看到一堆"Bob/Carol 误删"的回收记录，体验差且 Alice 无法操作
- 若按 `status=-1` 多用户视图割裂 → 同一资源不同人有不同 status，DB 索引/缓存/同步处处是坑
- 现在的"按回收操作人 + status 不变"是**最简单**的方案：状态 1 个，回收站按操作人切，永久删除按操作人鉴权

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

### 10.5 scope 语义（落实 MAJOR #4 / MAJOR #6）

> **落实 MAJOR #6**：v0.5 §10.5 只有语义表无 SQL。本节补完整 4 视图 SQL（基于 §5.3 CTE 思路）。
> 所有 SQL 接收 `:userId` (BIGINT) 和 `:groupIds` (List<BIGINT>) 两个参数，groupIds 为当前用户加入的所有群组 ID（`LoginContext.userGroupIds`）。

| scope | 含义 | 互斥 |
|---|---|---|
| `mine` | `owner_id = :userId` | — |
| `shared` | 命中 USER/GROUP ACL **且** `owner_id != :userId` | 排除 mine |
| `public` | 命中 ORG ACL **且** 不属于 mine/shared | 排除 mine, shared |
| `all` | `UNION(mine, shared, public)` | — |
| **缺省** | `mine` | — |

**核心 CTE 复用**：先用一个公共 CTE 算出"我对哪些 folder 有非 NONE 权限（含来源）"，各 scope 在此基础上筛选：

```sql
-- 公共 CTE：算出我对哪些 folder 有任何权限（v0.7 修：真正递归支持任意深度继承）
-- 算法：先定位"ACL 命中点"（直接打中或匹配 ORG），再向下递归所有后代
WITH RECURSIVE acl_hits AS (
  -- 直接命中本层的 folder
  SELECT DISTINCT acl.folder_id
  FROM doc_file_folder_acl acl
  WHERE acl.revoked_at IS NULL
    AND (
      (acl.principal_type = 'USER'  AND acl.principal_id = :userId)
      OR (acl.principal_type = 'GROUP' AND acl.principal_id IN :groupIds)
      OR (acl.principal_type = 'ORG')
    )
),
my_permitted_folders AS (
  -- Seed：ACL 命中点本身
  SELECT f.id AS folder_id, f.owner_id, f.parent_id, f.name, f.format,
         (SELECT acl.permission FROM doc_file_folder_acl acl
          WHERE acl.folder_id = f.id AND acl.revoked_at IS NULL
            AND (
              (acl.principal_type = 'USER'  AND acl.principal_id = :userId)
              OR (acl.principal_type = 'GROUP' AND acl.principal_id IN :groupIds)
              OR (acl.principal_type = 'ORG')
            )
          ORDER BY FIELD(acl.principal_type,'OWNER','USER','GROUP','ORG')
          LIMIT 1) AS permission,
         (SELECT acl.principal_type FROM doc_file_folder_acl acl
          WHERE acl.folder_id = f.id AND acl.revoked_at IS NULL
            AND (
              (acl.principal_type = 'USER'  AND acl.principal_id = :userId)
              OR (acl.principal_type = 'GROUP' AND acl.principal_id IN :groupIds)
              OR (acl.principal_type = 'ORG')
            )
          ORDER BY FIELD(acl.principal_type,'OWNER','USER','GROUP','ORG')
          LIMIT 1) AS principal_type,
         (SELECT acl.principal_id FROM doc_file_folder_acl acl
          WHERE acl.folder_id = f.id AND acl.revoked_at IS NULL
            AND (
              (acl.principal_type = 'USER'  AND acl.principal_id = :userId)
              OR (acl.principal_type = 'GROUP' AND acl.principal_id IN :groupIds)
              OR (acl.principal_type = 'ORG')
            )
          ORDER BY FIELD(acl.principal_type,'OWNER','USER','GROUP','ORG')
          LIMIT 1) AS principal_id,
         0 AS is_inherited
  FROM doc_file_folder f
  INNER JOIN acl_hits h ON h.folder_id = f.id

  UNION ALL

  -- 递归：所有后代继承父级权限
  SELECT dff.id, dff.owner_id, dff.parent_id, dff.name, dff.format,
         mpf.permission, mpf.principal_type, mpf.principal_id,
         1 AS is_inherited
  FROM my_permitted_folders mpf
  INNER JOIN doc_file_folder dff ON dff.parent_id = mpf.folder_id
)
```

**`scope=mine`**：直接走 owner_id，无 CTE
```sql
SELECT id, parent_id, name, format, owner_id, status, update_at
FROM doc_file_folder
WHERE owner_id = :userId
  AND id NOT IN (SELECT folder_id FROM doc_recycle WHERE user_id = :currentUserId)
ORDER BY update_at DESC
LIMIT :limit OFFSET :offset;
```

**`scope=shared`**：命中 USER/GROUP ACL **且** `owner_id != :userId`
```sql
SELECT DISTINCT mpf.folder_id AS id, mpf.parent_id, mpf.name, mpf.format,
                mpf.owner_id, dff.status, dff.update_at,
                mpf.permission AS my_permission,
                mpf.principal_type, mpf.principal_id  -- 告诉前端"权限来自谁"
FROM my_permitted_folders mpf
INNER JOIN doc_file_folder dff ON dff.id = mpf.folder_id
WHERE mpf.owner_id != :userId
  AND mpf.principal_type IN ('USER', 'GROUP')  -- 排除 ORG
  AND dff.id NOT IN (SELECT folder_id FROM doc_recycle WHERE user_id = :currentUserId)
ORDER BY dff.update_at DESC
LIMIT :limit OFFSET :offset;
```

**`scope=public`**：命中 ORG ACL **且** 不属于 mine/shared
```sql
SELECT mpf.folder_id AS id, mpf.parent_id, mpf.name, mpf.format,
       mpf.owner_id, dff.status, dff.update_at, 'READ' AS my_permission  -- ORG 永远只授 READ
FROM my_permitted_folders mpf
INNER JOIN doc_file_folder dff ON dff.id = mpf.folder_id
WHERE mpf.principal_type = 'ORG'
  AND mpf.owner_id != :userId  -- 排除 mine
  AND mpf.folder_id NOT IN (
    -- 排除"因为 USER/GROUP ACL 也命中"的情况
    SELECT folder_id FROM my_permitted_folders
    WHERE principal_type IN ('USER', 'GROUP')
  )
  AND dff.id NOT IN (SELECT folder_id FROM doc_recycle WHERE user_id = :currentUserId)
ORDER BY dff.update_at DESC
LIMIT :limit OFFSET :offset;
```

**`scope=all`**：UNION 上述三段；按 `update_at DESC` 统一排序，**用 `folder_id` 去重**（同一资源可能同时命中多个 scope）
```sql
-- v0.8 修：用 ROW_NUMBER() 取最高优先级源（OWNER > USER > GROUP > ORG）
-- + 补 update_at 列（ranked CTE 内部每段 SELECT 必须带）+ 加 doc_recycle NOT IN 过滤
-- 不再用 GROUP BY id（MySQL 5.7+ / 9.3.0 默认 ONLY_FULL_GROUP_BY 会直接拒绝非聚合列）
WITH ranked AS (
  SELECT id, parent_id, name, format, owner_id, status, update_at,
         my_permission, principal_type, principal_id,
         ROW_NUMBER() OVER (
           PARTITION BY id
           ORDER BY FIELD(principal_type, 'OWNER', 'USER', 'GROUP', 'ORG'),
                    FIELD(my_permission, 'MANAGE', 'WRITE', 'READ')
         ) AS rn
  FROM (
    -- mine
    SELECT id, parent_id, name, format, owner_id, status, update_at,
           'MANAGE' AS my_permission, 'OWNER' AS principal_type, :userId AS principal_id
    FROM doc_file_folder WHERE owner_id = :userId
    UNION ALL
    -- shared
    SELECT mpf.folder_id AS id, mpf.parent_id, mpf.name, mpf.format, mpf.owner_id, dff.status, dff.update_at,
           mpf.permission, mpf.principal_type, mpf.principal_id
    FROM my_permitted_folders mpf
    INNER JOIN doc_file_folder dff ON dff.id = mpf.folder_id
    WHERE mpf.owner_id != :userId
      AND mpf.principal_type IN ('USER', 'GROUP')
    UNION ALL
    -- public
    SELECT mpf.folder_id AS id, mpf.parent_id, mpf.name, mpf.format, mpf.owner_id, dff.status, dff.update_at,
           'READ', 'ORG', NULL
    FROM my_permitted_folders mpf
    INNER JOIN doc_file_folder dff ON dff.id = mpf.folder_id
    WHERE mpf.principal_type = 'ORG'
      AND mpf.owner_id != :userId
  ) AS combined
  WHERE id NOT IN (SELECT folder_id FROM doc_recycle WHERE user_id = :currentUserId)
)
SELECT id, parent_id, name, format, owner_id, status, update_at,
       my_permission, principal_type, principal_id
FROM ranked
WHERE rn = 1
ORDER BY update_at DESC
LIMIT :limit OFFSET :offset;
```

**索引要求**（V2 迁移后必须存在，否则走 full scan）：
- `doc_file_folder.idx_owner (owner_id)` —— `scope=mine` 命中
- `doc_file_folder_acl.idx_principal (principal_type, principal_id, revoked_at)` —— ACL 命中
- `doc_file_folder_acl.idx_folder (folder_id)` —— 反向 JOIN 命中
- `doc_file_folder.idx_status_update (status, update_at DESC)` —— 排序 + status 过滤

**响应体格式**（前端 `FolderCard` 消费）：
```json
{
  "id": 123,
  "name": "产品需求",
  "format": 1,
  "ownerId": 1001,
  "ownerName": "张三",
  "myPermission": "WRITE",     // 服务端按 scope 计算后填入
  "permissionSource": "USER",  // USER / GROUP / ORG / OWNER
  "principalId": 1002,         // 来自哪个 user/group；OWNER 时 = ownerId
  "isInherited": false,        // true = 来自父级继承
  "updateAt": "2026-06-06 10:00:00"
}
```

### 10.6 UI 权限边界（缺权时表现）

> **v0.7 修订**：与 §5.4.a / §5.4.b 对齐。**复制**与**移动**权限要求不同（前者源 READ，后者源 MANAGE），分两行写。

| 操作 | 触发点 | 权限要求 | 缺权 UI |
|---|---|---|---|
| 创建文件夹 | NameEditDialog "确定" | 总能（创建者 = Owner）| — |
| 分享/管 ACL | FolderCard "分享" 菜单 | MANAGE | 菜单项不显示 |
| 重命名 | FolderCard "重命名" | MANAGE | 菜单项不显示 |
| **复制**（源 → 目标）| FolderCard "复制" 菜单 | 源 READ + 目标父 MANAGE | 菜单项不显示（任一不满足）|
| **移动**（源 → 目标）| FolderCard "移动" 菜单 | 源 MANAGE + 目标父 MANAGE | 菜单项不显示（任一不满足）|
| 删除 | FolderCard "删除" | MANAGE | 菜单项不显示 |
| 软删 / 还原 | 列表行操作按钮 | MANAGE | 按钮 disable |
| 设为全员公开 | ShareDialog 全员 tab | MANAGE | tab 灰 |
| 转让 Owner | ShareDialog 底部 | Owner | 整段隐藏 |
| 访问他人资源 | GET 任意详情/列表 | 取决于有效权限 | 列表不显示 / 详情 404（屏蔽存在性） |

### 10.7 AuthorityAspect 复用（落实 MAJOR #8 / MAJOR #9 — OGNL 而非 SpEL）

> **落实 MAJOR #9**：v0.5 §10.7 用 `spelArgs` 表述错误。现有 `AuthorityAspect` 实际**基于 OGNL**（`Ognl.getValue`，位于 `kele-core/src/main/java/com/kele/core/other/aspect/authority/AuthorityAspect.java`），**不是** SpEL。
> 字段命名 `spelArgs` 是历史遗留（注解字段名沿用旧仓库命名），**不**代表底层是 SpEL 引擎。
> 本设计**不替换引擎**（OGNL 是项目既有栈），只修正**表述**和**语义中性字段名建议**。

复用现有 `com.kele.core.other.aspect.authority.Authority` + `AuthorityAspect`，不新建 `@RequireFolderPermission`：

**注解字段（语义中性重命名建议，不强制）**：
- 现存字段 `spelArgs` —— 历史命名，**误导性**（实际是 OGNL 而非 SpEL）
- **建议**：新增一个字段 `expressionArgs` 兼容老调用；老 `spelArgs` 保留作 `@Deprecated` 别名（带 Javadoc 说明迁移到 `expressionArgs`）
- 实施期可以一步到位直接重命名（项目内部 lib，外部消费者 = 0），但需同步更新所有 `@Authority` 调用点（grep 一下，全项目应该 < 20 处）

**controller 用法示例**（用新字段名）：
```java
@PostMapping("/{id}/acl")
@Authority(
  expressionArgs = "#folderId",   // 旧 spelArgs，新代码请用 expressionArgs
  methodName   = "requireManage",
  beanName     = "permissionService"
)
public ResponseVO grantAcl(@PathVariable Long folderId, @RequestBody GrantAclReq req) {
  return aclAO.grant(folderId, req);
}
```

**底层 OGNL 表达式求值**（v0.7 修复后）：
- `AuthorityAspect` 用 `Ognl.getValue(ognlExpr, contextMap)` **直接**求值（2-arg；mybatis-ognl 的 `Ognl.getValue` 没有 standalone OGNL 的 `(String, Map, Object)` 这种 context+root 双参重载，只有 `(String, Object)` / `(String, Object, Class)`）
- 调用方写 `expressionArgs = "#folderId"`，切面**自动剥 `#` 前缀**成 `folderId`（OGNL 在 root=Map 上走 property access，等价 `context.get("folderId")`，能拿到真实的 `Long` / `String` / VO 类型）
- **v0.7 回归记录**：之前误用 `OnglUtils.evaluate(...)` —— 那是 `${...}` 模板引擎（regex 只匹配 `${...}` 格式，对裸 `#folderId` 不识别），返回空串 → `findMethod` 拿 null 抛 NPE（`AuthorityAspect.java:85`）。2026-06-07 修复，切到 `Ognl.getValue` 直接求值
- 求值结果（如 `Long` folderId）传给 `beanName.methodName(...)` 反射调用 → 拿到 `PermissionResult` → 与要求的 permission 等级比较

**权限等级比较**：
- 注解 `methodName` 决定要求哪个方法（"requireManage" → 返回 `PermissionLevel` 或抛 `PERMISSION_DENIED`）
- 由 `PermissionService` 内 `requireManage(folderId)` 自行检查 ≥ MANAGE；不通过抛 `PERMISSION_DENIED(403)`
- **不引入** `OWNER_REQUIRED` 错误码（会泄露资源存在性 —— 响应码差异攻击；落实 MAJOR #9 闭环）

**为什么用 OGNL 而不换 SpEL**：
- OGNL 是项目既有栈（`kele-core` 已有 `OnglUtils` 工具类）
- 替换引擎牵涉 `@Authority` 全部调用点 + 测试 + 文档 —— 收益 = 0（项目无需要 SpEL 高级特性）
- OGNL 2.6.9 → SpEL 迁移属"非业务技术债"，建议留 follow-up 不在本设计范围

---

## 11. 不在本设计范围

- 通知中心（撤销/转让通知）
- 操作审计日志
- 外链共享（企业场景默认不做）
- 时间限制的临时授权
- 消息中心 / 邮件提醒
- 多租户（预留位，本期不实现）
