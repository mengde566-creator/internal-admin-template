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

mkdir -p "$LOGS"

is_port_open() { curl -s -m 2 -o /dev/null "http://127.0.0.1:$1"; }
is_pid_alive() { [ -f "$1" ] && kill -0 "$(cat "$1")" 2>/dev/null; }

# 启动后台进程并脱离当前进程组（防止 shell 退出时被清理）。
# 优先 setsid（Linux/macOS），Windows 下用 python DETACHED_PROCESS，最后回退 nohup。
launch_background() {
    local log="$1"
    shift
    if command -v setsid >/dev/null 2>&1; then
        setsid "$@" < /dev/null > "$log" 2>&1 &
        echo $!
    elif command -v python >/dev/null 2>&1; then
        python - "$log" "$@" <<'PYEOF'
import subprocess, sys, shutil
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
        p = subprocess.Popen(cmd, stdout=f, stderr=f, stdin=subprocess.DEVNULL, creationflags=flags)
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

check_database() {
    # SQLite 零配置：文件库存在且非空；未来切换外部数据库时在此追加连接检查
    local db="$ROOT/backend/data/internal-admin.db"
    if [ -f "$db" ]; then
        echo "database: SQLite 就绪（$db）"
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
        echo "backend jar: 存在（$jar）"
    else
        echo "backend jar: 不存在，先执行 cd backend && mvn -DskipTests package"
        return 1
    fi
}

check_frontend_deps() {
    if [ -d "$ROOT/frontend/node_modules" ]; then
        echo "frontend deps: node_modules 存在"
    else
        echo "frontend deps: 缺失，先执行 cd frontend && npm install"
        return 1
    fi
}

start_backend() {
    if is_port_open $BACKEND_PORT; then
        echo "后端已在运行（8080）"
        return 0
    fi
    check_backend_artifact
    local java_bin
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        java_bin="$JAVA_HOME/bin/java"
    else
        java_bin="java"
    fi
    cd "$ROOT/backend"
    local pid
    pid=$(launch_background "$BACKEND_LOG" "$java_bin" -jar apps/app-server/target/app-server-0.1.0-SNAPSHOT.jar)
    echo "$pid" > "$BACKEND_PID_FILE"
    echo "后端启动中（PID $pid，日志 logs/backend.log）"
}

start_frontend() {
    if is_port_open $FRONTEND_PORT; then
        echo "前端已在运行（5173）"
        return 0
    fi
    check_frontend_deps
    cd "$ROOT/frontend"
    local pid
    pid=$(launch_background "$FRONTEND_LOG" npm run dev)
    echo "$pid" > "$FRONTEND_PID_FILE"
    echo "前端启动中（PID $pid，日志 logs/frontend.log）"
}

status() {
    echo "==== 环境依赖 ===="
    check_java || true
    check_node || true
    check_database || true
    echo ""
    echo "==== 服务状态 ===="
    if is_port_open $BACKEND_PORT; then
        echo "后端: 运行中（http://127.0.0.1:$BACKEND_PORT，health: $(curl -s -m 3 http://127.0.0.1:$BACKEND_PORT/actuator/health | head -c 60)）"
    else
        echo "后端: 未运行"
    fi
    if is_port_open $FRONTEND_PORT; then
        echo "前端: 运行中（http://127.0.0.1:$FRONTEND_PORT）"
    else
        echo "前端: 未运行"
    fi
}

stop() {
    if [ -f "$BACKEND_PID_FILE" ]; then
        kill "$(cat "$BACKEND_PID_FILE")" 2>/dev/null || true
        rm -f "$BACKEND_PID_FILE"
        echo "后端已停止"
    fi
    if [ -f "$FRONTEND_PID_FILE" ]; then
        kill "$(cat "$FRONTEND_PID_FILE")" 2>/dev/null || true
        rm -f "$FRONTEND_PID_FILE"
        echo "前端已停止"
    fi
    # 兜底：残留进程清理
    pkill -f "app-server-0.1.0-SNAPSHOT.jar" 2>/dev/null || true
    pkill -f "vite" 2>/dev/null || true
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
