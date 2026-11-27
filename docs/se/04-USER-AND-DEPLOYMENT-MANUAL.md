# 用户操作手册与部署运维指南

**项目名称**：FixCampus · 校园报修管理平台  
**文档版本**：v1.0（对齐当前 Web/PWA + Flyway `V1～V6` 实现基线）  
**编制日期**：2026-11-26  
**相关文档**：[返回 README](../../README.md) · [Web/PWA 演示指南](../portfolio/WEB-DEMO.md) · [本地运行指南](../technical/LOCAL-RUN.md) · [系统概要与详细设计说明书](02-SYSTEM-AND-DATABASE-DESIGN.md)

---

## 第一部分：最终用户操作手册

打开浏览器访问应用地址（本机演示默认 `http://127.0.0.1:5173/app/`；访问根路径 `/` 会自动跳转至 `/app/`）。系统根据登录账号的服务端角色自动进入对应工作台。

### 1. 学生端操作指引（示例账号：`student01`）

1. **登录与查看我的报修**：输入学生账号密码登录，首页展示本人提交的全部报修单，支持按状态筛选、关键词搜索与翻页。
2. **提交新报修**：
   - 点击工作区导航「提交报修」（`/app/new`）。
   - 填写**报修地点**（1～120 字）、选择**报修分类**（水电设施、门窗家具、网络、空调、其他）、填写**问题描述**（1～2000 字），点击「提交报修／恢复原提交」。
   - 若提交时遇到网络中断、响应未知或 `503` / `429`，请勿刷新丢弃表单：页面会自动保留原内容与幂等提交键，待网络恢复并按提示点击「重新确认身份／CSRF」（若出现恢复条）后，再手动点击「提交报修／恢复原提交」，避免重复建单。
3. **上传/管理故障图片附件**：
   - 在工单详情页的「图片附件」区域选择 `PNG` 或 `JPEG` 图片上传（单图 ≤ 2 MiB，单工单学生最多 3 张；允许在「待分配」或「待维修」状态上传）。
   - 点击缩略图可放大预览（按 `Esc` 关闭并恢复焦点）；在「待分配」状态下可删除本人上传的附件。
4. **撤销待分配工单**：若管理员尚未派单，可在工单详情页点击「撤销报修」并二次确认，状态变为「已取消」。
5. **查看站内通知与确认维修完成**：
   - 工作区导航「站内通知」会以红点角标显示未读数（页面可见且联网时每 30 秒自动刷新）。
   - 进入 `/app/notifications` 可按「仅看未读」筛选、逐条标为已读或点击「全部标为已读」，并直接点击工单链接进入详情。
   - 维修人员提交结果后（状态为「待确认」），核对维修说明与完工照片，点击「确认维修完成」即完成整条闭环。

### 2. 管理员端操作指引（示例账号：`admin01`）

1. **查看全量工单与派单**：
   - 登录后默认看到全校所有学生的报修单，可按状态、关键词、排序检索。
   - 打开「待分配」工单详情，查阅学生提交的文字与故障图片，在派单区选择有效维修人员并点击「派单」，状态转为「待维修」。
2. **查看统计概览（`/app/stats`）**：
   - 点击工作区导航「统计概览」，可切换「近 7 天」「近 14 天」「近 30 天」趋势窗口或手动刷新。
   - 页面展示：全量工单概览卡片、两类独立耗时（「提交 → 维修员完工」与「提交 → 学生确认」，若样本为 0 显示「暂无有效样本」）、6 状态占比分布、5 分类占比分布、按日趋势图与明细表。
3. **站内通知**：学生新提交报修或撤销工单时，管理员工作区导航会收到未读通知提示。

### 3. 维修人员端操作指引（示例账号：`worker01`）

