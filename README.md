# smartwork · 企业工作台

Spring Cloud Gateway 统一入口 + 用户中心 + 四个业务域（CRM / TMS / WMS / OMS），
配套 Web 管理台与移动端工作台。面向 10–50 个服务的中小规模内网系统。

📖 **在线文档**：<https://vvvvv10.github.io/smartwork/>（GitHub Pages，源码在 [`site/`](site/)）

---

## 1. 模块地图

| 模块 | 形态 | 端口 / 路径 | 职责 |
| --- | --- | --- | --- |
| `gateway` | Spring Cloud Gateway（WebFlux） | 8080，公网 `/api/**` | 路由转发、令牌校验、身份与租户头注入、限流。**不碰数据库** |
| `user-center` | Spring Boot 3 / Java 17 | 8081，`/api/{auth,users,admin}/**` | 注册登录、用户与租户、权限体系、花名与权限审批、团队与组织、运营看板 |
| `crm` | 同上 | 8083，`/api/crm/**` | 客户、联系人、商机、跟进记录；个人工作台与团队看板 |
| `tms` | 同上 | 8084，`/api/tms/**` | 运输订单、车辆、司机；成本统计与派车推荐 |
| `wms` | 同上 | 8085，`/api/wms/**` | 仓库、库存、出入库登记、看板 |
| `oms` | 同上 | 8086，`/api/oms/**` | 款号商品、多渠道订单（含明细行）、销售统计 |
| `web-console` | React + Vite | `/workbench-console/` | Web 管理台：用户、租户、团队、角色权限、审批、集成配置 |
| `workbench-antd` | antd-mobile + Capacitor | Android APK | 移动端工作台（定版客户端） |
| `workbench-ios` | React Native (Expo) | — | iOS 端工作台 |
| `deploy` | docker compose + 脚本 | — | 编排、迁移、本机运维工具 |

基础设施：Nacos（注册与配置）、MySQL 8（6 个库）、Redis 7。

<details>
<summary>请求链路（点击展开）</summary>

```
客户端 → 网关 AuthGlobalFilter(order=-100) → 业务服务 → MySQL / Redis
```

三步缺一不可：

1. **剥离伪造**：无条件下掉客户端带来的 `X-User-Id` / `X-Tenant-Id` / `X-User-Roles`；
2. **校验**：白名单与 CORS 预检放行，其余必须带有效 Bearer 令牌（本地验签 + Redis 吊销黑名单）；
3. **注入**：把校验出的身份写回同名请求头——下游**只信网关写入的值**。

业务服务内统一约定：`require(roles, permission)` 校验权限点；`TenantFilter → TenantContext`
把租户塞进每条 SQL 的 `tenant_id` 做行级隔离；错误体统一 `{code, message, traceId}`。

</details>

## 2. 仓库结构

```
├── gateway/            统一网关（WebFlux）
├── user-center/        用户中心与权限体系
├── crm/ tms/ wms/ oms/ 四个业务域服务
├── web-console/        Web 管理台
├── workbench-antd/     移动端工作台（antd-mobile + Capacitor）
├── workbench-ios/      iOS 端工作台
├── deploy/             docker compose、initdb 迁移、ssh/upload 工具
├── scripts/            数据核查等运维脚本
├── site/               GitHub Pages 文档站
└── docs/               设计稿与评审记录
```

## 3. 快速开始

### 3.1 后端

```bash
# 需要 JDK 17、Maven、Nacos、MySQL 8、Redis 7
cd user-center && ./mvnw spring-boot:run     # 8081
cd gateway     && ./mvnw spring-boot:run     # 8080
```

各服务的数据库连接与 JWT 密钥走环境变量（见 §5），本地开发先导出：

```bash
export MYSQL_HOST=127.0.0.1 MYSQL_PORT=3306 MYSQL_USER=root MYSQL_PASSWORD=...
export REDIS_HOST=127.0.0.1 REDIS_PORT=6379
export JWT_SECRET=...        # ≥ 32 字节，gateway 与 user-center 必须一致
```

### 3.2 客户端

