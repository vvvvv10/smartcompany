-- =============================================================================
-- 39: OMS/WMS/TMS 三系统按 OMS 订单号关联
--
-- 关联键 = 统一 OMS 订单号：TMS 运单的 order_no 直接存 OMS 订单号（结构零改，
-- 只改存量种子数据），WMS 出入库记录新增 order_no 列挂 OMS 订单号。
--
--   一、WMS：wms_stock_records 加 order_no（VARCHAR(32) NOT NULL DEFAULT ''）
--       + 普通索引 idx_order_no——OMS 列表聚合与 WMS 按单查询都走它；
--   二、TMS 种子对齐：两条有 OMS 对应单的运单改成 OMS 订单号并同步
--       customer/destination；PENDING 的 TMS20261001003 保留原样，
--       演示「手工建的运单无 OMS 关联」（前端显示 —）；
--   三、WMS 种子对齐：两条出库记录挂上 OMS 订单号，商品/数量改成与
--       OMS 订单明细吻合（50 件基础圆领T恤 / 2 件直筒牛仔裤），入库记录不关联。
--
-- 幂等：ALTER 为一次性（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS），
--       重复执行报 Duplicate column，不影响已执行实例；UPDATE 均按主键/单号锚定，可重复执行。
-- =============================================================================

-- ---------------- 一、WMS 加列 + 索引 ----------------
USE wms;

ALTER TABLE wms_stock_records
    ADD COLUMN order_no VARCHAR(32) NOT NULL DEFAULT '' COMMENT '关联OMS订单号' AFTER remark,
    ADD KEY idx_order_no (order_no);

-- ---------------- 二、TMS 种子对齐 OMS ----------------
USE tms;

-- OMS20261001003（DONE，广州白马服装商行，广州站前路128号，50件）← 已送达运单
UPDATE tms_orders
   SET order_no = 'OMS20261001003',
       customer_name = '广州白马服装商行',
       destination = '广东省广州市站前路128号'
 WHERE order_no = 'TMS20261001001';

-- OMS20261002002（SHIPPED，李先生，深圳南山，2件）← 运输中运单
UPDATE tms_orders
   SET order_no = 'OMS20261002002',
       customer_name = '李先生',
       destination = '广东省深圳市南山区'
 WHERE order_no = 'TMS20261001002';

-- TMS20261001003（PENDING）不改：演示无 OMS 关联的独立运单

-- ---------------- 三、WMS 种子出库记录对齐 OMS ----------------
USE wms;

-- id=2：OMS20261001003 批发单 50 件 K2601 基础圆领T恤
UPDATE wms_stock_records
   SET order_no = 'OMS20261001003',
       product_name = '基础圆领T恤',
       remark = 'OMS订单 OMS20261001003 发货出库'
 WHERE id = 2;

-- id=4：OMS20261002002 淘宝单 2 件 K2602 直筒牛仔裤
UPDATE wms_stock_records
   SET order_no = 'OMS20261002002',
       product_name = '直筒牛仔裤',
       quantity = 2,
       remark = 'OMS订单 OMS20261002002 发货出库'
 WHERE id = 4;

-- 入库记录（id=1、3）不关联，order_no 保持默认 ''
