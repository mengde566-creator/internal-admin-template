#!/usr/bin/env bash
# 通过无数据库 MVC 契约导出器生成 OpenAPI 与 TypeScript，并检测仓库生成物漂移。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:-}"
SCHEMA_PATH="${OPENAPI_SCHEMA_PATH:-$ROOT/docs/system/api/openapi.json}"
TYPE_PATH="${OPENAPI_TYPE_PATH:-$ROOT/frontend/src/generated/api-schema.ts}"

usage() {
  echo "用法：$0 generate|check" >&2
  echo "前置条件：tools/openapi 已执行标准 npm ci；命令只运行 NoDatabaseOpenApiContractTest，不启动主应用或数据库。" >&2
  exit 64
}

export_schema() {
  local raw_target="$1"
  local normalized_target="$2"
  (
    cd "$ROOT/backend"
    ./mvnw -Djava.version=17 -pl apps/app-server -am \
      -Dtest=NoDatabaseOpenApiContractTest \
      -Dsurefire.failIfNoSpecifiedTests=false \
      -Dopenapi.contract.output="$raw_target" \
      test
  )
  [[ -s "$raw_target" ]] || {
    echo "错误：无数据库契约导出器未写出 springdoc OpenAPI JSON。" >&2
    exit 1
  }
  node "$ROOT/scripts/normalize-openapi-json.mjs" "$raw_target" "$normalized_target"
  node "$ROOT/scripts/assert-openapi-contract.mjs" "$normalized_target"
}

generate_types() {
  local schema="$1"
  local target="$2"
  (
    cd "$ROOT/tools/openapi"
    npm exec -- openapi-typescript "$schema" --output "$target"
  )
  node "$ROOT/scripts/assert-openapi-contract.mjs" --type-header "$target"
}

case "$MODE" in
  generate)
    temp_dir="$(mktemp -d)"
    trap 'rm -rf "$temp_dir"' EXIT
    export_schema "$temp_dir/openapi.raw.json" "$temp_dir/openapi.json"
    generate_types "$temp_dir/openapi.json" "$temp_dir/api-schema.ts"
    mkdir -p "$(dirname "$SCHEMA_PATH")" "$(dirname "$TYPE_PATH")"
    mv "$temp_dir/openapi.json" "$SCHEMA_PATH"
    mv "$temp_dir/api-schema.ts" "$TYPE_PATH"
    echo "已由无数据库 contract 测试应用的 springdoc 运行时生成 OpenAPI 与 TypeScript 类型。"
    ;;
  check)
    if [[ ! -f "$SCHEMA_PATH" || ! -f "$TYPE_PATH" ]]; then
      echo "错误：缺少已提交的生成物；请先执行 $0 generate（该入口只运行无数据库契约测试应用）。" >&2
      exit 1
    fi
    temp_dir="$(mktemp -d)"
    trap 'rm -rf "$temp_dir"' EXIT
    export_schema "$temp_dir/openapi.raw.json" "$temp_dir/openapi.json"
    generate_types "$temp_dir/openapi.json" "$temp_dir/api-schema.ts"
    cmp --silent "$temp_dir/openapi.json" "$SCHEMA_PATH" || {
      echo "错误：docs/system/api/openapi.json 与运行时规范不一致。" >&2
      exit 1
    }
    cmp --silent "$temp_dir/api-schema.ts" "$TYPE_PATH" || {
      echo "错误：frontend/src/generated/api-schema.ts 与 OpenAPI 类型生成结果不一致。" >&2
      exit 1
    }
    echo "OpenAPI 契约与前端生成类型无漂移。"
    ;;
  *)
    usage
    ;;
esac