```bash
# 移动端（antd-mobile 版）：门禁 = vitest + tsc + vite + cap sync + gradle assembleDebug
cd workbench-antd && ./build.sh

# 单元测试（antd 版需显式指定任务）
cd workbench-antd/web && npx vitest run
cd workbench-antd/web/android && gradle :app:testDebugUnitTest
```

### 3.3 部署

上线、迁移执行、排障速查见 [`deploy/DEPLOY.md`](deploy/DEPLOY.md)。要点：

- 镜像 tag 不变时必须 `docker compose up -d --force-recreate <svc>`，否则容器不会重建；
- `deploy/initdb/*.sql` **只在 MySQL 首次初始化时自动执行**（`data/` 为空），已有实例要手动导入；
- 服务器内存有限，构建须持 `flock /var/lock/stack-build.lock` 并等负载，1.7G 内存下并发构建会雪崩。

## 4. 文档

| 文档 | 内容 |
| --- | --- |
| [在线文档站](https://vvvvv10.github.io/smartwork/) | 模块、链路、权限模型、部署、客户端 |
| [`site/design.html`](site/design.html) | 网关设计：Filter 顺序与踩坑、Nacos 路由示例、权限分层 |
| [`README.backend.md`](README.backend.md) | 后端：服务一览、令牌、各服务功能清单、迁移 |
| [`README.web.md`](README.web.md) | Web 管理台：页面与权限点显隐、认证流 |
| [`README.mobile.md`](README.android.md) | 移动端（workbench-antd）：功能、构建、壳层约定 |
| [`README.ios.md`](README.ios.md) | iOS 端：功能与构建 |
| [`deploy/DEPLOY.md`](deploy/DEPLOY.md) | 部署手册、迁移执行、排障表 |

## 5. 凭据与安全

**仓库里不放任何真实凭据。** 需要的东西按下面取：

```bash
cp deploy/.env.example deploy/.env    # .env 已被 .gitignore 排除
```

| 变量 | 用途 |
| --- | --- |
| `MYSQL_ROOT_PASSWORD` / `REDIS_PASSWORD` | 中间件口令，compose 以 `${VAR}` 注入各服务 |
| `JWT_SECRET` | 令牌签名密钥，**gateway 与 user-center 必须一致**，长度 ≥ 32 字节 |
| `DEPLOY_HOST` / `DEPLOY_USER` / `DEPLOY_PASSWORD` | 本机运维脚本连服务器用；缺 `DEPLOY_PASSWORD` 时脚本直接报错退出 |

三条硬规矩：

1. **`JWT_SECRET` 泄露等于任何人可自行签发令牌**——与数据库口令同级对待；
2. 签名密钥库（`*.jks` / `*.keystore` / `*.p12` / `*.pem`）已在 `.gitignore` 中，
   一旦入库即视为泄露，必须轮换而不是删文件；
3. 凭据一旦进过 git，**改代码抹不掉历史**——推送过的提交任何人都能检出，只能轮换。

## 6. 上线前检查清单

- [ ] `JWT_SECRET` 走环境变量 / KMS，长度 ≥ 32 字节，没有进 Git
- [ ] 数据库与 Redis 口令只经环境变量注入，compose 文件里没有明文
- [ ] 网关前面有 LB 并由它重写 `X-Forwarded-For`；网关不要信任客户端直接传的那个头
- [ ] 业务服务间不绕网关（东西向走 RPC 直连 + 服务鉴权），避免环路
- [ ] 网关限流阈值与网关内服务分开配置，不与登录/验证码共用配额
- [ ] actuator 不把 `gateway` 端点暴露到公网
- [ ] 新增 `deploy/initdb/*.sql` 已在已有实例上手动导入过
- [ ] `deploy/.env` 未被提交，`git ls-files deploy/.env` 输出为空

## 7. 相关链接

- 接口与模块细节：在线文档站 <https://vvvvv10.github.io/smartwork/>
- CRM 客户/商机领用限制的设计稿（仅设计，未改代码）：
  [`workbench-antd/docs/crm-visibility-design.md`](workbench-antd/docs/crm-visibility-design.md)