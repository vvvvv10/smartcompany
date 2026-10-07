#!/usr/bin/env bash
# =============================================================================
# check_intl_data.sh —— 国际物流模块「部署后数据巡检」
#
# 这是什么
#   一组只读的 SQL 断言。跑完把「库里现在有哪些数据自相矛盾 / 哪些校验缺覆盖」
#   逐行列出来。它不修数据、不改表结构，只报告——修不修由人决定。巡检的价值
#   是暴露问题，不是替人做决定。
#
# 为什么这些检查值得存在（本脚本存在的全部理由）
#   上一轮评审查出 5 个 P0，其中 4 个的共同点是：**只要一条 SQL 就能发现**——
#     · 体积重写成 CBM×6000 而不是 CBM×1e6÷6000，偏大 36 倍（43 张子单全中）
#     · 运单 total_pieces 与明细 pieces 之和对不上
#     · house_no 在同一张运单内重复（12 张运单全中）
#     · 母单 status 被写成子单状态机的值 PICKING，母单状态机里根本没这个值
#   它们能溜过去不是因为难发现，而是**没人跑那条 SQL**。评审 §9.2 把本脚本列为
#   M2 第一件事（M2-1），理由就是让「一条 SQL 就能发现」变成「每次部署完自动
#   发现」。所以下面每条检查都刻意写成一条能独立跑的 SQL，而不是一段需要人
#   看懂的推理；也刻意把判断放在 SQL 里（ABS()、GROUP BY、HAVING），不让
#   几万行数据回流到 bash 里算。
#
# 怎么用
#   ./scripts/check_intl_data.sh                # 跑全部检查
#   ./scripts/check_intl_data.sh --db tms       # 只跑涉及 tms 库的检查
#   ./scripts/check_intl_data.sh --list         # 列出检查项，不执行
#   ./scripts/check_intl_data.sh --help
#
# 连接方式
#   通过 `docker exec` 进 MySQL 容器执行（库在容器里，宿主机不一定有 client）。
#   4 个库 tms / oms / wms / crm 在同一个实例里，所以跨库断言可以直接写
#   `tms.tms_routes`、`oms.oms_export_orders` 这样的全限定名，不需要 Federated。
#
# 密码处理（真实凭据，不是示例）
#   密码**不进 docker exec 的 argv**，也不进 set -x 回显、不进错误信息：
#     1) 默认完全不传密码——直接复用容器自带的 MYSQL_ROOT_PASSWORD，
#        `sh -c '... "$MYSQL_ROOT_PASSWORD" ...'` 的展开发生在容器内，
#        宿主机 ps 与本脚本任何输出里只有变量名；
#     2) 需要覆盖密码时写进一个 0600 的临时 env 文件，用 `docker exec
#        --env-file` 传，同样不进 argv；脚本退出时删除。
#   所有 SQL stderr 再统一过一遍 scrub()，双保险。
#
# 退出码
#   0  一行问题都没输出（通过）
#   1  至少输出一行问题（ERROR / WARN / INFO 任一）
#   2  用法错误 / 前置条件不满足（找不到容器等）
#
# 输出契约
#   每行一条：严重度 <TAB> 检查名 <TAB> 具体内容
#     ERROR  数据错了，会算错钱 / 错报关
#     WARN   数据不一致但暂时无害
#     INFO   只是提示当前缺少覆盖样本（不代表数据错）
#   每行都必须带可核验的定位信息（单号/主键 + 期望值 vs 实际值），
#   不允许只说「有 N 条不一致」。
#   结尾固定打印汇总行：ERROR n / WARN n / INFO n / 共 n 项检查
#
#   一条检查失败**不会中断整个脚本**：全部跑完再汇总退出。否则第一个问题就把
#   后面全挡住了，而后面的问题往往更严重。
# =============================================================================

# 刻意不加 set -e：单条 SQL 失败必须被当成「这条检查没结论」记下来并继续跑。
set -uo pipefail
export LC_ALL=C

# ----------------------------------------------------------------- 可覆盖配置
MYSQL_CONTAINER="${MYSQL_CONTAINER:-stack-mysql}"
MYSQL_USER="${MYSQL_USER:-root}"
# 留空 = 用容器自带的 MYSQL_ROOT_PASSWORD（推荐，密码完全不经过宿主机命令行）。
MYSQL_PASSWORD="${MYSQL_PASSWORD:-}"

# 数值容差：DECIMAL(12,3) 上 1e-3 之内视为相等。
WEIGHT_TOL="${WEIGHT_TOL:-0.001}"
VOLUME_TOL="${VOLUME_TOL:-0.0005}"
# 航线表取不到除数时的兜底值。6000 是 ExportOrderService.recalcTotals() 里
# 写死的空运口径（IATA TACT），也是 50 号 P0 修复脚本用的值。
VOL_DIVISOR_FALLBACK="${VOL_DIVISOR_FALLBACK:-6000}"

# 状态集合的源码位置，用于第 ⑱ 项漂移自检。留空则自动在当前目录下找；
# 找不到（脚本被上传到服务器、旁边没有仓库）时该项自动静默跳过。
OMS_STATE_FILE="${OMS_STATE_FILE:-}"
WMS_STATE_FILE="${WMS_STATE_FILE:-}"
TMS_STATE_FILE="${TMS_STATE_FILE:-}"
[ -z "$OMS_STATE_FILE" ] && [ -f oms/src/main/java/com/example/oms/intl/IntlStateMachine.java ] &&
  OMS_STATE_FILE=oms/src/main/java/com/example/oms/intl/IntlStateMachine.java
[ -z "$WMS_STATE_FILE" ] && [ -f wms/src/main/java/com/example/wms/intl/IntlStateMachine.java ] &&
  WMS_STATE_FILE=wms/src/main/java/com/example/wms/intl/IntlStateMachine.java
[ -z "$TMS_STATE_FILE" ] && [ -f tms/src/main/java/com/example/tms/intl/IntlStateMachine.java ] &&
  TMS_STATE_FILE=tms/src/main/java/com/example/tms/intl/IntlStateMachine.java

# ------------------------------------------------------------------- 状态集合
# 三份集合全部从 IntlStateMachine.java 的 Map key 抄来，不是从表 COMMENT 或
# 印象里写的。第 ⑱ 项会在能读到源码时校验它们有没有漂移。
BATCH_STATES="DRAFT CONFIRMED WAITING_GOODS BOOKING BOOKED LOADING DEPARTED IN_TRANSIT ARRIVED CLEARING CLEARED LAST_MILE DELIVERED CLOSED EXCEPTION CANCELLED"
SHIPMENT_STATES="DRAFT BOOKING BOOKED PICKED_UP EXPORT_DECLARED DEPARTED IN_TRANSIT ARRIVED CLEARING CLEARED LAST_MILE DELIVERED POD_CONFIRMED EXCEPTION RETURNED CANCELLED"
TRANSIT_STATES="IN_TRANSIT ARRIVED HELD EXCEPTION RECEIVED LOST"

# 比阶段轴用的两套词表，喂给 FIELD()。FIELD 按位置返回下标（匹配不到返回 0），
# 所以列表顺序必须与下面 CASE 的 WHEN 下标一一对应，改一处就要改另一处。
# 必须带引号写成字符串常量：不加引号时 MySQL 把 DRAFT 当列名，报
# "Unknown column 'DRAFT' in 'field list'"。
ORDER_STATE_LIST="'DRAFT','CONFIRMED','PICKING','PACKED','EXPORT_DECLARED','SHIPPED','IN_TRANSIT','ARRIVED','CUSTOMS_CLEARING','CUSTOMS_CLEARED','LAST_MILE','DELIVERED','CLOSED'"
SHIPMENT_STATE_LIST="'DRAFT','BOOKING','BOOKED','PICKED_UP','EXPORT_DECLARED','DEPARTED','IN_TRANSIT','ARRIVED','CLEARING','CLEARED','LAST_MILE','DELIVERED','POD_CONFIRMED'"
# 母单状态机比运单多一个 WAITING_GOODS（等货备货，LCL 拼箱的真实常态），位置不同。
BATCH_FIELD_LIST="'DRAFT','CONFIRMED','WAITING_GOODS','BOOKING','BOOKED','LOADING','DEPARTED','IN_TRANSIT','ARRIVED','CLEARING','CLEARED','LAST_MILE','DELIVERED','CLOSED'"

