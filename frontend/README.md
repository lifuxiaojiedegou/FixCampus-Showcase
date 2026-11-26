# FixCampus React 前端（Web / PWA）

本目录为 **FixCampus 2.0** 的前端工作台源码，基于 **React 19 + TypeScript + Vite** 构建，与后端 `/api/v1` 同源联调，支持三角色完整报修闭环、图片附件、站内通知、管理员统计面板与最小 PWA 应用壳。

## 运行入口与模式

- **真实三角色工作台（默认入口 `/app/`）**：通过 `StudentClient` 调用同源 `/api/v1`，登录、Session、CSRF、分页、创建幂等、状态流转、附件与通知均由真实后端处理。
- **同源开发与预览代理**：隔离后端（`http://127.0.0.1:18080`）就绪后，运行 `npm run dev:web` 或 `npm run preview:web`，将 `/api/v1` 代理至后端，浏览器访问 `http://127.0.0.1:5173/app/`。完整联调步骤见 [Web/PWA 演示指南](../docs/portfolio/WEB-DEMO.md)。
- **演示模式构建**：`npm run build:demo` / `npm run preview:demo` 在登录页展示预置演示账号提示与定期清空说明；标准生产构建 `npm run build` 默认隐藏。
- **离线 Mock 快速预览**：`npm run dev:mock` 无需后端或数据库即可离线体验界面与错误态模拟，Mock 代码严格隔离，不打入生产或演示构建。

## 常用命令

环境要求：Node 24（见 `.nvmrc`）、npm 11。进入 `frontend/` 目录后按需执行（兼容 Windows PowerShell 5.1+ / Bash）：

```powershell
npm ci --ignore-scripts --no-audit --no-fund
npm run check
npm run build:demo
```

- `npm run check`：依次执行 TypeScript 类型检查、ESLint（`--max-warnings=0`）、Prettier 格式校验、Vitest 自动化测试（160 项）、静态禁外网校验（`check:no-network`）与标准生产构建（`npm run build`）。
- `npm run licenses`：读取锁文件与已安装包的依赖许可摘要。

## 最小 PWA 应用壳边界

`npm run build` 会输出 `manifest.webmanifest`、图标与 `service-worker.js`（挂载路径 `/app/`）：

- Service Worker **仅预缓存**构建期校验完整性的静态应用壳与图标，**绝不缓存** `/api/v1` 业务数据、Session 或 CSRF 状态，避免跨账号串号。
- 断网可打开应用壳；已确认离线时拦截发送，网络中断导致响应未知时保留原请求与幂等键，联网后由用户手动核对恢复。
- 新版本下载完成后不强制自动刷新，先提示用户核对未确认操作，再通过重新打开全部页面切换至新版。

## 目录结构

| 路径 | 职责 |
| --- | --- |
| `src/app/` | 应用根布局（左侧固定导航与极简顶栏）、PWA 状态提示与演示配置 |
| `src/pages/` | 真实三角色工作台（`RealStudentApp`、`RealOrderWorkspace`、`RealOrderAttachments`、`RealNotifications`、`RealAdminStats`）与独立 Mock 演示页 |
| `src/shared/api/rest/` | `StudentClient`（会话代次隔离、CSRF/身份重验恢复、建单幂等键与草稿状态管理） |
| `src/shared/forms/` | 与后端对齐的 Unicode 码点长度校验与表单归一化 |
| `src/test/` | 10 个 Vitest 测试套件（覆盖三角色业务、跨标签切号隔离、未知结果恢复、附件配额、通知轮询与禁外网校验） |
