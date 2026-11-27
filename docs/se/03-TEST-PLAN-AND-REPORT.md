# 软件测试计划与测试报告

**项目名称**：FixCampus · 校园报修管理平台  
**文档版本**：v1.0（对齐当前 Web/PWA + Flyway `V1～V6` 实现基线）  
**编制日期**：2026-11-26  
**相关文档**：[返回 README](../../README.md) · [软件需求规格说明书](01-REQUIREMENTS-SPECIFICATION.md) · [系统概要与详细设计说明书](02-SYSTEM-AND-DATABASE-DESIGN.md) · [性能基准](../benchmark.md)

---

## 1. 测试策略与分层体系

本项目按风险分层设计自动化与实机验收测试，绝不以 Mock 替代真实数据库约束验证，也不以历史指标代替当次构建结果：

| 测试层级 | 运行环境与工具 | 覆盖重点 |
| --- | --- | --- |
| 后端单元测试与 MockMvc 切片 | JUnit 5 + Mockito + Spring MockMvc | 领域校验、限流桶与锁超时映射、图片魔数/解压边界、存储路径防护、统计空样本、通知幂等与默认 SSR 兼容 |
| 后端真实数据库集成测试 | JUnit 5 + 隔离 MySQL 8.4.11（`fixcampus_*_test`）+ Flyway `V1～V6` | 三角色真实 Cookie 闭环、并发派单/撤销与真实 InnoDB 锁超时、6 线程创建幂等、数据库外键/CHECK/唯一键、附件全局配额、通知同事务回滚、`V1～V5 → V6` 增量升级 |
| 前端组件与客户端自动化测试 | Vitest 5 + Testing Library + jsdom + ESLint + TypeScript + `check:no-network` | 三角色页面交互、表单校验、跨标签切换身份与 CSRF 隔离、未知结果手动恢复、附件上传/预览/聚焦恢复、30 秒通知轮询与写版本防覆盖、统计展示与零外网调用 |
| 清洁导出与安全回归测试 | Python `unittest`（`scripts/test-public-export.py`） | 清洁源码快照白名单、私有标识/令牌/邮箱/个人路径拦截、PNG 纯净块与 CRC 校验、Markdown 死链拦截、符号链接拦截 |
| 真实浏览器端到端与干净目录验证 | 独立隔离 MySQL 8.4.11 + Spring Boot `web-slice` + React 生产构建 + 浏览器 | 零 `.git` 目录独立构建启动、三角色完整闭环（含附件、撤销、通知、统计）与脱敏截图留证 |
| 数据库深分页性能基准 | 单机 10 万条隔离 MySQL 工单 + 4 并发槽 × 120 样本 | 深分页优化前后 P95 / P99 / SQL 耗时可复现对照 |

---

## 2. 需求-测试用例追踪矩阵（RTM）

以下追踪矩阵将 [软件需求规格说明书](01-REQUIREMENTS-SPECIFICATION.md) 中的核心需求逐项映射到真实存在的自动化测试类与方法：

