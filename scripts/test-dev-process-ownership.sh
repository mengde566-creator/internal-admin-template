#!/usr/bin/env bash
# 无副作用回归：用临时命令替身验证 dev.sh 的进程归属与恢复边界。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TEMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/internal-admin-dev-process.XXXXXX")"
TEMP_ROOT="$(cd "$TEMP_ROOT" && pwd)"
trap 'rm -rf "$TEMP_ROOT"' EXIT

mkdir -p "$TEMP_ROOT/scripts" "$TEMP_ROOT/logs" "$TEMP_ROOT/backend" "$TEMP_ROOT/frontend" "$TEMP_ROOT/bin"
cp "$ROOT/scripts/dev.sh" "$TEMP_ROOT/scripts/dev.sh"

cat > "$TEMP_ROOT/bin/python3" <<'EOF'
#!/usr/bin/env bash
case "${2:-}" in
  301|401) printf '%s\n' absent ;;
  501|601) printf '%s\n' unknown ;;
  *) printf '%s\n' alive ;;
esac
EOF

cat > "$TEMP_ROOT/bin/lsof" <<'EOF'
#!/usr/bin/env bash
args="$*"
if [[ "$args" == *"-iTCP:8080"* && "$args" == *"-Fp"* ]]; then
  [ -n "${DEV_TEST_8080:-}" ] && printf 'p%s\n' "$DEV_TEST_8080"
  exit 0
fi
if [[ "$args" == *"-iTCP:5173"* && "$args" == *"-Fp"* ]]; then
  [ -n "${DEV_TEST_5173:-}" ] && printf 'p%s\n' "$DEV_TEST_5173"
  exit 0
fi
if [[ "$args" == *"-a -p "* && "$args" == *"-d cwd"* ]]; then
  pid=""
  while [ "$#" -gt 0 ]; do
    if [ "$1" = -p ]; then
      pid="$2"
      break
    fi
    shift
  done
  case "$pid" in
    101) printf 'n%s/backend\n' "$DEV_TEST_ROOT" ;;
    201|202) printf 'n%s/frontend\n' "$DEV_TEST_ROOT" ;;
    999) printf '%s\n' 'n/tmp/foreign-project' ;;
  esac
fi
EOF

cat > "$TEMP_ROOT/bin/ps" <<'EOF'
#!/usr/bin/env bash
args="$*"
if [[ "$args" == *"-axo pid=,pgid="* ]]; then
  if [ "${DEV_TEST_8080:-}" = 101 ]; then
    printf '%s\n' '  101   101'
  fi
  if [ "${DEV_TEST_5173:-}" = 202 ]; then
    printf '%s\n' '  201   201' '  202   201'
  fi
  if [ "${DEV_TEST_8080:-}" = 999 ]; then
    printf '%s\n' '  999   999'
  fi
  exit 0
fi
pid=""
while [ "$#" -gt 0 ]; do
  if [ "$1" = -p ]; then
    pid="$2"
    break
  fi
  shift
done
if [[ "$args" == *"command="* ]]; then
  case "$pid" in
    101) printf '%s\n' 'java -jar apps/app-server/target/app-server-0.1.0-SNAPSHOT.jar' ;;
    201) printf '%s\n' 'npm run dev' ;;
    202) printf '%s/frontend/node_modules/.bin/vite\n' "$DEV_TEST_ROOT" ;;
    999) printf '%s\n' 'node /tmp/foreign-project/server.js' ;;
  esac
elif [[ "$args" == *"pgid="* ]]; then
  case "$pid" in
    101) printf '%s\n' '  101' ;;
    201|202) printf '%s\n' '  201' ;;
    999) printf '%s\n' '  999' ;;
  esac
elif [[ "$args" == *"ppid="* ]]; then
  case "$pid" in
    202) printf '%s\n' '  201' ;;
    *) printf '%s\n' '    1' ;;
  esac
fi
EOF

