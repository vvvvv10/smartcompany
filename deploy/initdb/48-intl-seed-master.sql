-- =============================================================================
-- 48: 国际跨境物流主数据种子（承运商 / 航线 / 船期 / 仓库 / SKU / 客户 / 渠道商）
--
-- 演示基准日：2026-10-03（今天）。船期 ETD 覆盖 2026-10-05 ~ 2026-10-28，
-- 这样看板的「今日出港 / 未来 7 天 / 已逾期」三档都有数据。
--
-- 【待验证】的行业数据集中在这里，grep 「【待验证】」可以逐条列出：
--   · MAEU/COSCO/ONE/MSC/CMA/EK/CZ/UPS/FEDEX/DHL/SEAWAY 是真实承运商代码，
--     LAZYLION（捷 lion 国际货代）是**故意造的虚构货代**，避免与真实公司混淆；
--   · HS 编码按中国出口 10 位格式写，需按实际海关税则复核；
--   · 锂电池 UN3480/UN3481、PI966/PI967/PI969、class 9、瓦时 = Ah × V；
--   · 体积重除数空运 6000 / 快递 5000（各承运商运价表为准）；
--   · 税号与授信是**虚构演示值**，公司名除最后一家外均为虚构。
--
-- 幂等：承运商/航线/船期走唯一键 ON DUPLICATE KEY UPDATE；
--       仓库/SKU/客户没有业务唯一键，走「WHERE NOT EXISTS 按业务键判存在」。
--       重复执行行数不变（验收清单第 5 条）。
-- =============================================================================

-- =============================================================================
-- 一、TMS：承运商（12 条）
-- =============================================================================
USE tms;

INSERT INTO tms_carriers
    (tenant_id, code, name_zh, name_en, carrier_type, country, contact_name, contact_phone,
     contact_email, service_level, transit_days, cut_off_hours, free_time_days, status, remark)
VALUES
    ('alibaba', 'MAEU', '马士基航运', 'Maersk', 'OCEAN', 'DK', '孙倩', '13800000011',
     'user47@example.com', 'STANDARD', 18, 48, 14, 'ACTIVE', '美线主力船司'),
    ('alibaba', 'COSCO', '中远海运集运', 'COSCO Shipping', 'OCEAN', 'CN', '王海', '13800000012',
     'user16@example.com', 'STANDARD', 16, 48, 10, 'ACTIVE', '美西/地中海线'),
    ('alibaba', 'ONE', '海洋网联船务', 'Ocean Network Express', 'OCEAN', 'JP', 'Kobayashi', '13800000013',
     'user56@example.com', 'STANDARD', 15, 72, 14, 'ACTIVE', '亚洲区内与美湾'),
    ('alibaba', 'MSC', '地中海航运', 'MSC', 'OCEAN', 'CH', '李蔚', '13800000014',
     'user52@example.com', 'STANDARD', 20, 48, 10, 'ACTIVE', '欧线'),
    ('alibaba', 'CMA', '达飞轮船', 'CMA CGM', 'OCEAN', 'FR', 'Marc', '13800000015',
     'user12@example.com', 'STANDARD', 19, 48, 12, 'ACTIVE', '欧线与美南'),
    ('alibaba', 'EK', '阿联酋航空', 'Emirates SkyCargo', 'AIR', 'AE', 'Ahmed', '13800000016',
     'user20@example.com', 'EXPRESS', 2, 24, 0, 'ACTIVE', '中东中转空运快线'),
    ('alibaba', 'CZ', '中国南方航空货机', 'China Southern Cargo', 'AIR', 'CN', '周敏', '13800000017',
     'user17@example.com', 'STANDARD', 3, 24, 0, 'ACTIVE', '华南始发'),
    ('alibaba', 'UPS', '联合包裹', 'UPS', 'EXPRESS', 'US', 'Linda', '13800000018',
     'user73@example.com', 'EXPRESS', 5, 12, 0, 'ACTIVE', '门到门快递'),
    ('alibaba', 'FEDEX', '联邦快递', 'FedEx', 'EXPRESS', 'US', 'Chris', '13800000019',
     'user26@example.com', 'EXPRESS', 5, 12, 0, 'ACTIVE', '大件/危险品件'),
    ('alibaba', 'DHL', '敦豪快递', 'DHL', 'EXPRESS', 'DE', 'Klaus', '13800000020',
     'user19@example.com', 'EXPRESS', 6, 12, 0, 'ACTIVE', '欧洲件'),
    ('alibaba', 'SEAWAY', '环球速运（无船承运人）', 'Seaway Express', 'NVOCC', 'HK', '陈启', '13800000021',
     'user63@example.com', 'STANDARD', 25, 72, 7, 'ACTIVE', '拼箱与散货'),
    ('alibaba', 'LAZYLION', '捷 lion 国际货代', 'LazyLion Forwarding', 'FORWARDER', 'CN', 'lion', '13800000022',
     'user14@example.com', 'STANDARD', 22, 72, 7, 'ACTIVE', '虚构货代，仅作演示')
