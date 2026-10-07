-- OMS 订单管理服务（服装行业：款号 + 颜色/尺码 SKU + 多渠道订单）
CREATE DATABASE IF NOT EXISTS oms DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE oms;

-- 商品 SKU：一行 = 一个款号+颜色+尺码 组合
CREATE TABLE IF NOT EXISTS oms_products (
    id BIGINT NOT NULL AUTO_INCREMENT,
    style_no VARCHAR(32) NOT NULL COMMENT '款号，如 K2601',
    name VARCHAR(128) NOT NULL COMMENT '品名',
    category VARCHAR(16) NOT NULL DEFAULT 'TOP' COMMENT 'TOP上衣/PANTS裤装/DRESS裙装/OUTER外套/KNIT针织',
    season VARCHAR(16) DEFAULT '' COMMENT '季节',
    color VARCHAR(32) NOT NULL DEFAULT '' COMMENT '颜色',
    size VARCHAR(16) NOT NULL DEFAULT '' COMMENT '尺码',
    sku VARCHAR(64) DEFAULT '' COMMENT 'SKU 编码（款号-颜色-尺码）',
    price DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '吊牌价（元）',
    status VARCHAR(16) NOT NULL DEFAULT 'ON' COMMENT 'ON上架/OFF下架',
    remark VARCHAR(255) DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_style_no (style_no),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品SKU';

CREATE TABLE IF NOT EXISTS oms_orders (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_no VARCHAR(32) NOT NULL COMMENT '订单号',
    channel VARCHAR(16) NOT NULL DEFAULT 'OFFLINE' COMMENT 'DOUYIN抖音/TAOBAO淘宝/P1688批发/OFFLINE线下/WECHAT微信',
    customer_name VARCHAR(64) DEFAULT '' COMMENT '客户/收货人',
    phone VARCHAR(20) DEFAULT '',
    address VARCHAR(255) DEFAULT '',
    status VARCHAR(16) NOT NULL DEFAULT 'UNPAID' COMMENT 'UNPAID待付款/TO_SHIP待发货/SHIPPED已发货/DONE已完成/AFTER_SALE售后/CANCELLED已取消',
    total_qty INT NOT NULL DEFAULT 0 COMMENT '总件数',
    total_amount DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '订单金额（元）',
    remark VARCHAR(255) DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_status (status),
    KEY idx_channel (channel)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单';

CREATE TABLE IF NOT EXISTS oms_order_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    product_id BIGINT DEFAULT NULL,
    style_no VARCHAR(32) NOT NULL COMMENT '款号',
    product_name VARCHAR(128) NOT NULL COMMENT '品名',
    color VARCHAR(32) DEFAULT '' COMMENT '颜色',
    size VARCHAR(16) DEFAULT '' COMMENT '尺码',
    quantity INT NOT NULL DEFAULT 1 COMMENT '数量',
    price DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '成交单价',
    PRIMARY KEY (id),
    KEY idx_order (order_id),
    KEY idx_style_no (style_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细';

-- 种子数据：商品（9 个 SKU，3 个款）
INSERT INTO oms_products (style_no, name, category, season, color, size, sku, price, status) VALUES
('K2601', '基础圆领T恤', 'TOP', '四季', '白色', 'M', 'K2601-白-M', 79.00, 'ON'),
('K2601', '基础圆领T恤', 'TOP', '四季', '白色', 'L', 'K2601-白-L', 79.00, 'ON'),
('K2601', '基础圆领T恤', 'TOP', '四季', '黑色', 'M', 'K2601-黑-M', 79.00, 'ON'),
('K2602', '直筒牛仔裤', 'PANTS', '春秋', '蓝色', '27', 'K2602-蓝-27', 199.00, 'ON'),
('K2602', '直筒牛仔裤', 'PANTS', '春秋', '蓝色', '28', 'K2602-蓝-28', 199.00, 'ON'),
('K2603', '碎花连衣裙', 'DRESS', '春夏', '粉色', 'S', 'K2603-粉-S', 259.00, 'ON'),
('K2603', '碎花连衣裙', 'DRESS', '春夏', '粉色', 'M', 'K2603-粉-M', 259.00, 'ON'),
('K2604', '连帽卫衣', 'KNIT', '秋冬', '灰色', 'L', 'K2604-灰-L', 159.00, 'ON'),
('K2604', '连帽卫衣', 'KNIT', '秋冬', '灰色', 'XL', 'K2604-灰-XL', 159.00, 'OFF');

-- 种子数据：订单（5 单，覆盖各状态/渠道）
INSERT INTO oms_orders (order_no, channel, customer_name, phone, address, status, total_qty, total_amount, remark) VALUES
('OMS20261002001', 'DOUYIN', '王小姐', '13800000008', '浙江省杭州市余杭区', 'TO_SHIP', 3, 237.00, '直播间下单，要求发顺丰'),
('OMS20261002002', 'TAOBAO', '李先生', '13800000009', '广东省深圳市南山区', 'SHIPPED', 2, 398.00, ''),
('OMS20261001003', 'P1688', '广州白马服装商行', '02088886666', '广东省广州市站前路128号', 'DONE', 50, 7950.00, '批发单，50 件×K2601'),
('OMS20261002004', 'OFFLINE', '门店散客', '', '', 'UNPAID', 1, 79.00, '门店下单未付款'),
('OMS20261001005', 'WECHAT', '周姐', '13800000010', '江苏省南京市鼓楼区', 'AFTER_SALE', 1, 259.00, '尺码不合适，申请换货');

INSERT INTO oms_order_items (order_id, product_id, style_no, product_name, color, size, quantity, price) VALUES
(1, 1, 'K2601', '基础圆领T恤', '白色', 'M', 2, 79.00),
(1, 2, 'K2601', '基础圆领T恤', '白色', 'L', 1, 79.00),
(2, 4, 'K2602', '直筒牛仔裤', '蓝色', '27', 2, 199.00),
(3, 1, 'K2601', '基础圆领T恤', '白色', 'M', 50, 159.00),
(4, 1, 'K2601', '基础圆领T恤', '白色', 'M', 1, 79.00),
(5, 6, 'K2603', '碎花连衣裙', '粉色', 'S', 1, 259.00);