| 需求编号 | 需求概述 | 验证层级 | 真实测试类与代表性测试方法 |
| --- | --- | --- | --- |
| `FR-01` | 会话轮换、CSRF、Origin 与认证隔离 | 后端集成 + 前端 | [WebStudentSliceIntegrationTest](../../src/test/java/com/fixcampus/WebStudentSliceIntegrationTest.java) (`authenticationRotatesSessionRefreshesCsrfAndLogsOut`, `realHttpCookieSessionPersistsAcrossSeparateRequests`)；[RealStudentApp.test.tsx](../../frontend/src/test/RealStudentApp.test.tsx) |
| `FR-02` | 根路径重定向、关闭注册、生产/Mock 隔离 | 切片 + 集成 | [PublicDemoApiTest](../../src/test/java/com/fixcampus/api/v1/PublicDemoApiTest.java) (`eachWebProfileLoadsSharedHardening`, `hardenedRootAndDisabledRegistrationPreserveTheApiSessionFlow`)；[OrderOperationsApiIntegrationTest](../../src/test/java/com/fixcampus/OrderOperationsApiIntegrationTest.java) (`webSliceRootRedirectsAndDisabledRegistrationLeavesUsersUnchanged`) |
| `FR-03` | 登录与显式业务写路由进程内限流 | MockMvc 切片 | [PublicDemoApiTest](../../src/test/java/com/fixcampus/api/v1/PublicDemoApiTest.java) (`loginQuotaFollowsRemoteAddressAcrossSessionsAndIgnoresForwardedHeader`, `allOrderWriteRoutesLimitEachSessionIndependently`) |
| `FR-04` / `FR-05` | 报修创建、Unicode 校验与 6 线程持久化幂等 | 后端集成 + 前端 | [WebStudentSliceIntegrationTest](../../src/test/java/com/fixcampus/WebStudentSliceIntegrationTest.java) (`persistedCreationReplaysOriginalResultAndDetailsRespectOwnership`, `concurrentSameKeyCreatesExactlyOneOrderAndEvent`, `transactionRollbackIncludesOrderEventAndIdempotency`)；[RealStudentApp.test.tsx](../../frontend/src/test/RealStudentApp.test.tsx) |
| `FR-06` | 学生撤销待分配工单与派单竞争 | 后端集成 + 前端 | [OrderOperationsApiIntegrationTest](../../src/test/java/com/fixcampus/OrderOperationsApiIntegrationTest.java) (`studentCancellationUsesExistingStateAndEventContract`, `cancellationRejectsForeignMissingMalformedAndForgedInputWithoutMutation`, `cancellationAndAssignmentRaceKeepsOneLegalStateAndEvent`)；[RealThreeRoleApp.test.tsx](../../frontend/src/test/RealThreeRoleApp.test.tsx) |
| `FR-07` | 三角色数据库分页、筛选与安全检索 | 后端集成 + 基准 | [OrderOperationsApiIntegrationTest](../../src/test/java/com/fixcampus/OrderOperationsApiIntegrationTest.java) (`listsPageInDatabaseAndRestrictWorkerScopeAndSearch`)；[OrderSearchIntegrationTest](../../src/test/java/com/fixcampus/OrderSearchIntegrationTest.java)；[性能基准](../benchmark.md) |
| `FR-08` / `FR-09` / `FR-10` | 管理员派单、维修执行、学生确认与并发锁 | 后端集成 | [OrderOperationsApiIntegrationTest](../../src/test/java/com/fixcampus/OrderOperationsApiIntegrationTest.java) (`realSessionsCompleteThreeRoleWorkflowAndExposeSafeDetails`, `mysqlLockWaitReturns503WithoutPartialWritesAndLaterStateConflictReturns409`, `concurrentTransitionHasOneWinnerAndOneEvent`, `outerRollbackRevertsStateAndEventTogether`) |
| `FR-11` | 处理事件时间线与无回填策略 | 后端集成 | [OrderHistoryIntegrationTest](../../src/test/java/com/fixcampus/OrderHistoryIntegrationTest.java)；[AdminOrderIntegrationTest](../../src/test/java/com/fixcampus/AdminOrderIntegrationTest.java) |
| `FR-12` / `FR-13` / `FR-14` | 图片魔数/尺寸/去 EXIF、全局配额、回滚清理与三角色 403/404 | 单测 + 切片 + 集成 + 前端 | [AttachmentImagesTest](../../src/test/java/com/fixcampus/service/AttachmentImagesTest.java)；[AttachmentStorageTest](../../src/test/java/com/fixcampus/service/AttachmentStorageTest.java)；[OrderAttachmentsApiTest](../../src/test/java/com/fixcampus/api/v1/OrderAttachmentsApiTest.java)；[OrderAttachmentIntegrationTest](../../src/test/java/com/fixcampus/OrderAttachmentIntegrationTest.java) (`threeRolesUsePersistedAttachmentsAndRespectOwnershipAndDeleteState`, `concurrentUploadsAcrossOrdersRespectTheGlobalQuota`, `rollingBackUploadRemovesTheFileAndRollingBackDeletePreservesIt`)；[RealOrderAttachments.test.tsx](../../frontend/src/test/RealOrderAttachments.test.tsx) |
| `FR-15` / `FR-16` | 同事务通知、幂等去重、跨账号 404 与 30 秒轮询 | 单测 + 切片 + 集成 + 前端 | [NotificationServiceTest](../../src/test/java/com/fixcampus/service/NotificationServiceTest.java)；[NotificationsApiTest](../../src/test/java/com/fixcampus/api/v1/NotificationsApiTest.java)；[NotificationIntegrationTest](../../src/test/java/com/fixcampus/NotificationIntegrationTest.java) (`isolatesStudentNotificationsFromOtherAccounts`, `failedOrderTransitionsLeaveNoNotifications`, `outerRollbackRevertsOrderAndNotificationWrites`, `duplicatePublicationAndMarkingPreserveReadTime`, `threeRoleWorkflowNotifiesEachRecipientAndPreservesOrderVisibility`)；[RealNotifications.test.tsx](../../frontend/src/test/RealNotifications.test.tsx) |
| `FR-17` | 管理员统计聚合、两类耗时与缺失事件披露 | 单测 + 切片 + 集成 + 前端 | [AdminStatisticsServiceTest](../../src/test/java/com/fixcampus/service/AdminStatisticsServiceTest.java)；[AdminStatisticsApiTest](../../src/test/java/com/fixcampus/api/v1/AdminStatisticsApiTest.java)；[AdminStatisticsIntegrationTest](../../src/test/java/com/fixcampus/AdminStatisticsIntegrationTest.java) (`emptyDatabaseReturnsZeroCountsAndNullDurations`, `sessionsEnforceRolesAndAdminReceivesRealSqlAggregates`)；[RealAdminStats.test.tsx](../../frontend/src/test/RealAdminStats.test.tsx) |
| `FR-18` | 双重显式开关原子重置示例库与清理附件 | 单测 + 集成 | [DemoResetServiceTest](../../src/test/java/com/fixcampus/service/DemoResetServiceTest.java)；[DemoResetIntegrationTest](../../src/test/java/com/fixcampus/DemoResetIntegrationTest.java) (`resetReplacesVisitorDataWithTheSameCompleteDemoBaselineOnEveryRun`, `failureAfterPartialSeedingRollsBackAllDeletesAndInserts`)；[OrderAttachmentIntegrationTest](../../src/test/java/com/fixcampus/OrderAttachmentIntegrationTest.java) (`demoResetClearsRowsAndManagedFilesOnCommit`) |
| `FR-19` / `NFR-OBS-01` | 禁外网静态检查、请求编号与脱敏日志 | 切片 + 前端 | [ApiRequestIdTest](../../src/test/java/com/fixcampus/api/v1/ApiRequestIdTest.java) (`failedLoginUsesTheSameIdInHeadersBodyAndRedactedLog`, `securityDenialsReplaceInvalidIdsAndDoNotLogQueryValues`, `concurrentRequestsKeepTheirOwnContextAndClearItOnExit`)；[noNetwork.test.ts](../../frontend/src/test/noNetwork.test.ts) |
| `FR-20` | 传统三表 SSR 兼容 | 单测 + 集成 | [RepairOrderServiceDefaultProfileTest](../../src/test/java/com/fixcampus/service/RepairOrderServiceDefaultProfileTest.java) (`createsAndTransitionsOrdersWithoutNotificationService`)；[SystemWorkflowIntegrationTest](../../src/test/java/com/fixcampus/SystemWorkflowIntegrationTest.java) |