ON DUPLICATE KEY UPDATE
    name_zh = VALUES(name_zh), name_en = VALUES(name_en), carrier_type = VALUES(carrier_type),
    transit_days = VALUES(transit_days), cut_off_hours = VALUES(cut_off_hours),
    free_time_days = VALUES(free_time_days), status = VALUES(status);

-- =============================================================================
-- 二、TMS：航线（8 条）
-- carrier_id 用子查询按 code 取——避免把承运商的自增 id 写死在种子里
-- （id 会因部署顺序不同而不同，写死就是一颗定时炸弹）。
-- =============================================================================
INSERT INTO tms_routes
    (tenant_id, route_code, carrier_id, mode, pol_code, pod_code, transit_days,
     volumetric_divisor, via_ports, status, remark)
SELECT 'alibaba', v.route_code, c.id, v.mode, v.pol_code, v.pod_code, v.transit_days,
       v.volumetric_divisor, v.via_ports, 'ACTIVE', v.remark
  FROM (
        SELECT 'CNSHA-USLAX'     AS route_code, 'COSCO' AS carrier, 'SEA' AS mode, 'CNSHA' AS pol_code, 'USLAX' AS pod_code, 16 AS transit_days, 6000 AS volumetric_divisor, '' AS via_ports, '美西快线' AS remark
        UNION ALL SELECT 'CNSHA-USNYC', 'MAEU', 'SEA', 'CNSHA', 'USNYC', 18, 6000, '', '美东线'
        UNION ALL SELECT 'CNNGB-USDAL', 'ONE',  'SEA', 'CNNGB', 'USDAL', 20, 6000, '', '宁波美湾'
        UNION ALL SELECT 'CNSZX-DEHAM', 'MSC', 'SEA', 'CNSZX', 'DEHAM', 30, 6000, 'SGSIN', '欧线经新加坡中转'
        UNION ALL SELECT 'CNTAU-USLAX', 'CMA', 'SEA', 'CNTAU', 'USLAX', 17, 6000, '', '青岛美西'
        UNION ALL SELECT 'PVG-ORD',     'EK',   'AIR', 'PVG',   'ORD',     3, 6000, 'DXB',  '【待验证】体积重除数 6000 cm³/kg'
        UNION ALL SELECT 'CAN-AMS',     'CZ',   'AIR', 'CAN',   'AMS',     4, 6000, '',     '华南经阿姆斯特丹'
        UNION ALL SELECT 'CNSHA-USLAX-EXP', 'UPS', 'EXPRESS', 'CNSHA', 'USLAX', 5, 5000, '', '快递件【待验证】体积重除数 5000'
       ) v
  JOIN tms_carriers c ON c.tenant_id = 'alibaba' AND c.code = v.carrier
ON DUPLICATE KEY UPDATE
    carrier_id = VALUES(carrier_id), transit_days = VALUES(transit_days),
    volumetric_divisor = VALUES(volumetric_divisor), status = VALUES(status);

