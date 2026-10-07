-- =============================================================================
-- 49: 国际跨境物流业务数据种子（母单 / 子单 / 明细 / 运单 / 箱 / 轨迹 / 报关 /
--     备货任务 / 库存批次 / 在途 / 异常工单）
--
-- 规模：12 母单 / 43 子单 / 71 明细 / 12 运单 / 44 箱 / 55 轨迹节点 / 8 报关单 /
--       18 库存批次 / 43 备货任务 / 71 任务明细 / 66 在途行 / 9 异常工单。
--       （与计划 §10 的估算略有出入：计划按「每子单 2~3 明细、48 任务、320 在途」
--        估的，本脚本按实际内容收敛——在途改成**按订单聚合**而不是按件展开，
--        320 行在演示里没有额外信息量，行数减半换来的是这四个库都还在 1 秒内响应。）
--
-- 全部 DATETIME 字面量按 **UTC** 写：'2026-10-14 01:00:00' = 北京 10-14 09:00。
-- DATE 字面量（etd_date/eta_date/expiry_date）是当地日期，不换算。
--
-- 【重要】本脚本里**跨库 JOIN**（oms → crm.customers、wms → oms/tms）：
--   迁移脚本由 DBA/运维在 MySQL 上一次性执行，同实例跨库 JOIN 是允许的；
--   而**应用代码一行都不跨库**（跨库一律走 LinkageClient）。
--   用 JOIN 而不是把同一份数据抄两遍，是为了避免「子单明细改了、在途行没跟着改」这种漂移。
--
-- 幂等：
--   · 子单/母单/运单/渠道商有唯一键 → ON DUPLICATE KEY UPDATE；
--   · 明细/箱/轨迹/在途没有唯一键 → 「WHERE NOT EXISTS 按业务键判存在」；
--   · 备货任务 task_no 由 order_no 派生，天然唯一；
--   · 库存批次 uk_wh_sku_batch、报关单 uk_decl_no、工单 uk_ticket_no 走唯一键。
-- 连跑 3 遍行数不变（验收清单第 5 条）。
-- =============================================================================

-- =============================================================================
-- 一、OMS：出口母单（12 条）
-- route_id / carrier_id 用子查询按业务码取，不写死自增 id。
-- =============================================================================
USE oms;

INSERT INTO oms_export_batches
    (tenant_id, batch_no, mode, pol_code, pol_name, pod_code, pod_name, route_id, carrier_id,
     container_type, container_qty, incoterm, currency, etd_date, eta_date, cutoff_at,
     status, remark, created_by)
SELECT 'alibaba', v.batch_no, v.mode, v.pol_code, v.pol_name, v.pod_code, v.pod_name,
       r.id, c.id, v.container_type, v.container_qty, 'FOB', 'USD',
       v.etd_date, v.eta_date, v.cutoff_at, v.status, v.remark, 1
  FROM (
        SELECT 'MEXP20261010001' AS batch_no, 'SEA' AS mode, 'CNSHA' AS pol_code, '上海' AS pol_name, 'USLAX' AS pod_code, '洛杉矶' AS pod_name, 'CNSHA-USLAX' AS route_code, 'COSCO' AS carrier_code, '40HQ' AS container_type, 2 AS container_qty, '2026-10-14' AS etd_date, '2026-10-30' AS eta_date, '2026-10-12 09:00:00' AS cutoff_at, 'IN_TRANSIT' AS status, '美西整柜（含锂电池）' AS remark
        UNION ALL SELECT 'MEXP20261010002', 'SEA', 'CNSHA', '上海', 'USNYC', '纽约', 'CNSHA-USNYC', 'MAEU', '40GP', 1, '2026-10-09', '2026-10-27', '2026-10-07 14:00:00', 'CLEARING', '目的港清关中'
        UNION ALL SELECT 'MEXP20261010003', 'AIR', 'PVG', '上海浦东', 'ORD', '芝加哥', 'PVG-ORD', 'EK', '', 0, '2026-10-11', '2026-10-14', '2026-10-11 04:00:00', 'DEPARTED', '空运快线，6 张子单合运'
        UNION ALL SELECT 'MEXP20261010004', 'SEA', 'CNNGB', '宁波', 'USDAL', '达拉斯', 'CNNGB-USDAL', 'ONE', '20GP', 1, '2026-10-17', '2026-11-06', '2026-10-14 15:00:00', 'BOOKED', '已订舱待揽收'
        UNION ALL SELECT 'MEXP20261010005', 'SEA', 'CNSZX', '深圳', 'DEHAM', '汉堡', 'CNSZX-DEHAM', 'MSC', '40HQ', 1, '2026-10-21', '2026-11-20', '2026-10-19 10:00:00', 'BOOKING', '欧线订舱中，S/O 未回'
        UNION ALL SELECT 'MEXP20261010006', 'EXPRESS', 'CNSHA', '上海', 'USLAX', '洛杉矶', 'CNSHA-USLAX-EXP', 'UPS', '', 0, '2026-10-08', '2026-10-13', '2026-10-08 04:00:00', 'CLEARED', '快递件已清关待派送'
        UNION ALL SELECT 'MEXP20261010007', 'SEA', 'CNTAU', '青岛', 'USLAX', '洛杉矶', 'CNTAU-USLAX', 'CMA', '40HQ', 1, '2026-10-06', '2026-10-23', '2026-10-04 13:00:00', 'LAST_MILE', '尾程派送中'
        UNION ALL SELECT 'MEXP20261010008', 'AIR', 'CAN', '广州', 'AMS', '阿姆斯特丹', 'CAN-AMS', 'CZ', '', 0, '2026-10-13', '2026-10-17', '2026-10-12 06:00:00', 'IN_TRANSIT', '空运在途'
        UNION ALL SELECT 'MEXP20261010009', 'SEA', 'CNSHA', '上海', 'USLAX', '洛杉矶', 'CNSHA-USLAX', 'COSCO', '40HQ', 1, '2026-10-12', '2026-10-28', '2026-10-10 09:00:00', 'DEPARTED', '已开航'
        UNION ALL SELECT 'MEXP20261010010', 'LCL', 'CNSHA', '上海', 'USLAX', '洛杉矶', 'CNSHA-USLAX', 'SEAWAY', 'LCL', 1, '2026-10-19', '2026-11-08', '2026-10-17 15:00:00', 'PICKING', '拼箱备货中，运单还没建'
        UNION ALL SELECT 'MEXP20261010011', 'SEA', 'CNSHA', '上海', 'USLAX', '洛杉矶', 'CNSHA-USLAX', 'COSCO', '40HQ', 1, '2026-10-15', '2026-10-31', '2026-10-13 09:00:00', 'EXCEPTION', '整批查验，异常挂起'
        UNION ALL SELECT 'MEXP20261010012', 'EXPRESS', 'CNSHA', '上海', 'USLAX', '洛杉矶', 'CNSHA-USLAX-EXP', 'UPS', '', 0, '2026-10-05', '2026-10-10', '2026-10-05 04:00:00', 'DELIVERED', '已妥投'
       ) v
  LEFT JOIN tms.tms_routes r ON r.tenant_id = 'alibaba' AND r.route_code = v.route_code
  LEFT JOIN tms.tms_carriers c ON c.tenant_id = 'alibaba' AND c.code = v.carrier_code
ON DUPLICATE KEY UPDATE
    mode = VALUES(mode), container_type = VALUES(container_type), container_qty = VALUES(container_qty),
    etd_date = VALUES(etd_date), eta_date = VALUES(eta_date), cutoff_at = VALUES(cutoff_at),
    status = VALUES(status);

-- =============================================================================
-- 二、OMS：出口子单（43 条）
-- customer_id 跨库弱引用 CRM customers.id，同时冗余 customer_name 快照
-- （对方删了客户，历史单据仍能显示「某某客户」而不是空白）。
-- 子单状态由母单状态派生，**允许落后不得领先**——同柜不同单可能已清关，
-- 未清关的单还在清关中，这是真实场景而不是数据错误。
-- =============================================================================
INSERT INTO oms_export_orders
    (tenant_id, order_no, batch_no, is_batch_locked, customer_id, customer_name,
     consignee_name, consignee_company, consignee_country, consignee_address,
     consignee_phone, consignee_email, incoterm, currency, total_amount, freight_amount,
     total_qty, total_weight, total_volume, volumetric_weight,
     etd_date, eta_date, ready_date, status, remark, created_by)