---

## 3. 核心验收测试场景与实测结论

| 编号 | 测试场景 | 预期结果 | 实测结论 |
| --- | --- | --- | :---: |
| `TC-01` | 三角色真实浏览器闭环（学生建单+传图 → 管理员派单 → 维修员开工+传图+完工 → 学生看通知全部已读+确认完成 → 刷新） | 工单状态流转至 `COMPLETED`，5 条时间线与 2 张附件刷新不丢失，通知未读角标准确增减与归零 | **PASS** |
| `TC-02` | 跨角色路由与对象越权边界测试 | 跨角色访问返回 `403 ROLE_FORBIDDEN`；同角色访问他人工单、附件或通知统一返回 `404` | **PASS** |
| `TC-03` | 6 线程同 `Idempotency-Key` 并发提交与同键不同内容重放 | 仅落库 1 张工单与 1 条 `SUBMITTED` 事件；同内容重放返回原工单，不同内容返回 `409` | **PASS** |
| `TC-04` | 并发派单/撤销竞争与真实 MySQL 3 秒行锁等待超时 | 竞争仅 1 个合法状态胜出；行锁超时完整回滚（无部分写入）并返回 `503 TEMPORARILY_UNAVAILABLE` | **PASS** |
| `TC-05` | 恶意/超限附件拦截与并发全局配额 | 伪造后缀、SVG/GIF、超像素返回 `400`；超 2 MiB 返回 `413`；超单单张数/64 MiB 返回 `409`；事务回滚清理落盘文件 | **PASS** |
| `TC-06` | 站内通知同事务回滚与幂等去重 | 失败流转或外层事务回滚时 0 通知落库；重复发布与重复已读保持幂等且不覆盖原 `readAt` | **PASS** |
| `TC-07` | Flyway `V1～V5 → V6` 增量升级与 `CHECK` 护栏 | 既有 5 表探针数据升级后逐行无损，`V1～V6` 全部 `SUCCESS`，非法事件类型与尾空格触发 MySQL 3819 拒绝 | **PASS** |
| `TC-08` | 零 `.git` 干净导出目录独立构建与空库启动 | 前端 `npm ci && npm run check && npm run build:demo` 通过；后端 `run-web-slice.ps1` 空库 `V1～V6` 启动且核心接口冒烟全通 | **PASS** |
| `TC-09` | 10 万条工单管理员无筛选末页深分页性能对照 | 延迟关联优化后 P95 显著下降且总数/排序契约不变（实测 123.02 ms → 39.88 ms） | **PASS** |