# 把空格分隔的集合转成 SQL 的 'A','B','C' 片段。
# 注意不能写成 printf "'%s'" "$*"：IFS=, 会先把参数拼成单个 "A,B,C"，
# printf 只会加一对引号，得到 'A,B,C' 而不是 'A','B','C'，于是 IN 列表整体失配。
# 这里改用 tr逐个加引号，行为不依赖 IFS 的拼接时机。
sql_list() { printf "'%s'," "$@" | sed 's/,$//'; }
# 刻意不加引号：sql_list 逐个处理参数，依赖词分割把集合拆成多个词。
# shellcheck disable=SC2086
BATCH_STATE_SQL="$(sql_list $BATCH_STATES)"
# shellcheck disable=SC2086
SHIPMENT_STATE_SQL="$(sql_list $SHIPMENT_STATES)"
# shellcheck disable=SC2086
TRANSIT_STATE_SQL="$(sql_list $TRANSIT_STATES)"

# --------------------------------------------------------------------- 计数器
ERROR_CNT=0
WARN_CNT=0
INFO_CNT=0
CHECK_CNT=0
ONLY_DB=""
SELF_TEST=0
ERR_FILE=""
ENV_FILE=""
USE_ENV_FILE=1

# 输出一行问题并计入汇总。参数：<严重度> <检查名> <具体内容>
emit() {
  case "$1" in
    ERROR) ERROR_CNT=$((ERROR_CNT + 1)) ;;
    WARN)  WARN_CNT=$((WARN_CNT + 1)) ;;
    INFO)  INFO_CNT=$((INFO_CNT + 1)) ;;
    *)     return 0 ;;
  esac
  printf '%s\t%s\t%s\n' "$1" "$2" "$3"
}

# 双保险：作为 stdin 过滤器，把可能出现在 SQL stderr 里的密码抹掉再打印。
# 用 bash 参数展开而不是 sed，密码里含正则元字符时也不会出错。
# MYSQL_PASSWORD 为空时原样透传（此时密码来自容器环境，根本没进过宿主机）。
scrub() {
  local line
  while IFS= read -r line || [ -n "$line" ]; do
    if [ -n "$MYSQL_PASSWORD" ]; then
      line="${line//"$MYSQL_PASSWORD"/<redacted>}"
    fi
    printf '%s\n' "$line"
  done
}

cleanup() {
  [ -n "$ENV_FILE" ] && rm -f "$ENV_FILE"
  [ -n "$ERR_FILE" ] && rm -f "$ERR_FILE"
  return 0
}

# 脚本会碰的库，用来校验 --db 参数。
KNOWN_DBS="tms oms wms crm"

usage() {
  cat <<'EOF'
用法: check_intl_data.sh [选项]

选项:
  --db <库名>    只跑涉及该库的检查（tms / oms / wms / crm）
  --list         列出检查项后退出
  --self-test    额外跑一组自检：故意执行一条坏 SQL，验证「SQL 失败会被报成
                 ERROR、且不中断后续检查、且密码不进输出」
  --help         本帮助

退出码: 0=通过(0 行问题) / 1=有问题 / 2=用法或前置条件错误

可用环境变量覆盖:
  MYSQL_CONTAINER(默认 stack-mysql)  MYSQL_USER(默认 root)
  MYSQL_PASSWORD(默认空=用容器自带 MYSQL_ROOT_PASSWORD，不经宿主机命令行)
  WEIGHT_TOL(默认 0.001)  VOLUME_TOL(默认 0.0005)
  VOL_DIVISOR_FALLBACK(默认 6000)
  OMS_STATE_FILE / WMS_STATE_FILE / TMS_STATE_FILE
EOF
}

# --------------------------------------------------------------------- MySQL
# 排序规则前缀：MySQL 服务器的 collation_server 是 utf8mb4_unicode_ci，而这几张
# 业务表是 utf8mb4_0900_ai_ci。SQL 里的字符串字面量按连接排序规则解释，于是
# UNION ALL 里「字面量列」和「CONCAT(表列)」相遇会报
# "Illegal mix of collations for operation 'UNION'"（第 ⑥ 项就踩到了）。
# 每条 SQL 前置这句把连接的排序规则对齐到表的排序规则；-N -B 下 SET NAMES
# 不产生任何输出行。
NAMES_PREFIX="SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;"

# 在容器里执行一段 SQL。stdout 原样返回，stderr 落到 $ERR_FILE。
# 注意 sh -c 里的 '$...' 由容器内展开，宿主机侧只出现变量名。
mysql_q() {
  local sql="$1"
  local envarg=()
  [ "$USE_ENV_FILE" = "1" ] && [ -n "$ENV_FILE" ] && envarg=(--env-file "$ENV_FILE")
  printf '%s\n%s' "$NAMES_PREFIX" "$sql" | docker exec -i "${envarg[@]}" "$MYSQL_CONTAINER" sh -c \
    'exec env MYSQL_PWD="${INTL_MYSQL_PASSWORD:-$MYSQL_ROOT_PASSWORD}" \
             mysql -u"${INTL_MYSQL_USER:-root}" -N -B --default-character-set=utf8mb4' \
    2>"$ERR_FILE"
}

# 跑一条检查。SQL 必须输出「严重度<TAB>内容」的行；没输出就是通过。
# 参数：<检查名> <涉及库，逗号分隔> <SQL>
#
# 所有 SQL 里的表名都写成 schema 全限定（tms.tms_shipments 这样）：一条检查经常
# 同时碰多个库，而 mysql 客户端不带默认库，早期版本这里漏了前缀导致每条 SQL 都
# 报 "No database selected"。
run_check() {
  local name="$1" dbs="$2" sql="$3"
  if [ -n "$ONLY_DB" ]; then
    case ",$dbs," in *",$ONLY_DB,"*) ;; *) return 0 ;; esac
  fi
  CHECK_CNT=$((CHECK_CNT + 1))

  local out rc first_err sev detail line
  # </dev/null：mysql_q 里的管道已经把 SQL 送进去了；不给 mysql 显式空 stdin，
  # 某些 docker/ssh 组合下它会继续等容器侧的输入，整条脚本就挂在这里。
  out="$(mysql_q "$sql" </dev/null)"
  rc=$?
  if [ "$rc" -ne 0 ]; then
    first_err="$(scrub <"$ERR_FILE" 2>/dev/null | head -n 1)"
    [ -z "$first_err" ] && first_err="(无 stderr)"
    # SQL 自己失败时必须报出来。否则这条检查会被当成「通过」而骗过部署流程——
    # 一个静默失效的断言比没有这个断言更危险。
    emit ERROR "$name" "SQL 执行失败(rc=$rc)，本项结论不可信: $first_err"
    return 0
  fi
  [ -z "$out" ] && return 0

  while IFS= read -r line; do
    [ -z "$line" ] && continue
    sev="${line%%$'\t'*}"
    detail="${line#*$'\t'}"
    emit "$sev" "$name" "$detail"
  done <<<"$out"
  return 0
}

# =============================================================================
# 各项检查。每条一个函数，函数名说清查什么。
# =============================================================================

# --- ① 母单状态合法性 ------------------------------------------------------
# 评审 P0-3：母单 status 曾被子单状态机的值 PICKING 占据，而 EXPORT_BATCH 里
# 没有这个 key，这张母单从此任何状态推进都会被 assertTransition 以「未知状态」
# 拒掉。合法集合来自 IntlStateMachine.EXPORT_BATCH 的 key。
check_batch_status_legality() {
  run_check batch_status_legality oms "
    SELECT 'ERROR', CONCAT_WS(' ', 'batch_no=', b.batch_no, 'status=', b.status,
             '不在 IntlStateMachine.EXPORT_BATCH 的 key 集合内(合法值: ', $BATCH_STATE_SQL, ')')
      FROM oms.oms_export_batches b
     WHERE b.status NOT IN ($BATCH_STATE_SQL);"
}

# --- ② 运单件数守恒 --------------------------------------------------------
# 评审 P0-5：total_pieces 必须等于明细 pieces 之和。
check_pieces_conservation() {
  run_check pieces_conservation tms "
    SELECT 'ERROR', CONCAT_WS(' ', 'shipment_no=', s.shipment_no,
             'total_pieces=', s.total_pieces, '明细 pieces 之和=', COALESCE(SUM(i.pieces), 0))
      FROM tms.tms_shipments s
      LEFT JOIN tms.tms_shipment_items i ON i.shipment_id = s.id
     GROUP BY s.id, s.shipment_no, s.total_pieces
    -- 明细 0 行 = 「还没录箱明细」，不是「录错了对不上」。一张刚建的 DRAFT 运单
    -- 本来就没有明细，拿它报 ERROR 只会让人学会忽略这条检查。
    HAVING COUNT(i.id) > 0
       AND s.total_pieces <> COALESCE(SUM(i.pieces), 0);"
}

