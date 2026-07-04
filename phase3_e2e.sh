#!/usr/bin/env bash
# Phase 3 E2E 验收脚本：缺货识别 → 建单 → 认领 → 入库 → 状态回写 + 权限拦截
set -u
BASE=http://localhost:8080/api
OUT=/tmp/wms_p3_e2e.txt
: > "$OUT"

login() {
  local user=$1
  curl -s -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$user\",\"password\":\"123456\"}" 2>/dev/null \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])" 2>/dev/null
}

echo "===== 登录 warehouse_admin =====" >> "$OUT"
W_TOKEN=$(login warehouse_admin)
echo "warehouse_admin token len: ${#W_TOKEN}" >> "$OUT"

echo "" >> "$OUT"; echo "===== 登录 purchase_admin =====" >> "$OUT"
P_TOKEN=$(login purchase_admin)
echo "purchase_admin token len: ${#P_TOKEN}" >> "$OUT"

echo "" >> "$OUT"; echo "===== 登录 sales_admin (用于权限负测) =====" >> "$OUT"
S_TOKEN=$(login sales_admin)
echo "sales_admin token len: ${#S_TOKEN}" >> "$OUT"

echo "" >> "$OUT"; echo "===== 1. 缺货识别 (warehouse_admin) =====" >> "$OUT"
curl -s "$BASE/business/purchase-requests/shortage-goods" -H "Authorization: Bearer $W_TOKEN" >> "$OUT"
echo "" >> "$OUT"

# 找一个缺货商品 id（若无缺货则取任意启用商品）
SHORT_ID=$(curl -s "$BASE/business/purchase-requests/shortage-goods" -H "Authorization: Bearer $W_TOKEN" \
  | python3 -c "import sys,json; d=json.load(sys.stdin)['data']; print(d[0]['id'] if d else '')" 2>/dev/null)
echo "缺货商品ID: $SHORT_ID" >> "$OUT"

echo "" >> "$OUT"; echo "===== 2. 建采购申请单 (warehouse_admin) =====" >> "$OUT"
# 若无缺货商品，用 id=1 兜底（仅验证流程）
GID="${SHORT_ID:-1}"
CREATE_BODY="{\"remark\":\"E2E测试\",\"details\":[{\"goodsId\":$GID,\"quantity\":10}]}"
curl -s -X POST "$BASE/business/purchase-requests" -H "Authorization: Bearer $W_TOKEN" \
  -H 'Content-Type: application/json' -d "$CREATE_BODY" >> "$OUT"
echo "" >> "$OUT"

echo "" >> "$OUT"; echo "===== 3. 查列表 (warehouse_admin) =====" >> "$OUT"
curl -s "$BASE/business/purchase-requests/page?pageNum=1&pageSize=5" -H "Authorization: Bearer $W_TOKEN" >> "$OUT"
echo "" >> "$OUT"

# 取最新申请单 id
REQ_ID=$(curl -s "$BASE/business/purchase-requests/page?pageNum=1&pageSize=1" -H "Authorization: Bearer $W_TOKEN" \
  | python3 -c "import sys,json; d=json.load(sys.stdin)['data']['records']; print(d[0]['id'] if d else '')" 2>/dev/null)
echo "申请单ID: $REQ_ID" >> "$OUT"

echo "" >> "$OUT"; echo "===== 4. 非采购admin建单被拒 (sales_admin 应403) =====" >> "$OUT"
curl -s -X POST "$BASE/business/purchase-requests" -H "Authorization: Bearer $S_TOKEN" \
  -H 'Content-Type: application/json' -d "$CREATE_BODY" >> "$OUT"
echo "" >> "$OUT"

echo "" >> "$OUT"; echo "===== 5. 采购认领 (purchase_admin) =====" >> "$OUT"
curl -s -X PUT "$BASE/business/purchase-requests/$REQ_ID/process" -H "Authorization: Bearer $P_TOKEN" >> "$OUT"
echo "" >> "$OUT"

echo "" >> "$OUT"; echo "===== 6. 取明细 detailId (purchase_admin) =====" >> "$OUT"
DETAIL_ID=$(curl -s "$BASE/business/purchase-requests/$REQ_ID" -H "Authorization: Bearer $P_TOKEN" \
  | python3 -c "import sys,json; d=json.load(sys.stdin)['data']['details']; print(d[0]['id'] if d else '')" 2>/dev/null)
echo "明细ID: $DETAIL_ID" >> "$OUT"

echo "" >> "$OUT"; echo "===== 7. 入库前库存 (warehouse_admin 查缺货商品) =====" >> "$OUT"
curl -s "$BASE/business/purchase-requests/shortage-goods" -H "Authorization: Bearer $W_TOKEN" >> "$OUT"
echo "" >> "$OUT"

echo "" >> "$OUT"; echo "===== 8. 转入库 (purchase_admin, 复用biz_purchase加库存) =====" >> "$OUT"
RECV_BODY="{\"items\":[{\"detailId\":$DETAIL_ID,\"quantity\":10,\"unitPrice\":5.50}]}"
curl -s -X PUT "$BASE/business/purchase-requests/$REQ_ID/receive" -H "Authorization: Bearer $P_TOKEN" \
  -H 'Content-Type: application/json' -d "$RECV_BODY" >> "$OUT"
echo "" >> "$OUT"

echo "" >> "$OUT"; echo "===== 9. 入库后状态回写 (查详情 status应=3已入库) =====" >> "$OUT"
curl -s "$BASE/business/purchase-requests/$REQ_ID" -H "Authorization: Bearer $P_TOKEN" >> "$OUT"
echo "" >> "$OUT"

echo "" >> "$OUT"; echo "===== 10. 重复入库被拒 (应400) =====" >> "$OUT"
curl -s -X PUT "$BASE/business/purchase-requests/$REQ_ID/receive" -H "Authorization: Bearer $P_TOKEN" \
  -H 'Content-Type: application/json' -d "$RECV_BODY" >> "$OUT"
echo "" >> "$OUT"

echo "" >> "$OUT"; echo "===== DONE =====" >> "$OUT"
cat "$OUT"
