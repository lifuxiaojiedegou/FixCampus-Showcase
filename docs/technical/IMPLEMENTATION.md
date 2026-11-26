# FixCampus 实现与测试索引

本文档按模块索引 FixCampus 2.0（Web/PWA + REST `/api/v1` + Flyway `V1～V6`）的核心源码与对应自动化测试，便于快速定位代码实现。

## 核心模块与源码入口

- **三角色流转与持久化幂等**：[StudentOrders](../../src/main/java/com/fixcampus/api/v1/StudentOrders.java) 与 [OperationalOrders](../../src/main/java/com/fixcampus/api/v1/OperationalOrders.java) 负责分页与列表查询，[RepairOrderService](../../src/main/java/com/fixcampus/service/RepairOrderService.java) 负责悲观行锁（`@Lock(PESSIMISTIC_WRITE)`）下的状态流转与事件写入；创建工单在同事务记录 `idempotency_requests`，并发冲突回滚后回查已提交记录，同内容重放原结果、不同内容返回 `409`。
- **认证、鉴权、限流与请求追踪**：[ApiSecurityConfig](../../src/main/java/com/fixcampus/api/v1/ApiSecurityConfig.java) 实现 Session 轮换、CSRF 与同源 `Origin` 校验，跨角色路径返回 `403`，越权对象统一返回 `404`；[ApiRateLimitFilter](../../src/main/java/com/fixcampus/api/v1/ApiRateLimitFilter.java) 保护登录及业务写路由（`429`）；[ApiRequestIdFilter](../../src/main/java/com/fixcampus/api/v1/ApiRequestIdFilter.java) 贯通 `X-Request-Id` 与脱敏 MDC 日志；[ApiErrors](../../src/main/java/com/fixcampus/api/v1/ApiErrors.java) 统一映射业务冲突（`409`）与锁超时/数据库故障（`503`）。
- **管理员统计聚合**：[AdminStatisticsApi](../../src/main/java/com/fixcampus/api/v1/AdminStatisticsApi.java) 与 [AdminStatisticsService](../../src/main/java/com/fixcampus/service/AdminStatisticsService.java) 在只读事务内用 SQL 聚合全量状态分布、分类分布、7/14/30 天按日趋势以及「提交 → 维修完工」「提交 → 学生确认」两类独立耗时。
- **工单图片附件**：[OrderAttachmentsApi](../../src/main/java/com/fixcampus/api/v1/OrderAttachmentsApi.java)、[OrderAttachments](../../src/main/java/com/fixcampus/api/v1/OrderAttachments.java)、[AttachmentImages](../../src/main/java/com/fixcampus/service/AttachmentImages.java) 与 [AttachmentStorage](../../src/main/java/com/fixcampus/service/AttachmentStorage.java) 基于纯 JDK `ImageIO` 校验 PNG/JPEG 魔数与宽高/总像素上限，解码后剥离 EXIF 重编码落盘，结合写串行化与行锁管控配额，并在事务回调中清理磁盘文件。
- **同事务站内通知**：[NotificationsApi](../../src/main/java/com/fixcampus/api/v1/NotificationsApi.java) 与 [NotificationService](../../src/main/java/com/fixcampus/service/NotificationService.java) 通过 `@Transactional(MANDATORY)` 与工单变更共享事务，基于确定性幂等键与 `ON DUPLICATE KEY UPDATE id = id` 去重，跨账号访问返回 `404`。
- **演示环境重置与输入校验**：[DemoResetService](../../src/main/java/com/fixcampus/service/DemoResetService.java) 在双重显式开关下原子重置示例库并清理托管附件；[CodePointLengthValidator](../../src/main/java/com/fixcampus/validation/CodePointLengthValidator.java) 按 Unicode 码点统一校验长度。

## 关键场景测试索引

