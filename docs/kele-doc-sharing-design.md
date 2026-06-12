# kele-doc 共享/权限/群组 — 设计方案 & 实施计划 & 冒烟清单

> 日期：2026-06-06（最后更新 2026-06-12）
> 版本：v0.13
> 状态：已实施（后端完成，前端基本完成）
> 项目：kele-doc（原 lx-doc-java）

本文档合并了以下三个历史文档：
- `superpowers/specs/2026-06-06-kele-doc-sharing-design.md`（设计方案 v0.12）
- `superpowers/plans/2026-06-06-kele-doc-sharing.md`（实施计划 34 tasks）
- `superpowers/plans/2026-06-06-kele-doc-sharing-smoketest.md`（冒烟测试清单）

---

## 0. 修订记录

### BLOCKER
- **B1**：isPublic 3 处活跃使用已全部重构为 ACL 子查询
- **B2**：Flyway 已加入（flyway-core），V1__init.sql 已包含所有表结构
- **B3**：SysUserInfo 已加 `role` 字段，登录流程已写入 UserInfoBO，LoginContext 有 `isAdmin()`
- **B5**：DocRecycle 已加 `folderId` 字段，id 含义改为自增 PK

### MAJOR
- **M4**：KeleGroup 实体 `@TableName("`group`")` 已加反引号 ✅
- **M5**：CTE 为 stub（`PermissionServiceCtePrimary` 抛 UnsupportedOperationException），生产走 app-layer walk ✅
- **M7**：copyFolder ACL 行为已设计（副本不继承源 ACL）✅
- **M8**：moveFolder 跨权限域已设计（双侧 MANAGE）✅
- **M9**：AuthorityAspect 确认是 OGNL，已加 `expressionArgs` 字段 ✅
- **M10**：DocRecycle userId = 回收操作人（非 Owner）✅

---

## 1. 背景与目标

### 1.1 现状（已完成迁移）
- 系统从单租户个人文档管理扩展为支持共享/群组/权限的协作系统
- `is_public` 字段已删除，其语义由 `doc_file_folder_acl` 表的 ORG ACL 行替代
- 所有 `creatorId` 硬编码校验已替换为 `PermissionService.resolve()` 调用

### 1.2 目标
- 三级权限（READ/WRITE/MANAGE）+ 群组 + 组织内公开
- 完全继承（子 = 父的权限，不可打破）
- 扁平群组（不嵌套），仅管理员可建

### 1.3 范围
- 后端：API + 数据模型 + 权限引擎
- 前端：ShareDialog + Pinia store 扩展 + API 客户端

---

## 2. 需求

| 维度 | 决策 |
|---|---|
| 场景 | 企业/团队协作 |
| 租户 | 单租户（预留 org_id 扩展位） |
| 群组 | 扁平群组，仅管理员可建 |
| 共享范围 | 指定用户 / 指定群组 / 组织内全员 |
| 权限级别 | 3 级：READ / WRITE / MANAGE |
| 级联策略 | 完全继承 |
| Owner | 创建者 = Owner；可转让；管理员可强制重设 |

### 2.1 三级权限含义

| 能力 | READ | WRITE | MANAGE |
|---|:-:|:-:|:-:|
| 查看 | ✓ | ✓ | ✓ |
| 编辑内容 | | ✓ | ✓ |
| 改结构（增删子项/改名/移动） | | | ✓ |
| 修改 ACL / 邀请 / 改角色 | | | ✓ |
| 分享 / 转让 | | | ✓ |

---

## 3. 数据模型（已实施）

### 3.1 核心表结构

**V1__init.sql** 是合并版全量建表脚本（合并了原 V1~V4），包含以下表：

```
doc_file_folder          — 加了 owner_id，无 is_public
doc_file_folder_acl      — 统一 ACL 表（含 active_marker 虚拟列）
`group`                  — 群组（保留字，必须反引号）
group_member             — 群组成员（复合主键）
doc_recycle              — 加了 folder_id / deleterId / ownerAtDeleteId
sys_user_info            — 加了 role 字段
```

### 3.2 实体关系

