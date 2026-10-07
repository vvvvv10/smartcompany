# workbench-ios

个人工作台的 React Native (Expo) 版本——从 `workbench-android` 移植，一套代码同时出 iOS 和 Android 包。

## 功能

与安卓端完全一致：

- **登录**：账号密码 + 服务地址选择器（地址可运行时切换、可新增历史配置）
- **概览**：按权限分段展示
  - 运营数据（`user:list` + `dashboard:view`）：注册/活跃/角色统计 + 注册趋势
  - 业务数据（`crm:read`）：我的客户/跟进中/商机/待办 + 最近客户与跟进
- **审批**（只读进度查询）：我的申请状态 + 待我审批列表（需 `nickname:review`）；提交/批准/驳回在 Web 管理台
- **我的**：身份卡、账号信息（工号/公司/花名/服务地址）、关于、退出登录

认证：token 持久化（AsyncStorage）+ 401 自动刷新 + 并发刷新去重。

## 技术栈

- Expo SDK 57 / React Native 0.86 / React 19
- TypeScript
- AsyncStorage（会话与服务地址持久化）
- 无第三方 UI 库（核心组件 + 自定义样式，与安卓端同一套色板）

## 本地开发

```bash
# 需要 Node >= 22（Expo SDK 57 要求）
npm install
npx expo start          # 启动 Metro bundler
npx expo run:android    # 构建并运行 Android（需要 Android SDK + NDK 27）
npx expo run:ios        # 构建并运行 iOS（需要 Xcode，仅 macOS）
```

## 构建 iOS 包

本机没有 Xcode 时，有两种方式出 iOS 包：

1. **EAS 云构建**（推荐，无需本地 Xcode）：
   ```bash
   npx eas-cli build --platform ios --profile production
   ```
   需要 Expo 账号（`eas login`）。

2. **本地 Xcode**：`npx expo run:ios` 或 `npx expo prebuild -p ios` 后用 Xcode 打开 `ios/` 目录。

## 目录结构

```
src/
  api/        # DTO 类型 + fetch 客户端（auth/刷新/错误处理）
  store/      # 会话与服务地址持久化（AsyncStorage）
  ui/         # 色板、共享组件、标签与格式化
  feature/
    auth/     # 登录页 + 认证上下文
    main/     # 底部 3 Tab 主框架 + 审批徽标
    overview/ # 概览页
    approvals/# 审批页
    me/       # 我的页
```
