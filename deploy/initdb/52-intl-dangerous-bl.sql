-- =============================================================================
-- 52 号迁移：P1-1 危险品在「子单 → 运单」汇总时丢失 / P1-4 缺 BL 类型
--
-- P1-1：oms_export_order_items 里每个带电 SKU 都标了 is_dangerous + un_number，
--        但 tms_shipments 的 is_dangerous / un_number / dg_class 全是空——
--        建运单时没有从子单明细汇总（详见 ExportBatchService.sumDangerousGoods）。
--        这里补齐存量运单的三个字段。
-- P1-1b：UN3480（锂电池单独装运）错配了 PI967（那是 UN3481「装在设备内」的包装说明），
--        IATA DGR 里 UN3480 对应 PI965 / PI966。这一条是真实的数据错误。
-- P1-4：缺 bl_type（MASTER 主单 / HOUSE 分单），无法表达「一柜多张 HBL 对应一张 MBL」，
--        也就无法回答「这票货向谁索赔、跟谁对账」——货代系统的核心命题。
--
-- 幂等：可重复执行。
-- =============================================================================

USE oms;

-- ------------------------------------------------------------------ P1-1b
-- UN3480 = 锂电池单独装运 → PI965（货板/货柜装运）；UN3481 = 装在设备内 → PI967。
-- 这里只改 UN3480 的错配，UN3481 保持 PI967。
UPDATE oms_export_order_items
   SET packing_instruction = 'PI965'
 WHERE tenant_id = 'alibaba'
   AND is_dangerous = 1
   AND un_number = 'UN3480'
   AND packing_instruction <> 'PI965';

USE tms;

-- ------------------------------------------------------------------ P1-4
-- bl_type：MASTER=主单（船司签发给货代/NVOCC）；HOUSE=分单（货代签发给真实货主）。
-- bl_issuer：签发方（船司代码或货代 partner_code），HOUSE 时必填。
--
-- MySQL 8.0 **不支持** ADD COLUMN IF NOT EXISTS（那是 MariaDB 的语法），
-- 所以这里查 information_schema 再决定要不要加——迁移要能重复执行。
SET @c1 = (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = 'tms' AND TABLE_NAME = 'tms_shipments' AND COLUMN_NAME = 'bl_type');
SET @s1 = IF(@c1 = 0,
    'ALTER TABLE tms_shipments ADD COLUMN bl_type VARCHAR(12) NOT NULL DEFAULT ''MASTER''
        COMMENT ''BL类型 MASTER主单(船司签发)/HOUSE分单(货代或NVOCC签发)''',
    'DO 0');
PREPARE stmt1 FROM @s1; EXECUTE stmt1; DEALLOCATE PREPARE stmt1;

SET @c2 = (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = 'tms' AND TABLE_NAME = 'tms_shipments' AND COLUMN_NAME = 'bl_issuer');
SET @s2 = IF(@c2 = 0,
    'ALTER TABLE tms_shipments ADD COLUMN bl_issuer VARCHAR(64) NOT NULL DEFAULT ''''
        COMMENT ''BL签发方（船司代码或货代 partner_code）''',
    'DO 0');
PREPARE stmt2 FROM @s2; EXECUTE stmt2; DEALLOCATE PREPARE stmt2;

-- ------------------------------------------------------------------ P1-1
-- 运单的危险品三件套从母单下的子单明细汇总。
-- 用 GROUP_CONCAT + DISTINCT 一次性算出（去重、逗号分隔），避免逐单回查。
UPDATE tms_shipments s
  JOIN (
        SELECT o.tenant_id, o.batch_no,
               MAX(i.is_dangerous) AS dg_flag,
               GROUP_CONCAT(DISTINCT i.un_number ORDER BY i.un_number SEPARATOR ',') AS un_list,
               GROUP_CONCAT(DISTINCT i.dg_class  ORDER BY i.dg_class  SEPARATOR ',') AS class_list
          FROM oms.oms_export_order_items i
          JOIN oms.oms_export_orders o ON o.id = i.order_id
         WHERE i.is_dangerous = 1 AND i.un_number <> ''
         GROUP BY o.tenant_id, o.batch_no
       ) g ON g.tenant_id = s.tenant_id AND g.batch_no = s.batch_no
   SET s.is_dangerous = g.dg_flag,
       s.un_number    = COALESCE(g.un_list, ''),
       s.dg_class     = COALESCE(g.class_list, '');

-- ------------------------------------------------------------------ P1-4 存量对齐
-- 存量运单一柜多箱（多个 house_no）但只有一个 bl_no，按「主单 + 船司签发」补默认语义：
-- bl_type=MASTER，bl_issuer 留空（船司代码在 tms_carriers.code，UI 上按 carrier 关联展示，
-- 这里不硬写，避免与船期表重复维护）。
UPDATE tms_shipments
   SET bl_type = 'MASTER',
       bl_issuer = COALESCE(NULLIF(bl_issuer, ''),
                            (SELECT c.code FROM tms_carriers c WHERE c.id = carrier_id))
 WHERE tenant_id = 'alibaba'
   AND bl_type = 'MASTER'
   AND bl_no <> ''
   AND bl_issuer = '';