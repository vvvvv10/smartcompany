# 部署手册

目标机：`workbench.example.com`（root）· CentOS 8 · 2 核 · 1.7G 内存 · 40G 磁盘 · 部署目录 `/opt/stack`

## 1. 已安装组件与端口矩阵

| 组件 | 版本 | 宿主机映射 | 占用内存 | 说明 |
| --- | --- | --- | --- | --- |
| Docker Engine | 26.1.3 | - | - | 阿里云源安装，开机自启 |
| Docker Compose | v2.27.0 | - | - | compose plugin |
| MySQL | 8.0.36 | `127.0.0.1:3306` | 90 MB | 库：`nacos_config`、`user_center` |
| Redis | 7.2-alpine | `127.0.0.1:6379` | 10 MB | maxmemory 192m，allkeys-lru |
| Nacos | 2.3.2 standalone | `127.0.0.1:8848/9848/9849` | 306 MB | 元数据存 MySQL |
| user-center | 1.0.0 | `127.0.0.1:8081` | 209 MB | 不直接对外 |
| api-gateway | 1.0.0 | **`0.0.0.0:8080`** | 235 MB | 唯一对外端口 |
| web-console | 1.0.0 | `127.0.0.1:8082` | ~15 MB | React + antd 控制台，经网关 `http://ip:8080/` 访问 |

实测容器总内存约 **850 MB**，加上系统约 1.1G，仍有余量（配了 2G swap 兜底但基本没用到）。
之前担心的"1.7G 跑不动"没有发生——Nacos 经 `-Xmx384m` 调优后只占 306MB。

## 1.1 前端控制台（React + antd）

浏览器打开 `http://<ip>:8080/` 即可（和 API 同一个入口，由网关兜底路由转发到前端容器，因此不存在跨域）。

| 页面 | 路径 | 需要角色 | 说明 |
| --- | --- | --- | --- |
| 登录 | `/login` | 公开 | 测试账号 `13800000006` / `DEMO_PASSWORD` |
| 注册 | `/register` | 公开 | 走验证码流程，注册成功直接签发令牌登录 |
| 网关工作台 | `/` | ADMIN | 默认 Tab：路由明细（匹配路径 / 限流 / 目标状态）、服务发现、网关运行健康、免鉴权白名单，取数走 `GET /api/admin/gateway/workbench` |
| 数据看板 | `/` 的第二个 Tab | ADMIN | 用户总数/启用/禁用/今日新增/7日新增/30天活跃 + 注册趋势折线 + 角色分布饼图 |
| 我的身份 | `/profile` | 登录即可 | 展示网关清洗注入后的身份头，附伪造头的 curl 对照命令；花名在这里只能**提交变更申请**（`nickname:request`），审批通过前身份不变 |
| 用户管理 | `/users` | ADMIN | 搜索（手机号 / 姓名 / 花名）、分页、**新增用户**（只填手机号 + 姓名 + 身份证，花名按姓名自动生成、重名加序号，初始密码由服务端随机生成并**只在创建响应里回一次**，角色缺省 USER；**所属团队**是可选的第四项，选了就在建号后自动入队，不选则此人只有角色权限）、列表展示姓名与**脱敏**身份证、启用禁用开关、角色分配（ADMIN / USER） |
| 权限中心 | `/permissions` | `role:manage` | 五个页签：概览 / 角色管理 / 授权矩阵 / **团队** / **组织架构**。团队只做展示——权限点仍只勾给角色，团队有效权限 = **整棵子树**在职成员的并集，负责人自动拥有这一份；组织架构是左树右面板，节点上标权限数、点开看按模块分组的明细，**选中团队可就地「新增下级团队」「删除」**（选中虚拟根则是「新建顶层团队」），加人减人与调上下级仍只在「团队」页签做 |
| 审批中心 | `/approvals` | `nickname:review` | 花名变更申请的批准 / 驳回；驳回必须填理由，批准走「先抢占再改名」的同事务，撞名则整体回滚 |
| 我的团队 | `/my-team` | `team:view`（**只在是团队负责人时才有**） | 负责人的**只读**视图：左树右面板，展示自己带队节点的整棵子树（本队 + 下级队）及其成员的角色、自身权限与有效权限。**兄弟团队、上级团队、其它中心不返回**——过滤在服务端做，前端不参与；没有任何写入口，调 `PUT`/`DELETE` 直接 405 |