cat > "$TEMP_ROOT/bin/curl" <<'EOF'
#!/usr/bin/env bash
printf '%s' '{"status":"UP"}'
EOF

chmod +x "$TEMP_ROOT/bin/python3" "$TEMP_ROOT/bin/lsof" "$TEMP_ROOT/bin/ps" "$TEMP_ROOT/bin/curl" "$TEMP_ROOT/scripts/dev.sh"

run_dev() {
  export DEV_TEST_ROOT="$TEMP_ROOT"
  export DEV_TEST_8080="${DEV_TEST_8080-}"
  export DEV_TEST_5173="${DEV_TEST_5173-}"
  PATH="$TEMP_ROOT/bin:$PATH" "$TEMP_ROOT/scripts/dev.sh" "$@"
}

assert_contains() {
  local actual="$1"
  local expected="$2"
  [[ "$actual" == *"$expected"* ]] || {
    printf 'assertion failed: expected <%s> in <%s>\n' "$expected" "$actual" >&2
    exit 1
  }
}

printf '101\n' > "$TEMP_ROOT/logs/backend.pid"
printf '201\n' > "$TEMP_ROOT/logs/frontend.pid"
normal_output="$(DEV_TEST_8080=101 DEV_TEST_5173=202 run_dev status)"
assert_contains "$normal_output" '后端: 已核验运行'
assert_contains "$normal_output" '前端: 已核验运行'
printf '%s\n' 'PASS normal-owned-pids'

printf '301\n' > "$TEMP_ROOT/logs/backend.pid"
printf '401\n' > "$TEMP_ROOT/logs/frontend.pid"
stale_output="$(DEV_TEST_8080= DEV_TEST_5173= run_dev stop)"
assert_contains "$stale_output" '已确认退出的陈旧 PID 301 记录已安全清理'
assert_contains "$stale_output" '已确认退出的陈旧 PID 401 记录已安全清理'
[ ! -e "$TEMP_ROOT/logs/backend.pid" ] && [ ! -e "$TEMP_ROOT/logs/frontend.pid" ]
printf '%s\n' 'PASS stale-pids-cleaned'

printf '501\n' > "$TEMP_ROOT/logs/backend.pid"
printf '601\n' > "$TEMP_ROOT/logs/frontend.pid"
if unknown_output="$(DEV_TEST_8080= DEV_TEST_5173= run_dev stop 2>&1)"; then
  printf '%s\n' 'expected unknown PID stop to fail' >&2
  exit 1
fi
assert_contains "$unknown_output" '当前无法安全核验'
[ -e "$TEMP_ROOT/logs/backend.pid" ] && [ -e "$TEMP_ROOT/logs/frontend.pid" ]
printf '%s\n' 'PASS unknown-pids-preserved'

rm -f "$TEMP_ROOT/logs/backend.pid" "$TEMP_ROOT/logs/frontend.pid"
recovery_output="$(DEV_TEST_8080=101 DEV_TEST_5173=202 run_dev start)"
assert_contains "$recovery_output" '已恢复唯一严格核验的本项目进程组 PID 101'
assert_contains "$recovery_output" '已恢复唯一严格核验的本项目进程组 PID 201'
[ "$(tr -d '[:space:]' < "$TEMP_ROOT/logs/backend.pid")" = 101 ]
[ "$(tr -d '[:space:]' < "$TEMP_ROOT/logs/frontend.pid")" = 201 ]
printf '%s\n' 'PASS missing-pid-recovered-from-unique-owned-listener'

rm -f "$TEMP_ROOT/logs/backend.pid" "$TEMP_ROOT/logs/frontend.pid"
if foreign_output="$(DEV_TEST_8080=999 run_dev stop 2>&1)"; then
  printf '%s\n' 'expected foreign listener stop to fail' >&2
  exit 1
fi
assert_contains "$foreign_output" '无法唯一核验'
printf '%s\n' 'PASS foreign-listener-refused'