SELECT 'alibaba', v.order_no, v.batch_no, 1, c.id, c.name,
       v.consignee_name, v.consignee_company, v.consignee_country, v.consignee_address,
       v.consignee_phone, v.consignee_email, v.incoterm, v.currency, 0, v.freight_amount,
       v.total_qty, 0, 0, 0,
       v.etd_date, v.eta_date, v.ready_date, v.status, v.remark, 1
  FROM (
        SELECT 'EXP20261010001' AS order_no, 'MEXP20261010001' AS batch_no, '深圳前海优选跨境电商有限公司' AS customer, 'Jordan Ellis' AS consignee_name, 'Ellis Trading LLC' AS consignee_company, 'US' AS consignee_country, '1201 S Santa Fe Ave, Los Angeles, CA 90021' AS consignee_address, '+1-213-555-0142' AS consignee_phone, 'user41@example.com' AS consignee_email, 'FOB' AS incoterm, 'USD' AS currency, 4256.00 AS freight_amount, 90 AS total_qty, '2026-10-14' AS etd_date, '2026-10-30' AS eta_date, '2026-10-12' AS ready_date, 'IN_TRANSIT' AS status, '含锂电池，需 MSDS 与危包证' AS remark
        UNION ALL SELECT 'EXP20261010002', 'MEXP20261010001', '深圳前海优选跨境电商有限公司', 'Morgan Chen', 'Pacific Devices Inc.', 'US', '88 Bluxome St, New York, NY 10012', '+1-646-555-0198', 'user51@example.com', 'CIF', 'USD', 4256.00, 50, '2026-10-14', '2026-10-30', '2026-10-12', 'IN_TRANSIT', ''
        UNION ALL SELECT 'EXP20261010003', 'MEXP20261010001', '深圳前海优选跨境电商有限公司', 'Ava Brooks', 'Ellis Trading LLC', 'US', '1201 S Santa Fe Ave, Los Angeles, CA 90021', '+1-213-555-0142', 'user07@example.com', 'FOB', 'USD', 4256.00, 84, '2026-10-14', '2026-10-30', '2026-10-12', 'IN_TRANSIT', ''
        UNION ALL SELECT 'EXP20261010004', 'MEXP20261010001', '深圳前海优选跨境电商有限公司', 'Ethan Reed', 'Pacific Devices Inc.', 'US', '1 Ferry Building, San Francisco, CA 94111', '+1-415-555-0117', 'user24@example.com', 'FOB', 'USD', 4256.00, 110, '2026-10-14', '2026-10-30', '2026-10-12', 'IN_TRANSIT', ''
        UNION ALL SELECT 'EXP20261010005', 'MEXP20261010001', '深圳前海优选跨境电商有限公司', 'Olivia Park', 'Ellis Trading LLC', 'US', '55 W 21st St, New York, NY 10010', '+1-212-555-0163', 'user55@example.com', 'FOB', 'USD', 4256.00, 76, '2026-10-14', '2026-10-30', '2026-10-12', 'IN_TRANSIT', '加急，需优先卸柜'
        UNION ALL SELECT 'EXP20261010006', 'MEXP20261010002', '义乌市小商品城跨境卖家联盟', 'Liam Wang', 'Harbor Goods Inc.', 'US', '10 Jay St, Brooklyn, NY 11201', '+1-718-555-0139', 'user42@example.com', 'FOB', 'USD', 2100.00, 350, '2026-10-09', '2026-10-27', '2026-10-07', 'ARRIVED', '目的港查验待回复'
        UNION ALL SELECT 'EXP20261010007', 'MEXP20261010002', '义乌市小商品城跨境卖家联盟', 'Sophia Liu', 'Harbor Goods Inc.', 'US', '10 Jay St, Brooklyn, NY 11201', '+1-718-555-0139', 'user67@example.com', 'FOB', 'USD', 2100.00, 500, '2026-10-09', '2026-10-27', '2026-10-07', 'ARRIVED', ''
        UNION ALL SELECT 'EXP20261010008', 'MEXP20261010002', '义乌市小商品城跨境卖家联盟', 'Noah Garcia', 'Midtown Retail LLC', 'US', '350 5th Ave, New York, NY 10118', '+1-646-555-0102', 'user54@example.com', 'DDP', 'USD', 2100.00, 230, '2026-10-09', '2026-10-27', '2026-10-07', 'ARRIVED', 'DDP，需我们付关税'
        UNION ALL SELECT 'EXP20261010009', 'MEXP20261010002', '义乌市小商品城跨境卖家联盟', 'Emma Wilson', 'Harbor Goods Inc.', 'US', '10 Jay St, Brooklyn, NY 11201', '+1-718-555-0139', 'user23@example.com', 'FOB', 'USD', 2100.00, 380, '2026-10-09', '2026-10-27', '2026-10-07', 'ARRIVED', ''
        UNION ALL SELECT 'EXP20261010010', 'MEXP20261010003', '深圳前海优选跨境电商有限公司', 'Lucas Brown', 'Lakeshore Distribution', 'US', '2250 W Fulton St, Chicago, IL 60612', '+1-312-555-0188', 'user45@example.com', 'FOB', 'USD', 980.00, 10, '2026-10-11', '2026-10-14', '2026-10-10', 'SHIPPED', '空运加急'
        UNION ALL SELECT 'EXP20261010011', 'MEXP20261010003', '深圳前海优选跨境电商有限公司', 'Mia Davis', 'Lakeshore Distribution', 'US', '2250 W Fulton St, Chicago, IL 60612', '+1-312-555-0188', 'user48@example.com', 'FOB', 'USD', 980.00, 22, '2026-10-11', '2026-10-14', '2026-10-10', 'SHIPPED', ''
        UNION ALL SELECT 'EXP20261010012', 'MEXP20261010003', '深圳前海优选跨境电商有限公司', 'James Miller', 'Windy City Trade', 'US', '401 N Morgan St, Chicago, IL 60642', '+1-312-555-0155', 'user38@example.com', 'FOB', 'USD', 980.00, 15, '2026-10-11', '2026-10-14', '2026-10-10', 'SHIPPED', ''
        UNION ALL SELECT 'EXP20261010013', 'MEXP20261010003', '深圳前海优选跨境电商有限公司', 'Isabella Moore', 'Lakeshore Distribution', 'US', '2250 W Fulton St, Chicago, IL 60612', '+1-312-555-0188', 'user36@example.com', 'FOB', 'USD', 980.00, 48, '2026-10-11', '2026-10-14', '2026-10-10', 'SHIPPED', ''
        UNION ALL SELECT 'EXP20261010014', 'MEXP20261010003', '深圳前海优选跨境电商有限公司', 'Benjamin Taylor', 'Windy City Trade', 'US', '401 N Morgan St, Chicago, IL 60642', '+1-312-555-0155', 'user09@example.com', 'FOB', 'USD', 980.00, 80, '2026-10-11', '2026-10-14', '2026-10-10', 'SHIPPED', ''
        UNION ALL SELECT 'EXP20261010015', 'MEXP20261010003', '深圳前海优选跨境电商有限公司', 'Amelia Thomas', 'Lakeshore Distribution', 'US', '2250 W Fulton St, Chicago, IL 60612', '+1-312-555-0188', 'user04@example.com', 'FOB', 'USD', 980.00, 100, '2026-10-11', '2026-10-14', '2026-10-10', 'SHIPPED', '移动电源，需危包证'
        UNION ALL SELECT 'EXP20261010016', 'MEXP20261010004', '广州希悦服饰出口有限公司', 'Daniel Jackson', 'Sunshine Apparel NY', 'US', '32 W 40th St, New York, NY 10036', '+1-212-555-0148', 'user18@example.com', 'FOB', 'USD', 1450.00, 600, '2026-10-17', '2026-11-06', '2026-10-15', 'PACKED', ''
        UNION ALL SELECT 'EXP20261010017', 'MEXP20261010004', '广州希悦服饰出口有限公司', 'Harper White', 'Sunshine Apparel NY', 'US', '32 W 40th St, New York, NY 10036', '+1-212-555-0148', 'user29@example.com', 'FOB', 'USD', 1450.00, 650, '2026-10-17', '2026-11-06', '2026-10-15', 'PACKED', ''
        UNION ALL SELECT 'EXP20261010018', 'MEXP20261010004', '广州希悦服饰出口有限公司', 'Henry Harris', 'Dallas Fashion Co.', 'US', '1401 Elm St, Dallas, TX 75202', '+1-214-555-0176', 'user30@example.com', 'FOB', 'USD', 1450.00, 80, '2026-10-17', '2026-11-06', '2026-10-15', 'PACKED', ''
        UNION ALL SELECT 'EXP20261010019', 'MEXP20261010005', '杭州星辰户外用品有限公司', 'Alexander Clark', 'Rhein Retail GmbH', 'DE', 'Königsallee 47, 40212 Düsseldorf', '+49-211-555-0134', 'user03@example.com', 'FOB', 'USD', 2680.00, 30, '2026-10-21', '2026-11-20', '2026-10-19', 'PACKED', ''
        UNION ALL SELECT 'EXP20261010020', 'MEXP20261010005', '杭州星辰户外用品有限公司', 'Evelyn Lewis', 'Hamburg Outdoor KG', 'DE', 'Ballindamm 25, 2013 Hamburg', '+49-40-555-0167', 'user25@example.com', 'FOB', 'USD', 2680.00, 175, '2026-10-21', '2026-11-20', '2026-10-19', 'PACKED', ''
        UNION ALL SELECT 'EXP20261010021', 'MEXP20261010005', '杭州星辰户外用品有限公司', 'Michael Walker', 'Rhein Retail GmbH', 'DE', 'Königsallee 47, 40212 Düsseldorf', '+49-211-555-0134', 'user49@example.com', 'FOB', 'USD', 2680.00, 90, '2026-10-21', '2026-11-20', '2026-10-19', 'PACKED', ''
        UNION ALL SELECT 'EXP20261010022', 'MEXP20261010006', '义乌市小商品城跨境卖家联盟', 'Charlotte Hall', 'Westside Online', 'US', '10880 Wilshire Blvd, Los Angeles, CA 90024', '+1-310-555-0192', 'user10@example.com', 'DDP', 'USD', 168.00, 800, '2026-10-08', '2026-10-13', '2026-10-07', 'CUSTOMS_CLEARING', ''
        UNION ALL SELECT 'EXP20261010023', 'MEXP20261010006', '义乌市小商品城跨境卖家联盟', 'Benjamin Young', 'Westside Online', 'US', '10880 Wilshire Blvd, Los Angeles, CA 90024', '+1-310-555-0192', 'user08@example.com', 'DDP', 'USD', 168.00, 1000, '2026-10-08', '2026-10-13', '2026-10-07', 'CUSTOMS_CLEARING', '马克杯易碎，已贴 FRAGILE'
        UNION ALL SELECT 'EXP20261010024', 'MEXP20261010006', '义乌市小商品城跨境卖家联盟', 'Abigail King', 'Pacific Bazaar', 'US', '1 Harbor Plaza, Long Beach, CA 90802', '+1-562-555-0118', 'user01@example.com', 'DDP', 'USD', 168.00, 600, '2026-10-08', '2026-10-13', '2026-10-07', 'CUSTOMS_CLEARING', ''
        UNION ALL SELECT 'EXP20261010025', 'MEXP20261010006', '义乌市小商品城跨境卖家联盟', 'William Wright', 'Westside Online', 'US', '10880 Wilshire Blvd, Los Angeles, CA 90024', '+1-310-555-0192', 'user74@example.com', 'DDP', 'USD', 168.00, 550, '2026-10-08', '2026-10-13', '2026-10-07', 'CUSTOMS_CLEARING', ''
        UNION ALL SELECT 'EXP20261010026', 'MEXP20261010007', '宁波甬佳家居用品有限公司', 'Sofia Scott', 'Bay Area Home LLC', 'US', '2150 Harrison St, San Francisco, CA 94110', '+1-415-555-0161', 'user66@example.com', 'FOB', 'USD', 2100.00, 540, '2026-10-06', '2026-10-23', '2026-10-05', 'CUSTOMS_CLEARED', ''
        UNION ALL SELECT 'EXP20261010027', 'MEXP20261010007', '宁波甬佳家居用品有限公司', 'Jack Green', 'Bay Area Home LLC', 'US', '2150 Harrison St, San Francisco, CA 94110', '+1-415-555-0161', 'user37@example.com', 'FOB', 'USD', 2100.00, 35, '2026-10-06', '2026-10-23', '2026-10-05', 'CUSTOMS_CLEARED', ''
        UNION ALL SELECT 'EXP20261010028', 'MEXP20261010007', '宁波甬佳家居用品有限公司', 'Emily Adams', 'SGV Furnishing', 'US', '901 S Broadway, Los Angeles, CA 90015', '+1-213-555-0129', 'user22@example.com', 'FOB', 'USD', 2100.00, 440, '2026-10-06', '2026-10-23', '2026-10-05', 'CUSTOMS_CLEARED', ''
        UNION ALL SELECT 'EXP20261010029', 'MEXP20261010008', '上海速达国际货运代理有限公司', 'Ryan Baker', 'Amsterdam Trading BV', 'NL', 'Keizersgracht 241, 1016 EA Amsterdam', '+31-20-555-0143', 'user60@example.com', 'FOB', 'USD', 1120.00, 55, '2026-10-13', '2026-10-17', '2026-10-12', 'IN_TRANSIT', '同业货代，走我们的舱位'
        UNION ALL SELECT 'EXP20261010030', 'MEXP20261010008', '上海速达国际货运代理有限公司', 'Chloe Nelson', 'Amsterdam Trading BV', 'NL', 'Keizersgracht 241, 1016 EA Amsterdam', '+31-20-555-0143', 'user11@example.com', 'FOB', 'USD', 1120.00, 6, '2026-10-13', '2026-10-17', '2026-10-12', 'IN_TRANSIT', ''
        UNION ALL SELECT 'EXP20261010031', 'MEXP20261010008', '上海速达国际货运代理有限公司', 'Nathan Carter', 'Rotterdam Logistics', 'NL', 'Wilhelminakade 909, 3072 AP Rotterdam', '+31-10-555-0178', 'user53@example.com', 'FOB', 'USD', 1120.00, 88, '2026-10-13', '2026-10-17', '2026-10-12', 'IN_TRANSIT', ''
        UNION ALL SELECT 'EXP20261010032', 'MEXP20261010008', '上海速达国际货运代理有限公司', 'Grace Mitchell', 'Amsterdam Trading BV', 'NL', 'Keizersgracht 241, 1016 EA Amsterdam', '+31-20-555-0143', 'user27@example.com', 'FOB', 'USD', 1120.00, 60, '2026-10-13', '2026-10-17', '2026-10-12', 'IN_TRANSIT', ''
        UNION ALL SELECT 'EXP20261010033', 'MEXP20261010009', '3C Global Trading Co., Ltd.', 'Daniel Roberts', '3C Global Trading Co., Ltd.', 'US', '1201 S Santa Fe Ave, Los Angeles, CA 90021', '+1-213-555-0102', 'user57@example.com', 'FOB', 'USD', 1800.00, 45, '2026-10-12', '2026-10-28', '2026-10-10', 'SHIPPED', '境外买家自营账号'
        UNION ALL SELECT 'EXP20261010034', 'MEXP20261010009', '3C Global Trading Co., Ltd.', 'Zoey Turner', '3C Global Trading Co., Ltd.', 'US', '1201 S Santa Fe Ave, Los Angeles, CA 90021', '+1-213-555-0102', 'user80@example.com', 'FOB', 'USD', 1800.00, 25, '2026-10-12', '2026-10-28', '2026-10-10', 'SHIPPED', ''
        UNION ALL SELECT 'EXP20261010035', 'MEXP20261010009', '3C Global Trading Co., Ltd.', 'Tyler Phillips', '3C Global Trading Co., Ltd.', 'US', '1201 S Santa Fe Ave, Los Angeles, CA 90021', '+1-213-555-0102', 'user71@example.com', 'FOB', 'USD', 1800.00, 91, '2026-10-12', '2026-10-28', '2026-10-10', 'SHIPPED', ''
        UNION ALL SELECT 'EXP20261010036', 'MEXP20261010010', '深圳前海优选跨境电商有限公司', 'Hannah Evans', 'Ellis Trading LLC', 'US', '1201 S Santa Fe Ave, Los Angeles, CA 90021', '+1-213-555-0142', 'user28@example.com', 'FOB', 'USD', 480.00, 65, '2026-10-19', '2026-11-08', '2026-10-17', 'PICKING', '拼箱，备货中'
        UNION ALL SELECT 'EXP20261010037', 'MEXP20261010010', '深圳前海优选跨境电商有限公司', 'Austin Collins', 'Ellis Trading LLC', 'US', '1201 S Santa Fe Ave, Los Angeles, CA 90021', '+1-213-555-0142', 'user06@example.com', 'FOB', 'USD', 480.00, 40, '2026-10-19', '2026-11-08', '2026-10-17', 'PICKING', ''
        UNION ALL SELECT 'EXP20261010038', 'MEXP20261010010', '深圳前海优选跨境电商有限公司', 'Madison Stewart', 'Ellis Trading LLC', 'US', '1201 S Santa Fe Ave, Los Angeles, CA 90021', '+1-213-555-0142', 'user46@example.com', 'FOB', 'USD', 480.00, 45, '2026-10-19', '2026-11-08', '2026-10-17', 'PICKING', ''
        UNION ALL SELECT 'EXP20261010039', 'MEXP20261010010', '深圳前海优选跨境电商有限公司', 'Sebastian Morris', 'Ellis Trading LLC', 'US', '1201 S Santa Fe Ave, Los Angeles, CA 90021', '+1-213-555-0142', 'user64@example.com', 'FOB', 'USD', 480.00, 4, '2026-10-19', '2026-11-08', '2026-10-17', 'PICKING', '库存仅 4 件'
        UNION ALL SELECT 'EXP20261010040', 'MEXP20261010011', '深圳鸿鹄供应链管理有限公司', 'Liam Murphy', 'Redwood Supply', 'US', '700 Binford St, Los Angeles, CA 90021', '+1-310-555-0177', 'user43@example.com', 'FOB', 'USD', 2100.00, 500, '2026-10-15', '2026-10-31', '2026-10-13', 'EXCEPTION', '整批查验，暂扣在洋山港'
        UNION ALL SELECT 'EXP20261010041', 'MEXP20261010011', '深圳鸿鹄供应链管理有限公司', 'Aria Rogers', 'Redwood Supply', 'US', '700 Binford St, Los Angeles, CA 90021', '+1-310-555-0177', 'user05@example.com', 'FOB', 'USD', 2100.00, 600, '2026-10-15', '2026-10-31', '2026-10-13', 'EXCEPTION', '查验中'
        UNION ALL SELECT 'EXP20261010042', 'MEXP20261010012', '3C Global Trading Co., Ltd.', 'Joseph Cook', '3C Global Trading Co., Ltd.', 'US', '1201 S Santa Fe Ave, Los Angeles, CA 90021', '+1-213-555-0102', 'user39@example.com', 'DDP', 'USD', 120.00, 900, '2026-10-05', '2026-10-10', '2026-10-04', 'DELIVERED', '已签收'
        UNION ALL SELECT 'EXP20261010043', 'MEXP20261010012', '3C Global Trading Co., Ltd.', 'Ella Perez', '3C Global Trading Co., Ltd.', 'US', '1201 S Santa Fe Ave, Los Angeles, CA 90021', '+1-213-555-0102', 'user21@example.com', 'DDP', 'USD', 120.00, 970, '2026-10-05', '2026-10-10', '2026-10-04', 'DELIVERED', ''
       ) v
  JOIN crm.customers c ON c.tenant_id = 'alibaba' AND c.name = v.customer
