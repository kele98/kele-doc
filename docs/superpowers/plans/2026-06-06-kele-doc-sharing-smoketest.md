# kele-doc 共享/权限/群组 — Final Smoke Test 清单

> 对应 spec: `docs/superpowers/specs/2026-06-06-kele-doc-sharing-design.md` v0.12
> 对应 plan:  `docs/superpowers/plans/2026-06-06-kele-doc-sharing-implementation.md`

---

## 0. 编译环境前置

**重要：** 本项目 `pom.xml` 锁定：

```xml
<maven.compiler.source>8</maven.compiler.source>
<maven.compiler.target>8</maven.compiler.target>
<lombok-version>1.18.20</lombok-version>
```

Lombok 1.18.20 **不兼容 JDK 25**（本机当前 `JAVA_HOME=D:\SDKs\JDK\jdk-25.0.3`）。编译时必须切到 **JDK 8** 或 **JDK 11**。

切环境变量（PowerShell）：

```powershell
$env:JAVA_HOME = "D:\SDKs\JDK\jdk-1.8"     # 或 jdk-11
$env:PATH       = "$env:JAVA_HOME\bin;$env:PATH"
java -version
# 应输出 1.8.x 或 11.x
```

或一次性指定（不污染环境变量）：

```bash
mvn -B -pl kele-core -am -DskipTests clean compile ^
   -Dmaven.compiler.fork=true ^
   -Dmaven.compiler.executable="$env:JAVA_HOME\bin\javac.exe"
```

---

## 1. 编译

```bash
# 1.1 全量清理
mvn -B clean

# 1.2 编译（不跑测试）
mvn -B -DskipTests compile

# 期望：BUILD SUCCESS；kele-common / kele-core 都产出 target/classes
ls kele-common/target/classes/com/kele/common/enums/ErrorCodeEnum.class
ls kele-core/target/classes/com/kele/core/buz/doc/permission/PermissionService.class
```

## 2. 单测 + 静态校验（不需要真实 DB）

```bash
mvn -B -pl kele-core test -Dtest='PermissionServiceTest,SharingE2EWorkflowTest,V2SharingMigrationTest'
```

期望：
- `PermissionServiceTest`：所有 resolve/requireXxx 用例通过
- `SharingE2EWorkflowTest`：所有共享流程 E2E 用例通过
- `V2SharingMigrationTest`：所有 11 条 step 标记齐全且字段存在

## 3. 启动后端（需要真实 MySQL 9.3.0）

### 3.1 准备 DB

```sql
CREATE DATABASE kele_doc DEFAULT CHARACTER SET utf8mb4;
CREATE USER 'kele'@'%' IDENTIFIED BY 'kele';
GRANT ALL ON kele_doc.* TO 'kele'@'%';
```

### 3.2 改 `application.yml`

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/kele_doc?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
    username: kele
    password: kele
```

### 3.3 启动

```bash
mvn -B -pl kele-core spring-boot:run
```

启动日志应可见：

```
Flyway Community Edition X.Y.Z by Redgate
Successfully validated 2 migrations (execution time 00:00.XXX)
Migrating schema kele_doc to version "2 - sharing"
Successfully applied 2 migrations to schema kele_doc
Started KeleDocApplication in X.XXX seconds
```

且 `KeleDocApplicationRunner` 应输出：

```
[admin-check] id=1 已为 ADMIN
```

### 3.4 表结构验证

```sql
-- 期望全部存在
SHOW TABLES LIKE 'doc_file_folder_acl';
SHOW TABLES LIKE 'group';
SHOW TABLES LIKE 'group_member';

-- 期望 sys_user_info.role 列存在
DESC sys_user_info;

-- 期望 doc_file_folder.owner_id NOT NULL
DESC doc_file_folder;

-- 期望 doc_recycle.folder_id NOT NULL + uk_folder_user
DESC doc_recycle;