左侧菜单把「我的身份 / 我的团队 / 用户管理 / 审批中心 / 权限中心」收进同一个父菜单
**管理台**（默认展开）。这只是导航层级的合并：URL、页面组件、上表每一行的权限点
一个都没改，菜单显隐与路由守卫仍然读同一份 `permissionCodes`。

角色区分完全由后端判定：管理端接口读网关注入的 `X-User-Roles`，非 ADMIN 直接 403，
前端只做菜单和路由的显示控制，**不作为安全边界**。

> **团队负责人的继承也是后端判定的**：`PermissionService.resolve()` 会把「本人角色权限 ∪
> 所带队节点**整棵子树**的在职成员权限并集」一起返回（子树用 `WITH RECURSIVE` 沿
> `teams.parent_id` 取），`/api/users/me` 的 `permissionCodes` 与各管理端接口的
> `firstMissing()` 走的是同一份结果——所以「菜单里看得见」和「接口调得动」不会劈叉。
> 子树里有 ADMIN 时按「成员拥有全部权限」处理，lead 因此也拿到全部（与「管理员拥有所有全部」
> 是同一条规则的自然延伸）。`userId` 由 `PermissionService` 自己从网关注入的 `X-User-Id` 取，
> 各 Controller 的 `require(roles, permission)` 签名不用挂这个参数。
>
> 层级只在写入时校验：上级不能是自己的下级（409 `team_cycle`），有下级的团队不能删
> （409 `team_has_children`），同队只能有一个负责人。**父子关系挂在子节点上**——
> 想让父团队失去子树，要断开的是子团队的「上级」，把父节点自己的 `parentId` 清空没有影响。

### 开发验证码开关（重要）

`user-center` 当前带了 `REVEAL_CAPTCHA=true`，会把短信验证码直接返回给前端，
注册页因此能看到并自动填充验证码。**这是为了演示注册流程**，生产环境必须在
`docker-compose.yml` 里删掉这一行，让 `AuthController` 回落到不发码的行为。

## 1.2 移动版（原生 Android）

仓库里有两个可共存安装的 App：

| 包 | 包名 | 定位 | 下载地址 |
|---|---|---|---|
| `crm-android/` | `com.example.crm` | CRM 业务端（客户/商机/回款） | `http://workbench.example.com/crm-android.apk` |
| `workbench-android/` | `com.example.workbench` | 个人工作台（待办/审批/运营） | `http://workbench.example.com/workbench.apk` |

两者签名不同、DataStore 不同（`crm_session` / `wb_session`），装在一起互不干扰。
`workbench-android` 的发布流程与下面完全一致，只是把路径换成
`workbench-android/app/build/outputs/apk/...`、目标文件换成 `workbench.apk` /
`workbench-debug.apk`（详见 `workbench-android/README.md`）。

手机浏览器打开 `http://workbench.example.com/crm-android.apk` 直接下载安装（**release，
11.5MB / 12042716 字节，正式签名**）。debug 备用包在
`http://workbench.example.com/crm-android-debug.apk`（17.5MB / 18362342 字节）。
两个包签名不同，不能互相覆盖安装。
演示账号同 web 端：`13800000006` / `DEMO_PASSWORD`。

`workbench-android` 同理（已发布 2026-10-03，视觉动效升级版）：

```bash
# 本机（在 workbench-android/ 目录下）
./build.sh :app:assembleRelease :app:assembleDebug
/tmp/rs-push.sh app/build/outputs/apk/release/app-release.apk  /tmp/workbench.apk
/tmp/rs-push.sh app/build/outputs/apk/debug/app-debug.apk      /tmp/workbench-debug.apk

# 服务器
docker cp /tmp/workbench.apk       app-web-console:/usr/share/nginx/html/workbench.apk
docker cp /tmp/workbench-debug.apk app-web-console:/usr/share/nginx/html/workbench-debug.apk
```

