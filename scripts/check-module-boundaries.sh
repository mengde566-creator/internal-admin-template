#!/usr/bin/env bash
# 只读生产源码模块边界检查；规则权威来源：docs/architecture/BACKEND_MODULES.md。
#
# 工具约定：只用 POSIX 工具（find/grep/sed）。这里刻意不依赖 ripgrep：
# 本检查曾用 `rg ... || true` 实现，而 rg 不是项目声明的前置工具；在没装 rg 的机器上
# 命令以 127 失败被 `|| true` 吞掉，循环读不到任何行，脚本照样打印"通过"并退出 0
# ——检查长期空转且无人发现（DEF-006）。因此这里既不用未声明的工具，也在开头显式
# 校验工具存在性：工具缺失必须响亮失败，不允许静默通过。
set -euo pipefail

require_tool() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "错误：边界检查缺少必需工具 $1；本脚本只依赖 dirname/find/grep/sed，请修复运行环境。" >&2
    exit 1
  }
}

# 工具存在性必须最先校验：下面的 ROOT 计算本身就依赖 dirname。
require_tool dirname
require_tool find
require_tool grep
require_tool sed

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAILURES=0

require_dir() {
  [ -d "$1" ] || { echo "错误：边界检查缺少目录 ${1#$ROOT/}。" >&2; exit 1; }
}

violation() {
  echo "边界违规 [$1]：$2" >&2
  FAILURES=1
}

# 统计目录下匹配的文件（含 -H 强制带文件名，避免单文件时不带前缀导致解析错位）。
# grep_flags 会按词拆分传给 grep：权限命名空间检查必须传 -o，只输出匹配片段，
# 否则整行里的引号会让命名空间提取错位（会把 "@PreAuthorize(...)" 误判成违规）。
scan() {
  local source_dir="$1" path_filter="$2" grep_flags="$3" pattern="$4"
  shift 4
  find "$source_dir" -path "$path_filter" -type f "$@" \
    -exec grep -Hn $grep_flags -E "$pattern" {} + 2>/dev/null || true
}

check_java_imports() {
  local rule="$1" source_dir="$2" allowed="$3" path_filter="${4:-*}" match module
  while IFS= read -r match; do
    module="$(sed -E 's/.*com\.internaladmin\.module\.([a-z-]+)\..*/\1/' <<<"$match")"
    [[ "$module" =~ $allowed ]] || violation "$rule" "$match"
  done < <(scan "$source_dir" "$path_filter" "" \
    '^[[:space:]]*import[[:space:]]+com\.internaladmin\.module\.[a-z-]+\.' -name '*.java')
}

check_permission_namespace() {
  local module="$1" allowed="$2" source_dir="$ROOT/backend/modules/$1/src/main/java" match namespace
  while IFS= read -r match; do
    namespace="${match##*[\'\"]}"
    namespace="${namespace%:}"
    [[ "$namespace" =~ $allowed ]] || violation "$module 权限命名空间" "$match"
  done < <(scan "$source_dir" '*' '-o' \
    "has(Any)?Authority\(['\"][a-z][a-z0-9-]*:" -name '*.java' ! -path '*/api/PermissionCodes.java')
}

check_frontend_shared_imports() {
  local match
  while IFS= read -r match; do
    violation "frontend/src/shared 禁止业务模块导入" "$match"
  done < <(scan "$ROOT/frontend/src/shared" '*' "" \
    "(from[[:space:]]+|import[[:space:]]*\()[[:space:]]*['\"][^'\"]*(/|@/)modules/" \
    \( -name '*.ts' -o -name '*.tsx' -o -name '*.vue' \) \
    ! -name '*.test.*' ! -name '*.spec.*' ! -path '*/__tests__/*')
}

require_dir "$ROOT/backend/foundation"
require_dir "$ROOT/backend/modules/module-file/src/main/java"
require_dir "$ROOT/backend/modules/module-audit/src/main/java"
require_dir "$ROOT/backend/modules/module-iam/src/main/java"
require_dir "$ROOT/frontend/src/shared"

check_java_imports "foundation 禁止业务模块依赖" "$ROOT/backend/foundation" '^$' '*/src/main/java/*'
check_java_imports "module-file 禁止业务模块依赖" "$ROOT/backend/modules/module-file/src/main/java" '^file$'
check_java_imports "module-audit 禁止业务模块依赖" "$ROOT/backend/modules/module-audit/src/main/java" '^audit$'
check_java_imports "module-iam 仅允许 module-audit" "$ROOT/backend/modules/module-iam/src/main/java" '^(iam|audit)$'
check_permission_namespace module-file file
check_permission_namespace module-audit audit
check_permission_namespace module-iam '^(iam|system)$'
check_frontend_shared_imports

if [ "$FAILURES" -ne 0 ]; then
  exit 1
fi

echo "模块边界检查通过（仅生产源码）。"