```
doc_file_folder (owner_id) ──1:*── doc_file_folder_acl (folder_id)
`group` ──1:*── group_member *:1── user
doc_recycle (folder_id + deleter_id) ── 多用户可软删同一 folder
```

---

## 4. API 设计（已实施）

### 4.1 群组管理（仅管理员）

```
POST   /api/admin/groups                        创建群组
GET    /api/admin/groups                        列表
GET    /api/admin/groups/{id}                   详情
PUT    /api/admin/groups/{id}                   更新
DELETE /api/admin/groups/{id}                   解散（soft: status=0）
POST   /api/admin/groups/{id}/restore           恢复
POST   /api/admin/groups/{id}/members           添加成员
DELETE /api/admin/groups/{id}/members/{userId}   移除成员
GET    /api/admin/groups/{id}/members           成员列表
GET    /api/admin/groups/my                     我的群组
```

### 4.2 ACL 管理

```
GET    /api/doc/folders/{id}/acl               本级显式授权
POST   /api/doc/folders/{id}/acl               批量授权
DELETE /api/doc/folders/{id}/acl/{aclId}       撤销单条
PUT    /api/doc/folders/{id}/acl/{aclId}       修改权限
PUT    /api/doc/folders/{id}/owner             转让 Owner
```

### 4.3 用户搜索

```
GET    /api/users/search?keyword=xxx&limit=20
```

### 4.4 错误码

```java
PERMISSION_DENIED(403)
RESOURCE_NOT_VISIBLE(404)    // 防探测：不知存在 + 无权统一 404
GROUP_NAME_DUPLICATE(409)
USER_ALREADY_IN_GROUP(409)
ACL_DUPLICATE(409)
CANNOT_REMOVE_LAST_MANAGE(409)
GROUP_HAS_MEMBERS(409)
GROUP_NOT_FOUND(404)
GROUP_DISMISSED(409)
USER_NOT_FOUND(404)
ORG_PUBLIC_PERMISSION_INVALID(400)
INVALID_ACL_PRINCIPAL_TYPE(400)
```

---

## 5. 权限校验引擎（已实施）

### 5.1 核心服务

`PermissionService.resolve(Long userId, Long folderId)` — app-layer walk 实现。

### 5.2 算法要点

- **permission-first**：先比 permission level（MANAGE > WRITE > READ），再比 source rank（OWNER > DIRECT > GROUP > INHERITED）
- OWNER 隐式 MANAGE 不可被任何 ACL 覆盖
- 沿祖先链向上走，不早退
- 管理员旁路：`LoginContext.isAdmin()` 仅用于管理态操作（清回收站、解散群组、强制重设 Owner）

### 5.3 CTE 主路径

`PermissionServiceCtePrimary` 是 stub，`resolve()` 抛 `UnsupportedOperationException`。
当前配置 `kele.doc.permission.engine=app-walk` 走 fallback。CTE 留待性能优化时实现。

---

## 6. 数据迁移

仅有一个 migration 文件：`V1__init.sql`（全量建表+种子数据）。所有新表（ACL、group、group_member）和字段变更（owner_id、role、folder_id）都在此文件中。无增量 V2 文件。

---

## 7. 回收站 / 软删（已实施）

**核心规则**：软删 = 写一行 `doc_recycle` 标记，物理行 `status` **保持 1** 不变。

| 操作 | 物理影响 | 视图变化 |
|---|---|---|
| 软删 | INSERT doc_recycle | 操作人视图里看不到 |
| 还原 | DELETE doc_recycle 行 | 操作人视图恢复 |
| 永久删除 | 硬删 doc_file_folder + acl + doc_recycle | 所有人视图消失 |
| 清空回收站 | emptyRecycle 批量硬删 | 所有人视图消失 |

DocRecycle 双视角字段：
- `deleterId` — 回收操作人
- `ownerAtDeleteId` — 删除时的 Owner

---

## 8. 代码变更清单（已实施）

### 8.1 后端关键改动