-- =============================================================================
-- 三、TMS：船期（16 条）
-- uk_route_etd(tenant_id, route_id, etd_at) 保证同航线同 ETD 只有一条，
-- 是本段幂等的唯一键。所有 DATETIME 字面量按 UTC 写：
-- '2026-10-14 01:00:00' = 北京时间 10-14 09:00。
-- =============================================================================
INSERT INTO tms_sailings
    (tenant_id, route_id, vessel_name, voyage_no, etd_at, eta_at, cutoff_at,
     free_time_days, space_left, status, remark)
SELECT 'alibaba', r.id, v.vessel_name, v.voyage_no, v.etd_at, v.eta_at, v.cutoff_at,
       v.free_time_days, v.space_left, 'SCHEDULED', v.remark
  FROM (
        SELECT 'COSCO ARIES'      AS vessel_name, 'V.034E'  AS voyage_no, '2026-10-14 01:00:00' AS etd_at, '2026-10-30 08:00:00' AS eta_at, '2026-10-12 09:00:00' AS cutoff_at, 10 AS free_time_days, 6  AS space_left, 'CNSHA-USLAX' AS route_code, '美西周班' AS remark
        UNION ALL SELECT 'EVER LOADING',  'V.0126E', '2026-10-21 01:00:00', '2026-11-06 08:00:00', '2026-10-19 09:00:00', 10, 12, 'CNSHA-USLAX', '美西周班'
        UNION ALL SELECT 'COSCO UNIVERSE','V.026E',  '2026-10-28 01:00:00', '2026-11-13 08:00:00', '2026-10-26 09:00:00', 10, 15, 'CNSHA-USLAX', '美西周班'
        UNION ALL SELECT 'XIN QING DAO',  'V.019E',  '2026-10-05 01:00:00', '2026-10-21 08:00:00', '2026-10-03 09:00:00', 10, 2,  'CNSHA-USLAX', '已截关'
        UNION ALL SELECT 'MAERSK SEVILLE','642W',     '2026-10-12 06:00:00', '2026-10-30 14:00:00', '2026-10-10 14:00:00', 14, 8,  'CNSHA-USNYC', '美东周班'
        UNION ALL SELECT 'MAERSK LIMA',  '639W',     '2026-10-19 06:00:00', '2026-11-06 14:00:00', '2026-10-17 14:00:00', 14, 11, 'CNSHA-USNYC', '美东周班'
        UNION ALL SELECT 'ONE COLUMBA',  '019S',     '2026-10-17 03:00:00', '2026-11-06 09:00:00', '2026-10-14 15:00:00', 14, 4,  'CNNGB-USDAL', '美湾周班'
        UNION ALL SELECT 'ONE HONOR',    '012S',     '2026-10-24 03:00:00', '2026-11-13 09:00:00', '2026-10-21 15:00:00', 14, 9,  'CNNGB-USDAL', '美湾周班'
        UNION ALL SELECT 'MSC LISA',     'FA034W',   '2026-10-21 02:00:00', '2026-11-20 07:00:00', '2026-10-19 10:00:00', 10, 7,  'CNSZX-DEHAM', '欧线经新加坡'
        UNION ALL SELECT 'MSC ISTANBUL', 'FA035W',   '2026-10-28 02:00:00', '2026-11-27 07:00:00', '2026-10-26 10:00:00', 10, 10, 'CNSZX-DEHAM', '欧线经新加坡'
        UNION ALL SELECT 'CMA CGM LYON','0FA7WE1',   '2026-10-06 05:00:00', '2026-10-23 11:00:00', '2026-10-04 13:00:00', 12, 5,  'CNTAU-USLAX', '青岛美西'
        UNION ALL SELECT 'CMA CGM MARSEILLE','0FA8WE1','2026-10-13 05:00:00','2026-10-30 11:00:00','2026-10-11 13:00:00',12, 9, 'CNTAU-USLAX', '青岛美西'
        UNION ALL SELECT 'EK302 货机',  'EK0302',   '2026-10-11 22:00:00', '2026-10-14 04:00:00', '2026-10-11 04:00:00', 0,  60, 'PVG-ORD',     '【待验证】AWB 前缀 176'
        UNION ALL SELECT 'EK304 货机',  'EK0304',   '2026-10-13 22:00:00', '2026-10-16 04:00:00', '2026-10-13 04:00:00', 0,  55, 'PVG-ORD',     '【待验证】AWB 前缀 176'
        UNION ALL SELECT 'CZ353 货机',  'CZ0353',   '2026-10-13 02:00:00', '2026-10-17 08:00:00', '2026-10-12 06:00:00', 0,  40, 'CAN-AMS',     '【待验证】AWB 前缀 784'
        UNION ALL SELECT 'CZ355 货机',  'CZ0355',   '2026-10-15 02:00:00', '2026-10-19 08:00:00', '2026-10-14 06:00:00', 0,  45, 'CAN-AMS',     '【待验证】AWB 前缀 784'
       ) v
  JOIN tms_routes r ON r.tenant_id = 'alibaba' AND r.route_code = v.route_code
