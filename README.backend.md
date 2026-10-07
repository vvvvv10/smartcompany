# 后端（Backend）

6 个 Spring Boot 3 / Java 17 服务：一个网关、一个用户中心、四个业务域。
Spring Cloud Gateway 统一入口 + Nacos 注册/配置 + MySQL 8（6 个库）+ Redis 7。

> 部署、上线、迁移执行的操作手册在 [`deploy/DEPLOY.md`](deploy/DEPLOY.md)；
> 网关的请求生命周期、令牌与权限边界设计在根目录 [`README.md`](README.md)。

## 1. 服务一览

| 服务 | 路由前缀 | 职责 | 数据库 |
| --- | --- | --- | --- |
| `gateway` | `/api/**`（公网 8080） | 路由转发、令牌校验、身份/租户头注入、限流；**不碰数据库** | — |
| `user-center` | `/api/auth/**`、`/api/users/**`、`/api/admin/**` | 注册登录、用户与租户、权限体系、花名/权限审批、团队与组织、运营看板 | `user_center` |
| `crm` | `/api/crm/**` | 客户、联系人、商机、跟进记录；个人工作台与团队看板 | `crm` |
| `tms` | `/api/tms/**` | 运输订单、车辆、司机；成本统计与派车推荐 | `tms` |
| `wms` | `/api/wms/**` | 仓库、库存、出入库记录 | `wms` |
| `oms` | `/api/oms/**` | 款号商品、多渠道订单（含明细行）、销售统计 | `oms` |

## 2. 一次请求的链路

客户端 → **网关（`AuthGlobalFilter`，order=-100）** → 业务服务，三步缺一不可：

1. **剥离伪造**：无条件下掉客户端带来的 `X-User-Id` / `X-Tenant-Id` / `X-User-Roles`；
2. **校验**：白名单（`/api/auth/captcha|register|login|refresh`、`/actuator/health/**`）与 CORS 预检放行，其余必须带有效 Bearer 令牌（网关本地验签 + Redis 吊销黑名单）；
3. **注入**：`X-User-Id`（sub）、`X-User-Roles`、`X-Tenant-Id`（JWT `tid`）、`X-Trace-Id` 写回请求头——下游**只信网关写入的值**。

业务服务的统一约定：

- **权限与租户在服务内做**：`require(roles, permission)` 校验权限点；`TenantFilter → TenantContext`
  把当前租户塞进每一条 SQL 的 `tenant_id`（行级隔离）；
- **错误体**统一 `{code, message, traceId}`，缺权限点返回 403 并写明「缺少权限点: xxx」；
- **团队负责人继承**：有效权限 = 自身角色权限 ∪ 所带队子树在职成员权限并集（`PermissionService` 递归求并）。

## 3. 令牌

| 令牌 | 形态 | 有效期 | 存储 |
| --- | --- | --- | --- |
| Access Token | JWT（`jti`/`sub`/`roles`/`tid`） | 15 分钟 | 无状态，网关本地验签 |
| Refresh Token | 随机串 | 15 天 | Redis，**用后即焚**（rotate 先删再签） |

登出 / 改密把 `jti` 写进吊销黑名单，TTL = 令牌剩余寿命，过期自动清理。

## 4. 各服务功能清单

### gateway

- 路由转发：本地 `application.yml` 兜底 + Nacos `gateway-routes.yaml` 热刷新，StripPrefix；
- 按身份的 Redis 限流（`RequestRateLimiter` + principal key resolver）；
- 令牌校验与吊销、白名单、CORS、可信头清洗注入；
- `/actuator/health`、`/actuator/gateway`——供 web「网关工作台」展示服务健康与路由表。

### user-center（认证与管理台的全部 API）

**认证 `/api/auth`**：`captcha` 图形码、`register`、`login`、`refresh`、`logout`、`revoke`。

