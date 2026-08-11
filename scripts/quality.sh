#!/usr/bin/env bash
# Internal Admin Template 无数据库质量入口（本地与 CI 共用，REQ-V01-002）。
# 任一必选检查失败即整体失败，依赖必须由调用方按锁文件预先准备。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
DATABASE_TEMP_DIR=""
DATABASE_PID=""
DATABASE_PORT=18080
DATABASE_STARTUP_VALIDATED=0

usage() {
  echo "用法：$0 --no-database|--database" >&2
  echo "--database 在七步无数据库质量层之后执行隔离 SQLite 集成与空库启动验证。" >&2
  exit 64
}

require_command() {
  local command="$1"
  local action="$2"
  command -v "$command" >/dev/null 2>&1 || {
    echo "错误：缺少 ${command}；${action}" >&2
    exit 1
  }
}

require_file() {
  local path="$1"
  local action="$2"
  [ -f "$path" ] || {
    echo "错误：缺少 ${path#$ROOT/}；${action}" >&2
    exit 1
  }
}

require_executable() {
  local path="$1"
  local action="$2"
  [ -x "$path" ] || {
    echo "错误：缺少可执行依赖 ${path#$ROOT/}；${action}" >&2
    exit 1
  }
}

require_jdk_25() {
  local version
  version="$(java -version 2>&1 | awk -F'\"' '/version/{print $2; exit}')"
  [[ "$version" == 25* ]] || {
    echo "错误：需要 JDK 25，当前 java 版本为 ${version:-未知}。" >&2
    exit 1
  }
}

require_node_24() {
  local version
  version="$(node --version)"
  [[ "$version" == v24.* ]] || {
    echo "错误：需要 Node 24，当前 node 版本为 ${version:-未知}。" >&2
    exit 1
  }
}

check_prerequisites() {
  require_command java "安装或配置 JDK 25。"
  require_jdk_25
  require_command node "安装或配置 Node 24。"
  require_command npm "安装 Node 24 自带的 npm。"
  require_node_24
  require_executable "$ROOT/backend/mvnw" "修复 Maven Wrapper 执行权限。"
  require_file "$ROOT/frontend/package-lock.json" "恢复前端锁文件后执行 cd frontend && npm ci。"
  require_file "$ROOT/tools/openapi/package-lock.json" "恢复工具锁文件后执行 cd tools/openapi && npm ci。"
  require_executable "$ROOT/frontend/node_modules/.bin/vitest" "执行 cd frontend && npm ci。"
  require_executable "$ROOT/frontend/node_modules/.bin/playwright" "执行 cd frontend && npm ci。"
  require_executable "$ROOT/frontend/node_modules/.bin/vue-tsc" "执行 cd frontend && npm ci。"
  require_executable "$ROOT/tools/openapi/node_modules/.bin/openapi-typescript" "执行 cd tools/openapi && npm ci。"
}

run_no_database() {
  check_prerequisites
  echo "==> [1/7] 后端：无数据库会话安全门禁"
  (cd backend && ./mvnw -Djava.version=25 -pl apps/app-server -am \
    -Dtest=NoDatabaseSessionSecurityTest,NoDatabaseSessionSecurityProductionTest \
    -Dsurefire.failIfNoSpecifiedTests=false test)
  echo "==> [2/7] 后端：无数据库文件存储门禁"
  (cd backend && ./mvnw -Djava.version=25 -pl modules/module-file -am \
    -Dtest=FileStorageServiceTest \
    -Dsurefire.failIfNoSpecifiedTests=false test)
  echo "==> [3/7] 后端：OpenAPI 无数据库漂移检查"
  "$ROOT/scripts/openapi-contract.sh" check
  echo "==> [4/7] 前端：Vitest"
  (cd frontend && npm run test:unit)
  echo "==> [5/7] 前端：Playwright 用例清单"
  (cd frontend && npm run test:e2e -- --list)
  echo "==> [6/7] 前端：TypeScript"
  (cd frontend && npm run typecheck)
  echo "==> [7/7] 前端：构建"
  (cd frontend && npm run build)
  echo "==> 无数据库质量层全部通过"
}