ON DUPLICATE KEY UPDATE
    vessel_name = VALUES(vessel_name), voyage_no = VALUES(voyage_no),
    eta_at = VALUES(eta_at), space_left = VALUES(space_left);

-- =============================================================================
-- 四、WMS：国际仓（6 个新仓，不动现有北京/上海/广州三个）
-- wms_warehouses 没有业务唯一键，按 name 判存在。
-- =============================================================================
USE wms;

INSERT INTO wms_warehouses
    (tenant_id, name, location, capacity, status, remark, warehouse_type, country, timezone, oversea_operator, is_bonded)
SELECT 'alibaba', v.name, v.location, v.capacity, 'ACTIVE', v.remark, v.warehouse_type, v.country, v.timezone, v.oversea_operator, v.is_bonded
  FROM (
        SELECT '深圳前海保税仓'   AS name, '广东省深圳市宝安区前海' AS location, 5000 AS capacity, '保税仓，账册货物走此仓' AS remark, 'BONDED' AS warehouse_type, 'CN' AS country, 'Asia/Shanghai' AS timezone, '' AS oversea_operator, 1 AS is_bonded
        UNION ALL SELECT '宁波北仑出口仓', '浙江省宁波市北仑区', 6000, '美湾航线集货仓', 'DOMESTIC', 'CN', 'Asia/Shanghai', '', 0
        UNION ALL SELECT '义乌集货仓',     '浙江省金华市义乌市',   8000, '小商品城跨境集货', 'DOMESTIC', 'CN', 'Asia/Shanghai', '', 0
        UNION ALL SELECT '洛杉矶海外仓',   '90021 Los Angeles, CA', 4000, '美西海外仓与尾程起点', 'OVERSEAS', 'US', 'America/Los_Angeles', '谷仓海外仓', 0
        UNION ALL SELECT '纽约海外仓',     '10018 New York, NY',   3000, '美东海外仓与尾程起点', 'OVERSEAS', 'US', 'America/New_York', '谷仓海外仓', 0
        UNION ALL SELECT '德国汉堡海外仓', '20457 Hamburg',         2500, '欧线海外仓', 'OVERSEAS', 'DE', 'Europe/Berlin', '汉堡海外仓', 0
       ) v
 WHERE NOT EXISTS (
       SELECT 1 FROM wms_warehouses w
        WHERE w.tenant_id = 'alibaba' AND w.name = v.name);

-- =============================================================================
-- 五、OMS：12 个国际 SKU（不动现有 9 条服装款号）
-- 按 sku 判存在——oms_products 上没有业务唯一键。
-- =============================================================================
USE oms;

INSERT INTO oms_products
    (tenant_id, style_no, name, category, season, color, size, sku, price, status, remark,
     hs_code, customs_name, customs_name_en, origin_country, is_dangerous, un_number, dg_class,
     packing_instruction, battery_watt_hours, net_weight, gross_weight, volume_cbm)