| 下载地址 | 大小 | md5 |
|---|---|---|
| `http://workbench.example.com/workbench.apk`（release） | 11990977 | `17d37ce3919eb4a1f9be7cb7911b0e36` |
| `http://workbench.example.com/workbench-debug.apk` | 18366112 | `6cc2d4837320c89290255df9cac7712d` |
| `http://workbench.example.com/crm-android.apk`（release） | 11977180 | `58694886704f82ec51b5b880cd7604e5` |
| `http://workbench.example.com/crm-android-debug.apk` | 18207586 | `9f794c23cdf6ce77630820bb669f1d02` |

> 2026-10-02 全量重打：**移动端只读化**（管理台写操作从两端移除）后的四个包。
> 2026-10-03 重打 workbench 两包：**视觉动效升级**（数字滚动/错落入场/零依赖 Canvas 图表）
> + 模拟器实测修复（光斑撑高页头、TMS/WMS 看板契约错位全 0）；crm 两包仍为 10-02 版本。

## 1.3 TMS 运输管理 & WMS 仓储管理

两个独立 Spring Boot 服务，完全对齐 crm 的结构（Entity/Payload/Service/Controller + 统计看板）：

| 服务 | 端口 | 数据库 | 网关路由 | 功能 |
|---|---|---|---|---|
| `tms/` | 8084 | `tms` | `/api/tms/**` | 运输订单/车辆/司机 + 统计看板 |
| `wms/` | 8085 | `wms` | `/api/wms/**` | 仓库/库存/出入库记录 + 统计看板 |

**部署**：
```bash
# 服务器
cd /opt/stack/apps/tms && docker build -t tms:1.0.0 .
cd /opt/stack/apps/wms && docker build -t wms:1.0.0 .
cd /opt/stack && docker compose up -d tms wms
```

**数据库初始化**（首次部署或重建后）：
```bash
docker cp deploy/initdb/28-tms.sql stack-mysql:/tmp/
docker cp deploy/initdb/29-wms.sql stack-mysql:/tmp/
docker exec stack-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" < /tmp/28-tms.sql'
docker exec stack-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" < /tmp/29-wms.sql'
docker compose restart tms wms
```

**Nacos 网关路由**：在 Nacos 配置中心更新 `gateway-routes.yaml`，加入 `/api/tms/**` 和 `/api/wms/**` 路由（与 crm 同构，限流 50/s）。

**前端**：
- web-console：`/tms` 和 `/wms` 路由，菜单入口在侧边栏
- workbench-android：底部导航 5 个 Tab（概览/TMS/WMS/审批/我的）

登录页的「服务地址」支持下拉选择历史配置与**运行时新增**，换环境不必重新打包
（详见 `crm-android/README.md` §六；注意因此**明文 HTTP 已改为全局放行**）。

- 源码在仓库 `crm-android/`，**本机构建**（`./build.sh`），服务器内存跑不动 Gradle
- 产物：`crm-android/app/build/outputs/apk/{release/app-release.apk, debug/app-debug.apk}`
- **web-console 没有挂宿主机卷**，APK 是 `docker cp` 进容器的，重建后会丢。重新放置：

```bash
# 本机（在 crm-android/ 目录下）
./build.sh :app:assembleRelease :app:assembleDebug
# 远端必须给**完整文件路径**——upload.py 是直接 sftp.put，写成 /tmp/ 会报 OSError: Failure
/tmp/rs-push.sh app/build/outputs/apk/release/app-release.apk  /tmp/app-release.apk
/tmp/rs-push.sh app/build/outputs/apk/debug/app-debug.apk      /tmp/app-debug.apk

# 服务器
docker cp /tmp/app-release.apk app-web-console:/usr/share/nginx/html/crm-android.apk
docker cp /tmp/app-debug.apk   app-web-console:/usr/share/nginx/html/crm-android-debug.apk
```

**验证不能只看 `HTTP=200`**：文件缺失时 nginx `try_files` 会回落到 `index.html`，
照样返回 200 但 `content_type=text/html`、389 字节——手机上表现为「安装包解析失败」。