-- 期望 uk_acl_active 唯一索引
SHOW INDEX FROM doc_file_folder_acl WHERE Key_name = 'uk_acl_active';
```

## 4. 端到端冒烟（按 spec §4.2 / §4.3）

### 4.1 注册 + 登录准备

| # | 步骤 | 期望 |
|---|------|------|
| 4.1.1 | `POST /api/register` 注册 userA / userB / userC（账号密码自定） | 200 |
| 4.1.2 | `POST /api/login` 依次登录三者，取 cookie `token` | 200 |

### 4.2 分享流（userA → userB）

| # | 步骤 | 期望 |
|---|------|------|
| 4.2.1 | userA 创建 folder F1 | 200；F1.ownerId = userA.id |
| 4.2.2 | userA `POST /api/doc/folders/{F1}/acl` body=`{entries:[{principalType:USER,principalId:userB.id,permission:READ}], replace:false}` | 200 |
| 4.2.3 | userB `GET /api/doc/folders/{F1}/acl` | 返 ownerId=userA；entries 含上述记录 |
| 4.2.4 | userB `GET /api/doc/folders/{F1}/getFolderAndFileList` | 可见 F1（READ 足够） |
| 4.2.5 | userB 尝试在 F1 下创建文件 | **403** PERMISSION_DENIED |
| 4.2.6 | userA 升 userB 权限到 WRITE（`PUT /api/doc/folders/{F1}/acl/{aclId}` body=`{permission:WRITE}`） | 200 |
| 4.2.7 | userB 再次创建文件 | 200 |
| 4.2.8 | userA 撤销 userB（`DELETE /api/doc/folders/{F1}/acl/{aclId}`） | 200 |
| 4.2.9 | userB 再 `GET /api/doc/folders/{F1}/getFolderAndFileList` | **404** RESOURCE_NOT_VISIBLE（防探测） |

### 4.3 转让所有权

| # | 步骤 | 期望 |
|---|------|------|
| 4.3.1 | userA `PUT /api/doc/folders/{F1}/owner` body=`{newOwnerId:userC.id}` | 200 |
| 4.3.2 | userA 再调一次 `updateFolderAcl` | **403**（已不是 owner） |
| 4.3.3 | userC 现在是 owner，三档权限全通 | 200 |

### 4.4 群组

| # | 步骤 | 期望 |
|---|------|------|
| 4.4.1 | userC（admin，role=ADMIN）`POST /api/admin/groups` body=`{name:'team-x', description:'for sharing'}` | 200；groupId=X |
| 4.4.2 | userC `POST /api/admin/groups/{X}/members` body=`{userIds:[userA.id, userB.id]}` | 200 |
| 4.4.3 | userA `GET /api/admin/groups/my` | 列表含 X |
| 4.4.4 | userA `GET /api/users/search?keyword=userB&limit=10` | 列表含 userB（不返敏感字段） |
| 4.4.5 | userA 分享 F1 给组 X：`POST /api/doc/folders/{F1}/acl` body=`{entries:[{principalType:GROUP,principalId:X,permission:READ}]}` | 200 |
| 4.4.6 | userA 分享 F1 给组 X 不可独立操作：`{principalType:GROUP,principalId:X,permission:READ}` 命中 uk_acl_active 防重 | 同上 200 |
| 4.4.7 | userB 重新 `requireRead(F1)` | 200（组成员继承） |
| 4.4.8 | userC `DELETE /api/admin/groups/{X}` | 200；该组相关 ACL 自动撤销 |

### 4.5 权限继承

| # | 步骤 | 期望 |
|---|------|------|
| 4.5.1 | userC 在 F1 下创建 F2 | 200 |
| 4.5.2 | userB `GET /api/doc/folders/{F2}/getFolderAndFileList` | 200（继承 F1 的 ORG/USER/GROUP ACL） |

### 4.6 ORG 公开

| # | 步骤 | 期望 |
|---|------|------|
| 4.6.1 | userC 分享 F1 为 ORG READ | 200 |
| 4.6.2 | 任何未登录用户登录后都能 read F1 | 200 |

### 4.7 前端冒烟（workbench）

| # | 步骤 | 期望 |
|---|------|------|
| 4.7.1 | `npm run dev`（workbench）启动前端 | 监听 :8080 |
| 4.7.2 | 浏览器登录 userC，进入工作台 | UI 正常 |
| 4.7.3 | 在 F1 上点 ⋮ 菜单，看「分享」选项 | 出现 |
| 4.7.4 | 点「分享」，弹 ShareDialog | 弹出 |
| 4.7.5 | 搜索 "userA"，选 READ，点添加 | 列表出现新条目 |
| 4.7.6 | 切到「管理现有」标签 | 表格列出条目 |
| 4.7.7 | 改权限为 MANAGE | 后端 200；UI 刷新 |
| 4.7.8 | 切到「转让所有权」标签 | 出现（因为 userC 是 owner） |
| 4.7.9 | 关掉对话框，userA 登录 | F1 可见、可写 |

## 5. 已知遗留 / 风险

| 编号 | 项 | 处理建议 |
|------|-----|---------|
| R-1 | Lombok 1.18.20 在 JDK 25 下不生成方法 | 编译前必须切到 JDK 8/11 |
| R-2 | PermissionServiceCtePrimary 是 stub，@Primary 跑会抛 UnsupportedOperationException | 留待 v0.13 写 CTE；当前 fallback 模式（`kele.doc.permission.fallback=true`）可用 |
| R-3 | `icon-fenxiang` iconfont 名称可能未在 iconfont.css 注册 | UI 测试时若图标不显示，替换为已存在的 icon |
| R-4 | V2 SQL Step 4 假设 `is_public` 列存在；schema 缺则该 INSERT 失败 | 实际项目 2026-06-06 状态：schema 无 is_public，按 spec 注释手动跳过该步 |
| R-5 | 单测需要 mockito-junit-jupiter；pom 已含 spring-boot-starter-test | OK |

## 6. 交付完成度

- [x] Tasks 1-33 全部完成
- [x] Task 34 本冒烟清单已交付
- [ ] **最终 BUILD + 真实 MySQL smoke 需在 JDK 8/11 环境由人工跑过本清单第 3、4 节**（当前会话受 JDK 25 + Lombok 1.18.20 冲突限制）

完成 Task 34 的 1-2 步（编译/单测/校验脚本）即可勾选。
