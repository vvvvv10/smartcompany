-- =============================================================================
-- 43: TMS 国际跨境物流域表结构（M1 纵向切片）
--
-- 为什么新建表而不改 tms_orders：tms_orders 的语义是「国内公路整车/零担运输单」
-- （起终点是省市地址、driver_id/vehicle_id、4 态无迁移校验）。国际整柜货的语义是
-- 「母单主单 AWB/BL + 分单 + 箱型箱量 + VGM + 截关 + 报关」，
-- 两者字段几乎无交集，硬改会同时破坏 39 号三系统联动与移动端只读页签。
--
-- 跨服务关联键沿用 39 号哲学：**只用业务单号字符串，不共享自增 id**——
--   oms_export_batches.batch_no  ←→  tms_shipments.batch_no
--   oms_export_orders.order_no   ←→  tms_shipment_items.order_no
-- 四个库各自 AUTO_INCREMENT，oms.id=3 与 tms.id=3 毫无关系。
--
-- 时区口径：本文件所有 DATETIME 列一律存 **UTC**，列名一律 `_at` 后缀并在 COMMENT
-- 标注「（UTC）」；DATE 列表达「当地日期」这个业务事实，不加后缀、不做时区换算。
-- 存量表的时间是本地时区（TZ=Asia/Shanghai），新表与之**故意不混算**。
--
-- 【待验证】（行业数据，评审时逐条核对，grep 「【待验证】」即可列出）
--   · AWB 前 3 位航司数字码 / 提单 4 位船公司代码是否有效（IATA / SCAC 官方码表）
--   · ISO 6346 集装箱号校验位算法（本项目只存字符串，不校验）
--   · 体积重除数（空运 6000 / 快递 5000）、海运计费重 W/M 比例
--   · 报关单号位数（18 位）与编码规则
--
-- 唯一索引的坑：MySQL 把空串当普通值，多条 awb_no='' 的行会撞唯一键。
-- 因此 awb_no / bl_no **不建唯一索引**，只用普通索引供列表搜索；
-- 唯一性由应用层「生成单号时先查后建」保证（同 LinkageService 哲学）。
-- 只有本系统生成、必非空的 shipment_no 建唯一键。
--
-- M1 范围：本文件只建 M1 用到的 7 张表。tms_rate_cards / tms_rate_card_items /
-- tms_fx_rates（运价卡与汇率）归 M3，随 M3 的端点一起建，避免建出无人读写的死表。
--
-- 幂等：全部 CREATE TABLE IF NOT EXISTS，可重复执行。
-- =============================================================================

CREATE DATABASE IF NOT EXISTS tms DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE tms;

