#!/usr/bin/env bash
# 一键启动/停止/检查前后端开发服务（落地检查标准入口）
#
# 用法：
#   ./scripts/dev.sh start   启动后端(8080)与前端(5173)，日志写入 logs/
#   ./scripts/dev.sh status  检查服务与依赖状态
#   ./scripts/dev.sh stop    停止前后端
#
# 原则：服务启动一律使用本脚本，禁止临时命令临场发挥；
# 每次启动必须执行 status 确认全部就绪（含依赖检查）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOGS="$ROOT/logs"
BACKEND_PID_FILE="$LOGS/backend.pid"
FRONTEND_PID_FILE="$LOGS/frontend.pid"
BACKEND_LOG="$LOGS/backend.log"
FRONTEND_LOG="$LOGS/frontend.log"
BACKEND_PORT=8080
FRONTEND_PORT=5173
LOCAL_ENV_FILE="$ROOT/.env.local"
BACKEND_ENV=()
BACKEND_DATABASE_MODE="SQLite"

mkdir -p "$LOGS"

require_process_inspector() {
    if ! command -v lsof >/dev/null 2>&1; then
        echo "错误：未找到 lsof，无法安全核验 PID、命令与端口归属。" >&2
        return 1
    fi
}

pid_from_file() {
    local file="$1"
    local pid
    [ -f "$file" ] || return 1
    pid="$(tr -d '[:space:]' < "$file")"
    [[ "$pid" =~ ^[1-9][0-9]*$ ]] || return 1
    printf '%s\n' "$pid"
}

pid_state() {
    # Shell 的 `kill -0` 将 ESRCH 和 EPERM 都折叠为非零；绝不能把
    # “当前会话无权核验”误作“进程已退出”。Python 保留 errno，缺失时宁可未知。
    local pid="$1"
    local python_bin
    [[ "$pid" =~ ^[1-9][0-9]*$ ]] || {
        printf '%s\n' unknown
        return
    }
    python_bin="$(command -v python3 || command -v python || true)"
    if [ -z "$python_bin" ]; then
        if kill -0 "$pid" 2>/dev/null; then
            printf '%s\n' alive
        else
            printf '%s\n' unknown
        fi
        return
    fi
    "$python_bin" - "$pid" <<'PYEOF' || printf '%s\n' unknown
import errno
import os
import sys

try:
    os.kill(int(sys.argv[1]), 0)
except OSError as error:
    if error.errno == errno.ESRCH:
        print("absent")
    else:
        print("unknown")
else:
    print("alive")
PYEOF
}

process_command() { ps -p "$1" -o command= 2>/dev/null; }

process_cwd() {
    lsof -a -p "$1" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -n 1
}

process_group_id() {
    ps -p "$1" -o pgid= 2>/dev/null | tr -d '[:space:]'
}

process_group_members() {
    local group_id="$1"
    ps -axo pid=,pgid= 2>/dev/null | awk -v expected="$group_id" '$2 == expected { print $1 }'
}

is_expected_command() {
    local service="$1"
    local command="$2"
    case "$service" in
        backend) [[ "$command" == *"apps/app-server/target/app-server-0.1.0-SNAPSHOT.jar"* ]] ;;
        frontend) [[ "$command" == *"npm run dev"* || "$command" == *"node_modules/.bin/vite"* || "$command" == *"node_modules/vite"* ]] ;;
        *) return 1 ;;
    esac
}

service_workdir() {
    case "$1" in
        backend) printf '%s\n' "$ROOT/backend" ;;
        frontend) printf '%s\n' "$ROOT/frontend" ;;
        *) return 1 ;;
    esac
}

is_owned_process() {
    local service="$1"
    local pid="$2"
    local command expected_workdir
    command="$(process_command "$pid")" || return 1
    expected_workdir="$(service_workdir "$service")" || return 1
    is_expected_command "$service" "$command" && [ "$(process_cwd "$pid")" = "$expected_workdir" ]
}

port_listener_pids() {
    lsof -nP -iTCP:"$1" -sTCP:LISTEN -Fp 2>/dev/null | sed -n 's/^p//p' || true
}