is_safe_database_temp_dir() {
  local parent base suffix
  [ -n "$DATABASE_TEMP_DIR" ] || return 1
  parent="$(dirname "$DATABASE_TEMP_DIR")"
  base="$(basename "$DATABASE_TEMP_DIR")"
  suffix="${base#internal-admin-quality.}"
  [ "$parent" = "/tmp" ] && [ "$suffix" != "$base" ] && [ -n "$suffix" ] && [ -d "$DATABASE_TEMP_DIR" ]
}

stop_database_server() {
  if [ -z "$DATABASE_PID" ]; then
    return
  fi
  [[ "$DATABASE_PID" =~ ^[1-9][0-9]*$ ]] || {
    echo "错误：本脚本记录的 PID 无效，拒绝终止。" >&2
    return 1
  }
  if kill -0 "$DATABASE_PID" 2>/dev/null; then
    if ! kill "$DATABASE_PID" 2>/dev/null; then
      echo "错误：无法停止本脚本启动的 PID ${DATABASE_PID}。" >&2
      return 1
    fi
    for _ in $(seq 1 25); do
      kill -0 "$DATABASE_PID" 2>/dev/null || break
      sleep 0.2
    done
    if kill -0 "$DATABASE_PID" 2>/dev/null; then
      echo "错误：PID ${DATABASE_PID} 未在 5 秒内退出，保留临时目录供人工核验。" >&2
      return 1
    fi
  fi
  DATABASE_PID=""
}

cleanup_database_run() {
  local cleanup_status=0
  stop_database_server || cleanup_status=1
  if [ "$DATABASE_STARTUP_VALIDATED" != "1" ]; then
    if is_safe_database_temp_dir; then
      echo "保留失败证据目录：${DATABASE_TEMP_DIR}" >&2
    else
      echo "错误：临时目录未通过安全校验，无法报告或清理。" >&2
    fi
    return "$cleanup_status"
  fi
  if [ "$cleanup_status" != "0" ]; then
    echo "错误：临时应用未确认退出，保留临时目录 ${DATABASE_TEMP_DIR}。" >&2
    return 1
  fi
  is_safe_database_temp_dir || {
    echo "错误：临时目录未通过安全校验，拒绝清理。" >&2
    return 1
  }
  rm -rf -- "$DATABASE_TEMP_DIR" || {
    echo "错误：无法清理已核验临时目录 ${DATABASE_TEMP_DIR}。" >&2
    return 1
  }
  DATABASE_TEMP_DIR=""
}

require_database_port_available() {
  [[ "$DATABASE_PORT" =~ ^[1-9][0-9]*$ ]] && [ "$DATABASE_PORT" -le 65535 ] || {
    echo "错误：验证端口必须是 1-65535 的整数，当前为 ${DATABASE_PORT}。" >&2
    exit 1
  }
  require_command lsof "安装 lsof 后重新执行，以安全拒绝已占用端口。"
  if lsof -nP -iTCP:"$DATABASE_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "错误：验证端口 ${DATABASE_PORT} 已被占用，拒绝启动临时应用。" >&2
    exit 1
  fi
}

create_database_temp_dir() {
  DATABASE_TEMP_DIR="$(mktemp -d /tmp/internal-admin-quality.XXXXXX)"
  is_safe_database_temp_dir || {
    echo "错误：mktemp 返回的路径不在允许范围内，拒绝继续。" >&2
    exit 1
  }
}

wait_for_database_health() {
  local attempt
  for attempt in $(seq 1 30); do
    if curl -fsS "http://127.0.0.1:${DATABASE_PORT}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
      echo "    空库应用 health UP（第 ${attempt} 次检查）"
      return
    fi
    echo "    等待空库应用 health（${attempt}/30）"
    sleep 1
  done
  echo "错误：空库应用未在 30 秒内健康；日志：${DATABASE_TEMP_DIR}/app.log" >&2
  tail -100 "${DATABASE_TEMP_DIR}/app.log" >&2 || true
  return 1
}

