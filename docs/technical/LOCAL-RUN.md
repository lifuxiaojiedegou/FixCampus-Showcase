# FixCampus 本地运行指南

## 路径选择总览（2026-11-26）

本仓库支持三类独立的本地运行/验证路径，**请勿将「传统 SSR 三表库初始化」与「全量 Maven 后端验证」混在同一测试库上顺序执行**：

1. **Web / PWA 三角色演示启动（推荐）**：从本仓库或零 `.git` 干净导出目录启动真实三角色 React 工作台与 `/api/v1` 后端，请直接按 [Web/PWA 演示指南](../portfolio/WEB-DEMO.md) 执行 `scripts/run-web-slice.ps1` 与 `frontend/` 预览命令。
2. **全量 Maven 后端验证（含 `web-slice` Flyway `V1～V6` 集成测试）**：须先单独准备空隔离测试库（库名匹配 `fixcampus_m421_[a-z0-9_]+_test`）及分离的应用 DML / 迁移 DDL 账号，见下文「全量 Maven 测试前置准备」。
3. **传统 SSR 运行路径**：仅用于启动旧三表 SSR 页面（`/login`），见下文「传统 SSR 运行说明」。

## 环境

- JDK 25，确认 `java`、`javac` 可用；IDEA 项目及 Maven 使用同一 JDK 主版本。
- MySQL 8.4.11，与 CI 基线一致；准备 MySQL 客户端 `mysql`。
- Git（可选，用于克隆和维护源码）。
- Windows 的完整便利脚本使用 PowerShell；Maven 发行版由 Wrapper 下载为 3.9.16，首次构建需要网络。Wrapper 实现版本是 3.3.4。

## 全量 Maven 测试前置准备（含 `web-slice` 集成测试）

完整全栈测试（`scripts/verify-local.ps1` 或 `./mvnw --batch-mode clean verify`）会加载 `web-slice` 集成测试，要求对独立空 schema 以分离的迁移/DML 账号完成 Flyway `V1～V6`（`users`、`repair_orders`、`order_events`、`idempotency_requests`、`order_attachments`、`notifications`），再由 Hibernate 校验。**传统 SSR 三表初始化脚本（`001`/`002`/`004`）建出的非空、未受 Flyway 管理的 schema 会被 [IsolatedMigrationConfig](../../src/main/java/com/fixcampus/config/IsolatedMigrationConfig.java) 拒绝，不能直接用于全量 Maven 测试。**

配置入口为 [application-web-slice.yml](../../src/main/resources/application-web-slice.yml) 和 [IsolatedMigrationConfig](../../src/main/java/com/fixcampus/config/IsolatedMigrationConfig.java)。运行前按以下四步单独准备：

1. **创建空隔离测试库与分离账号**：在独立本机 MySQL 8.4.11 中创建空库（库名须符合 `fixcampus_m421_[a-z0-9_]+_test`，例如 `fixcampus_m421_local_test`，禁止预先导入 `001`/`002`/`004` 手工 SQL），并分别创建应用 DML 账号（仅授 `SELECT, INSERT, UPDATE, DELETE`）与迁移 DDL 账号（授 `SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES`）。具体授权 SQL 可参考 [Web/PWA 演示指南](../portfolio/WEB-DEMO.md) 第 1 节或 [CI 工作流](../../.github/workflows/ci.yml)。已有 Flyway 管理的同一测试库可继续迁移。
2. **准备独立测试库配置文件**：复制 `config/database.example.json` 为 `.local/database-web-test.json`（由 `.gitignore` 忽略），填入 `127.0.0.1`、端口、空测试库名（如 `fixcampus_m421_local_test`）以及应用 DML 账号和密码。
3. **在测试终端设置 `web-slice` 附加环境变量**：除 `FIXCAMPUS_DB_*`（由 `verify-local.ps1` 从 `-ConfigPath` 读取）外，在当前测试终端显式设置迁移账号与运行配置（不写入仓库，不打印密码）：
   - `FIXCAMPUS_MIGRATION_USERNAME` / `FIXCAMPUS_MIGRATION_PASSWORD`（迁移 DDL 账号凭据）
   - `SPRING_PROFILES_ACTIVE=web-slice`
   - `FIXCAMPUS_WEB_ORIGIN=http://127.0.0.1:5173`
   - `FIXCAMPUS_COOKIE_SECURE=false`（仅本机回环测试）
   - 可选：`FIXCAMPUS_ATTACHMENT_STORAGE_DIR`（默认存入系统临时目录下按库名隔离的子目录）；演示库定时重置（`FIXCAMPUS_DEMO_RESET_ENABLED` / `FIXCAMPUS_DEMO_RESET_CRON`）默认关闭。