is_descendant_or_same() {
    local ancestor="$1"
    local current="$2"
    local parent
    while [[ "$current" =~ ^[1-9][0-9]*$ ]]; do
        [ "$current" = "$ancestor" ] && return 0
        parent="$(ps -p "$current" -o ppid= 2>/dev/null | tr -d '[:space:]')"
        [ -n "$parent" ] && [ "$parent" != "$current" ] || return 1
        current="$parent"
    done
    return 1
}

port_belongs_to_process() {
    local pid="$1"
    local port="$2"
    local listener
    while IFS= read -r listener; do
        is_descendant_or_same "$pid" "$listener" && return 0
    done < <(port_listener_pids "$port")
    return 1
}

verified_listener_group() {
    # 输出可安全终止的项目进程组 leader PID。所有成员都必须是同一服务，
    # 且唯一端口监听者属于该组；任何一项无法核验都拒绝恢复。
    local service="$1"
    local port="$2"
    local listeners listener group_id members member has_listener=0 has_leader=0
    listeners="$(port_listener_pids "$port")"
    [ "$(printf '%s\n' "$listeners" | sed '/^$/d' | wc -l | tr -d ' ')" = 1 ] || return 1
    listener="$(printf '%s\n' "$listeners" | head -n 1)"
    group_id="$(process_group_id "$listener")"
    [[ "$group_id" =~ ^[1-9][0-9]*$ ]] || return 1
    members="$(process_group_members "$group_id")"
    [ -n "$members" ] || return 1
    while IFS= read -r member; do
        [[ "$member" =~ ^[1-9][0-9]*$ ]] || return 1
        is_owned_process "$service" "$member" || return 1
        [ "$member" = "$listener" ] && has_listener=1
        [ "$member" = "$group_id" ] && has_leader=1
    done <<< "$members"
    [ "$has_listener" = 1 ] && [ "$has_leader" = 1 ] || return 1
    printf '%s\n' "$group_id"
}

recover_owned_listener_pid() {
    verified_listener_group "$1" "$2"
}

service_is_owned_and_listening() {
    local service="$1"
    local pid_file="$2"
    local port="$3"
    local pid
    pid="$(pid_from_file "$pid_file")" || return 1
    [ "$(pid_state "$pid")" = alive ] && is_owned_process "$service" "$pid" && port_belongs_to_process "$pid" "$port"
}

# 启动后台进程并脱离当前进程组（防止 shell 退出时被清理）。
# 优先 setsid（Linux/macOS），否则用 Python 创建独立会话，最后回退 nohup。
launch_background() {
    local log="$1"
    shift
    if command -v setsid >/dev/null 2>&1; then
        setsid "$@" < /dev/null > "$log" 2>&1 &
        echo $!
    elif command -v python >/dev/null 2>&1 || command -v python3 >/dev/null 2>&1; then
        local python_bin
        python_bin="$(command -v python || command -v python3)"
        "$python_bin" - "$log" "$@" <<'PYEOF'
import os, subprocess, sys, shutil
log = sys.argv[1]
cmd = sys.argv[2:]
exe = shutil.which(cmd[0]) or cmd[0]
flags = getattr(subprocess, 'DETACHED_PROCESS', 0) | getattr(subprocess, 'CREATE_NEW_PROCESS_GROUP', 0)
if exe.lower().endswith(('.cmd', '.bat')):
    # Windows 批处理需经 shell 执行
    full = ' '.join('"' + a + '"' if ' ' in a else a for a in cmd)
    with open(log, 'ab') as f:
        p = subprocess.Popen(full, shell=True, stdout=f, stderr=f, stdin=subprocess.DEVNULL, creationflags=flags)
else:
    with open(log, 'ab') as f:
        p = subprocess.Popen(cmd, stdout=f, stderr=f, stdin=subprocess.DEVNULL,
                             creationflags=flags, start_new_session=(os.name != 'nt'))
print(p.pid)
PYEOF
    else
        nohup "$@" < /dev/null > "$log" 2>&1 &
        echo $!
    fi
}

check_java() {
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        echo "java: $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
    elif command -v java >/dev/null 2>&1; then
        echo "java: $(java -version 2>&1 | head -1)"
    else
        echo "java: 未找到（需 JDK 25，或设置 JAVA_HOME）"
        return 1
    fi
}

