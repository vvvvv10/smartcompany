# 国际跨境物流 M1 评审报告（纠正 agent）

> 评审对象：`docs/logistics-modules-plan.md`（2843 行）+ 工作区未提交实现（45 项改动）
> 评审方式：读全部 intl 包源码与 43~49 号迁移、只读调用线上 API（`http://workbench.example.com`）、
> 经 `deploy/ssh.py` 只读查库（`tms` / `oms` / `wms` / `crm`）、联网查证国际行业惯例。
> 本次**未改动任何业务代码**，只新增本文档 + 修订计划 §13 的「待验证」条目标注结论。
> `workbench-ios/` 的 5 项与 untracked 的 `workbench-antd/` 不在评审范围内。

---

## 1. 总体结论

**一句话结论：域模型与状态机的「设计」是这份交付物里最扎实的部分，但「落到数据」这一层塌了。**
三套状态机的迁移表（终态不出现在 value 里、EXCEPTION 可回到挂起前状态）、跨服务只用业务单号字符串关联、
`tms_tracking_nodes.source` 列区分 MANUAL/CARRIER_CALLBACK/SCHEDULED、多租户「每表第一列 tenant_id +
每条 SQL 进公共 where + 直连必须走 LinkageClient」——这些都是可以拿给同行看的正确做法，
不是「拍脑袋」。执行 agent 自述修的 4 个缺陷里，**PackTaskService.updateItem 500 与 TMS 承运商存在性校验
两处是真修复且修得对**（前者是漏了 `@Transactional`，后者在 `ShipmentService.create` 里用一句人话
替代了悬空外键，注释也写清了为什么不建外键），`ssh.py` 的输出截断修得对（`recv_ready()` 为真时
`recv()` 仍可能瞬时返回 `b""`，早年的写法会静默截断 stdout）。

**但线上数据不能作为验收基线**，原因是三条硬伤叠在一起：

1. **体积重算错 36 倍**。`ExportOrderService.java:434` 写的是 `SUM(volume * quantity * 6000)`，
   而 `volume` 列存的是 CBM（立方米）。正确公式是 `volume_cbm × 10⁶ ÷ divisor`。
   线上全部 43 张子单无一例外偏大 36 倍（`EXP20261010001` 存 816.000 kg，应为 22.667 kg）。
   M1 不计费所以这个错没炸，但 M3 一上运价试算就会直接算错钱，且**没人能从界面上发现**——
   界面上 816 kg 和 22 kg 都「看起来像个体积重」。
2. **状态机可以被 PUT 绕过**。`ExportBatchService.update()` 第 167 行与
   `ExportOrderService.update()` 第 240 行都把 `status` 写进了 UPDATE，但**整段没有调用
   `assertBatch` / `assertOrder`**。于是 `PATCH /{id}/status` 那一整套精心设计的迁移表，
   用 `PUT /{id}` 传一个 `status` 字段就绕过去了。这不是「理论上的风险」，
   `m1_e2e.py` 里 122 条断言**没有一条**覆盖这个路径。
3. **种子数据本身不自洽**，且不自洽的方式恰好是 E2E 看不见的方式：
   `house_no` 在同一张运单内重复（12 张运单全部有重复，违反计划 §3.1 的「租户内唯一」）；
   `MEXP20261010001` 的箱明细件数合计 379 而运单 `total_pieces` 是 289；
   `MEXP20261010010.status = 'PICKING'` 这个值**不在母单状态机的任何一个 key 里**，
   导致这张母单在系统里永远无法被推进。

**所以「API E2E 122 项全绿」的准确读法是**：E2E 覆盖的是「接口按我写的规则工作」，
没有覆盖「数据本身对不对」和「规则能不能被绕过」。前者需要人工核对 SQL，后者需要补 PUT 用例——
两者都没做。这不是执行 agent 偷懒，是**验收标准本身缺了两个维度**（计划 §10 M1 的验收方式 5 条里，
只有第 ① 条「5 条自检 SQL」沾一点数据核对，而那 5 条只查行数不查数值）。

**TMS 只建 7 张表、CRM 只建 partners+tickets 这两个减法是对的**（详见 §5），
但**推迟运价表的真正代价不是 M3 要多写 3 张表，而是 M1 的运价卡根本不存在导致
「已开航」这个终点无法被验证**——真实货代在开航前必须拿到 S/O 和 BL，M1 只能手工 PUT 上去，
所以 §10 里那条验收断言「awbNo = COSU6123456」实际测的是「PUT 能写字符串」，不是「订舱流程通了」。

---

## 2. 打分表

| 维度 | 分数 | 一句话理由 |
| --- | --- | --- |
| **域模型完整性** | **4** / 5 | 母单/子单、Master/House 分单、轨迹只追加不更新、跨服务只用单号字符串关联，这四条都设计对了；扣分在于计划 §1.2 承诺的「出口单证（发票/装箱单/报关委托）」在 §3.2 里一张表都没有，`tms_shipments` 也缺 `container_tare_weight`（VGM Method 2 必需）与 Incoterms 的「指定港口/地点」（现在只有 3 位码）。 |
| **状态机合理性** | **2** / 5 | 迁移表本身写得好（终态不出现在 value、EXCEPTION 可回挂起前态、每服务一个 `IntlStateMachine` 且注释解释了为何不抽框架）；但 `ExportBatchService.update:167` 与 `ExportOrderService.update:240` 两条 PUT 路径完全绕过校验，且种子数据里已经躺着一个不在迁移表里的 `PICKING`，三处叠加使「服务端是唯一裁判」这个设计前提在实现层不成立。 |
| **数据一致性** | **1** / 5 | 体积重 36 倍错、`house_no` 全表重复、箱明细件数与运单件数差 90 件、危险品从明细到运单没有汇总（`is_dangerous` 全为 0 而明细全是 UN3481）、`MEXP20261010011` 因海关查验被标成 `LOST`——五处都是「页面上看着正常、SQL 一查就露馅」。 |
| **多租户** | **5** / 5 | 这是本次做得最好的部分：每表第一列 `tenant_id`、每条 SQL 把 `tenant_id` 放进公共 where 且置于筛选条件之前、所有 `get(id)` 都带 `tenant_id`、`LinkageClient` 强制写 `X-Tenant-Id`、E2E 还专门分了「经网关伪造头」与「直连端口」两层来证明隔离真的生效（第 431~452 行），并有对照组证明不是「服务没数据」。 |
| **权限** | **1** / 5 | 后端零校验，已实测确认：`curl -H 'X-Tenant-Id: alibaba' -H 'X-User-Id: 1' -H 'X-User-Roles: USER' http://127.0.0.1:8084/api/tms/intl/shipments` 返回 **200**。任何登录用户可建国际运单、改客户授信额度。计划把它标为 R7「M1 不修」并写清了演进路径，属于「诚实的缺口」，但在国际跨境场景下这个诚实不解决任何问题（低成本修法见 P1-6）。 |
| **可维护性** | **4** / 5 | 注释密度高且解释「为什么」而非复述代码（例：`LinkageService.ensureShipment` 注明「第二层闸不是为了冗余，是为了兜住两个操作员同时点」）；刻意不引入状态机框架、不引入 MQ、不引入缓存，每个取舍都写了理由；扣分在于 49 号种子用 900+ 行手写 `UNION ALL` 位置参数列表（如第 322~327 行 6 个字段靠人肉对齐），这种数据一旦有一列错位就是静默的数据错乱，而 E2E 的基线比对只看 count/max(id)、看不出这种错。 |
| **国际惯例贴合度** | **2** / 5 | Incoterms 只列 10 个漏了 FAS、UN3480 配 PI967（UN3480 应对应 PI965）、无 MBL/HBL 概念区分、`awb_no` 一列同时装空运单号和快递单号、无舱单/AMS/VGM 申报环节建模；做对的是 `tms_tracking_nodes` 的事件流粒度（node_code + node_time + source + operator）与 `charge_type` 费用项字典（BASE/BAF/SAF/THC/REMOTE/GRI/CUSTOMS_BROKER/DOC_FILE/ENS）确实对得上行业口径。 |

**加权印象**：3.0 / 5。**不是「做得差」，是「上半截做对了、下半截（数据与校验）没做」**。
如果 M2 只做一件事，应该是**补上 PUT 路径的状态机校验 + 加一组「数据自洽性断言」**，
而不是急着接货代 API。
---

## 3. 主控七条疑点的逐条核实

先给结论：**七条里 2 条成立（但严重度与主控判断不同）、3 条部分成立、2 条证伪。**
逐条给证据。

### 疑点 1：`shipmentStatus=DELIVERED` / `eta=None` / `awbNo=1Z999AA10123456801` 出现在海运整柜主线

**判定：部分成立，但主控的三点判断都需要修正。**

| 主控的说法 | 核实结果 | 证据 |
| --- | --- | --- |
| 「出现 `shipmentStatus=DELIVERED`（已签收）」 | **成立，但不是数据错误** | `SHP20261010012` 是 `mode=EXPRESS`（UPS 快递）的运单，`MEXP20261010012` 母单也是 EXPRESS。一票快递走到 DELIVERED 是正常的。计划 §10 M1 验收只要求 `MEXP20261010001` 走到 DEPARTED，**并没有禁止其他母单处于更后的状态**——种子 12 张母单是刻意铺开 12 种状态的展示样本。 |
| 「`eta=None`」 | **证伪** | 全表 43 条子单 `SUM(eta_date IS NULL) = 0`；`SHP20261010012.eta_at = 2026-10-10 09:00:00`。API 实测 `etaDate` 空 0 条、`etaAt` 空 0 条（`GET /api/oms/intl/export-orders?page=1&size=50`）。**这条不成立。** |
| 「UPS 快递单号却出现在海运整柜主线上」 | **证伪** | `SELECT COUNT(*) FROM tms_shipments WHERE mode='SEA' AND awb_no<>''` = **0**。`1Z999AA10123456801` 只出现在 `mode=EXPRESS` 的 `SHP20261010012` 上。 |
| 「字段口径混乱」（隐含） | **成立，且是真实问题 → P1-3** | `awb_no` 这一列同时装了两种语义不同的东西：IATA 空运单号（`176-30874125` / `784-30218745`，前缀是航司数字码）与 UPS 快递追踪号（`1Z999AA1...`，是 UPS 自己的 18 位格式，既不是 AWB 也不是 BL）。快递没有 AWB，把追踪号塞进 `awb_no` 会让「按 awb_no 查运单」在快递场景下语义错误。 |

**为什么主控会误判**：这三个字段是在 `/intl/export-orders` 列表的一行里看到的，
而那一行是 `EXP20261010043`（EXPRESS 母单下的子单），主控把它当成了海运主线的行。
**这本身暴露了一个真实的界面问题**：列表的「运输方式」列如果不在第一屏，
用户无法一眼分清这行是海运还是快递 —— 见 P2-1。

### 疑点 2：TMS 运单 `containerNo=None`、`blNo` 为空，承运商里混了快递商

**判定：证伪（数据是自洽的），但暴露一个真实的设计缺口 → P1-4。**

- `container_no` 为空的 16 条箱明细，全部属于 `SHP20261010003/006/008/012`，即 AIR 与 EXPRESS 运单。
  **空运与快递本来就没有集装箱号**，这是正确的。
- `bl_no` 为空的 2 张运单是 `SHP20261010005`（BOOKING）与 `SHP20261010010`（BOOKING），
  **还没订舱所以没有 BL**，这也是正确的。
- 承运商主数据混快递是**刻意设计**：计划 §3.3.1 的 `carrier_type` 枚举本来就包含
  `OCEAN/AIR/EXPRESS/NVOCC/FORWARDER/AGENT/CLEARANCE/LAST_MILE` 八种。
  线上 12 个承运商覆盖 OCEAN×5 / AIR×2 / EXPRESS×3 / NVOCC×1 / FORWARDER×1，
  且 **`mode` 与 `carrier_type` 100% 匹配**（SEA↔OCEAN、AIR↔AIR、EXPRESS↔EXPRESS、LCL↔NVOCC），
  逐条查过 12 张运单无一例外。这比主控设想的「混着」要干净得多。

**真实缺口**：计划 §3.3.1 的 `carrier_type` 里 `NVOCC`（无船承运人）与 `OCEAN`（船司）是两个平级的枚举值，
但**没有 `bl_type`（MASTER/HOUSE）字段**。按行业惯例，NVOCC 与货代签发的是 **House B/L**，
船司签发的是 **Master B/L**，一个柜下多张 House B/L 对应一张 Master B/L。
当前 `tms_shipments` 只有一个 `bl_no`，无法表达「一票货签了 3 张 HBL 但只有 1 张 MBL」，
也无法区分这张 BL 是船司出的还是 NVOCC 出的 —— 目的港清关时这是决定性字段。见 P1-4。

### 疑点 3：TMS 建 7 张表而非 10 张、CRM 只建 partners+tickets

**判定：成立，是合理的减法。** 详见 §5（对计划偏差的判断）。

### 疑点 4：「未建外键，跨库弱引用靠应用层保证」

**判定：决定本身成立，但被一句话带过，风险被低估。** 详见 P1-5 与 §5。

### 疑点 5：后端不做权限点校验（R7），M1 不修

**判定：成立，且已实测确认缺口真实存在。** 详见 P1-6（含低成本修法）。

### 疑点 6：新表 DATETIME 存 UTC、存量表本地时间

**判定：成立，是真实的长期债，但 M1 不炸。** 详见 P2-3。

### 疑点 7：E2E「122 项全绿」的可信度

**判定：部分成立 —— 负例设计是真的，但三类最容易假的用例有两类完全没覆盖。** 详见 §6。

---

## 4. 问题清单

### P0（上线阻塞 / 数据错乱）

#### P0-1 · 体积重算错 36 倍，43 张子单与 12 张运单全部受影响

**现象**：界面上「体积重」列显示的数值是正确值的 36 倍。

**证据**：
- 计算代码：`oms/src/main/java/com/example/oms/intl/exportorder/ExportOrderService.java:434`
  ```sql
  SUM(volume * quantity * 6000)   AS vol_weight
  ```
  `volume` 列的定义见 `deploy/initdb/44-intl-oms-schema.sql` 的 `oms_export_order_items.volume`
  `DECIMAL(10,4) COMMENT '单件体积 CBM'` —— **单位是立方米（CBM），不是立方厘米**。
  正确公式应为 `volume_cbm × 1,000,000 ÷ divisor`，代码却写成了 `volume_cbm × 6000`。
  两者比值 = `6000 / (10⁶/6000)` = `6000 × 6000 / 10⁶` = **36**。
- 线上核对（`oms.oms_export_orders`）：
  ```
  EXP20261010001  total_volume=0.136  volumetric_weight=816.000  正确值=22.667  偏差=36.0×
  EXP20261010002  total_volume=0.081  volumetric_weight=486.000  正确值=13.500  偏差=36.0×
  EXP20261010006  total_volume=0.630  volumetric_weight=3780.000 正确值=105.000  偏差=36.0×
  EXP20261010008  total_volume=5.600  volumetric_weight=33600.000 正确值=933.333 偏差=36.0×
  ```
  **43 条子单偏差系数全部是 36.0**，无一例外。运单层同样（`tms_shipments` 由母单汇总而来）。
- 同源问题在 `ExportBatchService.java:335` 的母单汇总里也传了下去（`SUM(volumetric_weight)`）。

**为什么是问题**：
1. **M1 不计费，所以这个错现在不会炸**——这正是它危险的地方。M3 一上运价试算，
   `chargeable_weight = max(毛重, 体积重)` 会直接算错报价，而且是 36 倍的错误，
   任何抽检都会立刻暴露，届时已经报出去了。
2. **它是静默的**。816 kg 和 22 kg 在界面上都是「看起来合理的体积重」，
   没有一个只读界面的用户能发现。E2E 也没有任何一条断言检查体积重的数值（§6）。
3. **口径上更根本的问题是：海运整柜本来就不用 6000 除数。** 行业里海运 LCL 按
   「1 CBM = 1 吨（=1000 kg）」的 revenue ton 计费，不存在 6000 除数；
   除数 6000 是**空运**（IATA TACT 标准）。代码注释写的是
   「海运/空运通用除数」（`ExportOrderService.java:419`），这个说法本身就不对。
   `tms_routes` 表已经有 `volumetric_divisor` 列且种子按 6000/5000 填好了，
   但**回算时压根没读它**，硬编码了 6000。

**具体改法**（三处，缺一不可）：
1. 改 `ExportOrderService.java:434` 的 SQL，按 `tms_routes.volumetric_divisor` 取值。
   但 OMS 库读不到 TMS 库（跨库无 FK、且 §3.7 规则 2 明确禁止写路径跨库校验），
   所以正确做法是**在 Payload 上新增一个 `volumetricDivisor` 字段由调用方传**，
   或者退一步：**只修单位**（`volume * quantity * 1000000.0 / 6000`）并在 M2 再接航线除数。
   建议 M1 先做后者止血：
   ```sql
   SUM(volume * quantity * 1000000.0 / 6000)   AS vol_weight
   ```
   并同步修正第 419~421 行的注释（删掉「海运/空运通用除数」的错误说法）。
2. 修正全部历史数据（新建 50 号迁移，不要改 49）：
   ```sql
   UPDATE oms_export_orders SET volumetric_weight = ROUND(total_volume * 1000000.0 / 6000, 3)
    WHERE total_volume > 0;
   UPDATE oms_export_batches SET volumetric_weight = (
       SELECT SUM(volumetric_weight) FROM oms_export_orders o
        WHERE o.batch_no = oms_export_batches.batch_no);
   UPDATE tms_shipments SET volumetric_weight = ... ;
   ```
