-- =============================================================================
-- 59 号迁移：巡检脚本 ⑲ 项新抓出来的 2 张「运单头落后于自己最后一条轨迹」
--
-- 这两张和 58 号修的那两张是同一类（运单头状态没跟上自己的轨迹节点），
-- 只是 58 号当时按「运单头 vs 母单」的对得上的口径人工扫出了 2 张，
-- 漏了另 2 张——而这 2 张恰好母单还跟不上，所以 ⑰ 没报、⑲ 才报。
--
--   运单                运单头      最后一条轨迹节点            source
--   SHP20261010001      DEPARTED    IN_TRANSIT                 CARRIER_CALLBACK（10-18 记录）
--   SHP20261010008      BOOKED      EXPORT_DECLARED            MANUAL（10-12 人工登记）
--
-- 证据都在，头状态就是没跟上。按「轨迹节点 > 运单头状态 > 母单」的权威顺序，
-- 头状态应当对齐证据。
--
-- 说明：离线修复直接 SET，不走状态机——和 58 号 B1 同理。状态机不允许
-- BOOKED 一步跳到 EXPORT_DECLARED（要经 PICKED_UP），但中间节点的证据（PICKED_UP
-- 轨迹）已经存在，逐级重放只会插入重复节点。历史修复与在线推进入口分开处理。
-- 幂等：WHERE 钉住「当前仍停在旧值」，重复执行不再命中。
-- =============================================================================
USE tms;

UPDATE tms_shipments
   SET status = 'IN_TRANSIT'
 WHERE shipment_no = 'SHP20261010001'
   AND status = 'DEPARTED'
   AND (SELECT n.node_code FROM tms_tracking_nodes n
         WHERE n.shipment_no = 'SHP20261010001'
         ORDER BY n.node_time DESC, n.id DESC LIMIT 1) = 'IN_TRANSIT';

UPDATE tms_shipments
   SET status = 'EXPORT_DECLARED'
 WHERE shipment_no = 'SHP20261010008'
   AND status = 'BOOKED'
   AND (SELECT n.node_code FROM tms_tracking_nodes n
         WHERE n.shipment_no = 'SHP20261010008'
         ORDER BY n.node_time DESC, n.id DESC LIMIT 1) = 'EXPORT_DECLARED';

-- 自证：这 2 张的头状态应等于自己时间序最后的一个节点的，跑完应为 0 行。
SELECT CONCAT('59 后头与最后轨迹节点不一致的运单数（应为 0）: ', COUNT(*)) AS check_b1
  FROM tms_shipments s
 WHERE s.shipment_no IN ('SHP20261010001', 'SHP20261010008')
   AND s.status <> (SELECT n.node_code FROM tms_tracking_nodes n
                     WHERE n.shipment_no = s.shipment_no
                     ORDER BY n.node_time DESC, n.id DESC LIMIT 1);