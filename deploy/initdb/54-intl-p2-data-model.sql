-- =============================================================================
-- 54 号迁移：P2-3 / P2-5 / P2-6 / P2-7（数据与模型层）
--
-- 这四条有一个共同的病根：**代码里的判断写了，但数据永远走不到那一步**。
-- 制裁筛查全是 CLEAR，所以「HIT 不得报关」的校验在生产数据上一次没跑过；
-- EXCEPTION 母单的箱明细被写成 LOST，所以 LOST 这个有理赔后果的状态
-- 装的全是「查验中」；在途状态没有迁移表，所以「已入库被改回在途」拦不住；
-- 渠道商与承运商两套主数据没有任何关联列，所以「我们和 COSCO 的账期是多少」
-- 答不出来。改数据 + 补结构，让这四条判断第一次真正被触发。
--
-- 对应评审：docs/logistics-modules-review.md §4 的 P2-3 / P2-5 / P2-6 / P2-7。
-- 代码侧（IntlStateMachine.IN_TRANSIT / changeStatus 加 @Transactional /
-- Partner 的 carrierCode 读写）在同批 Java 改动里。
--
-- 本脚本同时承担两件事，两件事都必须幂等：
--   一、给**已存在的实例**补结构（45/46 号只改了 CREATE TABLE，存量库不会重跑）；
--   二、改存量数据（种子留下的错值）。
-- MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS（那是 MariaDB），判存在走
-- information_schema + PREPARE，照抄 52/53 号的写法。
-- =============================================================================

-- ---------------------------------------------------------------- P2-3 制裁 HIT 样本
-- 之前 12 张运单 sanction_flag 全是 CLEAR，ShipmentService 里
-- 「HIT 不得推到 EXPORT_DECLARED」的那段校验在真实数据上从未执行过一次——
-- 没有样本的校验等于没有校验，出问题时是第一次发现，而不是第二次。
--
-- 挑 SHP20261010004（BOOKED「已订舱待揽收」）：它是**报关前**最后一个可推进状态，
-- 从这里推 EXPORT_DECLARED 唯一会被拦的就是制裁校验本身（BOOKED → EXPORT_DECLARED
-- 不在 TMS 状态机里，会先被状态机以「非法状态迁移」拒掉，测不出制裁这条）。
-- 不挑 SHP20261010001：那张是 P0-5 修正箱明细件数的正例基准单，
-- 把它标成 HIT 会让两件事混在一起，也与它 DEPARTED 的状态自相矛盾（已开航的货
-- 不可能还卡在制裁筛查）。
--
-- HIT 是**人工核**出来的，不是系统自动判的，所以 remark 里写明这是演示数据，
-- 免得半年后有人拿这行去追真实合规问题。
USE tms;

UPDATE tms_shipments
   SET sanction_flag = 'HIT',
       remark = CONCAT(remark,
           '；【演示数据】制裁筛查命中：收发货方命中 SDN 清单（模拟第三方报告号 '
           'SDN-P2-DEMO-0001），未人工放行前不得报关放行')
 WHERE tenant_id = 'alibaba'
   AND shipment_no = 'SHP20261010004'
   AND sanction_flag <> 'HIT'
   AND remark NOT LIKE '%SDN-P2-DEMO-0001%';

-- ---------------------------------------------------------------- P2-5 / P2-6 在途
USE wms;

-- 1) status 列的取值域写在 COMMENT 里。
--    关键是把 HELD（查验扣留）与 LOST（丢件）分开：这两个状态在业务上后果完全不同。
--    LOST 触发理赔/索赔，看板上会进「丢件率」；HELD 只是海关扣着，货还在、
--    责任在海关与收发货人之间未定。把两者混成一个状态，数据就在说谎。
--    MODIFY 幂等：重复执行只是把同一句 COMMENT 再写一遍。
ALTER TABLE wms_in_transit
    MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'IN_TRANSIT'
    COMMENT 'IN_TRANSIT在途/ARRIVED已到仓/RECEIVED已入库（终态）/HELD海关查验扣留（货在，未丢）/EXCEPTION异常挂起/LOST丢件（终态，触发理赔，不等于查验）';