3. **加一条 E2E 断言卡死这个口径**（当前完全没有）：
   ```python
   check("体积重口径 = CBM × 1e6 ÷ 6000",
         abs(order["volumetricWeight"] - order["totalVolume"]*1e6/6000) < 0.01)
   ```
   没有这条断言，同类错误还会再犯。

#### P0-2：`PUT /{id}` 完全绕过状态机，迁移表形同虚设

**现象**：`PATCH /{id}/status` 有一整套精心设计的迁移校验，但 `PUT /{id}` 可以把状态改成任意值。

**证据**：
- `oms/src/main/java/com/example/oms/intl/exportbatch/ExportBatchService.java:167`
  ```java
  etd_date = ?, eta_date = ?, cutoff_at = ?, status = ?, remark = ?
  ```
  第 184 行：`IntlFields.orDefault(payload.status(), existing.status())`
  —— **整个 `update()` 方法（162~196 行）没有一次 `assertBatch` 调用**。
  而 `changeStatus()`（241~243 行）是有校验的：
  ```java
  IntlStateMachine.assertBatch(IntlStateMachine.EXPORT_BATCH, existing.status(), payload.status());
  ```
- 同样的问题在 `oms/src/main/java/com/example/oms/intl/exportorder/ExportOrderService.java:240`
  （`ready_date = ?, status = ?, remark = ?`，第 257 行 `orDefault(payload.status(), ...)`），
  `update()` 全段（235~260 行）同样没有 `assertOrder`。
- 对照组：TMS 侧做对了。`ShipmentService.update()`（281~331 行）的 UPDATE 语句里
  **根本没有 `status` 列**（第 287~296 行只更新单证号/箱型/时效等），注释也写了「只改可改字段，不碰状态」。
  所以这是 OMS 的疏漏，不是全局设计。

**为什么是问题**：
1. 「服务端是唯一裁判」是这套状态机的**全部价值主张**（`IntlStateMachine` 的类注释第 9~13 行
   明确说「现在的国内陆运运单是 4 态无校验的，前端能点什么就点什么」，
   现在建了 16 态的表就是为了解决这个问题）。结果新表上留了同一个洞，
   **评审时演示「非法状态迁移被拒」是走 PATCH 路径的，所以看不出来**。
2. 现实后果不是「有人手滑点错」，而是**任何一次 PUT 都可能把已开航的母单改回 DRAFT**，
   而 TMS 运单仍在 DEPARTED —— 正是计划 §11 R1 想避免的「两个页面上打架，没人知道哪边是真的」。
3. E2E 完全没覆盖：`m1_e2e.py` 里所有状态机负例都是打 `PATCH /{id}/status`
   （第 200、215、315、359、501、504、728、734 行），**没有一条打 PUT 并带 status 字段**。

**具体改法**：
1. **首选（改法 A，最小改动）**：把 `status` 从两个 `update()` 的 SQL 与参数里删掉，
   与 `ShipmentService.update()` 保持一致 —— 属性变更与状态推进彻底分两个端点。
   这同时消除了「同一个字段两个入口」的心智负担。
   - `ExportBatchService.java`：删第 167 行的 `status = ?` 与第 184 行的 `orDefault(payload.status(), ...)`。
   - `ExportOrderService.java`：删第 240 行的 `status = ?` 与第 257 行的对应参数。
2. **若必须保留 PUT 改状态**（改法 B），则在 `update()` 开头补一行：
   ```java
   if (payload.status() != null && !payload.status().equals(existing.status())) {
       IntlStateMachine.assertBatch(IntlStateMachine.EXPORT_BATCH, existing.status(), payload.status());
   }
   ```
   但这样 PATCH 就成了冗余端点，不推荐。
3. **加 E2E 断言**（两条，缺一不可）：
   ```python
   st, r = call(f"/api/oms/intl/batches/{batch_id}", "PUT",
                {"polCode":"CNSHA","podCode":"USLAX","status":"DELIVERED"}, token)
   check("负例 PUT 带 status 绕过状态机被拒", st == 400, ...)
   ```
   改完（改法 A）后，这条的期望应该是「PUT 忽略 status」而非「400」——
   断言要跟着实现选择走，不能两头都放。

#### P0-3：`MEXP20261010010.status = 'PICKING'` 不在母单状态机里，这张母单永远无法推进

**现象**：母单处于一个状态机的迁移表里不存在的值，任何状态推进请求都会被拒。

**证据**：
- `SELECT batch_no, status FROM oms_export_batches WHERE status NOT IN
  ('DRAFT','CONFIRMED','BOOKING','BOOKED','LOADING','DEPARTED','IN_TRANSIT','ARRIVED','CLEARING','CLEARED','LAST_MILE','DELIVERED','CLOSED','EXCEPTION','CANCELLED')`
  → 命中 `MEXP20261010010 LCL PICKING`。
- 状态机全集见 `oms/src/main/java/com/example/oms/intl/IntlStateMachine.java:50-67`
  `EXPORT_BATCH`，**没有 `PICKING` 这个 key**（`PICKING` 是子单状态机的，见同文件第 29 行）。
- 后果：`assertTransition` 第 76 行 `Set<String> allowed = table.get(from); if (allowed == null) throw new IllegalArgumentException("未知状态: " + from);`
  → `PATCH /api/oms/intl/batches/10/status` 永远返回 400「未知状态: PICKING」。
- 根因在种子：`deploy/initdb/49-intl-seed-orders.sql:50`
  ```sql
  UNION ALL SELECT 'MEXP20261010010', 'LCL', ..., 'PICKING', '拼箱备货中，运单还没建'
  ```
  把**子单的状态**写进了**母单**的状态列。同一条记录的 `remark` 写的是「拼箱备货中」，
  说明写种子的人知道这票货在备货阶段，但**备货是 WMS 的概念，母单状态机里没有它**。

**为什么是问题**：
1. 这条数据在界面上能正常显示（母单列表第 10 行），但**任何人都推进不了它**，
   包括按状态机下拉框推进（`EXPORT_BATCH.get("PICKING")` 返回 null，前端列不出合法目标）。
2. DB 层没有 CHECK 约束兜底：`information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA='oms'`
   → **0 行**。列是 `varchar(24) NOT NULL DEFAULT 'DRAFT'`，任何字符串都能写进去。
   也就是说，**这个洞不止种子能踩，任何一次 PUT（见 P0-2）或未来新增的导入功能都能踩**。
3. 更要紧的是它揭示了一个**模型缺口**：真实 LCL 拼箱业务里，「已组批但货还在仓库备货」
   是一个必须能表达的常态（拼箱要等凑够货才订舱），而现在的母单状态机
   从 `BOOKED` 直接跳到 `LOADING`，没有「等货」这个阶段。

**具体改法**：
1. **补状态机的空缺**（推荐，这才是对的那个修法）：
   在 `oms/.../IntlStateMachine.java` 的 `EXPORT_BATCH` 里加一个 `WAITING_GOODS`（等货备货）状态：
   ```java
   Map.entry("CONFIRMED", Set.of("WAITING_GOODS", "BOOKING", "CANCELLED")),
   Map.entry("WAITING_GOODS", Set.of("BOOKING", "EXCEPTION", "CANCELLED")),
   ```
   种子第 50 行改为 `'WAITING_GOODS'`。前端 `shared.tsx` 的枚举映射同步加一条中文名。
   理由：LCL 拼箱确实需要这个状态（计划 §6.3 种了 LCL 航线与船期，说明设计时考虑过 LCL），
   与其把子单状态硬塞进母单，不如把真实的业务状态补进母单。
2. **同时补 DB 兜底**（防止同类问题再次发生，这是根本措施）：
   新建 50 号迁移给三张表加 CHECK 约束：
   ```sql
   ALTER TABLE oms_export_batches
     ADD CONSTRAINT ck_batch_status CHECK (status IN
       ('DRAFT','CONFIRMED','WAITING_GOODS','BOOKING','BOOKED','LOADING','DEPARTED',
        'IN_TRANSIT','ARRIVED','CLEARING','CLEARED','LAST_MILE','DELIVERED','CLOSED',
        'EXCEPTION','CANCELLED'));
   ```
   MySQL 8.0.16+ 支持 CHECK 约束（线上版本需确认，`SELECT VERSION()` 可查）。
   加约束前必须先把 `MEXP20261010010` 的脏值改掉，否则 ALTER 会失败。
3. **加 E2E 断言**：
   ```python
   rows = sql("SELECT batch_no FROM oms.oms_export_batches WHERE status NOT IN (...)")
   check("全部母单状态都在状态机内", not rows, str(rows))
   ```

#### P0-4：`house_no` 在同一张运单内重复，12 张运单全部中招

**现象**：分单号（House No）在同一张运单内不唯一，多个箱子共用同一个号。

**证据**：
- `SELECT shipment_no, house_no, COUNT(*) FROM tms_shipment_items
   GROUP BY shipment_no, house_no HAVING COUNT(*)>1`：
  ```
  SHP20261010001  MEXP20261010001-01  5
  SHP20261010002  MEXP20261010002-01  4
  SHP20261010003  MEXP20261010003-01  6
  SHP20261010004  MEXP20261010004-01  3
  ...（12 张运单全部有重复，最大的一条重复 6 次）
  ```
- 计划 §3.1 单号体系表明确写了：分单号 House「**租户内唯一**」，格式 `MEXP20261010001-01`。
  实现违反了它自己定的规则。
- 表结构层面也没有兜底：`43-intl-tms-schema.sql:200` 只有 `KEY idx_house_no (house_no)`，
  是**普通索引不是唯一索引**。
- 根因在种子 SQL 的 `box_seq`：`49-intl-seed-orders.sql:322-327`
  ```sql
  SELECT 'SHP20261010001', 'EXP20261010001', 1, 'CSNU6382914', ...   -- box_seq=1
  UNION ALL SELECT 'SHP20261010001', 'EXP20261010001', 2, 'CSNU6382915', ... -- box_seq=2
  UNION ALL SELECT 'SHP20261010001', 'EXP20261010002', 1, 'CSNU6382916', ... -- 又回到 1
  UNION ALL SELECT 'SHP20261010001', 'EXP20261010003', 1, 'CSNU6382917', ...
  ```
  `box_seq` 是**每条记录各自写死**的，不是递增序列；`house_no` 又由它拼出来（第 316 行
  `CONCAT(s.batch_no, '-', LPAD(v.box_seq, 2, '0'))`），于是同一个 `batch_no + "-01"` 反复出现。
- **注意：Java 侧代码是对的**，问题纯在种子。`ExportBatchService.java:286-293` 用的是正确的递增 `seq`：
  ```java
  int seq = 0;
  for (ExportOrder order : orderService.listByBatch(batch.batchNo())) { seq++; ... }
  ```
  这意味着**通过页面真实发起的订舱不会产生重复 house_no，只有种子数据会**——
  但线上现在跑的就是这份种子数据。

**为什么是问题**：
1. **House B/L 是法律单证编号**。目的港清关时凭 HBL 提货，一个号对应两票货，
   海关和船司都无法对账。这不是「字段不好看」，是单证有效性问题。
2. **代码里已经依赖它做幂等去重**：`ShipmentService.mergeItems()`（443~456 行）
   ```java
   if (item.houseNo() != null && !item.houseNo().isBlank() && existing.contains(item.houseNo().trim())) {
       continue;   // 已存在则跳过
   }
   ```
   重复的 house_no 会让**重试订舱时静默丢掉真实的箱子** —— 不是报错，是数据丢失。
   这是最坏的一种失败模式：用户以为补齐了，实际少了几票货。
3. E2E 没覆盖：唯一的箱子相关断言是第 288 行 `len(r.get("orders")) == 1`，
   而 E2E 的测试数据只有**一个母单挂一个子单**（第 240 行建母单只挂 1 条），
   永远撞不上重复。**这是典型的「测试用例的形状恰好避开了 bug」。**

**具体改法**：
1. **改种子**（49 号是幂等脚本，改它不违反 R6「不改历史迁移」——R6 指的是不改
   22~42 号已上线的迁移，49 号是本轮自己新建的）：
   把 `box_seq` 改成按运单内递增。最简单的方式是用窗口函数重编号：
   ```sql
   -- 在 INSERT ... SELECT 外面套一层，用 ROW_NUMBER() 替代手写的 box_seq
   SELECT ..., ROW_NUMBER() OVER (PARTITION BY shipment_no ORDER BY order_no) AS box_seq, ...
   ```
   若 MySQL 版本不支持窗口函数，退而求其次：为每张运单显式写 01..0N。
2. **修历史数据**（新建 50 号迁移）：
   ```sql
   UPDATE tms_shipment_items i
     JOIN (SELECT id, ROW_NUMBER() OVER (PARTITION BY shipment_id ORDER BY id) AS rn
              FROM tms_shipment_items) x ON x.id = i.id
      SET i.house_no = CONCAT(i.shipment_no = (SELECT shipment_no FROM tms_shipments s WHERE s.id=i.shipment_id)
          ? (SELECT batch_no FROM tms_shipments s2 WHERE s2.id=i.shipment_id)
          : i.house_no, '-', LPAD(x.rn, 2, '0'));
   ```
   实际写法可直接用运单的 `batch_no`：`CONCAT(b.batch_no, '-', LPAD(x.rn,2,'0'))`。
3. **加唯一索引兜底**（新迁移）：
   ```sql
   ALTER TABLE tms_shipment_items
     ADD UNIQUE KEY uk_shipment_house (tenant_id, shipment_id, house_no);
   ```
   注意：`house_no` 允许为空串时这条约束会撞车（同 P0-3 里 `awb_no` 的问题），
   所以要先确认所有行的 `house_no` 非空（实测 44 行全部非空），或者改用 NULL 默认值。
4. **改 E2E 的用例形状**（这条比数据修复更重要，否则 bug 还会回来）：
   把第 240 行「建母单只挂 1 条子单」改成挂 **3 条**子单，然后加断言：
   ```python
   check("⑤ 同一母单下 3 个子单生成 3 个不重复的 house_no",
         len({i["houseNo"] for i in r["items"]}) == 3, ...)
   ```

#### P0-5：`MEXP20261010001` 的箱明细件数合计（379）与运单 `total_pieces`（289）对不上

**现象**：M1 的验收基准母单上，同一张运单的箱明细件数加起来比运单主表的件数多 90 件。

**证据**：
```
SELECT s.shipment_no, s.total_pieces,
       (SELECT SUM(i.pieces) FROM tms_shipment_items i WHERE i.shipment_id = s.id)
  FROM tms_shipments s;
```
```
SHP20261010001  total_pieces=289  SUM(items.pieces)=379   ← 差 90
```
其余 11 张运单全部一致（650/650、340/340、1330/1330 …），**只有 M1 的验收基准母单不一致**。

逐箱明细：
```
SHP20261010001 EXP20261010001 MEXP20261010001-01 CSNU6382914 pieces=90
SHP20261010001 EXP20261010001 MEXP20261010001-02 CSNU6382915 pieces=90   ← 同一子单第二次出现
SHP20261010001 EXP20261010002 MEXP20261010001-01 CSNU6382916 pieces=50
SHP20261010001 EXP20261010003 MEXP20261010001-01 CSNU6382917 pieces=15
SHP20261010001 EXP20261010004 MEXP20261010001-01 CSNU6382918 pieces=50
SHP20261010001 EXP20261010005 MEXP20261010001-01 CSNU6382919 pieces=84
```
根因有两层，且**互相独立**：
1. `EXP20261010001` 在种子第 322~323 行出现了**两次**（box_seq=1 和 2），
   两次都取 `o.total_qty`（第 319 行 `o.total_qty, o.total_weight, ...`），
   于是 90 件被算了两次 → 多 90。
2. 运单主表 `total_pieces` 来自母单汇总（第 276 行 `b.total_qty`），母单又来自子单求和，
   是 289（90+50+15+50+84）。**所以主表是对的，明细表多了 90**。

**为什么是问题**：
1. **件数是海关申报与运费计算的核心要素**。箱单件数与运单件数不一致，
   在真实报关场景下会导致申报不实；而 MEXP20261010001 正是计划 §10 里
   那个被当作**验收基准**的母单（`awbNo = COSU6123456`、`shipmentStatus = DEPARTED`）。
2. 它和 P0-4 同源（都是种子 `box_seq` 手写导致的），但**症状不同**：
   P0-4 是「号重复」，这是「件数重复」。修 P0-4 的数据时如果只重编 house_no 而不动 pieces，
   这个还在。
3. 同一份种子里 `49-intl-seed-orders.sql:319` 对每条箱子明细都直接取
   `o.total_qty`（整个子单的件数），而**不是一个箱子的件数**。
   也就是说，即使把重复行删掉，「一个子单一个箱子」这个模型本身也是错的：
   一个子单的 90 件货不可能全在一个箱子里。

**具体改法**：
1. **先定清模型再改数据**。当前 `tms_shipment_items` 混了两种语义：
   - 「一个箱子」→ 有 container_no、有 seal_no、有独立唛头
   - 「一个子单的一份分单（House B/L）」→ 有 house_no
   这两者在真实业务里**不是一对一**：一个子单可能有多个箱（house_no 相同、container_no 不同），
   一个箱里也可能装多个子单（house_no 不同、container_no 相同）。
   计划 §3.3.5 把它们放在一张表里是对的（业界轻量系统常这么做），但**必须明确
   `pieces` 记的是「本行所代表的那部分件数」，且所有行的 pieces 之和 == 运单 total_pieces**。
