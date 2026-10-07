# CRM 客户/商机「领用限制」后端设计稿（v1）

> 状态：**设计说明，未动代码**。`crm/` 目录当前有并行会话未提交改动，落地前要先跟那边的人坐一下，先后端再前端。
>
> 已确认的业务边界（来自需求沟通）：**只有未签（进行中的）商机受限归属；已签客户团队内全可见。**

---

## 1. 问题定义

现在 `crm` 服务里，客户和商机都带 `owner_id / owner_name`（归属人），但：

- **没有领用动作**——归属就是创建人/管理员手填，改归属没有正式的「改派」端点；
- **没有可见性规则**——任何拿 `crm:read` 的人看到的数据是整张表（或至少没有按归属圈定范围）；
- 同一个「看见谁的客户」的问题，在 `workbench-antd` 客户端上已经通过 `keepByOwner`（留 `owner 为我` 和 `owner 为空` 的）做过一层前端过滤，但这只是「显示习惯」，不是权限边界——直接调 `GET /api/crm/...` 可以绕过它。

所以要补一条**后端的、按归属圈出范围的可见性规则**，前端过滤只留下「默认折叠自己的」的体验层角色。

## 2. 数据现状（来自现有表）

| 表 | 关键列 | 说明 |
| --- | --- | --- |
| `customers` | `id, name, industry, level, source, status(POTENTIAL/FOLLOWING/DEAL/LOST), ..., owner_id, owner_name` | 客户；`status=DEAL` 即已成交签约 |
| `opportunities` | `id, customer_id, name, stage(LEAD/PROPOSAL/NEGOTIATION/WON/LOST), probability, amount, owner_id, owner_name` | 商机；`stage` 里只有 `WON` 才算「赢单」 |
| `contacts` / `follow_ups` | 同款 `owner_id/owner_name` | 跟随归属各自的可见范围 |
| `teams` / `team_members` | `team_members.is_lead` | 组织归属与「是否负责人」（**单表、单级**，没有父子层级字段） |

注意：`owner_name` 只是展示占位，**不作安全依据**（缺乏唯一约束，改花名后会漂）。规则一律按 `owner_id` 判。

## 3. 推荐的可见性模型

> 核心取舍：**只有「未签约」的商机受限，已签约的客户（无论成交与否）全团队可见。**

### 3.1 数据判定

| 数据 | 判定 | 对普通成员的可见性 |
| --- | --- | --- |
| 进行中的商机 `opportunities.stage IN ('LEAD','PROPOSAL','NEGOTIATION')` | 「未签商机」 | **仅本人**（`owner_id = me`）。队里其他成员名下的不返回 |
| 已收尾的商机 `stage IN ('WON','LOST')` | 已定输赢的商机 | **全团队可见**（用于复盘、统计、对照，无归属门槛） |
| 已成交客户 `customers.status = 'DEAL'` | 已签客户 | **全团队可见** |
| 其余客户 `POTENTIAL / FOLLOWING / LOST` | 潜在/跟进/流失客户 | 规则同「未签商机」：**受限**（只看本人名下），`LOST` 的定性后不再作归属限制 |

给了这个口径，可以想成：**未签链路是私有的，签约之后对团队透明、可复盘**。

### 3.2 谁是「团队」

- 「全团队」的最小语义单位 = `team_members` 里**共享同一个 `team_id`** 的成员。
- 不在 `customers`/`opportunities` 上再挂团队字段——由 `owner_id` → `team_members` 回查。理由：挂团队字段会和 `owner` 双写不一致，且人多团队大的时候一个人只会挂主责团队。

### 3.3 「我的」与「我的团队」的界定

- **成员视角**：只看自己的未签商机/未签客户 + 全团队的已签客户/已定输赢商机。
- **团队负责人**（`team_members.is_lead = 1` 的那张）：在成员视角上，额外解锁**本队成员名下的未签商机**（用于督办）。不下钻到下级——本模型里组织关系在 `teams` 是单层，不承诺级联。
- **管理员**（gateway roles 含 `ADMIN`）：不做限制。

