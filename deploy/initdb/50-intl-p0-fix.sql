-- =============================================================================
-- 50 号迁移：修M1 评审查出的 5 个 P0 数据问题
--
-- 只修数据，不改表结构以外的逻辑；每段都可重复执行（幂等）。
-- 对应评审：docs/logistics-modules-review.md §4 的 P0-1/3/4/5。
-- 代码侧的修法（体积重公式、PUT 不碰 status、补 WAITING_GOODS 状态）在同批 Java 改动里。
-- =============================================================================

USE oms;

-- ------------------------------------------------------------------ P0-3
-- 母单 MEXP20261010010 的 status='PICKING' 是子单状态机的值，母单状态机里
-- 没有它，这张母单任何状态推进都会被 assertTransition 以「未知状态」拒掉。
-- 已给 EXPORT_BATCH 补 WAITING_GOODS（LCL 拼箱等货备货），这里改过去。
UPDATE oms_export_batches
   SET status = 'WAITING_GOODS'
 WHERE tenant_id = 'alibaba'
   AND batch_no = 'MEXP20261010010'
   AND status = 'PICKING';

-- ------------------------------------------------------------------ P0-1
-- 体积重此前按 CBM × 6000 算，偏大 36 倍（正确值 = CBM × 1e6 ÷ 6000）。
-- 三层都要修：子单 → 母单 → 运单，否则汇总层继续带着错值。
-- 只动有体积的记录：total_volume=0 的行体积重本就该是 0（下面 ROUND 结果也是 0，
-- 但显式限掉能让「本段改了几行」在验收时看得清）。
UPDATE oms_export_orders
   SET volumetric_weight = ROUND(total_volume * 1000000.0 / 6000, 3)
 WHERE tenant_id = 'alibaba'
   AND total_volume > 0
   AND ABS(volumetric_weight - ROUND(total_volume * 1000000.0 / 6000, 3)) > 0.0005;

UPDATE oms_export_batches b
   SET b.volumetric_weight = (
           SELECT ROUND(SUM(o.volumetric_weight), 3)
             FROM oms_export_orders o
            WHERE o.tenant_id = b.tenant_id AND o.batch_no = b.batch_no)
 WHERE b.tenant_id = 'alibaba'
   AND EXISTS (SELECT 1 FROM oms_export_orders o
                WHERE o.tenant_id = b.tenant_id AND o.batch_no = b.batch_no)
   AND b.volumetric_weight <> (
           SELECT ROUND(SUM(o.volumetric_weight), 3)
             FROM oms_export_orders o
            WHERE o.tenant_id = b.tenant_id AND o.batch_no = b.batch_no);

USE tms;

-- 运单层的体积重是母单汇总来的（母单 → 运单是跨库弱引用，靠 batch_no 对齐）。
-- 用 LEFT JOIN 而不是子查询：MySQL 不允许 UPDATE 的子查询引用目标表本身。
UPDATE tms_shipments s
   LEFT JOIN (SELECT tenant_id, batch_no, ROUND(SUM(volumetric_weight), 3) AS vol
                FROM oms.oms_export_batches
               WHERE tenant_id = 'alibaba'
               GROUP BY tenant_id, batch_no) b
     ON b.tenant_id = s.tenant_id AND b.batch_no = s.batch_no
   SET s.volumetric_weight = COALESCE(b.vol, s.volumetric_weight)
 WHERE s.tenant_id = 'alibaba'
   AND b.batch_no IS NOT NULL
   AND s.volumetric_weight <> COALESCE(b.vol, s.volumetric_weight);

-- ------------------------------------------------------------------ P0-5
-- 箱明细件数与运单主表对不上：EXP20261010001 在 SHP20261010001 里出现了两行，
-- 每行都记了整个子单的 90 件 → 合计 180，而子单真实件数是 90，多出 90。
--
-- 这里不删行（两行各代表一个真实箱子，container_no 不同），而是按
-- 「子单真实件数 ÷ 该子单在本运单里的箱数」回写每箱件数：
-- 90 ÷ 2 = 45，两行 45+45=90，明细合计与运单 total_pieces 重新守恒。
-- 注意基准必须取「子单的真实件数」（跨库弱引用 order_no），不能拿明细自己
-- 求和——那样 180÷2=90，等于什么都没改（第一版就是这么错的）。
UPDATE tms_shipment_items i
   JOIN tms_shipments s ON s.id = i.shipment_id
   JOIN (SELECT id, shipment_id, order_no,
                COUNT(*) OVER (PARTITION BY shipment_id, order_no) AS sub_rows
           FROM tms_shipment_items
          WHERE tenant_id = 'alibaba') x
     ON x.id = i.id
   LEFT JOIN oms.oms_export_orders o
     ON o.tenant_id = i.tenant_id AND o.order_no = i.order_no
   SET i.pieces = ROUND(o.total_qty / x.sub_rows)
 WHERE i.tenant_id = 'alibaba'
   AND x.sub_rows > 1
   AND s.shipment_no = 'SHP20261010001'
   AND o.total_qty IS NOT NULL
   AND i.pieces <> ROUND(o.total_qty / x.sub_rows);

-- ------------------------------------------------------------------ P0-4
-- house_no 在同一张运单内重复（12张运单全中）：种子按「每个子单各自从 -01 起」
-- 拼号，而 house_no 是法律单证编号（提单分单号），运单内必须唯一。
-- 重编为「同一运单内全局递增」，与 ShipmentService.mergeItems() 生成规则一致
-- （那边是 int seq = 0; seq++ 对母单下全部子单递增，不是按子单分组）。
UPDATE tms_shipment_items i
   JOIN (SELECT id, ROW_NUMBER() OVER (PARTITION BY shipment_id ORDER BY id) AS rn
           FROM tms_shipment_items) x ON x.id = i.id
   JOIN tms_shipments s ON s.id = i.shipment_id
   SET i.house_no = CONCAT(s.batch_no, '-', LPAD(x.rn, 2, '0'))
 WHERE i.tenant_id = 'alibaba'
   AND i.house_no IS NOT NULL AND i.house_no <> ''
   -- 已经全局递增过的就别再改，否则重跑本迁移会把号打乱
   AND i.house_no <> CONCAT(s.batch_no, '-', LPAD(x.rn, 2, '0'));