check_node() {
    if command -v node >/dev/null 2>&1; then
        echo "node: $(node --version)"
    else
        echo "node: 未找到"
        return 1
    fi
}

load_backend_local_env() {
    BACKEND_ENV=()
    BACKEND_DATABASE_MODE="SQLite"
    [ -f "$LOCAL_ENV_FILE" ] || return 0

    local line key value
    while IFS= read -r line || [ -n "$line" ]; do
        line="${line%$'\r'}"
        [ -z "$line" ] && continue
        [[ "$line" == \#* ]] && continue
        if [[ "$line" != *=* ]]; then
            echo "错误：.env.local 存在无效行，拒绝加载后端本地环境。" >&2
            return 1
        fi
        key="${line%%=*}"
        value="${line#*=}"
        if [[ ! "$key" =~ ^[A-Z][A-Z0-9_]*$ ]]; then
            echo "错误：.env.local 存在无效变量名，拒绝加载后端本地环境。" >&2
            return 1
        fi
        BACKEND_ENV+=("$key=$value")
        if [ "$key" = "SPRING_DATASOURCE_URL" ]; then
            case "$value" in
                jdbc:postgresql:*) BACKEND_DATABASE_MODE="PostgreSQL 本地配置" ;;
                jdbc:sqlite:*) BACKEND_DATABASE_MODE="SQLite 本地配置" ;;
                *) BACKEND_DATABASE_MODE="外部数据源本地配置" ;;
            esac
        fi
    done < "$LOCAL_ENV_FILE"
}

check_database() {
    load_backend_local_env
    if [ "$BACKEND_DATABASE_MODE" != "SQLite" ]; then
        echo "database: ${BACKEND_DATABASE_MODE} 已加载（敏感连接信息未显示）"
        return 0
    fi
    # SQLite 零配置：文件库存在且非空。
    local db="$ROOT/backend/data/internal-admin.db"
    if [ -f "$db" ]; then
        echo "database: SQLite 就绪（${db}）"
    else
        echo "database: 开发库不存在（首次启动将自动创建并迁移）"
    fi
    # 外部数据库/中间件检查扩展位（按实际配置启用）：
    # - MySQL/PostgreSQL/Oracle：nc -z <host> <port> 或 JDBC 探活
    # - Redis：redis-cli ping
}

check_backend_artifact() {
    local jar="$ROOT/backend/apps/app-server/target/app-server-0.1.0-SNAPSHOT.jar"
    if [ -f "$jar" ]; then
        echo "backend jar: 存在（${jar}）"
    else
        echo "backend jar: 不存在，先执行 cd backend && ./mvnw -DskipTests package"
        return 1
    fi
}

check_frontend_deps() {
    if [ -d "$ROOT/frontend/node_modules" ]; then
        echo "frontend deps: node_modules 存在"
    else
        echo "frontend deps: 缺失，先执行 cd frontend && npm ci"
        return 1
    fi
}

start_backend() {
    require_process_inspector
    if service_is_owned_and_listening backend "$BACKEND_PID_FILE" "$BACKEND_PORT"; then
        echo "后端已在运行且归属已核验（8080）"
        return 0
    fi
    local recovered_pid recorded_pid
    if [ -f "$BACKEND_PID_FILE" ] && recorded_pid="$(pid_from_file "$BACKEND_PID_FILE")" && [ "$(pid_state "$recorded_pid")" = absent ]; then
        rm -f "$BACKEND_PID_FILE"
        echo "后端: 已清理已确认退出的陈旧 PID 记录"
    fi
    if ! [ -f "$BACKEND_PID_FILE" ] && recovered_pid="$(recover_owned_listener_pid backend "$BACKEND_PORT")"; then
        echo "$recovered_pid" > "$BACKEND_PID_FILE"
        echo "后端: 已恢复唯一严格核验的本项目进程组 PID ${recovered_pid}（8080）"
        return 0
    fi
    if [ -n "$(port_listener_pids "$BACKEND_PORT")" ]; then
        echo "错误：8080 已被非本项目已核验进程占用，拒绝覆盖启动。" >&2
        return 1
    fi
    if [ -f "$BACKEND_PID_FILE" ]; then
        echo "错误：后端 PID 文件存在但不对应可监听的本项目进程；先执行 ./scripts/dev.sh stop 安全清理。" >&2
        return 1
    fi
    check_backend_artifact
    load_backend_local_env
    local java_bin
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        java_bin="$JAVA_HOME/bin/java"
    else
        java_bin="java"
    fi
    cd "$ROOT/backend"
    local pid
    if [ "${#BACKEND_ENV[@]}" -gt 0 ]; then
        pid=$(launch_background "$BACKEND_LOG" env "${BACKEND_ENV[@]}" "$java_bin" -jar apps/app-server/target/app-server-0.1.0-SNAPSHOT.jar)
    else
        pid=$(launch_background "$BACKEND_LOG" "$java_bin" -jar apps/app-server/target/app-server-0.1.0-SNAPSHOT.jar)
    fi
    echo "$pid" > "$BACKEND_PID_FILE"
    echo "后端启动中（PID ${pid}，日志 logs/backend.log）"
}

