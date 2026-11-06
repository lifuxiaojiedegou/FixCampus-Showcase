# FixCampus · 工程设计与验证说明

这份文档聚焦代码中已落实的设计边界、工程取舍与验证证据，而不是将所有组件罗列成“技术亮点”。项目采用 **Spring Boot 单体应用 + Thymeleaf 服务端渲染 + MySQL**，目前用于本地演示和作品集展示，并非真实校园生产系统。

[返回 README](../../README.md) · [系统架构与状态图](SYSTEM-DIAGRAMS.md) · [实现与测试依据](../technical/IMPLEMENTATION.md)

## 业务问题与系统边界

一张报修单需要跨三个角色协作：学生提交，管理员派给有效维修人员，维修人员开始处理并提交维修结果，最后由报修学生确认。系统将这一流程显式编码为六个状态：

```text
待分配 ─管理员派单→ 待维修 ─维修开始→ 维修中 ─提交结果→ 待确认 ─学生确认→ 已完成
   └────────────学生撤销────────────→ 已取消
```

- 学生只能撤销本人仍处于待分配状态的工单。
- “维修人员提交结果”不等于“学生确认完成”；只有后者会写入最终完成时间。
- 已完成和已取消是终态。当前不支持改派和返修，流程中没有被省略的额外状态。

依据：[RepairStatus](../../src/main/java/com/fixcampus/entity/RepairStatus.java) · [RepairOrderService](../../src/main/java/com/fixcampus/service/RepairOrderService.java)。

## 设计一：角色授权与对象级数据隔离

**问题：** 如果只在页面上隐藏其他角色的按钮，或者只验证 URL 的角色前缀，就无法阻止用户替换工单 ID 查看或修改别人的数据。

**当前实现：**

1. Spring Security 对 `/student/**`、`/worker/**`、`/admin/**` 做角色授权，使用 Session 表单登录，并保留 CSRF 防护。
2. 学生和维修人员的 Repository 读取查询直接携带本人 ID；管理员可在职责范围内查看全部工单。
3. Service 的写操作在锁定工单之后再次核对当前操作者、工单归属及状态。不是仅依赖页面隐藏或前端传来的角色字段。

对于本人范围之外的工单，应用按业务约定返回 404。角色校验、对象归属与状态校验分别解决不同问题，不能相互替代。

依据：[SecurityConfig](../../src/main/java/com/fixcampus/config/SecurityConfig.java) · [RepairOrderRepository](../../src/main/java/com/fixcampus/repository/RepairOrderRepository.java) · [RepairOrderService](../../src/main/java/com/fixcampus/service/RepairOrderService.java)。

## 设计二：并发状态变更与处理事件一致性

**问题：** 管理员并发派单、学生撤销与管理员派单竞争、学生重复确认，都可能造成过时状态检查和重复事件。

**当前实现：**

1. 取消、派单、开始维修、提交结果和学生确认由 Service 中的 `@Transactional` 方法处理。
2. Repository 通过 `@Lock(PESSIMISTIC_WRITE)` 锁定目标工单行；拿到最新状态后，再进行归属与转移合法性检查。
3. 修改工单与新增 `order_events` 记录在同一事务提交。事件记录保留操作动作、操作者角色/姓名快照与时间。
4. 数据库外键、CHECK 约束和 `(order_id, action)` 唯一约束提供额外保护。重复状态操作与锁超时按当前规则返回 409，存储/提交故障按 503 处理。

这套机制避免“先查后改”在竞争窗口中覆盖其他人的状态，也保证事务失败时不会把状态和处理事件拆成两个成功结果；它**不意味着所有类型的请求均具有幂等性**。当前“创建新报修”尚无服务端幂等键，网络超时后的重试可能重复创建。

依据：[RepairOrderService](../../src/main/java/com/fixcampus/service/RepairOrderService.java) · [RepairOrderRepository](../../src/main/java/com/fixcampus/repository/RepairOrderRepository.java) · [工单表 SQL](../../database/002-repair-orders.sql) · [事件表 SQL](../../database/004-order-events.sql)。

## 设计三：分层单体、输入边界与可解释性

项目的 HTTP 请求由 Spring MVC Controller 接收，Controller 对表单执行白名单字段绑定与 Bean Validation；业务归属和状态由 Service 管理；Repository 负责查询与锁操作；View DTO 经 Thymeleaf 渲染成 HTML。

- `open-in-view=false`，不依赖模板直接操作 JPA 实体完成数据访问。
- 普通注册固定为学生角色，服务端生成工单系统字段，不信任表单自报的操作者和状态。
- 登录密码使用 PBKDF2 编码；Thymeleaf 对普通文本执行转义；报修描述按 Unicode 码点校验长度。
- 当前采用单体和服务端页面；**没有**微服务、Redis、消息队列、前后端分离 API 或生产部署事实可供展示。

分层设计及界面定位见：[架构与业务图](SYSTEM-DIAGRAMS.md) · [实现导览](../technical/IMPLEMENTATION.md)。

## 如何验证这些设计

- **完整业务闭环：** `SystemWorkflowIntegrationTest` 使用真实 Cookie 会话，覆盖学生提交、管理员派单、维修处理、学生确认，并检查角色和工单归属边界。
- **并发与回滚：** `AdminOrderIntegrationTest` 检查并发派单与撤销竞争；`OrderHistoryIntegrationTest` 检查事件和状态一并回滚、并发确认仅产生一次事件。
- **真实数据库：** 关键集成测试要求隔离的 MySQL 测试库；Mock 测试单独用于部分故障响应，不把 Mock 结果说成真实数据库故障演练。
- **CI：** `main` 推送或手动触发 GitHub Actions，以 JDK 25、Maven Wrapper、独立 MySQL 8.4.11 容器执行完整 `clean verify`，生成结构化 Surefire 和 JaCoCo 证据。

本仓库的 CI Run、Surefire 与 JaCoCo 文件是当前展示版本的独立验证依据；具体测试结果以实际 Run 记录为准。

测试依据：[SystemWorkflowIntegrationTest](../../src/test/java/com/fixcampus/SystemWorkflowIntegrationTest.java) · [AdminOrderIntegrationTest](../../src/test/java/com/fixcampus/AdminOrderIntegrationTest.java) · [OrderHistoryIntegrationTest](../../src/test/java/com/fixcampus/OrderHistoryIntegrationTest.java) · [CI 工作流](../../.github/workflows/ci.yml)。

## 当前取舍与下一步

| 当前边界 | 对项目的影响或后续考虑 |
| --- | --- |
| 单体、Thymeleaf SSR | 适合现有规模的三角色本地业务闭环；不宣称已解决生产规模部署问题 |
| 列表未分页 | 数据增长后需评估带索引的数据库分页及页面交互 |
| 创建工单无幂等键 | 不确定响应后重试可能重复提交；后续可设计提交标识与服务端去重 |
| 每工单每动作唯一 | 适用于现有单向状态流程；改派/返修需要重设计事件模型 |
| 没有附件、通知或用户管理界面 | 属于未实现需求，不能作为简历功能成果 |
| 未完成生产部署、负载与容量验证 | 当前 CI 和本地演示不能等同于线上可用性 |

**阅读建议：** 先看 [README](../../README.md) 和 [架构图](SYSTEM-DIAGRAMS.md)，再从 Service、Repository、数据库 DDL 与集成测试理解具体实现。这里记录的是当前代码可以解释的工程实践，不替代个人贡献说明或面试时对实际工作过程的准确陈述。
