#!/usr/bin/env bash
# 开发库重置工具（唯一合法的删库入口）
#
# 红线（AGENTS §16.1）：删库/清数据必须同时满足——
#   1. 数据确认为一次性测试数据（非真实、非用户验证过的数据）；
#   2. 用户明确知晓并同意；
#   3. 已先修复代码 bug 再验证。
# 本工具强制：先备份（data/backup/）、再确认（输入 RESET）、最后删除重建。
#
# 用法：
#   ./scripts/reset-dev-db.sh          # 交互确认后备份并重置开发库与上传文件
#   ./scripts/reset-dev-db.sh --yes    # 跳过确认（CI/脚本内使用，仍需先备份）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DB="$ROOT/backend/data/internal-admin.db"
UPLOADS="$ROOT/backend/data/uploads"
BACKUP_DIR="$ROOT/data/backup"

if [ ! -f "$DB" ]; then
  echo "开发库不存在（$DB），无需重置。"
  exit 0
fi

# 1. 备份（时间戳目录，含数据库与上传文件）
STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_PATH="$BACKUP_DIR/$STAMP"
mkdir -p "$BACKUP_PATH"
cp "$DB" "$BACKUP_PATH/internal-admin.db"
if [ -d "$UPLOADS" ]; then
  cp -r "$UPLOADS" "$BACKUP_PATH/uploads"
fi
echo "已备份到：$BACKUP_PATH"
echo "备份内容：$(du -sh "$BACKUP_PATH" | cut -f1)"

# 2. 确认（除非 --yes）
if [ "${1:-}" != "--yes" ]; then
  echo ""
  echo "警告：将删除开发库（$DB）与上传文件（$UPLOADS）。"
  echo "请确认这些数据是一次性测试数据且用户已知晓同意。"
  read -r -p "输入 RESET 继续，其他任意键取消： " CONFIRM
  if [ "$CONFIRM" != "RESET" ]; then
    echo "已取消。"
    exit 0
  fi
fi

# 3. 删除并重建（Liquibase 会在下次启动时自动迁移）
rm -f "$DB"
rm -rf "$UPLOADS"
echo "开发库与上传文件已重置。"
echo "下次启动将自动创建空库并迁移；初始密码输出到日志（或设置 APP_ADMIN_PASSWORD）。"
echo "如需恢复：停止服务后将 data/backup/$STAMP 中的文件复制回 backend/data/。"