start_frontend() {
    require_process_inspector
    if service_is_owned_and_listening frontend "$FRONTEND_PID_FILE" "$FRONTEND_PORT"; then
        echo "前端已在运行且归属已核验（5173）"
        return 0
    fi
    local recovered_pid recorded_pid
    if [ -f "$FRONTEND_PID_FILE" ] && recorded_pid="$(pid_from_file "$FRONTEND_PID_FILE")" && [ "$(pid_state "$recorded_pid")" = absent ]; then
        rm -f "$FRONTEND_PID_FILE"
        echo "前端: 已清理已确认退出的陈旧 PID 记录"
    fi
    if ! [ -f "$FRONTEND_PID_FILE" ] && recovered_pid="$(recover_owned_listener_pid frontend "$FRONTEND_PORT")"; then
        echo "$recovered_pid" > "$FRONTEND_PID_FILE"
        echo "前端: 已恢复唯一严格核验的本项目进程组 PID ${recovered_pid}（5173）"
        return 0
    fi
    if [ -n "$(port_listener_pids "$FRONTEND_PORT")" ]; then
        echo "错误：5173 已被非本项目已核验进程占用，拒绝覆盖启动。" >&2
        return 1
    fi
    if [ -f "$FRONTEND_PID_FILE" ]; then
        echo "错误：前端 PID 文件存在但不对应可监听的本项目进程；先执行 ./scripts/dev.sh stop 安全清理。" >&2
        return 1
    fi
    check_frontend_deps
    cd "$ROOT/frontend"
    local pid
    pid=$(launch_background "$FRONTEND_LOG" npm run dev)
    echo "$pid" > "$FRONTEND_PID_FILE"
    echo "前端启动中（PID ${pid}，日志 logs/frontend.log）"
}

report_service_status() {
    local service="$1"
    local label="$2"
    local pid_file="$3"
    local port="$4"
    local pid
    if [ ! -f "$pid_file" ]; then
        local recovered_pid
        if recovered_pid="$(recover_owned_listener_pid "$service" "$port")"; then
            echo "${label}: 检测到唯一严格核验的本项目进程组 PID ${recovered_pid}（PID 文件缺失；执行 start 可安全恢复记录）"
        elif [ -n "$(port_listener_pids "$port")" ]; then
            echo "${label}: ${port} 被无法唯一核验的进程占用（无本项目 PID 文件）"
        else
            echo "${label}: 未运行"
        fi
        return
    fi
    if ! pid="$(pid_from_file "$pid_file")"; then
        echo "${label}: PID 文件无效，陈旧记录可由 stop 安全清理"
    elif [ "$(pid_state "$pid")" = absent ]; then
        echo "${label}: PID ${pid} 已确认退出，陈旧记录可由 stop 安全清理"
    elif [ "$(pid_state "$pid")" = unknown ]; then
        echo "${label}: PID ${pid} 存在但当前会话无法安全核验；保留记录并拒绝停止"
    elif ! is_owned_process "$service" "$pid"; then
        echo "${label}: PID ${pid} 不属于本项目，拒绝认定或停止"
    elif ! port_belongs_to_process "$pid" "$port"; then
        if [ -n "$(port_listener_pids "$port")" ]; then
            echo "${label}: PID ${pid} 已核验，但 ${port} 由其他进程监听"
        else
            echo "${label}: PID ${pid} 已核验，尚未监听 ${port}"
        fi
    elif [ "$service" = "backend" ]; then
        echo "${label}: 已核验运行（http://127.0.0.1:${port}，health: $(curl -s -m 3 "http://127.0.0.1:${port}/actuator/health" | head -c 60)）"
    else
        echo "${label}: 已核验运行（http://127.0.0.1:${port}）"
    fi
}