# --- ③ 运单毛重守恒 --------------------------------------------------------
# P0-5 的另一半。边界：total_gross_weight=0 视为「没填」而不是「填了 0」，跳过。
check_gross_weight_conservation() {
  run_check gross_weight_conservation tms "
    SELECT 'ERROR', CONCAT_WS(' ', 'shipment_no=', s.shipment_no,
             'total_gross_weight=', s.total_gross_weight,
             '明细 gross_weight 之和=', ROUND(COALESCE(SUM(i.gross_weight), 0), 3))
      FROM tms.tms_shipments s
      LEFT JOIN tms.tms_shipment_items i ON i.shipment_id = s.id
     GROUP BY s.id, s.shipment_no, s.total_gross_weight
    HAVING s.total_gross_weight > 0
       AND COUNT(i.id) > 0          -- 同上：0 明细是「还没录」，不是「对不上」
       AND ABS(s.total_gross_weight - COALESCE(SUM(i.gross_weight), 0)) > $WEIGHT_TOL;"
}

# --- ④ house_no 运单内唯一 -------------------------------------------------
# 评审 P0-4：house_no 是提单分单号（法律单证编号），同一张运单内必须唯一。
# 种子曾按「每个子单各自从 -01 起」拼号，12 张运单全中。
check_house_no_unique() {
  run_check house_no_unique tms "
    SELECT 'ERROR', CONCAT_WS(' ', 'shipment_no=', shipment_no, 'house_no=', house_no,
             '在同一运单内出现', COUNT(*), '次(运单内必须唯一)')
      FROM tms.tms_shipment_items
     WHERE house_no <> ''
     GROUP BY shipment_no, house_no
    HAVING COUNT(*) > 1;"
}

# --- ⑤ 体积重口径（跨库：除数取自 tms.tms_routes.volumetric_divisor）-------
# P0-1 修的是「CBM×6000」应为「CBM×1e6÷6000」，偏大 36 倍；本条就是防它回退。
# 期望值 = CBM × 1e6 ÷ 除数；除数优先取所属航线，取不到（子单未组批）则退回
# VOL_DIVISOR_FALLBACK。
#
# 分三档，因为「按航线取除数」是 M3 才做的事（ExportOrderService.recalcTotals
# 现在写死 6000，其注释明确写了这一点）：
#   与航线除数一致                        → 通过
#   航线除数≠兜底值 但与兜底值一致        → INFO：M1 的已知简化，不是新错误
#   与两者都不一致                        → ERROR：这才是真的算错（36 倍那类）
# INFO 按航线分组汇总成一行，避免同一个航线除数刷屏几十行。
check_volumetric_weight_basis() {
  run_check volumetric_weight_basis oms,tms "
    SELECT 'INFO', CONCAT_WS(' ', 'route_code=', x.rcode, 'volumetric_divisor=', x.divisor,
             CONCAT(COUNT(*), ' 张子单挂在这条航线上，但 volumetric_weight 是按兜底除数 ',
                    $VOL_DIVISOR_FALLBACK, ' 算的(期望 ', x.exp_route, ')'),
             '涉及 order_no=', GROUP_CONCAT(x.order_no ORDER BY x.order_no SEPARATOR ','))
      FROM (
        SELECT o.order_no, IFNULL(r.route_code, '(无航线)') rcode,
               IFNULL(r.volumetric_divisor, $VOL_DIVISOR_FALLBACK) divisor,
               ROUND(o.total_volume * 1000000.0 / IFNULL(r.volumetric_divisor, $VOL_DIVISOR_FALLBACK), 3) exp_route
          FROM oms.oms_export_orders o
          LEFT JOIN oms.oms_export_batches b ON b.tenant_id = o.tenant_id AND b.batch_no = o.batch_no
          LEFT JOIN tms.tms_routes r ON r.id = b.route_id
         WHERE o.total_volume > 0
           AND IFNULL(r.volumetric_divisor, $VOL_DIVISOR_FALLBACK) <> $VOL_DIVISOR_FALLBACK
           AND ABS(o.volumetric_weight - ROUND(o.total_volume * 1000000.0 /
                IFNULL(r.volumetric_divisor, $VOL_DIVISOR_FALLBACK), 3)) > $WEIGHT_TOL
           AND ABS(o.volumetric_weight - ROUND(o.total_volume * 1000000.0 / $VOL_DIVISOR_FALLBACK, 3)) <= $WEIGHT_TOL
      ) x
     GROUP BY x.rcode, x.divisor, x.exp_route;"
  run_check volumetric_weight_basis oms,tms "
    SELECT 'ERROR', CONCAT_WS(' ', 'order_no=', x.order_no,
             'batch_no=', IFNULL(NULLIF(x.batch_no, ''), '(空)'),
             'total_volume=', x.total_volume, 'volumetric_weight=', x.volumetric_weight,
             CONCAT('期望 CBM*1e6/航线除数 = ', x.exp_route, '(除数=', x.divisor, ')'),
             CONCAT('按兜底除数 ', $VOL_DIVISOR_FALLBACK, ' 应为 ', x.exp_fallback),
             '—— 两者都不符，疑似 P0-1 的 ×6000/÷6000 回退(偏大 36 倍)')
      FROM (
        SELECT o.order_no, o.batch_no, o.total_volume, o.volumetric_weight,
               IFNULL(r.route_code, '(无航线)') rcode,
               IFNULL(r.volumetric_divisor, $VOL_DIVISOR_FALLBACK) divisor,
               ROUND(o.total_volume * 1000000.0 / IFNULL(r.volumetric_divisor, $VOL_DIVISOR_FALLBACK), 3) exp_route,
               ROUND(o.total_volume * 1000000.0 / $VOL_DIVISOR_FALLBACK, 3) exp_fallback
          FROM oms.oms_export_orders o
          LEFT JOIN oms.oms_export_batches b ON b.tenant_id = o.tenant_id AND b.batch_no = o.batch_no
          LEFT JOIN tms.tms_routes r ON r.id = b.route_id
         WHERE o.total_volume > 0
           AND ABS(o.volumetric_weight - ROUND(o.total_volume * 1000000.0 /
                IFNULL(r.volumetric_divisor, $VOL_DIVISOR_FALLBACK), 3)) > $WEIGHT_TOL
           AND ABS(o.volumetric_weight - ROUND(o.total_volume * 1000000.0 / $VOL_DIVISOR_FALLBACK, 3)) > $WEIGHT_TOL
      ) x;"
}