1. **接收派单通知与查看待办**：登录后工作区导航显示新派单未读通知，列表页仅展示已指派给本人的工单。
2. **开始维修**：打开「待维修」工单详情，查看地点、故障描述与学生上传的现场图片，点击「开始维修」，状态转为「维修中」。
3. **上传完工照片与提交结果**：
   - 在「维修中」状态可上传最多 2 张 `PNG` / `JPEG` 完工照片（单图 ≤ 2 MiB）。
   - 填写维修处理说明（选填，≤ 1000 字），点击「提交维修结果」，状态转为「待确认」，等待学生最终确认。

### 4. PWA 安装与异常处理说明

- **安装桌面应用壳**：在支持 PWA 的浏览器（如 Edge / Chrome）打开生产构建预览页，可通过地址栏安装图标将 FixCampus 添加为独立应用窗口。断网时仍可打开应用壳界面并看到离线提示，真实工单读写需恢复联网。
- **遇到错误页时**：页面会展示可读中文提示及可一键复制的 `X-Request-Id` 请求编号，方便对照后端日志定位问题。
- **多账号切换**：每次切换角色请先点击「退出」；或为三个角色分别使用无痕窗口/独立浏览器配置文件，避免同浏览器共享 Cookie 会话。

---

## 第二部分：部署与运维配置指南

### 5. 运行环境要求

| 组件 | 推荐基线版本 | 说明 |
| --- | --- | --- |
| JDK | Java 25 LTS | 后端编译与运行（Maven Wrapper 自动下载 Maven 3.9.16） |
| Node.js / npm | Node 24.x / npm 11.x | 前端依赖安装、检查与 Vite 构建 |
| MySQL | MySQL 8.4.11（InnoDB） | 建议使用独立回环实例（如 `127.0.0.1:13306`），DML 与迁移账号分离 |
| 操作系统与终端 | Windows PowerShell（已实测）；Linux/macOS 兼容核心 Java/Node 流程 | 干净目录（无 `.git`）可直接运行 |

### 6. 快速部署步骤（干净源码目录）