| 文件 | 变更 |
|---|---|
| `PermissionService.java` | 新建；resolve + requireRead/Write/Manage |
| `PermissionServiceCtePrimary.java` | 新建；CTE stub（未实现） |
| `PermissionLevel.java` | 新建；枚举 NONE/READ/WRITE/MANAGE |
| `PermissionResult.java` | 新建；含 Source 枚举 + sourceNodeId |
| `DocFileFolderAcl.java` | 新建；ACL 实体 |
| `KeleGroup.java` | 新建；`@TableName("`group`")` 有反引号 |
| `GroupMember.java` | 新建；复合主键（无 @TableId） |
| `AclAOImpl.java` | 新建；ACL CRUD + transferOwner（CAS） |
| `AclController.java` | 新建；/api/doc/folders/{id}/acl |
| `GroupAOImpl.java` | 新建；群组 CRUD + dissolve 级联 revoke ACL + restore 精确复活 |
| `GroupController.java` | 新建；/api/admin/groups |
| `DocFileFolder.java` | 加 ownerId + isRoot()；删 isPublic |
| `DocRecycle.java` | 加 folderId / deleterId / ownerAtDeleteId |
| `SysUserInfo.java` | 加 role 字段 |
| `LoginContext.java` | 加 isAdmin() + userGroupIds |
| `Authority.java` | 加 expressionArgs（旧 spelArgs 保留 @Deprecated） |
| `AuthorityAspect.java` | OGNL 求值，优先 expressionArgs fallback spelArgs |
| `DocFileFolderAOImpl.java` | 删 creatorId 过滤 → ACL 子查询；deleteFolder 不再 setStatus(DEL)；createFolder setOwnerId |
| `DocFileContentAOImpl.java` | createFile setOwnerId；getFileBaseInfo 加 requireRead |
| `DocRecycleAOImpl.java` | getId → getFolderId；emptyRecycle 批量硬删 |
| `DocCollectFolderAOImpl.java` | 删 creatorId → owner + ACL 可见性 |
| `SysUserInfoController.java` | 加 /api/users/search |
| `ErrorCodeEnum.java` | 加 12 个新错误码 |

### 8.2 前端关键改动

| 文件 | 变更 |
|---|---|
| `workbench/src/api/index.js` | 加 ACL/群组/搜索共 10+ API 方法 |
| `workbench/src/store.js` | 加 folderPermissionMap + myGroups + loadFolderPermission |
| `workbench/src/components/ShareDialog.vue` | 新建；分享对话框完整实现 |

---

## 9. 前端关键页面

### 9.1 ShareDialog.vue（实际路径：`components/ShareDialog.vue`）

三段布局：
1. **Owner 行**（只读）
2. **当前 ACL 列表**（每行可改权限/撤销）
3. **添加新授权**（用户 tab / 群组 tab / 全员 tab）

底部 Owner 操作：转让所有权（仅 Owner 可见）

### 9.2 Pinia store 新增

```js
state: {
  folderPermissionMap: {},  // folderId -> { level, source, isOwner }
  myGroups: null,           // 群组数组（通过 loadMyGroups 加载）
}
```

### 9.3 API 客户端

已实现方法：
- `getFolderAcl` / `grantFolderAcl` / `revokeFolderAcl` / `updateFolderAcl` / `transferOwner`
- `searchUsers` / `listMyGroups`
- 群组管理全套（listGroups / createGroup / dissolveGroup / restoreGroup 等）

---

## 10. 已知差异（文档 vs 实现）

> 以下为设计方案与当前代码的对比差异。