# --- ⑥ 同库/跨库弱引用无悬空 ----------------------------------------------
# 库之间没有外键（跨库更不可能），只能靠 LEFT JOIN 查 id 指向的行还在不在。
# 一条检查里用 UNION ALL 覆盖全部弱引用形态，任何一条断了都按行报出来，
# 每行都带上「哪张表哪个列指向了什么、localizer 是什么」。
check_weak_reference_integrity() {
  run_check weak_reference_integrity tms,oms,wms,crm "
    SELECT 'ERROR', CONCAT_WS(' ', rel, loc, key_col, '=', val, '→ 目标表', tgt, '中已无此行')
      FROM (
        SELECT 'tms_shipments.carrier_id' rel, CONCAT('shipment_no=', s.shipment_no) loc,
               'carrier_id' key_col, CAST(s.carrier_id AS CHAR) val, 'tms.tms_carriers' tgt
          FROM tms.tms_shipments s LEFT JOIN tms.tms_carriers c ON c.id = s.carrier_id
         WHERE c.id IS NULL
        UNION ALL
        SELECT 'tms_shipments.sailing_id', CONCAT('shipment_no=', s.shipment_no),
               'sailing_id', CAST(s.sailing_id AS CHAR), 'tms.tms_sailings'
          FROM tms.tms_shipments s LEFT JOIN tms.tms_sailings g ON g.id = s.sailing_id
         WHERE s.sailing_id <> 0 AND g.id IS NULL
        UNION ALL
        SELECT 'tms_routes.carrier_id', CONCAT('route_code=', r.route_code),
               'carrier_id', CAST(r.carrier_id AS CHAR), 'tms.tms_carriers'
          FROM tms.tms_routes r LEFT JOIN tms.tms_carriers c ON c.id = r.carrier_id
         WHERE c.id IS NULL
        UNION ALL
        SELECT 'tms_sailings.route_id', CONCAT('sailing_id=', g.id),
               'route_id', CAST(g.route_id AS CHAR), 'tms.tms_routes'
          FROM tms.tms_sailings g LEFT JOIN tms.tms_routes r ON r.id = g.route_id
         WHERE r.id IS NULL
        UNION ALL
        SELECT 'tms_shipment_items.shipment_id', CONCAT('item_id=', i.id, 'shipment_no=', i.shipment_no),
               'shipment_id', CAST(i.shipment_id AS CHAR), 'tms.tms_shipments'
          FROM tms.tms_shipment_items i LEFT JOIN tms.tms_shipments s ON s.id = i.shipment_id
         WHERE s.id IS NULL
        UNION ALL
        SELECT 'tms_tracking_nodes.shipment_id', CONCAT('node_id=', n.id, 'shipment_no=', n.shipment_no),
               'shipment_id', CAST(n.shipment_id AS CHAR), 'tms.tms_shipments'
          FROM tms.tms_tracking_nodes n LEFT JOIN tms.tms_shipments s ON s.id = n.shipment_id
         WHERE s.id IS NULL
        UNION ALL
        SELECT 'tms_customs_declarations.shipment_id', CONCAT('declaration_no=', d.declaration_no),
               'shipment_id', CAST(d.shipment_id AS CHAR), 'tms.tms_shipments'
          FROM tms.tms_customs_declarations d LEFT JOIN tms.tms_shipments s ON s.id = d.shipment_id
         WHERE s.id IS NULL
        UNION ALL
        SELECT 'oms_export_order_items.order_id', CONCAT('item_id=', i.id, 'order_no=', i.order_no),
               'order_id', CAST(i.order_id AS CHAR), 'oms.oms_export_orders'
          FROM oms.oms_export_order_items i LEFT JOIN oms.oms_export_orders o ON o.id = i.order_id
         WHERE o.id IS NULL
        UNION ALL
        SELECT 'wms_pack_task_items.task_id', CONCAT('item_id=', i.id),
               'task_id', CAST(i.task_id AS CHAR), 'wms.wms_pack_tasks'
          FROM wms.wms_pack_task_items i LEFT JOIN wms.wms_pack_tasks t ON t.id = i.task_id
         WHERE t.id IS NULL
        UNION ALL
        SELECT 'oms_export_orders.batch_no', CONCAT('order_no=', o.order_no),
               'batch_no', o.batch_no, 'oms.oms_export_batches'
          FROM oms.oms_export_orders o
          LEFT JOIN oms.oms_export_batches b ON b.tenant_id = o.tenant_id AND b.batch_no = o.batch_no
         WHERE o.batch_no <> '' AND b.id IS NULL
        UNION ALL
        SELECT 'tms_shipments.batch_no(跨库)', CONCAT('shipment_no=', s.shipment_no),
               'batch_no', s.batch_no, 'oms.oms_export_batches'
          FROM tms.tms_shipments s
          LEFT JOIN oms.oms_export_batches b ON b.tenant_id = s.tenant_id AND b.batch_no = s.batch_no
         WHERE s.batch_no <> '' AND b.id IS NULL
        UNION ALL
        SELECT 'tms_shipment_items.order_no(跨库)', CONCAT('item_id=', i.id, 'house_no=', i.house_no),
               'order_no', i.order_no, 'oms.oms_export_orders'
          FROM tms.tms_shipment_items i
          LEFT JOIN oms.oms_export_orders o ON o.tenant_id = i.tenant_id AND o.order_no = i.order_no
         WHERE i.order_no <> '' AND o.id IS NULL
      ) dangling;"
}

# --- ⑦ 跨表状态一致性：子单状态不得领先于其运单 ---------------------------
# 子单状态机（EXPORT_ORDER）与运单状态机（SHIPMENT）是两套词表，粒度也不同，
# 不能直接比下标——直接比会把「子单已打包、运单还在订舱」这种完全正常的业务
# 现状判成错误（现网会有 18 条这种假警报）。所以先把两边都映射到同一条 11 级
# 「物流阶段」轴上再比：
#
#   阶段 1  准备       order DRAFT/CONFIRMED         shipment DRAFT
#   阶段 2  备货/组批  order PICKING/PACKED           shipment BOOKING / batch WAITING_GOODS
#   阶段 3  已订舱     order -                        shipment BOOKED/PICKED_UP, batch BOOKED/LOADING
#   阶段 4  已报关     order EXPORT_DECLARED          shipment EXPORT_DECLARED
#   阶段 5  已开航     order SHIPPED/IN_TRANSIT       shipment DEPARTED/IN_TRANSIT
#   阶段 6  已到港     order ARRIVED                  shipment ARRIVED
#   阶段 7  清关中     order CUSTOMS_CLEARING         shipment CLEARING
#   阶段 8  已清关     order CUSTOMS_CLEARED          shipment CLEARED
#   阶段 9  尾程       order LAST_MILE                shipment LAST_MILE
#   阶段 10 已送达     order DELIVERED                shipment DELIVERED
#   阶段 11 完成       order CLOSED                   shipment POD_CONFIRMED
#
# EXCEPTION / CANCELLED / RETURNED 没有可比的阶段，一律跳过（异常挂起不是「更后面」）。
# 规则：order_phase > shipment_phase 即子单领先，报 ERROR。
check_order_status_not_ahead_of_shipment() {
  run_check order_status_not_ahead_of_shipment oms,tms "
    SELECT 'ERROR', CONCAT_WS(' ', 'order_no=', x.order_no, '子单 status=', x.ostatus,
             CONCAT('(阶段 ', x.oph, ')'), 'house_no=', x.house_no,
             '所属 shipment_no=', x.shipment_no, '运单 status=', x.sstatus,
             CONCAT('(阶段 ', x.sph, ')'), '→ 子单领先于运单')
      FROM (
        SELECT DISTINCT o.order_no, o.status ostatus, s.shipment_no, s.status sstatus, i.house_no,
          CASE FIELD(o.status, $ORDER_STATE_LIST)
            WHEN 1 THEN 1 WHEN 2 THEN 1 WHEN 3 THEN 2 WHEN 4 THEN 2 WHEN 5 THEN 4 WHEN 6 THEN 5
            WHEN 7 THEN 5 WHEN 8 THEN 6 WHEN 9 THEN 7 WHEN 10 THEN 8 WHEN 11 THEN 9
            WHEN 12 THEN 10 WHEN 13 THEN 11 ELSE NULL END oph,
          -- 有效阶段 = GREATEST(运单头阶段, 最后一条轨迹节点的阶段)。
          -- 为什么不能只看运单头：addTracking 只追加轨迹、不推状态（刻意如此，
          -- 见ShipmentService.addTracking 的注释），所以「证据领先状态」是正常
          -- 工作流，运单头可能落后于自己的轨迹。这时拿头当唯一权威会把报错
          -- 指向错的表——本轮就误报过「母单领先于运单」，而实际是运单头该跟上。
          GREATEST(
            CASE FIELD(s.status, $SHIPMENT_STATE_LIST)
              WHEN 1 THEN 1 WHEN 2 THEN 2 WHEN 3 THEN 3 WHEN 4 THEN 3 WHEN 5 THEN 4 WHEN 6 THEN 5
              WHEN 7 THEN 5 WHEN 8 THEN 6 WHEN 9 THEN 7 WHEN 10 THEN 8 WHEN 11 THEN 9
              WHEN 12 THEN 10 WHEN 13 THEN 11 ELSE NULL END,
            (SELECT CASE FIELD(n.node_code, $SHIPMENT_STATE_LIST)
                      WHEN 1 THEN 1 WHEN 2 THEN 2 WHEN 3 THEN 3 WHEN 4 THEN 3 WHEN 5 THEN 4
                      WHEN 6 THEN 5 WHEN 7 THEN 5 WHEN 8 THEN 6 WHEN 9 THEN 7 WHEN 10 THEN 8
                      WHEN 11 THEN 9 WHEN 12 THEN 10 WHEN 13 THEN 11 ELSE NULL END
               FROM tms.tms_tracking_nodes n
              WHERE n.shipment_id = s.id
              ORDER BY n.node_time DESC, n.id DESC LIMIT 1)
          ) sph
          FROM oms.oms_export_orders o
          JOIN tms.tms_shipment_items i ON i.tenant_id = o.tenant_id AND i.order_no = o.order_no
          JOIN tms.tms_shipments s ON s.id = i.shipment_id
      ) x
     WHERE x.oph IS NOT NULL AND x.sph IS NOT NULL AND x.oph > x.sph;"
}