SELECT 'alibaba', v.style_no, v.name, 'OUTER', '四季', v.color, '', v.sku, v.price, 'ON', v.remark,
       v.hs_code, v.customs_name, v.customs_name_en, 'CN', v.is_dangerous, v.un_number, v.dg_class,
       v.packing_instruction, v.battery_watt_hours, v.net_weight, v.gross_weight, v.volume_cbm
  FROM (
        SELECT 'IPH-15-BLK'   AS style_no, 'iPhone 15 Pro 256G 黑' AS name, '黑' AS color, 'IPH-15-BLK' AS sku, 7999.00 AS price, '含锂电池，需 MSDS' AS remark, '8517130000' AS hs_code, '手机' AS customs_name, 'Mobile Phone' AS customs_name_en, 1 AS is_dangerous, 'UN3481' AS un_number, '9' AS dg_class, 'PI967' AS packing_instruction, 17.30 AS battery_watt_hours, 0.450 AS net_weight, 0.480 AS gross_weight, 0.0025 AS volume_cbm
        UNION ALL SELECT 'IPH-15-BLU',   'iPhone 15 Pro 256G 蓝', '蓝', 'IPH-15-BLU',   7999.00, '', '8517130000', '手机', 'Mobile Phone', 1, 'UN3481', '9', 'PI967', 17.30, 0.450, 0.480, 0.0025
        UNION ALL SELECT 'AIRP-2-WHT',   'AirPods Pro 2',          '白', 'AIRP-2-WHT',  1899.00, '', '8518301000', '耳机', 'Earphone', 1, 'UN3481', '9', 'PI967', 4.40, 0.060, 0.070, 0.0004
        UNION ALL SELECT 'WATCH-S9-BLK', 'Apple Watch S9 黑',     '黑', 'WATCH-S9-BLK',2999.00, '', '8517120000', '智能手表', 'Smart Watch', 1, 'UN3481', '9', 'PI967', 1.90, 0.050, 0.060, 0.0003
        UNION ALL SELECT 'MBP-14-SLV',   'MacBook Pro 14 银色',   '银', 'MBP-14-SLV', 14999.00, '大容量电池，单件瓦时接近 100Wh 阈值【待验证】', '8471300000', '笔记本电脑', 'Laptop Computer', 1, 'UN3481', '9', 'PI967', 72.40, 1.600, 1.700, 0.0032
        UNION ALL SELECT 'IPAD-AIR-64G', 'iPad Air 64G',          '深空灰', 'IPAD-AIR-64G', 4799.00, '', '8517120000', '平板电脑', 'Tablet', 1, 'UN3481', '9', 'PI967', 21.50, 0.460, 0.500, 0.0015
        UNION ALL SELECT 'TEE-COT-M-WHT', '精梳棉圆领 T 恤 M 白', '白', 'TEE-COT-M-WHT', 89.00, '', '6109100021', '针织棉制T恤', 'Knitted Cotton T-Shirt', 0, '', '', '', 0.00, 0.180, 0.200, 0.0018
        UNION ALL SELECT 'TEE-COT-M-BLU', '精梳棉圆领 T 恤 M 蓝', '蓝', 'TEE-COT-M-BLU', 89.00, '', '6109100021', '针织棉制T恤', 'Knitted Cotton T-Shirt', 0, '', '', '', 0.00, 0.180, 0.200, 0.0018
        UNION ALL SELECT 'DENIM-JKT-L',   '水洗牛仔外套 L',       '蓝', 'DENIM-JKT-L',  399.00, '', '6202130000', '男式棉外套', 'Men''s Cotton Jacket', 0, '', '', '', 0.00, 0.720, 0.800, 0.0045
        UNION ALL SELECT 'CERAMIC-MUG',   '陶瓷马克杯 350ml',      '白', 'CERAMIC-MUG', 59.00, '易碎品，需贴 FRAGILE 标', '6912001000', '陶瓷餐具', 'Ceramic Tableware', 0, '', '', '', 0.00, 0.420, 0.480, 0.0012
        UNION ALL SELECT 'CHAIR-ERGO',    '人体工学椅',            '黑', 'CHAIR-ERGO',  1299.00, '大件货，走海运', '9401790000', '办公椅', 'Office Chair', 0, '', '', '', 0.00, 12.500, 13.200, 0.2800
        UNION ALL SELECT 'POWER-100W',    '移动电源 20000mAh',     '黑', 'POWER-100W',  299.00, 'UN3480 单独装运【待验证】20000mAh × 3.7V ≈ 74Wh', '8507600090', '锂离子蓄电池', 'Lithium Ion Battery', 1, 'UN3480', '9', 'PI967', 74.00, 0.420, 0.480, 0.0012
       ) v
 WHERE NOT EXISTS (
       SELECT 1 FROM oms_products p
        WHERE p.tenant_id = 'alibaba' AND p.sku = v.sku);

