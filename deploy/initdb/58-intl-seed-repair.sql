-- =============================================================================
-- 58 号迁移：巡检脚本报出的 13 个真实 ERROR——P0-5 只修了一半，且状态口径分两层
--
-- 这批错误不是本轮新造的，是 scripts/check_intl_data.sh（新增的部署后巡检）
-- 第一次对全库跑体检报出来的。**上一轮说「P0 全部修完」是不准确的**：
-- 件数守恒修对了，但同一处的重量/体积没修；状态一致性的口径本身也简化了。
-- 巡检的价值正在这里——P0 的 5 条里有 4 条只要一条 SQL 就能发现，
-- 而它们之所以没被发现，就是因为没人跑那条 SQL。
--
-- -----------------------------------------------------------------------------
-- 问题 A：P0-5 只修了一半——箱明细的重量与体积重复计了整箱（2 个 ERROR）
--
-- 50 号迁移把 EXP20261010001 拆成两个箱时，按「子单真实件数 ÷ 箱数」把
-- pieces 改成了 45 + 45，但**同一处的 gross_weight 与 volume 两行仍然各自记着
-- 整个子单的 30.900 / 0.136**。于是：
--     运单头 89.640（= 5 个子单真实重量之和，是对的）
--     明细之和 120.540（= 89.640 + 30.900，多了一整份）
-- 体积同理：头 0.404，明细之和 0.540。
--
-- 修法没有解释空间：对半拆之后
--     毛重 15.450×2 + 15.600 + 13.500 + 24.000 + 5.640 = 89.640✓（与运单头逐位吻合）
--     体积 0.068×2 + 0.081 + 0.031 + 0.125 + 0.031       = 0.404✓
-- 运单头是权威（它是 TMS 自己算的汇总），拆完后两边逐位对上，
-- 这就是「不是编的」与「凑出来的」的区别。
-- =============================================================================
USE tms;

-- -----------------------------------------------------------------------------
-- A-1 只改这一个子单的两行。
-- WHERE 里钉死 order_no / house_no 与当前值，重复执行不再命中（幂等）。
-- DECIMAL 用 ROUND(..., 3) 保留三位，与列定义 DECIMAL(10,3) 对齐。
UPDATE tms_shipment_items
   SET gross_weight = ROUND(30.900 / 2, 3),
       volume       = ROUND(0.136 / 2, 3)
 WHERE shipment_no = 'SHP20261010001'
   AND order_no = 'EXP20261010001'
   AND house_no IN ('MEXP20261010001-01', 'MEXP20261010001-02')
   AND gross_weight = 30.900
   AND volume = 0.136;

-- =============================================================================
-- 问题 B：母单/子单「跑在运单前面」——但它其实是**两个方向相反**的问题（11 个 ERROR）
--
-- 巡检第 ⑦ 项报的是「子单不得领先于运单」。逐条核对后发现要分两类，
-- 而两类的修法完全相反。若按「运单头是权威，一律把 OMS 拉回来」处理，
-- 会把真实存在的物理证据一起抹掉。
--
-- ------------------------------- B1：运单头落后于**它自己的轨迹**（2 个 ERROR）
--
--   运单                运单头状态  自己的轨迹节点            母单状态
--   SHP20261010002      IN_TRANSIT 已到 CLEARING（7 个节点）  CLEARING
--   SHP20261010007      CLEARED    已到 LAST_MILE（8 个节点） LAST_MILE
--
-- 轨迹里 ARRIVED / CLEARING / LAST_MILE 都带时间戳摆在那里，母单也跟着推进到了
-- 同一阶段——**是运单头自己没跟上**。所以要推进运单头，不是拉回母单。
--
-- 为什么人工加轨迹不会自动推进状态（`ShipmentService.addTracking` 只追加节点）：
-- 这是刻意的。轨迹是「证据」，状态是「有门禁的决策」。若让人工追加一条 DEPARTED
-- 轨迹就顺手把状态推到 DEPARTED，那 VGM 门禁（SOLAS：未取得核实总重的柜不得装载）
-- 就能被一条自由文本绕过——门禁的强度等于所有写入路径里最弱的那一条。
-- 代价就是允许「证据领先状态」，这正好由本条巡检来发现。
--
-- 说明：这里是对历史数据的修复，**直接 UPDATE 而不走状态机**。状态机不允许
-- IN_TRANSIT 一步跳到 CLEARING（要经 ARRIVED），但那两步的证据（轨迹节点）
-- 已经存在了，逐级重放反而会插入重复节点。历史修复与在线推进入口分开处理。
-- =============================================================================
-- 取「时间上最后一个节点」而不是 MAX(node_code)：node_code 是字符串，
-- 字符串最大是 PICKED_UP（P > I > E > D > C > B），与进度毫无关系。
-- 第一版就是栽在这里，WHERE 不命中、UPDATE 静默 0 行、迁移「成功」但没修。
UPDATE tms_shipments
   SET status = 'CLEARING'
 WHERE shipment_no = 'SHP20261010002'
   AND status = 'IN_TRANSIT'
   AND (SELECT n.node_code FROM tms_tracking_nodes n
         WHERE n.shipment_no = 'SHP20261010002'
         ORDER BY n.node_time DESC, n.id DESC LIMIT 1) = 'CLEARING';

