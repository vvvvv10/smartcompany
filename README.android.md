# 移动端（workbench-antd）

个人工作台的定版客户端：**antd-mobile 5 + Vite + Capacitor 6**，只发 Android 包。
包名 `com.example.workbench.antd`，与已下线的原生 Compose 版（`workbench-android`）包名不同，可并存。

> 原生 Compose 版与 `crm-android` 已删除（工作台以本工程为准）。
> 历史提交里仍可取回：`git checkout <commit> -- workbench-android`。

## 1. 功能

固定三个页签（按权限点显隐）：

| 页签 | 入口条件 | 内容 |
| --- | --- | --- |
| **系统管理** | 需 `dashboard:view` 或管理员 | 运营数据（用户/活跃/注册趋势）、网关工作台（服务健康与路由表）、我的待办 |
| **通讯录** | 始终可见 | 企业通讯录，按团队/角色查人 |
| **审批** | 始终可见，徽标显示待办数 | 我的申请（历史 + 在途高亮）、待我审批（待审批/全部分段，批准与驳回） |
| **我的** | 始终可见 | 身份卡、账号信息、**功能模块开关**、待办提醒开关、服务地址、退出登录 |

四个**可选业务模块**：TMS / WMS / OMS / CRM。页签显隐 = **本机开关 ∧ 权限点**——
关掉 CRM 不应把 TMS 一起带走，四组开关彼此独立，且只存在本机（退出登录不清除）。

无权限的模块**不占页签位置**，在「我的」里对应行显示「无权限」并提供「申请权限」，
提交后走与花名申请同一套两级审批，通过后页签自动出现。

## 2. 壳层约定

- **跨域**：走 Capacitor 的 `CapacitorHttp`，网关无需为移动端单独放行 CORS。
- **状态栏**：WebView 全屏化（`LAYOUT_FULLSCREEN`）+ 状态栏透明，页头渐变一路铺进系统栏；
  页头 `position: sticky` 吸顶，否则滚动后白色状态栏图标会飘到浅灰内容上。
  状态栏高度由原生 `SysBarsPlugin.statusBarHeight()` 上报（Chrome 在 Android 上
  `env(safe-area-inset-top)` 只反映屏幕挖孔、不含状态栏），网页侧除以 `devicePixelRatio`
  换算成 CSS px 写入 `--wb-status-h`。
- **触感**：`@capacitor/haptics`，按语义封装在 `src/lib/haptics.ts`
  （Light/Medium/Select/Success/Error/Warning），提示与触感触绑在 `src/lib/toast.ts`——
  两者都做了静默降级，浏览器预览或系统关闭振动都不影响业务。
- **待办提醒**：前台服务轮询 + 通知点击直达那条单子（深链落点由 `NotifyPlugin` 暂存，
  Web 起来后一次性取走）。
- **错误态**：`antd-mobile` 的 `ErrorBlock` 没有 `onRetry`，故自建 `ErrorBox`（插画 + 重新加载）；
  后端重启不必杀掉 App 重开。

## 3. 构建

门禁一次跑完单测、类型检查、web 构建、Capacitor 同步与 APK 打包：

```bash
cd workbench-antd && ./build.sh
# 单测 → tsc + vite 构建 → npx cap sync android → gradle assembleDebug
```

产物：`web/android/app/build/outputs/apk/debug/app-debug.apk`

需要单独跑时：

```bash
cd web && npx vitest run                                  # 单元测试
cd web && npx tsc --noEmit                                # 仅类型检查
cd web/android && gradle :app:testDebugUnitTest           # JUnit（须显式指定任务）
```

> `cap sync` 会改写 `android/capacitor.build.gradle` 与 `capacitor.settings.gradle`
> （注册原生插件），这两个生成文件需要随依赖变更一起提交，否则原生侧编不过。

## 4. 发布

上传后 `docker cp` 进 `app-web-console:/usr/share/nginx/html/`（该容器**不挂卷**，
重建会丢，需按 [`deploy/DEPLOY.md`](../deploy/DEPLOY.md) 重新推）。
APK 不是可复现构建，每次打包 md5 都变，发布时把当次大小/md5 记进 DEPLOY.md 下载表。

## 5. 演示账号

登录页提供一键填入：`13800000006` / `DEMO_PASSWORD`（管理员）、`13800000006` 等业务账号。
**这些口令是测试环境的真实凭据**，仅用于演示环境；正式部署前应轮换或改为从接口下发。

## 6. 相关文档

- 后端接口：[`README.backend.md`](../README.backend.md)
- Web 管理台：[`README.web.md`](../README.web.md) · iOS：[`README.ios.md`](../README.ios.md)
- CRM 客户/商机领用限制设计稿（仅设计，未改代码）：
  [`docs/crm-visibility-design.md`](docs/crm-visibility-design.md)