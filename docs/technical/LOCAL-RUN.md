# FixCampus 本地运行指南

FixCampus 是独立完成的个人课程实践项目。适用于当前源码，不要求已有课程演示环境。命令从项目根目录执行，密码和数据库由使用者在本地设置；本指南不提供共享默认凭据。

## 环境

- JDK 25，确认 `java`、`javac` 可用；IDEA 项目及 Maven 使用同一 JDK 主版本。
- MySQL 8.4.11，与 CI 基线一致；准备 MySQL 客户端 `mysql`。
- Git（可选，用于克隆和维护源码）。
- Windows 的完整便利脚本使用 PowerShell；Maven 发行版由 Wrapper 下载为 3.9.16，首次构建需要网络。Wrapper 实现版本是 3.3.4。

## Windows：建立本机配置

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

## 初始化、测试、启动

```powershell
./scripts/initialize-database.ps1 -AdminConfigPath .local/mysql-admin.ini
./scripts/verify-local.ps1
./scripts/run-local.ps1 -SkipBuild
```

实际行为：

1. 初始化只接受本机、`fixcampus_` 前缀数据库，开发库与以 `_test` 结尾的测试库必须不同；默认测试库为 `fixcampus_test`。准备 `users`、`repair_orders`、`order_events`，严格顺序为 [001](../../database/001-users.sql) → [002](../../database/002-repair-orders.sql) → [004](../../database/004-order-events.sql)。
2. 管理员为已创建应用账号授予两库所需 SELECT/INSERT/UPDATE/DELETE；脚本处理 MySQL `partial_revokes` 与库名下划线，避免通配授权。管理员配置不会作为应用配置传给 Spring Boot。
3. 自动生成被忽略的 `.local/database-test.json`；已有文件保留，因此重用环境时必须核对其实际库名、账号和权限。测试会创建并清理夹具，禁止指向重要用户数据或与其他测试并发共享该库。
4. verify-local 执行 Wrapper 的 `--batch-mode clean verify`，用独立真实 MySQL 测试并生成 JAR、Surefire 和 JaCoCo；不跳过测试。run-local 切回开发配置后启动 JAR。去掉 `-SkipBuild` 会先验证构建，再启动。

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

配置为测试库后运行 `./mvnw --batch-mode clean verify`；确认通过后切换为开发库配置，再运行 `java -jar target/fixcampus-0.0.1-SNAPSHOT.jar`。可复用 CI 的一次性数据库准备设计，但临时容器无密码 root 约定不能照搬到共享或实际数据库。CI 的 Ubuntu 路径有真实运行依据，见 [CI 工作流](../../.github/workflows/ci.yml)。

## 既有环境与故障排查

- 从旧两表版本升级：先核对并备份自己的数据，使用 `scripts/update-order-history.ps1 -ConfigPath .local/database.json -AdminConfigPath .local/mysql-admin.ini` 显式新增事件表；旧工单不补造历史。该[脚本](../../scripts/update-order-history.ps1)只处理 ConfigPath 指定库，不会自动升级测试库；测试库需要再以 `.local/database-test.json` 作为 ConfigPath 显式执行，或按初始化指南核对并准备三表后再测试。
- 页面打不开：分别确认数据库、Java 服务及端口；只有 MySQL 就绪不够。
- 配置或表结构错误：检查库名、账号授权和初始化顺序，不切换 root 或改成 ddl-auto=update 绕过。
- 测试安全检查拒绝：实际 schema 必须以 fixcampus_ 开头、以 _test 结尾；不要放宽检查。
- 多角色登录：同一浏览器配置会共享会话，使用独立配置或隔离上下文。
- 报告路径：`target/surefire-reports/`、`target/site/jacoco/index.html`、`jacoco.xml`、`jacoco.csv`。构建输出会被 clean 清理，需留证时先另存副本。

源码快照不含私有 Git 历史。本机凭据应继续被 .gitignore 排除，不能提交真实密码、日志或业务数据。