-- =============================================================================
-- 六、CRM：8 个国际客户（不动现有 5 个国内客户）
-- owner_id 统一给 1 号演示账号（ADMIN），与 22 号种子的归属人口径一致。
-- 税号/授信是**虚构演示值**。
-- =============================================================================
USE crm;

INSERT INTO customers
    (tenant_id, name, industry, level, source, status, phone, email, address, remark,
     owner_id, owner_name, customer_type, country, tax_no, payment_term_days,
     credit_limit, credit_used, default_currency, ioss_no)
SELECT 'alibaba', v.name, '跨境电商', v.level, '国际物流展会', v.status, v.phone, v.email, v.address, v.remark,
       1, '风清扬', v.customer_type, v.country, v.tax_no, v.payment_term_days,
       v.credit_limit, v.credit_used, 'USD', v.ioss_no
  FROM (
        SELECT '深圳前海优选跨境电商有限公司' AS name, 'KEY' AS level, 'DEAL' AS status, '075588880001' AS phone, 'user62@example.com' AS email, '广东省深圳市南山区前海路 1 号' AS address, '3C 类主力客户，美西整柜月均 2 柜' AS remark, 'SELLER' AS customer_type, 'CN' AS country, '91440300MA5EX8Q71K' AS tax_no, 30 AS payment_term_days, 500000.00 AS credit_limit, 185000.00 AS credit_used, '' AS ioss_no
        UNION ALL SELECT '义乌市小商品城跨境卖家联盟', 'KEY', 'DEAL', '057988880002', 'user72@example.com', '浙江省金华市义乌市国际商贸城', '拼箱与快递件为主，账期 15 天', 'SELLER', 'CN', '91330782MA2AB3XY1D', 15, 200000.00, 96000.00, ''
        UNION ALL SELECT '广州希悦服饰出口有限公司', 'NORMAL', 'FOLLOWING', '020388880003', 'user31@example.com', '广东省广州市白云区石井大道', '服饰类出口，试单阶段', 'SELLER', 'CN', '91440101MA9UY2L08P', 0, 50000.00, 0.00, ''
        UNION ALL SELECT '杭州星辰户外用品有限公司', 'NORMAL', 'FOLLOWING', '057188880004', 'user76@example.com', '浙江省杭州市余杭区仓前街道', '户外家具，欧线为主', 'SELLER', 'CN', '91330106MA2C7K93T5', 30, 120000.00, 42000.00, ''
        UNION ALL SELECT '宁波甬佳家居用品有限公司', 'NORMAL', 'DEAL', '057488880005', 'user77@example.com', '浙江省宁波市北仑区', '家居工厂自营出口，账期 45 天', 'FACTORY', 'CN', '91330206MA7J5X2R9W', 45, 300000.00, 240000.00, ''
        UNION ALL SELECT '上海速达国际货运代理有限公司', 'NORMAL', 'DEAL', '021588880006', 'user69@example.com', '上海市虹山区东大名路', '同业货代，空运渠道为主', 'FORWARDER', 'CN', '91310115MA1H8P3K7C', 30, 80000.00, 31000.00, ''
        UNION ALL SELECT '深圳鸿鹄供应链管理有限公司', 'LOW', 'FOLLOWING', '075588880007', 'user32@example.com', '广东省深圳市宝安区', '小批量拼箱，额度小', 'DISTRIBUTOR', 'CN', '91440300MA5FL6T21N', 0, 20000.00, 0.00, ''
        UNION ALL SELECT '3C Global Trading Co., Ltd.', 'KEY', 'DEAL', '+1-310-555-0188', 'user57@example.com', '1201 S Santa Fe Ave, Los Angeles, CA 90021', '境外买家演示数据，款到发货', 'SELLER', 'US', 'US-EIN 47-3829104', 0, 150000.00, 0.00, 'IM123456789'
       ) v
 WHERE NOT EXISTS (
       SELECT 1 FROM customers c
        WHERE c.tenant_id = 'alibaba' AND c.name = v.name);

