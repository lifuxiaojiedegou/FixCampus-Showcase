# FixCampus 实现与验证依据

## 当前 REST 与全栈实现依据（2026-11-26）

真实 API 与 React 19 工作台已完整覆盖三角色工单闭环、数据库深分页优化、创建持久化幂等、演示加固与限流、管理员统计、工单图片附件、站内通知与最小 PWA 应用壳：

- **三角色流转与持久化幂等**：[StudentOrders](../../src/main/java/com/fixcampus/api/v1/StudentOrders.java) 在失败事务回滚后处理唯一键冲突，同内容返回原工单、不同内容返回 `409`、结果暂无法确认时返回受控 `503`。验证入口：[WebStudentSliceIntegrationTest](../../src/test/java/com/fixcampus/WebStudentSliceIntegrationTest.java)（六线程同键提交只生成一单）、[OrderOperationsApiIntegrationTest](../../src/test/java/com/fixcampus/OrderOperationsApiIntegrationTest.java)（三角色操作与待分配撤销）。
- **演示加固、限流与请求追踪**：[ApiRequestIdFilter](../../src/main/java/com/fixcampus/api/v1/ApiRequestIdFilter.java) 贯通 `X-Request-Id` 与脱敏 MDC 日志；[ApiRateLimitFilter](../../src/main/java/com/fixcampus/api/v1/ApiRateLimitFilter.java) 挂载于 Origin、CSRF 与角色授权检查之后，对登录与显式业务写路由实施进程内限流（`429`，不含退出登录）；[ApiErrors](../../src/main/java/com/fixcampus/api/v1/ApiErrors.java) 统一业务冲突（`409`）与锁等待超时/数据访问异常（`503`）；[DemoResetService](../../src/main/java/com/fixcampus/service/DemoResetService.java) 实现双重显式开关的数据库单事务重置与提交后附件清理。验证入口：[ApiRequestIdTest](../../src/test/java/com/fixcampus/api/v1/ApiRequestIdTest.java)、[PublicDemoApiTest](../../src/test/java/com/fixcampus/api/v1/PublicDemoApiTest.java)、[DemoResetServiceTest](../../src/test/java/com/fixcampus/service/DemoResetServiceTest.java)、[DemoResetIntegrationTest](../../src/test/java/com/fixcampus/DemoResetIntegrationTest.java)。
- **管理员统计**：[AdminStatisticsApi](../../src/main/java/com/fixcampus/api/v1/AdminStatisticsApi.java) 与 [AdminStatisticsService](../../src/main/java/com/fixcampus/service/AdminStatisticsService.java) 在只读事务内用 SQL 聚合状态、分类、7/14/30 天按日趋势及两类耗时（诚实披露缺失事件与地点自由文本边界）。验证入口：[AdminStatisticsServiceTest](../../src/test/java/com/fixcampus/service/AdminStatisticsServiceTest.java)、[AdminStatisticsApiTest](../../src/test/java/com/fixcampus/api/v1/AdminStatisticsApiTest.java)、[AdminStatisticsIntegrationTest](../../src/test/java/com/fixcampus/AdminStatisticsIntegrationTest.java)。
- **工单图片附件**：[OrderAttachmentsApi](../../src/main/java/com/fixcampus/api/v1/OrderAttachmentsApi.java)、[OrderAttachments](../../src/main/java/com/fixcampus/api/v1/OrderAttachments.java)、[AttachmentImages](../../src/main/java/com/fixcampus/service/AttachmentImages.java) 与 [AttachmentStorage](../../src/main/java/com/fixcampus/service/AttachmentStorage.java) 实现无第三方依赖的 PNG/JPEG 魔数与解压边界预检、无 EXIF 重编码、进程内写串行化与行锁配额、事务回调磁盘清理（失败项记日志并入队补偿）及严格 403/404 隔离。验证入口：[AttachmentImagesTest](../../src/test/java/com/fixcampus/service/AttachmentImagesTest.java)、[AttachmentStorageTest](../../src/test/java/com/fixcampus/service/AttachmentStorageTest.java)、[OrderAttachmentsApiTest](../../src/test/java/com/fixcampus/api/v1/OrderAttachmentsApiTest.java)、[OrderAttachmentIntegrationTest](../../src/test/java/com/fixcampus/OrderAttachmentIntegrationTest.java)。
- **同事务站内通知**：[NotificationsApi](../../src/main/java/com/fixcampus/api/v1/NotificationsApi.java) 与 [NotificationService](../../src/main/java/com/fixcampus/service/NotificationService.java) 通过 `@Transactional(MANDATORY)` 与工单写操作同事务落库，使用确定性幂等键与 `ON DUPLICATE KEY UPDATE id = id` 去重，并在默认 SSR Profile 下按条件不装配以保持向后兼容。验证入口：[NotificationServiceTest](../../src/test/java/com/fixcampus/service/NotificationServiceTest.java)、[NotificationsApiTest](../../src/test/java/com/fixcampus/api/v1/NotificationsApiTest.java)、[RepairOrderServiceDefaultProfileTest](../../src/test/java/com/fixcampus/service/RepairOrderServiceDefaultProfileTest.java)、[NotificationIntegrationTest](../../src/test/java/com/fixcampus/NotificationIntegrationTest.java)。
- **数据库迁移与性能基准**：[RealMySqlTestSupport](../../src/test/java/com/fixcampus/RealMySqlTestSupport.java) 校验单隔离 schema 从 `V1～V5` 写入探针数据再升级至 `V6` 的无损兼容与约束生效；10 万条工单深分页基准见 [性能基准](../benchmark.md)。