# --- ⑧ 计费重口径 ---------------------------------------------------------
# P1-2：之前 chargeable_weight 直接抄毛重，等于每票都算不出体积重溢价。
# 口径按模式：海运/拼箱 max(毛重, 体积CBM)（revenue ton，1CBM=1t）；
# 空运/快递 max(毛重, 体积重)；其他按毛重。容差 $WEIGHT_TOL。
check_chargeable_weight_basis() {
  run_check chargeable_weight_basis tms "
    SELECT 'ERROR', CONCAT_WS(' ', 'shipment_no=', s.shipment_no, 'mode=', s.mode,
             'chargeable_weight=', s.chargeable_weight,
             CONCAT('期望=', ROUND(CASE WHEN s.mode IN ('SEA','LCL')
                    THEN GREATEST(s.total_gross_weight, s.total_volume)
                    WHEN s.mode IN ('AIR','EXPRESS')
                    THEN GREATEST(s.total_gross_weight, s.volumetric_weight)
                    ELSE s.total_gross_weight END, 3)),
             '(gross=', s.total_gross_weight, 'volume=', s.total_volume,
             'volumetric=', s.volumetric_weight, ')')
      FROM tms.tms_shipments s
     WHERE s.total_gross_weight > 0
       AND ABS(s.chargeable_weight - CASE WHEN s.mode IN ('SEA','LCL')
              THEN GREATEST(s.total_gross_weight, s.total_volume)
              WHEN s.mode IN ('AIR','EXPRESS')
              THEN GREATEST(s.total_gross_weight, s.volumetric_weight)
              ELSE s.total_gross_weight END) > $WEIGHT_TOL;"
}

# --- ⑨ VGM 口径 -----------------------------------------------------------
# P1-2：SOLAS VI/2 的核实总重不是毛重。Method 2 = Σ(货物+包装) + 集装箱自重。
# 只对海运/拼箱有意义（SOLAS 只管海运），其他模式不查。
check_vgm_weight_basis() {
  run_check vgm_weight_basis tms "
    SELECT 'ERROR', CONCAT_WS(' ', 'shipment_no=', s.shipment_no, 'mode=', s.mode,
             'vgm_weight=', s.vgm_weight,
             CONCAT('期望 total_gross_weight + container_tare_weight = ',
                    ROUND(s.total_gross_weight + s.container_tare_weight, 3)),
             '(gross=', s.total_gross_weight, 'tare=', s.container_tare_weight, ')')
      FROM tms.tms_shipments s
     WHERE s.mode IN ('SEA', 'LCL')
       AND s.total_gross_weight > 0
       AND ABS(s.vgm_weight - (s.total_gross_weight + s.container_tare_weight)) > $WEIGHT_TOL;"
}

# --- ⑩ 整柜必须填集装箱自重 ----------------------------------------------
# tare=0 时 VGM Method 2 在结构上就算不出来。未开航的还没到强制时点，报 WARN；
# 已经开航的，SOLAS 明确「未取得 VGM 的柜不得装载」，报 ERROR。
check_container_tare_filled() {
  run_check container_tare_filled tms "
    SELECT IF(FIELD(s.status, 'DEPARTED', 'IN_TRANSIT', 'ARRIVED', 'CLEARING', 'CLEARED',
                    'LAST_MILE', 'DELIVERED', 'POD_CONFIRMED') > 0, 'ERROR', 'WARN'),
           CONCAT_WS(' ', 'shipment_no=', s.shipment_no, 'container_type=', s.container_type,
             'container_tare_weight=', s.container_tare_weight, 'status=', s.status,
             '→ 整柜必须有自重，否则 VGM Method 2 算不出来')
      FROM tms.tms_shipments s
     WHERE s.container_type IN ('20GP', '40GP', '40HQ')
       AND s.container_tare_weight <= 0;"
}

# --- ⑪ 危险品汇总透传 -----------------------------------------------------
# P1-1：明细标了危险品但运单层 is_dangerous / un_number 全空 → 报关会漏报。
# 两个方向都查：
#   A 箱明细(tms_shipment_items)带 UN 号 → 运单必须 is_dangerous=1
#   B 子单明细(oms_export_order_items)是危险品 → 运单必须 is_dangerous=1
# B 才是 P1-1 的原始现场：52 号迁移就是从 oms_export_order_items 汇总到运单的。
# 刻意不让 tms_shipment_items 当唯一依据——那列 UN 只在「这个箱子本身装了危险
# 品」时才填，现网 44 条里只有 23 条有值，拿它当权威会误报（SHP20261010002 的
# 四个箱明细 UN 全空，但它所属子单 EXP20261010009 确实有 UN3481，运单标得没错）。
# 反方向（运单标了危险品但箱明细没 UN）不查：箱级 UN 允许稀疏，权威在 OMS 侧。
check_dangerous_goods_rollup() {
  run_check dangerous_goods_rollup tms "
    SELECT 'ERROR', CONCAT_WS(' ', 'shipment_no=', s.shipment_no,
             'is_dangerous=', s.is_dangerous,
             'un_number=', IFNULL(NULLIF(s.un_number, ''), '(空)'),
             CONCAT('但箱明细带 UN 号: item_id=', i.id, ' order_no=', i.order_no,
                    ' un_number=', i.un_number))
      FROM tms.tms_shipment_items i
      JOIN tms.tms_shipments s ON s.id = i.shipment_id
     WHERE i.un_number <> '' AND s.is_dangerous = 0;"
  run_check dangerous_goods_rollup tms,oms "
    SELECT 'ERROR', CONCAT_WS(' ', 'shipment_no=', s.shipment_no,
             'is_dangerous=', s.is_dangerous,
             'un_number=', IFNULL(NULLIF(s.un_number, ''), '(空)'),
             CONCAT('但子单明细带危险品: order_no=', o.order_no, ' sku=', i.sku,
                    ' un_number=', i.un_number))
      FROM tms.tms_shipments s
      JOIN tms.tms_shipment_items si ON si.shipment_id = s.id
      JOIN oms.oms_export_orders o ON o.tenant_id = si.tenant_id AND o.order_no = si.order_no
      JOIN oms.oms_export_order_items i ON i.order_id = o.id
     WHERE i.is_dangerous = 1 AND i.un_number <> '' AND s.is_dangerous = 0
     GROUP BY s.shipment_no, s.is_dangerous, s.un_number, o.order_no, i.sku, i.un_number;"
}

# --- ⑫ 在途状态语义 -------------------------------------------------------
# P2-5：HELD（海关查验扣留）与 LOST（丢件，有理赔后果）必须分开。种子曾把「整批
# 查验」写成 LOST，等于凭空造出一批理赔事件，将来「丢件率」看板算出来的其实是
# 查验率。所以除了值域，还要查「状态与 remark 说法打架」：
#   LOST 但 remark 说货被查验/扣留（还在海关手里）→ 数据说谎，报 ERROR。
# 这里用「有扣留类措辞 且 没有真丢件类措辞」来判定，是为了不误伤那条正确的 LOST
# 样本——它的 remark 里有「与 HELD 的海关查验不是一回事」这种对照句，光靠
# remark LIKE '%查验%' 会把正确的行也报出来。
check_in_transit_status_semantics() {
  run_check in_transit_status_semantics wms "
    SELECT 'ERROR', CONCAT_WS(' ', 'wms_in_transit.id=', t.id, 'status=', t.status,
             '不在 IntlStateMachine.IN_TRANSIT 的 key 集合内(合法值: ', $TRANSIT_STATE_SQL, ')',
             CONCAT('remark=', IFNULL(NULLIF(t.remark, ''), '(空)')))
      FROM wms.wms_in_transit t
     WHERE t.status NOT IN ($TRANSIT_STATE_SQL);"
  run_check in_transit_status_semantics wms "
    SELECT 'ERROR', CONCAT_WS(' ', 'wms_in_transit.id=', t.id, 'status=LOST',
             '但 remark 说的是海关查验/扣留(货在手里，不是丢件): remark=', t.remark)
      FROM wms.wms_in_transit t
     WHERE t.status = 'LOST'
       AND (t.remark LIKE '%查验%' OR t.remark LIKE '%扣留%' OR t.remark LIKE '%滞留%')
       AND t.remark NOT LIKE '%赔付%' AND t.remark NOT LIKE '%索赔%'
       AND t.remark NOT LIKE '%确认丢失%' AND t.remark NOT LIKE '%丢件率%';"
}