```bash
# 本机：核对 content_type + 大小 + md5 三样
curl -s -o /tmp/dl.apk -w '%{http_code} %{content_type} %{size_download}\n' \
  http://workbench.example.com/crm-android.apk      # 200 application/octet-stream 12042716
md5 -q /tmp/dl.apk                            # 应等于本机 app-release.apk 的 md5
```

> **APK 不是可复现构建**：ZIP 条目带时间戳，每 `./build.sh` 一次 md5 就变一次。
> 所以核对对象是「**你刚上传的那份文件**」，不是「重新构建一份来比」——
> 重新构建必然对不上，看到不一致先确认自己有没有重新 build 过。
> 换句话说：上传后记下当次 md5，或直接用上面这条 curl 拿服务端的来比对。

原生客户端不发 Origin 头，网关 CORS 不参与；**网关零改动**。详见 `crm-android/README.md`。

## 2. 安全边界（已实测）

- 服务器 firewalld 处于关闭状态，端口由 Docker 直接管理
- MySQL / Redis / Nacos / user-center 全部 `127.0.0.1`，**公网端口探测全部 closed**
- 公网访问 Nacos 控制台走 SSH 隧道：
  `ssh -L 8848:127.0.0.1:8848 root@workbench.example.com` → `http://localhost:8848/nacos`
- **8080 需要在阿里云控制台的安全组里放行 TCP 8080**，否则公网访问会被丢弃（服务器上 curl 本机 `http://127.0.0.1:8080/actuator/health` 已返回 200）
- Nacos 未开鉴权（`NACOS_AUTH_ENABLE=false`），因为端口不对外才可接受。若要映射到公网，必须先开鉴权并改 `NACOS_AUTH_TOKEN`
- 中间件密码与服务器 root 密码相同（`Gc199522022`），**仅因端口不暴露**；上生产必须更换并同步改 `/opt/stack/.env`
- `JWT_SECRET` 在 `.env`，网关与 user-center 共用，泄露等于任何人可签发令牌

## 3. 已验证的功能链路

| # | 场景 | 结果 |
| --- | --- | --- |
| 1 | 无 token 访问受保护路径 | `401 missing_token` |
| 2 | 伪造 `X-User-Id` / `X-Tenant-Id` + 无 token | `401 missing_token`（匿名无法冒充） |
| 3 | 伪造身份头 + 有效 token | 与正常请求行为一致，伪造值被网关剥离后替换 |
| 4 | 有效 token 转发到 user-center | 转发成功（`lb://` 经 Nacos 解析） |
| 5 | 注册：验证码 → 注册 → 签发令牌 | `201`，返回 AT/RT |
| 6 | 错误验证码 / 重复账号 | `400 captcha_invalid` / `409 account_exists` |
| 7 | 登录 | `200`，返回双令牌 |
| 8 | **登出后复用同一 token** | `401 token_revoked` ← 网关黑名单与签发方联动生效 |
| 9 | Nacos 服务发现 | `api-gateway`、`user-center` 均已注册且健康 |
| 10 | 前端首页 / 登录页 / SPA 子路由 | `200`，无需令牌 |
| 11 | 管理员看板 | 总数/启用/禁用/今日/7日/30天活跃 + 14 天趋势 + 角色分布 |
| 12 | 管理员用户列表 | 分页返回，`lastLoginAt` 正常回写 |
| 13 | 普通用户调管理接口 | `403 forbidden`（X-User-Roles 不含 ADMIN） |
| 14 | 普通用户调 `/api/users/me` 并伪造 `X-User-Id: 999` | 仍返回真实 `id=2`，伪造值被网关剥离 |
| 15 | 管理员新增用户 `POST /api/admin/users` | `201`，回读新 UserRow；省略角色兜底 `USER`、省略状态默认 `1` |
| 16 | 账号 / 花名重复提交 | `409 account_exists` / `409 nickname_exists`，文案「…换一个试试」 |
| 17 | 新用户用初始密码登录 | `200`，JWT `roles=["USER"]`；同名 `status=0` 账号登录 `401` |
| 18 | 普通用户调新增用户接口 | `403 forbidden`（`user:manage` 不在权限点里），前端同款按钮不渲染 |

测试账号：`13800000006` / `DEMO_PASSWORD`

```bash
curl -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"account":"13800000006","password":"DEMO_PASSWORD"}'
```

