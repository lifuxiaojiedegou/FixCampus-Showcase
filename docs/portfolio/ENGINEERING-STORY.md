# FixCampus · 工程设计与取舍说明

本文说明 FixCampus（**React 19 / TypeScript / Vite Web/PWA + Spring Boot 3.5 + MySQL 8.4 + Flyway `V1～V6`**）在权限隔离、并发事务、附件处理、统计口径与深分页上的核心设计与架构取舍。

[返回 README](../../README.md) · [Web/PWA 演示](WEB-DEMO.md) · [系统架构与状态图](SYSTEM-DIAGRAMS.md) · [实现与测试依据](../technical/IMPLEMENTATION.md)

## 业务问题与系统边界

一张报修单需要跨三个角色协作：学生提交（可附现场照片），管理员查看统计并派给有效维修人员，维修人员开始处理、上传维修照片并提交结果，最后由报修学生收到通知后确认完成。系统将这一流程显式编码为六个状态：

```text
待分配 ─管理员派单→ 待维修 ─维修开始→ 维修中 ─提交结果→ 待确认 ─学生确认→ 已完成
   └────────────学生撤销────────────→ 已取消
```

- 学生只能撤销本人仍处于待分配状态的工单；学生待分配/待维修阶段可传图（待分配可删本人附件），维修员仅在维修中可传图。
- “维修人员提交结果”不等于“学生确认完成”；只有后者会写入 `completed_at`。
- 已完成和已取消是终态。当前不支持改派和返修，流程中没有被省略的额外状态。

依据：[RepairStatus](../../src/main/java/com/fixcampus/entity/RepairStatus.java) · [RepairOrderService](../../src/main/java/com/fixcampus/service/RepairOrderService.java)。

## 设计一：角色授权、对象级隔离与演示加固

**问题：** 如果只在前端隐藏其他角色的按钮，或只校验 URL 角色前缀，攻击者仍可替换工单、附件或通知 ID 访问或修改他人数据；公开演示场景还面临暴力登录、高频刷单、恶意注册和演示账号被改密的风险。

**当前实现：**

1. **认证与入口防护**：[ApiSecurityConfig](../../src/main/java/com/fixcampus/api/v1/ApiSecurityConfig.java) 基于服务端 Servlet Session、`HttpOnly` / `SameSite=Lax` Cookie、CSRF 令牌与同源 `Origin` 校验保护 `/api/v1/**`；[ApiRateLimitFilter](../../src/main/java/com/fixcampus/api/v1/ApiRateLimitFilter.java) 挂载于 Origin、CSRF 与角色授权检查之后，对 `POST /api/v1/auth/login` 及显式列出的业务写路由（建单、状态流转、附件上传/删除、通知已读；不含 `POST /api/v1/auth/logout`）执行进程内限流，超限返回 `429 RATE_LIMITED` + `Retry-After` + `X-Request-Id`。
2. **明确区分 403 与 404**：跨角色访问路由前缀（如学生访问 `/api/v1/admin/**` 或 `/api/v1/worker/**`）由安全层返回 `403 ROLE_FORBIDDEN`；同角色越权访问他人工单、附件或通知，由查询/业务层按当前用户 ID 过滤并统一返回 `404`（`ORDER_NOT_FOUND` / `ATTACHMENT_NOT_FOUND` / `NOTIFICATION_NOT_FOUND`），不泄露对象存在性。
3. **演示与生产加固**：`web-slice` / `demo` / `prod` Profile 默认关闭注册（`fixcampus.auth.registration-enabled=false`）且不暴露改密接口；[DemoResetService](../../src/main/java/com/fixcampus/service/DemoResetService.java) 与 [DemoResetScheduler](../../src/main/java/com/fixcampus/config/DemoResetScheduler.java) 采用双重显式开关（Fail-Closed），仅在同时开启演示与重置开关时才注册定时任务，在**数据库单事务**内按外键顺序原子重置示例工单、事件、附件元数据与通知（保留预置三角色账号和密码哈希），并在事务提交后回调清理托管目录中的附件文件（清理失败时记录日志并入队补偿）。