# --- ⑬ 合规样本存在性（INFO） -------------------------------------------
# 目的是查「缺覆盖」而不只是查「错」：如果一条「HIT 且停在 EXPORT_DECLARED 之前」
# 的运单都没有，那么「HIT 不得报关」这条校验在演示数据上从未被触发过——它没报错
# 不代表它对，只代表它没被执行过。把这种可见性做出来，比再加一条断言更值钱。
# 反方向也查：HIT 却已经报关/开航的，是真的错了（ERROR）。
check_sanction_hit_sample_coverage() {
  run_check sanction_hit_sample_coverage tms "
    SELECT 'ERROR', CONCAT_WS(' ', 'shipment_no=', s.shipment_no, 'sanction_flag=HIT',
             '但 status=', s.status, '→ 已过 EXPORT_DECLARED，制裁命中的货不得报关')
      FROM tms.tms_shipments s
     WHERE s.sanction_flag = 'HIT'
       AND FIELD(s.status, 'EXPORT_DECLARED', 'DEPARTED', 'IN_TRANSIT', 'ARRIVED',
                 'CLEARING', 'CLEARED', 'LAST_MILE', 'DELIVERED', 'POD_CONFIRMED') > 0;"
  run_check sanction_hit_sample_coverage tms "
    SELECT 'INFO', CONCAT('tms_shipments 中没有 sanction_flag=HIT 且停在 EXPORT_DECLARED 之前的运单',
             CONCAT('(当前 HIT 运单共 ',
                    (SELECT COUNT(*) FROM tms.tms_shipments WHERE sanction_flag = 'HIT'), ' 条)'),
             '→「HIT 不得报关」这条校验在当前数据上从未被触发过，其正确性未被验证')
      FROM DUAL
     WHERE (SELECT COUNT(*) FROM tms.tms_shipments
             WHERE sanction_flag = 'HIT'
               AND FIELD(status, 'DRAFT', 'BOOKING', 'BOOKED', 'PICKED_UP') > 0) = 0;"
}

# --- ⑭ carrier_code 弱引用（P2-7） ---------------------------------------
# crm 侧渠道商的 carrier_code 指向 tms_carriers.code，跨库无外键。
# 空串表示「不是承运商」（报关行/海外仓/卡航），不查。
check_carrier_code_reference() {
  run_check carrier_code_reference crm,tms "
    SELECT 'ERROR', CONCAT_WS(' ', 'crm_carrier_partners.id=', p.id,
             'partner_name=', p.partner_name, 'carrier_code=', p.carrier_code,
             '→ tms.tms_carriers.code 中没有这个承运商代码')
      FROM crm.crm_carrier_partners p
      LEFT JOIN tms.tms_carriers c ON c.code = p.carrier_code
     WHERE p.carrier_code <> '' AND c.id IS NULL;"
}

# --- ⑮ 运单体积守恒（本轮新增，与 ③ 同源） -----------------------------
# 评审必修-3 说的是「件数守恒 + 箱量对得上」，但 50 号迁移只按件数重算了 pieces：
# 同一子单被拆成多箱的行（EXP20261010001 占两行）把 gross_weight / volume 也各
# 算了两次。体积与毛重是同一份重复，一条 SQL 就能查出来，所以一起看。
check_volume_conservation() {
  run_check volume_conservation tms "
    SELECT 'ERROR', CONCAT_WS(' ', 'shipment_no=', s.shipment_no,
             'total_volume=', s.total_volume,
             '明细 volume 之和=', ROUND(COALESCE(SUM(i.volume), 0), 4))
      FROM tms.tms_shipments s
      LEFT JOIN tms.tms_shipment_items i ON i.shipment_id = s.id
     GROUP BY s.id, s.shipment_no, s.total_volume
    HAVING COUNT(i.id) > 0
       AND s.total_volume > 0
       AND ABS(s.total_volume - COALESCE(SUM(i.volume), 0)) > $VOLUME_TOL;"
}

# --- ⑯ 运单状态合法性（本轮新增，与 ① 对称） ----------------------------
# P0-3 是母单写进了子单词表的值；同一个错发生在运单上没人查。合法集合取自
# tms IntlStateMachine.SHIPMENT 的 key。
check_shipment_status_legality() {
  run_check shipment_status_legality tms "
    SELECT 'ERROR', CONCAT_WS(' ', 'shipment_no=', s.shipment_no, 'status=', s.status,
             '不在 IntlStateMachine.SHIPMENT 的 key 集合内(合法值: ', $SHIPMENT_STATE_SQL, ')',
             CONCAT('remark=', IFNULL(NULLIF(s.remark, ''), '(空)')))
      FROM tms.tms_shipments s
     WHERE s.status NOT IN ($SHIPMENT_STATE_SQL);"
}

# --- ⑰ 母单不得领先于其下运单（本轮新增） ------------------------------
# 母单与运单描述的是同一票货的同一段物理位移，母单按「下辖运单的最远阶段」推进。
# 母单阶段 > 其下运单阶段，说明有人把母单点过去了而运单没跟上（MEXP20261010008
# 就是这样：母单 IN_TRANSIT、运单还 BOOKED，且该运单没有任何 DEPARTED 轨迹节点）。
check_batch_shipment_status_consistency() {
  run_check batch_shipment_status_consistency oms,tms "
    SELECT 'ERROR', CONCAT_WS(' ', 'batch_no=', x.batch_no, '母单 status=', x.bstatus,
             CONCAT('(阶段 ', x.bph, ')'), '其下 shipment_no=', x.shipment_no,
             '运单 status=', x.sstatus, CONCAT('(阶段 ', x.sph, ')'),
             '→ 母单领先于运单')
      FROM (
        SELECT b.batch_no, b.status bstatus, s.shipment_no, s.status sstatus,
          CASE FIELD(b.status, $BATCH_FIELD_LIST)
            WHEN 1 THEN 1 WHEN 2 THEN 1 WHEN 3 THEN 2 WHEN 4 THEN 2 WHEN 5 THEN 3 WHEN 6 THEN 3
            WHEN 7 THEN 5 WHEN 8 THEN 5 WHEN 9 THEN 6 WHEN 10 THEN 7 WHEN 11 THEN 8
            WHEN 12 THEN 9 WHEN 13 THEN 10 WHEN 14 THEN 11 ELSE NULL END bph,
          GREATEST(
            CASE FIELD(s.status, $SHIPMENT_STATE_LIST)
              WHEN 1 THEN 1 WHEN 2 THEN 2 WHEN 3 THEN 3 WHEN 4 THEN 3 WHEN 5 THEN 4
              WHEN 6 THEN 5 WHEN 7 THEN 5 WHEN 8 THEN 6 WHEN 9 THEN 7 WHEN 10 THEN 8
              WHEN 11 THEN 9 WHEN 12 THEN 10 WHEN 13 THEN 11 ELSE NULL END,
            (SELECT CASE FIELD(n.node_code, $SHIPMENT_STATE_LIST)
                      WHEN 1 THEN 1 WHEN 2 THEN 2 WHEN 3 THEN 3 WHEN 4 THEN 3 WHEN 5 THEN 4
                      WHEN 6 THEN 5 WHEN 7 THEN 5 WHEN 8 THEN 6 WHEN 9 THEN 7 WHEN 10 THEN 8
                      WHEN 11 THEN 9 WHEN 12 THEN 10 WHEN 13 THEN 11 ELSE NULL END
               FROM tms.tms_tracking_nodes n
              WHERE n.shipment_id = s.id
              ORDER BY n.node_time DESC, n.id DESC LIMIT 1)
          ) sph
          FROM oms.oms_export_batches b
          JOIN tms.tms_shipments s ON s.tenant_id = b.tenant_id AND s.batch_no = b.batch_no
      ) x
     WHERE x.bph IS NOT NULL AND x.sph IS NOT NULL AND x.bph > x.sph;"
}

# --- ⑱ 状态集合漂移自检 ---------------------------------------------------
# 内嵌的三份状态集合是从 IntlStateMachine.java 抄的。抄的东西会烂：有人给状态机
# 加了一个状态，脚本还按旧集合判合法，于是新状态的数据全被报成「非法状态」——
# 这种假警报会让人从此不再相信巡检输出，比不写更糟。所以能读到源码时，把内嵌
# 集合和源码里那张状态表用到的状态字面量对一遍。
# 读不到源码（脚本上传到服务器、旁边没有仓库）时本项静默跳过，不计入检查数。
java_map_states() {
  # 抽出 java 文件里**指定那张表**用到的全部状态字面量（去重排序）。
  # 参数：<文件> <Map 变量名，如 EXPORT_BATCH>
  #
  # 两个必须做对的细节，都是踩出来的：
  #
  # 1) 按表切块，不能 grep 整个文件。IntlStateMachine.java 里 EXPORT_ORDER /
  #    EXPORT_BATCH / SHIPMENT / DECLARATION 四张表同处一个文件，全文件 grep 会把
  #    PACKED/PICKED_UP（子单词表）、FILED/HELD（报关单词表）算进来，于是每张表都报
  #    「源码有而脚本没有」——假警报比没有这项检查更糟。
  #
  # 2) 取「key ∪ 迁移目标」，不是只取 key。TMS 的 SHIPMENT 表里 CANCELLED 与
  #    POD_CONFIRMED 只出现在别人的 value 里（DRAFT→CANCELLED、上一状态→POD_CONFIRMED），
  #    自己没有 key。按 key 判合法，会把「已取消」「已回传 POD」的正常运单报成非法状态。
  #    数据库 status 列能取到的值就是这张表能表示的全部状态，所以比对基准是并集。
  #
  # 结束锚点是「行尾的 );」：这几张表的最后一个 entry 都是
  # `Map.entry("CANCELLED", Set.of()));` 这种右括号跟在内容后面的写法，没有独立成行的
  # `);`（锚点写成 `^[[:space:]]*);$` 时 sed 找不到结束行，会一路读到文件尾）。
  local file="$1" mapname="$2"
  [ -f "$file" ] || return 1
  sed -n "/${mapname}[[:space:]]*=[[:space:]]*Map\.ofEntries(/,/);[[:space:]]*$/p" "$file" \
    | grep -o '"[A-Z_]\{2,\}"' \
    | tr -d '"' \
    | sort -u
}

