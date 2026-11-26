# FixCampus React 前端

## 运行入口

本前端已将真实三角色工作台直接作为 `/app/` 默认入口（`/app/real/` 访问时重定向回 `/app/`），使用 `StudentClient` 访问 `/api/v1`，登录、Session、CSRF、分页、幂等、派单、维修、确认和撤销由真实后端处理。

已有依赖与隔离后端就绪后，`npm run dev`（或 `npm run dev:web`）与 `npm run preview:web` 将 `/api/v1` 同源代理到 `http://127.0.0.1:18080`，统一访问 `http://127.0.0.1:5173/app/`。`build:demo` / `preview:demo` 用于启用公开演示提示与演示账号说明；Mock UI 仅保留在显式 `npm run dev:mock` 模式中，不打入生产或演示构建。

三角色的隔离配置、演示流程和接口见 [Web/PWA 演示](../docs/portfolio/WEB-DEMO.md)。下文的 M4.1 验收记录仅对应当时的 Mock 版本。

## 最小 PWA

用 `npm run build` 生成应用，部署时将构建目录挂在 `/app/`。使用 HTTPS，或在本机通过浏览器认可的回环地址测试。构建目录包含 manifest、安装图标和 service worker。Edge 的应用菜单提供安装入口，安装后从真实报修工作台打开。与真实业务后端联用时，仍通过同源 `/api/v1` 访问。

- Service worker 只预缓存构建时逐项校验完整性的应用壳、静态资源、manifest 和图标。身份、CSRF、工单、操作结果及幂等键按现有认证和客户端内存规则处理。退出和切换账号继续由服务端重新确认身份，业务 API 始终走网络。
- 首次成功加载后，断网可打开应用壳。已确认离线时，真实请求会在发送前停止。网络在发送后中断时，页面继续保留原来的未知结果及人工恢复路径。重新联网后手动核对或发起操作。
- 新版下载完成后，页面提示先核对未确认的操作，再关闭所有 FixCampus 页面和窗口。重新打开后使用新版本。更新沿用浏览器的等待机制，当前页面保持打开期间不会自动刷新。

## M4.1 Mock 路径历史说明

M4.1 的本地 Mock 演示。所有身份、地点和工单均为虚构数据，刷新或切换场景后重置。URL 中的角色用于演示和 UX 守卫，不是身份认证。没有真实 HTTP adapter、登录、Session、CSRF、数据库或原生宿主。

## 本地使用

环境：Node 24.19.0、npm 11.17.0。直接依赖固定精确版本，首次工程创建时先生成 lockfile，随后已用以下方式安装：

```text
npm ci --ignore-scripts --no-audit --no-fund
npm run check
npm run dev:mock
```

Mock 开发地址 `http://127.0.0.1:5173/app/`。该命令无后端代理，5173 被占用时停止。使用前仍须具备当前任务的安装、运行及文件修改授权。

## 页面和模拟场景

- `/app/`：选择虚构身份。
- `/app/student/orders`、`/app/student/new`、`/app/student/orders/:orderId`。
- `/app/admin/orders`、`/app/admin/orders/:orderId`。
- `/app/worker/orders`、`/app/worker/orders/:orderId`。
- 页面顶部可切换正常、空列表、503、403、401、写入冲突409场景。切换会重建内存仓库及 Query 缓存。
- 工单 1001 可演示管理员派单 → 维修人员开始 → 提交结果 → 学生确认；直接切换身份时内存保留，浏览器刷新则回到 fixtures。
- 学生详情 1024 为不可见对象负样本；管理员详情 1011 为缺失历史事件样本。

## 代码与检查

`src/shared/api/contracts.ts` 镜像 M4.0.3 候选契约，ID 使用字符串；Mock 列表返回 `items/page/size/totalElements/totalPages/sort`。`viewRole` 是仅用于前端 Mock 投影的判别字段，不能未经审查带入真实协议。详情按角色投影，写操作每次重新判断模拟状态。Mock 不证明真实服务端授权、事务、并发、分页或幂等。

`src/shared/forms/schemas.ts` 使用 Unicode 码点长度、去除首尾空白和换行归一化；表单校验只提供 UX。组件与设计令牌沿用既有 SSR 视觉语义，缺少 M4.0.2 视觉稿，未声称精确复刻。

`npm run check` 包含类型、Lint、格式、Vitest、静态隔离扫描和构建。`npm run licenses` 从锁文件及已安装包读取许可证和来源；清单不构成分发许可。静态检查与禁止传输的单元测试不能替代浏览器网络证据。

## 验收限制

M4.1 内置浏览器验证了三角色模拟操作，留存三种视口截图。该次验收缺少完整网络捕获，运行时网络隔离为 **UNKNOWN**，整体验收为 **BLOCKED**。

## P1 视觉增量

负责人已提供四张视觉参考并批准P1最小优化。当前使用桌面侧栏、移动导航、可键盘展开的模拟场景设置、统一卡片和表单样式；仍复用相同路由、类型、Mock与状态机。分类图标块、插画、照片、评分、统计等未加入。

当时的验收记录覆盖四个页面的三种视口；实际 200% 缩放与完整网络轨迹未留证。