1. **准备隔离 MySQL 空库与分离账号**：参照 [Web/PWA 演示指南](../portfolio/WEB-DEMO.md#1-准备隔离数据库) 创建 `fixcampus_m421_*_test` 空库与最小权限 DML/DDL 账号。
2. **准备本地忽略配置**：在 `.local/` 中配置数据库连接文件与演示账号密码（切勿将真实密码写入 Git 跟踪文件）。
3. **启动后端**：执行 `.\scripts\run-web-slice.ps1 -ConfigPath .local/portfolio-db.json -DemoConfigPath .local/portfolio-accounts.json`，等待日志出现 `FixCampus MySQL connection check passed (SELECT 1).` 与 `FixCampus demo accounts initialized`。
4. **构建并启动前端预览**：进入 `frontend/` 依次执行以下三条独立命令（兼容 Windows PowerShell 5.1 与 7+）：
   ```powershell
   npm ci --ignore-scripts --no-audit --no-fund
   npm run build
   npm run preview:web
   ```
5. **验收访问**：打开 `http://127.0.0.1:5173/app/`。详细传统 SSR 路径部署步骤见 [本地运行指南](../technical/LOCAL-RUN.md)。

### 7. 完整环境变量配置字典

#### 7.1 后端环境变量（[application.yml](../../src/main/resources/application.yml) 与 [application-web-slice.yml](../../src/main/resources/application-web-slice.yml)）

| 环境变量 | 默认值 | 适用 Profile | 说明 |
| --- | --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | （空，传统 SSR） | 全局 | Web/REST 实机演示设为 `web-slice`；演示/生产部署对应 `demo` / `prod` |
| `FIXCAMPUS_DB_HOST` / `FIXCAMPUS_DB_PORT` | `127.0.0.1` / `3306` | 全局 | MySQL 主机与端口 |
| `FIXCAMPUS_DB_NAME` | `fixcampus_dev`（`demo`/`prod` 无默认） | 全局 | 目标数据库名（`web-slice` 强制匹配 `fixcampus_m421_[a-z0-9_]+_test`） |
| `FIXCAMPUS_DB_USERNAME` / `FIXCAMPUS_DB_PASSWORD` | 必填 | 全局 | 应用运行期 DML 账号（仅授予 `SELECT/INSERT/UPDATE/DELETE`） |
| `FIXCAMPUS_MIGRATION_USERNAME` / `FIXCAMPUS_MIGRATION_PASSWORD` | 启用 Flyway 时必填 | `web-slice`/`demo`/`prod` | Flyway 迁移专用 DDL 账号 |
| `FIXCAMPUS_PORT` | `8080`（`web-slice` 为 `18080`） | 全局 | 后端监听端口（绑定 `127.0.0.1`） |
| `FIXCAMPUS_WEB_ORIGIN` | 必填 | `web-slice`/`demo`/`prod` | 允许的同源前端 Origin（如 `http://127.0.0.1:5173`） |
| `FIXCAMPUS_COOKIE_SECURE` | `true`（回环脚本覆盖为 `false`） | `web-slice` | HTTP 回环演示设 `false`，HTTPS 部署保持 `true` |
| `FIXCAMPUS_AUTH_REGISTRATION_ENABLED` | SSR 为 `true`；Web/演示/生产为 `false` | 全局 | 是否开放自助注册路由 |
| `FIXCAMPUS_ATTACHMENT_STORAGE_DIR` | `${java.io.tmpdir}/fixcampus-attachments/${FIXCAMPUS_DB_NAME}` | 全局 | 托管图片附件根目录（按库名隔离并写入数据库绑定标记） |
| `FIXCAMPUS_DEMO_ENABLED` | `false` | 全局 | 是否启用三角色预置演示账号初始化 |
| `FIXCAMPUS_DEMO_ADMIN_PASSWORD` / `FIXCAMPUS_DEMO_WORKER_PASSWORD` / `FIXCAMPUS_DEMO_STUDENT_PASSWORD` | 空 | 启用演示时必填 | 三个预置演示账号初始密码（8～64 字符，外部注入） |
| `FIXCAMPUS_DEMO_RESET_ENABLED` | `false` | 需同时启用演示 | 双重开关之二：是否启用定时重置演示库（默认关闭） |
| `FIXCAMPUS_DEMO_RESET_CRON` | `0 0 4 * * *` | 启用重置时 | 演示库重置 Cron 表达式（时区 `Asia/Hong_Kong`） |

#### 7.2 前端构建期环境变量（[demoConfig.ts](../../frontend/src/app/demoConfig.ts)）

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `VITE_DEMO_MODE` | 仅 `--mode demo` 或 `'true'` 时开启 | 控制登录页是否展示演示账号名与重置提示 |
| `VITE_DEMO_RESET_NOTICE` | 内置中文清空提示 | 覆盖演示模式下的定期重置说明文本（不含密码） |
| `VITE_DEMO_ACCESS_HINT` | 空 | 演示模式下可选公开访问指引文本（严禁写入密码） |

### 8. 常见运维故障排查

- **启动报 `Cannot verify isolated migration target`**：确认目标 MySQL 端口已启动、库名符合 `fixcampus_m421_*_test`、迁移账号权限正确且非空库未被非 Flyway 脚本建表。
- **页面 `403 ORIGIN_REJECTED` 或无法登录**：核对 `FIXCAMPUS_WEB_ORIGIN` 与浏览器访问地址完全一致（主机与端口均匹配），且前端使用 `preview:web` / `dev:web` 同源代理。
- **附件上传报 `400` / `409` / `413`**：确认文件确为 PNG/JPEG（非改后缀文件）、单图 ≤ 2 MiB、宽高 ≤ 4096、总像素 ≤ 16M 且未超单单张数/64 MiB 配额。
- **操作返回 `429` 或 `503`**：`429` 为限流保护，按 `Retry-After` 等待后重试；`503` 多为 3 秒行锁超时或数据库瞬时异常，请复制 `X-Request-Id` 检索后端日志并先刷新工单状态再决定后续操作。
