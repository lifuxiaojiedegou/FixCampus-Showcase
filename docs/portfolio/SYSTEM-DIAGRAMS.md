# FixCampus · 系统架构与业务流程图

这份文档依据当前源码与 Flyway `V1～V6` 数据库结构整理，展示实际实现的 Web/PWA 前后端分层单体架构、六状态工单状态机、三角色闭环事务边界及六表数据模型。图为**设计与代码关系示意**，并非多节点生产部署拓扑图；当前项目没有微服务、Redis 或消息队列。

返回 [项目 README](../../README.md) · 阅读 [Web/PWA 演示](WEB-DEMO.md) · [详细技术架构](../technical/ARCHITECTURE.md) · [实现与测试依据](../technical/IMPLEMENTATION.md)

## 一、系统分层架构

```mermaid
flowchart TB
    ST["学生"] --> B["桌面 / 移动浏览器 · PWA 应用壳"]
    AD["管理员"] --> B
    WK["维修人员"] --> B

    subgraph APP["FixCampus · Spring Boot 3.5 单体后端"]
        direction TB
        REQID["ApiRequestIdFilter<br/>X-Request-Id · 脱敏 MDC"]
        SEC["Spring Security（/api/v1/** 与兼容 SSR）<br/>Servlet Session · Origin · CSRF · 角色授权"]
        LIMIT["ApiRateLimitFilter<br/>登录与显式业务写路由进程内限流"]
        API["REST API 控制器层<br/>StudentApi · OrderOperationsApi · AdminStatisticsApi<br/>OrderAttachmentsApi · NotificationsApi"]
        SVC["业务与查询服务层<br/>RepairOrderService · StudentOrders · OperationalOrders<br/>AdminStatisticsService · OrderAttachments · NotificationService"]
        REPO["JPA 行锁写入 + JDBC 分页/聚合查询"]
        REQID --> SEC
        SEC --> LIMIT
        LIMIT --> API
        API --> SVC
        SVC --> REPO
    end

    B -- "同源 REST /api/v1" --> REQID
    REPO --> DB[("MySQL 8.4 · InnoDB · Flyway V1~V6<br/>users · repair_orders · order_events<br/>idempotency_requests · order_attachments · notifications")]
    SVC --> FS[("本地托管附件目录<br/>无 EXIF 重编码 PNG/JPEG")]
    API -. "JSON / Problem JSON / 图片流" .-> B
```

前端 React 19 SPA 挂载于 `/app/`，通过同源 `/api/v1` 访问后端；PWA Service Worker 仅缓存静态应用壳，绝不缓存认证与业务接口。后端入口先附加请求编号，再经 Spring Security 会话/Origin/CSRF/角色授权校验，随后由 `ApiRateLimitFilter` 对登录与显式业务写路由执行限流；读路径使用 JDBC 数据库分页与 SQL 聚合，写路径在 `@Transactional` 中使用 `PESSIMISTIC_WRITE` 锁定工单行并保持 `open-in-view=false`。原有 Thymeleaf SSR 路径保留兼容。

核心源码：[ApiSecurityConfig](../../src/main/java/com/fixcampus/api/v1/ApiSecurityConfig.java) · [ApiRateLimitFilter](../../src/main/java/com/fixcampus/api/v1/ApiRateLimitFilter.java) · [StudentApi](../../src/main/java/com/fixcampus/api/v1/StudentApi.java) · [OrderOperationsApi](../../src/main/java/com/fixcampus/api/v1/OrderOperationsApi.java) · [AdminStatisticsApi](../../src/main/java/com/fixcampus/api/v1/AdminStatisticsApi.java) · [OrderAttachmentsApi](../../src/main/java/com/fixcampus/api/v1/OrderAttachmentsApi.java) · [NotificationsApi](../../src/main/java/com/fixcampus/api/v1/NotificationsApi.java) · [RepairOrderService](../../src/main/java/com/fixcampus/service/RepairOrderService.java)。

## 二、工单状态流转

```mermaid
stateDiagram-v2
    state "待分配 PENDING_ASSIGNMENT" as PA
    state "待维修 PENDING_REPAIR" as PR
    state "维修中 IN_PROGRESS" as IP
    state "待确认 PENDING_CONFIRMATION" as PC
    state "已完成 COMPLETED" as CO
    state "已取消 CANCELLED" as CA

    [*] --> PA: 学生提交报修（Idempotency-Key + 通知管理员）
    PA --> PR: 管理员派单（通知学生与维修员）
    PR --> IP: 维修人员开始维修（通知学生）
    IP --> PC: 维修人员提交结果（通知学生）
    PC --> CO: 报修学生确认完成（写入 completed_at + 通知维修员）
    PA --> CA: 报修学生撤销（通知管理员）
    CO --> [*]
    CA --> [*]
```

只有这六种状态；**开始维修**与**提交维修结果**是引发状态变化的操作，不是额外状态。撤销仅限未派单的本人工单，完成时间仅在学生最终确认时写入。学生可在待分配/待维修阶段上传故障图片（待分配可删本人上传），维修人员可在维修中上传完工图片。`COMPLETED` 与 `CANCELLED` 均为终态，当前不支持改派或返修。状态与事件枚举见 [RepairStatus](../../src/main/java/com/fixcampus/entity/RepairStatus.java) 与 [OrderAction](../../src/main/java/com/fixcampus/entity/OrderAction.java)。

## 三、三角色闭环与事务边界

