-- =============================================================================
-- 56 号迁移：M2-4 承运商回调入口（tms_tracking_nodes 补三列 + 幂等唯一索引）
--
-- 背景（评审 §9.2 M2-4 验收标准 ①⑤）：
--   tms_tracking_nodes.source 的注释早早就写好了 MANUAL / CARRIER_CALLBACK / SCHEDULED
--   三档，但 CARRIER_CALLBACK 与 SCHEDULED **从来没有代码写过它们**——43 号迁移建表时
--   只是把字段位留好了。承运商状态只能人工点回调端点（当时还不存在）或直接改库。
--
-- 这三列解决的是同一个问题的三个侧面：**这一条轨迹是承运商说的，还是我们说的？**
--   external_code   承运商的原始事件码（IFTSTA 的 C555/4405，如 DEP/ARR）。
--                   为什么必须原样存而不是直接丢弃：我们落 node_code=DEPARTED 之后，
--                   就再也无法回答「你们说的 DEP 是哪个口径的 DEP」。码表各家自定义，
--                   存了原文，将来对不上时才能反查是不是码表理解错了。
--   external_source 承运商标识（SCAC：COSU/MAEU/CMDU；空运用 IATA 数字码）。
--                   为什么要单独一列而不用 tms_shipments.carrier_id：回调报文里的承运商
--                   标识**不一定**等于我们库里挂的那家（货代代报、船东代理、转运商都会
--                   用自己的码发状态）。强行对齐到 carrier_id 会丢掉「这条状态是 XX 转发的」
--                   这条事实，而这条事实正是对账扯皮时第一句要问的。
--   raw_payload     原始报文留存（JSON 字符串，整条消息一份，不拆到每个节点）。
--                   为什么留原文：**对账时承运商会拿他们的报文质疑我们的节点时间**。
--                   存了原文，那一刻就能把「我们写的节点时间」和「他们报的事件时间」
--                   并排摆出来自证；不留原文就只能靠嘴说，而 BIT 格式的 IFTSTA 报文
--                   过期后往往取不回来（对方只保留 90 天）。
--
-- 幂等索引（验收标准⑤）：
--   幂等键 = (tenant_id, shipment_no, node_code, node_time, external_code)。
--   前三列+node_time 是**原有**的人工幂等键（ShipmentService.addTracking 一直用它
--   先查后插）；加上 external_code 之后它对回调同样成立——同一条承运商事件推两次
--   （承运商的重试、以及我们自己的网络抖动重发）撞的就是这五列。
--   为什么要**唯一索引**而不是继续只靠先查后插：先查后插在并发下必然有窗口——两个请求
--   同时 SELECT 到 0 行，然后同时 INSERT，产生两条重复轨迹。「先查后插 + 捕获唯一键冲突」
--   是这条 SQL 上唯一真的安全的写法（第二个请求的唯一键冲突会让它整个事务回滚，
--   因此也不会出现「状态推进了两次」）。这一步把保证从应用层搬到了数据库层。
--   注意 external_code 对人工/定时轨迹恒为 ''：唯一索引因此**顺带把人工幂等键也变成了
--   强约束**（原先只是应用层的 best-effort）。这是想要的结果——「状态从哪来」只有一个答案。
--
-- MySQL 8.0 不支持 ADD COLUMN / ADD INDEX IF NOT EXISTS（那是 MariaDB 语法），
-- 所以照 52/53 号迁移的写法：查 information_schema 再 PREPARE。
-- 幂等：可重复执行。
-- =============================================================================

USE tms;

-- ------------------------------------------------------------------ 三列
SET @c1 = (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = 'tms' AND TABLE_NAME = 'tms_tracking_nodes'
              AND COLUMN_NAME = 'external_code');
SET @s1 = IF(@c1 = 0,
    'ALTER TABLE tms_tracking_nodes ADD COLUMN external_code VARCHAR(32) NOT NULL DEFAULT ''''
        COMMENT ''承运商原始事件码(IFTSTA C555/4405，如 DEP/ARR)；原样留存，不映射成内部码''',
    'DO 0');