依据：[ApiSecurityConfig](../../src/main/java/com/fixcampus/api/v1/ApiSecurityConfig.java) · [ApiRateLimitFilter](../../src/main/java/com/fixcampus/api/v1/ApiRateLimitFilter.java) · [DemoResetService](../../src/main/java/com/fixcampus/service/DemoResetService.java) · [RepairOrderRepository](../../src/main/java/com/fixcampus/repository/RepairOrderRepository.java)。

## 设计二：并发状态机、持久化幂等与同事务通知

**问题：** 并发派单、撤销与派单竞争、网络超时后重复提交创建请求、状态变更后通知漏发或重复发，都会破坏业务一致性。

**当前实现：**

1. **悲观行锁与短锁超时**：所有状态变更在 `@Transactional` 内通过 `@Lock(PESSIMISTIC_WRITE)` 锁定目标 `repair_orders` 行后再核对归属与前置状态；连接会话设置 `innodb_lock_wait_timeout = 3`，锁等待超时完整回滚并返回 `503 TEMPORARILY_UNAVAILABLE`，正常获取锁后的非法流转继续返回 `409 ORDER_CONFLICT`。
2. **创建报修持久化幂等**：[StudentOrders](../../src/main/java/com/fixcampus/api/v1/StudentOrders.java) 将工单、`SUBMITTED` 事件与 `idempotency_requests` 写入同一事务；并发同键冲突回滚后回查已提交记录，同内容返回原工单，同键不同内容返回 `409`，前端保留原表单与 `Idempotency-Key` 供用户安全恢复提交。
3. **事件与站内通知同事务落地**：[NotificationService](../../src/main/java/com/fixcampus/service/NotificationService.java) 使用 `@Transactional(propagation = Propagation.MANDATORY)` 强制与工单写操作共享同一事务，通过确定性幂等键 `order:{orderId}:{eventType}:recipient:{recipientId}` 与 `ON DUPLICATE KEY UPDATE id = id` 去重，同时保留外键和 `CHECK` 约束严格报错。

依据：[RepairOrderService](../../src/main/java/com/fixcampus/service/RepairOrderService.java) · [StudentOrders](../../src/main/java/com/fixcampus/api/v1/StudentOrders.java) · [NotificationService](../../src/main/java/com/fixcampus/service/NotificationService.java) · [V4 幂等迁移](../../src/main/resources/db/migration/V4__create_idempotency.sql) · [V6 通知迁移](../../src/main/resources/db/migration/V6__create_notifications.sql)。

## 设计三：安全图片附件、统计口径与深分页优化

1. **图片附件零依赖校验与净化**：[AttachmentImages](../../src/main/java/com/fixcampus/service/AttachmentImages.java) 与 [AttachmentStorage](../../src/main/java/com/fixcampus/service/AttachmentStorage.java) 仅用 JDK 25 `ImageIO` 校验 PNG/JPEG 魔数，解码前用 `ImageReader` 预检宽高（≤4096）与总像素（≤16M）防解压炸弹，完整解码后剥离 EXIF 重编码并落盘至随机 `storage_key`；单工单张数与全局总量配额同时由进程内写串行化与工单悲观行锁共同保护，事务回滚或删除附件时通过事务同步回调清理磁盘文件（若删除失败则记录日志并入队补偿）；响应附带 `nosniff`、`sandbox` CSP 与 `private, no-store`。
2. **诚实的管理员统计口径**：[AdminStatisticsService](../../src/main/java/com/fixcampus/service/AdminStatisticsService.java) 在只读事务内用 SQL 完成聚合，严格区分「提交 → 维修员完工」与「提交 → 学生确认」两种耗时（按微秒差值换算分钟保留 1 位小数，空样本返回 `null` 而非 `0`，绝不补造缺失事件并披露 `missingEventCount`），对自由文本 `location` 明确声明暂不做启发式区域拆分。
3. **10 万条工单深分页优化**：单机基准定位了 `OFFSET` 深分页回表搬运开销，改为按索引先取目标页主键 ID、再按主键回表详情，P95 从 123.02 ms 降至 39.88 ms，完整基线与复现脚本见 [性能基准](../benchmark.md)。