UPDATE tms_shipments
   SET status = 'LAST_MILE'
 WHERE shipment_no = 'SHP20261010007'
   AND status = 'CLEARED'
   AND (SELECT n.node_code FROM tms_tracking_nodes n
         WHERE n.shipment_no = 'SHP20261010007'
         ORDER BY n.node_time DESC, n.id DESC LIMIT 1) = 'LAST_MILE';

-- ------------------------------- B2：母单/子单跑在**所有物理证据**之前（9 个 ERROR）
--
--   运单                运单头状态  轨迹节点             actual_etd_at  母单状态
--   SHP20261010008      BOOKED      只到 EXPORT_DECLARED  NULL          IN_TRANSIT
--                                                                            └ 4 张子单
--
-- 这一张是真的没有任何证据：三个轨迹节点到「已报关」为止，`actual_etd_at` 为空，
-- 而母单与 4 张子单都已经在途。**没有任何物理事件支持它已开航**，所以这里
-- 要把 OMS 侧拉回来（与 B1 相反）。
--
-- 拉回的目标值不是编的：另外 3 个 BOOKED 阶段的母单，其子单一律是 PACKED
-- （本脚本末尾的自证查询就是按这个对照写的），照抄它们即可。
-- =============================================================================
USE oms;

UPDATE oms_export_orders
   SET status = 'PACKED'
 WHERE batch_no = 'MEXP20261010008'
   AND status = 'IN_TRANSIT'
   -- 只有在运单真的还停在订舱、且确实没有开航事实时才拉回。
   -- 判据用 actual_etd_at 为空而不是「运单状态 = BOOKED」：前者是不可伪造的物理事实
   -- （它由实际开航写入），后者只是一个当前状态，可能因为一次漏推而与证据不符。
   AND EXISTS (SELECT 1 FROM tms.tms_shipments s
                WHERE s.tenant_id = oms_export_orders.tenant_id
                  AND s.batch_no = oms_export_orders.batch_no
                  AND s.actual_etd_at IS NULL);

UPDATE oms_export_batches
   SET status = 'BOOKED'
 WHERE batch_no = 'MEXP20261010008'
   AND status = 'IN_TRANSIT'
   AND EXISTS (SELECT 1 FROM tms.tms_shipments s
                WHERE s.tenant_id = oms_export_batches.tenant_id
                  AND s.batch_no = oms_export_batches.batch_no
                  AND s.actual_etd_at IS NULL);

-- =============================================================================
-- 自证：这一段是本脚本存在的意义。跑完看这几行，不对就是修错了，别往下走。
-- =============================================================================
USE tms;

SELECT CONCAT('A 运单头 vs 明细毛重不一致的行数（应为 0）: ', COUNT(*)) AS a_gross
  FROM tms_shipments s
  JOIN (SELECT shipment_no, SUM(gross_weight) g, SUM(volume) v, SUM(pieces) p
          FROM tms_shipment_items GROUP BY shipment_no) i
    ON i.shipment_no = s.shipment_no
 WHERE ABS(s.total_gross_weight - i.g) > 0.001;

SELECT CONCAT('A 运单头 vs 明细体积不一致的行数（应为 0）: ', COUNT(*)) AS a_volume
  FROM tms_shipments s
  JOIN (SELECT shipment_no, SUM(gross_weight) g, SUM(volume) v, SUM(pieces) p
          FROM tms_shipment_items GROUP BY shipment_no) i
    ON i.shipment_no = s.shipment_no
 WHERE ABS(s.total_volume - i.v) > 0.001;

-- B1 的自证：这两张运单的头状态现在应当**等于**自己时间序上的最后一个节点。
-- 这里用「等值」而不是「阶段轴比较」，是因为本迁移修的正是「头落后于自己的
-- 轨迹」这一个形态——修完之后两者应当字面相等，不需要任何映射。
SELECT CONCAT('B1 运单头状态与最后轨迹节点不相等的运单数（本迁移只覆盖这 2 张，应为 0）: ',
              COUNT(*)) AS b1
  FROM tms_shipments s
 WHERE s.shipment_no IN ('SHP20261010002', 'SHP20261010007')
   AND s.status <> (SELECT n.node_code FROM tms_tracking_nodes n
                     WHERE n.shipment_no = s.shipment_no
                     ORDER BY n.node_time DESC, n.id DESC LIMIT 1);

-- B1 的自检**故意不在这里写**：「运单头落后于自己的轨迹」需要把两套状态机
-- 的词表映射到同一条物流阶段轴上才能比较，而那份轴是判断，不是仓库里的既有定义，
-- 它的唯一真相在 scripts/check_intl_data.sh 的第 ⑦⑰ 项里。在 SQL 里抄一份
-- 只会多出第二个可能过期的定义——那正是 P2-2 里「时区口径」栽过的跟头。
-- 所以 B1 的验证交给巡检脚本，本段只断言能用 SQL 无歧义表达的两件事。

USE oms;

SELECT CONCAT('B2 母单已开航但运单无开航事实的行数（应为 0）: ', COUNT(*)) AS b2
  FROM oms_export_batches b
  JOIN tms.tms_shipments s
    ON s.tenant_id = b.tenant_id AND s.batch_no = b.batch_no
 WHERE b.status IN ('DEPARTED', 'IN_TRANSIT', 'ARRIVED', 'CLEARING', 'CLEARED',
                    'LAST_MILE', 'DELIVERED', 'POD_CONFIRMED')
   AND s.actual_etd_at IS NULL;