ON DUPLICATE KEY UPDATE
    batch_no = VALUES(batch_no), is_batch_locked = VALUES(is_batch_locked),
    status = VALUES(status), customer_name = VALUES(customer_name);

-- =============================================================================
-- 三、OMS：出口单明细（71 行）
-- 申报要素（HS 编码/报关品名/危险品）从 oms_products **按 sku 取**，
-- 不在这里重抄一遍：抄两遍就会出现「商品档案改了、已报关的单子没跟着改」的漂移。
-- declared_value = unit_price × quantity（在 SQL 里算，避免与单价不自洽）。
-- =============================================================================
INSERT INTO oms_export_order_items
    (tenant_id, order_id, order_no, line_no, product_id, sku, product_name,
     customs_name, customs_name_en, hs_code, origin_country, quantity, unit_price, declared_value,
     net_weight, gross_weight, volume, is_dangerous, un_number, dg_class,
     packing_instruction, battery_watt_hours, remark)
SELECT 'alibaba', o.id, o.order_no, v.line_no, p.id, v.sku, p.name,
       p.customs_name, p.customs_name_en, p.hs_code, p.origin_country, v.quantity, v.unit_price,
       ROUND(v.unit_price * v.quantity, 2),
       p.net_weight, p.gross_weight, p.volume_cbm, p.is_dangerous, p.un_number, p.dg_class,
       p.packing_instruction, p.battery_watt_hours, ''
  FROM (
        SELECT 'EXP20261010001' AS order_no, 1 AS line_no, 'IPH-15-BLK' AS sku, 40 AS quantity, 7999.00 AS unit_price
        UNION ALL SELECT 'EXP20261010001', 2, 'AIRP-2-WHT', 30, 1899.00
        UNION ALL SELECT 'EXP20261010001', 3, 'POWER-100W', 20, 299.00
        UNION ALL SELECT 'EXP20261010002', 1, 'IPH-15-BLU', 30, 7999.00
        UNION ALL SELECT 'EXP20261010002', 2, 'WATCH-S9-BLK', 20, 2999.00
        UNION ALL SELECT 'EXP20261010003', 1, 'MBP-14-SLV', 5, 14999.00
        UNION ALL SELECT 'EXP20261010003', 2, 'IPAD-AIR-64G', 10, 4799.00
        UNION ALL SELECT 'EXP20261010004', 1, 'IPH-15-BLK', 50, 7999.00
        UNION ALL SELECT 'EXP20261010005', 1, 'AIRP-2-WHT', 60, 1899.00
        UNION ALL SELECT 'EXP20261010005', 2, 'WATCH-S9-BLK', 24, 2999.00
        UNION ALL SELECT 'EXP20261010006', 1, 'TEE-COT-M-WHT', 200, 89.00
        UNION ALL SELECT 'EXP20261010006', 2, 'TEE-COT-M-BLU', 150, 89.00
        UNION ALL SELECT 'EXP20261010007', 1, 'DENIM-JKT-L', 60, 399.00
        UNION ALL SELECT 'EXP20261010007', 2, 'CERAMIC-MUG', 120, 59.00
        UNION ALL SELECT 'EXP20261010008', 1, 'CHAIR-ERGO', 20, 1299.00
        UNION ALL SELECT 'EXP20261010009', 1, 'IPH-15-BLK', 60, 7999.00
        UNION ALL SELECT 'EXP20261010009', 2, 'AIRP-2-WHT', 40, 1899.00
        UNION ALL SELECT 'EXP20261010010', 1, 'MBP-14-SLV', 5, 14999.00
        UNION ALL SELECT 'EXP20261010010', 2, 'IPAD-AIR-64G', 5, 4799.00
        UNION ALL SELECT 'EXP20261010011', 1, 'WATCH-S9-BLK', 22, 2999.00
        UNION ALL SELECT 'EXP20261010012', 1, 'POWER-100W', 80, 299.00
        UNION ALL SELECT 'EXP20261010013', 1, 'IPH-15-BLU', 40, 7999.00
        UNION ALL SELECT 'EXP20261010013', 2, 'MBP-14-SLV', 8, 14999.00
        UNION ALL SELECT 'EXP20261010014', 1, 'WATCH-S9-BLK', 30, 2999.00
        UNION ALL SELECT 'EXP20261010014', 2, 'AIRP-2-WHT', 50, 1899.00
        UNION ALL SELECT 'EXP20261010015', 1, 'POWER-100W', 80, 299.00
        UNION ALL SELECT 'EXP20261010015', 2, 'IPAD-AIR-64G', 20, 4799.00
        UNION ALL SELECT 'EXP20261010016', 1, 'TEE-COT-M-WHT', 500, 89.00
        UNION ALL SELECT 'EXP20261010016', 2, 'DENIM-JKT-L', 100, 399.00
        UNION ALL SELECT 'EXP20261010017', 1, 'TEE-COT-M-BLU', 450, 89.00
        UNION ALL SELECT 'EXP20261010017', 2, 'CERAMIC-MUG', 200, 59.00
        UNION ALL SELECT 'EXP20261010018', 1, 'DENIM-JKT-L', 80, 399.00
        UNION ALL SELECT 'EXP20261010019', 1, 'CHAIR-ERGO', 30, 1299.00
        UNION ALL SELECT 'EXP20261010020', 1, 'CHAIR-ERGO', 25, 1299.00
        UNION ALL SELECT 'EXP20261010020', 2, 'CERAMIC-MUG', 150, 59.00
        UNION ALL SELECT 'EXP20261010021', 1, 'DENIM-JKT-L', 90, 399.00
        UNION ALL SELECT 'EXP20261010022', 1, 'TEE-COT-M-WHT', 800, 89.00
        UNION ALL SELECT 'EXP20261010023', 1, 'TEE-COT-M-BLU', 700, 89.00
        UNION ALL SELECT 'EXP20261010023', 2, 'CERAMIC-MUG', 300, 59.00
        UNION ALL SELECT 'EXP20261010024', 1, 'TEE-COT-M-WHT', 600, 89.00
        UNION ALL SELECT 'EXP20261010025', 1, 'DENIM-JKT-L', 150, 399.00
        UNION ALL SELECT 'EXP20261010025', 2, 'TEE-COT-M-BLU', 400, 89.00
        UNION ALL SELECT 'EXP20261010026', 1, 'CHAIR-ERGO', 40, 1299.00
        UNION ALL SELECT 'EXP20261010026', 2, 'CERAMIC-MUG', 500, 59.00
        UNION ALL SELECT 'EXP20261010027', 1, 'CHAIR-ERGO', 35, 1299.00
        UNION ALL SELECT 'EXP20261010028', 1, 'DENIM-JKT-L', 180, 399.00
        UNION ALL SELECT 'EXP20261010028', 2, 'CERAMIC-MUG', 260, 59.00
        UNION ALL SELECT 'EXP20261010029', 1, 'IPAD-AIR-64G', 30, 4799.00
        UNION ALL SELECT 'EXP20261010029', 2, 'WATCH-S9-BLK', 25, 2999.00
        UNION ALL SELECT 'EXP20261010030', 1, 'MBP-14-SLV', 6, 14999.00
        UNION ALL SELECT 'EXP20261010031', 1, 'AIRP-2-WHT', 70, 1899.00
        UNION ALL SELECT 'EXP20261010031', 2, 'IPH-15-BLK', 18, 7999.00
        UNION ALL SELECT 'EXP20261010032', 1, 'POWER-100W', 60, 299.00
        UNION ALL SELECT 'EXP20261010033', 1, 'IPH-15-BLK', 45, 7999.00
        UNION ALL SELECT 'EXP20261010034', 1, 'MBP-14-SLV', 10, 14999.00
        UNION ALL SELECT 'EXP20261010034', 2, 'IPAD-AIR-64G', 15, 4799.00
        UNION ALL SELECT 'EXP20261010035', 1, 'WATCH-S9-BLK', 36, 2999.00
        UNION ALL SELECT 'EXP20261010035', 2, 'AIRP-2-WHT', 55, 1899.00
        UNION ALL SELECT 'EXP20261010036', 1, 'IPH-15-BLK', 30, 7999.00
        UNION ALL SELECT 'EXP20261010036', 2, 'AIRP-2-WHT', 35, 1899.00
        UNION ALL SELECT 'EXP20261010037', 1, 'IPAD-AIR-64G', 18, 4799.00
        UNION ALL SELECT 'EXP20261010037', 2, 'WATCH-S9-BLK', 22, 2999.00
        UNION ALL SELECT 'EXP20261010038', 1, 'POWER-100W', 45, 299.00
        UNION ALL SELECT 'EXP20261010039', 1, 'MBP-14-SLV', 4, 14999.00
        UNION ALL SELECT 'EXP20261010040', 1, 'TEE-COT-M-WHT', 500, 89.00
        UNION ALL SELECT 'EXP20261010041', 1, 'TEE-COT-M-BLU', 420, 89.00
        UNION ALL SELECT 'EXP20261010041', 2, 'CERAMIC-MUG', 180, 59.00
        UNION ALL SELECT 'EXP20261010042', 1, 'TEE-COT-M-WHT', 900, 89.00
        UNION ALL SELECT 'EXP20261010043', 1, 'DENIM-JKT-L', 220, 399.00
        UNION ALL SELECT 'EXP20261010043', 2, 'TEE-COT-M-BLU', 750, 89.00
       ) v
  JOIN oms_export_orders o ON o.tenant_id = 'alibaba' AND o.order_no = v.order_no
  JOIN oms_products p ON p.tenant_id = 'alibaba' AND p.sku = v.sku
 WHERE NOT EXISTS (
       SELECT 1 FROM oms_export_order_items i
        WHERE i.order_id = o.id AND i.line_no = v.line_no);

