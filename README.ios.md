# iOS 端（workbench-ios）

个人工作台的 **React Native (Expo)** 客户端——与安卓端（`workbench-antd`）同源，
一套 TypeScript 代码出 iOS 包。

- 演示账号：`13800000006` / `DEMO_PASSWORD`
- 默认服务地址 `workbench.example.com:8080`，登录页可切换/新增

## 1. 功能（3 个 Tab）

| Tab | 内容 | 权限点 |
| --- | --- | --- |
| **概览** | 按权限分段：**业务数据**（我的客户/跟进中/商机/待办 + 最近客户与跟进）与**运营数据**（注册/活跃/角色统计 + 注册趋势） | 业务 `crm:read`；运营 `user:list` + `dashboard:view` |
| **审批** | **只读进度查询**：我的申请（状态跟踪）+ 待我审批列表；提交/批准/驳回在 Web 管理台 | 待我审批需 `nickname:review` |
| **我的** | 身份卡、账号信息（**工号 / 公司 / 花名 / 服务地址**，公司对残留 `default` 兜底显示「未知」）、关于、退出登录 | — |

- **登录**：账号密码 + 服务地址选择器（历史配置可选、可新增，运行时切换）。
- **认证**：token 持久化（AsyncStorage）+ 401 自动刷新 + 并发刷新去重；
  Access Token 15 分钟 / Refresh Token 15 天用后即焚。
- **权限守卫与 web/安卓一致**：唯一依据 `/api/users/me` 的 `permissionCodes`，
  缺权限给 403 占位说明而不是报错。
- **改花名与审批动作只读**：App 不提供提交/批准/驳回——改名回 web「我的身份」提交申请、
  在 web「审批中心」批准生效，App 只看进度。

## 2. 与安卓端的差异

| | workbench-antd | workbench-ios（本工程） |
| --- | --- | --- |
| Tab | 概览 / 审批 / 我的 + **可选 TMS/WMS/OMS 模块**（本机开关，默认隐藏） | 仅 概览 / 审批 / 我的三个 Tab，**暂无 TMS/WMS/OMS 模块页签** |
| 身份字段（工号/公司/花名）、审批流、服务地址切换、403 占位 | ✅ | ✅ 一致 |
| 移动端只读边界 | ✅ | ✅ 一致 |

管理台功能（用户管理、权限中心、团队管理）两端都**不提供**——只在 Web 端。

## 3. 构建

```bash
cd workbench-ios
npm install          # Node >= 22（Expo SDK 57 要求）
npx expo start       # Metro 开发
npx expo run:ios     # 本机构建运行（需 Xcode，仅 macOS）
npx expo run:android # 也可以出安卓包（需 Android SDK + NDK 27）
npx expo lint        # 代码检查
```

本机没有 Xcode 时出 iOS 包：**EAS 云构建**（`npx eas-cli build --platform ios --profile production`，
先 `npx eas-cli login` 登录 Expo 账号；无需本地 Xcode——方案待定）。
工程与移植细节见 [`workbench-ios/README.md`](workbench-ios/README.md)。

## 4. 相关文档

- 后端接口：[`README.backend.md`](README.backend.md)
- Web 管理台：[`README.web.md`](README.web.md) · 安卓端：[`README.android.md`](README.android.md)
