#!/usr/bin/env bash
# =============================================================================
# 单服务串行部署（tms / wms / oms / crm / web-console）
#
# 为什么需要这个脚本（2026-10-04 一次真实事故）：
#   那天同时有 4 个会话在改不同服务，各自跑 `docker build`。这台机器只有
#   **1.7 GB 内存**，而 `docker build` 会在容器里重跑完整构建（Java 服务是
#   Maven，web-console 是 vite），每个构建峰值 1 GB 上下。四个并发直接把
#   2 GB swap 写满，vmstat 显示每秒换入 209 万页 —— 换页抖动死锁：
#   构建互相等磁盘页，谁都跑不完；SSH 握手超时、网关 8080 请求超时，
#   整台机器看起来像挂了。恢复办法是加 swap + 等构建结束，不是重启能解决的。
#
# 所以本脚本做三件之前没做的事：
#   1. **加锁**：flock 一把全局锁。同一台机器上永远只有一个构建在跑。
#      并发调用者会排队等，不是各跑各的。
#   2. **看负载再开始**：拿不到锁还不够，拿到了也要等负载降下来。
#      load average 高于阈值说明还有别的活儿在跑，插进去就是第二次雪崩。
#   3. **md5 逐文件校验**：批量上传会静默漏文件（踩过），且漏了不会报错，
#      表现为「代码改了但服务行为没变」，极难定位。
#
# 用法：
#   ./deploy/deploy_serial.sh tms                      # 只重建 tms
#   ./deploy/deploy_serial.sh web-console              # 会自动还原 4 个 APK
#   LOAD_MAX=12 ./deploy/deploy_serial.sh wms          # 自定义负载阈值
# =============================================================================
set -uo pipefail

SVC="${1:?用法: deploy_serial.sh <服务名>}"
SERVICE_DIR="/opt/stack/apps/${SVC}"
LOCK_FILE="/var/lock/stack-build.lock"
APK_NAMES=(crm-android crm-android-debug workbench workbench-debug)

# 阈值 8 的依据：1.7 GB 机器上单个容器构建的稳态负载约 3~6，
# 超过 8 说明不止一个构建在跑（或有别的重活），插进去必雪崩。
LOAD_MAX="${LOAD_MAX:-8}"

log() { printf '[deploy_serial] %s\n' "$*"; }
die() { printf '[deploy_serial] 错误: %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- 1. 拿锁
mkdir -p "$(dirname "$LOCK_FILE")"
log "等待构建锁（同一时刻只允许一个构建）..."
exec {lock_fd}>"$LOCK_FILE"
if ! flock -n "$lock_fd"; then
    log "已有构建在跑，等待它结束（这正是避免雪崩的机制）..."
    flock "$lock_fd" || die "获取构建锁失败"
fi
log "已持有构建锁"

# ---------------------------------------------------------------- 2. 等负载
while :; do
    LOAD=$(cut -d' ' -f1 /proc/loadavg)
    # 切整数比较。load 8.5 也会拦住（宁可多等，不要雪崩）
    LOAD_INT=${LOAD%.*}
    if [ "$LOAD_INT" -lt "$LOAD_MAX" ]; then
        log "负载 $LOAD < $LOAD_MAX，开始构建"
        break
    fi
    log "负载 $LOAD ≥ $LOAD_MAX，等 45 秒再看"
    sleep 45
done

# ---------------------------------------------------------------- 3. 迁移先行
# SQL 迁移必须在容器重建之前跑完：容器起来发现列不存在会直接启动失败，
# 而此时没人能进容器看日志。
if [ -d "$SERVICE_DIR/deploy/initdb" ]; then
    log "执行未跑过的迁移..."
    for f in "$SERVICE_DIR"/deploy/initdb/*.sql; do
        [ -f "$f" ] || continue
        name=$(basename "$f")
        # 已执行过的跳过靠 SQL 自身幂等，这里全跑一遍代价也很低（幂等是硬要求）
        # 口令取 mysql 容器**自己**的环境变量（compose 注入的 MYSQL_ROOT_PASSWORD）：
        # 既不写进脚本，也不过宿主机 argv——`ps` 看得见 argv，仓库更不该留明文。
        docker exec -i stack-mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD"' <"$f" \
            || die "迁移失败: $name"
        log "  迁移 $name 完成"
    done
else
    log "无迁移目录，跳过"
fi

# ---------------------------------------------------------------- 4. 构建
log "构建 ${SVC}:1.0.0（容器内重跑完整构建，Java 服务约 3~6 分钟）..."
docker build -t "${SVC}:1.0.0" "$SERVICE_DIR" || die "构建失败"

# ---------------------------------------------------------------- 5. 重建
log "重建容器..."
docker compose -f /opt/stack/docker-compose.yml up -d --force-recreate "$SVC" || die "重建失败"

# ---------------------------------------------------------------- 6. web-console 还原 APK
# 容器重建会把镜像里的 4 个 APK 冲掉。上一轮没还原，UI 上「下载 App」全 404。
if [ "$SVC" = "web-console" ]; then
    log "还原 4 个 APK（容器重建会覆盖镜像里的文件）..."
    for a in "${APK_NAMES[@]}"; do
        if [ -f "/tmp/save-${a}.apk" ]; then
            docker cp "/tmp/save-${a}.apk" "app-web-console:/usr/share/nginx/html/${a}.apk" \
                && log "  ${a}.apk 还原"
        else
            log "  警告: /tmp/save-${a}.apk 不存在，跳过 ${a}（UI 下载会 404）"
        fi
    done
fi

# ---------------------------------------------------------------- 7. md5 校验
# 放在最后统一校验：前面的任何一步失败都会 exit，没走到这里说明都成功了，
# 但文件没真正同步到服务器的情况不会报错——只有 md5 比对能发现。
log "校验服务器端文件与本地一致..."
DIFF=0
cd /opt/stack/apps || die "/opt/stack/apps 不存在"
while IFS= read -r f; do
    l=$(md5 -q "../../${f}" 2>/dev/null || echo "")
    r=$(md5sum "$f" 2>/dev/null | cut -d' ' -f1)
    [ -n "$l" ] && [ "$l" = "$r" ] || { printf '  不一致: %s\n' "$f"; DIFF=1; }
done < <(cd "$(dirname "$0")/../.." && git diff --name-only HEAD -- . 2>/dev/null | grep "^${SVC}/" ; true)
[ "$DIFF" = 0 ] && log "md5 全部一致" || log "有文件不一致，请重新上传"

# ---------------------------------------------------------------- 8. 等就绪
# 容器起来后约 20~25 秒才连上 Nacos（gRPC 9848 有 3s timeout），
# 期间请求全 500；Nacos 真掉了要 --force-recreate 才能恢复。
log "等 Nacos 注册（20~25 秒）..."
for i in $(seq 1 20); do
    sleep 15
    CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 \
        -X POST http://127.0.0.1:8080/api/auth/login \
        -H 'Content-Type: application/json' \
        -d '{"account":"13800000006","password":"DEMO_PASSWORD"}')
    if [ "$CODE" = "200" ]; then
        log "服务就绪（登录 200），耗时约 $((i * 15)) 秒"
        exit 0
    fi
    log "  第 $((i * 15)) 秒：login=$CODE，继续等"
done
die "等了 5 分钟服务仍未就绪，检查 docker logs app-${SVC}"