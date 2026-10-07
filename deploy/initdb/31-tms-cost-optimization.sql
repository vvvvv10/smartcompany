-- TMS 成本优化：车辆成本字段 + 派车推荐
USE tms;

-- 车辆加成本字段
ALTER TABLE tms_vehicles
    ADD COLUMN cost_per_km DECIMAL(10,2) DEFAULT 3.50 COMMENT '每公里成本（元）',
    ADD COLUMN daily_fixed_cost DECIMAL(10,2) DEFAULT 200.00 COMMENT '每日固定成本（元，含司机工资/折旧/保险）',
    ADD COLUMN is_external TINYINT(1) DEFAULT 0 COMMENT '是否外部车辆（0自有 1外部如货拉拉）';

-- 订单加距离和成本字段
ALTER TABLE tms_orders
    ADD COLUMN distance_km DECIMAL(10,2) DEFAULT 0 COMMENT '运输距离（公里）',
    ADD COLUMN cost DECIMAL(10,2) DEFAULT 0 COMMENT '实际成本（元）';

-- 更新种子数据
UPDATE tms_vehicles SET cost_per_km = 3.50, daily_fixed_cost = 200.00, is_external = 0 WHERE id = 1;
UPDATE tms_vehicles SET cost_per_km = 4.00, daily_fixed_cost = 180.00, is_external = 0 WHERE id = 2;
UPDATE tms_vehicles SET cost_per_km = 5.00, daily_fixed_cost = 0.00, is_external = 1 WHERE id = 3;

UPDATE tms_orders SET distance_km = 1200.00, cost = 4200.00 WHERE id = 1;
UPDATE tms_orders SET distance_km = 300.00, cost = 1200.00 WHERE id = 2;
UPDATE tms_orders SET distance_km = 200.00, cost = 800.00 WHERE id = 3;

-- 订单关联车辆（成本统计按车辆归属汇总）
UPDATE tms_orders SET vehicle_id = 1 WHERE id = 1;
UPDATE tms_orders SET vehicle_id = 3 WHERE id = 2;
UPDATE tms_orders SET vehicle_id = 2 WHERE id = 3;
