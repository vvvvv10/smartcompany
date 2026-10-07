# 国际跨境物流改造计划（TMS / OMS / WMS / CRM）

> 状态：规划稿，**未落一行业务代码**。本文只做现状盘点、目标设计、分阶段计划。
> 面向后续实现 agent：表字段、端点签名、种子数据都已写死到「可直接照抄」的粒度。
> 涉及行业惯例但未在本仓找到依据的地方，一律标 **【待验证】**，留给评审 agent 查证后再定稿。

---

## 1. 摘要与范围声明

### 1.1 一句话

把当前「阿里巴巴国内电商 + 国内公路运输」的四服务骨架，改造成**面向 B2C 跨境卖家 / 货代的物流协同平台**：
OMS 接单 → WMS 备货/贴标/装箱/报关资料 → 揽收 → 出口报关 → 干线（空/海/铁/卡航）→ 目的港清关 → 海外仓/尾程 → 签收回传；CRM 管客户、询报价、货代与渠道商关系、异常工单。

### 1.2 范围（做什么）

| 服务 | 本次要做 | 本次不做 |
| --- | --- | --- |
| **OMS** | 出口订单（母单/子单）、出口单证（发票/装箱单/报关委托）、SKU 增 HS 编码与危险品属性 | 不动国内零售订单表 `oms_orders` 的业务语义 |
| **WMS** | 备货装箱贴标任务、批次与效期、在途库存、海外仓/保税仓 | 不重写现有 `wms_inventory` 主键与唯一键 |
| **TMS** | 国际运单（母单主单 AWB/BL）、运输节点轨迹、承运商/航线/船期、报关、运价卡、汇率 | 不删除现有国内陆运 `tms_orders/vehicles/drivers` |
| **CRM** | 货代与渠道商、询价→报价→合同、异常工单、客户账期与信用额度 | 不改现有跟进/商机语义 |

### 1.3 明确的范围声明（重要）

1. **移动端（workbench-android / workbench-ios）一律不动**，本次新增的所有写操作只做在 Web 管理台。
   移动端现有 TMS/WMS/OMS 页签是**只读列表 + 看板**，其消费的 JSON 字段（`TmsOrder` / `WmsStockRecord` / `OmsOrder`）
   本次**不做删改**，因此移动端不会因为后端加表而崩溃（`AppJson { ignoreUnknownKeys = true }`）。
2. **不重构网关**。新增端点全部挂在既有前缀 `/api/tms/**`、`/api/oms/**`、`/api/wms/**`、`/api/crm/**` 下，
   Nacos `gateway-routes.yaml` **零改动**（现有路由已按前缀覆盖，详见 §12.3）。
3. **不改历史迁移**。`22/28/29/30/31/32/33/34/35/36/37/38/39/40/41/42` 一律不碰，新迁移从 **43** 起，且只用
   `CREATE TABLE IF NOT EXISTS` / `INSERT ... ON DUPLICATE KEY UPDATE` / `INSERT IGNORE`，
   以及 41 号那样的 `information_schema + PREPARE` 加列模式（MySQL 8 不支持 `ADD COLUMN IF NOT EXISTS`）。
4. **不引入中间件**。没有 MQ、没有工作流引擎、没有定时调度框架（当前四个服务都没有 `@EnableScheduling`）。
   「定时任务触发状态迁移」在 M3 才引入，M1/M2 的状态迁移全部由**人工（Web 台）或承运商回调**触发。
5. **本次不引入跨库事务与分布式事务**。跨服务写用「先查后建」幂等 + 失败回滚（沿用 39 号 `LinkageService` 口径）。

### 1.4 「模块列表里 TMS 出现两次」的澄清

用户口述的模块列表里 TMS 出现两次。本计划**按 TMS / OMS / WMS / CRM 四个业务服务理解**，
把两次 TMS 理解为「TMS 的两个子域：国际运输执行 + 承运商/渠道主数据」，二者都落在 `tms` 服务与 `tms` 库内，
不额外开第五个服务。若实际意图是第五个独立服务（见 §1.5 假设 A1），实现前需重新评估。

### 1.5 假设清单（实现前请逐条确认）

| # | 假设 | 若不成立的影响 |
| --- | --- | --- |
| A1 | 四个模块 = TMS / OMS / WMS / CRM，TMS 的「重复出现」是子域而非第五个服务 | 需新增服务 + 网关路由 + 独立库，里程碑整体后移 |
| A2 | 业务域从「服装国内零售」整体切换为「国际跨境物流」。**保留**旧表与旧页面，不清库 | 若要清库，需先做数据归档，且移动端旧页签要同步下线 |
| A3 | 仍以 `tenant_id` 为租户隔离主键，默认租户 `alibaba`；国际数据与国内数据同租户并存 | 若国际业务要求独立租户（如 `global`），需 43 号先插 `tenants` 行并给种子打 `tenant_id='global'` |
| A4 | 单号规则沿用「前缀 + yyyyMMdd + 序列」，不引入雪花 ID/发号器 | 若要全局唯一且并发安全，需引入发号器（当前 `OrderService.generateOrderNo()` 用 `yyyyMMddHHmmss` 后 6 位 + 6 位随机，够用） |
| A5 | 不做服务端权限点校验，沿用现状（业务服务 Controller 一律「登录即可读写」，权限点只管 web 菜单/路由显隐与 user-center 管理台） | 若要求服务端 403，需要网关额外注入权限点集合（见 §11 风险 R2 与 §12.4） |
| A6 | 时区：数据库 `DATETIME` 一律存 **UTC**，展示层按 `Asia/Shanghai` 转；现有数据是本地时区，新表不与之混算 | 若要沿用本地时区存，需删掉所有 `*_utc` 后缀列名，风险是跨时区订单 ETD/ETA 错乱 |
| A7 | 币种：金额一律 `DECIMAL + currency` 双列，汇率取值时点落在 `rate_date`，不实时取汇率 | 若要实时汇率，需引入外部汇率源（当前无外网依赖） |
| A8 | 演示数据量按「够撑满列表分页与看板分布」设计，约 60~80 张母单、120~180 条子单 | 若要多，页面聚合查询会变慢（当前服务无缓存） |

---

## 2. 现状盘点表

> 盘点方法：逐个读完 `tms/oms/wms/crm` 的全部 Controller / Service / 实体 record / Payload record，
> 以及 `22/28/29/30/31/32/33/34/35/36/37/38/39/40/41/42` 号迁移、`README.backend.md` / `README.web.md`、
> web-console 的 `App.tsx` / `AppLayout.tsx` / `api/types.ts` / 各模块 `shared.tsx`。

### 2.1 总览

| 模块 | 服务/库/端口 | 已有表 | 已有端点数 | 已有页面 | 权限点 |
| --- | --- | --- | --- | --- | --- |
| TMS | `tms` 8084 | 3（`tms_orders` / `tms_vehicles` / `tms_drivers`） | 5×6 + 6 看板 ≈ 26 | `/tms`（3 页签） | `tms:view` |
| OMS | `oms` 8086 | 3（`oms_products` / `oms_orders` / `oms_order_items`） | 5×6 + 4 看板 ≈ 34 | `/oms`（3 页签） | `oms:view` |
| WMS | `wms` 8085 | 3（`wms_warehouses` / `wms_inventory` / `wms_stock_records`） | 6+5+4+3 看板 ≈ 18 | `/wms`（3 页签） | `wms:view` |
| CRM | `crm` 8083 | 4（`customers` / `contacts` / `opportunities` / `follow_ups`） | 6+4+5+6+1 ≈ 22 | `/crm`（2 页）+ `/crm/customers` + `/crm/opportunities` | `crm:read` / `crm:write` |

### 2.2 逐模块：已有 vs 缺口

#### TMS（运输）— 现状是**国内公路整车/零担**

| 维度 | 已有（表/端点/字段） | 缺口（国际跨境要什么） |
| --- | --- | --- |
| 运输单 | `tms_orders`：order_no / customer_name / origin / destination / status(4 态) / driver_id / vehicle_id / distance_km / cost | 母单/子单结构；AWB（空运单号）/ BL（提单号）；Master/House 分单；S/O 订舱号；箱型箱量（20GP/40GP/40HQ/LCL）；VGM；毛重/体积重/计费重；唛头；Incoterms；ETD/ETA/ETD-cutoff；截关/截仓；目的港清关状态；POD 签收证明 |
| 承运与渠道 | 无主数据（只有车辆表 `tms_vehicles` 含 `plate_no`） | 船司/航司/快递（MAEU/COSCO/ONE/MSC/CMA/UPS/FEDEX/DHL）；NVOCC；货代代理；清关服务商；尾程派送商；航线（POL/POD + 模式 + 时效）；船期/班期（船名航次 + ETD/ETA + cutoff） |
| 运价 | `cost`（单值元） | 运价卡：基础运费 + BAF/SAF + THC + 偏远 + 旺季 GRI + 关税代理费；多币种（USD/EUR/CNY）；汇率取值时点 |
| 合规 | 无 | 危险品（UN number / class 3/9 / 锂电池 PI967/PI966/PI969）；禁限售；双用途；制裁筛查提示；VAT/IOSS/OSS |
| 轨迹 | 无（`status` 只是个当前值） | 节点级轨迹流：揽收 → 报关 → 开航 → 到港 → 清关 → 提柜 → 派送 → 妥投 → POD；触发方（人工/承运商回调/定时） |
| 报关 | 无 | 报关单：报关行、HS 汇总、申报货值、关税/VAT/消费税、保证金、报关状态与回执 |
| 状态机 | 4 态 `PENDING/IN_TRANSIT/DELIVERED/CANCELLED`，无迁移校验（前端下拉直接 PUT） | 十余态 + 合法迁移表 + 触发方 + 非法迁移 409 |
| 现有可复用 | `tenant/TenantContext` + `TenantFilter`、`GlobalExceptionHandler`、`PageResult<T>` 信封、`NamedParameterJdbcTemplate` 条件拼装、`/brief` 下拉端点、看板聚合风格 | — |

#### OMS（订单）— 现状是**国内 B2C 电商零售**

| 维度 | 已有 | 缺口 |
| --- | --- | --- |
| 商品 | `oms_products`：款号 `style_no` + 颜色/尺码 + 类目（TOP/PANTS/…）+ 吊牌价 | HS 编码、中文/英文申报品名、原产国、危险品标记、UN 号、包装指令、单件毛重/体积、是否带电 |
| 订单 | `oms_orders`：channel(DOUYIN/TAOBAO/P1688/OFFLINE/WECHAT) + 收货人 + 6 态状态机 + 件数/金额 | 母单/子单；发货方 vs 收货方双地址；Incoterms；币种 + 汇率；目的国/目的港；ETA/ETD；是否为 B2C 集货；发票号/装箱单号/报关委托号 |
| 明细 | `oms_order_items`：款号/品名/颜色/尺码/数量/单价 | 逐行 HS 编码、申报单价与申报币种、申报货值、原产国、危险品标记 |
| 单证 | 无 | 商业发票、装箱单、报关委托书、原产地证、提单/运单副本；单证与子单的关联与状态 |
| 联动 | `LinkageService`：订单到 SHIPPED/DONE 时**先查后建**在 TMS 建运单、在 WMS 建出库记录；`LinkageClient` 直连 `http://tms:8084` / `http://wms:8085`；失败抛 `LinkageException` 回滚 | 国际链路要复用同一套「先查后建 + 租户头透传 + 降级不挡列表」的模式，但对象换成「母单 → TMS 运单」 |
| 现有可复用 | `generateOrderNo()` 生成规则、`OrderDetail` 一次返回明细、`linkage.enrich` 列表聚合降级、看板 `topStyles` 聚合风格 | — |

#### WMS（仓储）— 现状是**国内单仓 + 无批次**

| 维度 | 已有 | 缺口 |
| --- | --- | --- |
| 仓库 | `wms_warehouses`：name/location/capacity/status | 仓库类型（DOMESTIC/OVERSEAS/BONDED/TRANSIT）、所在国、时区、海外仓服务商、是否保税 |
| 库存 | `wms_inventory`：`uk_warehouse_sku(warehouse_id, sku)` + `quantity` 单值 | 批次 `batch_no` + 生产日期 + 效期；占用 `reserved_qty`；可用 = 现有量 − 占用；库龄；FEFO（先到期先出） |
| 出入库 | `wms_stock_records`：type(IN/OUT) + quantity + `order_no`(39 号加的) | 任务化（拣货/装箱/贴标/交接）、箱号与箱型、唛头、体积与 VGM、危险品单独库位 |
| 在途 | 无 | `in-transit` 在途库存：已出库未入库的货，随运单状态流转到目的仓入库 |
| 现有可复用 | `StockRecordService.create` 事务内同步库存 + 出库前查库存不足；`idx_type_created` 看板聚合索引；`wms_inventory` 的 UPSERT 语义 | — |

#### CRM（客户）— 现状是**通用 B2B 销售 CRM**

| 维度 | 已有 | 缺口 |
| --- | --- | --- |
| 客户 | `customers`：level(KEY/NORMAL/LOW) + status(POTENTIAL/FOLLOWING/DEAL/LOST) + owner_id/owner_name | 客户类型（SELLER 卖家 / FORWARDER 货代 / FACTORY 工厂 / DISTRIBUTOR 经销）；国家；税号；账期 `payment_term_days`；信用额度 `credit_limit` + 已用 `credit_used`；默认币种 |
| 商机/跟进 | `opportunities`（LEAD→…→WON/LOST + 赢率）、`follow_ups`（含 next_follow_at 待办） | 询价→报价→合同三段式（询价是物流特有的：**POL/POD + 柜型 + 重量/体积 + 目的价**，不是金额 + 赢率） |
| 渠道 | 无 | 货代与渠道商主数据（货代代理/NVOCC/清关行/尾程商），与合作等级、账期、授信 |
| 异常 | 无 | 异常工单（破损/延误/清关查验/改单/弃货），关联 `export_no` / `shipment_no`，有跟进人与 SLA |
| 现有可复用 | `WorkbenchService`（按 `X-User-Id` 的个人工作台 + 跟进待办）、`opportunities` 的 `moveStage` PATCH 语义、跟进记录时间线 | — |

### 2.3 跨服务现状（必须知道的三条链路）

| 链路 | 现状 | 本次怎么处理 |
| --- | --- | --- |
| OMS → TMS/WMS | `LinkageClient` 直连容器网络（`tms.base-url` / `wms.base-url`），写 `X-Tenant-Id` + 透传 `X-User-Id`/`X-User-Roles`；**不走网关**（服务端内部调用带 JWT 属绕路） | 国际链路沿用同一模式，新增 `exportNos` 批量反查参数 |
| OMS 列表聚合 | `LinkageService.enrich` 每页 2 次 HTTP 批量回填 `tmsStatus/origin/destination/outQty`，**下游挂了各自降级为 null，不挡列表** | 母单列表沿用；新增 `shipmentStatus` / `awbNo` / `etd` / `eta` 四个回填列 |
| 移动端 | `workbench-android` 三个模块页签是只读列表 + 看板 | 本次**零改动**；新增国际页面**不**进移动端 |

### 2.4 前端现状与复用点

| 现状 | 复用方式 |
| --- | --- |
| 路由集中在 `src/App.tsx`，`RequirePermission` 守卫按 `permissionCodes` | 新增 `/intl/*` 路由，复用同一个 `RequirePermission` 组件 |
| 菜单在 `src/components/AppLayout.tsx`（注意：**不是** `src/layouts/AppLayout.tsx`，实际路径是 `components/`），`PAGE_META` 字典按 `location.pathname` 取标题 | 新增 `PAGE_META` 条目 + 一个父菜单「国际物流」，子项按权限点显隐 |
| 每个模块一个 `shared.tsx` 存枚举中文/配色映射 + `formatTime` | 新建 `src/pages/intl/shared.tsx`，**同样只此一份** |
| `api/types.ts` 集中所有 TS interface | 在末尾追加 `Intl*` 系列 interface |
| `Empty` / `Alert` / `Spin` 的空态与降级写法见 `TmsPage.tsx` | 照抄 |
| antd 坑：两个汉字的按钮会自动插空格（「成员」→「成 员」）；Tabs 非激活面板仍在 DOM | 页面里凡是自动化要点的按钮，文案用 **2 字以上且不触发该坑**（如「新建运单」「批量订舱」）；验收脚本按 `.ant-tabs-tabpane-active` 收窄 |

---

## 3. 目标域模型

### 3.0 总体切分原则（先说「为什么」，因为这决定了后面所有表结构）

1. **不复用旧表**。`tms_orders` 的语义是「国内公路运输单」，`oms_orders` 的语义是「国内 B2C 电商零售单」，
   字段与状态机与国际货代无交集。硬改会同时破坏 39 号三系统联动与移动端只读页签。
   → **国际域全部新建表，旧表与旧页面原样保留**。
2. **不跨库共享自增 id**。四个库各自 AUTO_INCREMENT，`oms.id=3` 与 `tms.id=3` 毫无关系。
   → **跨服务只用业务单号字符串关联**（沿用 39 号的「关联键 = 统一订单号」哲学）。
3. **谁的状态谁定义**。TMS 定义「运单走到哪了」，OMS 定义「订单走到哪了」，
   两套状态机独立，通过 `tms_shipments.batch_no = oms_export_batches.batch_no` 关联，
   **不允许双向强一致**，列表页用批量聚合回填（与现有 `enrich` 同款）。

### 3.1 单号体系（跨服务关联的唯一钥匙）

| 单号 | 所属服务/表 | 格式 | 示例 | 唯一范围 |
| --- | --- | --- | --- | --- |
| 出口子单号 | `oms_export_orders.order_no` | `EXP` + yyyyMMdd + 3 位序列 | `EXP20261010001` | 租户内唯一 |
| 出口母单号 | `oms_export_batches.batch_no` | `MEXP` + yyyyMMdd + 3 位序列 | `MEXP20261010001` | 租户内唯一 |
| 国际运单号 | `tms_shipments.shipment_no` | `SHP` + yyyyMMdd + 3 位序列 | `SHP20261010001` | 租户内唯一 |
| 空运单号 AWB | `tms_shipments.awb_no` | 3 位航司数字码 + `-` + 8 位序列 | `112-30874125` | 租户内唯一（`uk_awb`） |
| 海运提单号 BL | `tms_shipments.bl_no` | 4 位船公司代码 + 7 位序列 | `COSU6123456` | 租户内唯一（`uk_bl`） |
| 分单号 House | `tms_shipment_items.house_no` | 母单号 + `-` + 2 位 | `MEXP20261010001-01` | 租户内唯一 |
| 订舱号 S/O | `tms_shipments.booking_no` | 船司 4 字母 + 9 位 | `COSU6123456` | 可空、可重复（同一柜可换船司重订） |
| 集装箱号 | `tms_shipment_items.container_no` | ISO 6346：4 字母 + 6 位数字 + 1 位校验 | `CSNU6382914` | 【待验证】校验位算法 |
| 报关单号 | `tms_customs_declarations.declaration_no` | 18 位数字（关单号） | `TESTID00000010X` | 【待验证】位数与编码规则 |
| 报价单号 | `crm_quotations.quotation_no` | `QT` + yyyyMMdd + 4 位 | `QT202610100001` | 租户内唯一 |
| 合同号 | `crm_contracts.contract_no` | `CT` + yyyyMMdd + 3 位 | `CT20261010001` | 租户内唯一 |
| 工单号 | `crm_tickets.ticket_no` | `TK` + yyyyMMdd + 4 位 | `TK202610100001` | 租户内唯一 |
| 备货任务号 | `wms_pack_tasks.task_no` | `PK` + yyyyMMdd + 4 位 | `PK202610100001` | 租户内唯一 |

**空值约定**：所有单号列 `NOT NULL DEFAULT ''`，空串表示「未生成」。
唯一索引**只加在业务上必须唯一的列**（如 `uk_awb`），允许为空的列不加唯一键（否则两条空串行会撞）。

### 3.2 OMS 库表结构

#### 3.2.1 `oms_export_orders` 出口子单（客户一笔货 = 一行）

```sql
CREATE TABLE IF NOT EXISTS oms_export_orders (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba' COMMENT '租户ID（36 号规范）',
    order_no        VARCHAR(32)  NOT NULL COMMENT '出口子单号 EXP+yyyyMMdd+3位',
    batch_no        VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '出口母单号（空=未组批）',

    -- 客户与收货
    customer_id     BIGINT       NOT NULL DEFAULT 0 COMMENT 'CRM customers.id（跨库弱引用，只存 id 不做外键）',
    customer_name   VARCHAR(128) NOT NULL DEFAULT '' COMMENT '客户名称快照（跨库读不到时前端仍能显示）',
    consignee_name  VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '境外收货人',
    consignee_company VARCHAR(128) NOT NULL DEFAULT '' COMMENT '境外收货公司',
    consignee_country  VARCHAR(2)   NOT NULL DEFAULT '' COMMENT '收货国 ISO-3166 alpha-2，如 US/DE/GB',
    consignee_address VARCHAR(255) NOT NULL DEFAULT '' COMMENT '境外收货地址',
    consignee_phone VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '收货联系电话（国际格式，含 + 号）',
    consignee_email VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '收货邮箱',

    -- 贸易条款与币种
    incoterm        VARCHAR(8)   NOT NULL DEFAULT 'FOB' COMMENT 'EXW/FOB/CIF/DDP…（Incoterms 2020 共 11 个【待验证】全集）',
    currency        CHAR(3)      NOT NULL DEFAULT 'USD' COMMENT 'ISO-4217',
    total_amount    DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '订单金额（原币）',
    freight_amount  DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '应收运费（原币）',
    total_qty       INT          NOT NULL DEFAULT 0 COMMENT '总件数',
    total_weight    DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '总毛重 KG',
    total_volume    DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '总体积 CBM',
    volumetric_weight DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '总体积重 KG（长×宽×高/6000 或 /5000，见 tms_routes.volumetric_divisor）',

    -- 时效
    etd_date        DATE         NULL DEFAULT NULL COMMENT '预计离港/起飞日（目的港当地，见 §11 R3）',
    eta_date        DATE         NULL DEFAULT NULL COMMENT '预计到港日',
    ready_date      DATE         NULL DEFAULT NULL COMMENT '备货完成日',

    -- 状态
    status          VARCHAR(24)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CONFIRMED/PICKING/PACKED/EXPORT_DECLARED/SHIPPED/IN_TRANSIT/ARRIVED/CUSTOMS_CLEARING/CUSTOMS_CLEARED/LAST_MILE/DELIVERED/CLOSED/EXCEPTION/CANCELLED',
    is_batch_locked TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1=已组批，组批后不允许改明细行',

    remark          VARCHAR(255) NOT NULL DEFAULT '',
    created_by      BIGINT       NOT NULL DEFAULT 0 COMMENT '创建人 users.id（网关注入的 X-User-Id）',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (tenant_id, order_no),
    KEY idx_tenant (tenant_id, id),
    KEY idx_batch (batch_no),
    KEY idx_status (status),
    KEY idx_customer (customer_id),
    KEY idx_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '出口订单（子单）';
```