## 如何验证这些设计

- **真实 MySQL 集成测试**：覆盖三角色完整闭环（`SystemWorkflowIntegrationTest`、`OrderOperationsApiIntegrationTest`）、六线程同键创建幂等（`WebStudentSliceIntegrationTest`）、并发派单/撤销与锁超时（`AdminOrderIntegrationTest`）、回滚与事件唯一性（`OrderHistoryIntegrationTest`）、演示原子重置（`DemoResetIntegrationTest`）、管理员统计快照与缺失事件（`AdminStatisticsIntegrationTest`）、图片附件校验/配额/跨角色权限（`OrderAttachmentIntegrationTest`）、站内通知同事务/回滚/跨账号隔离（`NotificationIntegrationTest`）以及 Flyway `V1～V5 → V6` 增量升级校验（`RealMySqlTestSupport`）。
- **前端自动化检查**：TypeScript 类型检查、ESLint（`--max-warnings=0`）、Prettier、静态禁外网校验（`check:no-network`）、生产与演示双模式构建，以及 Vitest 覆盖三角色页面、附件上传、通知轮询/已读竞态、统计展示与未知结果恢复。
- **CI**：推送 `main` 与 PR 均触发 GitHub Actions，以 JDK 25、Maven Wrapper、独立 MySQL 8.4.11 容器运行后端全量 `clean verify` 并收集 Surefire 与 JaCoCo 结构化报告，同时独立运行前端 `npm run check`。

所有自动化测试与 JaCoCo 覆盖率报告均由本仓库 GitHub Actions 独立生成，可在 Actions 页面公开复核。

测试依据：[OrderOperationsApiIntegrationTest](../../src/test/java/com/fixcampus/OrderOperationsApiIntegrationTest.java) · [AdminStatisticsIntegrationTest](../../src/test/java/com/fixcampus/AdminStatisticsIntegrationTest.java) · [OrderAttachmentIntegrationTest](../../src/test/java/com/fixcampus/OrderAttachmentIntegrationTest.java) · [NotificationIntegrationTest](../../src/test/java/com/fixcampus/NotificationIntegrationTest.java) · [CI 工作流](../../.github/workflows/ci.yml)。

## 当前取舍与已知边界

| 当前边界 | 对项目的影响或后续考虑 |
| --- | --- |
| 分层单体 + 本地磁盘附件 | 适合当前规模与单机演示/课程交付；多节点部署需改用共享对象存储与集中式限流 |
| 单向六状态机（每工单每动作唯一） | 不支持改派与返修；引入分支流转前须重新评估 `order_events` 唯一约束与通知幂等键 |
| 报修地点为自由文本 | 不做启发式区域聚合，统计接口如实返回 `supported: false`；需结构化楼栋字典后再扩展 |
| 最小 PWA 仅缓存应用壳 | 避免跨账号泄露业务数据；真实读写需联网 |
| 尚未公网部署、未做原生桌面/移动端 | 本阶段交付可复现的本机 Web/PWA 演示与清洁源码包；公网 HTTPS、Tauri 2 与 Capacitor 留待后续 |

**阅读建议：** 先看 [README](../../README.md)、[Web/PWA 演示](WEB-DEMO.md) 和 [架构图](SYSTEM-DIAGRAMS.md)，再从 API、Service、Flyway `V1～V6` DDL 与真实 MySQL 集成测试核对实现细节。
