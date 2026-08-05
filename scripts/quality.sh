#!/usr/bin/env bash
# Internal Admin Template 统一质量入口（本地与 CI 共用，REQ-V01-002）
#
# 任一必选检查失败即整体失败（set -e），禁止跳过检查继续交付。
# 前置环境：JDK 25 + Maven（JAVA_HOME 已配置）、Node.js + npm。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "==> [1/3] 后端：编译 + 测试"
(cd backend && mvn -q clean verify)

echo "==> [2/3] 后端：迁移检查（空库 -> Liquibase 迁移 -> 健康 -> 表校验）"
QUALITY_DB="$ROOT/data/quality-check.db"
rm -f "$QUALITY_DB"
JAR="$(ls backend/apps/app-server/target/app-server-*.jar 2>/dev/null | head -1)"
if [ -z "$JAR" ]; then
  echo "错误：未找到 app-server 构建产物，请先执行 [1/3]" >&2
  exit 1
fi
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
"$JAVA_BIN" -jar "$JAR" \
  --spring.datasource.url="jdbc:sqlite:$QUALITY_DB?foreign_keys=on" \
  --server.port=18080 > /tmp/quality-server.log 2>&1 &
SERVER_PID=$!
cleanup() {
  kill "$SERVER_PID" 2>/dev/null || true
  rm -f "$QUALITY_DB"
}
trap cleanup EXIT

HEALTH_OK=0
for _ in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:18080/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
    HEALTH_OK=1
    break
  fi
  sleep 1
done
if [ "$HEALTH_OK" != "1" ]; then
  echo "错误：应用未在 30 秒内健康（health 未返回 UP）" >&2
  tail -20 /tmp/quality-server.log >&2
  exit 1
fi

TABLE_COUNT="$(python -c "
import sqlite3
conn = sqlite3.connect(r'$QUALITY_DB')
n = len([r for r in conn.execute(\"SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'DATABASECHANGELOG%'\").fetchall()])
conn.close()
print(n)
")"
if [ "$TABLE_COUNT" != "12" ]; then
  echo "错误：迁移后业务表数量应为 12，实际为 $TABLE_COUNT" >&2
  exit 1
fi
echo "    迁移检查通过：12 张业务表已从空库创建"

echo "==> [3/3] 前端：类型检查 + 构建"
(cd frontend && npm run typecheck && npm run build)

echo "==> 质量门禁全部通过"