**索引依据**：`idx_tenant` 服务「按租户 + id 倒序分页」；`idx_status` 服务看板与状态筛选；
`idx_batch` 服务「按母单号取全部子单」（OMS 母单详情一次拉齐）；`idx_customer` 服务 CRM 侧反查。

#### 3.2.2 `oms_export_order_items` 出口单明细

```sql
CREATE TABLE IF NOT EXISTS oms_export_order_items (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba',
    order_id        BIGINT       NOT NULL COMMENT 'oms_export_orders.id',
    order_no        VARCHAR(32)  NOT NULL COMMENT '冗余单号，列表聚合免 JOIN',
    line_no         INT          NOT NULL DEFAULT 1 COMMENT '行号（1 起）',

    product_id      BIGINT       NOT NULL DEFAULT 0 COMMENT 'oms_products.id，0=自由品名未挂商品档案',
    sku             VARCHAR(64)  NOT NULL DEFAULT '' COMMENT 'SKU 快照',
    product_name    VARCHAR(128) NOT NULL DEFAULT '' COMMENT '中文品名快照',
    customs_name    VARCHAR(255) NOT NULL DEFAULT '' COMMENT '报关品名（可与商品名不同，报关行要求）',
    customs_name_en VARCHAR(255) NOT NULL DEFAULT '' COMMENT '英文报关品名',
    hs_code         VARCHAR(12)  NOT NULL DEFAULT '' COMMENT 'HS 编码（中国出口 10 位【待验证】位数口径）',
    origin_country  VARCHAR(2)   NOT NULL DEFAULT 'CN' COMMENT '原产国',

    quantity        INT          NOT NULL DEFAULT 1 COMMENT '数量（件）',
    unit_price      DECIMAL(14,4) NOT NULL DEFAULT 0 COMMENT '成交单价（原币）',
    declared_value  DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '申报货值（原币）',

    net_weight      DECIMAL(10,3) NOT NULL DEFAULT 0 COMMENT '单件净重 KG',
    gross_weight    DECIMAL(10,3) NOT NULL DEFAULT 0 COMMENT '单件毛重 KG',
    volume          DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '单件体积 CBM',

    is_dangerous    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1=危险品',
    un_number       VARCHAR(16)  NOT NULL DEFAULT '' COMMENT 'UN 编号，如 UN3481',
    dg_class        VARCHAR(8)   NOT NULL DEFAULT '' COMMENT '危险品类别，3=易燃液体 9=锂电池',
    packing_instruction VARCHAR(8) NOT NULL DEFAULT '' COMMENT '包装说明 PI967/PI966/PI969',
    battery_watt_hours DECIMAL(8,2) NOT NULL DEFAULT 0 COMMENT '电池额定瓦时 Wh',
    remark          VARCHAR(255) NOT NULL DEFAULT '',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_order (order_id),
    KEY idx_hs_code (hs_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '出口单明细（报关申报要素）';
```

#### 3.2.3 `oms_export_batches` 出口母单（拼批：多子单 → 一票货）

```sql
CREATE TABLE IF NOT EXISTS oms_export_batches (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba',
    batch_no        VARCHAR(32)  NOT NULL COMMENT '母单号 MEXP+yyyyMMdd+3位',

    -- 运输方案
    mode            VARCHAR(16)  NOT NULL DEFAULT 'SEA' COMMENT 'SEA海运/AIR空运/RAIL铁路/TRUCK卡航/EXPRESS快递/LCL海运拼箱',
    pol_code        VARCHAR(8)   NOT NULL DEFAULT '' COMMENT '起运港 UN/LOCODE，如 CNSHA【待验证】LOCODE 口径',
    pol_name        VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '起运港中文名',
    pod_code        VARCHAR(8)   NOT NULL DEFAULT '' COMMENT '目的港，如 USNYC',
    pod_name        VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '目的港中文名',
    route_id        BIGINT       NOT NULL DEFAULT 0 COMMENT 'TMS tms_routes.id（弱引用）',
    carrier_id      BIGINT       NOT NULL DEFAULT 0 COMMENT 'TMS tms_carriers.id（弱引用）',

    container_type  VARCHAR(8)   NOT NULL DEFAULT '20GP' COMMENT '20GP/40GP/40HQ/LCL',
    container_qty   INT          NOT NULL DEFAULT 1 COMMENT '箱量',
    incoterm        VARCHAR(8)   NOT NULL DEFAULT 'FOB',
    currency        CHAR(3)      NOT NULL DEFAULT 'USD',

    total_qty       INT          NOT NULL DEFAULT 0 COMMENT '本批总件数（组批时重算）',
    total_weight    DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '本批总毛重 KG',
    total_volume    DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '本批总体积 CBM',
    volumetric_weight DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '本批总体积重 KG',

    etd_date        DATE         NULL DEFAULT NULL COMMENT 'ETD 计划离港',
    eta_date        DATE         NULL DEFAULT NULL COMMENT 'ETA 预计到港',
    cutoff_at       DATETIME     NULL DEFAULT NULL COMMENT '截关/截仓时间（UTC）',

    status          VARCHAR(24)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CONFIRMED/BOOKING/BOOKED/LOADING/DEPARTED/IN_TRANSIT/ARRIVED/CLEARING/CLEARED/LAST_MILE/DELIVERED/CLOSED/EXCEPTION/CANCELLED',
    remark          VARCHAR(255) NOT NULL DEFAULT '',
    created_by      BIGINT       NOT NULL DEFAULT 0,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_batch_no (tenant_id, batch_no),
    KEY idx_tenant (tenant_id, id),
    KEY idx_status (status),
    KEY idx_pol_pod (pol_code, pod_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '出口母单（拼批/一票货）';
```

#### 3.2.4 `oms_products` 增列（国际申报属性）

用 41 号的 `information_schema + PREPARE` 幂等加列模式（MySQL 8 无 `ADD COLUMN IF NOT EXISTS`）：

| 列名 | 类型 | 说明 |
| --- | --- | --- |
| `hs_code` | `VARCHAR(12) NOT NULL DEFAULT ''` | HS 编码 |
| `customs_name` | `VARCHAR(255) NOT NULL DEFAULT ''` | 报关品名（中文） |
| `customs_name_en` | `VARCHAR(255) NOT NULL DEFAULT ''` | 报关品名（英文） |
| `origin_country` | `VARCHAR(2) NOT NULL DEFAULT 'CN'` | 原产国 |
| `is_dangerous` | `TINYINT(1) NOT NULL DEFAULT 0` | 危险品标记 |
| `un_number` | `VARCHAR(16) NOT NULL DEFAULT ''` | UN 编号 |
| `dg_class` | `VARCHAR(8) NOT NULL DEFAULT ''` | 危险品类别 |
| `packing_instruction` | `VARCHAR(8) NOT NULL DEFAULT ''` | 包装说明 PI967/PI966/PI969 |
| `battery_watt_hours` | `DECIMAL(8,2) NOT NULL DEFAULT 0` | 电池瓦时 |
| `net_weight` | `DECIMAL(10,3) NOT NULL DEFAULT 0` | 单件净重 KG |
| `gross_weight` | `DECIMAL(10,3) NOT NULL DEFAULT 0` | 单件毛重 KG |
| `volume_cbm` | `DECIMAL(10,4) NOT NULL DEFAULT 0` | 单件体积 CBM |

### 3.3 TMS 库表结构

#### 3.3.1 `tms_carriers` 承运商 / 渠道商

```sql
CREATE TABLE IF NOT EXISTS tms_carriers (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba',
    code            VARCHAR(16)  NOT NULL COMMENT '承运商代码，如 MAEU/COSCO/UPS（也用作 IATA/UN/SCAC【待验证】三者是否同一标识）',
    name_zh         VARCHAR(64)  NOT NULL COMMENT '中文名',
    name_en         VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '英文名',
    carrier_type    VARCHAR(20)  NOT NULL DEFAULT 'OCEAN' COMMENT 'OCEAN海运船司/AIR空运航司/EXPRESS快递/NVOCC无船承运人/FORWARDER货代/AGENT货代代理/CLEARANCE清关服务商/LAST_MILE尾程派送',
    country         VARCHAR(2)   NOT NULL DEFAULT '' COMMENT '注册国',
    contact_name    VARCHAR(64)  NOT NULL DEFAULT '',
    contact_phone   VARCHAR(32)  NOT NULL DEFAULT '',
    contact_email   VARCHAR(64)  NOT NULL DEFAULT '',
    service_level   VARCHAR(16)  NOT NULL DEFAULT 'STANDARD' COMMENT 'STANDARD标准/EXPRESS特快/ECONOMY经济',
    transit_days    INT          NOT NULL DEFAULT 0 COMMENT '参考时效（天）',
    cut_off_hours   INT          NOT NULL DEFAULT 0 COMMENT '截关/截仓提前小时数（ETD 前 N 小时）',
    free_time_days  INT          NOT NULL DEFAULT 0 COMMENT '免堆存/免箱期天数（海运常见 7~14【待验证】）',
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE启用/INACTIVE停用',
    remark          VARCHAR(255) NOT NULL DEFAULT '',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_carrier_code (tenant_id, code),
    KEY idx_tenant (tenant_id, id),
    KEY idx_type (carrier_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '承运商与渠道商主数据';
```

#### 3.3.2 `tms_routes` 航线

```sql
CREATE TABLE IF NOT EXISTS tms_routes (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba',
    route_code      VARCHAR(32)  NOT NULL COMMENT '航线代码 CNSHA-USNYC',
    carrier_id      BIGINT       NOT NULL COMMENT 'tms_carriers.id',
    mode            VARCHAR(16)  NOT NULL DEFAULT 'SEA' COMMENT 'SEA/AIR/RAIL/TRUCK/EXPRESS/LCL',
    pol_code        VARCHAR(8)   NOT NULL COMMENT '起运港/起飞机场三字码',
    pod_code        VARCHAR(8)   NOT NULL COMMENT '目的港/目的地三字码',
    transit_days    INT          NOT NULL DEFAULT 0 COMMENT '参考时效（天）',
    volumetric_divisor INT       NOT NULL DEFAULT 6000 COMMENT '体积重除数：空运 6000 / 快递 5000（cm³/kg）',
    via_ports       VARCHAR(128) NOT NULL DEFAULT '' COMMENT '中转港，逗号分隔',
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    remark          VARCHAR(255) NOT NULL DEFAULT '',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_route_code (tenant_id, route_code),
    KEY idx_tenant (tenant_id, id),
    KEY idx_pol_pod (pol_code, pod_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '航线主数据';
```

#### 3.3.3 `tms_sailings` 船期 / 班期

```sql
CREATE TABLE IF NOT EXISTS tms_sailings (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba',
    route_id        BIGINT       NOT NULL COMMENT 'tms_routes.id',
    vessel_name     VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '船名/航班号',
    voyage_no       VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '航次号（如 V.0121E）',
    etd_at          DATETIME     NOT NULL COMMENT 'ETD（UTC）',
    eta_at          DATETIME     NOT NULL COMMENT 'ETA（UTC）',
    cutoff_at       DATETIME     NULL DEFAULT NULL COMMENT '截关时间（UTC），空则用 tms_carriers.cut_off_hours 推算',
    free_time_days  INT          NOT NULL DEFAULT 0 COMMENT '本航次免堆存天数',
    space_left      INT          NOT NULL DEFAULT 0 COMMENT '剩余舱位（TEU 或 件，按 mode 口径【待验证】）',
    status          VARCHAR(16)  NOT NULL DEFAULT 'SCHEDULED' COMMENT 'SCHEDULED已排期/BOOKING已订舱/CLOSED已截关/CANCELLED已取消',
    remark          VARCHAR(255) NOT NULL DEFAULT '',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_route_etd (tenant_id, route_id, etd_at),
    KEY idx_tenant (tenant_id, id),
    KEY idx_etd (etd_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '船期与班期';
```

#### 3.3.4 `tms_shipments` 国际运单（核心表）

```sql
CREATE TABLE IF NOT EXISTS tms_shipments (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba',
    shipment_no     VARCHAR(32)  NOT NULL COMMENT '国际运单号 SHP+yyyyMMdd+3位',
    batch_no        VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '关联出口母单号 oms_export_batches.batch_no',

    -- 单证号
    awb_no          VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '空运单号（空运填，如 112-30874125）',
    bl_no           VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '海运提单号（海运填，如 COSU6123456）',
    booking_no      VARCHAR(32)  NOT NULL DEFAULT '' COMMENT 'S/O 订舱号',
    sailing_id      BIGINT       NOT NULL DEFAULT 0 COMMENT 'tms_sailings.id（弱引用）',

    -- 运输方案
    mode            VARCHAR(16)  NOT NULL DEFAULT 'SEA' COMMENT 'SEA/AIR/RAIL/TRUCK/EXPRESS/LCL',
    carrier_id      BIGINT       NOT NULL COMMENT 'tms_carriers.id',
    pol_code        VARCHAR(8)   NOT NULL DEFAULT '',
    pod_code        VARCHAR(8)   NOT NULL DEFAULT '',
    container_type  VARCHAR(8)   NOT NULL DEFAULT '' COMMENT '20GP/40GP/40HQ/LCL',
    container_qty   INT          NOT NULL DEFAULT 0 COMMENT '箱量',

    -- 货物
    incoterm        VARCHAR(8)   NOT NULL DEFAULT 'FOB',
    currency        CHAR(3)      NOT NULL DEFAULT 'USD',
    total_pieces    INT          NOT NULL DEFAULT 0 COMMENT '总件数',
    total_gross_weight DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '总毛重 KG',
    total_volume    DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '总体积 CBM',
    volumetric_weight DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '总体积重 KG',
    chargeable_weight DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '计费重：海运取 max(毛重, 体积重按吨比)；空运取 max(毛重, 体积重)【待验证】W/M 比例',
    vgm_weight      DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT 'VGM 核实总重 KG（海运 SOLAS 强制）',
    marks           VARCHAR(255) NOT NULL DEFAULT '' COMMENT '唛头',

    -- 时效
    etd_at          DATETIME     NULL DEFAULT NULL COMMENT 'ETD（UTC）',
    eta_at          DATETIME     NULL DEFAULT NULL COMMENT 'ETA（UTC）',
    cutoff_at       DATETIME     NULL DEFAULT NULL COMMENT '截关/截仓（UTC）',
    actual_etd_at   DATETIME     NULL DEFAULT NULL COMMENT '实际开航（UTC）',
    actual_eta_at   DATETIME     NULL DEFAULT NULL COMMENT '实际到港（UTC）',
    pod_received_at DATETIME     NULL DEFAULT NULL COMMENT 'POD 签收证明回传时间（UTC）',

    -- 合规
    is_dangerous    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1=含危险品',
    un_number       VARCHAR(16)  NOT NULL DEFAULT '' COMMENT 'UN 编号汇总（多品取逗号串）',
    dg_class        VARCHAR(8)   NOT NULL DEFAULT '' COMMENT '危险品类别汇总',
    sanction_flag   VARCHAR(16)  NOT NULL DEFAULT 'CLEAR' COMMENT '制裁筛查结果 CLEAR/PENDING/HIT（人工核，HIT 必须人工放行）',
    ioss_no         VARCHAR(32)  NOT NULL DEFAULT '' COMMENT 'IOSS 编号（欧盟 B2C 进口 VAT【待验证】适用条件）',

    -- 状态
    status          VARCHAR(24)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/BOOKING/BOOKED/PICKED_UP/EXPORT_DECLARED/DEPARTED/IN_TRANSIT/ARRIVED/CLEARING/CLEARED/LAST_MILE/DELIVERED/POD_CONFIRMED/EXCEPTION/RETURNED/CANCELLED',
    exception_reason VARCHAR(255) NOT NULL DEFAULT '' COMMENT '异常原因（进入 EXCEPTION 时必填）',

    remark          VARCHAR(255) NOT NULL DEFAULT '',
    created_by      BIGINT       NOT NULL DEFAULT 0,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_shipment_no (tenant_id, shipment_no),
    UNIQUE KEY uk_awb (tenant_id, awb_no),
    UNIQUE KEY uk_bl (tenant_id, bl_no),
    KEY idx_tenant (tenant_id, id),
    KEY idx_batch (batch_no),
    KEY idx_status (status),
    KEY idx_etd (etd_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '国际运单（主单）';
```

> **`uk_awb` / `uk_bl` 的坑**：MySQL 唯一索引把空串当作普通值，多条 `awb_no=''` 的行会撞唯一键。
> 处置：这两列在 `CREATE TABLE` 时带 `DEFAULT ''`，**建表时不能同时有两条空串行**——
> 所以改为 `UNIQUE` 前先在 43 号种子里确保「要么有值要么用 NULL」。
> **最终决定**：`awb_no` / `bl_no` 建表时**不加唯一索引**，改用普通 `idx`，唯一性由应用层在「生成单号时先查后建」保证
> （与 39 号 `LinkageService` 的「先查后建」同一哲学）。仅 `shipment_no` / `batch_no` 这类**本系统生成、必非空**的列建唯一键。

**修正后的索引**：去掉 `uk_awb` / `uk_bl`，改为
`KEY idx_awb (awb_no)`、`KEY idx_bl (bl_no)`（列表搜索用）。

#### 3.3.5 `tms_shipment_items` 运单-箱/分单

```sql
CREATE TABLE IF NOT EXISTS tms_shipment_items (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba',
    shipment_id     BIGINT       NOT NULL COMMENT 'tms_shipments.id',
    shipment_no     VARCHAR(32)  NOT NULL COMMENT '冗余运单号',
    order_no        VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '关联出口子单号 oms_export_orders.order_no',
    house_no        VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '分单号 House（母单号-01）',
    container_no    VARCHAR(16)  NOT NULL DEFAULT '' COMMENT '集装箱号 ISO 6346',
    container_type  VARCHAR(8)   NOT NULL DEFAULT '' COMMENT '箱型',
    seal_no         VARCHAR(16)  NOT NULL DEFAULT '' COMMENT '封条号',
    marks           VARCHAR(255) NOT NULL DEFAULT '' COMMENT '唛头',
    pieces          INT          NOT NULL DEFAULT 0 COMMENT '件数',
    gross_weight    DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '毛重 KG',
    volume          DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '体积 CBM',
    volumetric_weight DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '体积重 KG',
    is_dangerous    TINYINT(1)   NOT NULL DEFAULT 0,
    un_number       VARCHAR(16)  NOT NULL DEFAULT '',
    dg_class        VARCHAR(8)   NOT NULL DEFAULT '',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_shipment (shipment_id),
    KEY idx_order_no (order_no),
    KEY idx_container (container_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '运单箱明细/分单';
```

#### 3.3.6 `tms_tracking_nodes` 轨迹节点流

```sql
CREATE TABLE IF NOT EXISTS tms_tracking_nodes (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba',
    shipment_id     BIGINT       NOT NULL,
    shipment_no     VARCHAR(32)  NOT NULL,
    node_code       VARCHAR(24)  NOT NULL COMMENT '节点码：PICKED_UP/EXPORT_DECLARED/DEPARTED/ARRIVED/CLEARING/CLEARED/OUT_FOR_DELIVERY/DELIVERED/POD_CONFIRMED/EXCEPTION',
    node_name       VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '节点中文名（承运商原文）',
    node_time       DATETIME     NOT NULL COMMENT '节点时间（UTC）',
    location        VARCHAR(128) NOT NULL DEFAULT '' COMMENT '节点地点',
    source          VARCHAR(16)  NOT NULL DEFAULT 'MANUAL' COMMENT '来源 MANUAL人工/CARRIER_CALLBACK承运商回调/SCHEDULED定时任务',
    operator        VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '操作人（MANUAL 时取网关注入用户）',
    remark          VARCHAR(255) NOT NULL DEFAULT '',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_shipment_time (shipment_id, node_time),
    KEY idx_tenant (tenant_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '运单轨迹节点';
```

> **轨迹只追加不更新**：`source` 列是这套设计的核心——它回答「这一条是人点的、承运商推的还是系统推的」。
> 回调接口写入前先按 `(shipment_id, node_code, node_time)` 查一次，命中就返回既有（幂等，见 §8.3）。

#### 3.3.7 `tms_customs_declarations` 报关单

```sql
CREATE TABLE IF NOT EXISTS tms_customs_declarations (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba',
    declaration_no  VARCHAR(32)  NOT NULL COMMENT '报关单号（18 位数字关单号【待验证】）',
    shipment_id     BIGINT       NOT NULL,
    shipment_no     VARCHAR(32)  NOT NULL,
    batch_no        VARCHAR(32)  NOT NULL DEFAULT '',
    customs_broker  VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '报关行名称',
    broker_contact  VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '报关行联系人电话',
    hs_code_summary VARCHAR(512) NOT NULL DEFAULT '' COMMENT 'HS 编码汇总（去重逗号串，报关单头展示）',
    declared_value  DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '申报货值（原币）',
    currency        CHAR(3)      NOT NULL DEFAULT 'USD',
    duty_amount     DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '关税',
    vat_amount      DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '增值税',
    consumption_tax DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '消费税',
    deposit_amount  DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '海关保证金',
    status          VARCHAR(20)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/FILED/RELEASED/INSPECTING/HELD/REJECTED',
    filed_at        DATETIME     NULL DEFAULT NULL COMMENT '申报时间（UTC）',
    released_at     DATETIME     NULL DEFAULT NULL COMMENT '放行时间（UTC）',
    remark          VARCHAR(255) NOT NULL DEFAULT '',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_decl_no (tenant_id, declaration_no),
    KEY idx_shipment (shipment_id),
    KEY idx_tenant (tenant_id, id),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '出口报关单';
```

#### 3.3.8 `tms_rate_cards` / `tms_rate_card_items` 运价卡