| 关注点 | 代表性测试类 |
| --- | --- |
| 会话轮换、CSRF、Origin 与三角色权限隔离 | [WebStudentSliceIntegrationTest](../../src/test/java/com/fixcampus/WebStudentSliceIntegrationTest.java) · [OrderOperationsApiIntegrationTest](../../src/test/java/com/fixcampus/OrderOperationsApiIntegrationTest.java) · [UserAuthenticationIntegrationTest](../../src/test/java/com/fixcampus/UserAuthenticationIntegrationTest.java) |
| 6 线程同键创建幂等、同键异参 `409` 与回滚 | [WebStudentSliceIntegrationTest](../../src/test/java/com/fixcampus/WebStudentSliceIntegrationTest.java) |
| 并发派单/撤销竞争、3 秒行锁超时 `503` 与回滚 | [OrderOperationsApiIntegrationTest](../../src/test/java/com/fixcampus/OrderOperationsApiIntegrationTest.java) · [AdminOrderIntegrationTest](../../src/test/java/com/fixcampus/AdminOrderIntegrationTest.java) · [OrderHistoryIntegrationTest](../../src/test/java/com/fixcampus/OrderHistoryIntegrationTest.java) |
| 登录/写路由限流、禁用注册与请求追踪脱敏 | [PublicDemoApiTest](../../src/test/java/com/fixcampus/api/v1/PublicDemoApiTest.java) · [ApiRequestIdTest](../../src/test/java/com/fixcampus/api/v1/ApiRequestIdTest.java) |
| 图片魔数/尺寸/去 EXIF、全局配额与事务文件清理 | [AttachmentImagesTest](../../src/test/java/com/fixcampus/service/AttachmentImagesTest.java) · [AttachmentStorageTest](../../src/test/java/com/fixcampus/service/AttachmentStorageTest.java) · [OrderAttachmentsApiTest](../../src/test/java/com/fixcampus/api/v1/OrderAttachmentsApiTest.java) · [OrderAttachmentIntegrationTest](../../src/test/java/com/fixcampus/OrderAttachmentIntegrationTest.java) |
| 站内通知同事务回滚、幂等去重与跨账号 404 | [NotificationServiceTest](../../src/test/java/com/fixcampus/service/NotificationServiceTest.java) · [NotificationsApiTest](../../src/test/java/com/fixcampus/api/v1/NotificationsApiTest.java) · [NotificationIntegrationTest](../../src/test/java/com/fixcampus/NotificationIntegrationTest.java) |
| 管理员统计聚合、两类耗时与空样本边界 | [AdminStatisticsServiceTest](../../src/test/java/com/fixcampus/service/AdminStatisticsServiceTest.java) · [AdminStatisticsApiTest](../../src/test/java/com/fixcampus/api/v1/AdminStatisticsApiTest.java) · [AdminStatisticsIntegrationTest](../../src/test/java/com/fixcampus/AdminStatisticsIntegrationTest.java) |
| 演示库原子重置与 Flyway `V1～V5 → V6` 增量升级 | [DemoResetIntegrationTest](../../src/test/java/com/fixcampus/DemoResetIntegrationTest.java) · [RealMySqlTestSupport](../../src/test/java/com/fixcampus/RealMySqlTestSupport.java) |
| 前端交互、跨标签切号隔离、未知结果恢复与禁外网 | [RealStudentApp.test.tsx](../../frontend/src/test/RealStudentApp.test.tsx) · [RealThreeRoleApp.test.tsx](../../frontend/src/test/RealThreeRoleApp.test.tsx) · [RealOrderAttachments.test.tsx](../../frontend/src/test/RealOrderAttachments.test.tsx) · [RealNotifications.test.tsx](../../frontend/src/test/RealNotifications.test.tsx) · [RealAdminStats.test.tsx](../../frontend/src/test/RealAdminStats.test.tsx) · [noNetwork.test.ts](../../frontend/src/test/noNetwork.test.ts) |

## 持续集成

[CI 工作流](../../.github/workflows/ci.yml) 在 Ubuntu 24.04 上并行执行：

- **Maven / MySQL**：JDK 25 + 独立 MySQL 8.4.11 容器运行 `./mvnw --batch-mode clean verify`，校验并上传 Surefire 测试报告与 JaCoCo 覆盖率产物。
- **Frontend / Checks**：Node 24 运行 `npm run check`（TypeScript、ESLint 0 warnings、Prettier、Vitest、静态禁外网校验与生产构建）。

10 万条工单深分页性能对照与复现脚本见 [性能基准](../benchmark.md)。