-- 子单的汇总列（金额/件数/重量/体积）在明细落库后从明细回算一次。
-- 口径写在同一处，免得「建单时算一次、脚本算一次」两个口径。
UPDATE oms_export_orders o
  JOIN (SELECT order_id,
               SUM(quantity)                              AS qty,
               SUM(unit_price * quantity)                 AS amount,
               SUM(gross_weight * quantity)               AS weight,
               SUM(volume * quantity)                     AS volume,
               SUM(volume * quantity * 6000)              AS vol_weight
          FROM oms_export_order_items
         WHERE tenant_id = 'alibaba'
         GROUP BY order_id) s ON s.order_id = o.id
   SET o.total_qty    = s.qty,
       o.total_amount = ROUND(s.amount, 2),
       o.total_weight = ROUND(s.weight, 3),
       o.total_volume = ROUND(s.volume, 4),
       o.volumetric_weight = ROUND(s.vol_weight, 3)
 WHERE o.tenant_id = 'alibaba' AND o.order_no LIKE 'EXP%';

-- 母单汇总列同样从子单回算（拼批后母单的件数必须等于子单之和）。
UPDATE oms_export_batches b
  JOIN (SELECT batch_no,
               SUM(total_qty)            AS qty,
               SUM(total_weight)         AS weight,
               SUM(total_volume)         AS volume,
               SUM(volumetric_weight)    AS vol_weight
          FROM oms_export_orders
         WHERE tenant_id = 'alibaba' AND batch_no <> ''
         GROUP BY batch_no) s ON s.batch_no = b.batch_no
   SET b.total_qty = s.qty,
       b.total_weight = ROUND(s.weight, 3),
       b.total_volume = ROUND(s.volume, 3),
       b.volumetric_weight = ROUND(s.vol_weight, 3)
 WHERE b.tenant_id = 'alibaba';

-- =============================================================================
-- 四、TMS：国际运单（12 条，与 12 个母单 1:1）
-- 承运商按 code 取 id；箱量/单证号按 mode 给——
--   海运必有 BL（提单号）+ S/O，空运必有 AWB，快递有 UPS 运单号。
-- S/O 与 BL 同号是**演示简化**（真实场景两者通常不同），留作后续可调整点。
-- status 与母单状态对齐：M1 的验收终点是母单 DEPARTED / 运单 DEPARTED。
-- =============================================================================
USE tms;

INSERT INTO tms_shipments
    (tenant_id, shipment_no, batch_no, awb_no, bl_no, booking_no, sailing_id,
     mode, carrier_id, pol_code, pod_code, container_type, container_qty,
     incoterm, currency, total_pieces, total_gross_weight, total_volume, volumetric_weight,
     chargeable_weight, vgm_weight, marks, etd_at, eta_at, cutoff_at,
     actual_etd_at, sanction_flag, status, remark, created_by)
SELECT 'alibaba', CONCAT('SHP20261010', LPAD(v.seq, 3, '0')), v.batch_no,
       v.awb_no, v.bl_no, v.booking_no, IFNULL(s.sailing_id, 0),
       v.mode, c.id, v.pol_code, v.pod_code, v.container_type, v.container_qty,
       'FOB', 'USD', b.total_qty, b.total_weight, b.total_volume, b.volumetric_weight,
       b.total_weight, b.total_weight,
       CONCAT('N/M: ', v.marks_name, '\nP/C: ', b.batch_no, '\nR/D: ', v.pod_code, '\nMADE IN CHINA'),
       v.etd_at, v.eta_at, v.cutoff_at, v.actual_etd_at,
       -- sanction_flag：seq=4（SHP20261010004，BOOKED「已订舱待揽收」）给 HIT。
       -- 全 CLEAR 的话，「HIT 不得推到 EXPORT_DECLARED」这段校验在真实数据上一次
       -- 都跑不到——没有样本的校验等于没有校验。挑它是因为它是**报关前**最后一个
       -- 可推进状态，从这里推 EXPORT_DECLARED 唯一拦得住的就是制裁校验本身
       -- （BOOKED → EXPORT_DECLARED 不在状态机里，会先被状态机拒掉）。
       -- HIT 是人工核出来的，remark 里写明是演示数据，免得日后被当成真实合规事件。
       CASE WHEN v.seq = 4 THEN 'HIT' ELSE 'CLEAR' END,
       v.status,
       CASE WHEN v.seq = 4 THEN CONCAT(v.remark,
            '；【演示数据】制裁筛查命中：收发货方命中 SDN 清单（模拟第三方报告号 '
            'SDN-P2-DEMO-0001），未人工放行前不得报关放行')
            ELSE v.remark END,
       1
  FROM (
        SELECT 1 AS seq, 'MEXP20261010001' AS batch_no, 'SEA' AS mode, 'COSCO' AS carrier_code, 'CNSHA' AS pol_code, 'USLAX' AS pod_code, '40HQ' AS container_type, 2 AS container_qty, 'HAIER GLOBAL' AS marks_name, '' AS awb_no, 'COSU6123456' AS bl_no, 'COSU6123456' AS booking_no, 'CNSHA-USLAX' AS route_code, '2026-10-14 01:00:00' AS etd_at, '2026-10-30 08:00:00' AS eta_at, '2026-10-12 09:00:00' AS cutoff_at, '2026-10-14 01:40:00' AS actual_etd_at, 'DEPARTED' AS status, '美西海运整柜' AS remark
        UNION ALL SELECT 2, 'MEXP20261010002', 'SEA', 'MAEU', 'CNSHA', 'USNYC', '40GP', 1, 'HARBOR GOODS', '', 'MAEU4120877', 'MAEU4120877', 'CNSHA-USNYC', '2026-10-09 06:00:00', '2026-10-27 14:00:00', '2026-10-07 14:00:00', '2026-10-09 06:30:00', 'IN_TRANSIT', '美东整柜，目的港清关中'
        UNION ALL SELECT 3, 'MEXP20261010003', 'AIR', 'EK', 'PVG', 'ORD', '', 0, 'LAKESHORE', '176-30874125', '', '', 'PVG-ORD', '2026-10-11 22:00:00', '2026-10-14 04:00:00', '2026-10-11 04:00:00', '2026-10-11 22:35:00', 'DEPARTED', '空运快线【待验证】AWB 前缀 176'
        UNION ALL SELECT 4, 'MEXP20261010004', 'SEA', 'ONE', 'CNNGB', 'USDAL', '20GP', 1, 'SUNSHINE APPAREL', '', 'ONEY3390218', 'ONEY3390218', 'CNNGB-USDAL', '2026-10-17 03:00:00', '2026-11-06 09:00:00', '2026-10-14 15:00:00', NULL, 'BOOKED', '已订舱待揽收'
        UNION ALL SELECT 5, 'MEXP20261010005', 'SEA', 'MSC', 'CNSZX', 'DEHAM', '40HQ', 1, 'RHEIN OUTDOOR', '', '', '', 'CNSZX-DEHAM', '2026-10-21 02:00:00', '2026-11-20 07:00:00', '2026-10-19 10:00:00', NULL, 'BOOKING', '订舱中，S/O 未回'
        UNION ALL SELECT 6, 'MEXP20261010006', 'EXPRESS', 'UPS', 'CNSHA', 'USLAX', '', 0, 'WESTSIDE ONLINE', '1Z999AA10123456784', '', '', 'CNSHA-USLAX-EXP', '2026-10-08 04:00:00', '2026-10-13 10:00:00', '2026-10-08 01:00:00', '2026-10-08 05:10:00', 'CLEARED', 'UPS 公开示例运单号'
        UNION ALL SELECT 7, 'MEXP20261010007', 'SEA', 'CMA', 'CNTAU', 'USLAX', '40HQ', 1, 'BAY AREA HOME', '', 'CMDU5510874', 'CMDU5510874', 'CNTAU-USLAX', '2026-10-06 05:00:00', '2026-10-23 11:00:00', '2026-10-04 13:00:00', '2026-10-06 05:20:00', 'CLEARED', '尾程派送中'
        UNION ALL SELECT 8, 'MEXP20261010008', 'AIR', 'CZ', 'CAN', 'AMS', '', 0, 'AMSTERDAM TRADE', '784-30218745', '', '', 'CAN-AMS', '2026-10-13 02:00:00', '2026-10-17 08:00:00', '2026-10-12 06:00:00', NULL, 'BOOKED', '空运在途【待验证】AWB 前缀 784'
        UNION ALL SELECT 9, 'MEXP20261010009', 'SEA', 'COSCO', 'CNSHA', 'USLAX', '40HQ', 1, '3C GLOBAL', '', 'COSU6123519', 'COSU6123519', 'CNSHA-USLAX', '2026-10-12 01:00:00', '2026-10-28 08:00:00', '2026-10-10 09:00:00', '2026-10-12 01:25:00', 'DEPARTED', '已开航'
        UNION ALL SELECT 10, 'MEXP20261010010', 'LCL', 'SEAWAY', 'CNSHA', 'USLAX', 'LCL', 1, 'ELLIS TRADING', '', '', '', 'CNSHA-USLAX', '2026-10-19 01:00:00', '2026-11-08 08:00:00', '2026-10-17 15:00:00', NULL, 'BOOKING', '拼箱，运单已建待订舱'
        UNION ALL SELECT 11, 'MEXP20261010011', 'SEA', 'COSCO', 'CNSHA', 'USLAX', '40HQ', 1, 'REDWOOD SUPPLY', '', 'COSU6123502', 'COSU6123502', 'CNSHA-USLAX', '2026-10-15 01:00:00', '2026-10-31 08:00:00', '2026-10-13 09:00:00', NULL, 'EXCEPTION', '整批查验'
        UNION ALL SELECT 12, 'MEXP20261010012', 'EXPRESS', 'UPS', 'CNSHA', 'USLAX', '', 0, '3C GLOBAL', '1Z999AA10123456801', '', '', 'CNSHA-USLAX-EXP', '2026-10-05 04:00:00', '2026-10-10 09:00:00', '2026-10-05 01:00:00', '2026-10-05 05:05:00', 'DELIVERED', '已妥投'
       ) v
  JOIN oms.oms_export_batches b ON b.tenant_id = 'alibaba' AND b.batch_no = v.batch_no
  JOIN tms_carriers c ON c.tenant_id = 'alibaba' AND c.code = v.carrier_code
  LEFT JOIN (SELECT r.tenant_id, r.route_code, MIN(s2.id) AS sailing_id
               FROM tms_routes r
               LEFT JOIN tms_sailings s2 ON s2.route_id = r.id AND s2.tenant_id = r.tenant_id
              GROUP BY r.tenant_id, r.route_code) s
         ON s.tenant_id = 'alibaba' AND s.route_code = v.route_code