```sql
CREATE TABLE IF NOT EXISTS tms_rate_cards (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba',
    card_code       VARCHAR(32)  NOT NULL COMMENT '运价卡编号 RC+yyyyMM+3位',
    card_name       VARCHAR(64)  NOT NULL COMMENT '运价卡名称',
    carrier_id      BIGINT       NOT NULL COMMENT 'tms_carriers.id',
    route_id        BIGINT       NOT NULL COMMENT 'tms_routes.id',
    mode            VARCHAR(16)  NOT NULL DEFAULT 'SEA',
    container_type  VARCHAR(8)   NOT NULL DEFAULT '20GP',
    incoterm        VARCHAR(8)   NOT NULL DEFAULT 'FOB',
    currency        CHAR(3)      NOT NULL DEFAULT 'USD',
    transit_days    INT          NOT NULL DEFAULT 0 COMMENT '时效（天）',
    valid_from      DATE         NULL DEFAULT NULL COMMENT '生效日（含）',
    valid_to        DATE         NULL DEFAULT NULL COMMENT '失效日（含）',
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE生效/INACTIVE停用',
    remark          VARCHAR(255) NOT NULL DEFAULT '',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_card_code (tenant_id, card_code),
    KEY idx_tenant (tenant_id, id),
    KEY idx_route (route_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '运价卡主表';

CREATE TABLE IF NOT EXISTS tms_rate_card_items (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba',
    rate_card_id    BIGINT       NOT NULL COMMENT 'tms_rate_cards.id',
    charge_type     VARCHAR(16)  NOT NULL COMMENT 'BASE基础运费/BAF燃油附加费/SAF安全附加费/THC码头操作费/REMOTE偏远附加费/GRI旺季附加费/CUSTOMS_BROKER关税代理费/DOC_FILE单证费/ENSAMS舱单费',
    charge_name     VARCHAR(64)  NOT NULL COMMENT '费用中文名',
    amount          DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '金额（原币）',
    calc_type       VARCHAR(16)  NOT NULL DEFAULT 'FIXED' COMMENT 'FIXED定额/PERCENT百分比/BASE_RATE百分比',
    calc_value      DECIMAL(8,4) NOT NULL DEFAULT 0 COMMENT 'calc_type 非 FIXED 时为百分比（如 15.0000 = 15%）',
    remark          VARCHAR(255) NOT NULL DEFAULT '',
    PRIMARY KEY (id),
    KEY idx_card (rate_card_id),
    KEY idx_tenant (tenant_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '运价卡费用项';
```

#### 3.3.9 `tms_fx_rates` 汇率

```sql
CREATE TABLE IF NOT EXISTS tms_fx_rates (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba',
    base_currency   CHAR(3)      NOT NULL COMMENT '基准币种（如 USD）',
    quote_currency  CHAR(3)      NOT NULL COMMENT '报价币种（如 CNY）',
    rate            DECIMAL(16,8) NOT NULL COMMENT '汇率：1 base = rate quote',
    rate_date       DATE         NOT NULL COMMENT '汇率取值时点（哪一天的价）',
    source          VARCHAR(16)  NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL人工录入/SYSTEM系统',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pair_date (tenant_id, base_currency, quote_currency, rate_date),
    KEY idx_tenant (tenant_id, id),
    KEY idx_pair (base_currency, quote_currency)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '汇率（含取值时点）';
```

### 3.4 WMS 库表结构

#### 3.4.1 `wms_warehouses` 增列

| 列名 | 类型 | 说明 |
| --- | --- | --- |
| `warehouse_type` | `VARCHAR(16) NOT NULL DEFAULT 'DOMESTIC'` | `DOMESTIC`/`OVERSEAS`/`BONDED`/`TRANSIT` |
| `country` | `VARCHAR(2) NOT NULL DEFAULT 'CN'` | 所在国 |
| `timezone` | `VARCHAR(32) NOT NULL DEFAULT 'Asia/Shanghai'` | IANA 时区 |
| `oversea_operator` | `VARCHAR(64) NOT NULL DEFAULT ''` | 海外仓服务商（如 XX 海外仓） |
| `is_bonded` | `TINYINT(1) NOT NULL DEFAULT 0` | 1=保税仓 |

#### 3.4.2 `wms_pack_tasks` 备货/装箱/贴标任务

```sql
CREATE TABLE IF NOT EXISTS wms_pack_tasks (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba',
    task_no         VARCHAR(32)  NOT NULL COMMENT '任务号 PK+yyyyMMdd+4位',
    order_no        VARCHAR(32)  NOT NULL COMMENT '关联出口子单号 oms_export_orders.order_no',
    warehouse_id    BIGINT       NOT NULL COMMENT 'wms_warehouses.id',
    location_code   VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '库位编码（批次作业定位）',

    task_type       VARCHAR(16)  NOT NULL DEFAULT 'PACK' COMMENT 'PACK装箱/LABEL贴标（先装箱后贴标，同一任务两阶段）',
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待处理/PICKING拣货中/PACKED已装箱/LABELLED已贴标/HANDED_OVER已交接/SHORTAGE缺料/CANCELLED已取消',

    box_no          VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '箱号（自生成，如 CTN-EXP20261010001-01）',
    container_type  VARCHAR(8)   NOT NULL DEFAULT 'CARTON' COMMENT '外包装：CARTON纸箱/PALLET托盘/BAG袋/DRUM桶',
    pieces          INT          NOT NULL DEFAULT 0 COMMENT '件数',
    gross_weight    DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '毛重 KG',
    volume          DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '体积 CBM',
    volumetric_weight DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '体积重 KG',
    marks           VARCHAR(255) NOT NULL DEFAULT '' COMMENT '唛头（装箱时生成）',
    is_dangerous    TINYINT(1)   NOT NULL DEFAULT 0,
    label_printed_at DATETIME    NULL DEFAULT NULL COMMENT '贴标完成时间（UTC）',
    handed_over_at  DATETIME     NULL DEFAULT NULL COMMENT '交接承运人时间（UTC）',
    assignee_id     BIGINT       NOT NULL DEFAULT 0 COMMENT '作业人 users.id',
    remark          VARCHAR(255) NOT NULL DEFAULT '',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_no (tenant_id, task_no),
    KEY idx_tenant (tenant_id, id),
    KEY idx_order_no (order_no),
    KEY idx_status (status),
    KEY idx_warehouse (warehouse_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '备货/装箱/贴标任务';
```

#### 3.4.3 `wms_pack_task_items` 任务明细

```sql
CREATE TABLE IF NOT EXISTS wms_pack_task_items (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba',
    task_id         BIGINT       NOT NULL COMMENT 'wms_pack_tasks.id',
    sku             VARCHAR(64)  NOT NULL,
    product_name    VARCHAR(128) NOT NULL DEFAULT '',
    batch_no        VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '批次号（FEFO 取用）',
    production_date DATE         NULL DEFAULT NULL COMMENT '生产日期',
    expiry_date     DATE         NULL DEFAULT NULL COMMENT '效期',
    quantity        INT          NOT NULL DEFAULT 0 COMMENT '应备数量',
    picked_quantity INT          NOT NULL DEFAULT 0 COMMENT '实备数量（缺料时小于应备）',
    hs_code         VARCHAR(12)  NOT NULL DEFAULT '' COMMENT 'HS 编码快照（报关要用）',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_task (task_id),
    KEY idx_sku (sku)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '备货任务明细';
```

#### 3.4.4 `wms_inventory_batches` 库存批次与效期

```sql
CREATE TABLE IF NOT EXISTS wms_inventory_batches (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba',
    warehouse_id    BIGINT       NOT NULL,
    sku             VARCHAR(64)  NOT NULL,
    product_name    VARCHAR(128) NOT NULL DEFAULT '',
    batch_no        VARCHAR(32)  NOT NULL COMMENT '批次号',
    production_date DATE         NULL DEFAULT NULL COMMENT '生产日期（FEFO 辅助）',
    expiry_date     DATE         NULL DEFAULT NULL COMMENT '效期（FEFO 主排序键）',
    quantity        INT          NOT NULL DEFAULT 0 COMMENT '在库数量',
    reserved_qty    INT          NOT NULL DEFAULT 0 COMMENT '占用数量（已被任务/订单占用）',
    unit_cost       DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '单位成本',
    location_code   VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '库位',
    status          VARCHAR(16)  NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL正常/NEAR_EXPIRY临期/EXPIRED过期/FROZEN冻结',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wh_sku_batch (warehouse_id, sku, batch_no),
    KEY idx_tenant (tenant_id, id),
    KEY idx_fefo (warehouse_id, sku, expiry_date),
    KEY idx_expiry (expiry_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '库存批次（效期/FEFO）';
```

> **可用量口径**：`available = quantity - reserved_qty`，**不建列、查询时算**。
> 理由：多一张列就多一处「谁负责维护」的问题（现有 `wms_inventory.quantity` 已经由 `StockRecordService` 事务内维护），
> 而本项目没有触发器、没有定时对账，算出来的口径不会漂。

#### 3.4.5 `wms_in_transit` 在途库存

```sql
CREATE TABLE IF NOT EXISTS wms_in_transit (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba',
    shipment_no     VARCHAR(32)  NOT NULL COMMENT 'tms_shipments.shipment_no',
    order_no        VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '出口子单号',
    sku             VARCHAR(64)  NOT NULL DEFAULT '',
    product_name    VARCHAR(128) NOT NULL DEFAULT '',
    batch_no        VARCHAR(32)  NOT NULL DEFAULT '',
    quantity        INT          NOT NULL DEFAULT 0 COMMENT '在途数量',
    from_warehouse_id BIGINT     NOT NULL DEFAULT 0 COMMENT '发货仓',
    to_warehouse_id BIGINT       NOT NULL DEFAULT 0 COMMENT '目的仓（海外仓/目的港仓）',
    shipped_at      DATETIME     NOT NULL COMMENT '出库时间（UTC）',
    arrived_at      DATETIME     NULL DEFAULT NULL COMMENT '到达时间（UTC）',
    status          VARCHAR(16)  NOT NULL DEFAULT 'IN_TRANSIT' COMMENT 'IN_TRANSIT在途/ARRIVED已到仓/RECEIVED已入库/LOST丢件',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_shipment (shipment_no),
    KEY idx_order_no (order_no),
    KEY idx_status (status),
    KEY idx_tenant (tenant_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '在途库存';
```

### 3.5 CRM 库表结构

#### 3.5.1 `customers` 增列

| 列名 | 类型 | 说明 |
| --- | --- | --- |
| `customer_type` | `VARCHAR(16) NOT NULL DEFAULT 'SELLER'` | `SELLER`/`FORWARDER`/`FACTORY`/`DISTRIBUTOR` |
| `country` | `VARCHAR(2) NOT NULL DEFAULT 'CN'` | 国家 |
| `tax_no` | `VARCHAR(32) NOT NULL DEFAULT ''` | 税号 |
| `payment_term_days` | `INT NOT NULL DEFAULT 0` | 账期天数（0=款到发货，30/45/60=月结） |
| `credit_limit` | `DECIMAL(14,2) NOT NULL DEFAULT 0` | 信用额度（原币） |
| `credit_used` | `DECIMAL(14,2) NOT NULL DEFAULT 0` | 已用额度 |
| `default_currency` | `CHAR(3) NOT NULL DEFAULT 'USD'` | 默认币种 |
| `ioss_no` | `VARCHAR(32) NOT NULL DEFAULT ''` | IOSS 编号 |

#### 3.5.2 `crm_carrier_partners` 货代与渠道商

```sql
CREATE TABLE IF NOT EXISTS crm_carrier_partners (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba',
    partner_code    VARCHAR(32)  NOT NULL COMMENT '渠道商编号',
    partner_name    VARCHAR(64)  NOT NULL COMMENT '渠道商名称',
    partner_type    VARCHAR(20)  NOT NULL DEFAULT 'FORWARDER' COMMENT 'FORWARDER货代/NVOCC无船承运人/AGENT货代代理/CLEARANCE_BROKER报关行/LAST_MILE尾程派送/TRUCKING卡航',
    contact_name    VARCHAR(64)  NOT NULL DEFAULT '',
    contact_phone   VARCHAR(32)  NOT NULL DEFAULT '',
    contact_email   VARCHAR(64)  NOT NULL DEFAULT '',
    country         VARCHAR(2)   NOT NULL DEFAULT 'CN',
    currency        CHAR(3)      NOT NULL DEFAULT 'USD' COMMENT '结算币种',
    payment_term_days INT        NOT NULL DEFAULT 0 COMMENT '我方对其账期（天）',
    credit_limit    DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '我方在其处的信用额度',
    credit_used     DECIMAL(14,2) NOT NULL DEFAULT 0,
    rating          TINYINT      NOT NULL DEFAULT 3 COMMENT '合作评级 1~5',
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE合作中/INACTIVE已停用',
    remark          VARCHAR(255) NOT NULL DEFAULT '',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_partner_code (tenant_id, partner_code),
    KEY idx_tenant (tenant_id, id),
    KEY idx_type (partner_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '货代与渠道商';
```

#### 3.5.3 `crm_quotations` / `crm_quotation_items` 报价单

```sql
CREATE TABLE IF NOT EXISTS crm_quotations (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba',
    quotation_no    VARCHAR(32)  NOT NULL COMMENT '报价单号 QT+yyyyMMdd+4位',
    customer_id     BIGINT       NOT NULL COMMENT 'customers.id',
    customer_name   VARCHAR(128) NOT NULL DEFAULT '',
    pol_code        VARCHAR(8)   NOT NULL DEFAULT '' COMMENT '起运港',
    pod_code        VARCHAR(8)   NOT NULL DEFAULT '' COMMENT '目的港',
    mode            VARCHAR(16)  NOT NULL DEFAULT 'SEA' COMMENT 'SEA/AIR/RAIL/TRUCK/EXPRESS/LCL',
    container_type  VARCHAR(8)   NOT NULL DEFAULT '20GP',
    container_qty   INT          NOT NULL DEFAULT 1,
    charge_weight_mode VARCHAR(16) NOT NULL DEFAULT 'KG' COMMENT 'KG按重/R/TON按吨/PIECE按件',
    charge_weight   DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '计费量',
    rate_card_id    BIGINT       NOT NULL DEFAULT 0 COMMENT 'tms_rate_cards.id（跨库弱引用）',
    currency        CHAR(3)      NOT NULL DEFAULT 'USD',
    base_amount     DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '基础运费合计',
    surcharge_total DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '附加费合计',
    total_amount    DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '总计',
    transit_days    INT          NOT NULL DEFAULT 0 COMMENT '参考时效',
    valid_until     DATE         NULL DEFAULT NULL COMMENT '有效期至',
    status          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT草稿/SENT已报价/WON已成交/LOST已流失/EXPIRED已过期',
    owner_id        BIGINT       NOT NULL DEFAULT 0 COMMENT '归属人 users.id',
    owner_name      VARCHAR(64)  NOT NULL DEFAULT '',
    remark          VARCHAR(255) NOT NULL DEFAULT '',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_quotation_no (tenant_id, quotation_no),
    KEY idx_tenant (tenant_id, id),
    KEY idx_customer (customer_id),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '出口报价单';

CREATE TABLE IF NOT EXISTS crm_quotation_items (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba',
    quotation_id    BIGINT       NOT NULL COMMENT 'crm_quotations.id',
    charge_type     VARCHAR(16)  NOT NULL COMMENT '费用类型码，与 tms_rate_card_items.charge_type 同字典',
    charge_name     VARCHAR(64)  NOT NULL COMMENT '费用中文名',
    amount          DECIMAL(14,2) NOT NULL DEFAULT 0,
    remark          VARCHAR(255) NOT NULL DEFAULT '',
    PRIMARY KEY (id),
    KEY idx_quotation (quotation_id),
    KEY idx_tenant (tenant_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '报价单费用明细';
```

#### 3.5.4 `crm_contracts` 合同 / 协议价

```sql
CREATE TABLE IF NOT EXISTS crm_contracts (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba',
    contract_no     VARCHAR(32)  NOT NULL COMMENT '合同号 CT+yyyyMMdd+3位',
    customer_id     BIGINT       NOT NULL,
    customer_name   VARCHAR(128) NOT NULL DEFAULT '',
    quotation_id    BIGINT       NOT NULL DEFAULT 0 COMMENT 'crm_quotations.id',
    sign_date       DATE         NULL DEFAULT NULL COMMENT '签约日',
    start_date      DATE         NULL DEFAULT NULL,
    end_date        DATE         NULL DEFAULT NULL COMMENT '到期日',
    currency        CHAR(3)      NOT NULL DEFAULT 'USD',
    amount          DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '合同金额',
    payment_term_days INT        NOT NULL DEFAULT 0 COMMENT '账期天数',
    credit_limit    DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '信用额度',
    status          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/SIGNED签约/ACTIVE履约中/EXPIRED已到期/TERMINATED已解除',
    owner_id        BIGINT       NOT NULL DEFAULT 0,
    remark          VARCHAR(255) NOT NULL DEFAULT '',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_contract_no (tenant_id, contract_no),
    KEY idx_tenant (tenant_id, id),
    KEY idx_customer (customer_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '出口合同与协议价';
```

#### 3.5.5 `crm_tickets` 异常工单

```sql
CREATE TABLE IF NOT EXISTS crm_tickets (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba',
    ticket_no       VARCHAR(32)  NOT NULL COMMENT '工单号 TK+yyyyMMdd+4位',
    customer_id     BIGINT       NOT NULL DEFAULT 0,
    customer_name   VARCHAR(128) NOT NULL DEFAULT '',
    order_no        VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '出口子单号',
    shipment_no     VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '国际运单号',
    category        VARCHAR(16)  NOT NULL DEFAULT 'DELAY' COMMENT 'DELAY延误/DAMAGE破损/CUSTOMS_HOLD清关查验/AMEND改单/ABANDON弃货/OTHER其他',
    severity        VARCHAR(8)   NOT NULL DEFAULT 'MEDIUM' COMMENT 'LOW/MEDIUM/HIGH',
    status          VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN待处理/FOLLOWING跟进中/RESOLVED已解决/CLOSED已关闭',
    title           VARCHAR(128) NOT NULL COMMENT '标题',
    detail          VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '问题描述',
    resolution      VARCHAR(500) NOT NULL DEFAULT '' COMMENT '处理结果',
    owner_id        BIGINT       NOT NULL DEFAULT 0 COMMENT '跟进人 users.id',
    owner_name      VARCHAR(64)  NOT NULL DEFAULT '',
    due_at          DATETIME     NULL DEFAULT NULL COMMENT 'SLA 到期（UTC）',
    resolved_at     DATETIME     NULL DEFAULT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ticket_no (tenant_id, ticket_no),
    KEY idx_tenant (tenant_id, id),
    KEY idx_status (status),
    KEY idx_shipment (shipment_no),
    KEY idx_order_no (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '出口异常工单';
```

### 3.6 状态机（三套 + 触发方）

> 规则：所有状态迁移都在 Service 里用一张**常量 Map** 校验，非法迁移抛
> `IllegalArgumentException("非法状态迁移: X → Y")` → 全局异常处理器转 **400 `invalid_argument`**。
> 前端下拉只列出「当前状态可迁移到的状态」，不靠前端自己判断（与现有 `opportunities.moveStage` 的 PATCH 语义一致）。

#### 3.6.1 OMS 出口子单状态机（`oms_export_orders.status`）

| # | 状态 | 含义 | 允许迁移到 | 触发方 |
| --- | --- | --- | --- | --- |
| 1 | `DRAFT` | 草稿 | CONFIRMED / CANCELLED | 人工 |
| 2 | `CONFIRMED` | 已确认（客户下单） | PICKING / CANCELLED | 人工 |
| 3 | `PICKING` | 备货拣货中 | PACKED / EXCEPTION / CANCELLED | 人工 / WMS 任务到 PACKED 自动 |
| 4 | `PACKED` | 已装箱贴标 | EXPORT_DECLARED / EXCEPTION / CANCELLED | 人工 |
| 5 | `EXPORT_DECLARED` | 已出口报关放行 | SHIPPED / EXCEPTION | 人工（报关单 RELEASED 时联动） |
| 6 | `SHIPPED` | 已发运（已装船/已起飞） | IN_TRANSIT / EXCEPTION | 人工 / TMS 运单 DEPARTED 联动 |
| 7 | `IN_TRANSIT` | 在途 | ARRIVED / EXCEPTION | 人工 / TMS 运单 IN_TRANSIT 联动 |
| 8 | `ARRIVED` | 已到港 | CUSTOMS_CLEARING / EXCEPTION | 人工 / TMS 运单 ARRIVED 联动 |
| 9 | `CUSTOMS_CLEARING` | 目的港清关中 | CUSTOMS_CLEARED / EXCEPTION | 人工 |
| 10 | `CUSTOMS_CLEARED` | 已清关放行 | LAST_MILE / EXCEPTION | 人工 |
| 11 | `LAST_MILE` | 尾程派送中 | DELIVERED / EXCEPTION | 人工 |
| 12 | `DELIVERED` | 已妥投 | CLOSED / EXCEPTION | 人工 |
| 13 | `CLOSED` | 已完结 | —（终态） | — |
| 14 | `EXCEPTION` | 异常挂起 | 回到挂起前的状态 / CLOSED | 人工 |
| 15 | `CANCELLED` | 已取消 | —（终态） | — |

#### 3.6.2 TMS 运单状态机（`tms_shipments.status`）

| # | 状态 | 含义 | 允许迁移到 | 触发方 |
| --- | --- | --- | --- | --- |
| 1 | `DRAFT` | 草稿（本地建单） | BOOKING / CANCELLED | 人工 |
| 2 | `BOOKING` | 订舱中 | BOOKED / EXCEPTION / CANCELLED | 人工（订舱回执后点「已订舱」） |
| 3 | `BOOKED` | 已订舱（S/O 已确认） | PICKED_UP / EXCEPTION / CANCELLED | 人工 |
| 4 | `PICKED_UP` | 已揽收 | EXPORT_DECLARED / EXCEPTION | 人工 / WMS 任务 HANDED_OVER 联动 |
| 5 | `EXPORT_DECLARED` | 已出口报关 | DEPARTED / EXCEPTION | 人工（报关单 RELEASED 联动） |
| 6 | `DEPARTED` | 已开航/已起飞 | IN_TRANSIT / EXCEPTION | 人工 / M3 定时任务（ETD 到点自动） |
| 7 | `IN_TRANSIT` | 在途 | ARRIVED / EXCEPTION | 人工 / 承运商回调 / M3 定时 |
| 8 | `ARRIVED` | 已到港 | CLEARING / EXCEPTION | 人工 / 承运商回调 / M3 定时（ETA 到点） |
| 9 | `CLEARING` | 清关中 | CLEARED / EXCEPTION | 人工 / 清关服务商回调 |
| 10 | `CLEARED` | 已清关放行 | LAST_MILE / EXCEPTION | 人工 |
| 11 | `LAST_MILE` | 尾程派送中 | DELIVERED / EXCEPTION | 人工 / 尾程商回调 |
| 12 | `DELIVERED` | 已妥投 | POD_CONFIRMED / EXCEPTION | 人工 / 尾程商回调 |
| 13 | `POD_CONFIRMED` | 签收已回传 | —（终态） | 人工（上传 POD） |
| 14 | `EXCEPTION` | 异常挂起 | 回到挂起前的状态 / RETURNED / CANCELLED | 人工（必填 exception_reason） |
| 15 | `RETURNED` | 已退运/退关 | CANCELLED | 人工 |
| 16 | `CANCELLED` | 已取消 | —（终态） | — |