```mermaid
sequenceDiagram
    autonumber
    actor ST as 学生
    actor AD as 管理员
    actor WK as 维修人员
    participant API as REST / Security / RateLimit
    participant SVC as Service（事务 + 行锁）
    participant DB as MySQL（V1~V6）+ 附件存储

    ST->>API: POST /api/v1/orders（含 Idempotency-Key）
    API->>SVC: StudentOrders.create(...)
    SVC->>DB: 先查幂等记录；未命中则同事务写工单、SUBMITTED 事件、幂等记录与管理员通知
    DB-->>SVC: 事务提交
    API-->>ST: 201 Created

    ST->>API: POST /api/v1/orders/{id}/attachments
    API->>SVC: OrderAttachments.upload(...)
    SVC->>DB: 写串行化 + 锁行核对配额；图片去 EXIF 重编码落盘并写元数据
    DB-->>SVC: 事务提交
    API-->>ST: 201 Created

    AD->>API: POST /api/v1/orders/{id}/assign
    API->>SVC: RepairOrderService.assign(...)
    SVC->>DB: PESSIMISTIC_WRITE 锁工单行（超时 3s → 503）
    Note over SVC,DB: 校验角色、待分配状态与有效维修员
    SVC->>DB: 更新为待维修 + ASSIGNED 事件 + 学生/维修员通知
    DB-->>SVC: 事务提交
    API-->>AD: 204 No Content

    WK->>API: POST /api/v1/orders/{id}/start
    API->>SVC: RepairOrderService.start(...)
    SVC->>DB: 锁行校验归属 → 维修中 + REPAIR_STARTED + 学生通知
    DB-->>SVC: 事务提交
    API-->>WK: 204 No Content

    WK->>API: POST /api/v1/worker/orders/{id}/attachments
    API->>SVC: OrderAttachments.upload(...)
    SVC->>DB: 写串行化 + 锁行核对配额；图片去 EXIF 重编码落盘并写元数据
    DB-->>SVC: 事务提交
    API-->>WK: 201 Created

    WK->>API: POST /api/v1/orders/{id}/complete
    API->>SVC: RepairOrderService.complete(...)
    SVC->>DB: 锁行更新为待确认 + REPAIR_COMPLETED + 学生通知
    DB-->>SVC: 事务提交
    API-->>WK: 204 No Content

    ST->>API: GET /api/v1/notifications
    API->>SVC: NotificationService.list(...)
    SVC->>DB: 按接收人分页查询通知与未读总数
    DB-->>SVC: 返回分页结果
    API-->>ST: 200 OK

    ST->>API: POST /api/v1/notifications/read-all
    API->>SVC: NotificationService.readAll(...)
    SVC->>DB: 批量标记当前用户未读通知已读
    DB-->>SVC: 事务提交
    API-->>ST: 200 OK（markedCount / unreadCount）

    ST->>API: POST /api/v1/orders/{id}/confirm
    API->>SVC: RepairOrderService.confirm(...)
    SVC->>DB: 锁行写入 completed_at + CONFIRMED 事件 + 维修员通知
    DB-->>SVC: 事务提交
    API-->>ST: 204 No Content（已完成）

    AD->>API: GET /api/v1/admin/statistics?days=14
    API->>SVC: AdminStatisticsService.query(...)
    SVC->>DB: 只读事务 SQL 聚合（状态/分类/按日趋势/两类耗时）
    DB-->>SVC: 返回聚合快照
    API-->>AD: 200 OK
```

时序图突出成功路径。各业务 HTTP 请求彼此独立：`StudentOrders.create` 在开启写事务前先查询既有幂等记录（重放命中直接返回原工单，并发同键冲突则在写事务回滚后再次查询）；既有工单的状态变更、事件和通知在各自的 `@Transactional` 边界内同事务提交；任一归属或状态校验失败返回 `404` 或 `409`，锁等待超时或数据库故障完整回滚数据库变更并返回 `503`。附件落盘后的磁盘清理通过事务提交/回滚回调执行，若文件系统删除失败则记录日志并入队补偿清理。

## 四、六表数据与设计边界

- `users` 保存三角色与登录身份；`repair_orders` 保存报修内容、学生、可选维修人员、状态和学生确认完成时间；`order_events` 保存动作、操作人角色/姓名快照与微秒时间；`idempotency_requests` 保存创建报修幂等键、请求摘要与结果工单；`order_attachments` 保存净化后图片元数据与随机存储键；`notifications` 保存接收人、事件类型、内容、已读时间与幂等键。
- 数据库外键、`CHECK` 约束、事件 `(order_id, action)` 唯一约束与通知 `idempotency_key` 唯一约束共同提供底层护栏；事件与通知属于**业务处理记录**，不宣称为数据库级不可篡改审计日志。
- 当前不支持工单改派、返修、地点结构化区域聚合与多节点部署；如果未来扩展分支流转，应先重新评估事件唯一约束与通知幂等键规则。

数据库迁移依据：[V1 用户](../../src/main/resources/db/migration/V1__create_users.sql) · [V2 工单](../../src/main/resources/db/migration/V2__create_repair_orders.sql) · [V3 事件](../../src/main/resources/db/migration/V3__create_order_events.sql) · [V4 幂等](../../src/main/resources/db/migration/V4__create_idempotency.sql) · [V5 附件](../../src/main/resources/db/migration/V5__create_order_attachments.sql) · [V6 通知](../../src/main/resources/db/migration/V6__create_notifications.sql)。