ON DUPLICATE KEY UPDATE
    batch_no = VALUES(batch_no), status = VALUES(status), etd_at = VALUES(etd_at),
    eta_at = VALUES(eta_at), actual_etd_at = VALUES(actual_etd_at);

-- =============================================================================
-- 五、TMS：运单箱明细 / 分单（44 行）
-- house_no = 母单号-2 位序号（House B/L），集装箱号 ISO 6346 格式
-- 【待验证】校验位未验算，本项目只存字符串不校验。
-- 唛头在 SQL 里拼：收货人抬头 + 单号 + 目的港 + 箱号 + MADE IN CHINA，
-- 这是真实唛头的标准排版，不是占位符。
-- =============================================================================
INSERT INTO tms_shipment_items
    (tenant_id, shipment_id, shipment_no, order_no, house_no, container_no, container_type,
     seal_no, marks, pieces, gross_weight, volume, volumetric_weight, is_dangerous, un_number, dg_class)
SELECT 'alibaba', s.id, s.shipment_no, v.order_no,
       CONCAT(s.batch_no, '-', LPAD(v.box_seq, 2, '0')),
       v.container_no, s.container_type, v.seal_no,
       CONCAT('N/M: ', o.consignee_company, '\nP/C: ', LPAD(v.box_seq, 2, '0'), '\nR/D: ', s.pod_code, '\nCNTR NO: ', v.container_no, '\nMADE IN CHINA'),
       o.total_qty, o.total_weight, o.total_volume, o.volumetric_weight,
       v.is_dangerous, v.un_number, v.dg_class
  FROM (
        SELECT 'SHP20261010001' AS shipment_no, 'EXP20261010001' AS order_no, 1 AS box_seq, 'CSNU6382914' AS container_no, 'SL12345678' AS seal_no, 1 AS is_dangerous, 'UN3481' AS un_number, '9' AS dg_class
        UNION ALL SELECT 'SHP20261010001', 'EXP20261010001', 2, 'CSNU6382915', 'SL12345679', 1, 'UN3481', '9'
        UNION ALL SELECT 'SHP20261010001', 'EXP20261010002', 1, 'CSNU6382916', 'SL12345680', 1, 'UN3481', '9'
        UNION ALL SELECT 'SHP20261010001', 'EXP20261010003', 1, 'CSNU6382917', 'SL12345681', 1, 'UN3481', '9'
        UNION ALL SELECT 'SHP20261010001', 'EXP20261010004', 1, 'CSNU6382918', 'SL12345682', 1, 'UN3481', '9'
        UNION ALL SELECT 'SHP20261010001', 'EXP20261010005', 1, 'CSNU6382919', 'SL12345683', 1, 'UN3480', '9'
        UNION ALL SELECT 'SHP20261010002', 'EXP20261010006', 1, 'TGHU4837265', 'SL12345684', 0, '', ''
        UNION ALL SELECT 'SHP20261010002', 'EXP20261010007', 1, 'TGHU4837266', 'SL12345685', 0, '', ''
        UNION ALL SELECT 'SHP20261010002', 'EXP20261010008', 1, 'TGHU4837267', 'SL12345686', 0, '', ''
        UNION ALL SELECT 'SHP20261010002', 'EXP20261010009', 1, 'TGHU4837268', 'SL12345687', 0, '', ''
        UNION ALL SELECT 'SHP20261010003', 'EXP20261010010', 1, '', 'SL12345688', 1, 'UN3481', '9'
        UNION ALL SELECT 'SHP20261010003', 'EXP20261010011', 1, '', 'SL12345689', 1, 'UN3481', '9'
        UNION ALL SELECT 'SHP20261010003', 'EXP20261010012', 1, '', 'SL12345690', 1, 'UN3480', '9'
        UNION ALL SELECT 'SHP20261010003', 'EXP20261010013', 1, '', 'SL12345691', 1, 'UN3481', '9'
        UNION ALL SELECT 'SHP20261010003', 'EXP20261010014', 1, '', 'SL12345692', 1, 'UN3481', '9'
        UNION ALL SELECT 'SHP20261010003', 'EXP20261010015', 1, '', 'SL12345693', 1, 'UN3480', '9'
        UNION ALL SELECT 'SHP20261010004', 'EXP20261010016', 1, 'OOLU7712054', 'SL12345694', 0, '', ''
        UNION ALL SELECT 'SHP20261010004', 'EXP20261010017', 1, 'OOLU7712055', 'SL12345695', 0, '', ''
        UNION ALL SELECT 'SHP20261010004', 'EXP20261010018', 1, 'OOLU7712056', 'SL12345696', 0, '', ''
        UNION ALL SELECT 'SHP20261010005', 'EXP20261010019', 1, 'MSCU2258417', 'SL12345697', 0, '', ''
        UNION ALL SELECT 'SHP20261010005', 'EXP20261010020', 1, 'MSCU2258418', 'SL12345698', 0, '', ''
        UNION ALL SELECT 'SHP20261010005', 'EXP20261010021', 1, 'MSCU2258419', 'SL12345699', 0, '', ''
        UNION ALL SELECT 'SHP20261010006', 'EXP20261010022', 1, '', 'SL12345700', 0, '', ''
        UNION ALL SELECT 'SHP20261010006', 'EXP20261010023', 1, '', 'SL12345701', 0, '', ''
        UNION ALL SELECT 'SHP20261010006', 'EXP20261010024', 1, '', 'SL12345702', 0, '', ''
        UNION ALL SELECT 'SHP20261010006', 'EXP20261010025', 1, '', 'SL12345703', 0, '', ''
        UNION ALL SELECT 'SHP20261010007', 'EXP20261010026', 1, 'CMAU1193840', 'SL12345704', 0, '', ''
        UNION ALL SELECT 'SHP20261010007', 'EXP20261010027', 1, 'CMAU1193841', 'SL12345705', 0, '', ''
        UNION ALL SELECT 'SHP20261010007', 'EXP20261010028', 1, 'CMAU1193842', 'SL12345706', 0, '', ''
        UNION ALL SELECT 'SHP20261010008', 'EXP20261010029', 1, '', 'SL12345707', 1, 'UN3481', '9'
        UNION ALL SELECT 'SHP20261010008', 'EXP20261010030', 1, '', 'SL12345708', 1, 'UN3481', '9'
        UNION ALL SELECT 'SHP20261010008', 'EXP20261010031', 1, '', 'SL12345709', 1, 'UN3481', '9'
        UNION ALL SELECT 'SHP20261010008', 'EXP20261010032', 1, '', 'SL12345710', 1, 'UN3480', '9'
        UNION ALL SELECT 'SHP20261010009', 'EXP20261010033', 1, 'CSNU6382921', 'SL12345711', 1, 'UN3481', '9'
        UNION ALL SELECT 'SHP20261010009', 'EXP20261010034', 1, 'CSNU6382922', 'SL12345712', 1, 'UN3481', '9'
        UNION ALL SELECT 'SHP20261010009', 'EXP20261010035', 1, 'CSNU6382923', 'SL12345713', 1, 'UN3481', '9'
        UNION ALL SELECT 'SHP20261010010', 'EXP20261010036', 1, 'XSKU4410274', 'SL12345714', 1, 'UN3481', '9'
        UNION ALL SELECT 'SHP20261010010', 'EXP20261010037', 1, 'XSKU4410275', 'SL12345715', 1, 'UN3481', '9'
        UNION ALL SELECT 'SHP20261010010', 'EXP20261010038', 1, 'XSKU4410276', 'SL12345716', 1, 'UN3480', '9'
        UNION ALL SELECT 'SHP20261010010', 'EXP20261010039', 1, 'XSKU4410277', 'SL12345717', 1, 'UN3481', '9'
        UNION ALL SELECT 'SHP20261010011', 'EXP20261010040', 1, 'CSNU6382927', 'SL12345718', 0, '', ''
        UNION ALL SELECT 'SHP20261010011', 'EXP20261010041', 1, 'CSNU6382928', 'SL12345719', 0, '', ''
        UNION ALL SELECT 'SHP20261010012', 'EXP20261010042', 1, '', 'SL12345720', 0, '', ''
        UNION ALL SELECT 'SHP20261010012', 'EXP20261010043', 1, '', 'SL12345721', 0, '', ''
       ) v
  JOIN tms_shipments s ON s.tenant_id = 'alibaba' AND s.shipment_no = v.shipment_no
  JOIN oms.oms_export_orders o ON o.tenant_id = 'alibaba' AND o.order_no = v.order_no
 WHERE NOT EXISTS (
       SELECT 1 FROM tms_shipment_items i
        WHERE i.shipment_id = s.id AND i.house_no = CONCAT(s.batch_no, '-', LPAD(v.box_seq, 2, '0')));

-- =============================================================================
-- 六、TMS：轨迹节点（55 条）
-- source 列刻意混合 MANUAL 与 CARRIER_CALLBACK：M1 只实现了人工录入，
-- 承运商回调入口是 M2 的事，但种子先把 CARRIER_CALLBACK 的数据铺上，
-- 这样页面上的「来源」标签三色区分从第一天就有意义。
-- 幂等：按 (shipment_no, node_code, node_time) 判存在（与回调幂等同一思路）。
-- =============================================================================
INSERT INTO tms_tracking_nodes
    (tenant_id, shipment_id, shipment_no, node_code, node_name, node_time, location, source, operator, remark)