实际测试通过数与覆盖率以对应候选版本和 CI 运行记录为准。

## 原 SSR 实现与历史验证依据

以下正文与 208 项指标记录原 SSR 版本，旧能力缺口在该历史入口和版本范围内解释。

FixCampus 是独立完成的个人课程实践项目。依据为当前生产源码、测试和已验收的 M2.0～M2.2 工程能力。本页帮助阅读代码，不声明生产规模、漏洞审计结论或 M2.3 门禁已经完成。

## 分层与数据库

浏览器提交表单后，Spring Security 负责身份、角色入口和 CSRF；Controller 白名单绑定表单，Service 校验角色、归属、状态并管理事务，Repository 执行范围查询与锁定读取，MySQL 维护数据库约束。Thymeleaf 输出视图 DTO；`open-in-view=false`，避免在模板渲染阶段意外延长数据访问。

分层及数据模型见[系统架构](ARCHITECTURE.md)，依据现有源码与三份建表 SQL 整理：

```mermaid
erDiagram
    users ||--o{ repair_orders : "报修学生或被派维修人员"
    repair_orders ||--o{ order_events : "处理记录"
    users ||--o{ order_events : "操作人"
```

图中合并表达 users 与工单的角色关系；实际 `student_id` 必填、`worker_id` 可空，是两个不同外键，具体基数和状态约束以 DDL 为准。

| 表 | 职责与主要约束 | 源码 |
| --- | --- | --- |
| users | 角色、用户名唯一、密码哈希、姓名与可选联系方式 | [001-users.sql](../../database/001-users.sql) · [User](../../src/main/java/com/fixcampus/entity/User.java) |
| repair_orders | 学生/维修人员外键，六状态、字段长度和状态字段一致性约束 | [002-repair-orders.sql](../../database/002-repair-orders.sql) · [RepairOrder](../../src/main/java/com/fixcampus/entity/RepairOrder.java) |
| order_events | 动作、时间、当次角色/姓名快照；每单每动作唯一，动作与角色匹配 | [004-order-events.sql](../../database/004-order-events.sql) · [OrderEvent](../../src/main/java/com/fixcampus/entity/OrderEvent.java) |