| # | 设计文档描述 | 实际实现 | 状态 |
|---|---|---|---|
| 1 | `KeleDocApplicationRunner.java` 启动校验 id=1 admin | **已补建**。yml 有 `admin-check.enabled=true`，Runner 消费该配置 | ✅ 已修复 |
| 2 | V2__sharing.sql 增量迁移 | **只有 V1__init.sql**（全量合并版） | 设计如此，老库升级走手动脚本 |
| 3 | ShareDialog 路径 `pages/workspace/components/content/` | **实际在 `components/ShareDialog.vue`** | ✅ 文档已更正 |
| 4 | store `currentFolderAcl` + `myGroupIds` | **实际用 `folderPermissionMap` + `myGroups`** | ✅ 文档已更正 |
| 5 | DocFileFolderBaseResVO 残留 isPublic | **已删除** | ✅ 已修复 |
| 6 | CTE 主路径 | **PermissionServiceCtePrimary 是 stub**，抛 UnsupportedOperationException | 设计如此，app-walk 可用 |
| 7 | `@TableLogic` on DocFileFolderAcl.revokedAt | **无 @TableLogic**，用显式 `revoked_at IS NULL` 查询 | 设计如此，显式查询更可控 |
| 8 | Flyway 版本锁定 8.5.13 | **pom.xml 有 flyway-core 依赖**（版本由 BOM 或 properties 管理） | 需确认版本是否为 8.x |
| 9 | Authority 注解用于所有鉴权 | **v0.13 #15 已迁移到 AO 层 inline requireXxx**，Authority 注解标记 @Deprecated | ✅ 文档已更正 |
| 10 | FolderCard 分享菜单未按权限过滤 | **已修复**：非 Owner 且已接收的文件夹不显示「分享」菜单 | ✅ 已修复 |
| 9 | Authority 注解用于所有鉴权 | **v0.13 #15 已迁移到 AO 层 inline requireXxx**，Authority 注解标记 @Deprecated | 鉴权逻辑不变，切面方式被直接调用替代 |

---

## 11. 冒烟测试清单

### 11.1 编译环境

```bash
# 必须用 JDK 8（Lombok 1.18.20 不兼容 JDK 25）
export JAVA_HOME="D:/SDKs/JDK/jdk1.8.0_202"
mvn -B clean compile
```

### 11.2 端到端冒烟

| # | 步骤 | 期望 |
|---|------|------|
| 1 | 注册 userA / userB / userC，登录取 cookie | 200 |
| 2 | userA 创建 folder F1 | 200；F1.ownerId = userA.id |
| 3 | userA 分享 F1 给 userB (READ) | 200 |
| 4 | userB 查看 F1 ACL | 返 ownerId=userA |
| 5 | userB 查看 F1 文件列表 | 可见 |
| 6 | userB 尝试在 F1 下创建文件 | 403 |
| 7 | userA 升 userB 到 WRITE | 200 |
| 8 | userB 再次创建文件 | 200 |
| 9 | userA 撤销 userB | 200 |
| 10 | userB 再查看 F1 | 404（防探测） |
| 11 | userA 转让 F1 给 userC | 200 |
| 12 | userA 再操作 ACL | 403（已非 owner） |
| 13 | userC（admin）创建群组 team-x | 200 |
| 14 | userC 把 userA/userB 加入群组 | 200 |
| 15 | userA 查看我的群组 | 含 team-x |
| 16 | userA 搜索 userB | 返回结果 |
| 17 | userA 分享 F1 给 team-x (READ) | 200 |
| 18 | userB 通过群组继承 READ | 200 |
| 19 | userC 解散群组 | 200；ACL 级联撤销 |
| 20 | userC 设 F1 为 ORG 公开 READ | 200 |
| 21 | 任何登录用户都能 read F1 | 200 |

### 11.3 前端冒烟

| # | 步骤 | 期望 |
|---|------|------|
| 1 | workbench `npm run dev` 启动 | 监听 8080 |
| 2 | 登录 userC，进入工作台 | UI 正常 |
| 3 | F1 上点 ⋮ 菜单，看「分享」选项 | 出现 |
| 4 | 点「分享」，弹 ShareDialog | 弹出 |
| 5 | 搜索用户，选权限，点添加 | 列表出现新条目 |
| 6 | 改权限为 MANAGE | 后端 200；UI 刷新 |

### 11.4 遗留风险

| 编号 | 项 | 处理建议 |
|------|-----|---------|
| R-1 | ~~KeleDocApplicationRunner 缺失~~ | ✅ 已补建 |
| R-2 | CTE stub 未实现 | 当前 app-walk 模式可用，大数据量场景需实现 CTE |
| R-3 | 无增量 V2 迁移脚本 | 老库升级需手动写迁移 SQL |
| R-4 | ~~FolderCard 分享菜单未按权限过滤~~ | ✅ 已修复 |

---

## 12. 不在本设计范围

- 通知中心（撤销/转让通知）
- 操作审计日志
- 外链共享
- 时间限制的临时授权
- 多租户