2. 改种子：删掉 `EXP20261010001` 的重复行；并把每条明细的 `pieces` 改为
   **按箱拆分**的值（同一子单多箱时按比例或按实际装箱数分配，总和守恒）。
3. 加 DB 约束或至少加一条自检（放进 §6.11 的自检清单）：
   ```sql
   -- 期望：0 行
   SELECT s.shipment_no, s.total_pieces, SUM(i.pieces) AS items_pieces
     FROM tms_shipments s JOIN tms_shipment_items i ON i.shipment_id = s.id
    GROUP BY s.id HAVING s.total_pieces <> SUM(i.pieces);
   ```
4. **E2E 加断言**：每个测试母单在订舱后校验件数守恒。
   注意：这条断言必须放在「母单挂 ≥2 个子单」的用例上（与 P0-4 同一个改动），
   单子单的用例永远发现不了。

### P1（会出问题）

#### P1-1：`tms_shipments.is_dangerous` / `un_number` / `dg_class` 全是空，明细里全是 UN3481

**现象**：所有运单的「含危险品」标记都是 0，危险品编号为空，但箱明细里每一条都是 UN3481/class 9。

**证据**：
```
SELECT shipment_no, mode, is_dangerous, un_number, dg_class FROM tms_shipments WHERE is_dangerous=1;
→ 0 行
SELECT s.shipment_no, s.mode FROM tms_shipments s WHERE s.mode IN ('SEA','LCL') AND s.is_dangerous=1;
→ 0 行
```
但明细：
```
SELECT sku,un_number,dg_class,packing_instruction FROM oms_export_order_items WHERE is_dangerous=1 GROUP BY ...;
IPH-15-BLK    8517130000 UN3481 9 PI967 17.30   ×6
POWER-100W    8507600090 UN3480 9 PI967 74.00   ×5
MBP-14-SLV    8471300000 UN3481 9 PI967 72.40   ×6
...（7 个 SKU 全部标记危险品）
```
根因：运单的三个危险品列是**建表时就有的**（`43-intl-tms-schema.sql:151-153`），
但 `49-intl-seed-orders.sql:267-279` 的 INSERT 列清单里**根本没有这三列**，
所以走了默认值 0/''。而 `oms_export_order_items` 那边是填了的。
**危险品信息在「子单 → 运单」汇总时丢失了。**

**为什么是问题**：锂电池是海运/空运的强制申报项（IATA DGR class 9）。
运单上 `is_dangerous=0` 意味着**报关单和舱单都会漏报**。
另外 `ShipmentService.java:363-365` 有一个合规前置校验：
```java
if ("EXPORT_DECLARED".equals(payload.status()) && "HIT".equals(existing.sanctionFlag())) { throw ... }
```
这个校验的对照组 `sanction_flag` 全是 `CLEAR`（种子第 279 行硬编码），所以它也没被真正触发过。

**具体改法**：
1. 在 `LinkageService.ensureShipment()` 组装 `ShipmentCreateCommand` 时，
   **从子单明细汇总危险品**（OMS 库能读到 `oms_export_order_items`，不跨库）：
   ```java
   boolean dg = orders.stream().anyMatch(o -> hasDangerousGoods(o.orderNo()));
   String unNos = distinctJoin(orders.flatMap(o -> unNumbers(o.orderNo())), ",");
   String dgClasses = distinctJoin(..., ",");
   ```
   填进 `ShipmentCreate` 的 `isDangerous` / `unNumber` / `dgClass`。
   注意 `LinkageClient.ShipmentCreate` record 目前**没有这三个字段**，要一并加。
2. 种子 49 号的运单 INSERT 补上这三列，用子表汇总：
   ```sql
   (SELECT MAX(i.is_dangerous) FROM oms.oms_export_order_items i
     JOIN oms_export_orders o ON o.id = i.order_id WHERE o.batch_no = v.batch_no)
   ```
3. 顺带修一个真实错误：`UN3480`（锂电池单独装运）配的包装说明是 `PI967`，
   但 IATA DGR 里 **UN3480 对应 PI965**（PI967 是「锂电池装在设备内」UN3481 用的）。
   见国际对照表 §5 第 9 行。

#### P1-2：`chargeable_weight` 与 `vgm_weight` 直接抄毛重，等于没算

**现象**：三个重量字段（`total_gross_weight` / `chargeable_weight` / `vgm_weight`）在种子里是同一个值。

**证据**：`49-intl-seed-orders.sql:277`
```sql
'FOB', 'USD', b.total_qty, b.total_weight, b.total_volume, b.volumetric_weight,
b.total_weight, b.total_weight,     ← chargeable_weight 与 vgm_weight 都是毛重
```
线上核对 `SHP20261010001: total_gross_weight=89.640, chargeable_weight=89.640, vgm_weight=89.640`。

**为什么是问题**：
1. **VGM 不是毛重**。SOLAS VI/2 要求的是「**核实**总重」，两种法定方法：
   Method 1 用校准设备实称整柜；**Method 2 是「所有货物+托盘+垫料+绑扎材料 之和 + 集装箱自重（tare）」**
   （IMO MSC.1/Circ.1475 §5.1）。当前模型里**没有 `container_tare_weight` 字段**，
   所以 Method 2 在结构上就表达不了 —— 40HQ 柜的自重通常 3.8~4.2 吨，
   直接抄毛重等于把 VGM 少报了几个吨，这是会被船司拒载的（SOLAS：未取得 VGM 的柜不得装载）。
2. **计费重也没算**。`chargeable_weight` 按定义应该是 `max(毛重, 体积重)`（空运）
   或海运的 W/M 规则。现在抄毛重，等于 M3 上运价试算时**每一票都算不出体积重溢价**。
   而由于 P0-1 把体积重放大了 36 倍，如果有人改成 `max()` 又会得到完全错误的答案 ——
   两个 bug 叠在一起。

**具体改法**：
1. 新增 `tms_shipments.container_tare_weight DECIMAL(8,3) NOT NULL DEFAULT 0`
   （新迁移 50 号，不要改 43），并在 `tms_shipment_items` 已有 `gross_weight` 基础上支持 Method 2。
2. `ShipmentService` 增加一个显式方法 `recalcWeights(shipmentId)`：
   ```java
   // 空运/快递：chargeable = max(总毛重, 总体积重)
   // 海运 LCL：chargeable = max(总毛重(吨), 总体积 × 1000)  ← revenue ton
   // VGM Method 2：vgm = Σ(货物+包装) + container_tare_weight
   ```
   除数从 `tms_routes.volumetric_divisor` 读（同 P0-1）。
3. **加状态门禁**：`DEPARTED` 状态下 `vgm_weight > 0` 才允许（海运/内河模式）。
   这条门禁是有价值的 —— SOLAS 明确「未核实总重的柜不得装船」，
   让系统在状态机上体现这条硬规则，比事后被船司拒载强。
   ```java
   if ("DEPARTED".equals(payload.status()) && ("SEA".equals(s.mode) || "LCL".equals(s.mode))
           && (s.vgmWeight() == null || s.vgmWeight().signum() == 0)) {
       throw new IllegalArgumentException("海运开航前必须录入 VGM 核实总重（SOLAS 要求）");
   }
   ```
4. 种子补上正确的值（按 Method 2 + 常见柜型自重估算），并注明估算口径。

#### P1-3：`awb_no` 一列同时装 IATA 空运单号与快递追踪号

**现象**：见 §3 疑点 1 的核实。`awb_no` 里有 `176-30874125`（真 AWB，前缀是航司数字码），
也有 `1Z999AA10123456801`（UPS 追踪号，UPS 自己的 18 位格式，既非 AWB 也非 BL）。

**为什么是问题**：快递没有 AWB。「按 awb_no 搜运单」这个功能在快递场景下语义不对；
如果将来接货代 API 做单号回传（`awbNo` 是 IATA ONE Record / EDIFACT IFTSTA 的关联键之一），
快递单号混在这一列会让映射逻辑必须写 `if (mode == EXPRESS)` 特判。

**具体改法**（按代价从低到高）：
1. **最低成本（推荐 M1 做）**：不改表结构，在 `shared.tsx` 的枚举映射里把列标题
   从「空运单号」改为「**运输单号**」，并在 `IntlShipmentsPage` 的该列加 tooltip
   「空运填 AWB（如 176-30874125），快递填承运商追踪号，海运填 BL」；
   `formatShipmentNo(mode, awbNo, blNo)` 统一一个取单号的函数，避免各处自己拼。
2. **正解（M2 做）**：新迁移加一列 `tracking_no VARCHAR(64) DEFAULT ''` 专放快递追踪号，
   `awb_no` 回归「只有空运才填」的纯语义。同时加 CHECK：
   `CHECK (mode <> 'SEA' OR awb_no = '' OR awb_no LIKE '___-________')`（AWB 是 3-8 格式）。

#### P1-4：缺 `bl_type`（MASTER/HOUSE），无法表达一柜多张 House B/L

**现象**：`tms_shipments` 只有一个 `bl_no`，分单信息全靠 `tms_shipment_items.house_no`，
但**没有字段说明这些 House B/L 是谁签发的、对应哪张 Master B/L**。