SELECT 'alibaba', s.id, v.shipment_no, v.node_code, v.node_name, v.node_time, v.location, v.source, v.operator, v.remark
  FROM (
        SELECT 'SHP20261010001' AS shipment_no, 'BOOKED' AS node_code, '已订舱' AS node_name, '2026-10-06 03:20:00' AS node_time, '上海' AS location, 'MANUAL' AS source, '风清扬' AS operator, 'COSCO 回 S/O 确认' AS remark
        UNION ALL SELECT 'SHP20261010001', 'PICKED_UP', '已揽收', '2026-10-11 06:40:00', '宁波北仑出口仓', 'MANUAL', '风清扬', '拖车进仓'
        UNION ALL SELECT 'SHP20261010001', 'EXPORT_DECLARED', '已报关', '2026-10-12 02:15:00', '上海洋山港', 'MANUAL', '风清扬', '速通关报关行放行'
        UNION ALL SELECT 'SHP20261010001', 'DEPARTED', '已开航', '2026-10-14 01:40:00', '上海洋山港', 'CARRIER_CALLBACK', '', 'COSCO ARIES V.034E'
        UNION ALL SELECT 'SHP20261010001', 'IN_TRANSIT', '在途', '2026-10-18 08:00:00', '北太平洋', 'CARRIER_CALLBACK', '', '跨太平洋西行'
        UNION ALL SELECT 'SHP20261010002', 'BOOKED', '已订舱', '2026-10-05 06:10:00', '上海', 'MANUAL', '风清扬', 'MAEU 回 S/O'
        UNION ALL SELECT 'SHP20261010002', 'PICKED_UP', '已揽收', '2026-10-08 02:05:00', '义乌集货仓', 'MANUAL', '风清扬', ''
        UNION ALL SELECT 'SHP20261010002', 'EXPORT_DECLARED', '已报关', '2026-10-08 04:30:00', '上海洋山港', 'MANUAL', '风清扬', ''
        UNION ALL SELECT 'SHP20261010002', 'DEPARTED', '已开航', '2026-10-09 06:30:00', '上海洋山港', 'CARRIER_CALLBACK', '', 'MAERSK SEVILLE 642W'
        UNION ALL SELECT 'SHP20261010002', 'IN_TRANSIT', '在途', '2026-10-14 12:00:00', '北大西洋', 'CARRIER_CALLBACK', '', ''
        UNION ALL SELECT 'SHP20261010002', 'ARRIVED', '已到港', '2026-10-27 14:20:00', '纽约港', 'CARRIER_CALLBACK', '', ''
        UNION ALL SELECT 'SHP20261010002', 'CLEARING', '清关中', '2026-10-28 03:40:00', '纽约港', 'MANUAL', '风清扬', 'FDA 查验待回复'
        UNION ALL SELECT 'SHP20261010003', 'BOOKED', '已订舱', '2026-10-09 01:00:00', '上海浦东', 'MANUAL', '风清扬', 'EK302'
        UNION ALL SELECT 'SHP20261010003', 'PICKED_UP', '已揽收', '2026-10-11 03:20:00', '深圳前海保税仓', 'MANUAL', '风清扬', ''
        UNION ALL SELECT 'SHP20261010003', 'EXPORT_DECLARED', '已报关', '2026-10-11 06:00:00', '浦东机场货站', 'MANUAL', '风清扬', '空运舱单已报'
        UNION ALL SELECT 'SHP20261010003', 'DEPARTED', '已起飞', '2026-10-11 22:35:00', '浦东机场', 'CARRIER_CALLBACK', '', 'EK302'
        UNION ALL SELECT 'SHP20261010004', 'BOOKED', '已订舱', '2026-10-08 07:00:00', '宁波', 'MANUAL', '风清扬', 'ONE 019S'
        UNION ALL SELECT 'SHP20261010004', 'PICKED_UP', '已揽收', '2026-10-12 01:10:00', '宁波北仑出口仓', 'MANUAL', '风清扬', ''
        UNION ALL SELECT 'SHP20261010005', 'BOOKING', '订舱中', '2026-10-09 08:00:00', '深圳', 'MANUAL', '风清扬', 'MSC 订舱申请已发，等 S/O'
        UNION ALL SELECT 'SHP20261010006', 'BOOKED', '已下单', '2026-10-07 08:00:00', '上海', 'MANUAL', '风清扬', 'UPS 取件'
        UNION ALL SELECT 'SHP20261010006', 'PICKED_UP', '已揽收', '2026-10-08 05:10:00', '上海', 'CARRIER_CALLBACK', '', 'UPS 上门揽收'
        UNION ALL SELECT 'SHP20261010006', 'EXPORT_DECLARED', '已报关', '2026-10-08 08:00:00', '上海', 'MANUAL', '风清扬', '出口报关放行'
        UNION ALL SELECT 'SHP20261010006', 'DEPARTED', '已起飞', '2026-10-08 12:00:00', '上海浦东', 'CARRIER_CALLBACK', '', 'UPS 航空'
        UNION ALL SELECT 'SHP20261010006', 'ARRIVED', '已到港', '2026-10-13 10:00:00', '洛杉矶', 'CARRIER_CALLBACK', '', ''
        UNION ALL SELECT 'SHP20261010006', 'CLEARING', '清关中', '2026-10-13 22:00:00', '洛杉矶', 'MANUAL', '风清扬', ''
        UNION ALL SELECT 'SHP20261010006', 'CLEARED', '已清关放行', '2026-10-14 06:00:00', '洛杉矶', 'MANUAL', '风清扬', ''
        UNION ALL SELECT 'SHP20261010007', 'BOOKED', '已订舱', '2026-10-02 04:00:00', '青岛', 'MANUAL', '风清扬', 'CMA 0FA7WE1'
        UNION ALL SELECT 'SHP20261010007', 'PICKED_UP', '已揽收', '2026-10-04 23:30:00', '宁波北仑出口仓', 'MANUAL', '风清扬', ''
        UNION ALL SELECT 'SHP20261010007', 'EXPORT_DECLARED', '已报关', '2026-10-05 01:00:00', '青岛', 'MANUAL', '风清扬', ''
        UNION ALL SELECT 'SHP20261010007', 'DEPARTED', '已开航', '2026-10-06 05:20:00', '青岛', 'CARRIER_CALLBACK', '', 'CMA CGM LYON'
        UNION ALL SELECT 'SHP20261010007', 'ARRIVED', '已到港', '2026-10-23 11:00:00', '洛杉矶', 'CARRIER_CALLBACK', '', ''
        UNION ALL SELECT 'SHP20261010007', 'CLEARING', '清关中', '2026-10-24 04:00:00', '洛杉矶', 'MANUAL', '风清扬', ''
        UNION ALL SELECT 'SHP20261010007', 'CLEARED', '已清关放行', '2026-10-26 08:00:00', '洛杉矶', 'MANUAL', '风清扬', ''
        UNION ALL SELECT 'SHP20261010007', 'LAST_MILE', '尾程派送中', '2026-10-29 09:00:00', '洛杉矶海外仓', 'MANUAL', '风清扬', '谷仓海外仓派送'
        UNION ALL SELECT 'SHP20261010008', 'BOOKED', '已订舱', '2026-10-10 03:00:00', '广州', 'MANUAL', '风清扬', 'CZ353'
        UNION ALL SELECT 'SHP20261010008', 'PICKED_UP', '已揽收', '2026-10-12 06:00:00', '义乌集货仓', 'MANUAL', '风清扬', ''
        UNION ALL SELECT 'SHP20261010008', 'EXPORT_DECLARED', '已报关', '2026-10-12 09:00:00', '广州白云机场', 'MANUAL', '风清扬', ''
        UNION ALL SELECT 'SHP20261010009', 'BOOKED', '已订舱', '2026-10-05 02:30:00', '上海', 'MANUAL', '风清扬', 'COSCO 回 S/O'
        UNION ALL SELECT 'SHP20261010009', 'PICKED_UP', '已揽收', '2026-10-10 23:00:00', '深圳前海保税仓', 'MANUAL', '风清扬', ''
        UNION ALL SELECT 'SHP20261010009', 'EXPORT_DECLARED', '已报关', '2026-10-11 02:00:00', '上海洋山港', 'MANUAL', '风清扬', ''
        UNION ALL SELECT 'SHP20261010009', 'DEPARTED', '已开航', '2026-10-12 01:25:00', '上海洋山港', 'CARRIER_CALLBACK', '', 'COSCO ARIES V.026E'
        UNION ALL SELECT 'SHP20261010010', 'BOOKING', '订舱中', '2026-10-13 02:00:00', '上海', 'MANUAL', '风清扬', '拼箱待订舱'
        UNION ALL SELECT 'SHP20261010011', 'BOOKED', '已订舱', '2026-10-07 03:00:00', '上海', 'MANUAL', '风清扬', ''
        UNION ALL SELECT 'SHP20261010011', 'PICKED_UP', '已揽收', '2026-10-13 00:40:00', '义乌集货仓', 'MANUAL', '风清扬', ''
        UNION ALL SELECT 'SHP20261010011', 'EXPORT_DECLARED', '已报关', '2026-10-13 03:00:00', '上海洋山港', 'MANUAL', '风清扬', ''
        UNION ALL SELECT 'SHP20261010011', 'EXCEPTION', '异常', '2026-10-13 15:10:00', '上海洋山港', 'MANUAL', '风清扬', '海关查验，整批暂扣'
        UNION ALL SELECT 'SHP20261010012', 'BOOKED', '已下单', '2026-10-04 08:00:00', '上海', 'MANUAL', '风清扬', 'UPS 取件'
        UNION ALL SELECT 'SHP20261010012', 'PICKED_UP', '已揽收', '2026-10-05 05:05:00', '上海', 'CARRIER_CALLBACK', '', ''
        UNION ALL SELECT 'SHP20261010012', 'EXPORT_DECLARED', '已报关', '2026-10-05 07:00:00', '上海', 'MANUAL', '风清扬', ''
        UNION ALL SELECT 'SHP20261010012', 'DEPARTED', '已起飞', '2026-10-05 12:00:00', '上海浦东', 'CARRIER_CALLBACK', '', ''
        UNION ALL SELECT 'SHP20261010012', 'ARRIVED', '已到港', '2026-10-10 09:00:00', '洛杉矶', 'CARRIER_CALLBACK', '', ''
        UNION ALL SELECT 'SHP20261010012', 'CLEARING', '清关中', '2026-10-10 21:00:00', '洛杉矶', 'MANUAL', '风清扬', ''
        UNION ALL SELECT 'SHP20261010012', 'CLEARED', '已清关放行', '2026-10-11 05:00:00', '洛杉矶', 'MANUAL', '风清扬', ''
        UNION ALL SELECT 'SHP20261010012', 'DELIVERED', '已妥投', '2026-10-14 16:30:00', '洛杉矶', 'MANUAL', '风清扬', '前台签收人 J. Ellis'
       ) v
  JOIN tms_shipments s ON s.tenant_id = 'alibaba' AND s.shipment_no = v.shipment_no
 WHERE NOT EXISTS (
       SELECT 1 FROM tms_tracking_nodes n
        WHERE n.tenant_id = 'alibaba' AND n.shipment_no = v.shipment_no
          AND n.node_code = v.node_code AND n.node_time = v.node_time);

-- =============================================================================
-- 七、TMS：出口报关单（8 条）
-- 状态分布 RELEASED 5 / FILED 1 / INSPECTING 1 / REJECTED 1。
-- HELD（扣货）不铺种子——只留 INSPECTING，避免异常数据喧宾夺主。
-- 报关单号位数与编码规则【待验证】。
-- =============================================================================
INSERT INTO tms_customs_declarations
    (tenant_id, declaration_no, shipment_id, shipment_no, batch_no, customs_broker, broker_contact,
     hs_code_summary, declared_value, currency, duty_amount, vat_amount, consumption_tax,
     deposit_amount, status, filed_at, released_at, remark)
SELECT 'alibaba', v.declaration_no, s.id, v.shipment_no, s.batch_no, v.customs_broker, v.broker_contact,
       v.hs_code_summary, v.declared_value, 'USD', v.duty_amount, v.vat_amount, 0.00,
       v.deposit_amount, v.status, v.filed_at, v.released_at, v.remark
  FROM (
        SELECT 'TESTID00000001X' AS declaration_no, 'SHP20261010001' AS shipment_no, '速通关报关行' AS customs_broker, '13800000023' AS broker_contact, '8507600090,8517130000,8518301000' AS hs_code_summary, 48600.00 AS declared_value, 1240.60 AS duty_amount, 1063.80 AS vat_amount, 5000.00 AS deposit_amount, 'RELEASED' AS status, '2026-10-11 01:00:00' AS filed_at, '2026-10-12 08:30:00' AS released_at, '含锂电池，已附 MSDS 与危包证' AS remark
        UNION ALL SELECT 'TESTID00000002X', 'SHP20261010002', '速通关报关行', '13800000023', '6109100021,6202130000,6912001000', 61280.00, 1532.00, 1400.00, 0.00, 'RELEASED', '2026-10-08 03:00:00', '2026-10-08 10:15:00', ''
        UNION ALL SELECT 'TESTID00000003X', 'SHP20261010003', '速通关报关行', '13800000023', '8471300000,8517120000,8507600090', 148250.00, 3706.25, 2965.00, 3000.00, 'RELEASED', '2026-10-11 05:00:00', '2026-10-11 09:40:00', '空运舱单随报关单一并提交'
        UNION ALL SELECT 'TESTID00000004X', 'SHP20261010004', '速通关报关行', '13800000023', '6109100021,6202130000', 61880.00, 1547.00, 0.00, 0.00, 'RELEASED', '2026-10-12 00:30:00', '2026-10-12 04:20:00', ''
        UNION ALL SELECT 'TESTID00000005X', 'SHP20261010006', '速通关报关行', '13800000023', '6109100021,6912001000,6202130000', 48300.00, 1207.50, 0.00, 0.00, 'RELEASED', '2026-10-08 07:00:00', '2026-10-08 11:10:00', '快递件合并报关'
        UNION ALL SELECT 'TESTID00000006X', 'SHP20261010007', '速通关报关行', '13800000023', '9401790000,6912001000,6202130000', 92610.00, 2315.25, 0.00, 0.00, 'RELEASED', '2026-10-05 00:20:00', '2026-10-05 05:50:00', ''
        UNION ALL SELECT 'TESTID00000007X', 'SHP20261010008', '速通关报关行', '13800000023', '8517120000,8518301000,8507600090', 78600.00, 1965.00, 0.00, 0.00, 'FILED', '2026-10-12 08:00:00', NULL, '已申报待放行'
        UNION ALL SELECT 'TESTID00000008X', 'SHP20261010011', '速通关报关行', '13800000023', '6109100021,6912001000', 52360.00, 1309.00, 0.00, 0.00, 'INSPECTING', '2026-10-13 02:00:00', NULL, '海关随机查验，暂扣在洋山港'
       ) v
  JOIN tms_shipments s ON s.tenant_id = 'alibaba' AND s.shipment_no = v.shipment_no