---

## 4. 自动化测试执行与覆盖率汇总

以下汇总为 2026-11-26 上游基线导出前的真实 CI 与本机实测基线记录；在本公开展示仓库中，当前发布提交的权威自动化验收结果以本仓库 [CI 工作流](../../.github/workflows/ci.yml) 的独立 Actions 实跑产物（Surefire、JaCoCo 与前端检查）为准：

| 验证项 | 范围 | 执行结果 |
| --- | --- | --- |
| 后端全量测试（隔离 MySQL 8.4.11 + JDK 25） | 35 份 Surefire 报告（单元 + MockMvc + 真实 MySQL 集成） | **457 / 457 通过**（0 失败 / 0 错误 / 0 跳过） |
| 后端 JaCoCo 覆盖率（全量生产代码，无人工排除） | 104 个生产类 | 行覆盖 `1595 / 1678`（95.05%）、指令覆盖 `10030 / 10489`（95.62%）、分支覆盖 `667 / 798`（83.58%） |
| 前端全量质量检查（`npm run check`） | 10 个测试文件 + TS + ESLint (0 warnings) + Prettier + `check:no-network` + 标准构建（`npm run build`） | **160 / 160 通过**，标准生产构建成功（注：CI 的 `npm run check` 仅包含标准构建） |
| 前端演示模式构建（`npm run build:demo`） | 独立演示模式构建验证（Vite `--mode demo`） | **通过**（独立于 CI `npm run check`，本机与干净导出目录单独验证） |
| 干净目录源码导出回归（`test-public-export.py`） | 白名单、脱敏规则、PNG 纯净块、Markdown 死链、符号链接 | **全部通过** |
| 干净目录实机启动 + 真实浏览器三角色闭环 | 一次性隔离 MySQL 8.4.11（`127.0.0.1:13306`） | **通过**（完整留存新版 M4.1 脱敏实测截图，见根目录 [README](../../README.md) 界面预览） |

---

## 5. 已知局限与未覆盖风险说明

1. **未开展公网 HTTPS 生产压测与反向代理实测**：本阶段所有验证均在 CI 容器与本机回环隔离环境完成；若部署至公网反代之后，须验证真实客户端 IP 透传对登录限流的影响、TLS/Secure Cookie 与长期磁盘空间监控。
2. **单节点进程内限流与本地磁盘附件**：当前实现面向单机演示与课程交付，若扩展为多实例部署需替换为集中式限流与共享对象存储。
3. **未包含多库并行的独立双 Schema 迁移实测**：受限于单隔离测试 Schema 权限，自动化升级测试在同一隔离空库内按 `V1～V5` 写入 5 表探针再升级 `V6`；另在干净目录启动验收中对全新空库直接执行了 `V1～V6` 全量初始化。
4. **未实现功能边界**：改派、返修、地点结构化区域聚合与原生桌面/移动端客户端不在本次测试范围内。
