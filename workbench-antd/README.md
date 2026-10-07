# workbench-antd —— 用 antd-mobile 做的安卓工作台客户端

个人工作台的**第二个安卓客户端**：UI 用 [Ant Design Mobile](https://mobile.ant.design/)
（真组件，非仿制），装在 Capacitor 的 WebView 壳里，复用同一套后端
（`http://workbench.example.com`）与同一份业务口径。

功能与原生版 `workbench-android` 对齐：

| Tab | 内容 | 模块开关 |
| --- | --- | --- |
| 系统管理 | 运营数据（用户/角色/注册趋势）、网关工作台（健康/路由/服务）、我的待办 | 固定 |
| TMS | 运力概览、运单状态环形图、运单/车辆/司机 | 默认关 |
| WMS | 库存概览、各仓库存 TOP5、仓库/库存明细/出入库记录 | 默认关 |
| OMS | 经营概览、订单状态环形图、渠道分布、热销款 TOP5、订单（含明细）/商品 | 默认关 |
| CRM | 客户池 / 商机池、全员口径说明 + 「只看我的」、客户详情、阶段流转 | 默认关 |
| 审批 | 我的申请（含已办结）、申请列表（待审批 / 全部）、手机批准与驳回 | 固定 |
| 我的 | 身份与权限点、四个功能模块开关、服务地址、退出登录 | 固定 |

Tab 顺序与原生版 `workbench-android` 一致。四个业务模块都是**只读看板**——后端
这三块只提供 GET（写操作在 web 管理台），所以页面没有提交按钮。

## 为什么是「WebView 壳 + Capacitor」而不是原生

需求指定用 antd mobile 框架，而 antd-mobile 是 React 的 **H5** 组件库，没有原生
Android 版。Capacitor 提供原生壳 + 插件，UI 就是官方 antd-mobile 组件。

## 跨域：靠 CapacitorHttp，不动网关

线上网关**没有放行 CORS**（`OPTIONS /api/users/me` 直接 403）。所以开启
`CapacitorHttp` 插件（`capacitor.config.ts`）：POST/PUT 等由原生层发出，GET 走
Capacitor 的本地拦截器由原生代发，两者都不过浏览器同源策略。这样**不需要改网关**
（网关是别的会话在动的），也不用退回到"自己写原生代理"的方案。

## 明文 HTTP

后端是 `http://`，Android 9+ 默认禁明文。`app/src/main/res/xml/network_security_config.xml`
全局放行，理由与原生版 `workbench-android` 一致：服务地址是运行时由用户填的，
静态白名单挡不住内网其它服务器，详见该文件里的注释。

## 目录

```
web/                     React + Vite + antd-mobile 应用（壳内页面）
  src/lib/               接口层与纯逻辑（types / api / approvals / crm / format）
  src/pages/             Login / Overview / Crm / Approvals / Me
  src/components/        通用组件（卡片、指标、空态、图标）
  src/test/              vitest 单测（14 项）
  android/               Capacitor 生成的安卓工程
scripts/cdp-eval.cjs     通过 CDP 在 WebView 里执行 JS（调试用，见文件头说明）
```

## 构建

```bash
./build.sh                              # 单测 + web 打包 + cap sync + debug APK
./build.sh --skip-tests                 # 跳过单测
./build.sh :app:assembleRelease:app:assembleDebug   # 发布双包
```

产物：`web/android/app/build/outputs/apk/debug/app-debug.apk`（约 3.9MB）

不用 gradle wrapper：`services.gradle.org` 在这个网络环境只有 ~100B/s，wrapper
下载会卡死；脚本直接用本地 Gradle 8.7（与 `workbench-android` 同一个）。

## 开发

```bash
cd web
npm run dev        # 浏览器里调试（原生壳外没有 CapacitorHttp，走的是普通 fetch，
                   #  所以本地调试需要一个放行 CORS 的后端或代理）
npm test           # vitest
```

## 实测结论（模拟器 crm_pixel6）

- 管理员（风清扬）：七个 Tab 全通；运营数据 36 用户、网关 7 条路由、申请列表 44 条、
  TMS 4 运单/3 车/3 司机、WMS 9 仓/库存 3400、OMS 5 单/本月 ¥714/热销款 K2601
- 手机批花名：批准/驳回都验过，驳回理由会回写到申请人的「我的申请」
- 普通用户（墨言，5 个权限点）：系统管理无任何分段 → 给出可读的缺权说明；
  商机不显示「推进阶段」并说明缺 `crm:write`
- 模块开关存在 WebView localStorage，**跨版本安装保留**（装新包后 Tab 仍在）；
  注意 localStorage 是异步落盘，"改完立刻 force-stop" 这种强杀会丢一次写入
- **已知后端缺口**：墨言没有 `crm:read` 也能拉到租户全量 13 个客户 —— CRM 列表接口
  本身没做权限校验，需要后端补（与原生版是同一个问题）