-- =============================================================================
-- 七、CRM：渠道商（6 条）
-- 与 tms_carriers 并存是有意的：那边管运输能力（时效/截关/免堆存），
-- 这边管合作关系（结算币种/账期/授信/评级）。
--
-- carrier_code 指出「这家渠道商背后是哪家承运商」，只填**能自证**的两家
-- （LAZYLION / SEAWAY 在 tms_carriers 里用的是同一个 code）。
-- SPD 报关行、GOKU 海外仓、ROADRUNNER 卡航本来就不是承运商，留空——
-- 留空是事实，硬凑一个承运商代码才是编数据。
-- =============================================================================
INSERT INTO crm_carrier_partners
    (tenant_id, partner_code, carrier_code, partner_name, partner_type, contact_name, contact_phone,
     contact_email, country, currency, payment_term_days, credit_limit, credit_used,
     rating, status, remark)
VALUES
    ('alibaba', 'LAZYLION', 'LAZYLION', '捷 lion 国际货代', 'FORWARDER', 'lion', '13800000022',
     'user14@example.com', 'CN', 'CNY', 30, 200000.00, 42000.00, 4, 'ACTIVE', '虚构货代，仅作演示'),
    ('alibaba', 'SEAWAY', 'SEAWAY', '环球速运', 'NVOCC', '陈启', '13800000021',
     'user63@example.com', 'HK', 'USD', 15, 150000.00, 0.00, 3, 'ACTIVE', '拼箱与散货'),
    ('alibaba', 'SPD', '', '速通关报关行', 'CLEARANCE_BROKER', '关经理', '13800000023',
     'user68@example.com', 'CN', 'CNY', 0, 50000.00, 12000.00, 5, 'ACTIVE', '出口报关与改单'),
    ('alibaba', 'FREIGHTPRO', '', '飞通国际代理', 'AGENT', 'Fly', '13800000024',
     'user02@example.com', 'HK', 'USD', 30, 120000.00, 0.00, 3, 'ACTIVE', '货代代理'),
    ('alibaba', 'GOKU', '', '谷仓海外仓', 'LAST_MILE', '谷仓客服', '13800000025',
     'user65@example.com', 'US', 'USD', 30, 80000.00, 26000.00, 4, 'ACTIVE', '海外仓与尾程派送'),
    ('alibaba', 'ROADRUNNER', '', '路歌卡航', 'TRUCKING', '路歌调度', '13800000026',
     'user59@example.com', 'CN', 'CNY', 15, 60000.00, 0.00, 3, 'ACTIVE', '中欧卡航')
ON DUPLICATE KEY UPDATE
    partner_name = VALUES(partner_name), partner_type = VALUES(partner_type),
    carrier_code = VALUES(carrier_code),
    payment_term_days = VALUES(payment_term_days), credit_limit = VALUES(credit_limit),
    rating = VALUES(rating), status = VALUES(status);