-- 2) remark 列：状态本身不解释「为什么」。
--    没有它，「这行为什么是 LOST」只能靠猜；而 LOST 与 HELD 的区别恰恰在原因上。
--    （InTransitPayload 早就声明了 remark 入参，但表里没有这一列——
--      入参被接收后静默丢弃，是本仓库「半截真相」的一个实例，这里一并补上。）
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'wms' AND TABLE_NAME = 'wms_in_transit' AND COLUMN_NAME = 'remark'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE wms_in_transit ADD COLUMN remark VARCHAR(255) NOT NULL DEFAULT ''''
        COMMENT ''状态原因（如查验单号/索赔工单号）；状态本身不解释为什么，这列解释''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3) 把「查验」从 LOST 改判为 HELD。
--    判据不是「这行有没有 remark 写着查验」，而是**母单的状态**：
--    母单停在 EXCEPTION（整批查验/异常挂起）时，箱明细就是被扣着，
--    海关没放行之前没人知道这批货最后会不会退运——把它记成丢件是凭空
--    造出一个理赔事件。JOIN tms 而不是写死 shipment_no，是为了以后任何
--    EXCEPTION 母单生成的箱明细都不会再犯同一个错。
--    幂等靠 status = 'LOST'：改完就不是 LOST 了，重跑不命中。
UPDATE wms_in_transit it
    JOIN tms.tms_shipments s
      ON s.tenant_id = it.tenant_id AND s.shipment_no = it.shipment_no
   SET it.status = 'HELD',
       it.remark = CONCAT('海关查验扣留：母单 ', it.shipment_no,
                          ' 处于 EXCEPTION（整批查验挂起），货在海关手里未丢失，'
                          '不适用理赔流程；放行后回到 ARRIVED，退运则转 EXCEPTION')
 WHERE it.tenant_id = 'alibaba'
   AND it.status = 'LOST'
   AND s.status = 'EXCEPTION';

-- 4) 造一条**真正**的 LOST。
--    否则 LOST 这个状态在数据里永远只有「查验」这一个合法样本，将来
--    「丢件率」看板算出来的是查验率。数据里必须有真丢件，状态才有意义。
--
--    不用凭空造行，而是让数据自证：CRM 工单 TK202610030008（ABANDON，已 RESOLVED）
--    写的是「尾程签收少一件，已向 GOKU 索赔，GOKU 确认赔付 1299.00 USD」，
--    对应子单 EXP20261010026 的 40 把人体工学椅——而 wms_in_transit 里
--    这 40 把整行记成 ARRIVED（全部到仓），与工单自相矛盾。
--    在途行的语义是「这一行货现在怎么样」，一行只能一个状态，
--    所以丢的那 1 件必须自己占一行：40 → 39 ARRIVED + 1 LOST。
--    这样子单明细件数（40）与在途明细之和（39 + 1）重新守恒。
--    NOT EXISTS 里套了一层派生表：INSERT 的目标表不能在自身子查询里被引用，
--    套派生表让 MySQL 先物化，否则报 ERROR 1093。幂等靠 NOT EXISTS。
UPDATE wms_in_transit
   SET quantity = quantity - 1
 WHERE tenant_id = 'alibaba'
   AND shipment_no = 'SHP20261010007'
   AND order_no = 'EXP20261010026'
   AND sku = 'CHAIR-ERGO'
   AND status = 'ARRIVED'
   AND quantity = 40;      -- 幂等：改成 39 之后本段不再命中

INSERT INTO wms_in_transit
    (tenant_id, shipment_no, order_no, sku, product_name, batch_no, quantity,
     from_warehouse_id, to_warehouse_id, shipped_at, arrived_at, status, remark)
SELECT 'alibaba', it.shipment_no, it.order_no, it.sku, it.product_name, it.batch_no, 1,
       it.from_warehouse_id, it.to_warehouse_id, it.shipped_at, it.arrived_at, 'LOST',
       '尾程（GOKU 谷仓海外仓）签收少一件，承运人已确认丢失并赔付：工单 TK202610030008，'
       '赔付 1299.00 USD。真丢件，与 HELD 的海关查验不是一回事'
  FROM wms_in_transit it
 WHERE it.tenant_id = 'alibaba'
   AND it.shipment_no = 'SHP20261010007'
   AND it.order_no = 'EXP20261010026'
   AND it.sku = 'CHAIR-ERGO'
   AND it.status = 'ARRIVED'
   AND it.quantity = 39
   AND NOT EXISTS (
       SELECT 1 FROM (SELECT tenant_id, shipment_no, order_no, sku, status FROM wms_in_transit) x
        WHERE x.tenant_id = 'alibaba'
          AND x.shipment_no = 'SHP20261010007'
          AND x.order_no = 'EXP20261010026'
          AND x.sku = 'CHAIR-ERGO'
          AND x.status = 'LOST');

-- ---------------------------------------------------------------- P2-7 渠道商↔承运商
-- 计划 §3.7 明确说两套主数据并行是**刻意设计**（TMS 管运输能力、
-- CRM 管合作关系与账期），这个设计是对的，评审也认可。问题只在于
-- 两张表之间一个关联字段都没有，于是「我们和 COSCO 的账期是多少」
-- 答不出来，结算时也不知道该把对账单发给哪个 partner_id。
--
-- 现在加这一列成本为零（表小、无历史数据要迁），等 M3 有了对账数据再补
-- 就得做一次数据迁移了。
--
-- **跨库弱引用**，口径同 P1-5：不建外键（跨库外键物理上不可能），
-- 写路径也不校验它是否真在 tms_carriers 里——跨库校验要网络调用，
-- 会把一次普通的渠道商保存变成一次跨服务调用，失败还会连累保存本身。
-- 空串（而不是 NULL）表示「这家渠道商不是某家承运商」：
-- 报关行、海外仓、卡航本来就没有对应的船司代码，让它们留空比硬凑一个值诚实。
USE crm;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'crm' AND TABLE_NAME = 'crm_carrier_partners' AND COLUMN_NAME = 'carrier_code'
);
-- 注意引号层数：外层是字符串字面量，里面的 DEFAULT '' 与 COMMENT '...' 各要再翻一层，
-- 所以 DEFAULT 后面是**四个**单引号。写成六个会在 DEFAULT 后面提前闭合字符串。
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE crm_carrier_partners ADD COLUMN carrier_code VARCHAR(16) NOT NULL DEFAULT ''''
        COMMENT ''对应 TMS tms_carriers.code（承运商代码）；跨库弱引用：空串=不是承运商（报关行/海外仓/卡航），写路径不跨库校验''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 存量回填：**只认两种能自证的匹配**，其余一律留空，不猜。
--   1) partner_code = code（两边都用同一个代码，如 LAZYLION / SEAWAY）；
--   2) partner_name = name_zh（中文名完全一致）。
-- 之所以要 COUNT(DISTINCT c.code) = 1：一旦某个渠道商同时匹配上两 家承运商，
-- 说明这层主数据本身有歧义，此时任何自动选择都是编的，留空等人来定。
UPDATE crm_carrier_partners p
    JOIN (
        SELECT p2.tenant_id, p2.partner_code,
               MIN(c.code) AS code,
               COUNT(DISTINCT c.code) AS matched
          FROM crm_carrier_partners p2
          JOIN tms.tms_carriers c
            ON c.tenant_id = p2.tenant_id
           AND (c.code = p2.partner_code OR c.name_zh = p2.partner_name)
         GROUP BY p2.tenant_id, p2.partner_code
    ) m
      ON m.tenant_id = p.tenant_id AND m.partner_code = p.partner_code
   SET p.carrier_code = m.code
 WHERE p.tenant_id = 'alibaba'
   AND p.carrier_code = ''
   AND m.matched = 1;
