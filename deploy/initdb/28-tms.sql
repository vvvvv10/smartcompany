-- TMS 运输管理服务
CREATE DATABASE IF NOT EXISTS tms DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE tms;

CREATE TABLE IF NOT EXISTS tms_orders (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_no VARCHAR(32) NOT NULL COMMENT '订单编号',
    customer_name VARCHAR(64) NOT NULL COMMENT '客户名称',
    origin VARCHAR(128) NOT NULL COMMENT '起点',
    destination VARCHAR(128) NOT NULL COMMENT '终点',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/IN_TRANSIT/DELIVERED/CANCELLED',
    driver_id BIGINT DEFAULT NULL,
    vehicle_id BIGINT DEFAULT NULL,
    remark VARCHAR(255) DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运输订单';

CREATE TABLE IF NOT EXISTS tms_vehicles (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plate_no VARCHAR(16) NOT NULL COMMENT '车牌号',
    type VARCHAR(32) DEFAULT '' COMMENT '车型',
    status VARCHAR(16) NOT NULL DEFAULT 'IDLE' COMMENT 'IDLE/BUSY/MAINTENANCE',
    driver_name VARCHAR(64) DEFAULT '',
    remark VARCHAR(255) DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_plate_no (plate_no),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='车辆';

CREATE TABLE IF NOT EXISTS tms_drivers (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL COMMENT '姓名',
    phone VARCHAR(32) DEFAULT '',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
    remark VARCHAR(255) DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='司机';

-- 种子数据：前两单的 order_no 存 OMS 订单号（39 号关联方案，运单↔OMS 订单同号），
-- 第三单保留 TMS 手工单号，演示无 OMS 关联的独立运单。
INSERT INTO tms_orders (order_no, customer_name, origin, destination, status) VALUES
('OMS20261001003', '广州白马服装商行', '北京', '广东省广州市站前路128号', 'DELIVERED'),
('OMS20261002002', '李先生', '广州', '广东省深圳市南山区', 'IN_TRANSIT'),
('TMS20261001003', '红星制造', '成都', '重庆', 'PENDING');

INSERT INTO tms_vehicles (plate_no, type, status, driver_name) VALUES
('京A12345', '厢式货车', 'BUSY', '张三'),
('沪B67890', '冷藏车', 'IDLE', '李四'),
('粤C11111', '平板车', 'MAINTENANCE', '');

INSERT INTO tms_drivers (name, phone, status) VALUES
('张三', '13800000006', 'ACTIVE'),
('李四', '13800000006', 'ACTIVE'),
('王五', '13800000006', 'INACTIVE');