## 4. 常用命令

```bash
cd /opt/stack
docker compose ps
docker compose logs -f api-gateway
docker compose restart user-center
docker compose down          # 数据保留在 data/
```

在本机 `deploy/` 目录远程操作（不用手动 ssh）：

```bash
PY=/Users/qianjiajie/.workbuddy/binaries/python/envs/default/bin/python
$PY ssh.py 'docker compose -f /opt/stack/docker-compose.yml ps'
$PY upload.py ../gateway /opt/stack/apps/gateway     # 推源码
```

## 5. 重新构建发布

```bash
cd /opt/stack/apps/gateway && docker build -t api-gateway:1.0.0 .
cd /opt/stack/apps/user-center && docker build -t user-center:1.0.0 .
cd /opt/stack/apps/web-console && docker build -t web-console:1.0.0 .
cd /opt/stack && docker compose up -d --force-recreate user-center web-console
```

- 前端**必须** `--force-recreate`：镜像 tag 不变时 compose 只比对服务配置、不比对镜像 ID，
  容器不会重建，页面仍是旧 bundle（见 §6 对照行）。
- `force-recreate` 会清空 web-console 容器里 `docker cp` 进去的 APK，**发完前端要重新 cp**（§1.2）。

### 5.1 数据库迁移脚本（已有部署必须手动导入）

`deploy/initdb/*.sql` 只在 MySQL **首次初始化**（`data/` 目录为空）时由容器自动执行，
之后新增的脚本对已存在的实例**不会生效**，必须手动跑一次。脚本自带 `USE user_center;`：

```bash
cd deploy
python3 upload.py initdb/25-teams.sql /tmp/25-teams.sql
python3 ssh.py 'docker exec -i stack-mysql mysql --default-character-set=utf8mb4 \
  -uroot -p"$(grep MYSQL_ROOT_PASSWORD /opt/stack/.env | cut -d= -f2)" < /tmp/25-teams.sql'
```

**字符集参数不能省**（容器 `LANG=C` 会让客户端用 latin1 连接，中文被双重编码，见 §6）。

当前线上已发布实例需要补跑的脚本：

| 脚本 | 内容 | 幂等 |
| --- | --- | --- |
| `24-nickname-approval.sql` | `nickname_change_requests` 表 + `nickname:request` / `nickname:review` 权限点 | `CREATE TABLE IF NOT EXISTS` + `INSERT IGNORE` |
| `25-teams.sql` | `teams` / `team_members` 表（**不新增权限点**，团队页沿用 `role:manage`） | `CREATE TABLE IF NOT EXISTS` |
| `26-team-hierarchy.sql` | `teams.parent_id` + `idx_parent`，团队可嵌套成组织架构 | 查 `information_schema` 后 `PREPARE` 执行或空转 |
| `27-user-create.sql` | `users.real_name` / `id_card`；`uk_account` / `uk_nickname` 改挂到「仅 `status = 1` 才有值」的**生成列**上，离职后手机号与花名即可让给新人 | 查 `information_schema` 后 `PREPARE` 执行或空转 |

> **顺序**：`26` / `27` 都必须在**重建 user-center 之前**跑完。新代码所有 `select ... from teams`
> 都带 `parent_id`，列还不存在时会直接 SQL 报错，容器起来就是 500；`27` 同理——建号要写
> `real_name` / `id_card`，列不存在就是插入失败。
> 反过来单独跑它们则没有任何风险——旧代码不读新列，多几列不影响它。

## 6. 踩过的坑（改代码前先看）