status() {
    echo "==== 环境依赖 ===="
    check_java || true
    check_node || true
    check_database || true
    echo ""
    echo "==== 服务状态 ===="
    if ! require_process_inspector; then
        echo "服务归属：无法安全核验"
        return
    fi
    report_service_status backend 后端 "$BACKEND_PID_FILE" "$BACKEND_PORT"
    report_service_status frontend 前端 "$FRONTEND_PID_FILE" "$FRONTEND_PORT"
}

stop_service() {
    local service="$1"
    local label="$2"
    local pid_file="$3"
    local port="$4"
    local pid
    if [ ! -f "$pid_file" ]; then
        local recovered_without_record
        if ! recovered_without_record="$(recover_owned_listener_pid "$service" "$port")"; then
            if [ -n "$(port_listener_pids "$port")" ]; then
                echo "错误：${label} ${port} 被无法唯一核验的进程占用，拒绝终止。" >&2
                return 1
            fi
            echo "${label}: 无 PID 记录，未运行"
            return
        fi
        echo "$recovered_without_record" > "$pid_file"
        echo "${label}: 已恢复唯一严格核验的本项目进程组 PID ${recovered_without_record}"
    fi
    if ! pid="$(pid_from_file "$pid_file")"; then
        rm -f "$pid_file"
        echo "${label}: 无效 PID 记录已安全清理"
        return
    fi
    local state recovered_pid group_id
    state="$(pid_state "$pid")"
    if [ "$state" = absent ]; then
        rm -f "$pid_file"
        echo "${label}: 已确认退出的陈旧 PID ${pid} 记录已安全清理"
    elif [ "$state" = unknown ]; then
        echo "错误：${label} PID ${pid} 当前无法安全核验，保留 PID 记录。" >&2
        return 1
    else
        group_id="$(verified_listener_group "$service" "$port" || true)"
        if [ -z "$group_id" ] || [ "$group_id" != "$pid" ]; then
            echo "错误：${label} PID ${pid} 未核验为唯一严格匹配的项目进程组，拒绝终止并保留记录。" >&2
            return 1
        fi
    fi
    if ! recovered_pid="$(recover_owned_listener_pid "$service" "$port")"; then
        if [ -n "$(port_listener_pids "$port")" ]; then
            echo "错误：${label} ${port} 被无法唯一核验的进程占用，拒绝终止。" >&2
            return 1
        fi
        echo "${label}: 未运行"
        return 0
    fi
    if ! kill -TERM -- "-${recovered_pid}"; then
        echo "错误：${label} 项目进程组 ${recovered_pid} 终止失败，保留 PID 记录供人工核验。" >&2
        return 1
    fi
    # Spring 正常关闭已实测约 2.02 秒，保留 10 秒余量避免安全退出被误判超时。
    for _ in $(seq 1 50); do
        [ "$(pid_state "$recovered_pid")" = absent ] && break
        sleep 0.2
    done
    if [ "$(pid_state "$recovered_pid")" != absent ]; then
        echo "错误：${label} 项目进程组 ${recovered_pid} 未在限时内确认退出，保留 PID 记录供人工核验。" >&2
        return 1
    fi
    rm -f "$pid_file"
    echo "${label}: 已停止本项目进程组 ${recovered_pid}"
}

stop() {
    require_process_inspector
    local result=0
    stop_service backend 后端 "$BACKEND_PID_FILE" "$BACKEND_PORT" || result=1
    stop_service frontend 前端 "$FRONTEND_PID_FILE" "$FRONTEND_PORT" || result=1
    return "$result"
}

case "${1:-status}" in
    start)
        start_backend
        start_frontend
        echo "启动完成，执行 ./scripts/dev.sh status 确认就绪"
        ;;
    status)
        status
        ;;
    stop)
        stop
        ;;
    *)
        echo "用法: $0 {start|status|stop}"
        exit 1
        ;;
esac