### 3.4 改派（owner 变更）留痕

现状：改归属没有正式端点，挂了 `owner` 也没走「变更事件」。v1 的做法：

- 新建 `crm_owner_events` 表：`id, entity_type(CUSTOMER/OPPORTUNITY), entity_id, old_owner_id, new_owner_id, operator_id, created_at, note`。
- 凡是**有写入权**的归属变更（v1 先只有管理员的直接 SQL、管理端、以及后续的「改派」端点），同步写一行事件。
- v1 **不放开**任何买卖端上的改派入口——给管理端API做钩子就够。前端改 owner 之前先走 409 孤儿处理，避免占位的「测试账号」数据把留痕污染成空记录。

## 4. 接口侧的设计

最小改动原则：**只加读取范围，不改既有字段**。

### 4.1 列表接口 `GET /api/crm/opportunities` 与 `GET /api/crm/customers`

- 解锁条件 = 后端 viewer：`me`(from `X-User-Id`)、`isAdmin`、`isTeamLead`、`myTeamMemberIds`。
- 返回集合裁剪规则（追加到 SQL 的 WHERE）：

  - 商机列表（普通成员）：
    ```
    opportunities.stage IN ('WON','LOST')                  -- 已定输赢，人人可复盘
    OR opportunities.owner_id = :me                         -- 本人名下的未签链路
    ```
  - 客户列表（普通成员）：
    ```
    customers.status = 'DEAL'                               -- 已签客户，人人可见
    OR customers.owner_id = :me                              -- 本人名下的未签链路
    ```
  - 团队负责人 = 上述基础上额外扩一条：
    ```
    OR <实体>.owner_id IN (:myTeamMemberIds)                 -- 解锁本队未签链路
    ```
  - `ADMIN` 不加任何条件。
- `GET /api/crm/workbench` 内的 `mine` / `customers` / `opportunities` 同一口径取数，`owner_name` 空的视作 `''`。

### 4.2 详情接口 `GET /api/crm/{customers|opportunities}/{id}`

- 用 4.1 的同一集合规则筛 id：在不可见集合上请求 → `404`（不是 403——403 会泄露「这条 id 确实存在」）。

### 4.3 前端收进来后的体验层（workbench-antd）

后端已经裁过，前端 `keepByOwner` 可以保留——它现在只负责「默认只看我的」到「含全部已签客户」的排序偏好，**不再承担权限语义**。

## 5. 上级链的选择（本期推荐）

已经有的对象：`team_members.is_lead` 指定一个人是谁的负责人。直接用它做「我的上级」：

- 我向上汇报 → 找 `team_id` 相同且 `is_lead = 1` 的人；
- 不做三层以上的链式判定——组织只有单层（parent_id 在 `teams` 里是可用的，但本期不依赖它，以后有需要再按层叠加管理员/总监做完整链）。

## 6. 迁移与上线

- 本期**不迁移老数据**。`owner_id` 已经在表里，字段齐备；规则直接落到查询上。
- 如果分项验证「未签归属不合预期」：先把异常 owner 修成管理员或团队负责人，再开规则。

## 7. 验收标准

1. 成员 A 只能看到自己名下的「进行中商机」；看不到成员 B 的。
2. 成员 A 能看到所有的已签客户 / WON,LOST 商机（跨归属）。
3. 团队负责人的视图 = 成员视图 ∪ 本队其他成员的未签链路。
4. ADMIN 登录看到全量。
5. 直接点他人不可见 id 的详情接口 → 404。
6. 任何 owner 变更路径写一行 `crm_owner_events`。

## 8. 明确不做（本期）

- 买卖端「改派客户/商机」的写入口与冲突重置；
- 基于 `teams.parent_id` 的上级多级链；
- 「领用」动作（一个客户/商机从池里认领到个人）——池↔个人转换状态机未来再讨论；
- 回款/合同侧规则（`om`/`wms` 的订单可见范围）——那是 another 议题。