4. **执行全量 Maven 验证**：
   ```powershell
   ./scripts/verify-local.ps1 -ConfigPath .local/database-web-test.json
   ```
   `verify-local.ps1` 强制 `FIXCAMPUS_DEMO_ENABLED=false`，校验库名符合 `fixcampus_*_test` 后执行 Wrapper 的 `--batch-mode clean verify`，在真实隔离 MySQL 上运行全部单元与集成测试，并生成可执行 JAR、Surefire 与 JaCoCo 报告（不跳过测试）。

## 传统 SSR 运行说明

FixCampus 是独立完成的个人课程实践项目。下文保留传统三表 SSR 路径（默认 Profile）的本地启动方式；命令从项目根目录执行，密码和数据库由使用者在本地设置，本指南不提供共享默认凭据。

### Windows：建立传统 SSR 本机配置

确认 MySQL 已启动。MySQL 与 Java 应用是两个进程，启动数据库不会启动网站。

```powershell
New-Item -ItemType Directory -Path .local -Force
Copy-Item config/database.example.json .local/database.json
Copy-Item config/mysql-admin.example.ini .local/mysql-admin.ini
```

在 `.local/database.json` 填入本机 host、port、开发库名（如 `fixcampus_dev`）、应用账号和自行生成的密码。在 `.local/mysql-admin.ini` 填写仅用于初始化的管理员连接信息；两种身份不要混用。这些文件由原有 `.gitignore` 排除，不能提交或截图分享。

使用 MySQL 管理员在客户端创建应用账号；下面只是新环境的占位示例，必须替换密码。已有同名账号时先核对归属，不覆盖密码或盲目重建：

```sql
CREATE USER 'fixcampus_app'@'localhost'
    IDENTIFIED BY 'REPLACE_WITH_YOUR_LOCAL_APP_PASSWORD';
```

应用账号须与 `.local/database.json` 一致。库和表由下一步初始化脚本创建；不要给应用 root 或全局管理权限。

### 初始化传统三表库与启动 SSR

```powershell
./scripts/initialize-database.ps1 -AdminConfigPath .local/mysql-admin.ini
```

若尚未生成 `target/fixcampus-0.0.1-SNAPSHOT.jar`，先按上一节「全量 Maven 测试前置准备」在独立 `web-slice` 测试库上运行 `./scripts/verify-local.ps1 -ConfigPath .local/database-web-test.json` 完成构建与验证。随后打开**新的独立终端**（确保未设置 `SPRING_PROFILES_ACTIVE=web-slice`，避免 `web-slice` 隔离迁移规则拒绝传统三表 `fixcampus_dev` 库），启动 SSR：

```powershell
./scripts/run-local.ps1 -SkipBuild
```

实际行为：

1. `initialize-database.ps1` 只接受本机、`fixcampus_` 前缀数据库，开发库与以 `_test` 结尾的测试库必须不同；默认测试库为 `fixcampus_test`。脚本按严格顺序 [001](../../database/001-users.sql) → [002](../../database/002-repair-orders.sql) → [004](../../database/004-order-events.sql) 准备传统三表（`users`、`repair_orders`、`order_events`）。
2. 管理员为已创建应用账号授予两库所需 `SELECT`/`INSERT`/`UPDATE`/`DELETE`；脚本处理 MySQL `partial_revokes` 与库名下划线，避免通配授权。管理员配置不会作为应用配置传给 Spring Boot。
3. 脚本生成的 `.local/database-test.json` 仅对应传统三表结构，**不要**将其直接用于包含 `web-slice` 迁移测试的全量 `verify-local.ps1`；全量测试请使用上一节单独准备的 `fixcampus_m421_*_test` 空库与迁移账号。
4. `run-local.ps1 -SkipBuild` 加载 `.local/database.json` 后直接启动已验证生成的 JAR。**请务必保持分步执行**（先在 `web-slice` 测试终端完成 `verify-local.ps1`，再在未设置 `SPRING_PROFILES_ACTIVE` 的新终端执行 `run-local.ps1 -SkipBuild`）；不要在设置了 `SPRING_PROFILES_ACTIVE=web-slice` 的终端里省略 `-SkipBuild`，否则验证结束后脚本虽会切回开发库配置，但仍会以 `web-slice` Profile 启动传统三表库并触发隔离迁移检查拒绝。

MySQL 客户端不在 PATH 时，在初始化命令添加 `-MySqlExecutable '<你的mysql可执行文件>'`。不要将真实密码放在命令行或 URL 中。