**我的 `/api/users/me`**：资料（工号、公司 = 租户字典名、花名、角色、权限点）、
`organizations` 组织归属、`teams`（负责人可见自己带队的子树）、`permission-catalog` 权限目录（code → 中文名）。
花名变更**只能走申请**：`POST /me/nickname/request` → 管理员审批；直接改名的 `PUT /me/nickname`
永久返回 403 `nickname_approval_required`。

**管理 `/api/admin`**：

- `users`：分页搜索、**建号**（团队/租户下拉，缺省 `alibaba` = 示例集团集团，服务端 `requireActive` 复验）、分配角色、启停、租户切换、`dashboard` 运营看板；
- `tenants` 租户字典（`alibaba` 排最前）、`teams` 团队（负责人/成员/防环四不变量）；
- `role-list` / `roles` / `role-matrix` / `permissions` / `catalog`：角色与权限点配置（全数据化，勾选即生效）；
- `nickname-requests` 花名审批（approve/reject）、`permission-requests` 权限自助申请审批；
- `gateway` 网关工作台数据（服务健康/路由表）、权限中心概览与组织架构。

**权限模型**：`permissions → role_permissions → user_roles` 三层全数据；`ADMIN` 在代码里硬兜底全部权限点
（配置页把管理员勾光也锁不死自己）；`team:view` 等派生权限**不入表**，由身份推导（详见根 README §4.1）。

**多租户**：`tenants` 字典（默认 `alibaba` = 示例集团集团），`users.tenant_id` 进 JWT `tid`；
38 号迁移已把存量 `default` 全部改写为 `alibaba`，列默认值同步切换。

### crm

- 客户：列表（搜索/分页/筛选）、`/brief` 下拉枚举、新建/删除（级联联系人、商机、跟进）；
- 联系人、跟进记录（挂在客户下）；商机与阶段推进 `/{id}/stage`；
- `/workbench` 个人工作台、`/dashboard` 团队看板。

### tms

- 运输订单（列表/状态流转）、车辆、司机；
- `/api/tms/dashboard/cost` 成本统计、`/api/tms/dashboard/recommend?orderId=` 派车推荐。

### wms

- 仓库、库存、出入库记录（`/stock` 登记），`/api/wms/dashboard` 看板。

### oms

- 款号商品、订单（提交时组装明细 `items`），`/api/oms/dashboard` 销售额与爆款款号统计。

## 5. 数据库与迁移

- 6 个库；`deploy/initdb/*.sql` 只在 MySQL **首次初始化**（`data/` 为空）时自动执行，
  已有实例要手动导入（`DEPLOY.md` §5.1）。
- 关键迁移：`34` 索引优化（+8/-4，按业务 SQL 模式补齐）、`35`/`37` 权限命名与业务动作名、
  `36` 多租户（13 张业务表 `tenant_id` + `idx_tenant`）、`38` 默认租户 `default → alibaba`
  （存量改写 + 列默认值 + 种子同步）。
- 所有业务表 `tenant_id NOT NULL DEFAULT 'alibaba'`，`idx_tenant(tenant_id, id)` 对齐
  「顶层列表按租户扫 + 按 id 倒序分页」的查询模式。

## 6. 部署与运维

- 编排：`deploy/docker-compose.yml`（mysql / redis / nacos + 6 个服务，镜像 `<服务名>:1.0.0`）；
- 本机工具：`deploy/ssh.py`（远程执行）、`deploy/upload.py`（推文件/源码）；
- Java 服务上线：推源码 → 服务器 `docker build -t <svc>:1.0.0 .` →
  `docker compose up -d --force-recreate <svc>`（镜像 tag 不变时必须 `--force-recreate`，否则容器不重建）；
- 排障、字符集、常见错误见 `DEPLOY.md` 的速查表。

## 7. 相关文档

- 根目录 [`README.md`](README.md)：网关设计说明（请求生命周期、令牌、权限边界、上线检查清单）
- [`deploy/DEPLOY.md`](deploy/DEPLOY.md)：部署手册、迁移执行、排障表
- 按端功能说明：[`README.web.md`](README.web.md) · [`README.android.md`](README.android.md) · [`README.ios.md`](README.ios.md)