ON DUPLICATE KEY UPDATE
    status = VALUES(status), filed_at = VALUES(filed_at), released_at = VALUES(released_at);

-- =============================================================================
-- 八、WMS：库存批次与效期（18 行）
-- 效期刻意包含「7 天内到期」与「已过期」两种，喂看板的 nearExpiring / expired。
-- 可用量 = quantity - reserved_qty，不建列、查询时算。
-- =============================================================================
USE wms;

INSERT INTO wms_inventory_batches
    (tenant_id, warehouse_id, sku, product_name, batch_no, production_date, expiry_date,
     quantity, reserved_qty, unit_cost, location_code, status)
SELECT 'alibaba', w.id, v.sku, v.product_name, v.batch_no, v.production_date, v.expiry_date,
       v.quantity, v.reserved_qty, v.unit_cost, v.location_code, v.status
  FROM (
        SELECT '宁波北仑出口仓' AS warehouse, 'TEE-COT-M-WHT' AS sku, '精梳棉圆领T恤 M 白' AS product_name, 'B260901-A' AS batch_no, '2026-09-01' AS production_date, '2027-09-01' AS expiry_date, 1200 AS quantity, 300 AS reserved_qty, 18.50 AS unit_cost, 'A-01-03' AS location_code, 'NORMAL' AS status
        UNION ALL SELECT '宁波北仑出口仓', 'TEE-COT-M-WHT', '精梳棉圆领T恤 M 白', 'B260908-C', '2026-09-08', '2026-10-08', 400, 150, 18.50, 'A-01-04', 'NEAR_EXPIRY'
        UNION ALL SELECT '宁波北仑出口仓', 'TEE-COT-M-BLU', '精梳棉圆领T恤 M 蓝', 'B260510-B', '2026-05-10', '2026-09-28', 80, 0, 17.20, 'A-02-01', 'EXPIRED'
        UNION ALL SELECT '宁波北仑出口仓', 'TEE-COT-M-BLU', '精梳棉圆领T恤 M 蓝', 'B260901-A', '2026-09-01', '2027-09-01', 900, 200, 18.50, 'A-02-02', 'NORMAL'
        UNION ALL SELECT '宁波北仑出口仓', 'IPH-15-BLK', 'iPhone 15 Pro 256G 黑', 'B260615-A', '2026-06-15', '2028-06-15', 300, 140, 6800.00, 'B-01-01', 'NORMAL'
        UNION ALL SELECT '宁波北仑出口仓', 'IPH-15-BLU', 'iPhone 15 Pro 256G 蓝', 'B260615-A', '2026-06-15', '2028-06-15', 220, 130, 6800.00, 'B-01-02', 'NORMAL'
        UNION ALL SELECT '宁波北仑出口仓', 'AIRP-2-WHT', 'AirPods Pro 2', 'B260710-A', '2026-07-10', '2028-07-10', 800, 120, 1500.00, 'B-02-01', 'NORMAL'
        UNION ALL SELECT '宁波北仑出口仓', 'WATCH-S9-BLK', 'Apple Watch S9 黑', 'B260622-A', '2026-06-22', '2028-06-22', 400, 91, 2600.00, 'B-02-03', 'NORMAL'
        UNION ALL SELECT '宁波北仑出口仓', 'MBP-14-SLV', 'MacBook Pro 14 银色', 'B260520-A', '2026-05-20', '2028-05-20', 60, 23, 12800.00, 'C-01-01', 'NORMAL'
        UNION ALL SELECT '宁波北仑出口仓', 'IPAD-AIR-64G', 'iPad Air 64G', 'B260703-A', '2026-07-03', '2028-07-03', 150, 68, 4100.00, 'C-01-02', 'NORMAL'
        UNION ALL SELECT '宁波北仑出口仓', 'POWER-100W', '移动电源 20000mAh', 'B260818-A', '2026-08-18', '2027-02-18', 500, 250, 210.00, 'D-01-01', 'NORMAL'
        UNION ALL SELECT '深圳前海保税仓', 'IPH-15-BLK', 'iPhone 15 Pro 256G 黑', 'B260715-B', '2026-07-15', '2028-07-15', 180, 30, 6800.00, 'A-01-01', 'NORMAL'
        UNION ALL SELECT '深圳前海保税仓', 'MBP-14-SLV', 'MacBook Pro 14 银色', 'B260728-B', '2026-07-28', '2028-07-28', 30, 9, 12800.00, 'A-02-01', 'NORMAL'
        UNION ALL SELECT '深圳前海保税仓', 'AIRP-2-WHT', 'AirPods Pro 2', 'B260802-B', '2026-08-02', '2028-08-02', 260, 105, 1500.00, 'A-02-02', 'NORMAL'
        UNION ALL SELECT '义乌集货仓', 'TEE-COT-M-WHT', '精梳棉圆领T恤 M 白', 'B260901-D', '2026-09-01', '2027-09-01', 3000, 800, 18.20, 'E-01-01', 'NORMAL'
        UNION ALL SELECT '义乌集货仓', 'TEE-COT-M-BLU', '精梳棉圆领T恤 M 蓝', 'B260901-D', '2026-09-01', '2027-09-01', 2800, 690, 18.20, 'E-01-02', 'NORMAL'
        UNION ALL SELECT '义乌集货仓', 'CERAMIC-MUG', '陶瓷马克杯 350ml', 'B260620-D', '2026-06-20', '2029-06-20', 1500, 320, 22.00, 'E-02-01', 'NORMAL'
        UNION ALL SELECT '义乌集货仓', 'DENIM-JKT-L', '水洗牛仔外套 L', 'B260520-D', '2026-05-20', '2030-05-20', 600, 175, 145.00, 'E-02-04', 'FROZEN'
       ) v
  JOIN wms_warehouses w ON w.tenant_id = 'alibaba' AND w.name = v.warehouse
ON DUPLICATE KEY UPDATE
    quantity = VALUES(quantity), reserved_qty = VALUES(reserved_qty), status = VALUES(status);

-- =============================================================================
-- =============================================================================
-- 九、WMS：备货任务（43 条，一个子单一条）
-- task_no 由 order_no 派生（PK + yyyyMMdd + 后 4 位），天然唯一，脚本幂等靠它。
--
-- 任务状态**按子单所处阶段派生**，目的是让四阶段（拣货→装箱→贴标→交接）在种子里
-- 每一档都有数据 —— M1 的纵向切片就是「备货装箱贴标交接」，如果种子只有 PICKING 和
-- HANDED_OVER，装箱与贴标这两个中间态在页面上根本看不到，演示时说不清楚：
--   子单 PICKING（还没装箱）      → 任务 PICKING
--   子单 PACKED（已装箱未交接）    → 任务 PACKED / LABELLED（按单号尾号取模分两档，
--                                   让页面上的状态筛选每一档都筛得出东西）
--   子单已装箱贴标并交接          → 任务 HANDED_OVER
--   指定 1 条 EXCEPTION           → 任务 SHORTAGE（喂看板的 shortage 档）
-- label_printed_at / handed_over_at 也跟着这个阶段走：还没贴标就不该有贴标时间，
-- 没交接就不该有交接时间，否则时间线上会出现「交接早于贴标」这种自相矛盾的时间戳。
--
-- 重量/体积/危险品**从 OMS 明细回算**，不在这里抄一遍。
-- =============================================================================
INSERT INTO wms_pack_tasks
    (tenant_id, task_no, order_no, warehouse_id, location_code, task_type, status,
     box_no, container_type, pieces, gross_weight, volume, volumetric_weight, marks,
     is_dangerous, label_printed_at, handed_over_at, assignee_id, remark)
SELECT s.tenant_id,
       CONCAT('PK', DATE_FORMAT(s.created_at, '%Y%m%d'), SUBSTRING(s.order_no, -4)),
       s.order_no, s.warehouse_id, 'A-01-03', 'PACK',
       s.task_status,
       CASE WHEN s.task_status = 'PICKING' THEN '' ELSE CONCAT('CTN-', s.order_no, '-01') END,
       'CARTON', s.total_qty, s.total_weight, s.total_volume, s.volumetric_weight,
       CONCAT('N/M: ', s.consignee_company, '\nP/C: ', s.order_no, '\nHS CODE: ', IFNULL(s.hs_code, ''), '\nMADE IN CHINA'),
       s.is_dangerous,
       CASE WHEN s.task_status IN ('LABELLED', 'HANDED_OVER') THEN '2026-10-10 04:20:00' END,
       CASE WHEN s.task_status = 'HANDED_OVER' THEN '2026-10-10 06:40:00' END,
       1,
       CASE WHEN s.task_status = 'SHORTAGE' THEN '缺料：T 恤缺 80 件，等采购补货'
            WHEN s.task_status = 'PICKING'  THEN '备货中'
            WHEN s.task_status = 'PACKED'   THEN '已装箱，待贴标'
            WHEN s.task_status = 'LABELLED' THEN '已贴标，待交接承运人'
            ELSE '已装箱贴标并交接承运人' END
  FROM (
        SELECT o.tenant_id,
               o.order_no,
               o.created_at,
               o.consignee_company,
               o.total_qty,
               o.total_weight,
               o.total_volume,
               o.volumetric_weight,
               w.id AS warehouse_id,
               v.hs_code,
               v.is_dangerous,
               CASE
                   WHEN o.status = 'PICKING' THEN 'PICKING'
                   WHEN o.order_no = 'EXP20261010040' THEN 'SHORTAGE'
                   WHEN o.status = 'PACKED'
                        AND MOD(CAST(SUBSTRING(o.order_no, -4) AS UNSIGNED), 3) = 0 THEN 'LABELLED'
                   WHEN o.status = 'PACKED' THEN 'PACKED'
                   ELSE 'HANDED_OVER'
               END AS task_status
          FROM oms.oms_export_orders o
          JOIN wms_warehouses w ON w.tenant_id = 'alibaba' AND w.name = '宁波北仑出口仓'
          LEFT JOIN (SELECT order_id,
                            MAX(IFNULL(hs_code, ''))  AS hs_code,
                            MAX(is_dangerous)         AS is_dangerous
                       FROM oms.oms_export_order_items
                      WHERE tenant_id = 'alibaba'
                      GROUP BY order_id) v ON v.order_id = o.id
         WHERE o.tenant_id = 'alibaba'
       ) s
 WHERE NOT EXISTS (
       SELECT 1 FROM wms_pack_tasks t
        WHERE t.tenant_id = 'alibaba' AND t.order_no = s.order_no);

-- 上面那段只在「该子单还没有任务」时插入，已经跑过一次的老库不会因为改了派生规则而更新。
-- 用一条范围极窄的 UPDATE 收尾：只动「子单还在 PACKED、任务却已经 HANDED_OVER」的行，
-- 也就是本次要修的那类不自洽数据（子单还在装箱阶段，任务却说已交接承运人了）。
-- 写成 UPDATE 而不是 ON DUPLICATE KEY UPDATE，是因为页面上的操作员会改任务状态，
-- 种子不该在重跑时把人家改过的行整个盖回去。
-- 幂等：第二遍 affected rows = 0。
UPDATE wms_pack_tasks t
  JOIN oms.oms_export_orders o
    ON o.tenant_id = t.tenant_id AND o.order_no = t.order_no
   SET t.status = CASE
                       WHEN o.status = 'PACKED'
                            AND MOD(CAST(SUBSTRING(o.order_no, -4) AS UNSIGNED), 3) = 0 THEN 'LABELLED'
                       WHEN o.status = 'PACKED' THEN 'PACKED'
                       ELSE t.status
                   END,
       t.label_printed_at = CASE
                       WHEN o.status = 'PACKED'
                            AND MOD(CAST(SUBSTRING(o.order_no, -4) AS UNSIGNED), 3) = 0 THEN '2026-10-10 04:20:00'
                       WHEN o.status = 'PACKED' THEN NULL
                       ELSE t.label_printed_at
                   END,
       t.handed_over_at = CASE WHEN o.status = 'PACKED' THEN NULL ELSE t.handed_over_at END,
       t.remark = CASE
                       WHEN o.status = 'PACKED'
                            AND MOD(CAST(SUBSTRING(o.order_no, -4) AS UNSIGNED), 3) = 0 THEN '已贴标，待交接承运人'
                       WHEN o.status = 'PACKED' THEN '已装箱，待贴标'
                       ELSE t.remark
                   END
 WHERE t.tenant_id = 'alibaba'
   AND o.status = 'PACKED'
   AND t.status = 'HANDED_OVER';