| 现象 | 根因 | 修复 |
| --- | --- | --- |
| 多表 `DELETE a FROM db.a JOIN db.b ...` 报 `ERROR 1046 No database selected` | 没有默认库时，多表删除的表引用不认 `db.table` 限定名（单表 `DELETE FROM db.a WHERE ...` 反而正常） | 先 `USE db;`，或拆成每张表一条单表 DELETE |
| `lb://user-center` 全部 503 | Gateway 4.x 不再传递依赖 `spring-cloud-starter-loadbalancer` | gateway/pom.xml 显式加该依赖 |
| 转发后下游 404 | `StripPrefix=1` 多剥了一层：user-center 的 `@RequestMapping` 本身带 `/api` | Nacos 路由里去掉 StripPrefix |
| **不存在的路径 / 不支持的方法一律 `500 internal_error`** | `GlobalExceptionHandler` 的 `@ExceptionHandler(Exception.class)` 兜底抢在 Spring 默认处理之前，把 `NoResourceFoundException`、`HttpRequestMethodNotSupportedException` 一起吞了（症状之一：`DELETE /api/admin/users/{id}` 和拿 `PUT` 调只读接口都返回 500） | 显式接住三类：`NoResourceFoundException` → 404 `not_found`、`HttpRequestMethodNotSupportedException` → 405 `method_not_allowed`、`HttpMediaTypeNotSupportedException` → 415。否则「这里压根没有这个接口」和「服务坏了」是同一种响应，调用方没法区分，也不该重试 |
| `no main manifest attribute` | user-center/pom.xml 漏了 `spring-boot-maven-plugin` | 补 plugin |
| 一个手机号 / 花名在 `users` 里出现两条，看着像脏数据 | `27-user-create.sql` 把 `uk_account` / `uk_nickname` 挂到生成列 `account_active` / `nickname_active` 上，只有 `status = 1` 才有值，离职行那格是 NULL、不参与唯一性 | 这是「离职可重注册」的**预期结果**，不是重复行。查人一律带 `and status = 1`（`findByAccount` / `existsByAccount` 已带），登录与预检只命中在职那条；另一头「禁用 → 启用」撞车时，`AdminUserService.assertNotTaken` 会先报 409 说人话，不会把一句 `Duplicate entry` 漏给前端 |
| `Unable to access jarfile` | `finalName` 改了 jar 名，Dockerfile 的 COPY 通配符对不上 | Dockerfile 改成 `user-center*.jar` |
| JWT 编译失败 | jjwt 0.12.x 把 `allowedClockSkewSeconds` 改名成 `clockSkewSeconds` | 改方法名 |
| CentOS 8 yum 报错 | 官方源 2021 年已下线 | 切到 `mirrors.aliyun.com/centos-vault` |
| 前端首页返回 401 | 鉴权过滤器把所有路径都当 API | 网关改成只对 `/api/**` 校验令牌，HTML 与静态资源放行 |
| 管理接口返回 HTML + 200 | Nacos 路由里漏了 `/api/admin/**`，请求掉进前端兜底路由 | 路由 predicates 补上 `/api/admin/**` |
| 数据库迁移脚本没生效 | 在容器内访问宿主机路径，文件根本不存在 | 用 `cat file \| docker exec -i <容器> mysql`，走 stdin |
| `Account` 没有 `nickname()` | record 里没定义该字段 | 补字段并同步 SQL 与构造调用 |
| 中文昵称显示成 `æµ‹è¯•ç”¨æˆ·` | 容器 `LANG=C` 让 mysql 客户端用 **latin1** 连接，initdb 里的 UTF-8 中文被按 latin1 存进 utf8mb4 列，形成双重编码 | 见下方「字符集」小节 |
| `COPY settings.xml: not found` | 三个 Java 服务的 Dockerfile 都 `COPY settings.xml`，但该文件原本只在 `deploy/` 下、服务目录里没有（服务器上那份是当初手工拷进去的），一 `rm -rf` 就没了 | `deploy/settings.xml` 已复制到 `user-center/`、`gateway/`、`crm/` 各一份，打包上传时自动带上 |
| `docker build` 成功但线上还是旧 bundle | 镜像 tag 不变时 `compose up -d` 只比对服务配置、不比对镜像 ID，容器不重建；此时 curl 静态资源仍是旧 hash | 发布前端一律用 `docker compose up -d --force-recreate web-console`，并 `curl -s http://127.0.0.1:8080/ \| grep -o 'assets/index[^"]*'` 确认 hash 变了 |
| `http://ip:8080/crm-android.apk` 返回 389 字节的 HTML | APK 是 `docker cp` 进 web-console 容器的（该服务没挂宿主机卷），重建容器就没了；nginx `try_files` 找不到文件会回落 `index.html`，于是返回 HTML 而不是 404，手机上表现为「安装包解析失败」 | 按上方 1.2 节重新 `docker cp`；curl 时同时看 `content_type`，`text/html` 说明文件不在 |
| 自动化里点 antd `TreeSelect` 没反应 | antd 的 Select 是监听 **mousedown** 展开的，`element.click()` 只派发 `click` 事件 | 派发 `mousedown`（+ `mouseup`）再等下拉 |
| 点树上**已经选中**的节点，右侧面板突然跳回「全公司」 | antd Tree 点已选中的节点会把 `keys` 清空（反选），`onSelect` 里 `keys[0] ?? 'root'` 就跟着跳根 | `onSelect` 只在 `keys.length > 0` 时更新选中态；要回根请点虚拟根 |
| 删团队返回 409 `team_has_children` | 有子团队时拒绝删除，避免子节点 `parent_id` 悬空、树少一整棵分支 | 预期行为，先把子团队移走或删除；文案已说人话 |
| 花名改了但侧栏 / 顶栏 / 首页仍显示旧名字 | 这三处都读 `AuthContext.profile`，而它原本只在登录 / 注册 / 进「我的身份」时刷新；管理员在别处批准申请后，本人页面拿不到新值，看起来就像「审批没生效」 | `AuthContext` 里加「窗口聚焦立刻拉一次 + 15s 轮询」兜底（审批可能发生在同一个标签页里，聚焦事件不触发）；「我的身份」页在花名变化时同步刷新申请状态，避免「新花名已生效 + 还挂着待审批标签」的自相矛盾 |