#### 3.6.3 WMS 备货任务状态机（`wms_pack_tasks.status`）

| # | 状态 | 含义 | 允许迁移到 | 触发方 |
| --- | --- | --- | --- | --- |
| 1 | `PENDING` | 待处理 | PICKING / CANCELLED | 人工 |
| 2 | `PICKING` | 拣货中 | PACKED / SHORTAGE / CANCELLED | 人工 |
| 3 | `PACKED` | 已装箱（唛头生成、毛重体积回填） | LABELLED / SHORTAGE | 人工 |
| 4 | `LABELLED` | 已贴标 | HANDED_OVER / SHORTAGE | 人工 |
| 5 | `HANDED_OVER` | 已交接承运人 | —（终态，同时联动 OMS 子单 → PACKED） | 人工 |
| 6 | `SHORTAGE` | 缺料（实备 < 应备） | PICKING / CANCELLED | 人工 / 自动判定 |
| 7 | `CANCELLED` | 已取消 | —（终态） | 人工 |

#### 3.6.4 报关单状态（`tms_customs_declarations.status`）

`DRAFT` → `FILED`（已申报）→ `RELEASED`（放行）／`INSPECTING`（查验中）→ `RELEASED`／`HELD`（扣货）→ `RELEASED`／`REJECTED`（退单，终态）
进入 `RELEASED` 时**联动**：母单 `BOOKED/PICKED_UP → EXPORT_DECLARED`，子单 `PACKED → EXPORT_DECLARED`。

### 3.7 跨服务实体映射（同一业务对象在各库里的 id 如何对应）

| 业务对象 | OMS 库 | WMS 库 | TMS 库 | CRM 库 | 关联方式 |
| --- | --- | --- | --- | --- | --- |
| **客户** | `oms_export_orders.customer_id` | — | — | `customers.id` | 存 CRM 的 `id`（弱引用）；同时冗余 `customer_name` 快照 |
| **出口子单**（一笔货） | `oms_export_orders.order_no` | `wms_pack_tasks.order_no` | `tms_shipment_items.order_no` | `crm_tickets.order_no` | **`order_no` 字符串**，四库同值 |
| **出口母单**（一票货） | `oms_export_batches.batch_no` | — | `tms_shipments.batch_no` | — | **`batch_no` 字符串** |
| **国际运单** | `oms_export_batches.shipment_no`（见 §12 说明：为聚合回填预留） | `wms_in_transit.shipment_no` | `tms_shipments.shipment_no` | `crm_tickets.shipment_no` | **`shipment_no` 字符串** |
| **SKU / 商品** | `oms_export_order_items.product_id` → `oms_products.id` | `wms_pack_task_items.sku` / `wms_inventory_batches.sku` | 快照（不外键） | — | `oms_products.id` 内部引用；跨库用 `sku` 字符串 |
| **承运商** | — | — | `tms_carriers.id` | `crm_carrier_partners`（**独立实体，不与承运商合表**） | 两套主数据并行：TMS 管「运输能力」，CRM 管「合作关系与账期」 |
| **航线** | `oms_export_batches.route_id`（弱引用） | — | `tms_routes.id` | `crm_quotations` 只存 pol/pod 字符串 | `route_id` 弱引用 + `pol_code`/`pod_code` 冗余 |
| **运价卡** | — | — | `tms_rate_cards.id` | `crm_quotations.rate_card_id`（弱引用） | 弱引用；报价单同时把金额快照进 `crm_quotation_items`，**运价卡改价不影响已报出的价** |
| **箱** | — | `wms_pack_tasks.box_no` | `tms_shipment_items.container_no` | — | **箱号字符串**；`container_no`（真箱号）由 TMS 侧在装柜时回填 |
| **汇率** | — | — | `tms_fx_rates` | 报价单只存 `currency` + 金额 | 报价单不存汇率快照（金额已折算成本位币），需要追溯时按 `rate_date` 反查 |

**跨库 ID 使用的三条硬规则**（实现时必须遵守，否则会出现「跨租户串号」）：

1. 任何 `customer_id` / `product_id` / `route_id` / `rate_card_id` 都**只是显示用的弱引用**，
   列表渲染**不得**因为该 id 在对方库不存在而报错——查不到就显示 `—`。
2. 写路径**不校验**弱引用是否真在对方库存在（跨库校验需要网络调用，会把一次本地事务变成三次远程调用）。
   唯一例外：`crm_quotations.customer_id` 是**同库**引用，写入前必须 `customerService.get()` 过闸（沿用现有 `OpportunityController` 的做法）。
3. 所有跨库查询一律带 `tenant_id = TenantContext.get()`，
   `LinkageClient` 直连时**必须**写 `X-Tenant-Id`（现有实现已如此，且注释已说明漏发会污染阿里巴巴租户）。

---

## 4. 接口清单

### 4.0 通用约定（沿用现有实现，不再重复解释）

- **权限点**：按假设 A5，**后端不做权限点校验**，全部登录用户可读写（与现有四个业务服务完全一致）。
  权限点只用于 web 菜单显隐 + 路由守卫。下表「权限点」列的含义是「**该页面/按钮在 Web 端需要的权限点**」，
  实现时要同步在 `AppLayout.tsx` 菜单与 `App.tsx` 路由上体现。行不通则前端不渲染。
- **行级过滤**：所有列表默认 `AND tenant_id = :tenant`（`TenantContext.get()`）。
  标注「按 `owner_id`」的端点额外带 `AND owner_id = <X-User-Id>`（个人工作台口径，沿用 `WorkbenchService`）。
- **分页信封**：`{ list: [], total: long, page: int, size: int }`，与现有 `PageResult<T>` 一致。
- **错误体**：`{ code, message }`，沿用各服务 `GlobalExceptionHandler`。
  非法状态迁移 → `400 invalid_argument`；跨租户/不存在 → `404 not_found`；下游不可用 → `502 linkage_failed`（OMS）。
- **`/brief` 端点**：下拉选择器用，返回 ≤500 行、只含必要字段（沿用现有约定）。
- **内部调用头**：OMS→TMS/WMS 的直连继续用 `LinkageClient`，携带 `X-Tenant-Id` + 透传 `X-User-Id`/`X-User-Roles`。

### 4.1 TMS 新增端点

#### 4.1.1 承运商 `/api/tms/intl/carriers`

| 方法 | 路径 | 入参 | 返回 | 权限点 | 行级过滤 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/tms/intl/carriers` | `keyword, carrierType, status, page=1, size=10` | `PageResult<Carrier>` | `tms:intl:view` | `tenant_id` |
| GET | `/api/tms/intl/carriers/brief` | `carrierType`（可选） | `List<CarrierBrief>` | `tms:intl:view` | `tenant_id` |
| GET | `/api/tms/intl/carriers/{id}` | — | `Carrier` | `tms:intl:view` | `tenant_id` |
| POST | `/api/tms/intl/carriers` | `CarrierPayload` | `Carrier` | `tms:intl:edit` | `tenant_id` |
| PUT | `/api/tms/intl/carriers/{id}` | `CarrierPayload`（id 可空） | `Carrier` | `tms:intl:edit` | `tenant_id` |
| DELETE | `/api/tms/intl/carriers/{id}` | — | 204 | `tms:intl:edit` | `tenant_id`，有运单引用时 409 `data_in_use` |

`CarrierPayload` 字段与校验：
```
Long id,
@NotBlank @Size(max=16) String code,            // 承运商代码
@NotBlank @Size(max=64) String nameZh,
@Size(max=64) String nameEn,
@NotBlank @Pattern("(OCEAN|AIR|EXPRESS|NVOCC|FORWARDER|AGENT|CLEARANCE|LAST_MILE)") String carrierType,
@Size(max=2)  String country,
@Size(max=64) String contactName, @Size(max=32) String contactPhone, @Size(max=64) String contactEmail,
@Pattern("(STANDARD|EXPRESS|ECONOMY)") String serviceLevel,
@Min(0) @Max(365) Integer transitDays,
@Min(0) @Max(168) Integer cutOffHours,
@Min(0) @Max(90)  Integer freeTimeDays,
@Pattern("(ACTIVE|INACTIVE)") String status,
@Size(max=255) String remark
```

#### 4.1.2 航线与船期

| 方法 | 路径 | 入参 | 返回 | 权限点 |
| --- | --- | --- | --- | --- |
| GET | `/api/tms/intl/routes` | `keyword, mode, polCode, podCode, page, size` | `PageResult<Route>` | `tms:intl:view` |
| GET | `/api/tms/intl/routes/brief` | `mode`（可选） | `List<RouteBrief>` | `tms:intl:view` |
| GET | `/api/tms/intl/routes/{id}` | — | `Route` | `tms:intl:view` |
| POST/PUT/DELETE | `/api/tms/intl/routes[/{id}]` | `RoutePayload` | `Route` | `tms:intl:edit` |
| GET | `/api/tms/intl/sailings` | `routeId, status, etdFrom, etdTo, page, size` | `PageResult<Sailing>` | `tms:intl:view` |
| GET | `/api/tms/intl/sailings/{id}` | — | `Sailing` | `tms:intl:view` |
| POST/PUT/DELETE | `/api/tms/intl/sailings[/{id}]` | `SailingPayload` | `Sailing` | `tms:intl:edit` |

`RoutePayload`：`carrierId`(NotNull) / `routeCode`(NotBlank,32) / `mode`(@Pattern SEA|AIR|RAIL|TRUCK|EXPRESS|LCL) / `polCode`(NotBlank,8) / `podCode`(NotBlank,8) / `transitDays`(0~365) / `volumetricDivisor`(@Min 1 @Max 20000，默认 6000) / `viaPorts`(128) / `status`。
`SailingPayload`：`routeId`(NotNull) / `vesselName`(64) / `voyageNo`(32) / `etdAt`(NotNull，ISO `yyyy-MM-dd'T'HH:mm:ss`，**UTC**） / `etaAt`(NotNull) / `cutoffAt`(可空) / `freeTimeDays`(0~90) / `spaceLeft`(0~99999) / `status`(SCHEDULED|BOOKING|CLOSED|CANCELLED)。

#### 4.1.3 国际运单 `/api/tms/intl/shipments`（核心）

| 方法 | 路径 | 入参 | 返回 | 权限点 | 行级过滤 |
| --- | --- | --- | --- | --- | --- |
| GET | `` | `keyword, status, mode, carrierId, batchNos(逗号分隔), orderNos(逗号分隔), polCode, podCode, etdFrom, etdTo, page, size` | `PageResult<Shipment>` | `tms:intl:view` | `tenant_id` |
| GET | `/brief` | — | `List<ShipmentBrief>` | `tms:intl:view` | `tenant_id` |
| GET | `/{id}` | — | `ShipmentDetail`（含 items + trackingNodes + declaration） | `tms:intl:view` | `tenant_id` |
| GET | `/by-no/{shipmentNo}` | 路径参数 | `ShipmentDetail` | `tms:intl:view` | `tenant_id` |
| POST | `` | `ShipmentPayload`（含 `items`） | `ShipmentDetail` | `tms:intl:edit` | `tenant_id` |
| PUT | `/{id}` | `ShipmentPayload`（**只改可改字段**：容器/唛头/时效/备注） | `ShipmentDetail` | `tms:intl:edit` | `tenant_id` |
| DELETE | `/{id}` | — | 204 | `tms:intl:edit` | `status != DEPARTED` 否则 409 |
| **PATCH** | `/{id}/status` | `{"status":"BOOKED","remark":"...","exceptionReason":"..."}` | `ShipmentDetail` | `tms:intl:edit` | 状态机校验，非法 → 400 |
| **POST** | `/{id}/tracking` | `TrackingPayload` | `ShipmentDetail` | `tms:intl:edit` | 幂等（见 §8.3） |
| GET | `/{id}/tracking` | — | `List<TrackingNode>` | `tms:intl:view` | `tenant_id` |
| GET | `/{id}/items` | — | `List<ShipmentItem>` | `tms:intl:view` | `tenant_id` |
| POST/PUT/DELETE | `/{id}/items[/{itemId}]` | `ShipmentItemPayload` | `ShipmentItem` | `tms:intl:edit` | `tenant_id` |
| GET | `/dashboard` | — | `IntlDashboard` | `tms:intl:view` | `tenant_id` |

`ShipmentPayload`：
```
Long id,
@Size(max=32) String batchNo,          // 母单号
@Size(max=32) String awbNo,             // 空运单号
@Size(max=32) String blNo,              // 海运提单号
@Size(max=32) String bookingNo,         // S/O
Long sailingId,
@Pattern("(SEA|AIR|RAIL|TRUCK|EXPRESS|LCL)") String mode,
@NotNull Long carrierId,
@Size(max=8) String polCode, @Size(max=8) String podCode,
@Pattern("(20GP|40GP|40HQ|LCL)") String containerType,
@Min(0) Integer containerQty,
@Pattern("(EXW|FOB|CIF|DDP|...)") String incoterm,      // 枚举见 §3.7 注
@Pattern("[A-Z]{3}") String currency,
@Min(0) Integer totalPieces,
@DecimalMin("0") BigDecimal totalGrossWeight, totalVolume, volumetricWeight, chargeableWeight, vgmWeight,
@Size(max=255) String marks,
LocalDateTime etdAt, etaAt, cutoffAt,                 // ISO 字符串，UTC
@Min(0) @Max(1) Integer isDangerous,
@Size(max=16) String unNumber, @Size(max=8) String dgClass,
@Pattern("(CLEAR|PENDING|HIT)") String sanctionFlag,
@Size(max=32) String iossNo,
@Size(max=255) String remark,
List<ShipmentItemPayload> items
```

`ShipmentItemPayload`：`orderNo`(32) / `houseNo`(32) / `containerNo`(16) / `containerType`(8) / `sealNo`(16) / `marks`(255) / `pieces` / `grossWeight` / `volume` / `volumetricWeight` / `isDangerous` / `unNumber` / `dgClass`。

`TrackingPayload`：`nodeCode`(NotBlank,24) / `nodeName`(64) / `nodeTime`(NotNull，ISO，UTC) / `location`(128) / `remark`(255)。
`source` **不由前端传**，服务端按调用来源固定：走网关的页面调用 → `MANUAL`；承运商回调入口 → `CARRIER_CALLBACK`。

**承运商回调入口**（M3）：
```
POST /api/tms/intl/callback/tracking
Header: X-Internal-Token: <shared secret>
Body:   {"carrierCode":"COSCO","awbNo":"COSU6123456","nodes":[{...}]}
```
→ 返回 `{"accepted": n, "duplicated": m}`。该路径**不经网关**（承运商服务器直连容器网络 `http://tms:8084`），
或在网关白名单里放 `/api/tms/intl/callback/**`（**待评审**：网关白名单是无鉴权放行，开放外部回调需要额外签名校验，本计划建议直连 + `X-Internal-Token`）。

#### 4.1.4 报关 `/api/tms/intl/customs`

| 方法 | 路径 | 入参 | 返回 | 权限点 |
| --- | --- | --- | --- | --- |
| GET | `` | `keyword, status, shipmentNos, page, size` | `PageResult<CustomsDeclaration>` | `tms:intl:view` |
| GET | `/{id}` | — | `CustomsDeclaration` | `tms:intl:view` |
| POST | `` | `CustomsDeclarationPayload` | `CustomsDeclaration` | `tms:intl:edit` |
| PUT | `/{id}` | `CustomsDeclarationPayload` | `CustomsDeclaration` | `tms:intl:edit` |
| PATCH | `/{id}/status` | `{"status":"RELEASED"}` | `CustomsDeclaration` | `tms:intl:edit` |
| GET | `/{id}/tax-summary` | — | `{declaredValue, duty, vat, consumptionTax, deposit, total, currency}` | `tms:intl:view` |

`PATCH /status` 进 `RELEASED` 时**联动**（见 §8.2）：TMS 运单 `BOOKED|PICKED_UP → EXPORT_DECLARED`，
并回调 OMS 把母单内所有子单 `PACKED → EXPORT_DECLARED`。

#### 4.1.5 运价与汇率

| 方法 | 路径 | 入参 | 返回 | 权限点 |
| --- | --- | --- | --- | --- |
| GET | `/api/tms/intl/rate-cards` | `keyword, carrierId, routeId, status, page, size` | `PageResult<RateCard>` | `tms:intl:view` |
| GET | `/api/tms/intl/rate-cards/{id}` | — | `RateCardDetail`（含 items） | `tms:intl:view` |
| POST/PUT/DELETE | `/api/tms/intl/rate-cards[/{id}]` | `RateCardPayload`（含 `items`） | `RateCardDetail` | `tms:intl:edit` |
| GET | `/api/tms/intl/rate-cards/{id}/quote` | `containerType, containerQty, chargeWeight, chargeWeightMode` | `{base, surcharges[], total, currency, transitDays}` | `tms:intl:view` |
| GET | `/api/tms/intl/fx-rates` | `baseCurrency, quoteCurrency, rateDate` | `List<FxRate>` | `tms:intl:view` |
| POST | `/api/tms/intl/fx-rates` | `FxRatePayload` | `FxRate` | `tms:intl:edit` |

**试算端点**的算法（写清楚，避免实现时各写各的）：
```
1. base   = rate_card_items 中 charge_type='BASE' 的 amount × containerQty
2. 其余项按 calc_type：
   FIXED       → amount × containerQty
   PERCENT     → base × calc_value / 100
   BASE_RATE   → base × calc_value / 100
3. total = base + Σ surcharges
```
海运通常只给 `BASE`（整柜一口价），空运按 KG 给（`chargeWeightMode=KG`，`BASE` 也按 `chargeWeight × amount` 算）——
因此 `quote` 端点需要按 `mode` 分支：`AIR/EXPRESS` 用重量口径，其余用柜量口径。

#### 4.1.6 TMS 看板 `/api/tms/intl/dashboard`

返回结构（与现有 `DashboardService` 的 record 风格一致）：
```json
{
  "shipment": { "total": 68, "draft": 4, "booking": 3, "booked": 6, "pickedUp": 4,
                "declared": 3, "departed": 9, "inTransit": 14, "arrived": 7,
                "clearing": 5, "cleared": 4, "lastMile": 3, "delivered": 5,
                "exception": 1, "statusDistribution": [{"status":"IN_TRANSIT","count":14}] },
  "etd":      { "todayDepart": 2, "next7Days": 5, "overdue": 1 },
  "containers":{"20GP": 18, "40GP": 22, "40HQ": 14, "LCL": 6},
  "weight":   { "totalGross": 182340.500, "totalVolume": 612.400, "totalChargeable": 198120.750 }
}
```

### 4.2 OMS 新增端点

| 方法 | 路径 | 入参 | 返回 | 权限点 | 行级过滤 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/oms/intl/export-orders` | `keyword, status, batchNo, customerId, consigneeCountry, incoterm, currency, etdFrom, etdTo, page, size` | `PageResult<ExportOrder>`（含聚合列 `batchNo/batchStatus/shipmentNo/shipmentStatus/awbNo/etd/eta/packStatus`） | `oms:intl:view` | `tenant_id` |
| GET | `/api/oms/intl/export-orders/brief` | `status`（可选） | `List<ExportOrderBrief>` | `oms:intl:view` | `tenant_id` |
| GET | `/api/oms/intl/export-orders/{id}` | — | `ExportOrderDetail`（子单 + 明细 + 母单 + 运单 + 备货任务聚合） | `oms:intl:view` | `tenant_id` |
| POST | `` | `ExportOrderPayload`（含 `items`） | `ExportOrderDetail` | `oms:intl:edit` | `tenant_id` |
| PUT | `/{id}` | `ExportOrderPayload` | `ExportOrderDetail` | `oms:intl:edit` | `is_batch_locked=0` 才允许改明细 |
| DELETE | `/{id}` | — | 204 | `oms:intl:edit` | `status ∈ {DRAFT, CONFIRMED}` 否则 409 |
| PATCH | `/{id}/status` | `{"status":"CONFIRMED"}` | `ExportOrderDetail` | `oms:intl:edit` | 状态机校验 |
| GET | `/api/oms/intl/export-orders/{id}/items` | — | `List<ExportOrderItem>` | `oms:intl:view` | `tenant_id` |
| GET | `/api/oms/intl/batches` | `keyword, status, mode, polCode, podCode, page, size` | `PageResult<ExportBatch>` | `oms:intl:view` | `tenant_id` |
| GET | `/api/oms/intl/batches/{id}` | — | `ExportBatchDetail`（母单 + 全部子单 + 运单 + 报关聚合） | `oms:intl:view` | `tenant_id` |
| POST | `` | `ExportBatchPayload`（含 `orderNos` 组批） | `ExportBatchDetail` | `oms:intl:edit` | `tenant_id` |
| PUT | `/{id}` | `ExportBatchPayload` | `ExportBatchDetail` | `oms:intl:edit` | `tenant_id` |
| DELETE | `/{id}` | — | 204 | `oms:intl:edit` | 无运单引用 |
| POST | `/{id}/orders` | `{"orderNos":["EXP...","EXP..."]}` | `ExportBatchDetail` | `oms:intl:edit` | 加入母批（子单未组批才允许） |
| DELETE | `/{id}/orders/{orderNo}` | — | `ExportBatchDetail` | `oms:intl:edit` | 移出母批 |
| PATCH | `/{id}/status` | `{"status":"BOOKED"}` | `ExportBatchDetail` | `oms:intl:edit` | 状态机校验 |
| POST | `/{id}/ship` | `{"carrierId":1,"mode":"SEA","polCode":"CNSHA","podCode":"USNYC","containerType":"40HQ","containerQty":2}` | `ShipmentDetail`(TMS 侧结构) | `oms:intl:edit` | **联动创建 TMS 运单**，见 §8.2 |
| GET | `/api/oms/intl/dashboard` | — | `IntlOrderDashboard` | `oms:intl:view` | `tenant_id` |

`ExportOrderPayload`：
```
Long id,
@Size(max=32) String orderNo,          // 空则自动生成 EXP+yyyyMMdd+3位
@NotNull Long customerId,               // CRM customers.id
@Size(max=128) String customerName,
@NotBlank @Size(max=64) String consigneeName,
@Size(max=128) String consigneeCompany,
@Pattern("^[A-Z]{2}$") String consigneeCountry,
@NotBlank @Size(max=255) String consigneeAddress,
@Size(max=32) String consigneePhone, @Size(max=64) String consigneeEmail,
@Pattern("(EXW|FCA|FOB|CFR|CIF|CPT|CIP|DAP|DPU|DDP)") String incoterm,
@Pattern("[A-Z]{3}") String currency,
@DecimalMin("0") BigDecimal totalAmount, freightAmount,
@Min(0) Integer totalQty,
@DecimalMin("0") BigDecimal totalWeight, totalVolume, volumetricWeight,
LocalDate etdDate, etaDate, readyDate,
@Pattern("(DRAFT|CONFIRMED|PICKING|PACKED|EXPORT_DECLARED|SHIPPED|IN_TRANSIT|ARRIVED|CUSTOMS_CLEARING|CUSTOMS_CLEARED|LAST_MILE|DELIVERED|CLOSED|EXCEPTION|CANCELLED)") String status,
@Size(max=255) String remark,
List<ExportOrderItemPayload> items     // 空列表=清空明细；null=不动明细
```

> **Incoterms 2020 全集**（11 个）：`EXW / FCA / FOB / CFR / CIF / CPT / CIP / DAP / DPU / DDP`——共 10 个代码
> （2020 版取消了 FAS、DEQ、DES、DDU 等旧代码）。**【待验证】**：以 ICC 官方口径复核，本计划 `@Pattern` 按这 10 个写。

`ExportOrderItemPayload`：
```
Long id, Long productId,
@Size(max=64) String sku, @Size(max=128) String productName,
@Size(max=255) String customsName, @Size(max=255) String customsNameEn,
@Pattern("^[0-9]{8,12}$") String hsCode,          // 10 位中国编码【待验证】
@Pattern("^[A-Z]{2}$") String originCountry,
@NotNull @Min(1) Integer quantity,
@DecimalMin("0") BigDecimal unitPrice, declaredValue,
@DecimalMin("0") BigDecimal netWeight, grossWeight, volume,
@Min(0) @Max(1) Integer isDangerous,
@Size(max=16) String unNumber, @Size(max=8) String dgClass,
@Pattern("(PI967|PI966|PI969)") String packingInstruction,
@DecimalMin("0") BigDecimal batteryWattHours,
@Size(max=255) String remark
```

`ExportBatchPayload`：`batchNo`（空则自动生成）/ `mode` / `polCode` / `polName` / `podCode` / `podName` / `routeId` / `carrierId` / `containerType` / `containerQty` / `incoterm` / `currency` / `totalQty` / `totalWeight` / `totalVolume` / `volumetricWeight` / `etdDate` / `etaDate` / `cutoffAt` / `status` / `remark` / `List<String> orderNos`。

**OMS 列表聚合**（沿用 `LinkageService.enrich` 模式，每页 2 次 HTTP，失败各自降级为 `null`）：
1. `GET http://tms:8084/api/tms/intl/shipments?batchNos=<本页所有 batch_no, 去重, 去空, 截断 200>` → 回填 `shipmentNo / shipmentStatus / awbNo / etdAt / etaAt`
2. `GET http://wms:8085/api/wms/intl/pack-tasks?orderNos=<本页所有 order_no>` → 回填 `packStatus / boxNo`
两路**各自 try/catch**，任一路失败只降级自己那几列，不挡列表。

