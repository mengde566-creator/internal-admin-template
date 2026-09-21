#!/usr/bin/env bash
# 真跑层固定入口：驱动**正在运行**的应用，用正式 HTTP 接口验证用户场景。
#
# 与 quality.sh 的分工：
#   ./scripts/quality.sh --no-database   快层：单元、契约、静态检查（不需要应用在跑）
#   ./scripts/scenario-gate.sh           真跑层：真实链路 + 真实 Provider + 真实页面后端
# 真跑层需要运行中的应用与管理员凭据，并且会真实调用模型 Provider，因此单独入口，
# 只在"完成一部分之后整层跑一次"时执行。
#
# 稳定性约定（写死在这里，以后不再改命令）：
#   1. 入口固定为 ./scripts/scenario-gate.sh，命令里不出现测试类名、版本号和 JAR 名；
#   2. 测试类按约定选择：-Dtest='*ScenarioIT'，新增场景测试类**不需要改命令**；
#   3. 真正防"静默少跑"的不是选择器，而是产物核验：必须出现**本次运行产生的** *ScenarioIT
#      报告，且用例数大于 0、失败与错误为 0。零匹配、被环境条件跳过、只拿到上一次的旧报告，
#      三种情况全部判失败（Maven 在零匹配时不报错，详见 scripts/assert-surefire-ran.sh）。
#
# 凭据：先取当前环境变量，缺失的键再从仓库根 .env.local 读取（不覆盖已设置的环境变量）。
#   SCENARIO_BASE_URL        被测应用地址，默认 http://127.0.0.1:8080
#   SCENARIO_ADMIN_USER      管理员账号，默认 admin
#   SCENARIO_ADMIN_PASSWORD  管理员密码，必填；缺失时本脚本直接失败并给出填写方式
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

LOCAL_ENV_FILE="$ROOT/.env.local"
DEFAULT_BASE_URL="http://127.0.0.1:8080"
# 让 assert-surefire-ran.sh 只认本次运行产生的报告（报告跨运行保留，旧报告会让门禁假通过）。
QUALITY_RUN_MARKER="${TMPDIR:-/tmp}/internal-admin-scenario-run.marker"
export QUALITY_RUN_MARKER

require_command() {
  local command="$1" action="$2"
  command -v "$command" >/dev/null 2>&1 || {
    echo "错误：缺少 ${command}；${action}" >&2
    exit 1
  }
}

# 按 .env.local 的统一格式补齐 SCENARIO_* 变量；已存在于环境中的键一律不覆盖。
load_scenario_local_env() {
  [ -f "$LOCAL_ENV_FILE" ] || return 0

  local line key value
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    [ -z "$line" ] && continue
    [[ "$line" == \#* ]] && continue
    if [[ "$line" != *=* ]]; then
      echo "错误：.env.local 存在无效行，拒绝读取场景凭据。" >&2
      exit 1
    fi
    key="${line%%=*}"
    value="${line#*=}"
    if [[ ! "$key" =~ ^[A-Z][A-Z0-9_]*$ ]]; then
      echo "错误：.env.local 存在无效变量名，拒绝读取场景凭据。" >&2
      exit 1
    fi
    [[ "$key" == SCENARIO_* ]] || continue
    if [ -z "$(printenv "$key" || true)" ]; then
      export "$key=$value"
    fi
  done <"$LOCAL_ENV_FILE"
}

require_command curl "安装 curl 后重试。"
[ -x "$ROOT/backend/mvnw" ] || {
  echo "错误：缺少可执行的 backend/mvnw；恢复 Maven Wrapper 后重试。" >&2
  exit 1
}

load_scenario_local_env

SCENARIO_BASE_URL="${SCENARIO_BASE_URL:-$DEFAULT_BASE_URL}"
SCENARIO_ADMIN_USER="${SCENARIO_ADMIN_USER:-admin}"
SCENARIO_ADMIN_PASSWORD="${SCENARIO_ADMIN_PASSWORD:-}"
if [ -z "$SCENARIO_ADMIN_PASSWORD" ]; then
  echo "错误：缺少 SCENARIO_ADMIN_PASSWORD，真跑层无法登录被测应用。" >&2
  echo "      两种提供方式（任选其一）：" >&2
  echo "        1. export SCENARIO_ADMIN_PASSWORD='<管理员密码>'" >&2
  echo "        2. 在 ${LOCAL_ENV_FILE} 中增加一行 SCENARIO_ADMIN_PASSWORD=<管理员密码>（该文件已被 .gitignore 忽略）" >&2
  exit 1
fi
export SCENARIO_BASE_URL SCENARIO_ADMIN_USER SCENARIO_ADMIN_PASSWORD

HEALTH_URL="${SCENARIO_BASE_URL%/}/actuator/health"
echo "==> 真跑层：被测应用 ${SCENARIO_BASE_URL}（账号 ${SCENARIO_ADMIN_USER}）"
echo "==> 前置：健康检查 ${HEALTH_URL}"
health_code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$HEALTH_URL" || true)"
if [ "$health_code" != "200" ]; then
  echo "错误：被测应用未就绪（${HEALTH_URL} 返回 ${health_code:-无响应}）。" >&2
  echo "      真跑层必须打在真实运行的应用上，不做降级：请先 ./scripts/dev.sh status 检查，再用 ./scripts/dev.sh start 启动。" >&2
  echo "      改了代码时，必须重启应用（./scripts/dev.sh stop && ./scripts/dev.sh start）才能验到新行为；本层不会替你重启。" >&2
  exit 1
fi
echo "    应用就绪。"

# 写入标记：只有它之后产生的报告才算本次运行（该变量在脚本开头已 export，子进程可继承）。
: >"$QUALITY_RUN_MARKER"

echo "==> 真跑层：按约定选择器运行场景测试（-Dtest='*ScenarioIT'，新增场景测试类无需改命令）"
(cd backend && ./mvnw -Djava.version=25 -pl apps/app-server -am \
  -Dtest='*ScenarioIT' -Dsurefire.failIfNoSpecifiedTests=false test)

echo "==> 真跑层：核验产物（必须是本次运行产生、用例数大于 0、无失败）"
bash "$ROOT/scripts/assert-surefire-ran.sh" '*ScenarioIT'

echo "==> 真跑层通过。"
echo "    报告与 runId 见 backend/apps/app-server/target/surefire-reports/ 下 *ScenarioIT* 输出文件。"
echo "    本层驱动的是正在运行的应用；结论只对当前运行中的构建成立。"
