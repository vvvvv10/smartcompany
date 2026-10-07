# Web 管理台（web-console）

React 19 + TypeScript + Vite + Ant Design 5。**所有管理台功能只在 Web 端**——
安卓 / iOS 只做查询与审批，不在手机上做管理配置。

- 入口：`http://workbench.example.com/`
- 演示账号：`13800000006` / `DEMO_PASSWORD`

## 1. 页面与菜单（按权限点显隐）

| 菜单 | 路由 | 门槛权限点 | 功能 |
| --- | --- | --- | --- |
| 首页 | `/` | `dashboard:view` | 两个页签：**网关工作台**（服务健康、路由表、限流统计、手动刷新）+ **数据看板**（总用户/今日注册/活跃/角色数、注册趋势与分布图） |
| CRM 工作台 | `/crm` | `crm:read` | 页签：**个人工作台**（我的客户、跟进待办、我的商机）+ **团队看板** |
| 客户管理 | `/crm/customers` | `crm:read` | 搜索/筛选/分页；详情含联系人与跟进记录；新建/删除（删除带级联警告）；写操作另需 `crm:write` |
| 商机管理 | `/crm/opportunities` | `crm:read` | 商机列表、新建/删除、阶段推进 |
| TMS 运输管理 | `/tms` | `tms:view` | 运输订单 / 车辆 / 司机三个页签：状态筛选、增删改 |
| WMS 仓储管理 | `/wms` | `wms:view` | 仓库 / 库存 / 出入库记录三个页签 |
| OMS 订单管理 | `/oms` | `oms:view` | 订单（明细行编辑）/ 商品（款号）/ 统计三个页签 |
| 我的身份 | `/profile` | 登录即可 | **工号**、**公司**（租户字典名，残留 `default` 显示「未知」）、花名与角色；**申请花名变更**；组织归属；退出登录 |
| 我的团队 | `/my-team` | `team:view`（带队身份派生） | 带队子树的团队与成员（兄弟/上级团队不可见） |
| 用户管理 | `/users` | `user:list` | 分页搜索；**新建用户**（手机号/姓名/身份证/所属团队/**归属租户下拉，默认示例集团集团**）；分配角色；启停；租户列内直接切换归属；花名、最近登录（`user:manage` 才能写） |
| 审批中心 | `/approvals` | `nickname:review` 或 `permission:review`（后者派生给团队负责人/系统管理员） | 双页签：**花名变更**（通过/驳回；列表按「申请人团队负责人/ADMIN」圈范围，非 ADMIN **禁自批**）+ **权限申请**（两级审批：组织上一级 → 系统管理员，ADMIN 任意一级一次批整单；列表按查看者圈范围，自己的单不进待办；非 ADMIN **禁自批**，提交时若能批这单的只有自己则直接拒绝，不留死单。**ADMIN 自批放行**——已是全量权限，自批不构成提权） |
| 权限中心 | `/permissions` | 登录即可进，页内分权 | **概览**页签人人可见——我的权限点 + 全目录分组速览，已有的可**删除**、没有的可**申请**；**团队**页签也人人可见——管理者（`role:manage`）看全量、可增删改，普通成员**只读、只看自己所在的团队**（`/users/me/joined-teams`，行级边界在服务端）；其余管理页签（角色管理/授权矩阵/系统管理员/组织架构）需 `role:manage`（42 号把 USER 曾被手工勾上的 `role:manage` 收回） |

缺权限不是白屏：路由守卫渲染 403 占位页，写明「缺哪个权限点 + 当前有哪些权限点」。

分组也是**权限开关**，与 CRM 管理 / 国际物流同一套写法：一个门槛权限都不持有的用户，那一组整个不渲染（而不是渲染出来置灰）。当前三个分组：

| 分组 | 出现条件（任一命中即出现该组） |
| --- | --- |
| CRM 管理 | `crm:read` |
| 国际物流 | `oms:intl:view` / `wms:intl:view` / `tms:intl:view` / `crm:intl:view` |
| 管理台 | `team:view`（带队派生）/ `user:list` / `nickname:review` / `permission:review` |

「我的身份」「权限中心」是人人可用的个人入口（权限中心还是权限自助申请入口），不进任何开关，**平铺在侧栏底部**。

## 2. 权限点的展示约定

- 对用户**全中文**：业务权限是纯动作（CRM → 查看/修改，TMS/WMS/OMS → 查看），
  管理台权限是描述名（管理用户、配置角色…）；原始 code 作为辅助小字/悬浮提示（`title=code`）。
- 按**业务分组**展示：分组标题给上下文（CRM、TMS、WMS、OMS、管理台、用户、团队…），
  组内是动作短名。
- 派生权限也有兜底文案：`team:view` = 「查看我的团队」（不入 `permissions` 表、勾不到，
  只由带队身份派生）。
- `permission:review`（审批权限）入了表，但 `resolve` 会给**团队负责人**与**系统管理员**
  （41 号 `system_admins`）也派生一份——他们进得了审批中心；某张单这一级轮没轮到你由
  后端按 `stage` 判，前端只认返回的 `canAct`，不自算权限。

## 3. 认证与工作流

- 登录 / 注册（`/login`、`/register`），Access Token 15 分钟自动续期，失效跳登录。
- **改花名必须走审批**：我的身份 →「申请花名变更」→ 审批中心管理员批准后立即生效
  （直接改名接口已永久 403，只留接口是为了给还缓存着旧 bundle 的浏览器一句能操作的提示）。
- **权限自助**：权限中心 →「概览」页签全目录里提交申请 → 审批中心**两级审批**
  （第 1 级组织上一级=直属团队负责人；没团队/负责人是本人则直接进第 2 级；
  第 2 级是该权限所属系统的管理员，ADMIN 在任意一级可一次批整单）后进入自己的
  `permissionCodes`。
- **多租户展示**：我的身份 / 用户管理显示公司名（`tenants` 字典），建号、换归属都走下拉。

## 4. 开发与部署

```bash
cd web-console
npm install
npm run dev        # 本地开发
npm run build      # 产出 dist/（部署只用 build，不做 typecheck：src/api/client.ts 有存量报错可忽略）
```

上线：`dist` 打 tar → `deploy/upload.py` → `docker cp` 进
`app-web-console:/usr/share/nginx/html/`，删旧 `index-*.js/css` 只留新 hash
（完整步骤与踩坑见 `deploy/DEPLOY.md`；镜像 tag 不变时必须 `--force-recreate web-console`）。

## 5. 相关文档

- 后端接口与权限模型：[`README.backend.md`](README.backend.md)
- 移动端：[`README.android.md`](README.android.md) · [`README.ios.md`](README.ios.md)