check_state_set_drift() {
  # 每份状态集合算一项检查。源码不在旁边（脚本上传到服务器就是这个情况）时
  # 整项跳过且不计数——没读到源码就没有结论，不该报成「通过」。
  local spec var mapname file embedded actual missing extra
  for spec in \
    "BATCH_STATES|EXPORT_BATCH|$OMS_STATE_FILE" \
    "SHIPMENT_STATES|SHIPMENT|$TMS_STATE_FILE" \
    "TRANSIT_STATES|IN_TRANSIT|$WMS_STATE_FILE"; do
    var="${spec%%|*}"
    spec="${spec#*|}"
    mapname="${spec%%|*}"
    file="${spec#*|}"
    [ -z "$file" ] || [ ! -f "$file" ] && continue
    CHECK_CNT=$((CHECK_CNT + 1))
    # shellcheck disable=SC2086  # 依赖词分割把集合拆成多行
    embedded="$(printf '%s\n' ${!var} | sort -u)"
    # 内嵌集合为空 = 脚本自身被改坏了，这时任何比对都没有意义，必须报出来。
    if [ -z "$embedded" ]; then
      emit ERROR state_set_drift "内嵌 $mapname 状态集合为空，本项无法比对（脚本自身可能已被改坏）"
      continue
    fi
    actual="$(java_map_states "$file" "$mapname")"
    # 切块失败（Map 声明格式变了）时不能当成「一致」——那等于把自检关掉了。
    if [ -z "$actual" ]; then
      emit WARN state_set_drift "无法从 $file 里定位 $mapname 的 Map 声明（格式可能变了），本项对 $mapname 无结论"
      continue
    fi
    missing="$(comm -23 <(printf '%s\n' "$actual") <(printf '%s\n' "$embedded") | tr '\n' ' ')"
    extra="$(comm -13 <(printf '%s\n' "$actual") <(printf '%s\n' "$embedded") | tr '\n' ' ')"
    if [ -n "$missing" ] || [ -n "$extra" ]; then
      emit WARN state_set_drift "$mapname 集合与 $file 不一致：源码有而脚本没有 [${missing:-无}]；脚本有而源码没有 [${extra:-无}] → 同步后本脚本的合法性判定才有意义"
    fi
  done
  return 0
}

# --- ⑲ 自检：故意跑一条坏 SQL，验证 run_check 的失败处理 ----------------
# 这不是业务检查，是对本脚本自己的断言。默认不跑（CHECKS 里没有它），
# 用 --self-test 打开：
#   1) 一条坏 SQL 之后，后续检查仍然要跑完并打印汇总（不中断）
#   2) 坏 SQL 要报成 ERROR 而不是被当成通过
#   3) 密码不出现在任何输出里
# 断言「检查脚本自己没坏」和断言「数据没坏」同等重要：巡检一旦静默失效，
# 部署流程会一直显示绿灯。
run_self_test() {
  CHECK_CNT=$((CHECK_CNT + 1))
  run_check self_test_broken_sql "tms" "SELEC this is not valid sql;"
  run_check self_test_after_broken "tms" \
    "SELECT 'ERROR', 'self-test: 坏 SQL 之后的检查仍然执行到了这里' FROM DUAL WHERE 1=1;"
  CHECK_CNT=$((CHECK_CNT + 1))
}


# --- ⑲ 运单头不得落后于自己最后一条轨迹节点 --------------------------------
# 盲区 2 的正向检查。addTracking 只追加轨迹不推状态，所以「头落后于自己的轨迹」
# 是可能出现的；那不是 OMS 的错，是运单头该跟上。权威顺序：轨迹 > 运单头 > OMS。
check_shipment_header_not_behind_tracking() {
  run_check shipment_header_not_behind_tracking tms "
    SELECT 'ERROR', CONCAT_WS(' ', 'shipment_no=', x.shipment_no,
             '运单头 status=', x.hstatus, '但最后一条轨迹节点=', x.last_node,
             CONCAT('(', x.last_time, ')'), '→ 头状态落后于自己的轨迹')
      FROM (
        SELECT s.id, s.shipment_no, s.status hstatus,
          (SELECT n.node_code FROM tms.tms_tracking_nodes n
            WHERE n.shipment_id = s.id ORDER BY n.node_time DESC, n.id DESC LIMIT 1) last_node,
          (SELECT n.node_time FROM tms.tms_tracking_nodes n
            WHERE n.shipment_id = s.id ORDER BY n.node_time DESC, n.id DESC LIMIT 1) last_time
          FROM tms.tms_shipments s
      ) x
     WHERE x.last_node IS NOT NULL
       AND FIELD(x.last_node, $SHIPMENT_STATE_LIST) > 0
       AND FIELD(x.hstatus, $SHIPMENT_STATE_LIST) > 0
       AND FIELD(x.last_node, $SHIPMENT_STATE_LIST) > FIELD(x.hstatus, $SHIPMENT_STATE_LIST)
       AND x.last_node NOT IN ('EXCEPTION', 'CANCELLED');"
}

# --- ⑳ 时区口径：etd_date 必须是起运港当地日期 ------------------------------
# P2-2：列注释写着「起运港当地日期」，种子却把 UTC 日期抄了进去（43 张子单里 6 行差一天），
# 于是子单列表显示 10-13、详情页同一票货显示 10-14 04:00，同一页面自己跟自己打架。
# 口径：etd_date = DATE(tms_shipments.etd_at + 8h)（Asia/Shanghai）。
# 只查 ETD 不查 ETA：ETA 是目的港当地日期，需要 pod_code→国家→时区表，本项目还没有，
# 拿起运港时区换算 ETA 是用一个错换另一个错（迁移 57 号的注释里写明了）。
check_etd_date_local_convention() {
  run_check etd_date_local_convention oms,tms "
    SELECT 'ERROR', CONCAT_WS(' ', 'order_no=', o.order_no,
             'shipment_no=', s.shipment_no,
             'etd_date=', o.etd_date,
             '期望(起运港当地)=', DATE(s.etd_at + INTERVAL 8 HOUR),
             'etd_at(UTC)=', s.etd_at, '→ 当地日期列里存的是 UTC 日期')
      FROM oms.oms_export_orders o
      JOIN tms.tms_shipments s ON s.tenant_id = o.tenant_id AND s.batch_no = o.batch_no
     WHERE o.etd_date IS NOT NULL AND s.etd_at IS NOT NULL
       AND o.etd_date <> DATE(s.etd_at + INTERVAL 8 HOUR);"
}

# --- ㉑ 在途量 vs 源仓批次可发量 -------------------------------------------
# P2-4：在途推到「已入库」时会扣源仓批次；若源仓可发量不足，会按实扣并把缺口
# 写进行remark。**这在入库那一刻才暴露，那时货已经在门口了**。
# 巡检要能提前看见：在途量 > 源仓可发量，意味着这批货一旦入库就会记缺口。
# 可发量 = 在库 - 占用（占用中的库存本来就不该发货）。
check_transit_vs_source_batch() {
  run_check transit_vs_source_batch wms "
    -- 用派生表做聚合，再在外层按「在途 > 可发」过滤。原写法把 b.id 等放进
    -- HAVING 的 IF() 里，MySQL 8 会报 Unknown column 'b.id' in 'having clause'——
    -- HAVING 里只能引用 GROUP BY 的列或聚合值，而 IF() 包裹的列算不出别名。
    SELECT 'WARN', CONCAT_WS(' ', 'wms_in_transit.id=', x.id, 'shipment_no=', x.shipment_no,
             'sku=', x.sku, '在途量=', x.transit_qty, '源仓可发量=', x.available,
             '→ 入库时只能实扣', x.available, '，缺口', x.transit_qty - x.available)
      FROM (
        SELECT t.id, t.shipment_no, t.sku,
               SUM(t.quantity) AS transit_qty,
               IF(b.id IS NULL, 0, GREATEST(b.quantity - b.reserved_qty, 0)) AS available
          FROM wms.wms_in_transit t
          LEFT JOIN wms.wms_inventory_batches b
            ON b.tenant_id = t.tenant_id AND b.warehouse_id = t.from_warehouse_id
           AND b.sku = t.sku AND b.batch_no = t.batch_no
         WHERE t.status IN ('IN_TRANSIT', 'ARRIVED')
         GROUP BY t.id, t.shipment_no, t.sku, b.id, b.quantity, b.reserved_qty
      ) x
     WHERE x.transit_qty > x.available;"
}