### 4.3 WMS 新增端点

| 方法 | 路径 | 入参 | 返回 | 权限点 | 行级过滤 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/wms/intl/pack-tasks` | `keyword, status, orderNos(逗号分隔), warehouseId, assigneeId, page, size` | `PageResult<PackTask>` | `wms:intl:view` | `tenant_id` |
| GET | `/api/wms/intl/pack-tasks/brief` | `status`（可选） | `List<PackTaskBrief>` | `wms:intl:view` | `tenant_id` |
| GET | `/api/wms/intl/pack-tasks/{id}` | — | `PackTaskDetail`（含 items + 库存批次候选） | `wms:intl:view` | `tenant_id` |
| POST | `` | `PackTaskPayload`（含 `items`） | `PackTaskDetail` | `wms:intl:edit` | `tenant_id` |
| PUT | `/{id}` | `PackTaskPayload` | `PackTaskDetail` | `wms:intl:edit` | `status ∈ {PENDING, PICKING}` |
| DELETE | `/{id}` | — | 204 | `wms:intl:edit` | `status ∈ {PENDING, PICKING}` |
| PATCH | `/{id}/status` | `{"status":"PACKED","boxNo":"CTN-...","grossWeight":120.5,"volume":0.8,"marks":"..."}` | `PackTaskDetail` | `wms:intl:edit` | 状态机校验 |
| GET | `/api/wms/intl/pack-tasks/{id}/items` | — | `List<PackTaskItem>` | `wms:intl:view` | `tenant_id` |
| POST/PUT/DELETE | `/api/wms/intl/pack-tasks/{id}/items[/{itemId}]` | `PackTaskItemPayload` | `PackTaskItem` | `wms:intl:edit` | `tenant_id` |
| GET | `/api/wms/intl/inventory-batches` | `warehouseId, sku, keyword, status, expiringWithinDays, page, size` | `PageResult<InventoryBatch>` | `wms:intl:view` | `tenant_id` |
| GET | `/api/wms/intl/inventory-batches/{id}` | — | `InventoryBatch` | `wms:intl:view` | `tenant_id` |
| POST/PUT | `/api/wms/intl/inventory-batches[/{id}]` | `InventoryBatchPayload` | `InventoryBatch` | `wms:intl:edit` | `tenant_id` |
| GET | `/api/wms/intl/inventory-batches/fefo` | `warehouseId, sku` | `List<InventoryBatch>`（按 expiry_date 升序） | `wms:intl:view` | `tenant_id` |
| GET | `/api/wms/intl/in-transit` | `shipmentNos, orderNos, status, page, size` | `PageResult<InTransit>` | `wms:intl:view` | `tenant_id` |
| GET | `/api/wms/intl/in-transit/{id}` | — | `InTransit` | `wms:intl:view` | `tenant_id` |
| POST | `/api/wms/intl/in-transit` | `InTransitPayload` | `InTransit` | `wms:intl:edit` | `tenant_id` |
| PATCH | `/api/wms/intl/in-transit/{id}/status` | `{"status":"RECEIVED","toWarehouseId":4}` | `InTransit` | `wms:intl:edit` | `tenant_id` |
| GET | `/api/wms/intl/warehouses` | `warehouseType, country, page, size` | `PageResult<Warehouse>` | `wms:intl:view` | `tenant_id` |
| GET | `/api/wms/intl/dashboard` | — | `IntlWarehouseDashboard` | `wms:intl:view` | `tenant_id` |

`PackTaskPayload`：
```
Long id,
@NotBlank @Size(max=32) String orderNo,
@NotNull Long warehouseId,
@Size(max=32) String locationCode,
@Pattern("(PACK|LABEL)") String taskType,
@Pattern("(PENDING|PICKING|PACKED|LABELLED|HANDED_OVER|SHORTAGE|CANCELLED)") String status,
@Size(max=32) String boxNo, @Pattern("(CARTON|PALLET|BAG|DRUM)") String containerType,
@Min(0) Integer pieces,
@DecimalMin("0") BigDecimal grossWeight, volume, volumetricWeight,
@Size(max=255) String marks,
@Min(0) @Max(1) Integer isDangerous,
Long assigneeId, @Size(max=255) String remark,
List<PackTaskItemPayload> items
```
`PackTaskItemPayload`：`sku`(NotBlank,64) / `productName`(128) / `batchNo`(32) / `productionDate` / `expiryDate` / `quantity`(NotNull,NotNull,≥0) / `pickedQuantity`(≥0) / `hsCode`(12)。

`IntlWarehouseDashboard` 结构：
```json
{ "packTask": { "total": 52, "pending": 8, "picking": 6, "packed": 10, "labelled": 7,
                "handedOver": 18, "shortage": 3, "statusDistribution": [...] },
  "batch":     { "total": 186, "nearExpiring": 9, "expired": 2, "frozen": 1 },
  "inTransit": { "total": 410, "byWarehouse": [{"warehouseName":"洛杉矶海外仓","qty":128}] },
  "warehouse": { "oversea": 3, "bonded": 1, "domestic": 2 } }
```

### 4.4 CRM 新增端点

| 方法 | 路径 | 入参 | 返回 | 权限点 | 行级过滤 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | `/api/crm/intl/partners` | `keyword, partnerType, status, page, size` | `PageResult<Partner>` | `crm:intl:view` | `tenant_id` |
| GET | `/api/crm/intl/partners/brief` | `partnerType`（可选） | `List<PartnerBrief>` | `crm:intl:view` | `tenant_id` |
| GET | `/api/crm/intl/partners/{id}` | — | `Partner` | `crm:intl:view` | `tenant_id` |
| POST/PUT/DELETE | `/api/crm/intl/partners[/{id}]` | `PartnerPayload` | `Partner` | `crm:intl:edit` | `tenant_id` |
| GET | `/api/crm/intl/quotations` | `keyword, status, customerId, polCode, podCode, mode, page, size` | `PageResult<Quotation>` | `crm:intl:view` | `tenant_id` |
| GET | `/api/crm/intl/quotations/{id}` | — | `QuotationDetail`（含 items） | `crm:intl:view` | `tenant_id` |
| POST | `` | `QuotationPayload`（含 `items`） | `QuotationDetail` | `crm:intl:edit` | `customerService.get()` 过闸 |
| PUT | `/{id}` | `QuotationPayload` | `QuotationDetail` | `crm:intl:edit` | `status=DRAFT` 才允许改 |
| DELETE | `/{id}` | — | 204 | `crm:intl:edit` | `status=DRAFT` |
| PATCH | `/{id}/status` | `{"status":"WON"}` | `QuotationDetail` | `crm:intl:edit` | `DRAFT→SENT→WON/LOST`，`SENT` 可到 `EXPIRED` |
| GET | `/api/crm/intl/contracts` | `keyword, status, customerId, page, size` | `PageResult<Contract>` | `crm:intl:view` | `tenant_id` |
| GET | `/api/crm/intl/contracts/{id}` | — | `Contract` | `crm:intl:view` | `tenant_id` |
| POST/PUT/DELETE | `/api/crm/intl/contracts[/{id}]` | `ContractPayload` | `Contract` | `crm:intl:edit` | `tenant_id` |
| PATCH | `/{id}/status` | `{"status":"ACTIVE"}` | `Contract` | `crm:intl:edit` | `tenant_id` |
| GET | `/api/crm/intl/tickets` | `keyword, status, category, severity, customerId, orderNos, shipmentNos, page, size` | `PageResult<Ticket>` | `crm:intl:view` | `tenant_id` |
| GET | `/api/crm/intl/tickets/{id}` | — | `Ticket` | `crm:intl:view` | `tenant_id` |
| POST/PUT/DELETE | `/api/crm/intl/tickets[/{id}]` | `TicketPayload` | `Ticket` | `crm:intl:edit` | `tenant_id` |
| PATCH | `/{id}/status` | `{"status":"FOLLOWING","resolution":"..."}` | `Ticket` | `crm:intl:edit` | `tenant_id` |
| GET | `/api/crm/intl/workbench` | — | `IntlWorkbench` | `crm:intl:view` | **按 `owner_id = X-User-Id`** |
| GET | `/api/crm/intl/dashboard` | — | `IntlCrmDashboard` | `crm:intl:view` | `tenant_id` |
| GET | `/api/crm/customers/{id}/credit` | — | `{creditLimit, creditUsed, available, paymentTermDays, currency, overdueAmount}` | `crm:intl:view` | `tenant_id` |

`IntlWorkbench`（对齐现有 `WorkbenchService` 的个人工作台口径）：
```json
{ "stats":   { "myCustomers": 8, "activeQuotations": 3, "wonAmount": 42000.00,
               "openTickets": 2, "overdueTickets": 1, "creditUsed": 185000.00, "creditLimit": 500000.00 },
  "todos":   [ {"ticketNo":"TK202610100001","title":"目的港查验待回复","dueAt":"2026-10-12T02:00:00","overdue":true} ],
  "quotations":[ {"quotationNo":"QT202610100001","customerName":"...","totalAmount":2380.00,"currency":"USD","validUntil":"2026-10-25"} ],
  "recentTickets": [ ... ] }
```

### 4.5 端点改动（不是新增，是现有端点加参数/加返回列）

| 端点 | 改动 | 原因 |
| --- | --- | --- |
| `GET /api/oms/products` | `Product` record 增加 11 个国际申报字段 | 商品档案承载 HS 编码与危险品属性 |
| `POST/PUT /api/oms/products` | `ProductPayload` 同步加字段 + `@Size` 校验 | 同上 |
| `GET /api/oms/dashboard` | **不动** | 现有看板口径是国内零售销售额，混入口径会失真 |
| `GET /api/crm/customers` | `Customer` record 增加 8 个字段；`CustomerPayload` 同步 | 客户分级/账期/授信 |
| `GET /api/wms/warehouses` | `Warehouse` record 增加 5 个字段（warehouse_type/country/timezone/oversea_operator/is_bonded） | 现有列表页要显示仓库类型 |
| `GET /api/tms/orders` | **不动** | 国内陆运单据，不与国际运单混列 |

---

## 5. 数据库迁移清单

> 全部文件遵循：`CREATE DATABASE IF NOT EXISTS xxx ...; USE xxx;` 开头（沿用 28/29/32 号写法），
> 表用 `CREATE TABLE IF NOT EXISTS`，种子用 `INSERT ... ON DUPLICATE KEY UPDATE` 或 `INSERT IGNORE`，
> 加列用 41 号的 `information_schema + PREPARE` 模式。执行前必须带
> `--default-character-set=utf8mb4`（DEPLOY.md §7 的字符集坑）。

| 文件 | 库 | 内容 | 幂等方式 | 阶段 |
| --- | --- | --- | --- | --- |
| `43-intl-tms-schema.sql` | `tms` | 建 `tms_carriers` / `tms_routes` / `tms_sailings` / `tms_shipments` / `tms_shipment_items` / `tms_tracking_nodes` / `tms_customs_declarations` / `tms_rate_cards` / `tms_rate_card_items` / `tms_fx_rates` | 全部 `CREATE TABLE IF NOT EXISTS` | M1 |
| `44-intl-oms-schema.sql` | `oms` | 建 `oms_export_orders` / `oms_export_order_items` / `oms_export_batches`；`oms_products` 加 11 列 | `CREATE IF NOT EXISTS` + `information_schema + PREPARE` 加列 | M1 |
| `45-intl-wms-schema.sql` | `wms` | 建 `wms_pack_tasks` / `wms_pack_task_items` / `wms_inventory_batches` / `wms_in_transit`；`wms_warehouses` 加 5 列 | 同上 | M1 |
| `46-intl-crm-schema.sql` | `crm` | 建 `crm_carrier_partners` / `crm_quotations` / `crm_quotation_items` / `crm_contracts` / `crm_tickets`；`customers` 加 8 列 | 同上 | M1 |
| `47-intl-permissions.sql` | `user_center` | 插入 8 个权限点 + `role_permissions` 授权 ADMIN +（可选）USER 只读 | `INSERT IGNORE` | M1 |
| `48-intl-seed-master.sql` | 四库 | 承运商 / 航线 / 船期 / 仓库 / 汇率 / 运价卡 / 客户 / 渠道商 / 商品国际属性种子 | `ON DUPLICATE KEY UPDATE` | M1 |
| `49-intl-seed-orders.sql` | 四库 | 出口母单/子单/明细 + 运单 + 箱 + 轨迹 + 报关 + 备货任务 + 在途 + 报价 + 工单种子 | `INSERT ... SELECT WHERE NOT EXISTS` 或 `ON DUPLICATE KEY UPDATE` | M1 |
| `50-intl-index-audit.sql` | 四库 | 按新增端点的实际 SQL 模式补索引（`idx_batch`+`idx_status` 组合等），先 ADD 后 DROP 冗余 | 幂等 DDL（可重复执行） | M2 |
| `51-intl-dashboard-idx.sql` | 四库 | 看板聚合专用复合索引（如 `idx_status_etd(status, etd_at)`） | 同上 | M3 |

### 5.1 `47-intl-permissions.sql` 全文（权限点定义，实现时照抄）

```sql
-- =============================================================================
-- 47: 国际跨境物流权限点
--
-- 命名规则沿用 35 号：动词 + 对象，动词收敛为「查看 / 编辑 / 管理」；
-- 业务权限名是纯动作（37 号），分组标题由 module 给出。
-- module 沿用已有的 tms/wms/oms/crm 四个值——**不新增 module**，
-- 这样 permission-shared.tsx 的 MODULE_LABEL / MODULE_COLOR 不用改。
-- =============================================================================
USE user_center;

INSERT IGNORE INTO permissions (code, name, module, description) VALUES
    ('tms:intl:view', '查看', 'tms', '国际运单/承运商/航线/船期/报关/运价/汇率：只读列表与看板'),
    ('tms:intl:edit', '编辑', 'tms', '国际运单建单改单、状态推进、轨迹录入、报关、运价卡与汇率维护'),
    ('wms:intl:view', '查看', 'wms', '备货装箱贴标任务/库存批次效期/在途库存/海外仓：只读'),
    ('wms:intl:edit', '编辑', 'wms', '备货任务创建与状态推进、批次与效期维护、在途出入库'),
    ('oms:intl:view', '查看', 'oms', '出口订单与母单：只读列表与看板'),
    ('oms:intl:edit', '编辑', 'oms', '出口订单建单改单、状态推进、组批与拆批、发起订舱'),
    ('crm:intl:view', '查看', 'crm', '货代渠道商/报价/合同/异常工单：只读'),
    ('crm:intl:edit', '编辑', 'crm', '渠道商建档、报价与合同维护、异常工单创建与流转');

-- 授权策略（沿用 30/33 号口径：ADMIN 全量，业务只读权限给 USER）
-- USER 只给四个 view —— 与现有「USER 有 crm:read/crm:write，但没有 tms:view/wms:view/oms:view」
-- 的现状**故意不同**：国际业务涉及报价金额与客户授信，保守起见 view 也不给 USER，
-- 由管理员在「权限中心」按需勾选（权限申请走 37 号的两级审批）。
INSERT IGNORE INTO role_permissions (role_code, permission_code)
SELECT 'ADMIN', code FROM permissions WHERE code LIKE '%:intl:%';

-- 需要放开时（评审后决定）再执行下面这条，幂等：
-- INSERT IGNORE INTO role_permissions (role_code, permission_code)
-- SELECT 'USER', code FROM permissions WHERE code LIKE '%:intl:view';
```

> **为什么 USER 不给 view**：现有 `USER` 有 `crm:read`/`crm:write`，说明 CRM 对普通用户开放；
> 但 `tms:view`/`wms:view`/`oms:view` 只给 ADMIN，说明业务数据默认不开放。
> 国际业务比国内零售敏感（含客户授信、报关单号、报价），**取严的一边**。
> 这一条列入 §12 待评审项。

### 5.2 `49-intl-seed-orders.sql` 的幂等写法样例

```sql
-- 幂等要点：运单/子单有唯一键，直接 ON DUPLICATE KEY UPDATE；
-- 但轨迹节点没有唯一键（承运商回调会重推），用「按 shipment_no + node_code 计数」判存在。
INSERT INTO tms_shipments
    (tenant_id, shipment_no, batch_no, awb_no, bl_no, booking_no, mode, carrier_id,
     pol_code, pod_code, container_type, container_qty, incoterm, currency,
     total_pieces, total_gross_weight, total_volume, volumetric_weight, chargeable_weight,
     etd_at, eta_at, cutoff_at, status, sanction_flag, remark)
VALUES
    ('alibaba', 'SHP20261010001', 'MEXP20261010001', '', 'COSU6123456', 'COSU6123456',
     'SEA', 1, 'CNSHA', 'USLAX', '40HQ', 2, 'FOB', 'USD',
     860, 18420.000, 62.400, 10400.000, 18420.000,
     '2026-10-14 01:00:00', '2026-11-02 08:00:00', '2026-10-12 09:00:00',
     'IN_TRANSIT', 'CLEAR', '美西海运整柜')
ON DUPLICATE KEY UPDATE
    status = VALUES(status), etd_at = VALUES(etd_at), eta_at = VALUES(eta_at);

-- 轨迹节点：先查后插（回调幂等的同一思路）
INSERT INTO tms_tracking_nodes (tenant_id, shipment_id, shipment_no, node_code, node_name, node_time, location, source, remark)
SELECT 'alibaba', s.id, 'SHP20261010001', 'DEPARTED', '已开航', '2026-10-14 09:20:00', '上海洋山港', 'CARRIER_CALLBACK', 'COSCO 船期确认'
  FROM tms_shipments s
 WHERE s.tenant_id = 'alibaba' AND s.shipment_no = 'SHP20261010001'
   AND NOT EXISTS (
       SELECT 1 FROM tms_tracking_nodes n
        WHERE n.tenant_id = 'alibaba' AND n.shipment_no = 'SHP20261010001'
          AND n.node_code = 'DEPARTED' AND n.node_time = '2026-10-14 09:20:00');
```

### 5.3 迁移执行注意事项（照抄 DEPLOY.md 的口径）

```bash
cd deploy
python3 upload.py initdb/43-intl-tms-schema.sql /tmp/43.sql
python3 ssh.py 'docker exec -i stack-mysql mysql --default-character-set=utf8mb4 \
  -uroot -p"$(grep MYSQL_ROOT_PASSWORD /opt/stack/.env | cut -d= -f2)" < /tmp/43.sql'
```
- **字符集参数不能省**（容器 `LANG=C` 会双重编码）。
- 顺序：**先跑 schema（43~46）→ 再跑权限（47）→ 最后跑种子（48、49）**，
  种子依赖 schema 的唯一键，权限必须在建菜单/页面之前跑。
- **种子里的 `DATETIME` 字面量一律按 UTC 写**（如 `'2026-10-14 01:00:00'` 表示 UTC 10-14 09:00 北京）。
- 新增服务不需要改 `deploy/docker-compose.yml`（镜像 tag 不变；如需改环境变量才改）。

---

## 6. 国际跨境物流测试数据方案

> 目标：数据一进库，页面就有东西看；单号格式要像真的；状态分布要「多数在途、少量异常」。

### 6.1 时间基准与时区约定

- 数据库 `DATETIME` **存 UTC**；示例种子里的 `'2026-10-14 01:00:00'` = 北京时间 10-14 09:00。
- 展示层（web）统一 `new Date(x).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' })`。
- 「今天/本周」类筛选**在 SQL 里用 UTC 计算**：`WHERE etd_at >= UTC_DATE() AND etd_at < UTC_DATE() + INTERVAL 1 DAY`。
- 演示基准日：**2026-10-10**（周六）。ETD 分布覆盖 10-01 ~ 10-20，确保「今日出港/未来 7 天/已逾期」三档都有数据。