**为什么是问题**（行业惯例，附出处见 §5）：
- MBL（Master B/L）由**船司**签发给货代/NVOCC；HBL（House B/L）由**货代或 NVOCC** 签发给真实货主。
  一个 LCL 柜可以有多张 HBL 对应一张 MBL（[DripCapital](https://www.dripcapital.com/en-in/resources/blog/house-bill-of-lading-bl)、
  [3W Logistics](https://3w-logistics.com/what-is-the-difference-house-bill-and-master-bill)）。
- 目的港清关与提货凭 HBL；船司只认 MBL。**没有 bl_type，系统无法回答
  「这票货我们向谁索赔、跟谁对账」** —— 而这正是货代系统的核心命题。
- 线上数据已经暴露这个问题：`SHP20261010001` 有 6 条箱明细（6 个 house_no）
  但只有 1 个 `bl_no = COSU6123456`，无法判断这 6 张 HBL 是同一个货代签的还是有多个。

**具体改法**：
1. 新迁移加两列：
   ```sql
   ALTER TABLE tms_shipments
     ADD COLUMN bl_type VARCHAR(12) NOT NULL DEFAULT 'MASTER'
       COMMENT 'BL类型 MASTER主单(船司签发)/HOUSE分单(货代或NVOCC签发)',
     ADD COLUMN bl_issuer VARCHAR(64) NOT NULL DEFAULT ''
       COMMENT 'BL签发方（船司代码或货代 partner_code）';
   ```
2. `bl_type='MASTER'` 时 `bl_no` 非空；`bl_type='HOUSE'` 时 `bl_issuer` 必填。
   在 `ShipmentService.update()` 里加这条互斥校验。
3. 与 P0-4 的 house_no 修复一起做：修完之后 `tms_shipment_items` 里的 house_no
   才是真正的「HBL 号」，才有意义讨论 MBL/HBL 的对应关系。

#### P1-5：不建外键的决定是对的，但「应用层保证」这句话没有兑现

**主控疑点 4 的判定：决定成立，但缓解措施没落实。**

**证据**：
- 计划 §3.7 规则 2（`docs/logistics-modules-plan.md:1009`）说
  「写路径**不校验**弱引用是否真在对方库存在」，理由是「跨库校验需要网络调用」。
- 规则 1（`:1008`）说「列表渲染**不得**因为该 id 在对方库不存在而报错，查不到就显示 `—`」。
- 但实际实现里**只有 TMS 一处兑现了**：`ShipmentService.create()` 第 206-208 行
  ```java
  if (payload.carrierId() != null && payload.carrierId() > 0) {
      carriers.get(payload.carrierId());   // 不存在直接 404
  }
  ```
  且注释（第 198-202 行）把理由讲得很清楚。**这是执行 agent 自己做的一个计划之外的加强，做得对。**
- **其余全部弱引用都没有校验**：`oms_export_orders.customer_id`（指向 CRM customers）、
  `oms_export_batches.route_id` / `carrier_id`（指向 TMS）、
  `wms_pack_tasks.warehouse_id`（这个有 `requireWarehouse()` 校验，是好的）、
  `tms_shipments.sailing_id`（无校验）。

**为什么是问题**：
「不建外键」在微服务/多库架构下是**正确且必然**的选择（跨库外键在物理上不可能，
同库外键在当前无 ORM 元数据管理的项目里也难维护）。真正的风险不在「有没有外键」，
而在**「悬空引用发生时系统会怎样」**。当前状态是：
- 渲染层：✅ 符合规则 1（`LEFT JOIN` + 显示 `—`）
- 写路径：❌ 不符合规则 2 的**精神** —— 规则 2 的意思是「不跨库校验」，
  但没说「不校验本地能校验的」。`tms_shipments.sailing_id` 指向的是**同一个库**的
  `tms_sailings`，完全可以本地校验，与「跨库要网络调用」无关。同理
  `ExportBatchService.ship()` 里的 `carrier_id` 来自请求参数，也没校验它是否真的是 OCEAN 类型。
- 删除路径：计划 §11 R8 缓解措施 2 说「删 CRM 客户前先查有没有出口订单引用，
  **M1 只做提示不做阻断**（前端弹窗）」。**但这个前端弹窗我在代码里没找到**
  （`CustomerService.delete` 走的是存量逻辑，M1 没动；`PartnerService` 同理）。

**具体改法**：
1. **同库弱引用一律本地校验**（成本极低，收益明确）：
   - `ShipmentService.create/update`：`sailingId > 0` 时调 `sailingService.get()`，
     且校验 `sailing.routeId` 对应的 `route.mode` 与 `shipment.mode` 一致
     （防止「海运单挂到空运船期」这种错配）。
   - `ExportBatchService.ship()`：校验 `carrier_id` 存在**且** `carrier_type='OCEAN'`
     （当 `mode='SEA'` 时）。
   - `ExportOrderService.create`：`customerId > 0` 时至少校验为正数并把
     `customer_name` 快照写进去（已经这么做了，✅）。
2. **把 R8 的「删除提示」补上**，或在文档里改口径为「M2 做」。
   现状是文档承诺了但代码里没有，属于**文档与实现不符**，比不做更糟
   （评审者会以为已经有了）。
3. **加一条巡检 SQL** 放进部署文档，作为每次上线的例行检查：
   ```sql
   -- 期望 0 行：承运商已删但运单还引用
   SELECT s.shipment_no FROM tms_shipments s
     LEFT JOIN tms_carriers c ON c.id = s.carrier_id
    WHERE c.id IS NULL;
   ```

#### P1-6：后端零权限校验（主控疑点 5），附低成本修法

**现象**：任何登录用户（含最小 `USER` 角色）都能建国际运单、改客户授信额度、推进状态。

**证据**（实测，只读）：
```
curl -s -o /dev/null -w '%{http_code}' \
  -H 'X-Tenant-Id: alibaba' -H 'X-User-Id: 1' -H 'X-User-Roles: USER' \
  'http://127.0.0.1:8084/api/tms/intl/shipments?page=1&size=1'
→ 200
```
`X-User-Roles: USER` 完全不影响响应。写端点同理（`POST /api/tms/intl/shipments` 只需登录）。
计划 §11 R7（`:2636-2651`）已诚实记录此缺口并给出两条演进路径。

**为什么是问题**：国内零售场景下，「任何登录用户能改订单」的业务损失有限；
但国际跨境场景下这几个动作都是**对外承担法律责任**的：
- `PATCH /api/tms/intl/shipments/{id}/status` 推到 `EXPORT_DECLARED` = 向海关申报
- 改 `customers.credit_limit` = 改客户可赊账额度 = 直接的资金敞口
- `PATCH /api/tms/intl/customs/{id}/status` 推到 `RELEASED` = 声明货物已放行
- `POST /api/tms/intl/shipments/{id}/tracking` = 伪造承运商轨迹

**具体改法（低成本，M1 就能做，不需要网关改造）**：
计划 §11 R7 提的方案 A（网关注入 `X-User-Permissions`）需要改网关 + 加一次 user-center 查询，
成本确实高。但还有一条**成本更低、当天就能上**的路子：

1. **在四个服务的 `IntlCurrentUser` 里加一个角色判断**（这四个类已经存在，
   正是为此准备的，接口见 `oms/.../intl/IntlCurrentUser.java`）：
   ```java
   /**
    * 写操作的角色闸。当前只有 ADMIN/USER 两个角色（见 user_center 42 号迁移），
    * 所以判断依据用角色而不是细粒度权限点——细粒度要等网关注入权限码（M3）。
    * 写死 ADMIN 是刻意的保守选择：宁可少给人权限，不要在国际单据上给错。
    */
   public static void requireAdminForWrite(String action) {
       if (!IntlCurrentUser.roles().contains("ADMIN")) {
           throw new PermissionDeniedException(action + " 仅限管理员；当前角色无国际物流写权限");
       }
   }
   ```
2. 在所有 `intl` 包的 **POST / PUT / PATCH / DELETE** 方法首行调用。
   这不是最终的权限模型，但**把「登录即可写」降级为「管理员才可写」**，
   而这正是当前角色体系的现实（只有两个角色，细分没意义）。
3. 预计改动量：4 个 `IntlCurrentUser` 各加 1 个方法 + 各 Controller 的写方法各加 1 行调用，
   合计约 30 行。**一天的工作量。**
4. 配套加 E2E 用例（这条现在完全没有）：
   ```python
   st, r = call("/api/tms/intl/shipments", "POST", {...}, user_token)  # USER 角色
   check("负例 非管理员不能建国际运单", st == 403, ...)
   ```
   注意需要一个 `USER` 角色的测试账号；若无，先在 user-center 建一个只用于测试的账号。

#### P1-7：单号生成用 `COUNT(*)+1`，并发下必然撞号

**现象**：所有单号的生成逻辑都是「查当日已有数量 + 1」，无锁、无序列。

**证据**（三处同一模式）：
- `tms/.../ShipmentService.java:511-518` `nextSeq()`
  ```java
  Integer count = jdbc.queryForObject(
      "SELECT COUNT(*) FROM tms_shipments WHERE tenant_id = ? AND shipment_no LIKE ?",
      Integer.class, TenantContext.get(), today + "%");
  return (count == null ? 0 : count) + 1;
  ```
  `generateShipmentNo()`（494~508 行）外面套了 5 次重试，但重试的是**同样的 COUNT**——
  第一次 INSERT 失败后表里还是那个数，5 次都撞同一个号，**重试是无效的**。
- `oms/.../ExportBatchService.java:401-417` 同模式，重试同样无效，
  且第 416 行兜底返回 `prefix + System.currentTimeMillis() % 1000`（**会撞**，
  同一毫秒的两个请求拿到同一个号）。
- `crm/.../TicketService.java:202` `generateTicketNo()`、`wms/.../PackTaskService.java:411`
  `generateTaskNo()`、`tms/.../CustomsService.java:259` `generateDeclarationNo()` 同理。

**为什么是问题**：唯一键会挡住重复写入（抛 `DuplicateKeyException` → 500），
所以**不会产生重复数据**——但用户看到的是 500 而不是 409，用户会重试，
于是**同一时间并发下单的操作员会看到一片 500**。在货代场景里
「两个操作员同时给两个客户建母单」是日常操作，不是边缘情况。
另外 `ExportBatchService` 的 `%1000` 兜底在 1000 次/秒下也会撞。

**为什么 E2E 没发现**：`m1_e2e.py` **全文没有 `threading` / `concurrent` / 并发**任何字样
（grep 验证），所有「幂等」用例都是**串行**调用两次（第 292~299 行的
「重复发起订舱幂等」）。串行永远撞不上 COUNT 竞态。

**具体改法**：
1. **最小改动（当天可做）**：把 5 次重试改成「重试时重新 COUNT」，
   并把兜底从 `%1000` 改成随机后缀 + 长度足够：
   ```java
   for (int i = 0; i < 5; i++) {
       String candidate = prefix + String.format("%03d", nextSeq());   // 每次重新 COUNT
       if (hit(candidate) == 0) return candidate;
       Thread.sleep(20L * (i + 1));   // 退避，让并发的那次先插进去
   }
   return prefix + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
   ```
   这不消除竞态，但把 500 的概率从「必然」降到「低」。
2. **正解（M2）**：引入一个 `tms_number_sequences` 表：
   ```sql
   CREATE TABLE IF NOT EXISTS tms_number_sequences (
       tenant_id VARCHAR(32) NOT NULL,
       biz_type  VARCHAR(16) NOT NULL,     -- SHIPMENT/BATCH/ORDER/TASK/DECL/TICKET
       biz_date  DATE        NOT NULL,
       next_seq  INT         NOT NULL,
       PRIMARY KEY (tenant_id, biz_type, biz_date)
   );
   ```
   用 `UPDATE ... SET next_seq = next_seq + 1 WHERE ...` 的**原子自增**取号
   （InnoDB 行锁保证），彻底消除 COUNT 竞态。不需要引入发号器服务或 Redis。
3. **补并发 E2E**（这是关键，否则改完也验证不了）：
   ```python
   import threading
   results = []
   def race():
       results.append(call("/api/oms/intl/batches", "POST", {...}, token))
   ts = [threading.Thread(target=race) for _ in range(5)]
   [t.start() for t in ts]; [t.join() for t in ts]
   nos = [r[1]["batch"]["batchNo"] for r in results if r[0] == 200]
   check("并发建 5 张母单得到 5 个不同单号", len(set(nos)) == len(nos), str(nos))
   ```

### P2（改进项）

#### P2-1：出口订单列表看不出「这行是海运还是快递」

主控之所以会误判疑点 1，根因就在这。`IntlOrdersPage.tsx` 的列定义里
**没有「运输方式」列**（第 457 行是 `ETA`，第 458 行是 `截关(UTC)`）。
`mode` 字段后端是返回的（`ExportBatch.mode`），但列表不显示。
一行的 `awbNo=1Z999AA10123456801` 与 `awbNo=176-30874125` 在界面上长得一样。

**改法**：在 `IntlOrdersPage.tsx` 的 columns 里，`shipmentNo` 之后插一列：
```typescript
{ title: '运输方式', dataIndex: 'mode', width: 90,
  render: (v?: string) => v ? <Tag color={MODE_COLOR[v]}>{MODE_LABEL[v]}</Tag> : '—' }
```
并把 `shipmentStatus` 列的 tag 颜色按 mode 区分。
这是一行改动，但能消除一整类误读。

#### P2-2：时区「新表 UTC、存量本地」的长期债

**主控疑点 6 的判定：成立，但 M1 不炸，且当前实现比计划描述的更严谨。**

- 实现比计划多走了一步：计划 §11 R3 缓解措施 5（`:2580`）要求「SQL 里『今天/本周』用 `UTC_DATE()` 不用 `CURDATE()`」。
  实际实现里**没有用 `CURDATE()` 去做日期筛选**（`ShipmentService.search` 的
  `etdFrom`/`etdTo` 是前端传的字符串，直接拼进 SQL，没有日期函数），
  所以这个坑暂时没踩。但**下一次谁加一个「今日出港」的看板聚合，`CURDATE()` 就会混进来**。
- 前端 `shared.tsx:371-380` 的 `formatUtc()` 实现是**对的**：
  ```typescript
  const d = new Date(value.endsWith('Z') ? value : `${value}Z`);
  return d.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false });
  ```
  补 `Z` 再指定 `Asia/Shanghai`，与「后端 LocalDateTime 不带时区」的序列化行为匹配。
  注释也写清了为什么不能直接 `new Date(x)`。这段值得表扬。
- **真正的长期风险**：`oms_export_orders.etd_date/eta_date` 是 `DATE` 类型表示「当地日期」，
  `tms_shipments.etd_at/eta_at` 是 `DATETIME` 表示「UTC 时刻」。
  两者对同一票货描述的是**同一个 ETD 的两种表达**。界面上列表列用
  `formatDate(row.etaDate)`（当地日期），详情页运单项用 `formatUtc(shipment.etaAt)`（UTC 转北京时间）——
  **同一个页面里同一个 ETD，两处可能差一天**（例如 ETD 是 `2026-10-14 01:00 UTC`，
  `DATE` 列存 `2026-10-14`，展示成 `10-14`；运单项展示成 `10-14 09:00`，一致。
  但若 ETD 是 `2026-10-13 20:00 UTC`，DATE 列存的是什么？种子第 281 行 `etd_at='2026-10-14 01:00:00'`
  而 `etd_date='2026-10-14'`，即**用 UTC 日期**填了当地日期列**）。

**改法**：
1. 在两个表头注释里写死这个约定（当前 43/44 号已有 `(UTC)` 标注，
   但 `oms_export_batches.etd_date` 的注释是「ETD 计划离港」，**没写是哪个时区的日期**）：
   ```sql
   etd_date DATE COMMENT '计划离港日（以目的港当地日期为准，参考 etd_at 的 UTC 时刻换算）',
   ```
2. M2 上「今日出港」看板前，先确认走 `UTC_DATE()`；或更好——
   **直接在 Java 侧算好日期边界再传参**（现有 `etdFrom` 已经是这个模式，照抄即可）。

#### P2-3：`sanction_flag` 全是 `CLEAR`，合规校验从未被触发

`sanction_flag` 默认 `CLEAR`（`43-intl-tms-schema.sql:154`），种子第 279 行硬编码 `'CLEAR'`，
全 12 张运单无例外。`ShipmentService.java:363-365` 的「HIT 不得报关放行」校验
因此从未在生产数据上跑过一次。

计划 §13 第 12 条「制裁筛查（HIT 放行）应由谁做、留不留痕」本来就标着【待验证】。
**改法**：种子至少放 1 条 `HIT` 的运单（并让它停在 `EXPORT_DECLARED` 之前），
让这条校验在 E2E 里有覆盖：
```python
# 先把某张运单改成 HIT，再试推到 EXPORT_DECLARED，断言 400
```

#### P2-4：`wms_in_transit` 的 `RECEIVED` 不扣库存，「在途量 > 在库量」的荒谬数据

`InTransitService.java:20-24` 的类注释已经诚实说明了这是 M1 的取舍
（「M1 的在途是只读登记，入库时不扣减 `wms_inventory_batches`」）。**这个取舍本身是对的**
（有意的半截真相优于隐形的半截真相）。但种子数据因此产生了看起来像 bug 的结果：

```
SELECT sku, SUM(intransit) FROM wms_in_transit WHERE status IN ('IN_TRANSIT','ARRIVED')
  GROUP BY sku;
TEE-COT-M-WHT   在途 2100
TEE-COT-M-BLU   在途 1700
CERAMIC-MUG     在途 1530
```
而 `wms_inventory_batches` 里 `TEE-COT-M-WHT` 在仓只有 1200+80=1280。
**在途量比在库量还多 64%**。界面上如果同时有「库存批次」和「在途库存」两个 Tab
（`IntlPackingPage` 里确实有），任何做库存的人第一眼就会发现不对。

**改法**：
1. **短期**：在 `IntlPackingPage` 的在途 Tab 上加一个显著的说明
   「M1 在途为只读登记，未与库存批次联动，两者不可相加」。
2. **M2 必须做**：`RECEIVED` 时在同一事务内扣减 `wms_inventory_batches.quantity`
   并累加目的仓的 `wms_inventory.quantity`，这是计划 §8.4 已经写好的设计，
   只是 M1 没实现。**这一条不做，库存和在途就是两个各自为政的账，永远对不上。**

#### P2-5：`EXCEPTION` 母单的箱明细被标成 `LOST`（丢件）

**证据**：
```sql
-- 49-intl-seed-orders.sql:667
CASE WHEN s.status = 'EXCEPTION' THEN 'LOST' ...
```
线上结果：
```
SELECT * FROM wms_in_transit WHERE status='LOST';
SHP20261010011 EXP20261010040 TEE-COT-M-WHT 500 LOST
SHP20261010011 EXP20261010041 TEE-COT-M-BLU 420 LOST
SHP20261010011 EXP20261010041 CERAMIC-MUG   180 LOST
```
但 `MEXP20261010011` 的 remark 是「整批查验，异常挂起」，`SHP20261010011` 的 remark 是「整批查验」——
**海关查验 ≠ 货物丢失**。`LOST`（丢件）是有业务后果的状态（理赔、索赔），
把「查验中」映射成「丢件」会让演示数据在说谎，而且如果将来有「丢件率」这样的看板指标，
它会被这个映射污染。

**改法**：`wms_in_transit.status` 增加 `HELD`（查验扣留）状态，
种子把 `EXCEPTION` 映射改为 `HELD`；`LOST` 留给真正的丢件场景（并单独造一条数据）。
`InTransitService.changeStatus`（100~120 行）的允许集合同步加 `HELD`。

#### P2-6：InTransit 的状态推进没有状态机表

`InTransitService.changeStatus` 只挡了两种情况（第 102 行「已入库不能再改」、
第 105 行「LOST→LOST 幂等」），其余**任意状态可跳任意状态**
—— 正好是 `IntlStateMachine` 类注释里批评国内陆运运单的那个问题。
另外它**没有 `@Transactional`**（对比 `ShipmentService.changeStatus` 第 354 行有）。
在途状态是 WMS 唯一的状态推进入口，缺状态机的代价是「已入库的记录被改回在途」。

**改法**：在 `wms/.../intl/IntlStateMachine.java` 加一张 `IN_TRANSIT` 表
（该文件已存在，只定义了 PACK_TASK），落库同 P2-5 的 `HELD`。

#### P2-7：CRM 渠道商与 TMS 承运商是两个独立主数据，没有关联

计划 §3.7（`:999`）明确说这是刻意设计：「两套主数据并行：TMS 管运输能力，CRM 管合作关系与账期」。
**这个设计是对的**（承运商是行业主数据，渠道商是商务关系，确实该分开）。
但**两张表之间没有任何关联字段**，导致：
- 无法回答「我们和 COSCO 的账期是多少」（`crm_carrier_partners` 里可能有 COSCO，
  但与 `tms_carriers.code='COSCO'` 没有硬关联）
- 结算时不知道该生成对账单给哪个 `partner_id`

**改法**：`crm_carrier_partners` 加一列 `carrier_code VARCHAR(16) DEFAULT ''`
（对应 `tms_carriers.code`），M3 做对账时按它关联。跨库弱引用，同 P1-5 的口径。
现在加成本为零，等 M3 有了对账数据再补就要做数据迁移了。

---

## 5. 国际惯例对照表

> 每行的「出处」列给可核验链接。查不到可靠出处的行明确标「**未查到可靠出处，存疑**」。
> 本表只列**会实际影响本项目决策**的项，不是行业知识大全。

| # | 领域 | 我们的设计 | 国际通行做法 | 差异后果 | 是否该改 | 出处 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | **体积重除数** | `ExportOrderService.java:434` 硬编码 `volume_cbm × 6000`；注释称「海运/空运通用除数」（`:419`） | 空运 IATA 标准 **6000 cm³/kg**（1 m³ ≈ 166.7 kg）；快递/快递集成商 **5000**（≈200 kg/m³）；**海运 LCL 不用除数**，按「1 CBM = 1 吨（revenue ton）」，即 CBM × **1000** | 三重错误：① 单位错 36 倍（P0-1）；② 快递该用 5000；③ **海运该用 ×1000 而不是 ÷6000**，整柜 FCL 更是不按体积算而是按箱型一口价。当前 12 张运单里 6 张是 SEA，**M3 上运价试算时海运报价会错得最离谱** | **必须改**，P0-1 | [IATA 空运运费规则](https://www.iata.org/en/publications/newsletters/iata-knowledge-hub/air-cargo-tariffs-and-rules-what-you-need-to-know/)（6000）、[SSLT Global](https://ssltglobal.com/guides/iata-volumetric-weight)（6000 vs 5000 vs 4000）、[CargoSum](https://cargosum.com/en/chargeable-weight-calculator)（LCL 1 CBM = 1 revenue ton）、[Maersk](https://www.maersk.com/logistics-explained/transportation-and-freight/2025/03/10/air-cargo-chargeable-weight) |
| 2 | **计费重 W/M** | `chargeable_weight` 直接抄毛重（种子 `:277`） | 海运按 W/M（重量吨 vs 体积吨）取大；空运取 max(毛重, 体积重)；快递多按件计费且有最低计费重 | 计费重恒等于毛重，等于**放弃体积重溢价**这个货代最常见的利润来源 | **必须改**，P1-2 | [CargoSum](https://cargosum.com/en/chargeable-weight-calculator)（W/M 取大、快递按件+最低计费） |
| 3 | **Incoterms 2020** | `shared.tsx:179-190` 列 10 个：`EXW/FCA/FOB/CFR/CIF/CPT/CIP/DAP/DPU/DDP` | ICC 官方 **11 个**规则，任意运输方式 7 个（EXW/FCA/CPT/CIP/DAP/DPU/DDP）+ 海运内河 4 个（FAS/FOB/CFR/CIF） | **漏了 FAS（Free Alongside Ship）**。FAS 在南美、俄罗斯、非洲西岸是常用条款，漏掉等于少一个可选项 | **该改**，P2，一行数组的事 | [ICC 官方](https://iccwbo.org/business-solutions/incoterms-rules/incoterms-2020/)（"eleven three-letter trade terms"）、[ICC 简介 PDF](https://2go.iccwbo.org/explore-our-products/ebooks/incoterms/incoterms-guides/incoterms-2020-introduction-free-document-pdf.html) |
| 4 | **Incoterms 的「指定地点」** | 只存 `incoterm`（如 `FOB`），无指定港口/地点字段 | 每个条款都要求「**插入指定港口或地点**」（如 `FOB Shanghai`），且这是合同要件；ICC 明确「A named place or port attached to the three letters … is critical」 | 只存三字母码，**无法确定货权转移地点**，出纠纷时无法举证。种子数据里 `FOB` 实际对应 `CNSHA`，这个信息只在 `pol_code` 里隐含 | **该改**，P2（加 `incoterm_place` 列） | [ICC Incoterms 2020 问答](https://library.iccwbo.org/clp/clp-incoterms-qa-2020.htm)（"a named place or port attached to the three letters … is critical in the workings"） |
| 5 | **Master/House B/L** | `tms_shipments` 单个 `bl_no`，无 `bl_type`，无签发方 | MBL（Master/Carrier B/L）由**船司**签发给货代/NVOCC；HBL（House/Forwarder B/L）由**货代/NVOCC** 签发给真实货主。LCL 一柜可多张 HBL 对应一张 MBL。目的港凭 HBL 提货，船司只认 MBL | 无法表达「一票货 3 张 HBL 对 1 张 MBL」，无法区分索赔对象与对账对象 —— **这恰是货代系统的核心命题** | **该改**，P1-4 | [DripCapital](https://www.dripcapital.com/en-in/resources/blog/house-bill-of-lading-bl)、[3W Logistics](https://3w-logistics.com/what-is-the-difference-house-bill-and-master-bill)（谁签发/签给谁/覆盖范围对照）、[Shipping Solutions](https://shippingsolutionssoftware.com/blog/house-bill-of-lading-vs.-master-bill-of-lading) |
| 6 | **危险品：UN 号与包装说明对应** | 种子把 `UN3480`（锂电池单独装运）标成 `PI967` | UN3480（锂离子电池，单独装运）→ **PI965**；UN3481（锂离子电池**装在设备内**）→ **PI967**；UN3481（与设备同包装）→ PI966；锂金属对应 UN3090/3091 + PI968/969/970。PI 还要分 Section IA/IB/II，Section II 有 ≤100 Wh 免申报豁免 | 包装说明与 UN 号不匹配 = 危包证与申报单据错误，**航空公司会直接拒收**。当前 7 个 SKU 全部标 `is_dangerous=1`，其中 `POWER-100W`（移动电源）的 UN3480 配 PI967 是错的 | **该改**，P1-1 附带 | [IATA 锂电池指南](https://www.iata.org/contentassets/05e6d8742b0047259bf3a700bc9d42b9/lithium-battery-guidance-document.pdf)（UN/PI 对照表）、[ANA Cargo 锂电池图表](https://www.anacargo.jp/en/int/upload/2025/0526/Chart_of_Lithium_batteries_and_Sodium_batteries_EN.pdf)（UN3480→PI965、UN3481→PI966/967 逐条对应）、[IATA DGR 事实表](https://www.iata.org/en/iata-repository/pressroom/fact-sheets/fact-sheet-lithium-batteries)（100 Wh 阈值） |
| 7 | **危险品：空运限制** | 无相关字段/校验 | 锂电池按 Section IA/IB/II 分级；**单独装运的锂电（UN3480 PI965 IA/IB）禁止客机运输，只能货机（CAO）**，且 SoC ≤ 30%；部分航司（Cathay、Jetstar 等）连货机都禁 | 一个标了 UN3480 的货物走客机航班 = **实际会被拒收**，系统层面无任何提示 | **该改**，P2（加 `dg_section` + `ca_only` 标记） | [IATA DGR 锂电池附录](https://www.iata.org/contentassets/05e6d8742b0047259bf3a700bc9d42b9/lbsg8-addendum1-en.pdf)（AF-01/AF-02、JQ-06、4M-08 等航司禁令原文）、[IATA 锂电池事实表](https://www.iata.org/en/iata-repository/pressroom/fact-sheets/fact-sheet-lithium-batteries)（SoC 30%、CAO 限制） |
| 8 | **VGM（核实总重）** | `vgm_weight` 抄毛重；无 `container_tare_weight`；DEPARTED 无门禁 | SOLAS VI/2（2016-07-01 生效）：托运人须在装船前提供**核实**总重。**Method 1** 实称整柜；**Method 2** = Σ(所有货物+托盘+垫料+绑扎材料) **+ 集装箱自重 tare**。**未取得 VGM 的柜禁止装载** | 抄毛重 = 漏掉柜自重（40HQ 约 3.8~4.2 吨），船司有权拒载并产生甩柜费用 | **该改**，P1-2 | [IMO](https://www.imo.org/en/ourwork/safety/pages/verification-of-the-gross-mass.aspx)、[IMO MSC.1/Circ.1475 指南 PDF](https://wwwcdn.imo.org/localresources/en/OurWork/Safety/Documents/MSC.1%20Circ.1475.pdf)（§5.1 两种方法）、[IMO 通函 3624(E)](https://wwwcdn.imo.org/localresources/en/OurWork/Safety/Documents/CIRCULAR%20LETTER%20NO.3624%20%28E%29.pdf) |
| 9 | **舱单 / AMS 24 小时规则** | **完全没有建模** | 美国 AMS、加拿大、欧盟多国、日本、中国、土耳其均要求海运货物**电子舱单在装船前 ≥24 小时**申报；美国违规罚则可达 $5,000。舱单内容须含：完整收货人地址、件数毛重、箱号封条号、船名航次 SCAC、危险品 UN 号与 IMO class + 应急电话 | 这是**硬时限**（不是建议），错过直接罚钱。当前系统连「什么时候必须交舱单」这个时间点都没有 —— `cutoff_at` 只当订舱截止用 | **该改**，P1（`cutoff_at` 拆成 `si_cutoff`（订舱指令截止）/ `vgm_cutoff` / `ams_cutoff` 三个） | [19 CFR §4.7 美国法条](https://www.law.cornell.edu/cfr/text/19/4.7)（24 小时规则原文）、[Hapag-Lloyd AMS 24h](https://www.hapag-lloyd.com/en/services-information/offices-localinfo/local-news/2021/11/ams24-hours-manifest-filing.html)（适用国家清单 + 舱单必填项清单）、[FreightAmigo](https://www.freightamigo.com/en/blog/logistics/demystifying-the-automated-manifest-system-ams-what-shippers-need-to-know)（罚则金额） |
| 10 | **IOSS / 欧盟 B2C VAT** | `tms_shipments.ioss_no` + `customers.ioss_no` 两列，**无任何逻辑使用**；种子全为空 | IOSS 适用于**货值 ≤ €150** 的自欧盟外直接卖给欧盟消费者的 B2C 订单；2021-07-01 起取消 €22 免税门槛，**所有进口货物都征 VAT**；未注册 IOSS 时 €150 以下可用「特殊安排」由承运人代收 | 两列是死字段。当前目的地全是 US/DE/NL，IOSS 确实用不上 —— 但**一旦有欧盟 B2C 直邮业务，这套结构不足以支撑**：还需要「本单是否为 IOSS 订单」「VAT 金额」「是否已申报」三个状态 | **暂不改**（无欧盟 B2C 场景），但要在字段注释里写明「当前未启用，启用需补 VAT 申报状态」，避免下一个人以为它能用 | [欧盟委员会 OSS 官方页](https://ec.europa.eu/taxation_customs/business/vat/oss_en)（€10,000 门槛、€22 豁免取消、IOSS 适用范围）、[IOSS 官方页](https://vat-one-stop-shop.ec.europa.eu/one-stop-shop_en)（≤€150、非消费税商品）、[欧盟税务总司低值货物通关页](https://taxation-customs.ec.europa.eu/customs/customs-procedures-import-and-export/customs-operations/customs-formalities-low-value-consignments_en) |
| 11 | **AWB / 运单标准报文** | `awb_no` / `bl_no` 均为自由文本，无格式校验、无承运商侧标识 | 空运 IATA ONE Record（2026-01 起被 IATA CSC 正式采纳为首选标准，200+ 家参与）用 **JSON-LD** 定义统一数据模型：Waybill / WaybillLineItem / Piece / TransportLegs，含 `involvedParty`、`carrierChargeCode`、`weightValuationIndicator`（IATA 第 14A/14B 项）、`declaredValueForCarriage`（第 16 项）vs `declaredValueForCustoms`（第 17 项，**两个申报价值是不同字段**） | 我们只有一个 `awb_no` 字符串，接 ONE Record 时无字段可映射。另外**我们缺 `declared_value_for_carriage`（运输申报价值）**，只有 `total_amount`（货值）—— 这两个在保险与索赔上完全不同 | **M4 前不改**，但 M4 对接时必须按 ONE Record 的对象模型设计，**不要按我们现在这张平表设计** | [IATA ONE Record](https://www.iata.org/one-record)、[ONE Record 标准页](https://www.iata.org/en/programs/cargo/e/one-record/standard)（数据模型/API/安全三规范）、[ONE Record Waybill 数据模型](https://iata-cargo.github.io/ONE-Record/2026-07/Data-Model/waybill)（AWB 逐栏映射表）、[ONE Record 事实表](https://www.iata.org/en/iata-repository/pressroom/pressroom/fact-sheets/fact-sheet-one-record/)（2026-01 起为首选标准） |
| 12 | **EDI 状态报文** | 无 EDI；回调入口 `X-Internal-Token` 计划放 M2/M3 | 海运/多式联运状态报文标准是 **UN/EDIFACT IFTSTA**（International multimodal status report message），用 `RFF+BM`（Bill of Lading Number）/`RFF+BN`（Booking Number）做关联键，`STS` 段（`STS0101` qualifier + `STS0201` statusCode）表达事件。美线用 **x12 856**（Ship Notice/Manifest，`BSN02` 为 packing slip number、`PRF01` 为 PO 号、`HL` 层级做多层装箱）；发票用 **x12 810**；订舱用 **IFTMIN** | 我们若自研回调协议，节点码与状态码是自定义的 `BOOKED/PICKED_UP/...`。对接传统货代时**必须做 IFTSTA ↔ 自定义码的映射表**；x12 856 的 `HL` 层级模型与我们「一单一条箱明细」的结构也不一致 | **不自研 EDI**（计划 §9.3 判断正确），但**M2 的回调入口应直接按 IFTSTA 的字段形状设计**（`RFF+BN`/`RFF+BM` 关联 + `STS` 事件段 + `DTM` 时间），这样 M4 接货代时零改造 | [UNECE IFTSTA D.21A](https://service.unece.org/trade/untdid/d21a/trmd/iftsta_c.htm)（段结构）、[UNECE IFTSTA D.15B](https://www.unece.org/fileadmin/DAM/trade/untdid/d15b/trmd/iftsta_c.htm)、[MSC IFTSTA 实现指南 PDF](https://developerportal.msc.com/content/MSC_MIG_EDIFACT_IFTSTA_Track_Trace.pdf)（RFF+BN/BM 关联键）、[UNECE IFTMIN](https://service.unece.org/trade/untdid/d12a/trmd/iftmin_c.htm)、[x12.org 856](https://x12.org/node/4398)、[x12.org 810](https://x12.org/node/4354)、[Auria 856 实现指南 PDF](https://www.auriasolutions.com/wp-content/uploads/2018/07/AURIA-EDI-856.pdf)（BSN/PRF/HL 段） |
| 13 | **燃油/旺季附加费** | `tms_rate_card_items.charge_type` 枚举含 `BASE/BAF/SAF/THC/REMOTE/GRI/CUSTOMS_BROKER/DOC_FILE/ENS`（计划 §3.3.8，**M3 才建**） | 船司按 **Platts VLSFO（0.5% 硫）指数**季度均值计算 BAF（Maersk 公开了每个评审期的均值与生效日）；PSS（旺季附加费）与 GRI（综合费率上涨）是独立公告，按航线/箱型分别定价 | 枚举本身**对得上行业**（这是计划做得好的一处）。但缺 `PSS`（旺季附加费）—— 现在只有 `GRI`，而两者是不同机制（GRI 是船司整体调价，PSS 是旺季临时加价） | **M3 建表时补 `PSS`**，成本为零 | [Maersk BAF 公告](https://www.maersk.com/news/articles/2024/12/02/bunker-adjustment-factor)（Platts VLSFO 0.5%、评审期与生效日、箱型分档）、[Maersk BAF 2026 公告](https://www.maersk.com.cn/news/articles/2025/12/01/bunker-adjustment-factor) |
| 14 | **运费附加费索引 / 汇率** | `tms_fx_rates` 表设计存在（M3 才建）：`(base, quote, rate_date)` 唯一，`rate` DECIMAL(16,8)，`source` 标 `MANUAL` | 行业惯例：BAF 跟随 Platts VLSFO 指数（每季度重定）；运价卡带 `valid_from/valid_to`（计划已有）；结汇汇率通常用**中间价**或**结算汇率**（合同约定），不是即期零售价 | 表结构合理。`source='MANUAL'` 意味着 M3 上线后**必须有人每月手工维护汇率**，否则报表口径会漂。建议 M3 同时把 `source` 的取值域扩为 `MANUAL/SYSTEM/BANK_SETTLEMENT` 以区分中间价与结算价 | **M3 改**，P2 | **未查到关于「中间价 vs 结算价」的权威统一规定出处，存疑** —— 这取决于具体合同约定，建议按合同条款实现并保留 `source` 区分 |
| 15 | **追踪事件粒度** | `tms_tracking_nodes`：`node_code` + `node_name` + `node_time` + `location` + `source` + `operator`，只追加不更新，幂等键 `(shipment_no, node_code, node_time)` | IATA ONE Record / EDIFACT IFTSTA 的追踪事件模型：**事件（event）+ 时间 + 地点 + 数据来源**。GS1 EPCIS 2.0 用 `eventTime` / `readPoint` / `bizLocation` / `disposition` 描述事件 | **这一项做对了，是本次最贴合国际惯例的设计。** `source` 列（MANUAL/CARRIER_CALLBACK/SCHEDULED）尤其好 —— 它回答了「这条是人点的还是承运商推的」，这正是多方对账时的关键问题。IFTSTA 规范还明确要求「**Please use UTC for each date/time information**」，与我们存 UTC 的决定一致 | **不改**。若要更完整，可补 EPCIS 风格的 `biz_location`（业务地点，如「已到港」与「在船上」不同） | [IATA ONE Record](https://www.iata.org/one-record)、[MSC IFTSTA PDF](https://developerportal.msc.com/content/MSC_MIG_EDIFACT_IFTSTA_Track_Trace.pdf)（"Please use UTC"）、[UNECE IFTSTA D.21A](https://service.unece.org/trade/untdid/d21a/trmd/iftsta_c.htm)（可按请求/定期/事件触发/异常触发四种方式发送状态） |
| 16 | **多承运商状态口径统一** | 单一 `tms_shipments.status` 16 态，所有承运商共用 | 行业现实：**没有统一标准**。快递（如 UPS/FedEx）有 30+ 个细粒度状态（`M`=已到达处理中心、`D`=已投递待确认…），海运船司只有 8~12 个宏观节点，NVOCC 的状态往往是自己编的。国际惯例是**接入层做映射，对内用统一模型** | 当前设计相当于「假设所有承运商的状态能映射到我的 16 态」，但**没有映射表也没有映射失败的兜底**。M2 接真实承运商回调时，UPS 推来的 `M` 事件无法直接落进任何一态 | **M2 必做**：加 `tms_tracking_events.external_code` + `external_source` 两列存承运商原始码，另建映射表；**映射不到的进 `TRACKING_ONLY` 状态（只记轨迹不动主状态）** | [Shippo API](https://docs.goshippo.com/api-reference/overview)（聚合多家承运商，统一 `Rates`/`Track` 模型）、[Flexport API](https://apidocs.flexport.com/)（`milestone` 事件模型，对接 80+ ERP/TMS）—— 这两家正是「接入层做映射、对内统一」的实践范例 |
| 17 | **库存与在途重复计数** | `wms_in_transit` 是独立表，`RECEIVED` **不扣减** `wms_inventory_batches`（`InTransitService.java:20-24` 明确说明是 M1 取舍） | 标准做法：在途库存与在库库存是**同一实物的两种状态**，出库即从在库转在途（不是两笔），到货再转入库。若分表存储，**必须有明确的转入转出时点** | 当前种子产生了「在途 2100 > 在库 1280」的荒谬数据（P2-4）。这是**已知取舍而非 bug**，但演示数据会误导库存使用者 | **M2 必做**（计划 §8.4 已设计好，只是 M1 未实现） | **未查到针对「在途库存是否应与在库库存互斥」的权威标准原文，存疑** —— 但按 accounting 的 inventory 恒等式（在手 + 在途 = 总量）推导，双计必然错误，这个推导不依赖外部标准 |
| 18 | **集装箱号 ISO 6346** | `container_no VARCHAR(16)`，只存不校验；种子 `CSNU6382914` 等（计划 §13 第 4 条标【待验证】） | ISO 6346：**4 位字母（船公司/箱主代码）+ 6 位序号 + 1 位校验位**，校验位按 ISO 6346 §4 的加权算法计算 | 校验位算法未实现，导致**手输错箱号无法发现**。而舱单（AMS）必须报准确的箱号，报错会导致舱单退单 | **M2 做**：加一个 `isValidContainerNo()` 校验（纯函数，10 行），至少在**发运指令提交时**校验一次 | **未查到 ISO 6346 官方文本的免费公开链接**（标准需付费购买），但校验算法的结构（字母跳过非字母后加权模 11）在行业资料中广泛记载。**建议按 IMO/船司公开的实现指南落地并注明出处，存疑** |

### 5.1 反模式自查：跨境系统最常见的 8 个坑

| 反模式 | 我们中招了吗 | 说明 |
| --- | --- | --- |
| 1. 时区跨日（UTC 与本地混算） | **部分中招** | 新表统一 UTC 且前端 `formatUtc()` 实现正确（`shared.tsx:371-380`），但 `oms_export_batches.etd_date` 是 DATE 类型且种子用 UTC 日期填（P2-2）。目前无跨表相减的代码，暂不爆。 |
| 2. 币种精度（多币种直接 SUM） | **未中招，但埋雷** | 计划 §11 R3 缓解措施 2 要求「任何 SUM 必须 GROUP BY currency」，且 `formatMoney(value, currency)` 在 TS 签名上把 currency 设为必填 —— 这两处做得对。但**当前没有一条聚合查询真正做过 GROUP BY currency**，M3 做看板时会第一次真正用到这条规则。 |
| 3. 箱型箱量与实际不符 | **中招** | `SHP20261010001` 声明 `container_qty=2`（2 个 40HQ），但箱明细里有 **6 个不同集装箱号**（CSNU6382914~919）。6 个箱号配 2 个柜量，**自相矛盾**。根因同样是种子把「每个子单一行」当成「每个箱子一行」。 |
| 4. 报关状态与运输状态脱节 | **未中招** | `tms_customs_declarations` 独立表，`RELEASED` 时联动运单到 `EXPORT_DECLARED`（`CustomsService.java:228`），且实测 `SHP20261010008` 报关 `FILED` + 运单 `BOOKED`，`SHP20261010001` 报关 `RELEASED` + 运单已过 `EXPORT_DECLARED` —— 联动是对的。 |
| 5. 库存与在途重复计数 | **中招（已知取舍）** | `wms_in_transit.RECEIVED` 不扣减 `wms_inventory_batches`（P2-4），种子产生「在途 2100 > 在库 1280」。取舍本身有注释说明，不是 bug，但演示数据会误导。 |
| 6. master/house 单拆分 | **中招** | `tms_shipment_items` 的 `house_no` 在同一运单内重复 12 张运单全中（P0-4），且无 `bl_type` 区分 MBL/HBL（P1-4）。House B/L 是法律单证，编号重复等于单证无效。 |
| 7. 多承运商状态口径不一 | **中招（尚未暴露）** | 单一 16 态模型套所有承运商，无映射表、无 `external_code` 列、无「映射失败只记轨迹」的兜底（P1 / 对照表第 16 行）。M2 接真实回调时第一个撞上。 |
| 8. 危险品信息在汇总时丢失 | **中招** | 明细 7 个 SKU 全标 `is_dangerous=1/UN3481`，但运单层 `tms_shipments.is_dangerous` 全为 0（P1-1）—— 危险品标记在「子单→运单」汇总时蒸发。 |
| 9. 申报要素与实物不符（件数对不上） | **中招** | `SHP20261010001` 箱明细件数合计 379 vs 运单 `total_pieces` 289（P0-5），且该单正是 M1 的验收基准单。 |

---

## 6. 货代对接专章

### 6.1 先回答定位问题：我们的定位论证

计划 §9.1（`docs/logistics-modules-plan.md:2315-2330`）列了「我们做货代系统」与「我们对接货代」两栏，
并在 `:2329` 写下「**本计划默认按『我们对接货代』设计**（更贴合 B2C 跨境卖家的定位）」。

**这个判断和实现对不上。** 证据在数据里，不在文档里：

| 判据 | 「我们做货代系统」应有的形态 | 实际实现 | 出处 |
| --- | --- | --- | --- |
| `carrier_type` 的取值 | 以 `FORWARDER/AGENT/NVOCC` 为主（自有运力或代理运力） | `OCEAN×5 / AIR×2 / EXPRESS×3 / NVOCC×1 / FORWARDER×1`，**船司为主** | `tms_carriers` 12 行 |
| 运价卡由谁定价 | 我方与船司/货代谈的**成本+售价** | 表推到 M3，暂无数据，但设计上是 `tms_rate_cards.card_name` + `carrier_id` 绑定船司 | 计划 §3.3.8 |
| 「发起订舱」的动作 | 我方向船司下订舱指令（EDI IFTMIN / 船司 API） | `POST /api/oms/intl/batches/{id}/ship` 在**自己系统内**建一张 TMS 运单，无任何对外报文 | `ExportBatchService.java:270-319` |
| 承运商合同在我手上 | 有船司合同、有约舱协议、有 VGM 提交通道 | `tms_carriers` 只有 `contact_name/phone/email`，**没有合同号、约舱量、账期** | `43-intl-tms-schema.sql:41-63` |
| `crm_carrier_partners` 的角色 | 合作货代/渠道商（我方是甲方） | 8 行，含 `credit_limit/payment_term_days/rating`，**是「我方给它的授信」** | `crm_carrier_partners` 实测 |
| `sanction_flag` | 对外承运商的合规筛查由我方做 | `CLEAR/PENDING/HIT`，「HIT 必须人工放行」—— 是**我方在审别人的货** | `43-intl-tms-schema.sql:154` |

**结论：实现是「我们做货代系统」，文档写的是「我们对接货代」。**

这不是「文档写错了」的小事，它决定了 **M4 到底要做什么**：
- 若是「做货代系统」：M4 要建的是**对外报文出口**（我方向船司/货代发 IFTMIN/IFTMCS、
  接 COACORI 订舱确认、提交 AMS 舱单与 VGM），以及**货代账期与结算**（我们向货代付款）。
- 若是「对接货代」：M4 要建的是**入向 API**（货代给我们运价、状态、账单），
  且核心能力是**对账**（我们向货代付款）。

两条路的表结构不同、报文方向相反、账期方向相反。**必须在 M3 开工前定下来**，
因为 M3（运价/报价/合同/对账/授信）正是分水岭。

**我的建议：就按「我们做货代系统」继续，但把计划 §9.1 的表述改过来。**
理由有三：
1. 已实现的 12 张表、16 态状态机、报文级字段（BL/AWB/S/O/箱号/封条/唛头/VGM/AMS 相关）
   全部是**自营货代**的形态，接入方形态不需要建 `tms_carriers`（运力是货代给的目录，
   不该由我们维护承运商主数据）。
2. `crm_carrier_partners` 的 `credit_limit` 是「我方在货代处的额度」，方向已经是自营。
3. 计划 §9.1 表格最后一行自己写了「本计划覆盖 §3 的全部表结构」—— 覆盖度上两者都能覆盖，
   但**实现已经选了自营**，改文档比改实现便宜。

### 6.2 三种对接方式对比（含成本与谁做什么）

| 维度 | ① 标准 API（REST/JSON） | ② EDI（EDIFACT / x12） | ③ 文件 / 邮件 / PDF |
| --- | --- | --- | --- |
| **形态** | HTTPS + JSON。船司/头部货代开放平台 | 海运多式联运：**IFTMIN**（订舱）、**IFTMCS**（主单）、**IFTSTA**（状态）、**COACORI**（订舱确认）；美线 **x12 850**（PO）/**856**（ASN）/**810**（发票） | SFTP / 邮件附件：CSV、Excel、PDF 账单与提单 |
| **覆盖能力** | 最强：运价、订舱、取消、改单、轨迹、库存、结算统一入口 | 标准化最强，报文结构固定；美线 856 的 `HL` 层级能表达多层装箱 | **只能做对账与凭证**，做不了实时状态 |
| **实时性** | 秒级~分钟级 | 分钟级（批量报文） | 天级 |
| **幂等/重试** | 天然：HTTP 状态码 + 业务幂等键 | 难：靠 `BGM`/`MEA` 唯一号去重，错误码体系庞大（IFTSTA 的 `9013` 等要查手册） | 靠文件指纹（MD5）去重 |
| **联调成本** | 中：需对方文档 + 沙箱账号 | **高**：需转换器（Mapper）或第三方中间件 | 低：拿模板对字段 |
| **实施周期（经验值）** | 2~4 周/家 | 6~12 周/家（含测试与对账核对） | 3~5 天 |
| **变更成本** | 版本化，加字段向后兼容 | **改一个字段要动转换器 + 对照表 + 三方联调** | 模板变就要重新对齐 |
| **错误可读性** | 好（结构化错误码 + 人话 message） | 差（错误码要查手册） | 无机器可读语义 |
| **谁做什么** | 我方写对接层；对方提供文档与沙箱 | **我方买中间件（SSE / eBizRobot 一类）或自研 Mapper**；对方只发报文 | 我方写 CSV/PDF 解析器；对方发模板 |

**出处**：
- IFTSTA 段结构（`RFF+BM`/`RFF+BN` 关联键、`STS` 状态段、`DTM` 时间）：[UNECE IFTSTA D.21A](https://service.unece.org/trade/untdid/d21a/trmd/iftsta_c.htm)、[MSC 实现指南 PDF](https://developerportal.msc.com/content/MSC_MIG_EDIFACT_IFTSTA_Track_Trace.pdf)
- IFTMIN（订舱）：[UNECE IFTMIN](https://service.unece.org/trade/untdid/d12a/trmd/iftmin_c.htm)
- x12 856/810：[x12.org 856](https://x12.org/node/4398)、[x12.org 810](https://x12.org/node/4354)、[Auria 856 实现指南 PDF](https://www.auriasolutions.com/wp-content/uploads/2018/07/AURIA-EDI-856.pdf)
- API 侧的成熟实践：[Shippo API](https://docs.goshippo.com/api-reference/overview)（多承运商统一 `Rates`/`Track` 模型）、[Flexport API](https://apidocs.flexport.com/)（milestone 事件模型）、[Flexport Logistics API 文档](https://docs.logistics-api.flexport.com/)（明确说明「vendor-route EDI 不支持，需用 EDI 集成」—— 即 API 与 EDI 是互斥的两条路）

### 6.3 推荐路线图（三期，含投入产出）

> **前提**：定位按 §6.1 定为「我们做货代系统」后，M4 的方向是**出向集成**
> （我方作为货代，向船司/上游 NVOCC 发订舱、收状态、提交 VGM/舱单）。
> 若定位改为「对接货代」，下表第 ②③ 期整体镜像（变成收运价/收状态/收账单）。

#### 第 ① 期（M2，1~2 人周）：**先把回调入口的报文形状对齐国际标准，不接任何外部系统**

- **做什么**：
  1. `tms_tracking_nodes` 加 3 列：`external_code VARCHAR(32)`（承运商原始事件码）、
     `external_source VARCHAR(32)`（承运商标识）、`raw_payload TEXT`（原文留档）。
  2. 建立 `承运商原始码 → 我们的 node_code` 映射表（可先硬编码在 Service 里）。
     **映射不到的进 `TRACKING_ONLY`：只追加轨迹、不动主状态**（这是对照表第 16 行的必做项）。
  3. 回调入口的**出向响应体**按 IFTSTA 的形状设计（`accepted` / `duplicated` / `rejected` 三个计数，
     计划 §8.3 已经这么设计了 ✅，只需在文档里写明「本接口是 IFTSTA 的简化子集」）。
  4. `TmsTrackingCallbackRequest` 的 `awbNo` 字段注释里写明：非空运的 IFTSTA 用 `RFF+BM`（BL 号），
     空运用 AWB，海运 LCL 还可用 `RFF+BN`（订舱号）—— 三种关联键都支持。
- **为什么现在做**：回调入口 M2 就要上（M2 计划里有 `POST /api/tms/intl/callback/tracking`）。
  **报文形状定错了，M4 接货代时要重写整个解析器。** 这是「改形状」与「改内容」的成本差。
- **产出**：M4 接入真实货代时，**解析层零改造**，只加映射表数据。
- **不做**：不接任何真实承运商（避免被外部依赖阻塞 M2 验收）。

#### 第 ② 期（M3 后半，2~3 人周）：**VGM + 舱单提交通道（自营货代的核心合规动作）**

- **做什么**：
  1. `tms_shipments` 拆三个截止时间（对应三个不同的外部时限）：
     `si_cutoff_at`（订舱指令/SI 截止）、`vgm_cutoff_at`（VGM 截止）、`ams_cutoff_at`（舱单截止）。
     当前只有一个 `cutoff_at`（对照表第 9 行）。
  2. 加 `container_tare_weight` + 按 SOLAS Method 2 计算 VGM，并在 `DEPARTED` 状态上做门禁（P1-2）。
  3. 生成**舱单报文草稿**（不是提交，只是生成让人工上传/发送）：含船名航次 SCAC、箱号封条号、
     件数毛重体积、完整收货人地址、危险品 UN 号 + IMO class + 应急电话。
     这份清单直接抄 [Hapag-Lloyd 的舱单必填项](https://www.hapag-lloyd.com/en/services-information/offices-localinfo/local-news/2021/11/ams24-hours-manifest-filing.html)。
  4. 危险品合规补强：`dg_section`（IA/IB/II）+ `ca_only` 标记 + UN 号与 PI 的对应表校验
     （P1-1 + 对照表第 6/7 行）。
- **为什么是第 ② 期而不是 M4**：VGM 与舱单是**法定时限**（未交 VGM 不得装船、
  AMS 逾期罚款可达 $5,000），它们不依赖任何外部系统对接 —— 我方自己算自己交。
  放在 M4（依赖货代提供 API）会让合规能力被外部依赖阻塞。
- **产出**：合规能力自持，不因对接进度而延期。
- **投入产出**：这是三期中**唯一「不做就会出事」**的一期。

#### 第 ③ 期（M4，3~4 人周）：**接 1~2 家真实上游（船司 API 优先，EDI 次选）**

- **做什么**（严格按 §6.2 判定规则）：
  1. 优先接**有 API 的船司**（COSCO/ONE/Maersk 等的订舱与舱单 API），走 ①。
  2. 只有在对方坚持 EDI 时才上 ②，且**买中间件不自研 Mapper**（计划 §9.3「不做」栏判断正确，
     理由是省掉 6~12 周/家的实施成本 —— 这个判断有出处支撑：[MSC IFTSTA 的错误码体系](https://developerportal.msc.com/content/MSC_MIG_EDIFACT_IFTSTA_Track_Trace.pdf)
     与 x12 的 `HL` 层级都说明自研转换器是长期负债）。
  3. **必做**：文件/PDF 对账导入（③）。理由：无论 API 谈成怎样，
     **对账单永远会有 PDF/Excel**（船司的 debit note 至今仍有大量 PDF）。
     计划 §9.3 把这条放在 M2 也对 —— 但要说明它是**长期必做**而非过渡方案。
- **产出**：真实上游跑通 1 条完整链路（订舱 → 状态回传 → VGM → 舱单）。
- **投入产出比**：三家里性价比最高的是 ①。②的 6~12 周/家换来的能力，
  用 ①（2~4 周/家）能覆盖 80% 场景 —— 出处：Flexport 明确区分
  「vendor-route EDI 不支持，需用 EDI 集成」（[Flexport Logistics API](https://docs.logistics-api.flexport.com/)），
  即大多数现代货代选择 API 而非 EDI。

### 6.4 主数据交换设计

| 主数据 | 方向（按自营定位） | 交换方式 | 频率 | 本项目落地位置 | 当前状态 |
| --- | --- | --- | --- | --- | --- |
| **承运商主数据**（代码/名称/类型） | **我方维护** | — | — | `tms_carriers` | ✅ 已建（12 行） |
| **航线主数据**（POL/POD + 时效 + 除数） | **我方维护**（与船司谈的） | — | — | `tms_routes` | ✅ 已建（除数已按 6000/5000 填，**但代码没读**） |
| **船期/班期**（船名航次 + ETD/ETA/cutoff） | 船司 → 我方 | ①API 拉取 / ③文件导入 | 每日 | `tms_sailings` | ✅ 已建，**但只能手工录入**（无拉取通道） |
| **运价** | 船司 → 我方（成本）；我方 → 客户（售价） | ①API / ③文件 | 每周 | `tms_rate_cards` + `_items` | ❌ 表未建（M3） |
| **订舱指令 SI** | 我方 → 船司 | ①API / ②IFTMIN | 实时 | `tms_shipments`（`booking_no`） | ❌ 无报文出口（M4） |
| **舱单 AMS** | 我方 → 海关/船司 | ①API / ③文件 | **装船前 ≥24h** | 需新增（P1 / 对照表第 9 行） | ❌ 完全缺失 |
| **VGM** | 我方 → 船司/码头 | ①API / ③文件 | **装船前** | `tms_shipments.vgm_weight` | ⚠️ 字段在但值是抄毛重 |
| **轨迹状态** | 船司 → 我方 | ①回调 / ②IFTSTA | 实时 | `tms_tracking_nodes` | ⚠️ 表在，只支持手工录入 |
| **BL/AWB 副本** | 船司 → 我方 | ③PDF（**提单正本常是纸质**） | 开船后 | 需新增单证表 | ❌ 计划 §9.4 提到「单证表」但 §3 没有 DDL |
| **账单/对账单** | 双向 | ③CSV/XLSX/PDF | 每周/每月 | 需新增（计划 §9.5 已设计） | ❌ M3 |
| **客户与地址** | 我方维护 | — | — | `customers` + `oms_export_orders.consignee_*` | ✅ 已建 |

**关键结论：主数据里最关键的两项（船期、运价）目前都要手工录入。**
`tms_sailings` 有 `vessel_name/voyage_no/etd_at/eta_at/cutoff_at/space_left`，
字段完全够用，但**没有任何自动拉取通道** —— 意味着 M3 之后每天仍要有人手工录 16 条船期。
运价卡同理。这不是技术问题，是**人力口径问题**：手工录入的船期和船司实际船期必然有偏差，
而船期偏差直接导致截关错过。

### 6.5 一句话推荐

> **M2 先把回调入口的报文形状对齐 EDIFACT IFTSTA（承运商原始码留痕 + 映射失败只记轨迹），
> M3 把 VGM 与舱单做成自持的合规能力（拆三个 cutoff + SOLAS Method 2 + DEPARTED 门禁），
> M4 才接真实上游且只接 1~2 家走 REST API、EDI 一律买中间件不自研 —— 
> 同时先把 §6.1 的定位表述改过来：我们现在做的和文档里写的不是同一件事。**

---

## 7. 对计划偏差的判断（逐条）

主控要求判定执行 agent 的三处减法：①推迟费率表/报价合同 ②只做单向链路 ③缩减在途数据量。

### 7.1 偏差一：TMS 只建 7 张表而非 10 张，CRM 只建 partners+tickets

**判定：合理取舍，不是欠债。**

| 证据 | 内容 |
| --- | --- |
| `43-intl-tms-schema.sql:29-30` | 文件头明确写：「M1 范围：本文件只建 M1 用到的 7 张表。`tms_rate_cards` / `tms_rate_card_items` / `tms_fx_rates`（运价卡与汇率）归 M3，随 M3 的端点一起建，避免建出无人读写的死表。」 |
| `46-intl-crm-schema.sql:15` | 「报价单/合同/对账（`crm_quotations` / `crm_quotation_items` / `crm_contracts`）归 M3」 |
| 计划 §10 M1 | 明确写「不含运价试算与汇率端点（放 M3）」 |

**理由**：
1. 「建出无人读写的死表」是真实的坏味道 —— 17 张表里有 3 张没有任何端点引用，
   下一个人不知道它们是否在用。推到 M3 让表与端点同批交付是更干净的做法。
2. M1 的验收断言不依赖运价：§10 M1 验收方式第 ② 条是「建单→派任务→组批→订舱→装箱→
   揽收→报关→开航，8 步全通」，全程不计价。
3. **真正的代价不是 M3 要多写 3 张表**，而是 §7.4 说的那个问题：M1 没有运价卡，
   导致「已开航」这个终点无法验证真实的订舱-计费闭环。这个代价 M1 已经付了（无法回避），
   但它是**里程碑切法的代价，不是执行 agent 的减法失误**。

**但有一处减过头了**：`customers` 表的 `credit_limit` / `credit_used` / `payment_term_days`
三列**在 M1 就加了**（`46-intl-crm-schema.sql:121-139`），
而**唯一会读写它们的 `crm_recon_statements` 推到 M3**。
实测 `CustomerService.java:107-109` 确实在读写这三列，但**没有任何业务逻辑消费它们**：
既不校验超额、也不影响下单、也不在看板上显示。
结果是**建出了 3 个「有人写但没人读」的死列** —— 恰好违反了执行 agent 自己用来论证减法的理由。

**改法（P2 级）**：要么在 M1 加一个只读展示（客户列表显示「已用额度 / 总额度」进度条，
1 行 SQL），要么把这三列也推到 M3 的迁移里。**不要留在中间态。**

### 7.2 偏差二：M1 只做 OMS→TMS 单向写链路

**判定：合理取舍，这是本次架构决策里最正确的一个。**

| 证据 | 内容 |
| --- | --- |
| 计划 §8.2（`:2196-2199`） | 「M1 **只保留 OMS→TMS 这一条同步写链路**，且完全沿用现有 `LinkageClient` 的形态（直连容器网络、写 `X-Tenant-Id`、超时 2s 连接 / 5s 读、先查后建、失败抛 `LinkageException`）。TMS→OMS、WMS→OMS 的反向回调放 M2，且**只做非阻塞**」 |
| 实现 `LinkageService.java:215-240` | `ensureShipment()` 完全按此实现，注释甚至解释了为什么需要两层幂等 |
| 实现 `LinkageService.java:284-297` | 列表聚合两个方向各自 `try/catch`，失败只 `log.warn` + 降级为 `—`，不挡列表 |

**理由**：
1. 四库各自独立、无分布式事务（计划 §1.3 第 5 条明确不做）。
   单向写 + 事务内先查后建 + 失败回滚，是**唯一能保证「不出现半截数据」的组合**。
2. 反向调用若同步，必然在两处打架；若异步非阻塞，就回到「人工补录」——
   那不如一开始就明确「M1 反向由人工点」，把这件事写进文档而不是藏在实现里。
3. 计划 §11 R1 缓解措施 3（第 2534-2535 行）还定了一个关键的单一真相原则：
   「任何关于『货走到哪』的问题以 `tms_shipments.status` 为准；OMS 子单状态是面向客户的展示层，
   **允许滞后**」。这条规则在 M1 的数据里被验证了 —— 子单状态确实总是落后于运单：
   ```
   MEXP20261010002  batch=CLEARING  shipment=IN_TRANSIT  order=ARRIVED
   MEXP20261010007  batch=LAST_MILE  shipment=CLEARED    order=CUSTOMS_CLEARED
   ```
   **规则成立，且执行正确。**

**但代价被低估了一处**：`wms_in_transit` 登记 shipment_no 是「**由前端传入**」
（计划 §8.1 第 ⑦ 步明写）。这意味着在途库存与 TMS 运单的绑定**完全靠人填对**，
没有任何校验。实测 M1 数据的 70 行在途里，`shipment_no` 与 `order_no` 的组合是对的，
但这是种子生成的，不是系统保证的。

**改法（P1）**：`InTransitService.create()` 加一条同库校验 ——
`sailing`/`shipment` 在 WMS 库读不到，但 `shipment_no` 的**格式**可以校验
（`^SHP\d{8}\d{3}$`），且 `order_no` 必须能对应到该 `shipment_no` 下的某个 `order_no` 白名单
（后者需要 OMS 侧提供，代价高）。**最低成本版：只校验 `shipment_no` 格式 + 前端下拉改为
「从 TMS 运单 brief 里选」而不是手填。** 当前 `InTransitPayload.shipmentNo()` 是自由字符串，
应改为必须来自 TMS `/shipments/brief` 的选项。

### 7.3 偏差三：在途数据量从 320 行缩减为 70 行

**判定：合理，且缩减方式暴露了模型问题（已在 P0-5/P2-4 记录）。**

计划 §11 R4 缓解措施 1（第 2602 行）说「控制在 1000 行以内：本计划的种子规模
（母单 12 / 子单 43 / 运单 12 / 在途 320）」。实测 `wms_in_transit` 只有 **70 行**，
`oms_export_order_items` **70 行**（计划说 96 行）。

缩减本身是对的（「在途」若每单每 SKU 一行，320 行 × 4 = 1280 行，
按服务器 2 核 1.7G 的配置（§11 R4）确实没必要）。

**但缩减方式有问题**：种子第 671-673 行用 `JOIN oms.oms_export_order_items` 展开，
即「每个非 DRAFT 运单 × 其下每个子单的每个明细行 = 一行在途」。
这导致 `wms_in_transit` 的粒度是 **SKU 级**，而 `tms_shipment_items` 的粒度是
**子单级**（每行一个箱号）。**三张表三种粒度，没有一对一的对应关系**，
这正是 P0-4/P0-5（house_no 重复、件数对不上）的根因。

**改法（P1）**：明确三个粒度并在表名或列注释里写死：
- `oms_export_order_items`：**子单 × SKU 行**（申报要素粒度）
- `tms_shipment_items`：**子单 × 箱**（House B/L 粒度）
- `wms_in_transit`：**运单 × SKU**（在途库存粒度）

然后加一条自检 SQL 放进 §6.11：
```sql
-- 期望 0 行：运单件数与箱明细件数不一致
SELECT s.shipment_no, s.total_pieces, SUM(i.pieces)
  FROM tms.tms_shipments s JOIN tms_shipment_items i ON i.shipment_id = s.id
 GROUP BY s.id HAVING s.total_pieces <> SUM(i.pieces);
```

### 7.4 减法的整体判断

**三处减法（推迟费率表/报价合同、只做单向链路、缩减在途数据量）都成立。**
执行 agent 在这三处的判断力是好的，且每个取舍都在代码注释或迁移文件头写明了理由
—— 这比「默默少做」强得多。

**真正的欠债不在减法本身，而在减法时遗漏的一致性约束**：
1. 三个粒度没定义（→ P0-4、P0-5）；
2. PUT 路径漏了状态机校验（P0-2）—— 这不是减法，是**验收盲区**；
3. `customers` 三列提前建但无消费方（→ §7.1 末尾）。

---

## 8. E2E 可信度评估

### 8.1 抽检方法

`m1_e2e.py` 共 53029 字节，122 个 `check()` 调用。我抽读了全部负例段（第 165~520 行）
与部分冒烟段（第 519~700 行），并对每个 `check()` 判断其断言的**证据来源**。

### 8.2 抽检结论

**总体：负例设计水平高，但三类最容易假的用例缺了两类半。**

#### 8.2.1 「跨服务失败回滚」— ✅ **覆盖了，且质量高**

第 454~491 行。做法：
1. 先造一个空母单，确认它因「母单下没有子单」在 OMS 自己这里 400（**前置识别**，
   注释第 462-463 行明确说「空母单只会在 OMS 自己这里 400，根本走不到跨库，
   测不到跨库失败回滚」——**知道这个坑并绕开了，值得肯定**）；
2. 挂一条子单进去；
3. 用 `carrierId: 999999`（不存在的承运商）触发 TMS 侧拒绝；
4. **三个独立断言**：
   - HTTP 层：`502` + `code == "linkage_failed"`（第 486-487 行）
   - **数据层**：直查 SQL 确认 `tms_shipments` 里该 `batch_no` 有 0 行（第 484-488 行）
   - **回滚层**：直查 SQL 确认母单 `status` 仍在 `DRAFT/CONFIRMED`（第 489-491 行）

**这三个断言不是自证式的** —— 它们分别从 HTTP 响应、**TMS 库**、**OMS 库**三个独立来源取证，
而不是「接口说成功了所以成功了」。这是本次 E2E 最扎实的一段。
而且 `carrierId: 999999` 这个选择很聪明：它**同时**测了「TMS 的存在性校验」（执行 agent 自修的
第 2 个缺陷）和「OMS 的失败回滚」。

**唯一缺口**：只测了「TMS 主动拒绝」（业务校验失败），**没测「TMS 网络不可达」**
（Connection refused / 超时）。这两种失败的行为不同：
- 业务拒绝 → TMS 事务已回滚，OMS 回滚 ✅ 当前覆盖
- 网络超时 → **TMS 可能已经写成功但响应没回来**，此时 OMS 回滚会留下 TMS 侧的孤儿运单。
  这是分布式写最经典的「不确定提交」问题，当前**完全没测也没有处理**。
  修法见 §8.3 的用例建议。

#### 8.2.2 「并发重复提交」— ❌ **完全没覆盖**

全文件 grep `Thread` / `concurrent` / `并发` / `threading` → **0 命中**。

唯一的幂等用例（第 292~299 行「重复发起订舱幂等」）是**串行**调用两次：
```python
st, r = call(f"/api/oms/intl/batches/{batch_id}/ship", "POST", {...}, token)
check("⑤ 重复发起订舱幂等（复用同一张主单）", ...)
rows = sql(f"SELECT COUNT(*) FROM tms.tms_shipments WHERE batch_no='{batch_no}'")[0]
check("⑤ 同一母单只有 1 张 TMS 运单", int(rows[0]) == 1, str(rows))
```
第二次调用走的是 `ensureShipment` 里的「先查」分支，**根本没走到 TMS 的 create()**。
所以它测的是「先查后建在串行下有效」，而**恰恰漏掉了 `LinkageService.java:205-207` 注释里
自己指出的那个场景**：

> 「**第二层不是冗余**——两个操作员同时点『发起订舱』时两次『先查』都会落空，
> 靠 TMS 侧那道业务键闸拦下第二张单」

注释作者知道需要这层闸，但**没有任何测试证明这层闸在并发下真的拦得住**。
更糟的是 `tms_shipments.batch_no` **没有唯一索引**（`43-intl-tms-schema.sql:166-172` 只有
`uk_shipment_no` 与四个普通 KEY），所以这层闸**只靠 `ShipmentService.create()` 第 209-217 行
的一次 `SELECT`** —— 两个并发事务可以同时通过这个 SELECT，然后同时 INSERT，
产生**两张同 batch_no 的运单**，而 `COUNT(*)=1` 这条断言在串行下永远看不到。

**这就是 P1-7 的并发版**，且比单号撞号更严重（撞号被唯一键挡住，撞 batch_no 没有）。

#### 8.2.3 「跨租户越权」— ⚠️ **覆盖了读取，但写操作未覆盖**

第 424~452 行，分两层设计，**这个思路是对的**：
- 层 ①：经网关伪造 `X-Tenant-Id: taotian`，断言**仍然返回 alibaba 的数据**
  （第 432-433 行）—— 证明网关会剥掉客户端伪造的头；
- 层 ②：直连服务端口带 `X-Tenant-Id: taotian`，断言 404 / 空列表
  （第 435-452 行）—— 证明服务端行级过滤真的生效；
- **并且有对照组**（第 441-443 行「直连 TMS + 租户头 alibaba 能看到运单
  （证明上一条不是『服务没数据』）」）——**这个对照是关键，它排除了「假阴性」**。

但**全部是 GET**。没有任何一条测试：
- 用 taotian 租户 **POST** 一张运单，看它会不会写到 alibaba 名下；
- 用 taotian 租户 **PATCH** 推进别人运单的状态。

考虑到 `LinkageClient` 是 M1 唯一的跨库写通道，且它是**服务端内部调用**（带 `X-Tenant-Id` 头），
「跨库写是否会把数据写错租户」是比「跨租户读」严重得多的问题，当前零覆盖。

#### 8.2.4 自证式（同源数据、无独立预期值）断言清单

以下断言的**期望值来自被测系统自身或同一份输入**，无法发现计算错误：

| 行号 | 断言 | 为什么是自证式 |
| --- | --- | --- |
| 160 | `order["totalQty"] == 100` | 输入是 10 件 × 10 行，输出 100。**只能验证加法，不能验证乘法和单位**。 |
| 161 | `totalAmount == 799900.0` | 输入是构造的固定值，输出与输入一致。**如果 `recalcTotals` 的金额公式写错（比如漏乘 quantity），这个断言仍可能通过**，因为它是「按同一份输入算的期望值」。 |
| 195 | `r["task"]["pieces"] == 100` | 同上，同源。 |
| 248 | `batch["totalQty"] == 100` | 母单汇总 = 子单之和，子单件数就是 100，**自证**。 |
| 288 | `len(r["orders"]) == 1` | 测试数据只有 1 个子单 → 必然是 1。**这正是 P0-4/P0-5 漏网的原因**：用例形状（单子单）恰好避开了「多子单才有」的 bug。 |
| 411-417 | 列表的 `shipmentStatus == "DEPARTED"` / `packStatus == "HANDED_OVER"` / `bookingNo == "COSUE2E00001"` | 这些值是测试自己 PUT 上去的，再读回来确认。**能验证「写进去读出来一致」，不能验证「这个值对不对」**。 |
| 420-422 | 轨迹里有 6 个指定 node_code | 同上，节点是测试自己 PATCH 出来的。 |

**对比：真正有价值的独立断言**（这些能发现真 bug）：
- 第 297 行「重复发起订舱返回同一个 `shipmentNo`」—— 期望值来自**第一次调用的结果**，
  是跨调用的独立参照；
- 第 484-488 行「失败后 TMS 侧没有残留」—— **直查数据库**，绕过了 API 的自证；
- 第 489-491 行「失败后 OMS 母单状态没被推进」—— 同上；
- 第 813 行「数据回到基线（8 张表 count/max(id) 全部一致）」—— **与运行前的快照比对**，
  这是最强的一条断言，它能发现任何「测试自己造的数据没删干净」。

**建议**：把第 288 行的测试母单从「挂 1 个子单」改成「挂 3 个子单」，
这一个改动同时让 house_no 唯一性（P0-4）、件数守恒（P0-5）、多子单汇总（P0-5）三处变成可测。

### 8.3 建议补充的用例（按价值排序）

| # | 用例 | 断言要点 | 能抓到哪个问题 |
| --- | --- | --- | --- |
| 1 | **母单挂 3 个子单后发起订舱** | `house_no` 三者互不相同；`SUM(items.pieces) == shipment.total_pieces`；`SUM(items.gross_weight) == shipment.total_gross_weight` | P0-4、P0-5 |
| 2 | **PUT 带 status 字段** | 期望 400（若采纳改法 A 则期望「status 未被修改」） | P0-2 |
| 3 | **5 线程并发建母单** | 5 个不同的 `batchNo`，全部 200，DB 里 5 行 | P1-7 |
| 4 | **5 线程并发对同一母单发起订舱** | DB 里 `SELECT COUNT(*) FROM tms_shipments WHERE batch_no=?` == **1** | §8.2.2 的孤儿运单 |
| 5 | **taotian 租户 POST 建运单** | 断言该运单**不出现在** alibaba 的列表里（直查两库） | §8.2.3 写越权 |
| 6 | **TMS 网络不可达时发起订舱** | 502 + OMS 回滚 + **TMS 侧无残留**（若 TMS 实际写成功，需有补偿机制） | §8.2.1 不确定提交 |
| 7 | **体积重口径** | `abs(volumetricWeight - totalVolume*1e6/6000) < 0.01` | P0-1 |
| 8 | **母单状态全量自检** | `SELECT ... WHERE status NOT IN (迁移表全集)` 返回空 | P0-3 |
| 9 | **USER 角色建运单** | 403 | P1-6 |
| 10 | **数据自洽巡检**（加进 §6.11） | 运单件数=明细件数之和；运单毛重=明细毛重之和；`house_no` 运单内唯一；母单状态合法 | P0-3/4/5 |

**用例 1、7、8、10 建议做成「每次部署后自动跑」的数据巡检**（不依赖测试数据，
直接查当前库），这四条能挡住本次发现的全部 5 个 P0 中的 4 个。

---

## 9. 下一阶段优先级排序（M2 建议）

### 9.1 先修 M1 遗留（阻塞项，必须在 M2 开工前完成）

> 判断依据：这些不是「优化」，是**数据已经错了**。带着错数据做 M2，
> 等于在错误的基础上继续建功能。

| 优先级 | 事项 | 工作量 | 为什么必须先做 |
| --- | --- | --- | --- |
| **必修-1** | **P0-1 体积重单位**（改 SQL + 修历史数据 + 加断言） | 2h | M3 一上运价试算就立刻算错钱，且错误金额会报给客户。**当前 43 张子单 100% 偏大 36 倍。** |
| **必修-2** | **P0-2 PUT 绕过状态机**（从 update() 删掉 status） | 1h | 只要 PUT 还在，`PATCH /status` 的全部校验都可被绕过。这是「状态机是否存在」的问题，不是优先级问题。 |
| **必修-3** | **P0-4 + P0-5 种子数据修复**（house_no 唯一 + 件数守恒 + 箱量对得上） | 3h | **M1 的验收基准单 `MEXP20261010001` 自身数据就是错的**。不修，M2 的所有「延续到已完结」演示都建立在错数据上。 |
| **必修-4** | **P0-3 母单状态机补 `WAITING_GOODS`**（LCL 拼箱真实需要） | 1h | `MEXP20261010010` 现在推进不了。顺带补三张表的 CHECK 约束防复发。 |
| **必修-5** | **P1-1 危险品汇总 + PI 修正** | 2h | 7 个 SKU 全标了危险品但运单层是 0，**报关会漏报**。顺带修 `UN3480→PI965`。 |

**必修项合计约 1 人日。** 这应该是 M2 开工前的唯一任务。

### 9.2 M2 该做什么（按「先做什么 / 为什么 / 验收标准」）

M2 计划（`:2471-2482`）列的内容是：到港清关、尾程派送、POD 回传、承运商回调入口、
反向非阻塞回调、`@EnableScheduling` + 定时任务、四个页面的新建/编辑表单。

**我的判定：M2 计划的内容有一半该做，一半该往后推。**

#### 先做（优先级从高到低）：

**M2-1 · 数据巡检脚本（新建 `deploy/initdb/` 之外的一个 `scripts/check_intl_data.sh`）**
- **为什么先**：本次 5 个 P0 里有 4 个都是「跑一次 SQL 就能发现」的问题。
  巡检脚本的价值在于**它们能在每次部署后自动发现同类问题**，成本不到半天。
- **验收标准**：脚本输出 0 行即通过；至少覆盖
  ①母单状态合法性 ②运单件数=明细件数和 ③运单毛重=明细毛重和 ④`house_no` 运单内唯一
  ⑤体积重口径 ⑥同库弱引用无悬空 ⑦跨表状态一致性（子单状态不得领先于运单状态）。

**M2-2 · 在途入库扣减库存（计划 §8.4 已设计，M1 未实现）**
- **为什么**：这是「库存和在途永远对得上」的唯一保障。当前数据已经在说谎
  （在途 2100 > 在库 1280）。**拖得越久，M2 引入的 ARRIVED/RECEIVED 状态越多，
  账就越乱。**
- **验收标准**：在途 `IN_TRANSIT → RECEIVED` 后，`wms_inventory_batches.quantity` 同步减少，
  目的仓 `wms_inventory.quantity` 同步增加，两者同一事务；写一个幂等用例（重复推 RECEIVED 不重复扣）。

**M2-3 · 后端写操作的角色闸（P1-6 的低成本版）**
- **为什么**：约 30 行改动、1 人日，把「登录即可改客户授信/推进报关状态」降级为
  「管理员才可」。这是国际跨境场景下的**最低限度**要求。
- **验收标准**：`USER` 角色调 `POST /api/tms/intl/shipments` 返回 403；
  `ADMIN` 角色仍 200；E2E 有用例覆盖。

   > **2026-10-06 升级**：49 个写端点已从「角色闸」全部改为标准 RBAC「权限点闸」
   > （`requirePermission("...", "<svc>:intl:edit")`），权限码由网关注入
   > `X-User-Permissions`（网关从 user-center `/api/users/me` 解析，60s 缓存）。
   > 非 ADMIN 现在可以按角色矩阵被授权（USER 被授 `tms:intl:edit` 实测可建运单）。
   > 原角色闸降为兜底路径（网关未注入权限头/user-center 故障时）。

**M2-4 · 承运商回调入口（计划 §8.3）+ 报文形状对齐 IFTSTA（§6.3 第 ① 期）**
- **为什么**：这是 M2 的核心交付，且**报文形状一旦定错，M4 要重写解析器**。
  现在做成本最低。
- **验收标准**：
  ① `tms_tracking_nodes` 加 3 列（`external_code` / `external_source` / `raw_payload`）并有迁移；
  ② 手工灌 IFTSTA 形状的报文，验证 `accepted` / `duplicated` / `rejected` 三个计数；
  ③ **推一条无法映射的承运商事件码，验证它只落轨迹、主状态不变**（`TRACKING_ONLY` 兜底）；
  ④ `X-Internal-Token` 鉴权生效（无 token / 错 token 均 401/403）；
  ⑤ 幂等：同一批报文推两次，`duplicated` 计数正确、DB 无重复行。

**M2-5 · 定时任务（`@EnableScheduling`）**
- **为什么**：计划 §8.1 的数据流里第 ⑪ 步「开航」当前完全靠人工点。
  有定时任务后，「ETD 到点自动 DEPARTED、ETA 到点自动 ARRIVED」才能验证。
- **验收标准**：测试环境把某张运单的 `etd_at` 改成过去时间，30 秒内状态自动变 `DEPARTED`
  且自动追加一条 `source=SCHEDULED` 的轨迹（**这个 `source` 区分正是 `source` 列的设计价值所在，
  必须验证它真的能被用上 —— M1 全部是 `MANUAL`，`SCHEDULED` 分支从未被执行过**）。

#### 后推（明确建议不要放进 M2）：

| 项 | 推到哪里 | 理由 |
| --- | --- | --- |
| **四个页面的完整新建/编辑表单** | M3 或 M2.5 | 计划 §10 M1 已经把前端定为「只做只读列表 + 详情 Drawer + 状态推进」。表单是**体验工作**，不是链路工作。在数据还没修对之前做表单，等于把错误的字段做成输入框。 |
| **托运行情对接** | M4 | §6.3 已论证：依赖外部 API，不该阻塞 M2。 |
| **`@EnableScheduling` 的「临期批次预警」** | M3 | 预警是运营增强，链路推进是能力缺口。 |

### 9.3 一句话的 M2 建议

> **M2 开工前先用 1 人日修完 5 个 P0（P0-1 体积重、P0-2 PUT 绕过状态机、
> P0-3 母单状态机补 `WAITING_GOODS`、P0-4/5 种子数据、危险品汇总），
> 然后 M2 按「数据巡检脚本 → 在途扣库存 → 后端角色闸 → 回调入口（报文对齐 IFTSTA）→ 定时任务」的顺序做，
> 明确把「四个页面的完整表单」推出 M2。**

---

## 10. 给计划 §13「待验证清单」的结论标注

评审已完成，把 §13 的 15 条逐条给出结论（本次评审只改标注，不改设计）：

| # | 待验证项 | 评审结论 |
| --- | --- | --- |
| 1 | HS 编码位数（中国出口 10 位 vs 13 位） | **10 位**。线上种子的 `8517130000`/`6109100021` 等均为 10 位，`VARCHAR(12)` 留了余量，**不用改表**。来源：中国海关《报关单填制规范》商品编号栏按 10 位填报（[海关总署公告 2016 年第 20 号](https://kab.sww.sh.gov.cn/xwzx/001004/20160329/1e3ef759-e393-4ce6-bae6-8bf3732ab170.html) 附件）。 |
| 2 | IATA AWB 的 3 位航司数字码 | **格式正确**。`176`（Emirates）、`784`（China Southern）都是真实的 IATA 航司数字码，AWB 格式为 `XXX-NNNNNNNN`。线上数据正确，**不用改**。 |
| 3 | 船公司 4 位代码（SCAC） | **有效**。`COSU`（中远海运集运）、`MAEU`（马士基）、`ONEY`（海洋网联）、`CMDU`（达飞）、`MSCU`（地中海航运）均为真实 SCAC 代码。线上 BL 号格式（4 字母 + 7 位）**符合惯例**。 |
| 4 | ISO 6346 校验位算法 | **算法未实现，但格式正确**。`CSNU6382914` 符合「4 字母 + 6 位 + 1 校验位」的结构。官方标准需付费购买，**未查到免费公开的算法原文**；建议 M2 按船司公开实现指南落地并注明出处（**存疑**）。 |
| 5 | Incoterms 2020 完整代码集 | **是 11 个不是 10 个，缺 `FAS`（Free Alongside Ship）**。ICC 明确为 "eleven three-letter trade terms"。`shared.tsx:179-190` 需补 `'FAS'`。来源：[ICC](https://iccwbo.org/business-solutions/incoterms-rules/incoterms-2020/)、[ICC 简介 PDF](https://2go.iccwbo.org/explore-our-products/ebooks/incoterms/incoterms-guides/incoterms-2020-introduction-free-document-pdf.html)。 |
| 6 | 体积重除数（空运 6000 / 快递 5000） | **数值正确，但代码完全没用它**。`tms_routes.volumetric_divisor` 填对了，`ExportOrderService.java:434` 却硬编码 6000 —— 且用错了单位。**这是 P0-1**。海运 LCL 不用除数，按 1 CBM = 1 吨（[CargoSum](https://cargosum.com/en/chargeable-weight-calculator)、[IATA](https://www.iata.org/en/publications/newsletters/iata-knowledge-hub/air-cargo-tariffs-and-rules-what-you-need-to-know/)）。 |
| 7 | 计费重 W/M 比例 | **当前完全没有实现**（`chargeable_weight` 抄毛重）。海运按 W/M 取大、空运取 max(毛重,体积重)。**这是 P1-2**。 |
| 8 | 免堆存/免箱期天数（7~14 天） | **线上数据合理**。实测 `free_time_days`：MAEU=14、ONE=14、COSCO=10、MSC=10、CMA=12、NVOCC/货代=7，与海运 7~14 天的行业区间一致。**不用改**。 |
| 9 | 锂电池 UN 编号与包装说明 | **`UN3480` 的 PI 配错了**：UN3480（锂电单独装运）应对应 **PI965**，代码与种子配的是 PI967（PI967 是 UN3481「装在设备内」用的）。**这是 P1-1**。来源：[IATA 锂电池指南](https://www.iata.org/contentassets/05e6d8742b0047259bf3a700bc9d42b9/lithium-battery-guidance-document.pdf)、[ANA Cargo 对照图](https://www.anacargo.jp/en/int/upload/2025/0526/Chart_of_Lithium_batteries_and_Sodium_batteries_EN.pdf)。UN 号（UN3480/UN3481）、class 9、100 Wh 阈值本身**均正确**。 |
| 10 | 报关单号位数（18 位） | **18 位正确**。中国海关报关单海关编号为 18 位：1-4 位关区代码 + 5-8 位年份 + 第 9 位进出口标志（出口为 `0`）+ 后 9 位顺序号。线上 `TESTID00000001X` 格式符合。来源：[海关总署《报关单填制规范》](https://kab.sww.sh.gov.cn/xwzx/001004/20160329/1e3ef759-e393-4ce6-bae6-8bf3732ab170.html)、[海关总署令第 277 号](https://www.gov.cn/gongbao/2025/issue_12026/202505/content_7022577.html)。 |
| 11 | 汇率数据源与维护频率 | **存疑，无权威出处**。行业惯例是合同约定中间价或结算汇率，因合同而异。建议 `source` 扩为 `MANUAL/SYSTEM/BANK_SETTLEMENT` 以区分口径。**未查到统一权威规定，存疑。** |
| 12 | 制裁筛查（HIT 放行）由谁做、留不留痕 | **建议 M2 落地为「留痕」**：新增 `sanction_screened_at` / `sanction_screened_by` / `screening_ref`（第三方报告号）三列。留痕是合规审计的硬要求（OFAC 要求可追溯）。**具体由谁做取决于业务模式，本评审无法代为决定**。当前 `sanction_flag` 全 `CLEAR`，校验从未被触发（**P2-3**）。 |
| 13 | `crm_carrier_partners.rating`（1~5）评分标准 | **建议用客观指标而非主观打分**：`rating = f(准时率, 货损率, 账期遵守率)`，每季度由 `crm_recon_statements` 自动重算。**当前无对账数据，人工打分不可信（存疑）。** |
| 14 | 关税/消费税税率模型 | **M3 之前不解决，但要知道一件事**：出口报关通常**不征关税与消费税**（中国出口关税为零或适用出口退税；消费税是进口环节概念）。当前 `tms_customs_declarations` 的 `duty_amount` / `consumption_tax` 字段在**出口场景下永远是 0**，属死字段。**应确认这些字段是否该放在出口报关单上，还是移到「目的国进口税费估算」表**（后者是给客户报价用的 DDP landed cost 估算，与海关申报是两回事）。**存疑，需业务确认。** |
| 15 | 是否用 UN/LOCODE 替代 3 位 IATA 码 | **两者都要，且要分开存**。**海港用 UN/LOCODE 5 位**（如 `CNSHA` 是 UN/LOCODE，`CNSHA` 也是 IATA 港口码，此例重合），但**空运必须用 IATA 三字机场码**（如 `PVG`/`ORD`）。行业做法是港口用 UN/LOCODE、机场用 IATA。`VARCHAR(8)` 长度够用。**建议**：保留 `pol_code`/`pod_code`，另加 `pol_locode`/`pod_locode` 专用于海运，**不要混用**。 |

### 10.1 §13 清单之外、本次评审新增的待验证项

| # | 新增项 | 说明 |
| --- | --- | --- |
| 16 | **AMS 舱单 24 小时规则** | 见对照表第 9 行。美国/加拿大/欧盟多国/日本/中国/土耳其均强制。海运业务**没有这个能力就不能做出口**。M2 应拆出 `ams_cutoff_at`。 |
| 17 | **BL 类型（MASTER/HOUSE）** | 见对照表第 5 行。无此字段无法表达 LCL 拼箱的多 HBL 场景。 |
| 18 | **SOLAS VGM Method 2 的箱自重来源** | 集装箱自重（tare）按 ISO 6346 由箱号可推算（不同箱型/箱龄差异显著），或由船司/码头提供。需确认数据来源。 |
| 19 | **承运商原始状态码映射表** | 见对照表第 16 行。UPS/FedEx 有 30+ 细粒度码，需映射到我们的 16 态。映射不到的走 `TRACKING_ONLY`。 |
| 20 | **INCOTERMS 的「指定地点」** | 见对照表第 4 行。只存三字母码无法确定货权转移地点。 |

---

## 11. 结语

**做得好的地方，直说**：
1. `tms_tracking_nodes` 的 `source` 列设计（MANUAL/CARRIER_CALLBACK/SCHEDULED）
   —— 这是一个「看起来多余但极其有用」的字段，它让「这条轨迹是谁写的」成为可查询的事实。
   国际上 ONE Record 与 EPCIS 都有类似的数据来源溯源机制。
2. 跨服务只用业务单号字符串关联、绝不共享自增 id（计划 §3.0 原则 2）
   —— 这是多库架构的正确解法，且贯彻到了每一张表。
3. 多租户隔离的完整链路 + E2E 的双层验证（经网关伪造头 + 直连端口 + 对照组）
   —— 本次打分唯一给 5 分的维度，是名副其实的。
4. 执行 agent 自修的 4 个缺陷里，`ShipmentService.create()` 的承运商存在性校验
   与其注释（第 198-202 行「宁可在建单时用一句人话拒掉」）体现了正确的判断力：
   **不建外键的决定保留，但把「填了个不存在的 id」的后果挡在建单那一刻**。
5. 每个取舍都写了理由（状态机为什么不抽框架、为什么不引入 MQ、为什么不加 Redis），
   这让代码 review 的成本降了一个量级。

**做得不好的地方，也直说**：
1. **验收标准缺了「数据本身对不对」和「规则能不能被绕过」这两个维度。**
   计划 §10 M1 的 5 条验收里，第 ① 条「5 条自检 SQL 全部返回期望行数」只查行数不查数值——
   所以 36 倍的体积重错误、件数差 90 件、house_no 重复 12 张全中，122 条 E2E 一条都没抓到。
2. **`PUT` 路径漏了状态机校验**，而验收全部打在 `PATCH` 上。这是「验收设计与实现同源」的典型盲区。
3. **种子数据用 900+ 行手写 `UNION ALL` 位置参数**，列错位或序号手误都不报错，
   而是变成静默的数据错乱（P0-4/P0-5 都是这么来的）。改成窗口函数 `ROW_NUMBER() OVER (PARTITION BY ...)`
   可以从结构上消除这类错误。

**一句话**：域模型和状态机的设计水平明显高于平均，问题集中在「设计落到数据」这一层。
M1 的正确用法不是「验收通过」，而是「**发现并暴露了 5 个 P0**」——
如果这 5 个 P0 是在 M3 接真实运价之后才被发现，代价会大得多。

---

*本评审未改动任何业务代码、未提交、未部署。*
*对 `docs/logistics-modules-plan.md` 的改动仅限 §13 待验证清单的结论标注（§10）。*
