#!/usr/bin/env bash
# =============================================
# 把当前数据库 warehouse_management 的全库快照同步到 GitHub
# 用法：bash sync-db-dump.sh
# 流程：mysqldump 覆盖 db-dump.sql → 有变化才 commit+push
# =============================================
set -euo pipefail
cd "$(dirname "$0")"

DUMP_FILE="db-dump.sql"

# 导出全库（结构+数据+视图+存储过程+触发器）
MYSQL_PWD=wms_pass mysqldump -u wms_user \
    --databases warehouse_management \
    --single-transaction --routines --triggers --events --no-tablespaces \
    --default-character-set=utf8mb4 > "$DUMP_FILE.tmp"

# 文件头说明 + 去掉每次都变的时间戳行（保证无数据变化时无 diff，不产生空提交）
sed -i '/^-- Dump completed on/d' "$DUMP_FILE.tmp"
sed -i '1i -- =============================================\n-- 仓库管理系统 - 全库数据快照（脚本自动导出，勿手改）\n-- 内容：结构+数据+视图+存储过程+触发器（含 CREATE DATABASE/USE）\n-- 恢复：mysql -u wms_user -pwms_pass < db-dump.sql\n-- 更新：bash sync-db-dump.sh（覆盖本文件并推送）\n-- =============================================' "$DUMP_FILE.tmp"

mv "$DUMP_FILE.tmp" "$DUMP_FILE"

# 无变化则不产生空提交（porcelain 可同时覆盖"未跟踪"与"已修改"两种状态）
if git status --porcelain -- "$DUMP_FILE" | grep -q .; then
    git add "$DUMP_FILE"
    git commit -m "chore(sql): 全库数据快照更新 db-dump.sql（sync-db-dump.sh 自动导出）"
    git push origin HEAD
    echo "已同步到 GitHub。"
else
    echo "数据库无变化，远端已是最新，无需提交。"
fi