-- =============================================================================
-- 十、WMS：备货任务明细（71 条）
-- 批次按 **FEFO**（先到期先出）从 wms_inventory_batches 里选该 SKU 效期最早且
-- 有在库量的那一条——这样任务明细里的批次号是**真能查到库存的批次**，
-- 而不是编出来的字符串。取不到批次（该 SKU 没在库）时留空，由人工补。
-- =============================================================================
INSERT INTO wms_pack_task_items
    (tenant_id, task_id, sku, product_name, batch_no, production_date, expiry_date,
     quantity, picked_quantity, hs_code)
SELECT 'alibaba', t.id, i.sku, i.product_name, IFNULL(b.batch_no, ''),
       IFNULL(b.production_date, '2026-09-01'), IFNULL(b.expiry_date, '2029-12-31'),
       i.quantity,
       CASE WHEN t.status = 'SHORTAGE' THEN GREATEST(i.quantity - 80, 0) ELSE i.quantity END,
       i.hs_code
  FROM wms_pack_tasks t
  JOIN oms.oms_export_orders o ON o.tenant_id = 'alibaba' AND o.order_no = t.order_no
  JOIN oms.oms_export_order_items i ON i.order_id = o.id
  LEFT JOIN wms_inventory_batches b
         ON b.tenant_id = 'alibaba' AND b.warehouse_id = t.warehouse_id AND b.sku = i.sku
        AND b.quantity > 0
        AND b.expiry_date = (SELECT MIN(b2.expiry_date)
                               FROM wms_inventory_batches b2
                              WHERE b2.tenant_id = 'alibaba'
                                AND b2.warehouse_id = t.warehouse_id
                                AND b2.sku = i.sku
                                AND b2.quantity > 0)
 WHERE t.tenant_id = 'alibaba'
   AND NOT EXISTS (
       SELECT 1 FROM wms_pack_task_items ti
        WHERE ti.task_id = t.id AND ti.sku = i.sku AND ti.quantity = i.quantity);

-- =============================================================================
-- 十一、WMS：在途库存
-- 由运单 + 子单 + 明细展开：运单不是 DRAFT（还没发出去）才有在途行。
-- to_warehouse_id 按目的国选：USLAX/USNYC → 洛杉矶仓；USNYC 优先纽约仓；
-- DEHAM → 汉堡仓；AMS → 留 0（欧洲清关后才有仓，M1 不预置）。
-- status 按运单状态映射：已开航及之后 → IN_TRANSIT；到港 → ARRIVED；
-- 已妥投 → RECEIVED；异常挂起 → **HELD**（海关查验扣留，货在）。
--
-- 异常挂起**不能**映射成 LOST：海关查验 ≠ 货物丢失。LOST 有理赔/索赔后果，
-- 把它当成「查验中」会凭空造出理赔事件，将来「丢件率」看板算出来的是查验率。
-- 真丢件另有其行（下面那张办公椅，40 → 39 ARRIVED + 1 LOST，对应 CRM 工单
-- TK202610030008「尾程签收少一件，已向 GOKU 索赔」），LOST 必须有真样本。
-- =============================================================================
INSERT INTO wms_in_transit
    (tenant_id, shipment_no, order_no, sku, product_name, batch_no, quantity,
     from_warehouse_id, to_warehouse_id, shipped_at, arrived_at, status, remark)
SELECT 'alibaba', s.shipment_no, o.order_no, i.sku, i.product_name,
       IFNULL(LEFT((SELECT pb.batch_no FROM wms_inventory_batches pb
                     WHERE pb.tenant_id = 'alibaba' AND pb.sku = i.sku AND pb.quantity > 0
                     ORDER BY pb.expiry_date LIMIT 1), 32), ''),
       i.quantity, wf.id,
       COALESCE(wl.id, wn.id, 0),
       COALESCE((SELECT MIN(n.node_time) FROM tms.tms_tracking_nodes n
                  WHERE n.tenant_id = 'alibaba' AND n.shipment_no = s.shipment_no
                    AND n.node_code = 'PICKED_UP'), '2026-10-11 06:40:00'),
       CASE WHEN s.status IN ('ARRIVED','CLEARING','CLEARED','LAST_MILE','DELIVERED','POD_CONFIRMED')
            THEN COALESCE((SELECT MIN(n.node_time) FROM tms.tms_tracking_nodes n
                            WHERE n.tenant_id = 'alibaba' AND n.shipment_no = s.shipment_no
                              AND n.node_code = 'ARRIVED'), s.actual_etd_at) END,
       CASE WHEN s.status = 'EXCEPTION' THEN 'HELD'
            WHEN s.status IN ('DELIVERED','POD_CONFIRMED') THEN 'RECEIVED'
            WHEN s.status IN ('ARRIVED','CLEARING','CLEARED','LAST_MILE') THEN 'ARRIVED'
            ELSE 'IN_TRANSIT' END,
       CASE WHEN s.status = 'EXCEPTION'
            THEN CONCAT('海关查验扣留：母单 ', s.shipment_no,
                        ' 处于 EXCEPTION（整批查验挂起），货在海关手里未丢失，不适用理赔流程')
            ELSE '' END
  FROM tms.tms_shipments s
  JOIN oms.oms_export_orders o ON o.tenant_id = 'alibaba' AND o.batch_no = s.batch_no
  JOIN oms.oms_export_order_items i ON i.order_id = o.id
  JOIN wms_warehouses wf ON wf.tenant_id = 'alibaba' AND wf.name = '宁波北仑出口仓'
  LEFT JOIN wms_warehouses wl ON wl.tenant_id = 'alibaba' AND wl.name = '洛杉矶海外仓'
  LEFT JOIN wms_warehouses wn ON wn.tenant_id = 'alibaba' AND wn.name = '纽约海外仓'
 WHERE s.tenant_id = 'alibaba'
   AND s.status <> 'DRAFT'
   AND NOT EXISTS (
       SELECT 1 FROM wms_in_transit it
        WHERE it.tenant_id = 'alibaba'
          AND it.shipment_no = s.shipment_no
          AND it.order_no = o.order_no
          AND it.sku = i.sku);

-- ---------------- 真丢件：让 LOST 有合法样本（否则它只装过「查验中」）
-- CRM 工单 TK202610030008（ABANDON，RESOLVED）写的是「尾程签收少一件，
-- 已向 GOKU 索赔，GOKU 确认赔付 1299.00 USD」——子单 EXP20261010026 的 40 把
-- 人体工学椅整行记成 ARRIVED（全部到仓）与工单自相矛盾。
-- 在途行一行只能一个状态，丢的那 1 件必须自己占一行：40 → 39 ARRIVED + 1 LOST。
-- 子单明细件数（40）与在途明细之和（39 + 1）因此重新守恒。
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

-- =============================================================================
-- 十二、CRM：出口异常工单（9 条）
-- 覆盖五种类别：清关查验 / 延误 / 破损 / 改单 / 弃货。
-- owner_id 给不同花名，让「我的工单」这类按 owner_id 的行级过滤有意义。
-- =============================================================================
USE crm;

INSERT INTO crm_tickets
    (tenant_id, ticket_no, customer_id, customer_name, order_no, shipment_no,
     category, severity, status, title, detail, resolution, owner_id, owner_name, due_at, resolved_at)
SELECT 'alibaba', v.ticket_no, IFNULL(c.id, 0), IFNULL(c.name, v.customer_name),
       v.order_no, v.shipment_no, v.category, v.severity, v.status, v.title, v.detail,
       v.resolution, v.owner_id, v.owner_name, v.due_at, v.resolved_at
  FROM (
        SELECT 'TK202610030001' AS ticket_no, '义乌市小商品城跨境卖家联盟' AS customer_name, 'EXP20261010006' AS order_no, 'SHP20261010002' AS shipment_no, 'CUSTOMS_HOLD' AS category, 'HIGH' AS severity, 'OPEN' AS status, 'FDA 查验待回复' AS title, 'FDA 要求提供 T 恤面料成分说明与染色牢度报告，已向客户索要。' AS detail, '' AS resolution, 82 AS owner_id, '逍遥子' AS owner_name, '2026-10-12 02:00:00' AS due_at, NULL AS resolved_at
        UNION ALL SELECT 'TK202610030002', '深圳前海优选跨境电商有限公司', 'EXP20261010001', 'SHP20261010001', 'DELAY', 'MEDIUM', 'FOLLOWING', '洋山港等泊位延迟' AS title, '船舶晚开 6 小时，预计影响后续船期衔接，已通知客户。' AS detail, '', 82, '逍遥子', '2026-10-16 01:00:00', NULL
        UNION ALL SELECT 'TK202610030003', '义乌市小商品城跨境卖家联盟', 'EXP20261010023', 'SHP20261010006', 'DAMAGE', 'HIGH', 'FOLLOWING', '陶瓷马克杯到货破损 12 件' AS title, '外箱受压，UPS 报告显示包装完好，客户提供了开箱视频。' AS detail, '', 82, '逍遥子', '2026-10-11 10:00:00', NULL
        UNION ALL SELECT 'TK202610030004', '上海速达国际货运代理有限公司', 'EXP20261010029', 'SHP20261010008', 'AMEND', 'MEDIUM', 'OPEN', '改单：收货人地址变更' AS title, '客户要求把 Amsterdam Trading BV 改成 Rotterdam Logistics。' AS detail, '', 85, '蓝河', '2026-10-09 10:00:00', NULL
        UNION ALL SELECT 'TK202610030005', '深圳鸿鹄供应链管理有限公司', 'EXP20261010040', 'SHP20261010011', 'CUSTOMS_HOLD', 'HIGH', 'FOLLOWING', '整批查验，客户要求弃货' AS title, '洋山港随机查验，客户在讨论是否弃货。' AS detail, '', 85, '蓝河', '2026-10-08 02:00:00', NULL
        UNION ALL SELECT 'TK202610030006', '广州希悦服饰出口有限公司', 'EXP20261010016', 'SHP20261010004', 'DELAY', 'LOW', 'RESOLVED', '20GP 订舱位确认延迟' AS title, '等船司放舱 2 天，已改订 10-17 航次。' AS detail, '改订 019S 航次，S/O ONEY3390218 已确认', 85, '蓝河', '2026-10-06 02:00:00', '2026-10-06 09:00:00'
        UNION ALL SELECT 'TK202610030007', '杭州星辰户外用品有限公司', 'EXP20261010019', 'SHP20261010005', 'DELAY', 'MEDIUM', 'OPEN', '欧线 MSC 尚未回 S/O' AS title, '订舱申请已发 5 天，MSC 客服未回。' AS detail, '', 86, '白露', '2026-10-14 10:00:00', NULL
        UNION ALL SELECT 'TK202610030008', '宁波甬佳家居用品有限公司', 'EXP20261010026', 'SHP20261010007', 'ABANDON', 'MEDIUM', 'RESOLVED', '一件办公椅在目的港丢失' AS title, '尾程签收少一件，已向 GOKU 索赔。' AS detail, 'GOKU 确认赔付 1299.00 USD', 86, '白露', '2026-10-15 10:00:00', '2026-10-02 06:00:00'
        UNION ALL SELECT 'TK202610030009', '3C Global Trading Co., Ltd.', 'EXP20261010033', 'SHP20261010009', 'AMEND', 'LOW', 'CLOSED', '提单补发' AS title, '客户需要 BL 副本用于清关。' AS detail, '已邮件发送 BL 副本', 87, '墨言', '2026-10-04 10:00:00', '2026-10-04 08:00:00'
       ) v
  LEFT JOIN customers c ON c.tenant_id = 'alibaba' AND c.name = v.customer_name
ON DUPLICATE KEY UPDATE
    status = VALUES(status), resolution = VALUES(resolution), resolved_at = VALUES(resolved_at);
