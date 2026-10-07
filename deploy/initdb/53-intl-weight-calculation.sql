-- =============================================================================
-- 53 号迁移：P1-2 计费重与 VGM 之前是直接抄毛重
--
-- 两个不同的问题，混在一起看不出来：
--
-- 1) chargeable_weight（计费重）应该是 max(毛重, 体积重) 或海运的 W/M 规则，
--    之前直接抄 total_gross_weight —— 每一票都算不出体积重溢价。M3 接真实运价
--    时这会直接算错报价。
--
-- 2) vgm_weight（核实总重）**不是毛重**。SOLAS VI/2 要求的是「核实总重」，
--    两种法定方法（IMO MSC.1/Circ.1475）：
--      Method 1 = 用校准设备实称整柜；
--      Method 2 = Σ(货物+包装+绑扎) + 集装箱自重(container tare)。
--    40HQ 柜自重通常 3.8~4.2 吨，抄毛重等于把 VGM 少报几个吨 —— SOLAS 明确
--    「未取得 VGM 的柜不得装载」，船司会直接拒载。
--    本模型没有 container_tare_weight 字段，Method 2 在结构上就表达不了。
--
-- 所以：新增 container_tare_weight，并按口径回填两个字段。
-- 幂等：可重复执行。
-- =============================================================================

USE tms;

-- container_tare：集装箱自重（吨）。这是 SOLAS Method 2 的必需项，
-- 箱型决定自重，取国际标准值（ISO/IEC 668 对应的 20GP/40GP/40HQ）。
SET @c1 = (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = 'tms' AND TABLE_NAME = 'tms_shipments'
              AND COLUMN_NAME = 'container_tare_weight');
SET @s1 = IF(@c1 = 0,
    'ALTER TABLE tms_shipments ADD COLUMN container_tare_weight DECIMAL(10,3) NOT NULL DEFAULT 0
        COMMENT ''集装箱自重(吨)，SOLAS VGM Method 2 =货物+包装+此值''',
    'DO 0');
PREPARE stmt1 FROM @s1; EXECUTE stmt1; DEALLOCATE PREPARE stmt1;

-- 按箱型给默认自重：只填「容器数为 0 或未填」的行，已有值的（人工录过）不动。
-- 40HQ 柜自重比 40GP 大（含更高的柜顶与加强），40GP 约 3.75 吨，20GP 约 2.2 吨。
UPDATE tms_shipments
   SET container_tare_weight = CASE
           WHEN container_type = '20GP' THEN 2.200
           WHEN container_type = '40GP' THEN 3.750
           WHEN container_type = '40HQ' THEN 3.900
           WHEN container_type = 'LCL'   THEN 0.000   -- 拼箱没有本方集装箱，tare 记 0
           ELSE container_tare_weight
       END
 WHERE tenant_id = 'alibaba'
   AND container_tare_weight = 0
   AND container_type IN ('20GP', '40GP', '40HQ', 'LCL');

-- ------------------------------------------------------------------ P1-2 回填
-- chargeable_weight：按模式分口径
--   SEA/LCL（海运）：W/M = max(毛重(吨), 体积(CBM) × 1) —— 行业惯例是
--     revenue ton（1 CBM = 1 吨）。海运整柜走重量吨更常见，但 W/M 规则本身
--     就是「谁大按谁」，这才是行业口径，也解释了为什么海运没有 6000 除数。
--   AIR/EXPRESS（空运/快递）：max(毛重, 体积重)，体积重已由 OMS 按除数算好。
--   RAIL/TRUCK：近似按重量，不在这轮口径内，保持毛重。
UPDATE tms_shipments
   SET chargeable_weight = ROUND(
         CASE
           WHEN mode IN ('SEA', 'LCL')
                THEN GREATEST(total_gross_weight, total_volume)      -- revenue ton: 1CBM=1t
           WHEN mode IN ('AIR', 'EXPRESS')
                THEN GREATEST(total_gross_weight, volumetric_weight)  -- 体积重 vs 毛重
           ELSE total_gross_weight
         END, 3)
 WHERE tenant_id = 'alibaba'
   AND total_gross_weight > 0;

-- vgm_weight：SOLAS Method 2 = Σ(货物+包装) + 集装箱自重。
-- 这里用total_gross_weight 代表「货物+包装」（明细行 gross_weight 之和已在
-- total_gross_weight 里汇总），加上 tare。
UPDATE tms_shipments
   SET vgm_weight = ROUND(total_gross_weight + container_tare_weight, 3)
 WHERE tenant_id = 'alibaba'
   AND mode IN ('SEA', 'LCL')
   AND total_gross_weight > 0;