#!/usr/bin/env bash
# 只读生产源码模块边界检查；规则权威来源：docs/architecture/BACKEND_MODULES.md。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAILURES=0

require_dir() {
  [ -d "$1" ] || { echo "错误：边界检查缺少目录 ${1#$ROOT/}。" >&2; exit 1; }
}

violation() {
  echo "边界违规 [$1]：$2" >&2
  FAILURES=1
}

check_java_imports() {
  local rule="$1" source_dir="$2" allowed="$3" file_glob="${4:-*.java}" match module
  while IFS= read -r match; do
    module="$(sed -E 's/.*com\.internaladmin\.module\.([a-z-]+)\..*/\1/' <<<"$match")"
    [[ "$module" =~ $allowed ]] || violation "$rule" "$match"
  done < <(rg -n --glob "$file_glob" '^[[:space:]]*import[[:space:]]+com\.internaladmin\.module\.[a-z-]+\.' "$source_dir" || true)
}

check_permission_namespace() {
  local module="$1" allowed="$2" source_dir="$ROOT/backend/modules/$1/src/main/java" match namespace
  while IFS= read -r match; do
    namespace="${match##*:}"
    [[ "$namespace" =~ $allowed ]] || violation "$module 权限命名空间" "$match"
  done < <(rg -n -o --pcre2 --glob '*.java' --glob '!**/api/PermissionCodes.java' 'has(?:Any)?Authority\([\x27\x22]\K[a-z][a-z0-9-]*(?=:)' "$source_dir" || true)
}

check_frontend_shared_imports() {
  local match
  while IFS= read -r match; do
    violation "frontend/src/shared 禁止业务模块导入" "$match"
  done < <(rg -n --glob '*.{ts,tsx,vue}' --glob '!*.test.*' --glob '!*.spec.*' --glob '!**/__tests__/**' "(from[[:space:]]+|import[[:space:]]*\\()[[:space:]]*['\"][^'\"]*(/|@/)modules/" "$ROOT/frontend/src/shared" || true)
}

require_dir "$ROOT/backend/foundation"
require_dir "$ROOT/backend/modules/module-file/src/main/java"
require_dir "$ROOT/backend/modules/module-audit/src/main/java"
require_dir "$ROOT/backend/modules/module-iam/src/main/java"
require_dir "$ROOT/frontend/src/shared"

check_java_imports "foundation 禁止业务模块依赖" "$ROOT/backend/foundation" '^$' '**/src/main/java/**/*.java'
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