# --- ㉒ 母单/子单不得领先于「已开航」这个物理事实 ---------------------------
# actual_etd_at 由实际开航写入，是**不可伪造的物理证据**（不像 status 那样
# 可能因为一次漏推而与证据不符）。它为空却声称已开航/在途/到港，就是数据在说谎。
check_no_departure_claim_without_evidence() {
  run_check no_departure_claim_without_evidence oms,tms "
    SELECT 'ERROR', CONCAT_WS(' ', 'batch_no=', b.batch_no, '母单 status=', b.status,
             '但其下运单 ', s.shipment_no, ' 的 actual_etd_at 为空',
             '→ 没有任何物理事件支持这批货已开航')
      FROM oms.oms_export_batches b
      JOIN tms.tms_shipments s
        ON s.tenant_id = b.tenant_id AND s.batch_no = b.batch_no
     WHERE b.status IN ('DEPARTED', 'IN_TRANSIT', 'ARRIVED', 'CLEARING', 'CLEARED',
                        'LAST_MILE', 'DELIVERED', 'POD_CONFIRMED')
       AND s.actual_etd_at IS NULL;"
}

# =============================================================================
# 检查清单：--list 与默认执行顺序共用一份，避免两处不同步。
# 格式：<函数名>|<涉及库>|<一句话说明>
# =============================================================================
CHECKS=(
  "check_batch_status_legality|oms|① 母单 status 必须在 EXPORT_BATCH 状态机 key 集合内（P0-3）"
  "check_pieces_conservation|tms|② 运单 total_pieces = 明细 pieces 之和（P0-5）"
  "check_gross_weight_conservation|tms|③ 运单 total_gross_weight = 明细 gross_weight 之和（P0-5）"
  "check_house_no_unique|tms|④ house_no 在同一运单内唯一（P0-4）"
  "check_volumetric_weight_basis|oms,tms|⑤ 体积重 = CBM×1e6÷航线除数，防 ×6000/÷6000 回退（P0-1）"
  "check_weak_reference_integrity|tms,oms,wms,crm|⑥ 各表 xxx_id / batch_no / order_no 弱引用无悬空"
  "check_order_status_not_ahead_of_shipment|oms,tms|⑦ 子单状态不得领先于运单（映射到 11 级物流阶段轴再比）"
  "check_chargeable_weight_basis|tms|⑧ 计费重 = 按模式的 max() 公式（P1-2）"
  "check_vgm_weight_basis|tms|⑨ 海运/拼箱 VGM = 毛重 + 集装箱自重（P1-2）"
  "check_container_tare_filled|tms|⑩ 整柜 container_tare_weight 必须 > 0，否则 VGM 算不出来"
  "check_dangerous_goods_rollup|tms,oms|⑪ 明细带危险品/UN 号则运单必须 is_dangerous=1（P1-1）"
  "check_in_transit_status_semantics|wms|⑫ 在途 status 合法；且 LOST 不得配「查验/扣留」remark（P2-5）"
  "check_sanction_hit_sample_coverage|tms|⑬ HIT 不得报关；且至少要有 1 条 HIT 样本，否则 INFO"
  "check_carrier_code_reference|crm,tms|⑭ crm.carrier_code 必须存在于 tms_carriers.code（P2-7）"
  "check_volume_conservation|tms|⑮ 运单 total_volume = 明细 volume 之和（新增，与 ③ 同源）"
  "check_shipment_status_legality|tms|⑯ 运单 status 必须在 SHIPMENT 状态机 key 集合内（新增）"
  "check_batch_shipment_status_consistency|oms,tms|⑰ 母单不得领先于其下运单（新增）"
  "check_state_set_drift|tms,oms,wms|⑱ 内嵌状态集合 vs IntlStateMachine.java 漂移自检"
  "check_shipment_header_not_behind_tracking|tms|⑲ 运单头不得落后于自己最后一条轨迹节点（新增，权威顺序：轨迹>运单头>OMS）"
  "check_etd_date_local_convention|oms,tms|⑳ etd_date 必须是起运港当地日期 = DATE(etd_at+8h)（新增，P2-2）"
  "check_transit_vs_source_batch|wms|㉑ 在途量不得大于源仓批次可发量（新增，P2-4 的提前预警）"
  "check_no_departure_claim_without_evidence|oms,tms|㉒ actual_etd_at 为空却声称已开航即为说谎（新增）"
)

# ============================================================================
# 主流程
# ============================================================================
main() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --db)
        [ $# -ge 2 ] || { echo "--db 需要一个库名（$KNOWN_DBS）" >&2; exit 2; }
        ONLY_DB="$2"; shift 2 ;;
      --db=*) ONLY_DB="${1#--db=}"; shift ;;
      --list)  printf '%s\n' "${CHECKS[@]}"; exit 0 ;;
      --self-test) SELF_TEST=1; shift ;;
      --help|-h) usage; exit 0 ;;
      *) echo "未知参数: $1（--help 看用法）" >&2; exit 2 ;;
    esac
  done

  # --db 拼错时必须报错退出，不能安静地跑 0 项检查然后 exit 0——
  # 那会让「我以为巡检过了」和「其实一项都没跑」长得一模一样。
  if [ -n "$ONLY_DB" ]; then
    case " $KNOWN_DBS " in
      *" $ONLY_DB "*) ;;
      *) echo "未知库名: $ONLY_DB（可选: $KNOWN_DBS）" >&2; exit 2 ;;
    esac
  fi

  command -v docker >/dev/null 2>&1 || {
    echo "找不到 docker 命令，无法进入容器 $MYSQL_CONTAINER 执行 SQL" >&2; exit 2; }
  docker inspect "$MYSQL_CONTAINER" >/dev/null 2>&1 || {
    echo "容器 $MYSQL_CONTAINER 不存在或不可访问（可用 MYSQL_CONTAINER 覆盖）" >&2; exit 2; }

  trap cleanup EXIT
  ERR_FILE="$(mktemp)"

  # 用户名走 env 文件；密码只在 MYSQL_PASSWORD 显式给了时才写进去，
  # 否则完全依赖容器自带的 MYSQL_ROOT_PASSWORD。
  ENV_FILE="$(mktemp)"
  chmod 600 "$ENV_FILE"
  printf 'INTL_MYSQL_USER=%s\n' "$MYSQL_USER" >"$ENV_FILE"
  if [ -n "$MYSQL_PASSWORD" ]; then
    printf 'INTL_MYSQL_PASSWORD=%s\n' "$MYSQL_PASSWORD" >>"$ENV_FILE"
    # 探测 docker exec --env-file（Docker >= 25 支持）。不支持就退回「只用容器
    # 自带密码」这条路——宁可连不上，也不把密码塞进 argv。
    if ! docker exec --env-file "$ENV_FILE" "$MYSQL_CONTAINER" true >/dev/null 2>&1; then
      printf 'INTL_MYSQL_USER=%s\n' "$MYSQL_USER" >"$ENV_FILE"
      echo "提示: 当前 docker 不支持 exec --env-file，已忽略 MYSQL_PASSWORD 并改用容器自带的 MYSQL_ROOT_PASSWORD。" >&2
    fi
  fi

  # 每项检查自己声明涉及哪些库并在 run_check 里做 --db 过滤，这里只管按顺序调用。
  # 不加 set -e：某条检查里 bash 级别出错也不该中断整体，stderr 照常透出。
  local spec fn
  for spec in "${CHECKS[@]}"; do
    fn="${spec%%|*}"
    "$fn"
  done
  [ "$SELF_TEST" = "1" ] && run_self_test

  printf 'ERROR %d / WARN %d / INFO %d / 共 %d 项检查\n' \
    "$ERROR_CNT" "$WARN_CNT" "$INFO_CNT" "$CHECK_CNT"

  if [ "$ERROR_CNT" -gt 0 ] || [ "$WARN_CNT" -gt 0 ] || [ "$INFO_CNT" -gt 0 ]; then
    return 1
  fi
  return 0
}

main "$@"