数据库外键不替代业务角色检查；事件记录没有应用层编辑/删除入口，但应用账号仍有 DML 权限，不能宣称数据库级不可篡改审计。当前唯一事件约束服务于不改派、不返修的单向状态机，扩展这些业务时需重新评估。

## 认证与数据归属

- [SecurityConfig](../../src/main/java/com/fixcampus/config/SecurityConfig.java) 对 `/student/**`、`/admin/**`、`/worker/**` 设置角色限制；表单登录使用服务端会话，登录变更 Session ID，退出清除会话；密码为带算法标识的 PBKDF2 哈希。
- [CampusUserDetailsService](../../src/main/java/com/fixcampus/security/CampusUserDetailsService.java) 规范化并校验用户名，[RoleLoginSuccessHandler](../../src/main/java/com/fixcampus/security/RoleLoginSuccessHandler.java) 根据数据库角色确定登录后入口，不接受客户端指定角色或跳转地址。
- [RepairOrderRepository](../../src/main/java/com/fixcampus/repository/RepairOrderRepository.java) 的学生/维修人员查询含身份范围；[RepairOrderService](../../src/main/java/com/fixcampus/service/RepairOrderService.java) 写入前再次核对角色和对象归属。本人范围内不存在或不可访问的工单按 404 处理，不能靠替换 URL 编号扩大范围。

验证入口：[UserAuthenticationIntegrationTest](../../src/test/java/com/fixcampus/UserAuthenticationIntegrationTest.java) 的 `roleLoginNormalizesUsernameIgnoresForgedRoleAndRejectsCrossRoleRequests`、`loginChangesSessionIdAndErasesCredentialHashFromSessionPrincipal`；[OrderSearchIntegrationTest](../../src/test/java/com/fixcampus/OrderSearchIntegrationTest.java) 的 `foreignIdsAndForgedIdentityParametersCannotExpandSearchOrCounts`。这些是已存在的方法，不代表穷尽所有授权组合。

## 输入与输出安全

[StudentController](../../src/main/java/com/fixcampus/controller/StudentController.java) 只绑定地点、分类和描述，系统字段从会话及服务器生成；普通注册在 [UserService](../../src/main/java/com/fixcampus/service/UserService.java) 固定为 STUDENT。其他写操作也有各自允许字段，不能直接绑定实体任意字段。

[RepairOrderForm](../../src/main/java/com/fixcampus/form/RepairOrderForm.java) 规范化 CRLF/CR，配合 [CodePointLengthValidator](../../src/main/java/com/fixcampus/validation/CodePointLengthValidator.java) 按 Unicode 码点计数，避免表情或换行造成前后端长度差异。Thymeleaf 使用转义文本而非将用户输入当 HTML 执行；注册失败不回显密码。CSRF 保持启用，包括登录、注册和 POST 退出。

验证入口：

- [UserAuthenticationIntegrationTest](../../src/test/java/com/fixcampus/UserAuthenticationIntegrationTest.java)：`registrationNormalizesSafeFieldsHashesPasswordAndIgnoresSystemFields`、`csrfProtectsAllPublicWritesAndLogoutInvalidatesOnlyCurrentSession`。
- [StudentRepairIntegrationTest](../../src/test/java/com/fixcampus/StudentRepairIntegrationTest.java)：`createUsesSessionIdentityAndSystemFieldsAndFlashIsOnceOnly`、`ownDetailEscapesHtmlAndDoesNotExposeWorkerContactsOrHashes`。
- [OrderSearchIntegrationTest](../../src/test/java/com/fixcampus/OrderSearchIntegrationTest.java)：`exactIdNumericLocationAndLikeMetacharactersHaveDefinedSemantics`，覆盖 LIKE 元字符作为字面输入的查询语义。

## 事务状态与处理记录

工单的取消、派单、开始、提交结果和确认均在事务中锁定目标行后检查最新状态。Repository 使用 `@Lock(PESSIMISTIC_WRITE)`；Service 用 `@Transactional` 包住状态修改及事件保存。完成时间只在学生确认时写入。

