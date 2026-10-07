-- WMS 仓储管理服务
CREATE DATABASE IF NOT EXISTS wms DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE wms;

CREATE TABLE IF NOT EXISTS wms_warehouses (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL COMMENT '仓库名称',
    location VARCHAR(128) DEFAULT '' COMMENT '位置',
    capacity INT DEFAULT 0 COMMENT '容量',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
    remark VARCHAR(255) DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库';

CREATE TABLE IF NOT EXISTS wms_inventory (
    id BIGINT NOT NULL AUTO_INCREMENT,
    warehouse_id BIGINT NOT NULL COMMENT '仓库ID',
    product_name VARCHAR(128) NOT NULL COMMENT '商品名称',
    sku VARCHAR(64) NOT NULL COMMENT 'SKU',
    quantity INT NOT NULL DEFAULT 0 COMMENT '数量',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_warehouse_sku (warehouse_id, sku),
    KEY idx_warehouse (warehouse_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存';

CREATE TABLE IF NOT EXISTS wms_stock_records (
    id BIGINT NOT NULL AUTO_INCREMENT,
    warehouse_id BIGINT NOT NULL COMMENT '仓库ID',
    product_name VARCHAR(128) NOT NULL COMMENT '商品名称',
    type VARCHAR(8) NOT NULL COMMENT 'IN/OUT',
    quantity INT NOT NULL COMMENT '数量',
    remark VARCHAR(255) DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_warehouse (warehouse_id),
    KEY idx_type (type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出入库记录';

-- 种子数据
INSERT INTO wms_warehouses (name, location, capacity, status) VALUES
('北京仓', '北京市大兴区', 10000, 'ACTIVE'),
('上海仓', '上海市青浦区', 8000, 'ACTIVE'),
('广州仓', '广州市黄埔区', 6000, 'ACTIVE');

INSERT INTO wms_inventory (warehouse_id, product_name, sku, quantity) VALUES
(1, '笔记本电脑', 'NB-001', 500),
(1, '手机', 'PH-001', 1200),
(2, '笔记本电脑', 'NB-001', 300),
(2, '平板电脑', 'TB-001', 800),
(3, '手机', 'PH-001', 600);

-- 出入库记录：order_no 列由 39 号迁移补齐并回填关联的 OMS 订单号
INSERT INTO wms_stock_records (warehouse_id, product_name, type, quantity, remark) VALUES
(1, '笔记本电脑', 'IN', 100, '采购入库'),
(1, '手机', 'OUT', 50, '销售出库'),
(2, '笔记本电脑', 'IN', 200, '采购入库'),
(3, '手机', 'OUT', 30, '销售出库');