### 6.2 承运商与渠道商（`tms_carriers`，12 条）

| code | name_zh | name_en | carrier_type | country | service_level | transit_days | cut_off_hours | free_time_days |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `MAEU` | 马士基航运 | Maersk | OCEAN | DK | STANDARD | 18 | 48 | 14 |
| `COSCO` | 中远海运集运 | COSCO Shipping | OCEAN | CN | STANDARD | 16 | 48 | 10 |
| `ONE` | 海洋网联船务 | ONE (Ocean Network Express) | OCEAN | JP | STANDARD | 15 | 72 | 14 |
| `MSC` | 地中海航运 | MSC | OCEAN | CH | STANDARD | 20 | 48 | 10 |
| `CMA` | 达飞轮船 | CMA CGM | OCEAN | FR | STANDARD | 19 | 48 | 12 |
| `EK` | 阿联酋航空 | Emirates SkyCargo | AIR | AE | EXPRESS | 2 | 24 | 0 |
| `CZ` | 中国南方航空货机 | China Southern Cargo | AIR | CN | STANDARD | 3 | 24 | 0 |
| `UPS` | 联合包裹 | UPS | EXPRESS | US | EXPRESS | 5 | 12 | 0 |
| `FEDEX` | 联邦快递 | FedEx | EXPRESS | US | EXPRESS | 5 | 12 | 0 |
| `DHL` | 敦豪快递 | DHL | EXPRESS | DE | EXPRESS | 6 | 12 | 0 |
| `SEAWAY` | 环球速运（无船承运人） | Seaway Express | NVOCC | HK | STANDARD | 25 | 72 | 7 |
| `LAZYLION` | 捷 lion 国际货代 | LazyLion Forwarding | FORWARDER | CN | STANDARD | 22 | 72 | 7 |

> `LAZYLION` 是**故意造出来的虚构货代**（避免与真实公司混淆），其余为真实承运商代码。
> **【待验证】**：MAEU/COSCO/ONE 等是否为当前有效的 SCAC/IATA 承运人标识，以及 `code` 是否应与 SCAC 分列两列存储。

### 6.3 航线与船期（`tms_routes` 8 条 / `tms_sailings` 16 条）

`CMA` 建议改名为 `CMA CGM`，此处用 `CMA` 作代码。

| route_code | carrier | mode | pol_code | pod_code | transit_days | volumetric_divisor |
| --- | --- | --- | --- | --- | --- | --- |
| `CNSHA-USLAX` | COSCO | SEA | CNSHA | USLAX | 16 | 6000 |
| `CNSHA-USNYC` | MAEU | SEA | CNSHA | USNYC | 18 | 6000 |
| `CNNGB-USDAL` | ONE | SEA | CNNGB | USDAL | 20 | 6000 |
| `CNSZX-DEHAM` | MSC | SEA | CNSZX | DEHAM | 30 | 6000 |
| `CNTAU-USLAX` | CMA | SEA | CNTAU | USLAX | 17 | 6000 |
| `PVG-ORD` | EK | AIR | PVG | ORD | 3 | 6000 |
| `CAN-AMS` | CZ | AIR | CAN | AMS | 4 | 6000 |
| `CNSHA-USLAX-EXP` | UPS | EXPRESS | CNSHA | USLAX | 5 | 5000 |

> **体积重除数**：空运 6000（cm³/kg）、快递 5000 —— 这是行业通行做法，**【待验证】**以承运商运价表为准。
> **港码**：`CNSHA`（上海）、`USLAX`（洛杉矶）、`USNYC`（纽约）、`USDAL`（达拉斯）、`DEHAM`（汉堡）、
> `AMS`（阿姆斯特丹）、`ORD`（芝加哥）、`CNNGB`（宁波）、`CNSZX`（深圳）、`CNTAU`（青岛）、`PVG`（上海浦东）、`CAN`（广州）。
> **【待验证】**：UN/LOCODE 标准码与 IATA 三字码在本项目混用是否可接受（海运用 UN/LOCODE 5 位、空运用 IATA 3 位，
> 这里统一用 3 位 + 8 位宽列，实际落地建议 `VARCHAR(8)` 同时兼容）。

船期样例（16 条，ETD 覆盖 2026-10-01 ~ 2026-10-20）：
```sql
INSERT INTO tms_sailings (tenant_id, route_id, vessel_name, voyage_no, etd_at, eta_at, cutoff_at, free_time_days, space_left, status)
SELECT 'alibaba', r.id, v.vessel_name, v.voyage_no, v.etd_at, v.eta_at, v.cutoff_at, v.free_time_days, v.space_left, 'SCHEDULED'
  FROM (SELECT 'COSCO ARIES' AS vessel_name, 'V.034E' AS voyage_no, '2026-10-14 01:00:00' AS etd_at,
               '2026-10-30 08:00:00' AS eta_at, '2026-10-12 09:00:00' AS cutoff_at, 10 AS free_time_days, 6 AS space_left
        UNION ALL SELECT 'EVER LOADING', 'V.0126E', '2026-10-21 01:00:00', '2026-11-06 08:00:00', '2026-10-19 09:00:00', 10, 12
        -- …共 16 行
       ) v
  JOIN tms_routes r ON r.tenant_id='alibaba' AND r.route_code='CNSHA-USLAX'
ON DUPLICATE KEY UPDATE vessel_name = VALUES(vessel_name), space_left = VALUES(space_left);
```

### 6.4 SKU 与 HS 编码（`oms_products` 12 个 SKU，覆盖危险品与非危险品）

> 现有 `oms_products` 是款号+颜色+尺码（服装）。国际业务需要**按 SKU 粒度带申报属性**。
> 做法：43 号迁移给 `oms_products` 加 11 列；49 号种子**新增 12 条国际 SKU**（不改现有 9 条服装款号）。

| sku | name | category | hs_code | customs_name | customs_name_en | origin | 净重 kg | 体积 CBM | 危险品 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `IPH-15-BLK` | iPhone 15 Pro 256G 黑 | OUTER | `8517130000` | 手机 | Mobile Phone | CN | 0.45 | 0.0025 | 是（UN3481，PI967，74Wh） |
| `IPH-15-BLU` | iPhone 15 Pro 256G 蓝 | OUTER | `8517130000` | 手机 | Mobile Phone | CN | 0.45 | 0.0025 | 是 |
| `AIRP-2-WHT` | AirPods Pro 2 | OUTER | `8518301000` | 耳机 | Earphone | CN | 0.06 | 0.0004 | 是（UN3481，PI967，4.4Wh） |
| `WATCH-S9-BLK` | Apple Watch S9 黑 | OUTER | `8517120000` | 智能手表 | Smart Watch | CN | 0.05 | 0.0003 | 是（UN3481，PI967，1.9Wh） |
| `MBP-14-SLV` | MacBook Pro 14 银色 | OUTER | `8471300000` | 笔记本电脑 | Laptop Computer | CN | 1.6 | 0.0032 | 是（UN3481，PI967，72.4Wh） |
| `IPAD-AIR-64G` | iPad Air 64G | OUTER | `8517120000` | 平板电脑 | Tablet | CN | 0.46 | 0.0015 | 是 |
| `TEE-COT-M-WHT` | 精梳棉圆领 T 恤 M 白 | TOP | `6109100021` | 针织棉制T恤 | Knitted Cotton T-Shirt | CN | 0.18 | 0.0018 | 否 |
| `TEE-COT-M-BLU` | 精梳棉圆领 T 恤 M 蓝 | TOP | `6109100021` | 针织棉制T恤 | Knitted Cotton T-Shirt | CN | 0.18 | 0.0018 | 否 |
| `DENIM-JKT-L` | 水洗牛仔外套 L | OUTER | `6202130000` | 男式棉外套 | Men's Cotton Jacket | CN | 0.72 | 0.0045 | 否 |
| `CERAMIC-MUG` | 陶瓷马克杯 350ml | TOP | `6912001000` | 陶瓷餐具 | Ceramic Tableware | CN | 0.42 | 0.0012 | 否 |
| `CHAIR-ERGO` | 人体工学椅 | OUTER | `9401790000` | 办公椅 | Office Chair | CN | 12.5 | 0.28 | 否 |
| `POWER-100W` | 移动电源 20000mAh | OUTER | `8507600090` | 锂离子蓄电池 | Lithium Ion Battery | CN | 0.42 | 0.0012 | 是（**UN3480**，PI967，74Wh，class 9） |

> **HS 编码与危险品**：以上编码为 10 位中国出口编码格式，**【待验证】**需按实际海关税则复核；
> 锂电池规则参考 IATA DGR 与《危险品运输规定》，**UN3480/UN3481**、**PI966/PI967/PI969**、
> **class 9**、瓦时阈值 100Wh 为通行认知，**【待验证】**以最新 IATA DGR 版本为准。
> 瓦时计算：`Wh = V(Ah) × V(伏)`，示例 `20000mAh × 3.7V ≈ 74Wh`。

### 6.5 仓库与库位（`wms_warehouses` 增 5 列 + 12 条库存批次）

仓库（新增 5 个国际仓，不动现有北京/上海/广州三个）：

| name | location | warehouse_type | country | timezone | is_bonded | oversea_operator |
| --- | --- | --- | --- | --- | --- | --- |
| 深圳前海保税仓 | 广东省深圳市宝安区前海 | BONDED | CN | Asia/Shanghai | 1 | — |
| 宁波北仑出口仓 | 浙江省宁波市北仑区 | DOMESTIC | CN | Asia/Shanghai | 0 | — |
| 义乌集货仓 | 浙江省金华市义乌市 | DOMESTIC | CN | Asia/Shanghai | 0 | — |
| 洛杉矶海外仓 | 90021 Los Angeles, CA | OVERSEAS | US | America/Los_Angeles | 0 | 谷仓海外仓 |
| 纽约海外仓 | 10018 New York, NY | OVERSEAS | US | America/New_York | 0 | 谷仓海外仓 |
| 德国汉堡海外仓 | 20457 Hamburg | OVERSEAS | DE | Europe/Berlin | 0 | 汉堡海外仓 |

> 仓名沿用国内仓「城市 + 仓」的命名习惯；海外仓服务商「谷仓海外仓」为真实公司，仅作演示数据。
> **【待验证】**：保税仓的账册与海关特殊监管代码，本计划只做标记位（`is_bonded`），不做账册。

库存批次 `wms_inventory_batches`（18 条，含临期与过期，用于看板的 `nearExpiring`/`expired`）：
```sql
-- 临期（7 天内到期，喂看板的 nearExpiring=9）
INSERT INTO wms_inventory_batches
    (tenant_id, warehouse_id, sku, product_name, batch_no, production_date, expiry_date, quantity, reserved_qty, unit_cost, location_code, status)
VALUES
  ('alibaba', 1, 'TEE-COT-M-WHT', '精梳棉圆领T恤 M 白', 'B260901-A', '2026-09-01', '2027-09-01', 1200, 300, 18.50, 'A-01-03', 'NORMAL'),
  ('alibaba', 1, 'TEE-COT-M-WHT', '精梳棉圆领T恤 M 白', 'B260908-C', '2026-09-08', '2026-10-15',  400, 150, 18.50, 'A-01-04', 'NEAR_EXPIRY'),
  ('alibaba', 1, 'TEE-COT-M-WHT', '精梳棉圆领T恤 M 白', 'B260510-B', '2026-05-10', '2026-10-05',   80,   0, 17.20, 'A-02-01', 'EXPIRED'),
  -- …其余 15 行（覆盖 12 个 SKU × 1~3 个批次）
ON DUPLICATE KEY UPDATE quantity = VALUES(quantity), status = VALUES(status);
```
> `uk_wh_sku_batch(warehouse_id, sku, batch_no)` 保证幂等。
> `location_code` 用 `仓库区-架-层` 三段式（如 `A-01-03`），演示库位维度。

### 6.6 客户、渠道商、报价、合同（CRM）

**客户**（新增 8 个国际客户，`customers` 增列填充；现有 5 个国内客户不动）：

| name | customer_type | country | level | status | payment_term_days | credit_limit | credit_used | default_currency | tax_no |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 深圳前海优选跨境电商有限公司 | SELLER | CN | KEY | DEAL | 30 | 500000.00 | 185000.00 | USD | `91440300MA5EX8Q71K` |
| 义乌市小商品城跨境卖家联盟 | SELLER | CN | KEY | DEAL | 15 | 200000.00 | 96000.00 | USD | `91330782MA2AB3XY1D` |
| 广州希悦服饰出口有限公司 | SELLER | CN | NORMAL | FOLLOWING | 0 | 50000.00 | 0.00 | USD | `91440101MA9UY2L08P` |
| 杭州星辰户外用品有限公司 | SELLER | CN | NORMAL | FOLLOWING | 30 | 120000.00 | 42000.00 | USD | `91330106MA2C7K93T5` |
| 宁波甬佳家居用品有限公司 | FACTORY | CN | NORMAL | DEAL | 45 | 300000.00 | 240000.00 | USD | `91330206MA7J5X2R9W` |
| 上海速达国际货运代理有限公司 | FORWARDER | CN | NORMAL | DEAL | 30 | 80000.00 | 31000.00 | USD | `91310115MA1H8P3K7C` |
| 深圳鸿鹄供应链管理有限公司 | DISTRIBUTOR | CN | LOW | FOLLOWING | 0 | 20000.00 | 0.00 | USD | `91440300MA5FL6T21N` |
| 3C Global Trading Co., Ltd.（美） | SELLER | US | KEY | DEAL | 0 | 150000.00 | 0.00 | USD | `US-EIN 47-3829104` |

> 税号/信用号格式为演示用**虚构值**，公司名除最后一家外均为虚构，避免真实企业数据风险。

**渠道商**（`crm_carrier_partners`，6 条）：`LAZYLION` 捷 lion 国际货代（FORWARDER，评级 4，账期 30，CNY 结算）、
`SEAWAY` 环球速运（NVOCC，账期 15，USD）、`SPD` 速通关报关行（CLEARANCE_BROKER，账期 0，CNY）、
`FREIGHTPRO` 飞通国际代理（AGENT，账期 30，USD）、`GOKU` 谷仓海外仓（LAST_MILE，账期 30，USD）、
`ROADRUNNER` 路歌卡航（TRUCKING，账期 15，CNY）。

**报价单**（`crm_quotations` + `crm_quotation_items`，10 条）：

| quotation_no | customer | pol → pod | mode | 箱型×量 | 基础 | 附加费明细 | 合计 | 币种 | 时效 | valid_until |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `QT202610100001` | 深圳前海优选 | CNSHA → USLAX | SEA | 40HQ×2 | 3600.00 | BAF 216.00 + THC 260.00 + 报关 180.00 | 4256.00 | USD | 16 | 2026-10-25 |
| `QT202610100002` | 深圳前海优选 | PVG → ORD | AIR | 按 KG | 28.50/kg | SAF 4.28/kg + 目的地操作 1.20/kg | 34.00/kg | USD | 3 | 2026-10-20 |
| `QT202610100003` | 义乌小商品城联盟 | CNSHA → USLAX | EXPRESS | 按 KG | 22.00/kg | 燃油 1.10/kg | 23.10/kg | USD | 5 | 2026-10-18 |
| `QT202610100004` | 宁波甬佳家居 | CNNGB → USDAL | SEA | 20GP×1 | 1450.00 | GRI 217.50 + THC 260.00 | 1927.50 | USD | 20 | 2026-11-05 |
| `QT202610100005` | 杭州星辰户外 | CNSZX → DEHAM | SEA | 40HQ×1 | 2680.00 | BAF 268.00 + ISPS 45.00 + 偏远 180.00 | 3173.00 | USD | 30 | 2026-11-10 |
| `QT202610100006` | 3C Global（美） | CAN → AMS | AIR | 按 KG | 31.00/kg | SAF 4.65/kg + 关税代理 250.00/票 | 35.65/kg | USD | 4 | 2026-10-22 |
| `QT202610100007` | 上海速达货代 | PVG → ORD | AIR | 按 KG | 26.80/kg | SAF 4.02/kg | 30.82/kg | USD | 3 | 2026-10-19 |
| `QT202610100008` | 广州希悦服饰 | CNSHA → USNYC | SEA | 40GP×1 | 2100.00 | BAF 210.00 + THC 260.00 + GRI 315.00 | 2885.00 | USD | 18 | 2026-10-28 |
| `QT202610100009` | 深圳鸿鹄供应链 | CNSHA → USLAX | LCL | 8 CBM | 480.00 | 操作 120.00 + 文件 80.00 | 680.00 | USD | 20 | 2026-10-15 |
| `QT202610100010` | 宁波甬佳家居 | CAN → AMS | AIR | 按 KG | 33.50/kg | SAF 5.03/kg | 38.53/kg | USD | 4 | 2026-10-24 |

状态分布：`SENT` 4 条（进行中）、`WON` 3 条、`LOST` 2 条、`EXPIRED` 1 条、`DRAFT` 1 条。

**合同**（`crm_contracts`，4 条）：
`CT20261010001` 深圳前海优选（2026-10-01 ~ 2027-09-30，USD 1,200,000，账期 30，额度 500,000，`ACTIVE`）、
`CT20261010002` 义乌联盟（2026-07-01 ~ 2027-06-30，USD 600,000，账期 15，`ACTIVE`）、
`CT20261010003` 宁波甬佳（2026-10-01 ~ 2027-03-31，USD 800,000，账期 45，`SIGNED`）、
`CT20261010004` 3C Global（2026-06-01 ~ 2026-12-31，USD 300,000，账期 0，`ACTIVE`）。

### 6.7 出口订单与母单（OMS）

**规模**：**12 个母单 / 43 个子单 / 96 条明细**。

母单 12 条，覆盖：

| batch_no | mode | pol → pod | 箱型×量 | ETD | ETA | status | 子单数 | 场景 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `MEXP20261010001` | SEA | CNSHA → USLAX | 40HQ×2 | 2026-10-14 | 2026-10-30 | IN_TRANSIT | 5 | 主力在途（3C 类，含锂电池） |
| `MEXP20261010002` | SEA | CNSHA → USNYC | 40GP×1 | 2026-10-09 | 2026-10-27 | CLEARING | 4 | 在目的港清关 |
| `MEXP20261010003` | AIR | PVG → ORD | 按 KG | 2026-10-11 | 2026-10-14 | DEPARTED | 6 | 空运快线 |
| `MEXP20261010004` | SEA | CNNGB → USDAL | 20GP×1 | 2026-10-17 | 2026-11-06 | BOOKED | 3 | 已订舱待揽收 |
| `MEXP20261010005` | SEA | CNSZX → DEHAM | 40HQ×1 | 2026-10-21 | 2026-11-20 | BOOKING | 3 | 订舱中 |
| `MEXP20261010006` | EXPRESS | CNSHA → USLAX | 按 KG | 2026-10-08 | 2026-10-13 | CLEARED | 4 | 已清关待派送 |
| `MEXP20261010007` | SEA | CNTAU → USLAX | 40HQ×1 | 2026-10-06 | 2026-10-23 | LAST_MILE | 3 | 尾程派送中 |
| `MEXP20261010008` | AIR | CAN → AMS | 按 KG | 2026-10-13 | 2026-10-17 | IN_TRANSIT | 4 | 在途 |
| `MEXP20261010009` | SEA | CNSHA → USLAX | 40HQ×1 | 2026-10-12 | 2026-10-28 | DEPARTED | 3 | 已开航 |
| `MEXP20261010010` | LCL | CNSHA → USLAX | 12 CBM | 2026-10-19 | 2026-11-08 | PICKING | 4 | 拼箱备货中 |
| `MEXP20261010011` | SEA | CNSHA → USLAX | 40HQ×1 | 2026-10-15 | 2026-10-31 | EXCEPTION | 2 | **异常**：整批查验 |
| `MEXP20261010012` | EXPRESS | CNSHA → USLAX | 按 KG | 2026-10-05 | 2026-10-10 | DELIVERED | 2 | 已妥投 |

> **状态分布刻意设计**：IN_TRANSIT 2 / DEPARTED 2 / CLEARING·CLEARED·LAST_MILE·DELIVERED 各 1
> → 「在途 + 已完成」共 7 条；BOOKED·BOOKING·PICKING 3 条（未发运）；EXCEPTION 1 条（少量异常）。
> 这满足「多数在途、少量异常」的要求，且看板各档都有数。

子单 43 条的**状态按母单状态派生**（保证一致性）：
`IN_TRANSIT` 母单下子单为 `IN_TRANSIT`；`DEPARTED` 母单下子单为 `SHIPPED`；
`CLEARING`/`CLEARED` 母单下子单为 `ARRIVED`/`CUSTOMS_CLEARING`；
`PICKING` 母单下子单为 `PICKING`；`BOOKED` 母单下子单为 `PACKED`；`EXCEPTION` 母单下子单为 `EXCEPTION`；
`DELIVERED` 母单下子单为 `DELIVERED`。
（子单状态**领先不得多于**母单，落后随意——这是有意为之：同柜不同单可能已清关，未清关的单还在清关中。）

单号样例（前 6 条子单，其余按 `EXP202610100{NN}` 递增）：
```sql
INSERT INTO oms_export_orders
    (tenant_id, order_no, batch_no, customer_id, customer_name,
     consignee_name, consignee_company, consignee_country, consignee_address, consignee_phone, consignee_email,
     incoterm, currency, total_amount, freight_amount, total_qty, total_weight, total_volume, volumetric_weight,
     etd_date, eta_date, ready_date, status, is_batch_locked, created_by, remark)
VALUES
  ('alibaba', 'EXP20261010001', 'MEXP20261010001', 6, '深圳前海优选跨境电商有限公司',
   'Jordan Ellis', 'Ellis Trading LLC', 'US', '1201 S Santa Fe Ave, Los Angeles, CA 90021',
   '+1-213-555-0142', 'user40@example.com',
   'FOB', 'USD', 48600.00, 4256.00, 120, 84.600, 0.3100, 56.000,
   '2026-10-14', '2026-10-30', '2026-10-12', 'IN_TRANSIT', 1, 1, '含锂电池，需 MSDS'),
  ('alibaba', 'EXP20261010002', 'MEXP20261010001', 6, '深圳前海优选跨境电商有限公司',
   'Morgan Chen', 'Pacific Devices Inc.', 'US', '88 Bluxome St, New York, NY 10012',
   '+1-646-555-0198', 'user50@example.com',
   'CIF', 'USD', 92400.00, 4256.00, 60, 49.500, 0.2200, 36.500,
   '2026-10-14', '2026-10-30', '2026-10-12', 'IN_TRANSIT', 1, 1, ''),
  -- …共 43 行
ON DUPLICATE KEY UPDATE status = VALUES(status), batch_no = VALUES(batch_no);
```