访问 [http://localhost:8080/login](http://localhost:8080/login)。日志应包含应用已启动及数据库 `SELECT 1` 检查通过。默认只监听 `127.0.0.1`；端口占用时停止对应旧实例，或使用 `run-local.ps1 -SkipBuild -Port 8081`。Ctrl+C 停止本次应用。

`ddl-auto=validate`、`spring.sql.init.mode=never` 保持不变：启动只验证已存在结构，不会自动建表或修复旧结构。003 演示数据不自动导入，重复 CREATE TABLE 也不等于数据库迁移。

## 可选的三角色演示

默认演示开关为 false，可在注册页创建学生。需要管理员/维修人员演示时：

```powershell
Copy-Item config/demo-accounts.example.json .local/demo-accounts.json
```

在该本地文件把 enabled 设为 JSON 布尔值 true，填写三个独立、8～64 字符的演示密码，再通过 run-local 启动。虚构账号为 `admin01`、`worker01`、`student01`，姓名分别为演示管理员、演示维修员、演示学生，联系方式为空。仓库不保存它们的实际密码。

初始化保留已有同名同角色账号；修改本地密码字段不会重置已存在密码。角色冲突会停止，不覆盖用户数据。完成演示后可关闭开关。运行测试时已有脚本会强制关闭演示初始化。

手动提交几张虚构报修即可演示完整闭环。可选的 [003-demo-orders.sql](../../database/003-demo-orders.sql) 和 prepare-demo-data 脚本仅用于显式选择的独立演示库，不导入测试库或开发库，也不属于 CI schema 初始化。

## IDEA 与其他系统

Windows 可运行 `./scripts/configure-idea.ps1`，再选择生成的 FixCampus Local 配置；该配置含本机连接信息，必须保持在被忽略的 `.idea` 路径中。直接点启动类的绿色三角不会自动执行 PowerShell 的 JSON 加载逻辑；手动运行时需要自己配置环境变量。

Linux/macOS 可使用同一 Wrapper 与 Java 应用；本轮没有重新演练这些本机平台的完整初始化流程，不将 Windows 脚本当作 Bash 使用。先由管理员按相同 SQL 顺序显式准备开发库、独立测试库及非 root、按库限定的 DML 授权，再从本机安全配置向当前进程注入：

| 变量 | 含义 |
| --- | --- |
| FIXCAMPUS_DB_HOST / FIXCAMPUS_DB_PORT | 本机 MySQL 地址，默认 127.0.0.1 / 3306 |
| FIXCAMPUS_DB_NAME | 构建测试时为 `fixcampus_*_test`；运行应用时切回开发库 |
| FIXCAMPUS_DB_USERNAME / FIXCAMPUS_DB_PASSWORD | 专用应用账号及本机密码，均必填 |
| FIXCAMPUS_DEMO_ENABLED | 默认 false；测试保持 false |
| FIXCAMPUS_PORT | 默认 8080 |

当前完整测试按本文 M4 前置条件准备环境。传统脚本的配置文件只负责自身读取的字段，迁移账号与 profile 等附加输入须在调用前配置。完成相应测试环境准备后运行 `./mvnw --batch-mode clean verify`；确认通过后，在独立终端加载传统 SSR 开发库配置并使用默认 SSR profile，再运行 `java -jar target/fixcampus-0.0.1-SNAPSHOT.jar`。可复用 CI 的一次性数据库准备设计，但临时容器无密码 root 约定不能照搬到共享或实际数据库。CI 的 Ubuntu 路径有真实运行依据，见 [CI 工作流](../../.github/workflows/ci.yml)。

## 既有环境与故障排查

- 从旧两表版本升级：先核对并备份自己的数据，使用 `scripts/update-order-history.ps1 -ConfigPath .local/database.json -AdminConfigPath .local/mysql-admin.ini` 显式新增事件表；旧工单不补造历史。该[脚本](../../scripts/update-order-history.ps1)只处理 ConfigPath 指定库，不会自动升级测试库；测试库需要再以 `.local/database-test.json` 作为 ConfigPath 显式执行，或按初始化指南核对并准备三表后再测试。
- 页面打不开：分别确认数据库、Java 服务及端口；只有 MySQL 就绪不够。
- 配置或表结构错误：检查库名、账号授权和初始化顺序，不切换 root 或改成 ddl-auto=update 绕过。
- 测试安全检查拒绝：实际 schema 必须以 fixcampus_ 开头、以 _test 结尾；不要放宽检查。
- 多角色登录：同一浏览器配置会共享会话，使用独立配置或隔离上下文。
- 报告路径：`target/surefire-reports/`、`target/site/jacoco/index.html`、`jacoco.xml`、`jacoco.csv`。构建输出会被 clean 清理，需留证时先另存副本。

源码快照不含私有 Git 历史。本机凭据应继续被 .gitignore 排除，不能提交真实密码、日志或业务数据。