PREPARE stmt1 FROM @s1; EXECUTE stmt1; DEALLOCATE PREPARE stmt1;

SET @c2 = (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = 'tms' AND TABLE_NAME = 'tms_tracking_nodes'
              AND COLUMN_NAME = 'external_source');
SET @s2 = IF(@c2 = 0,
    'ALTER TABLE tms_tracking_nodes ADD COLUMN external_source VARCHAR(32) NOT NULL DEFAULT ''''
        COMMENT ''承运商标识(SCAC/IATA，如 COSU/MAEU/784)；与 carrier_id 不同义，代理转发时不一样''',
    'DO 0');
PREPARE stmt2 FROM @s2; EXECUTE stmt2; DEALLOCATE PREPARE stmt2;

SET @c3 = (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = 'tms' AND TABLE_NAME = 'tms_tracking_nodes'
              AND COLUMN_NAME = 'raw_payload');
SET @s3 = IF(@c3 = 0,
    'ALTER TABLE tms_tracking_nodes ADD COLUMN raw_payload TEXT
        COMMENT ''承运商原始报文(JSON)。对账扯皮时对方会拿报文质疑我们的节点时间，必须能自证''',
    'DO 0');
PREPARE stmt3 FROM @s3; EXECUTE stmt3; DEALLOCATE PREPARE stmt3;

-- ------------------------------------------------------------------ 幂等唯一索引
-- 加唯一索引前必须确认存量无重复：(tenant_id, shipment_no, node_code, node_time)。
-- 本次上线前实测 52 条轨迹无重复（既有 idx_shipment_node 是普通索引，本来就允许重复）。
-- 若将来有人在别的环境先灌了重复数据，这句会直接失败——**这是故意的**：
-- 静默跳过会让「幂等保证」变成一句空话，不如启动即报错。
SET @i1 = (SELECT COUNT(*) FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = 'tms' AND TABLE_NAME = 'tms_tracking_nodes'
              AND INDEX_NAME = 'uk_node_event');
SET @s4 = IF(@i1 = 0,
    'ALTER TABLE tms_tracking_nodes ADD UNIQUE INDEX uk_node_event
         (tenant_id, shipment_no, node_code, node_time, external_code)',
    'DO 0');
PREPARE stmt4 FROM @s4; EXECUTE stmt4; DEALLOCATE PREPARE stmt4;

-- idx_shipment_node 刻意**不加** external_code，理由两条：
--   1) 它的用途是「按运单号取全部轨迹、按节点码+时间做人工判重」，
--      最左前缀 (shipment_no, node_code, node_time) 已经是这个查询的完整键；
--      回调幂等由上面的 uk_node_event 负责，再把 external_code 塞进这个普通索引
--      只会让每个 INSERT 多维护一份 B+ 树，对一张每天几千行的表没有收益。
--   2) 加进去之后两个索引的可区分性反而变差：读路径不需要 external_code，
--      多一列进索引键意味着 stats 里的基数分布被 callback 码污染。
-- 所以这里保持原样，只在文档里说明「回调幂等走 uk_node_event」。

-- ------------------------------------------------------------------ 存量对齐
-- 存量 15 条 CARRIER_CALLBACK 轨迹是 49 号种子直接灌的演示数据（49 号迁移自己的注释
-- 就写了「M1 只实现了人工录入，承运商回调入口是 M2 的事，但种子先把数据铺上」）。
-- 它们没有承运商事件码，也没有原文——但这正是「没有 token 就不该有值」的表达：
-- 留空串而不是填个假码，这样日后 `WHERE external_code <> ''` 永远等于「真的有报文来过」。
UPDATE tms_tracking_nodes
   SET external_source = 'SEED'
 WHERE source = 'CARRIER_CALLBACK'
   AND external_code = ''
   AND external_source = '';