明细样例（一行，注意 `hs_code` 与 `declared_value` 的申报口径）：
```sql
INSERT INTO oms_export_order_items
    (tenant_id, order_id, order_no, line_no, product_id, sku, product_name,
     customs_name, customs_name_en, hs_code, origin_country, quantity, unit_price, declared_value,
     net_weight, gross_weight, volume, is_dangerous, un_number, dg_class, packing_instruction, battery_watt_hours)
SELECT 'alibaba', o.id, o.order_no, 1, 0, 'IPH-15-BLK', 'iPhone 15 Pro 256G 黑',
       '手机', 'Mobile Phone', '8517130000', 'CN', 100, 406.00, 40600.00,
       0.450, 0.480, 0.0025, 1, 'UN3481', '9', 'PI967', 17.30
  FROM oms_export_orders o
 WHERE o.tenant_id = 'alibaba' AND o.order_no = 'EXP20261010001'
   AND NOT EXISTS (SELECT 1 FROM oms_export_order_items i
                    WHERE i.order_id = o.id AND i.line_no = 1 AND i.sku = 'IPH-15-BLK');
```
> `battery_watt_hours` 17.30 ≈ iPhone 15 Pro 电池 3.83Ah × 4.52V ≈ 17.3Wh（**【待验证】**以产品实际参数为准）。
> PI967 = 锂电池与设备**包装在一起**；PI966 = 在设备内；PI969 = 与设备**装在同一个外包装内**。**【待验证】**以 IATA DGR 表述复核。

### 6.8 运单、箱、轨迹、报关（TMS）

运单 12 条（与 12 个母单 1:1），单号 `SHP202610100{NN}`：

| shipment_no | batch_no | 单证号 | mode | 箱型×量 | 状态 | 节点数 |
| --- | --- | --- | --- | --- | --- | --- |
| `SHP20261010001` | `MEXP20261010001` | BL `COSU6123456` / S/O `COSU6123456` | SEA | 40HQ×2 | IN_TRANSIT | 5 |
| `SHP20261010002` | `MEXP20261010002` | BL `MAEU4120877` | SEA | 40GP×1 | CLEARING | 6 |
| `SHP20261010003` | `MEXP20261010003` | AWB `176-30874125` | AIR | — | DEPARTED | 4 |
| `SHP20261010004` | `MEXP20261010004` | BL `ONEY3390218` | SEA | 20GP×1 | BOOKED | 2 |
| `SHP20261010005` | `MEXP20261010005` | （待订舱） | SEA | 40HQ×1 | BOOKING | 1 |
| `SHP20261010006` | `MEXP20261010006` | AWB `1Z999AA10123456784` | EXPRESS | — | CLEARED | 7 |
| `SHP20261010007` | `MEXP20261010007` | BL `CMDU5510874` | SEA | 40HQ×1 | LAST_MILE | 8 |
| `SHP20261010008` | `MEXP20261010008` | AWB `784-30218745` | AIR | — | IN_TRANSIT | 4 |
| `SHP20261010009` | `MEXP20261010009` | BL `MSCU7741029` | SEA | 40HQ×1 | DEPARTED | 4 |
| `SHP20261010010` | `MEXP20261010010` | （拼箱待订） | LCL | — | （母单 PICKING，运单 DRAFT） | 1 |
| `SHP20261010011` | `MEXP20261010011` | BL `COSU6123502` | SEA | 40HQ×1 | EXCEPTION | 5 |
| `SHP20261010012` | `MEXP20261010012` | AWB `1Z999AA10123456801` | EXPRESS | — | DELIVERED | 8 |

> **AWB 格式**：IATA 规定的 11 位，前 3 位是航空公司数字代码（如 `176` = 阿联酋航空、`784` = 南航），
> 第 4 位固定为 `-`，后 8 位为序列号。**【待验证】**数字代码需与承运商核对。
> **UPS 运单号** `1Z999AA10123456784`：1Z + 6 位 UPS 账号 + 7 位序列 —— 这是被广泛用作**文档示例**的公开测试号。
> **提单号格式**：4 位船公司代码（COSU/MAEU/ONEY/MSCU/CMDU）+ 7 位数字。**【待验证】**船公司代码需核对。
> **S/O 与 BL 同号** 是演示简化（真实场景两者通常不同），**留作后续可调整点**。

箱明细 `tms_shipment_items`（32 条）—— 集装箱号用 **ISO 6346** 格式：
```sql
-- ISO 6346 = 4 位船公司代码 + 6 位序号 + 1 位校验位（校验位算法见 ISO 6346 §4）
INSERT INTO tms_shipment_items
    (tenant_id, shipment_id, shipment_no, order_no, house_no, container_no, container_type,
     seal_no, marks, pieces, gross_weight, volume, volumetric_weight, is_dangerous, un_number, dg_class)
SELECT 'alibaba', s.id, s.shipment_no, 'EXP20261010001', 'MEXP20261010001-01',
       'CSNU6382914', '40HQ', 'SL12345678',
       'N/M: HAIER GLOBAL\nP/C: NO. 1\nR/D: LOS ANGELES\nCNTR NO: CSNU6382914\nMADE IN CHINA',
       60, 42.300, 0.1550, 26.000, 1, 'UN3481', '9'
  FROM tms_shipments s
 WHERE s.tenant_id = 'alibaba' AND s.shipment_no = 'SHP20261010001'
   AND NOT EXISTS (SELECT 1 FROM tms_shipment_items i
                    WHERE i.shipment_id = s.id AND i.house_no = 'MEXP20261010001-01');
```
> 唛头用 `\n` 分行，是真实唛头的标准排版（收货人/编号/目的港/箱号/产地）。
> 箱号 `CSNU6382914` 的校验位 `4` **【待验证】**——ISO 6346 校验算法需要实测核对，
> 实现时若发现校验位不符，**保留格式但换成一个校验正确的号**，并在文档里注明。
> 封条号 `SL` + 8 位数字。

轨迹节点 `tms_tracking_nodes`（约 55 条），**来源混合**以体现 `source` 列的价值：

| shipment_no | node_code | node_time(UTC) | location | source |
| --- | --- | --- | --- | --- |
| `SHP20261010001` | BOOKED | 2026-10-08 03:20 | — | MANUAL |
| `SHP20261010001` | PICKED_UP | 2026-10-11 06:40 | 上海青浦仓 | MANUAL |
| `SHP20261010001` | EXPORT_DECLARED | 2026-10-12 02:15 | 上海洋山港 | MANUAL |
| `SHP20261010001` | DEPARTED | 2026-10-14 01:40 | 上海洋山港 | CARRIER_CALLBACK |
| `SHP20261010001` | IN_TRANSIT | 2026-10-18 08:00 | 北太平洋 | CARRIER_CALLBACK |
| `SHP20261010002` | CLEARING | 2026-10-08 22:30 | 纽约港 | CARRIER_CALLBACK |
| `SHP20261010002` | EXCEPTION | 2026-10-09 15:10 | 纽约港 | CARRIER_CALLBACK |

报关单 `tms_customs_declarations`（8 条，对应已进入报关阶段的运单）：
```sql
-- 18 位报关单号格式：4 位关区 + 4 位年份 + 4 位月日 + 6 位流水 + 1 位校验
-- 实际位数与编码规则【待验证】
('alibaba', 'TESTID00000009X', 1, 'SHP20261010001', '速通关报关行', '13800000027',
 '8517130000,8518301000,8517120000,8471300000', 132400.00, 'USD',
 1240.60, 1063.80, 0.00, 5000.00, 'RELEASED', '2026-10-12 01:00:00', '2026-10-12 08:30:00'),
```
状态分布：`RELEASED` 5 条、`FILED` 1 条、`INSPECTING` 1 条（对应 `SHP20261010011` 异常母单）、`REJECTED` 1 条。
`HELD` 状态**不铺种子**（只留 1 条 `INSPECTING`），避免异常数据喧宾夺主。

### 6.9 备货任务与在途库存（WMS）

**备货任务**（`wms_pack_tasks`，48 条 / 对应 `wms_pack_task_items` 96 条）：
按母单状态反推任务状态 —— `PICKING` 母单 → `PICKING`；`BOOKED`/`DEPARTED`/`IN_TRANSIT` → `HANDED_OVER`；
`CLEARING`~`DELIVERED` → `HANDED_OVER`；`EXCEPTION` 母单下 1 条任务为 `SHORTAGE`（喂看板的 `shortage=3`）。

箱号样例（自生成规则 `{container_type}-{order_no}-{序号}`）：
```sql
('alibaba', 'PK202610100001', 'EXP20261010001', 1, 'A-01-03', 'PACK',
 'HANDED_OVER', 'CTN-EXP20261010001-01', 'CARTON', 120, 84.600, 0.3100, 56.000,
 'N/M: HAIER GLOBAL\nP/C: NO. 1', 1, '2026-10-11 06:40:00', '2026-10-11 09:20:00', 0)
```

**在途库存**（`wms_in_transit`，约 320 行）：
按运单的每个 SKU × 数量展开，`shipped_at` 取该运单的 `PICKED_UP` 时间，
`status` 取运单状态映射（`IN_TRANSIT`/`DEPARTED`/`BOOKED` → `IN_TRANSIT`；`CLEARING` 及之后 → `ARRIVED`；
`DELIVERED` → `RECEIVED`；`EXCEPTION` → 1 条 `LOST`）。

> **数据量说明**：320 行在 `wms_in_transit` 上是**小表**，`idx_shipment` / `idx_order_no` 足以支撑列表分页。
> 看板聚合（`SUM(quantity)`）走全表扫描，320 行 < 1ms，可接受。

### 6.10 多币种与汇率种子（`tms_fx_rates`，12 条）

```sql
INSERT INTO tms_fx_rates (tenant_id, base_currency, quote_currency, rate, rate_date, source)
VALUES
  ('alibaba', 'USD', 'CNY', 7.1280, '2026-10-10', 'MANUAL'),
  ('alibaba', 'USD', 'EUR', 0.9180, '2026-10-10', 'MANUAL'),
  ('alibaba', 'USD', 'GBP', 0.7820, '2026-10-10', 'MANUAL'),
  ('alibaba', 'USD', 'JPY', 148.35, '2026-10-10', 'MANUAL'),
  ('alibaba', 'CNY', 'USD', 0.14028, '2026-10-10', 'MANUAL'),
  ('alibaba', 'EUR', 'CNY', 7.7658, '2026-10-10', 'MANUAL'),
  -- 加上 2026-10-01 / 2026-09-30 两个历史日期各 3 条，共 12 条
ON DUPLICATE KEY UPDATE rate = VALUES(rate);
```
> `uk_pair_date(tenant_id, base, quote, rate_date)` 保证同一天同一货币对只有一条（**幂等的关键**）。
> 汇率是**演示值**，**【待验证】**取自哪个数据源、由谁维护、多久更新一次，需评审确认。

### 6.11 数据自检清单（写完种子后逐条 `SELECT` 验证）

```sql
-- 1. 每个母单至少 1 个子单
SELECT b.batch_no, COUNT(o.id) FROM oms_export_batches b
  LEFT JOIN oms_export_orders o ON o.batch_no = b.batch_no AND o.tenant_id = b.tenant_id
 WHERE b.tenant_id = 'alibaba' GROUP BY b.batch_no HAVING COUNT(o.id) = 0;
-- 期望：0 行

-- 2. 每个非 DRAFT 母单必须有对应运单
SELECT b.batch_no FROM oms_export_batches b
  LEFT JOIN tms_shipments s ON s.batch_no = b.batch_no AND s.tenant_id = b.tenant_id
 WHERE b.tenant_id = 'alibaba' AND b.status <> 'DRAFT' AND s.id IS NULL;
-- 期望：0 行

-- 3. 在途库存必须都有对应运单
SELECT i.* FROM wms_in_transit i
  WHERE i.tenant_id='alibaba' AND i.status <> 'RECEIVED'
    AND NOT EXISTS (SELECT 1 FROM tms_shipments s WHERE s.shipment_no = i.shipment_no);
-- 期望：0 行

-- 4. 危险品 SKU 的明细必须都填了 un_number 与 packing_instruction
SELECT o.order_no, i.sku FROM oms_export_order_items i
  JOIN oms_export_orders o ON o.id = i.order_id
 WHERE i.tenant_id='alibaba' AND i.is_dangerous = 1
   AND (i.un_number = '' OR i.packing_instruction = '');
-- 期望：0 行

-- 5. 幂等复跑检查（连跑 3 遍 49 号脚本，行数不应增长）
SELECT COUNT(*) FROM oms_export_orders;   -- 三次都应是 43
```

---

## 7. web-console 页面方案

### 7.0 路由与菜单（复用现有机制）

**`src/App.tsx`** 新增 4 条路由，全部包在既有 `RequirePermission` 里：

```tsx
<Route path="intl/shipments" element={
    <RequirePermission permission="tms:intl:view"><IntlShipmentsPage /></RequirePermission>} />
<Route path="intl/export-orders" element={
    <RequirePermission permission="oms:intl:view"><IntlOrdersPage /></RequirePermission>} />
<Route path="intl/packing" element={
    <RequirePermission permission="wms:intl:view"><IntlPackingPage /></RequirePermission>} />
<Route path="intl/crm" element={
    <RequirePermission permission="crm:intl:view"><IntlCrmPage /></RequirePermission>} />
```
> 只加**一条**读取权限点即可进页面；页面内**写按钮**按 `hasPermission('<模块>:intl:edit')` 显隐
> （沿用现有 `TmsPage` 里「写操作另需 `crm:write`」的做法）。

**`src/components/AppLayout.tsx`** 的 `PAGE_META` 新增 4 条，菜单新增一个父节点：

```tsx
...(hasPermission('tms:intl:view') || hasPermission('oms:intl:view')
    || hasPermission('wms:intl:view') || hasPermission('crm:intl:view')
  ? [{
      key: 'intl-group',
      icon: <GlobalOutlined />,
      label: '国际物流',
      children: [
        ...(hasPermission('oms:intl:view') ? [{ key: '/intl/export-orders', label: '出口订单' }] : []),
        ...(hasPermission('wms:intl:view') ? [{ key: '/intl/packing',     label: '备货装箱' }] : []),
        ...(hasPermission('tms:intl:view') ? [{ key: '/intl/shipments',   label: '国际运单' }] : []),
        ...(hasPermission('crm:intl:view')  ? [{ key: '/intl/crm',         label: '客户与渠道' }] : [])
      ]
    }]
  : []),
```
`defaultOpenKeys={['crm-group', 'console-group', 'intl-group']}`。
新增文件：`src/pages/intl/shared.tsx`（枚举映射 + `formatMoney(amount, currency)` + `formatUtc()`）、
`src/pages/intl/IntlOrdersPage.tsx`、`IntlShipmentsPage.tsx`、`IntlPackingPage.tsx`、`IntlCrmPage.tsx`。
`src/api/types.ts` 末尾追加 `IntlExportOrder` / `IntlShipment` / `IntlPackTask` / `IntlQuotation` / `IntlTicket` 等 interface。

### 7.1 `/intl/export-orders` 出口订单（OMS）

**布局**：顶部 4 张 Statistic 卡（本月出口额 / 在途子单 / 本月出口件数 / 异常子单数）→ 筛选条 → Tabs（子单 / 母单）。

**Tab 1 子单** 列定义：

| 列 | dataIndex | 渲染 | 宽度 |
| --- | --- | --- | --- |
| 子单号 | `orderNo` | `Typography.Text copyable`（一键复制） | 150 |
| 客户 | `customerName` | 文字，hover 显示 `customerId` | 200 |
| 收货国 | `consigneeCountry` | `Tag`（US/DE 用国旗色，CN 不显示） | 80 |
| 收货人 | `consigneeName` | 文字 + 副标题显示公司 | 160 |
| 贸易条款 | `incoterm` | `Tag color="geekblue"` | 90 |
| 金额 | `totalAmount` | `formatMoney(v, currency)`，右下角小字显示币种 | 130 |
| 件数 | `totalQty` | 数字，右对齐 | 80 |
| 毛重/体积 | `totalWeight` / `totalVolume` | `1,240.5 kg` / `0.62 CBM` 两行小字 | 130 |
| 母单号 | `batchNo` | 可点击跳母单详情；空则 `—` | 150 |
| 运单状态 | `shipmentStatus` | `Tag`（TMS 状态映射，见 §7.5），null 显示 `—` | 110 |
| 订舱单号 | `awbNo` / `bookingNo` | 优先 awbNo，空则 bookingNo | 150 |
| ETD / ETA | `etdAt`/`etaAt` | `formatUtc` 两行，ETD 已过期标红 | 140 |
| 备货 | `packStatus` | `Tag`（WMS 任务状态），null 显示 `—` | 100 |
| 状态 | `status` | `Tag`（OMS 状态机） | 110 |
| 操作 | — | 「详情」「推进状态」 | 160 |

**筛选条**：`keyword`（子单号/母单号/客户名/收货人模糊）、`status`（Select，allowClear，14 态全列）、
`consigneeCountry`（Select US/DE/GB/JP/AU/CA/FR/NL…）、`incoterm`（Select 10 个代码）、
`currency`（Select USD/CNY/EUR/GBP/JPY）、`etdFrom`~`etdTo`（`RangePicker`，**注意 UTC 口径**：`dayjs.utc(value).format('YYYY-MM-DD')`）、
`hasException`（Switch「只看异常」）、`refreshBtn`、「新建出口订单」。

**行点击**打开 Drawer（宽 720），内含 Tabs：
- **概览**：Descriptions（客户/收货/条款/币种/时效/状态），底部是状态机 Steps（15 态横向，按当前状态高亮）。
- **明细**：Table（line_no / sku / 品名 / 报关品名中英 / HS 编码 / 原产国 / 数量 / 单价 / 申报货值 / 净重 / 体积 / 危险品标记）。
  危险品行在 `sku` 后加一个红色 `Tag`「危险品」，hover 显示 `UN3481 / class 9 / PI967 / 17.3Wh`。
- **母单与运单**：Descriptions（母单号/箱型/订舱号/AWB/BL/ETD/ETA/截关/清关状态）+ 轨迹 Timeline。
- **单证**：Descriptions + 「上传」按钮（当前只落 `fileUrl` 字符串，不做真实文件上传，**M3 再接 OSS/本地存储**）。

**空态**：
- 完全没有数据 → `<Empty description="还没有出口订单，点右上角新建出口订单" />` + 主按钮。
- 筛选无结果 → `<Empty description="没有符合条件的出口订单" />` + 「清空筛选」文字按钮。

**新建/编辑 Drawer**：表单分三段（客户与收货 / 条款与金额 / 时效），
明细用 `Form.List` 动态增删行，每行含 sku 下拉（`GET /api/oms/products/brief`）与 HS 编码输入，
选 SKU 时自动带出 `customs_name / customs_name_en / hs_code / origin_country / net_weight / gross_weight`（**前端带出后仍可改**，因为报关口径可能与档案不同）。

**antd 坑规避**：按钮文案用「新建出口订单」「保存草稿」「推进状态」，
避免两个汉字触发自动插空格（「成员」→「成 员」）；自动化断言按 `.ant-tabs-tabpane-active` 收窄。

**Tab 2 母单**：列 = 母单号 / 运输方式（Tag SEA/AIR…）/ 航线（`CNSHA → USLAX`，中间 `→`）/ 箱型×箱量 / 件数 / 毛重 / 体积重 / ETD / ETA / 截关 / 子单数 / 状态 / 操作（「详情」「组单」「发起订舱」「推进状态」）。
详情 Drawer 内含子单列表（Table，可跳子单详情）+ 运单信息 + 订舱表单。

### 7.2 `/intl/shipments` 国际运单（TMS）

**布局**：顶部 4 张卡（在途 / 本周出港 / 已逾期 / 异常）→ 筛选条 → Table → 行点击 Drawer。

**列**：
运单号 `shipmentNo`（copyable）/ 母单号 `batchNo` / 运输方式 `mode`（Tag）/ 承运商（按 `carrierId` 关联 `tms_carriers` 缓存渲染，**查不到显示 `—`**）/
航线 `polCode → podCode` / 箱型×量 / 单证号（`blNo` 或 `awbNo`，长文本 `ellipsis` + Tooltip 全量）/
订舱号 `bookingNo` / 件数 / 毛重 / 计费重 / ETD `etdAt`（**逾期标红**）/ ETA `etaAt` /
状态 `status`（Tag）/ 最新节点（`lastNodeName` + 时间）/ 操作（「详情」「推进状态」「录轨迹」「报关」）。

**筛选**：`keyword`（运单号/AWB/BL/S-O/箱号/唛头模糊）、`status`、`mode`、`carrierId`（Select from `/brief`）、
`polCode`、`podCode`、`etdFrom`~`etdTo`、`hasException`。

**详情 Drawer（Tabs）**：
- **概览**：Descriptions（承运商/航线/箱型/单证号/Incoterms/重量体积/危险品/制裁筛查/IOSS）+ **状态 Steps**（16 态横向）。
- **箱与分单**：Table（house_no / order_no / container_no / container_type / seal_no / marks / pieces / 毛重 / 体积 / 危险品）。
- **轨迹**：antd `Timeline`，每条显示 `节点名 · 时间(UTC 转本地) · 地点`，
  右侧 `Tag` 显示来源（`人工` / `承运商回调` / `系统定时`，三色区分），底部「录入轨迹节点」按钮（MANUAL）。
- **报关**：报关单 Descriptions（报关行/关单号/HS 汇总/申报货值/关税/VAT/消费税/保证金/状态）+ 「新建报关单」按钮。
- **运价**：展示该航线命中运价卡的试算结果（`GET /api/tms/intl/rate-cards/{id}/quote`），不落库，只做参考。

**空态**：无数据 → `<Empty description="还没有国际运单。到「出口订单」的母单详情里点「发起订舱」会自动生成运单" />`；
筛选无结果 → `<Empty description="没有符合条件的国际运单" />` + 清空筛选。

### 7.3 `/intl/packing` 备货装箱（WMS）

**布局**：顶部 3 张卡（待处理 / 缺料 / 本月已交接）→ Tabs（备货任务 / 库存批次 / 在途库存 / 仓库）。