## 7. 字符集：为什么中文会变乱码

症状：同样是中文昵称，通过 API 注册的用户显示正常，initdb 脚本插进去的显示成
`æµ‹è¯•ç”¨æˆ·`。这是**双重编码（mojibake）**——UTF-8 的字节被当成 Latin-1 字符后，又按 UTF-8 编码了一遍：

```
正确   "测试用户"        -> E6B58B E8AF95 E794A8 E688B7   (12 字节 / 4 字符)
损坏   "æµ‹è¯•ç”¨æˆ·"  -> C3A6 C2B5 E280B9 ...           (27 字节 / 12 字符)
                            ^^^^ E6 被当成字符 U+00E6(æ) 后又编成 UTF-8
```

根因是 MySQL 客户端的连接字符集：容器内 `LANG=C` 时 `mysql` 命令行默认用 **latin1** 连接
（`character_set_client=latin1`），而 JDBC 连接因为 URL 里写了 `characterEncoding=utf8` 所以一直是对的。

已经做的三件事：

1. **改配置**：compose 的 mysql 加了 `--character-set-client-handshake=FALSE`（忽略客户端握手，
   一律按服务端 utf8mb4 处理）和 `LANG: C.UTF-8`。重启后 `character_set_client` 已是 `utf8mb4`。
2. **修数据**：把双重编码的行 UPDATE 回正确的中文（用 `--default-character-set=utf8mb4` 连接执行）。
3. **保留习惯**：手动跑 SQL 时仍建议显式带上字符集参数：

```bash
cat xxx.sql | docker exec -i stack-mysql mysql --default-character-set=utf8mb4 -uroot -p'密码' user_center
```

排查命令（判断数据是真坏了还是只是显示问题，看字节最准）：

```bash
docker exec stack-mysql mysql --default-character-set=utf8mb4 -uroot -p'密码' user_center \
  -e "select id, nickname, hex(nickname), char_length(nickname), length(nickname) from users;"
# 纯中文正确时 length = char_length * 3；双重编码时 length 明显大于 char_length * 3 的一半以上且能数出 Ã/æ/Â 等字符
```

## 8. 后续建议

- 想省资源时可停掉 Nacos（约 306MB），网关改静态路由，但会失去动态路由能力
- 生产环境建议：升到 4G 内存、Nacos 独立部署并开鉴权、中间件改用独立密码
- 待补：登录风控钩子（失败次数锁定、异地提醒）、注册成功事件改 MQ、前端 bundle 代码分割（现在单包 1.5MB）
- 已补：全局异常处理（`@RestControllerAdvice`，统一 `{code,message}`）