重复状态流转或状态竞争按 409 处理；锁超时也按冲突反馈。连接、数据访问或提交错误按 503 处理，避免在事务失败后仍返回成功提示。[GlobalExceptionHandler](../../src/main/java/com/fixcampus/exception/GlobalExceptionHandler.java) 不输出原始 SQL 错误或用户请求数据。报修创建没有服务端提交幂等键，不能把上述状态保护解释为“网络重试不会重复创建工单”。

| 解决的问题 | 真实测试入口 |
| --- | --- |
| 并发派单不能覆盖另一个人的处理 | [AdminOrderIntegrationTest](../../src/test/java/com/fixcampus/AdminOrderIntegrationTest.java) · `concurrentAssignmentsToDifferentWorkersHaveExactlyOneWinner` |
| 取消与派单以事务实际提交顺序为准 | 同上 · `assignmentAndCancellationRespectWhicheverTransactionCommitsFirst` |
| 真正的 MySQL 行锁等待超时不产生变更 | 同上 · `realMySqlAssignmentLockTimeoutLeavesOrderUnassigned` |
| 状态与已 flush 事件一并回滚 | [OrderHistoryIntegrationTest](../../src/test/java/com/fixcampus/OrderHistoryIntegrationTest.java) · `explicitTransactionRollbackRestoresStatusAndFlushedEventTogether` |
| 并发确认仅追加一次事件 | 同上 · `simultaneousConfirmationsAppendExactlyOneConfirmation` |
| 数据库/提交异常不能显示确认成功 | [OrderFailureHandlingTest](../../src/test/java/com/fixcampus/OrderFailureHandlingTest.java) · `confirmationDatabaseAndCommitFailuresReturn503WithoutSuccessFlash`（Mock 故障测试） |
| 并发同名注册只成功一个 | [UserAuthenticationIntegrationTest](../../src/test/java/com/fixcampus/UserAuthenticationIntegrationTest.java) · `concurrentDuplicateRegistrationsCommitExactlyOneAccount` |

[OrderHistory](../../src/main/java/com/fixcampus/view/OrderHistory.java) 对旧数据缺失的中间事件显示未记录，只引用确实存在的提交/完成字段，不伪造时间或操作人。它表达业务处理过程，不是完整的安全审计日志系统。

## 可重复的测试与 CI

[RealMySqlTestSupport](../../src/test/java/com/fixcampus/RealMySqlTestSupport.java) 在启动时检查数据库产品为 MySQL 且实际 schema 符合 `fixcampus_*_test`；测试清理只处理自己建立的夹具。真实数据库测试覆盖事务、外键、唯一约束和锁；Mock 故障测试补充难以稳定制造的连接或提交失败，不能把 Mock 结果说成真实数据库故障演练。

[现有 CI](../../.github/workflows/ci.yml) 在 Ubuntu 24.04 上运行 JDK 25、Maven 3.9.16、MySQL 8.4.11，按 001→002→004 初始化，使用非 root 单库 DML 账号且关闭演示初始化。Maven 失败保持 Job failure，收集与上传以 always 条件尽可能保存诊断；成功时拒绝缺失、损坏或空的必要报告。

候选版本的测试与覆盖率须以其独立 CI 产生的 Surefire 和 JaCoCo 报告复核；不沿用私有仓历史指标。

完整 HTTP 闭环的阅读入口是 [SystemWorkflowIntegrationTest](../../src/test/java/com/fixcampus/SystemWorkflowIntegrationTest.java) 的 `registeredStudentCompletesLifecycleThroughRealCookieSessions`，通过真实 Cookie 会话完成学生提交、管理员分派、维修处理和学生确认；`realSessionsEnforceRoleAndOwnershipBeforeReadingOrMutatingOrders` 验证真实会话下的权限边界。

公开候选应使用自己的 PR CI 和真实审查记录；不宣称特定仓库保护规则、生产部署或尚未完成的工程优化。