**Tab 1 备货任务** 列：任务号 / 出口子单号 `orderNo` / 仓库 `warehouseName` / 库位 / 任务类型 /
箱号 / 包装 `containerType`（Tag CARTON/PALLET…）/ 件数 / 毛重 / 体积 / 唛头（`ellipsis`）/
状态 / 作业人 / 交接时间 / 操作。
「装箱」按钮打开 Drawer：显示明细 Table（sku / 应备 / 实备 / 批次 / 生产日期 / 效期），
并提供「按 FEFO 推荐批次」按钮 → 调 `GET /api/wms/intl/inventory-batches/fefo?warehouseId&sku`，
自动填 `batchNo`（**只推荐不强制**，操作人可改）。

**Tab 2 库存批次** 列：仓库 / SKU / 品名 / 批次号 / 生产日期 / **效期**（`expiringWithinDays` 内标橙，过期标红）/
在库 `quantity` / 占用 `reservedQty` / **可用**（= 在库−占用，列头写明口径）/ 单位成本 / 库位 / 状态。
筛选：仓库 / SKU 搜索 / 状态 / 「只看临期 N 天」（`Select` 7/30/90 天）。

**Tab 3 在途库存** 列：出口子单 / 运单号 / SKU / 品名 / 批次 / 在途数量 / 发货仓 → 目的仓 / 出库时间 / 状态。
提供「登记到达」与「入库」按钮（`PATCH /in-transit/{id}/status` → `ARRIVED` / `RECEIVED`）。

**Tab 4 仓库**：复用现有 `wms_warehouses` 表格样式，**新增 4 列**（仓库类型 / 所在国 / 时区 / 是否保税 / 海外仓服务商）。

**空态**：每个 Tab 各自一句中文 Empty 文案 + 「新建备货任务」/「新增库存批次」/「登记在途」按钮。

### 7.4 `/intl/crm` 客户与渠道（CRM）

**布局**：顶部 6 张卡（我的客户 / 进行中报价 / 待处理工单 / 逾期工单 / 授信已用 / 授信总额）→ Tabs（客户 / 渠道商 / 报价 / 合同 / 工单）。

- **客户**：复用现有 `CustomersPage` 的 Table + Drawer 结构，**新增列**（客户类型 Tag / 国家 / 账期 / 授信已用/总额 / 默认币种），
  Drawer 新增一个「账期与授信」Tab（进度条展示 `creditUsed/creditLimit`，红色警告条展示逾期金额）。
- **渠道商**：列 = 编号 / 名称 / 类型 Tag / 国家 / 联系人 / 结算币种 / 账期 / 我方在其处的授信 / 评级（5 星，antd `Rate` 只读）/ 状态 / 操作。
- **报价**：列 = 报价单号 / 客户 / 航线 `POL → POD` / 方式 / 箱型×量 / 计费量 / 基础 / 附加费 / **合计**（`formatMoney`）/ 币种 / 时效 / 有效期至 / 状态 / 操作。
  「详情」Drawer 内含费用明细 Table（费用类型 Tag 用色区分：基础运费蓝、BAF/SAF 橙、THC 紫、偏远红、旺季绿、代理费青）。
  操作：「发送报价」（`DRAFT→SENT`）、「标记成交」（`SENT→WON`）、「标记流失」（`SENT→LOST`）。
- **合同**：列 = 合同号 / 客户 / 签约日 / 有效期 / 金额 / 币种 / 账期 / 信用额度 / 状态 / 操作。
- **工单**：列 = 工单号 / 客户 / 出口子单 / 运单号 / 类别 Tag / 严重度 Tag（LOW 灰 / MEDIUM 蓝 / HIGH 红）/ 标题 / 状态 / 跟进人 / SLA 到期（**逾期红**）/ 操作。
  操作：「跟进」（`OPEN→FOLLOWING`）、「解决」（填 `resolution`，`→RESOLVED`）、「关闭」。

### 7.5 枚举映射表（`src/pages/intl/shared.tsx`，全站唯一一份）

```tsx
export const EXPORT_MODE = {
  SEA:     { label: '海运整柜', color: 'blue' },
  LCL:     { label: '海运拼箱', color: 'cyan' },
  AIR:     { label: '空运',     color: 'geekblue' },
  EXPRESS: { label: '国际快递', color: 'purple' },
  RAIL:    { label: '中欧班列', color: 'orange' },
  TRUCK:   { label: '卡航',     color: 'gold' }
}

export const EXPORT_ORDER_STATUS = {
  DRAFT:             { label: '草稿',     color: 'default' },
  CONFIRMED:         { label: '已确认',   color: 'default' },
  PICKING:           { label: '备货中',   color: 'processing' },
  PACKED:            { label: '已装箱',   color: 'cyan' },
  EXPORT_DECLARED:   { label: '已报关',   color: 'geekblue' },
  SHIPPED:           { label: '已发运',   color: 'blue' },
  IN_TRANSIT:        { label: '在途',     color: 'processing' },
  ARRIVED:           { label: '已到港',   color: 'lime' },
  CUSTOMS_CLEARING:  { label: '清关中',   color: 'gold' },
  CUSTOMS_CLEARED:   { label: '已清关',   color: 'green' },
  LAST_MILE:         { label: '尾程派送', color: 'cyan' },
  DELIVERED:         { label: '已妥投',   color: 'success' },
  CLOSED:            { label: '已完结',   color: 'default' },
  EXCEPTION:         { label: '异常',     color: 'error' },
  CANCELLED:         { label: '已取消',   color: 'default' }
}

export const SHIPMENT_STATUS = {
  DRAFT:           { label: '草稿',   color: 'default' },
  BOOKING:         { label: '订舱中', color: 'processing' },
  BOOKED:          { label: '已订舱', color: 'cyan' },
  PICKED_UP:       { label: '已揽收', color: 'blue' },
  EXPORT_DECLARED: { label: '已报关', color: 'geekblue' },
  DEPARTED:        { label: '已开航', color: 'processing' },
  IN_TRANSIT:      { label: '在途',   color: 'processing' },
  ARRIVED:         { label: '已到港', color: 'lime' },
  CLEARING:        { label: '清关中', color: 'gold' },
  CLEARED:         { label: '已清关', color: 'green' },
  LAST_MILE:       { label: '尾程派送', color: 'cyan' },
  DELIVERED:       { label: '已妥投', color: 'success' },
  POD_CONFIRMED:   { label: '签收已回传', color: 'success' },
  EXCEPTION:       { label: '异常',   color: 'error' },
  RETURNED:        { label: '已退运', color: 'volcano' },
  CANCELLED:       { label: '已取消', color: 'default' }
}

export const PACK_TASK_STATUS = {
  PENDING:     { label: '待处理', color: 'default' },
  PICKING:     { label: '拣货中', color: 'processing' },
  PACKED:      { label: '已装箱', color: 'cyan' },
  LABELLED:    { label: '已贴标', color: 'geekblue' },
  HANDED_OVER: { label: '已交接', color: 'success' },
  SHORTAGE:    { label: '缺料',   color: 'error' },
  CANCELLED:   { label: '已取消', color: 'default' }
}

export const CARRIER_TYPE = {
  OCEAN:     { label: '海运船司',   color: 'blue' },
  AIR:       { label: '空运航司',   color: 'geekblue' },
  EXPRESS:   { label: '快递',       color: 'purple' },
  NVOCC:     { label: '无船承运人', color: 'cyan' },
  FORWARDER: { label: '货代',       color: 'orange' },
  AGENT:     { label: '货代代理',   color: 'gold' },
  CLEARANCE: { label: '清关服务商', color: 'green' },
  LAST_MILE: { label: '尾程派送',   color: 'magenta' }
}

export const DECLARATION_STATUS = {
  DRAFT:       { label: '草稿',   color: 'default' },
  FILED:       { label: '已申报', color: 'processing' },
  RELEASED:    { label: '已放行', color: 'success' },
  INSPECTING:  { label: '查验中', color: 'warning' },
  HELD:        { label: '扣货',   color: 'error' },
  REJECTED:    { label: '已退单', color: 'error' }
}

export const TICKET_CATEGORY = {
  DELAY:        { label: '延误',   color: 'orange' },
  DAMAGE:       { label: '破损',   color: 'volcano' },
  CUSTOMS_HOLD: { label: '清关查验', color: 'gold' },
  AMEND:        { label: '改单',   color: 'geekblue' },
  ABANDON:      { label: '弃货',   color: 'red' },
  OTHER:        { label: '其他',   color: 'default' }
}

/** 金额统一千分位 + 两位小数 + 币种后缀；空值返回 '—' */
export function formatMoney(value: number | null | undefined, currency?: string | null): string {
  if (value == null || Number.isNaN(Number(value))) return '—'
  const amount = Number(value).toLocaleString('zh-CN', {
    minimumFractionDigits: 2, maximumFractionDigits: 2
  })
  return currency ? `${currency} ${amount}` : amount
}

/** UTC 存储 → 北京时间展示（仓库统一口径，见 §11 R3） */
export function formatUtc(value: string | null | undefined): string {
  if (!value) return '—'
  const d = new Date(value.endsWith('Z') ? value : value + 'Z')
  if (Number.isNaN(d.getTime())) return value
  return d.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
}

/** 只到日的 UTC 展示（ETD/ETA/效期用） */
export function formatUtcDate(value: string | null | undefined): string {
  if (!value) return '—'
  return formatUtc(value).slice(0, 10)
}
```
> **为什么所有时间都要挂 `Z` 后缀再解析**：后端 `LocalDateTime` 序列化成 `2026-10-14T01:40:00`（无时区信息），
> 前端 `new Date()` 会按浏览器本地时区解析，导致开发机（Asia/Shanghai）与容器（TZ=Asia/Shanghai）一致、
> 但任何非 UTC+8 的环境都会偏移 8 小时。统一补 `Z` 是最小改动的确定口径。

### 7.6 与现有页面的复用关系

| 复用什么 | 怎么复用 |
| --- | --- |
| `PageResult<T>` / `errorMessage()` / `api` 实例 | 直接用，零改动 |
| `AppLayout` 的菜单与 `PAGE_META` | 加条目，不改结构 |
| `RequirePermission` 组件 | 直接用，不改 |
| `src/pages/tms/shared.tsx` 的 `ORDER_STATUS` | **不复用**（那是国内陆运 4 态），国际单独一套 |
| `src/pages/oms/shared.tsx` 的 `formatAmount` | 改用 `intl/shared.tsx` 的 `formatMoney`（多币种） |
| `src/pages/crm/shared.tsx` 的 `CUSTOMER_STATUS` | 客户表状态语义不变，**继续复用** |
| `src/pages/wms/shared.tsx` 的 `WAREHOUSE_STATUS` | 继续复用（`ACTIVE/INACTIVE/FULL` 未变） |
| `TmsPage` 的看板卡布局 / `OmsPage` 的 Tabs 结构 | 照抄样式，不抽公共组件（避免过度抽象） |
| `CustomersPage` 的 Drawer + 跟进时间线 | CRM 客户 Tab 直接沿用其结构 |

---

## 8. 跨服务链路设计

### 8.1 一次完整国际订单的数据流（M1 覆盖到「已开航 DEPARTED」）

```
① 人工在 /intl/export-orders 建出口子单
   POST /api/oms/intl/export-orders        → oms_export_orders(DRAFT) + items

② 人工点「确认」PATCH /api/oms/intl/export-orders/{id}/status {"status":"CONFIRMED"}

③ 人工（或 OMS 侧按钮）派备货任务：POST /api/wms/intl/pack-tasks
   → wms_pack_tasks(PENDING) + pack_task_items
   （OMS 不直连 WMS 建任务，M1 用「人工在 WMS 页面建任务时填 order_no」的弱联动；
    M2 再加 OMS→WMS 直连，见 §8.4）

④ 人工组批：POST /api/oms/intl/batches  {"orderNos":[...]} 或 POST /api/oms/intl/batches/{id}/orders
   → oms_export_batches(DRAFT)，子单 batch_no 回填 + is_batch_locked=1

⑤ 人工点「发起订舱」：POST /api/oms/intl/batches/{id}/ship
   ┌──────────────────────────────────────────────────────────────┐
   │ OMS 在事务内（沿用 LinkageService 口径）：                       │
   │  1) 先查：GET http://tms:8084/api/tms/intl/shipments           │
   │           ?batchNos=<batch_no>  → 命中就不重复建              │
   │  2) 未命中 → POST http://tms:8084/api/tms/intl/shipments      │
   │           {batchNo, mode, carrierId, polCode, podCode,         │
   │            containerType, containerQty, incoterm, etdAt, etaAt, │
   │            items:[{orderNo, ...}]}                             │
   │     → tms_shipments(DRAFT) + tms_shipment_items                │
   │  3) 任一步失败 → 抛 LinkageException → OMS 事务回滚 → 502      │
   │  4) 成功 → 母单 status=BOOKING                                 │
   └──────────────────────────────────────────────────────────────┘

⑥ TMS 侧订舱完成：PATCH /api/tms/intl/shipments/{id}/status {"status":"BOOKED","…"}
   并 POST /{id}/tracking 记 BOOKED 节点（source=MANUAL），填 booking_no / awb_no 或 bl_no

⑦ WMS 装箱与贴标：
   PATCH /api/wms/intl/pack-tasks/{id}/status {"status":"PACKED","boxNo":...,"marks":...,"grossWeight":...}
   PATCH /api/wms/intl/pack-tasks/{id}/status {"status":"LABELLED"}
   PATCH /api/wms/intl/pack-tasks/{id}/status {"status":"HANDED_OVER"}
   → WMS 内部联动：LABLED/HANDED_OVER 时回写箱号与毛重到 pack_tasks，
     并 POST /api/wms/intl/in-transit 登记在途（shipment_no 由前端传入，弱关联）

⑧ OMS 侧感知装箱完成：PATCH /api/oms/intl/export-orders/{id}/status {"status":"PICKING"}
   → PACKED（人工或由 WMS→OMS 直连自动，M2 引入）

⑨ TMS 揽收：PATCH /api/tms/intl/shipments/{id}/status {"status":"PICKED_UP"}
   + tracking 节点；**联动**：母单 PICKED_UP

⑩ 报关：
   POST /api/tms/intl/customs  → tms_customs_declarations(DRAFT)
   PATCH /api/tms/intl/customs/{id}/status {"status":"FILED"}
   PATCH /api/tms/intl/customs/{id}/status {"status":"RELEASED"}
   → **联动**：TMS 运单 BOOKED|PICKED_UP → EXPORT_DECLARED
     并回调 OMS：母单内所有 `PACKED` 的子单 → EXPORT_DECLARED（M2 引入回调，M1 由人工在 OMS 点）

⑪ 开航：PATCH /api/tms/intl/shipments/{id}/status {"status":"DEPARTED","actualEtdAt":...}
   + tracking 节点 DEPARTED（source=CARRIER_CALLBACK 或 MANUAL）
   → **联动**：母单 DEPARTED
```

**M1 切到第 ⑪ 步为止**：出口子单 → 备货装箱贴标 → 组批 → 订舱 → 揽收 → 报关 → **已开航**。
后续的到港、清关、尾程、妥投、POD 回传（步骤 ⑫~⑘）放 M2。

### 8.2 跨服务写调用清单（M1 需要的只有一条）

| 调用方 | 被调方 | 时机 | 幂等策略 | 失败处理 |
| --- | --- | --- | --- | --- |
| OMS | TMS | 母单「发起订舱」 | **先查后建**（按 `batch_no` 查 `/shipments?batchNos=`），命中则复用 | 抛 `LinkageException` → OMS 回滚 → 502 `linkage_failed`；用户重试即续上 |
| TMS | — | 运单状态推进 | 单库事务，无跨库 | — |
| WMS | — | 任务状态推进 + 在途登记 | 单库事务 | — |
| TMS | OMS | 报关放行后推进子单 | **M1 不做**（人工点）；M2 加回调 | M2：失败只记日志，不回滚 TMS（**非阻塞**） |
| WMS | OMS | 装箱贴标完成后推进子单 | **M1 不做**（人工点）；M2 加回调 | 同上 |

> **重要取舍**：M1 **只保留 OMS→TMS 这一条同步写链路**，且完全沿用现有 `LinkageClient` 的形态
> （直连容器网络、写 `X-Tenant-Id`、超时 2s 连接 / 5s 读、先查后建、失败抛 `LinkageException`）。
> TMS→OMS、WMS→OMS 的反向回调放 M2，且**只做非阻塞**（失败记 `log.warn` + 列表聚合降级为 `null`），
> 理由见 §11 R1。

### 8.3 承运商回调的幂等设计（M3）

> **⚠ 本节的报文形状已在 M2-4 被重做（2026-10-04），下面这段示例作废。**
>
> 原文设计的是 `{carrierCode, awbNo, blNo, nodes[]}` 这种自造 JSON。实现时按评审
> §9.2 M2-4「报文形状对齐 IFTSTA」的要求，改成了**字段名直接对应 UN/EDIFACT 段名与
> 元素号**的形状，因为评审的判断是对的：**形状一旦定错，M4 要重写解析器**；而回调端点
> 一上线，承运商的对接文档就开始指向这个 JSON，改字段名等于通知所有对接方改代码。
>
> **本期报文形状的唯一真相在
> `tms/src/main/java/com/example/tms/intl/callback/IftstaMessage.java` 的类注释里**
> （「形状对齐说明」A~F 六节，标到元素号，并逐条写明本期不接哪些元素、为什么）。
> 要看形状看那里，不要看本节的示例。
>
> 四处与原文不同的实质决定：
>
> 1. **`IFTSTA` 是报文类型名，不是段名**——承载状态的是 `STS` 段。M4 写 BIT 解析器时
>    去找一个叫 `IFTSTA` 的段会找不到。
> 2. **事件码取 `STS.C555/4405`（UNCL 4405），不是原文暗示的 `C045`**；日期时间取
>    `DTM.C507`（5283+2379+2380），不是 `C252`；**提单号不是 `8281`**——`8281` 是运输工具
>    归属权码，提单号在 `TDT.C401/8053` 或 `CNI.C503/5794`。这类错误在报文形状里是
>    「看起来完全合理但永远对不上」的那种，只有真接了承运商的解析器才会发现，而那时 M4 已经写完了。
> 3. **幂等键从 `(运单, node_code, node_time)` 变成 `(运单, node_code, node_time, external_code)`**。
>    承运商一个报文里常有几十条事件，拿整条报文号当事件级幂等键会漏判，
>    而漏判的代价是「同一次到港被记成好几天」。
> 4. **映射不上我们状态码的承运商事件码，一律只落轨迹、主状态不动**（`TRACKING_ONLY`）。
>    原文第2 条只说了「状态倒挂不改状态」；实际上**任何不认识的事件码**都不能改状态——
>    UNCL 4405 是几百项的大码表，没有任何一家承运商会只用我们认识的那一撮，
>    硬套等于让外部数据驱动我们的状态机。
>
> 原文的另外两条约定仍然成立，且已实现：
>
> - `X-Internal-Token` 是**必须的**（若经网关则该路径无 JWT 可校验），并且
>   **无 token 返回 401、错 token 返回 403**。这两个码在 HTTP 语义里本来就是两件事：
>   401 =「去拿凭据重发」，403 =「停手并叫人」。都返 401 的话，承运商在 token 配错时
>   会无限重推，而运维从返回码上看不出这是配置错误还是网络抖动。
> - 回调端点**不做** `requireAdminForWrite` 角色闸：它不是人在操作，走角色闸会被 403
>   挡死。它有自己的 token 鉴权，且没有 token 谁都进不来。
> - **三个推进入口（人工 / 承运商回调 / 定时任务）必须汇到同一个私有方法**
>   `ShipmentService.applyStatusChange`。门禁的强度等于所有写入路径里最弱的那一条：
>   定时任务每天自动推几百票，若它自己 UPDATE status，VGM 门禁（SOLAS「未取得核实总重
>   的柜不得装载」）在这条路径上形同虚设——而那恰恰是现场被拒载最常见的原因。
>
> 下面这段原文示例保留仅作对照。

```
POST /api/tms/intl/callback/tracking
Header: X-Internal-Token: <shared secret，与 deploy/docker-compose.yml 的 .env 同源>
Body:
{
  "carrierCode": "COSCO",
  "awbNo": "COSU6123456",
  "blNo": null,
  "nodes": [
    { "nodeCode": "DEPARTED", "nodeName": "Vessel Departed",
      "nodeTime": "2026-10-14T01:40:00Z", "location": "SHAYT" },
    { "nodeCode": "IN_TRANSIT", "nodeName": "On the water",
      "nodeTime": "2026-10-18T08:00:00Z", "location": "NORTH PACIFIC" }
  ]
}
```

**幂等实现（照抄 39 号「先查后建」哲学）**：
```java
// 伪代码，实现时按本仓库风格写
for (var node : body.nodes()) {
    int exists = jdbc.queryForObject("""
            SELECT COUNT(*) FROM tms_tracking_nodes
             WHERE tenant_id = ? AND shipment_no = ? AND node_code = ? AND node_time = ?
            """, Integer.class, TenantContext.get(), shipmentNo, node.nodeCode(), node.nodeTime());
    if (exists != null && exists > 0) { duplicated++; continue; }
    jdbc.update("INSERT INTO tms_tracking_nodes (...) VALUES (...)", ..., "CARRIER_CALLBACK");
    // 顺带推进运单状态：合法迁移才推进，非法（回退）只记轨迹不改状态
    shipmentService.tryAdvance(shipmentId, node.nodeCode(), "CARRIER_CALLBACK");
    accepted++;
}
return new CallbackResult(accepted, duplicated);
```
**关键约定**：
1. `tenant_id` 由 `TenantContext` 给——**回调请求也必须带 `X-Tenant-Id`**，
   否则会落默认租户 `alibaba`（现有 `TenantContext` 的兜底行为）。承运商侧按运单号前缀推断租户，或单租户部署时固定传。
2. **非法回退不改状态**：承运商推来一条 `ARRIVED` 而本地已是 `IN_TRANSIT`，
   允许推进到 `ARRIVED`；但推来 `PICKED_UP`（回退）时**只记轨迹、不改状态**，
   并在响应里返回 `rejected: 1`，让承运商侧知道有状态倒挂。
3. **回调入口必须鉴权**。建议**不经网关**（承运商直连 `http://tms:8084`，服务端口当前只绑 `127.0.0.1`，
   需在 compose 里放开内网映射），用 `X-Internal-Token` 校验。
   若要走网关，必须在 `gateway-whitelist.yaml` 放行该路径——**这会让该端点无 JWT 可校验**，
   因此 `X-Internal-Token` 是**必须的**，不是可选的。