assert_fresh_database_migration() {
  local database="$DATABASE_TEMP_DIR/quality.db"
  local log="$DATABASE_TEMP_DIR/app.log"
  local run_count total_change_sets
  [ -s "$database" ] || {
    echo "错误：空库启动后未生成非空 quality.db；日志：${log}" >&2
    return 1
  }
  run_count="$(sed -n 's/.*Run:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$log" | head -n 1)"
  total_change_sets="$(sed -n 's/.*Total change sets:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$log" | head -n 1)"
  [[ "$run_count" =~ ^[1-9][0-9]*$ ]] && [[ "$total_change_sets" =~ ^[1-9][0-9]*$ ]] || {
    echo "错误：无法从 Liquibase UPDATE SUMMARY 解析正整数 Run/Total change sets；日志：${log}" >&2
    return 1
  }
  [ "$run_count" = "$total_change_sets" ] || {
    echo "错误：空库迁移不完整，Run=${run_count}，Total change sets=${total_change_sets}；日志：${log}" >&2
    return 1
  }
  echo "    空库 Liquibase UPDATE SUMMARY：Run=${run_count}，Total change sets=${total_change_sets}"
}

run_database_startup_check() {
  local jar="$ROOT/backend/apps/app-server/target/app-server-0.1.0-SNAPSHOT.jar"
  echo "==> [4/4] 后端：构建生产 JAR"
  (cd backend && ./mvnw -Djava.version=25 -pl apps/app-server -am -DskipTests package)
  require_file "$jar" "先完成生产 JAR 构建。"
  require_database_port_available
  create_database_temp_dir
  DATABASE_STARTUP_VALIDATED=0
  trap cleanup_database_run EXIT
  echo "==> 空库启动：临时目录 ${DATABASE_TEMP_DIR}，端口 ${DATABASE_PORT}"
  (
    cd "$ROOT/backend"
    exec java -jar "$jar" \
      --spring.datasource.url="jdbc:sqlite:${DATABASE_TEMP_DIR}/quality.db?foreign_keys=on" \
      --app.storage-root="${DATABASE_TEMP_DIR}/uploads" \
      --server.port="$DATABASE_PORT"
  ) >"${DATABASE_TEMP_DIR}/app.log" 2>&1 &
  DATABASE_PID=$!
  echo "    已启动临时应用 PID ${DATABASE_PID}，日志 ${DATABASE_TEMP_DIR}/app.log"
  wait_for_database_health || return 1
  assert_fresh_database_migration || return 1
  DATABASE_STARTUP_VALIDATED=1
  if ! cleanup_database_run; then
    trap - EXIT
    return 1
  fi
  trap - EXIT
  DATABASE_PID=""
  DATABASE_TEMP_DIR=""
}

run_database() {
  run_no_database
  echo "==> [1/4] 后端：IAM 隔离 SQLite 集成测试"
  (cd backend && ./mvnw -Djava.version=25 -pl apps/app-server -am \
    -Dtest=IamFlowTest -Dsurefire.failIfNoSpecifiedTests=false test)
  echo "==> [2/4] 后端：主页隔离 SQLite 集成测试"
  (cd backend && ./mvnw -Djava.version=25 -pl apps/app-server -am \
    -Dtest=SiteFlowTest -Dsurefire.failIfNoSpecifiedTests=false test)
  echo "==> [3/4] 后端：运行时 OpenAPI 隔离 SQLite 集成测试"
  (cd backend && ./mvnw -Djava.version=25 -pl apps/app-server -am \
    -Dtest=OpenApiContractTest -Dsurefire.failIfNoSpecifiedTests=false test)
  run_database_startup_check
  echo "==> 隔离数据库质量层全部通过"
}

case "${1:-}" in
  --no-database)
    [ "$#" -eq 1 ] || usage
    run_no_database
    ;;
  --database)
    [ "$#" -eq 1 ] || usage
    run_database
    ;;
  *)
    usage
    ;;
esac