-- ---------------- 承运商 / 渠道商主数据 ----------------
-- 索引依据：idx_tenant 服务「按租户 + id 倒序分页」（沿用 36 号口径）；
-- idx_type 服务列表按类型筛选。
CREATE TABLE IF NOT EXISTS tms_carriers (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba' COMMENT '租户ID（36 号规范）',
    code            VARCHAR(16)  NOT NULL COMMENT '承运商代码，如 MAEU/COSCO/UPS【待验证】是否等于 SCAC/IATA 码',
    name_zh         VARCHAR(64)  NOT NULL COMMENT '中文名',
    name_en         VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '英文名',
    carrier_type    VARCHAR(20)  NOT NULL DEFAULT 'OCEAN' COMMENT 'OCEAN海运船司/AIR空运航司/EXPRESS快递/NVOCC无船承运人/FORWARDER货代/AGENT货代代理/CLEARANCE清关服务商/LAST_MILE尾程派送',
    country         VARCHAR(2)   NOT NULL DEFAULT '' COMMENT '注册国 ISO-3166 alpha-2',
    contact_name    VARCHAR(64)  NOT NULL DEFAULT '',
    contact_phone   VARCHAR(32)  NOT NULL DEFAULT '',
    contact_email   VARCHAR(64)  NOT NULL DEFAULT '',
    service_level   VARCHAR(16)  NOT NULL DEFAULT 'STANDARD' COMMENT 'STANDARD标准/EXPRESS特快/ECONOMY经济',
    transit_days    INT          NOT NULL DEFAULT 0 COMMENT '参考时效（天）',
    cut_off_hours   INT          NOT NULL DEFAULT 0 COMMENT '截关/截仓提前小时数（ETD 前 N 小时）',
    free_time_days  INT          NOT NULL DEFAULT 0 COMMENT '免堆存/免箱期天数【待验证】',
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE启用/INACTIVE停用',
    remark          VARCHAR(255) NOT NULL DEFAULT '',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_carrier_code (tenant_id, code),
    KEY idx_tenant (tenant_id, id),
    KEY idx_type (carrier_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '承运商与渠道商主数据';

-- ---------------- 航线 ----------------
CREATE TABLE IF NOT EXISTS tms_routes (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba' COMMENT '租户ID',
    route_code      VARCHAR(32)  NOT NULL COMMENT '航线代码 CNSHA-USLAX',
    carrier_id      BIGINT       NOT NULL COMMENT 'tms_carriers.id',
    mode            VARCHAR(16)  NOT NULL DEFAULT 'SEA' COMMENT 'SEA海运/AIR空运/RAIL铁路/TRUCK卡航/EXPRESS快递/LCL海运拼箱',
    pol_code        VARCHAR(8)   NOT NULL COMMENT '起运港/起飞机场码',
    pod_code        VARCHAR(8)   NOT NULL COMMENT '目的港/目的地码',
    transit_days    INT          NOT NULL DEFAULT 0 COMMENT '参考时效（天）',
    volumetric_divisor INT       NOT NULL DEFAULT 6000 COMMENT '体积重除数 cm³/kg：空运 6000 / 快递 5000【待验证】',
    via_ports       VARCHAR(128) NOT NULL DEFAULT '' COMMENT '中转港，逗号分隔',
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    remark          VARCHAR(255) NOT NULL DEFAULT '',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_route_code (tenant_id, route_code),
    KEY idx_tenant (tenant_id, id),
    KEY idx_pol_pod (pol_code, pod_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '航线主数据';

-- ---------------- 船期 / 班期 ----------------
-- uk_route_etd：同一航线同一 ETD 只有一条船期，是种子脚本幂等的唯一键。
CREATE TABLE IF NOT EXISTS tms_sailings (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba' COMMENT '租户ID',
    route_id        BIGINT       NOT NULL COMMENT 'tms_routes.id',
    vessel_name     VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '船名/航班号',
    voyage_no       VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '航次号，如 V.034E',
    etd_at          DATETIME     NOT NULL COMMENT 'ETD 开航（UTC）',
    eta_at          DATETIME     NOT NULL COMMENT 'ETA 到港（UTC）',
    cutoff_at       DATETIME     NULL DEFAULT NULL COMMENT '截关时间（UTC），空则按承运商 cut_off_hours 推算',
    free_time_days  INT          NOT NULL DEFAULT 0 COMMENT '本航次免堆存天数',
    space_left      INT          NOT NULL DEFAULT 0 COMMENT '剩余舱位（口径随 mode 而定【待验证】）',
    status          VARCHAR(16)  NOT NULL DEFAULT 'SCHEDULED' COMMENT 'SCHEDULED已排期/BOOKING已订舱/CLOSED已截关/CANCELLED已取消',
    remark          VARCHAR(255) NOT NULL DEFAULT '',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_route_etd (tenant_id, route_id, etd_at),
    KEY idx_tenant (tenant_id, id),
    KEY idx_etd (etd_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '船期与班期';

-- ---------------- 国际运单（主单） ----------------
CREATE TABLE IF NOT EXISTS tms_shipments (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba' COMMENT '租户ID',
    shipment_no     VARCHAR(32)  NOT NULL COMMENT '国际运单号 SHP+yyyyMMdd+3位',
    batch_no        VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '关联出口母单号 oms_export_batches.batch_no（弱引用，跨库无外键）',

    -- 单证号：awb_no / bl_no 可空且可能重复，故不建唯一索引（MySQL 空串会撞唯一键）
    awb_no          VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '空运单号，如 176-30874125【待验证】航司数字码',
    bl_no           VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '海运提单号，如 COSU6123456【待验证】船公司代码',
    booking_no      VARCHAR(32)  NOT NULL DEFAULT '' COMMENT 'S/O 订舱号（可换船司重订，故不唯一）',
    sailing_id      BIGINT       NOT NULL DEFAULT 0 COMMENT 'tms_sailings.id（弱引用）',

    -- 运输方案
    mode            VARCHAR(16)  NOT NULL DEFAULT 'SEA' COMMENT 'SEA/AIR/RAIL/TRUCK/EXPRESS/LCL',
    carrier_id      BIGINT       NOT NULL COMMENT 'tms_carriers.id（弱引用，只存 id 不跨库校验）',
    pol_code        VARCHAR(8)   NOT NULL DEFAULT '' COMMENT '起运港/机场',
    pod_code        VARCHAR(8)   NOT NULL DEFAULT '' COMMENT '目的港/目的地',
    container_type  VARCHAR(8)   NOT NULL DEFAULT '' COMMENT '20GP/40GP/40HQ/LCL',
    container_qty   INT          NOT NULL DEFAULT 0 COMMENT '箱量',

    -- 货物
    incoterm        VARCHAR(8)   NOT NULL DEFAULT 'FOB' COMMENT 'EXW/FCA/FOB/CFR/CIF/CPT/CIP/DAP/DPU/DDP【待验证】',
    currency        CHAR(3)      NOT NULL DEFAULT 'USD' COMMENT 'ISO-4217',
    total_pieces    INT          NOT NULL DEFAULT 0 COMMENT '总件数',
    total_gross_weight DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '总毛重 KG',
    total_volume    DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '总体积 CBM',
    volumetric_weight DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '总体积重 KG（按航线 volumetric_divisor 算）',
    chargeable_weight DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '计费重【待验证】海运 W/M 比例由航线约定',
    vgm_weight      DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT 'VGM 核实总重 KG（海运 SOLAS 强制）',
    marks           VARCHAR(255) NOT NULL DEFAULT '' COMMENT '唛头（多行用 \\n）',

    -- 时效（全部 UTC）
    etd_at          DATETIME     NULL DEFAULT NULL COMMENT '计划开航 ETD（UTC）',
    eta_at          DATETIME     NULL DEFAULT NULL COMMENT '预计到港 ETA（UTC）',
    cutoff_at       DATETIME     NULL DEFAULT NULL COMMENT '截关/截仓（UTC）',
    actual_etd_at   DATETIME     NULL DEFAULT NULL COMMENT '实际开航（UTC）',
    actual_eta_at   DATETIME     NULL DEFAULT NULL COMMENT '实际到港（UTC）',
    pod_received_at DATETIME     NULL DEFAULT NULL COMMENT 'POD 签收证明回传时间（UTC）',

    -- 合规
    is_dangerous    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1=含危险品',
    un_number       VARCHAR(16)  NOT NULL DEFAULT '' COMMENT 'UN 编号汇总（多品逗号串）',
    dg_class        VARCHAR(8)   NOT NULL DEFAULT '' COMMENT '危险品类别汇总，9=锂电池',
    sanction_flag   VARCHAR(16)  NOT NULL DEFAULT 'CLEAR' COMMENT '制裁筛查结果 CLEAR/PENDING/HIT（人工核，HIT 必须人工放行）',
    ioss_no         VARCHAR(32)  NOT NULL DEFAULT '' COMMENT 'IOSS 编号（欧盟 B2C 进口 VAT）【待验证】适用条件',

    -- 状态
    status          VARCHAR(24)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/BOOKING/BOOKED/PICKED_UP/EXPORT_DECLARED/DEPARTED/IN_TRANSIT/ARRIVED/CLEARING/CLEARED/LAST_MILE/DELIVERED/POD_CONFIRMED/EXCEPTION/RETURNED/CANCELLED',
    exception_reason VARCHAR(255) NOT NULL DEFAULT '' COMMENT '异常原因（进入 EXCEPTION 时必填）',

    remark          VARCHAR(255) NOT NULL DEFAULT '',
    created_by      BIGINT       NOT NULL DEFAULT 0 COMMENT '创建人 users.id（网关注入的 X-User-Id）',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_shipment_no (tenant_id, shipment_no),
    KEY idx_tenant (tenant_id, id),
    KEY idx_batch (batch_no),
    KEY idx_status (status),
    KEY idx_etd (etd_at),
    KEY idx_awb (awb_no),
    KEY idx_bl (bl_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '国际运单（主单）';

-- ---------------- 运单箱明细 / 分单 ----------------
-- container_no 是 ISO 6346 格式（4 字母 + 6 位 + 1 校验位），本项目只存字符串不校验。
CREATE TABLE IF NOT EXISTS tms_shipment_items (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba' COMMENT '租户ID',
    shipment_id     BIGINT       NOT NULL COMMENT 'tms_shipments.id',
    shipment_no     VARCHAR(32)  NOT NULL COMMENT '冗余运单号，列表聚合免 JOIN',
    order_no        VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '关联出口子单号 oms_export_orders.order_no（跨库弱引用）',
    house_no        VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '分单号 House（母单号-01）',
    container_no    VARCHAR(16)  NOT NULL DEFAULT '' COMMENT '集装箱号 ISO 6346【待验证】校验位',
    container_type  VARCHAR(8)   NOT NULL DEFAULT '' COMMENT '箱型',
    seal_no         VARCHAR(16)  NOT NULL DEFAULT '' COMMENT '封条号',
    marks           VARCHAR(255) NOT NULL DEFAULT '' COMMENT '唛头',
    pieces          INT          NOT NULL DEFAULT 0 COMMENT '件数',
    gross_weight    DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '毛重 KG',
    volume          DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '体积 CBM',
    volumetric_weight DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '体积重 KG',
    is_dangerous    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1=含危险品',
    un_number       VARCHAR(16)  NOT NULL DEFAULT '',
    dg_class        VARCHAR(8)   NOT NULL DEFAULT '',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    KEY idx_shipment (shipment_id),
    KEY idx_order_no (order_no),
    KEY idx_house_no (house_no),
    KEY idx_container (container_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '运单箱明细/分单';

-- ---------------- 运单轨迹节点（只追加不更新） ----------------
-- source 列是这套设计的核心：它回答「这一条是人点的、承运商推的还是系统推的」。
-- M1 全部为 MANUAL；CARRIER_CALLBACK / SCHEDULED 随 M2/M3 的回调与定时任务产生。
CREATE TABLE IF NOT EXISTS tms_tracking_nodes (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba' COMMENT '租户ID',
    shipment_id     BIGINT       NOT NULL COMMENT 'tms_shipments.id',
    shipment_no     VARCHAR(32)  NOT NULL COMMENT '冗余运单号',
    node_code       VARCHAR(24)  NOT NULL COMMENT '节点码 BOOKED/PICKED_UP/EXPORT_DECLARED/DEPARTED/IN_TRANSIT/ARRIVED/CLEARING/CLEARED/LAST_MILE/DELIVERED/POD_CONFIRMED/EXCEPTION',
    node_name       VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '节点中文名（承运商原文）',
    node_time       DATETIME     NOT NULL COMMENT '节点时间（UTC）',
    location        VARCHAR(128) NOT NULL DEFAULT '' COMMENT '节点地点',
    source          VARCHAR(16)  NOT NULL DEFAULT 'MANUAL' COMMENT '来源 MANUAL人工/CARRIER_CALLBACK承运商回调/SCHEDULED定时任务',
    operator        VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '操作人（MANUAL 时取网关注入用户）',
    remark          VARCHAR(255) NOT NULL DEFAULT '',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    KEY idx_shipment_time (shipment_id, node_time),
    KEY idx_shipment_node (shipment_no, node_code, node_time),
    KEY idx_tenant (tenant_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '运单轨迹节点（只追加）';

-- ---------------- 出口报关单 ----------------
-- 进入 RELEASED 时联动：TMS 运单 BOOKED|PICKED_UP → EXPORT_DECLARED。
CREATE TABLE IF NOT EXISTS tms_customs_declarations (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'alibaba' COMMENT '租户ID',
    declaration_no  VARCHAR(32)  NOT NULL COMMENT '报关单号（关单号，位数与编码规则【待验证】）',
    shipment_id     BIGINT       NOT NULL COMMENT 'tms_shipments.id',
    shipment_no     VARCHAR(32)  NOT NULL COMMENT '冗余运单号',
    batch_no        VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '关联出口母单号',
    customs_broker  VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '报关行名称',
    broker_contact  VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '报关行联系人电话',
    hs_code_summary VARCHAR(512) NOT NULL DEFAULT '' COMMENT 'HS 编码汇总（去重逗号串）',
    declared_value  DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '申报货值（原币）',
    currency        CHAR(3)      NOT NULL DEFAULT 'USD' COMMENT 'ISO-4217',
    duty_amount     DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '关税',
    vat_amount      DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '增值税',
    consumption_tax DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '消费税',
    deposit_amount  DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '海关保证金',
    status          VARCHAR(20)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/FILED/RELEASED/INSPECTING/HELD/REJECTED',
    filed_at        DATETIME     NULL DEFAULT NULL COMMENT '申报时间（UTC）',
    released_at     DATETIME     NULL DEFAULT NULL COMMENT '放行时间（UTC）',
    remark          VARCHAR(255) NOT NULL DEFAULT '',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_decl_no (tenant_id, declaration_no),
    KEY idx_shipment (shipment_id),
    KEY idx_tenant (tenant_id, id),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '出口报关单';
