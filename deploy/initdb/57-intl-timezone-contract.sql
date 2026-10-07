-- =============================================================================
-- 57 号迁移：P2-2 时区约定——「标注当地日期的列里存着 UTC 日期」
--
-- 44 号建表时写死了口径（评审已确认）：
--   DATETIME 列 = UTC，列名 _at 后缀，COMMENT 标「（UTC）」；
--   DATE 列 = 当地日期这个业务事实，不带后缀，不做时区换算。
--
-- **但数据没有遵守这个口径。** 种子在填 oms_export_orders.etd_date（当地日期）
-- 时直接抄了 tms_shipments.etd_at 的 UTC 日期：
--   etd_at = '2026-10-13 20:00:00'（UTC）→上海当地已是 10-14 08:00
--   而 etd_date 存的是 2026-10-13（UTC 日期），不是 2026-10-14（当地日期）
--
-- 后果是界面在自己说谎，而且**同一页面上同一个 ETD 会出现两个日期**：
--   子单列表列用 formatDate(etdDate)      → 10-13（错的，少一天）
--   详情页运单项用 formatUtc(etdAt)       → 10-14 04:00（对的，已转北京时间）
-- 人会以为船期表录错了，或者以为有人在 UTC 与当地时间之间算错了。
--
-- 实测（本次迁移前的真库数据，43 张子单）：
--   ETD：37 行恰好同一天，**6 行差一天**
--   ETA：43 行全部同一天（种子的 ETA 时刻都落在 UTC 上午，+8h 不跨日）
--
-- ---------------------------------------------------------------- 为什么 ETA 不一起修
-- **故意不修 ETA。** ETA 描述的是**目的港**的当地日期（洛杉矶/纽约/汉堡），
-- 而目的港时区取决于 pod_code 对应的国家——本项目目前没有国家→时区的映射表。
-- 拿 Asia/Shanghai 去换算 ETA 会得到一个「用起运港时区解释目的港日期」的错值，
-- 那不是修数据，是把一个半截真相换成一个更难发现的错。
-- 所以 ETA 的 COMMENT 改成写明现状（当前按 UTC 日期近似）与后续口径（M3 补时区表）。
--
-- ---------------------------------------------------------------- 幂等
-- 本脚本幂等，可重复执行：
--   - MODIFY COLUMN ... COMMENT 重复执行只是把同一句再写一遍；
--   - 数据修正用「重算后与现值不同才写」的形式，跑第二遍条件不再命中。
-- =============================================================================

USE oms;

-- ---------------------------------------------------------------- 1. 数据修正
-- 跨库 UPDATE JOIN：两库在同一实例，可以直接全限定名引用（评审 §4 P1-5 的口径，
-- 同实例跨库 SQL 引用不算「跨库校验」，它不需要网络调用）。
--
-- 幂等靠算式本身：第二次跑 DATE(etd_at + 8h) 已经等于 etd_date，条件不命中。
UPDATE oms_export_orders o
    JOIN tms.tms_shipments s
      ON s.tenant_id = o.tenant_id
     AND s.batch_no = o.batch_no            -- 子单挂母单，母单挂运单
   SET o.etd_date = DATE(s.etd_at + INTERVAL 8 HOUR)
 WHERE o.tenant_id = 'alibaba'
   AND s.etd_at IS NOT NULL
   AND o.etd_date <> DATE(s.etd_at + INTERVAL 8 HOUR);

UPDATE oms_export_batches b
    JOIN tms.tms_shipments s
      ON s.tenant_id = b.tenant_id
     AND s.batch_no = b.batch_no
   SET b.etd_date = DATE(s.etd_at + INTERVAL 8 HOUR)
 WHERE b.tenant_id = 'alibaba'
   AND s.etd_at IS NOT NULL
   AND b.etd_date <> DATE(s.etd_at + INTERVAL 8 HOUR);

-- ---------------------------------------------------------------- 2. 注释写死口径
-- 原来的「（当地）」三个字太含糊：哪个当地？换算规则是什么？写的人各理解各的，
-- 于是种子直接把 UTC 日期抄了进去。COMMENT 是唯一会跟着列走遍全库的地方，
-- 口径必须写在这里，而不是只写在文档里等人去读。
--
-- MODIFY 必须重写完整列定义（MySQL 不支持只改 COMMENT），所以顺序/类型/默认值
-- 与 44 号建表保持一致——改类型或顺序会让已有实例与新建库结构分叉。
ALTER TABLE oms_export_orders
    MODIFY COLUMN etd_date DATE NULL DEFAULT NULL
        COMMENT '计划开港/起飞日（起运港当地日期=Asia/Shanghai）。= DATE(tms_shipments.etd_at + 8h)。与详情的 formatUtc(etdAt) 同源，不要再抄 UTC 日期',
    MODIFY COLUMN eta_date DATE NULL DEFAULT NULL
        COMMENT '预计到港日（目的港当地日期）。**当前按 UTC 日期近似**——目的港时区需 pod_code→国家→时区表，M3 补；在此之前不要用 Asia/Shanghai 换算，那会用起运港时区解释目的港日期',
    MODIFY COLUMN ready_date DATE NULL DEFAULT NULL
        COMMENT '备货完成日（境内业务日期=Asia/Shanghai）。纯境内事件，不跨时区';

ALTER TABLE oms_export_batches
    MODIFY COLUMN etd_date DATE NULL DEFAULT NULL
        COMMENT 'ETD 计划开港日（起运港当地日期=Asia/Shanghai）。= DATE(tms_shipments.etd_at + 8h)',
    MODIFY COLUMN eta_date DATE NULL DEFAULT NULL
        COMMENT 'ETA 预计到港日（目的港当地日期）。**当前按 UTC 日期近似**，M3 补目的港时区表后再改口径';

-- ---------------------------------------------------------------- 3. 自证
-- 迁移跑完看一眼这两行：应分别是 43 / 12 行的 0（即已全部对齐）。
-- 如果非 0，说明有子单挂不到运单（batch_no 为空或运单已删），需要人工看一眼。
SELECT CONCAT('子单 ETD 与运单 UTC 差 1 天的行数（应为 0）: ', COUNT(*)) AS check_orders
  FROM oms.oms_export_orders o
  JOIN tms.tms_shipments s
    ON s.tenant_id = o.tenant_id AND s.batch_no = o.batch_no
 WHERE o.tenant_id = 'alibaba' AND s.etd_at IS NOT NULL
   AND o.etd_date <> DATE(s.etd_at + INTERVAL 8 HOUR);
SELECT CONCAT('母单 ETD 与运单 UTC 差 1 天的行数（应为 0）: ', COUNT(*)) AS check_batches
  FROM oms.oms_export_batches b
  JOIN tms.tms_shipments s
    ON s.tenant_id = b.tenant_id AND s.batch_no = b.batch_no
 WHERE b.tenant_id = 'alibaba' AND s.etd_at IS NOT NULL
   AND b.etd_date <> DATE(s.etd_at + INTERVAL 8 HOUR);