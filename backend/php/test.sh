#!/usr/bin/env bash
# 本地自测：用 SQLite 起一个 PHP 内置服务器，把接口全流程跑一遍
set -u
cd "$(dirname "$0")"
PORT=${PORT:-8099}
export DB_FILE=/tmp/fd_php_test.db
rm -f "$DB_FILE"

php -S 127.0.0.1:$PORT index.php >/tmp/fd_php_server.log 2>&1 &
PID=$!
trap 'kill $PID 2>/dev/null' EXIT
sleep 1

BASE="http://127.0.0.1:$PORT/api"
pass=0; fail=0
chk() { # chk 描述 期望 实际
  if [ "$2" = "$3" ]; then pass=$((pass+1)); echo "  ok  $1"; else fail=$((fail+1)); echo "  FAIL $1  期望=$2 实际=$3"; fi
}
jget() { python3 -c "import sys,json;d=json.load(sys.stdin);print(eval(sys.argv[1]))" "$1"; }

H=$(curl -s $BASE/health)
chk "健康检查 platform=php" "php" "$(echo "$H" | jget "d['data']['platform']")"

R=$(curl -s -X POST $BASE/register -H 'Content-Type: application/json' -d '{"username":"zhangsan","password":"secret123"}')
chk "注册成功" "0" "$(echo "$R" | jget "d['code']")"
TOKEN=$(echo "$R" | jget "d['data']['token']")

D=$(curl -s -X POST $BASE/register -H 'Content-Type: application/json' -d '{"username":"zhangsan","password":"secret123"}')
chk "重复注册 409" "409" "$(echo "$D" | jget "d['code']")"

W=$(curl -s -X POST $BASE/login -H 'Content-Type: application/json' -d '{"username":"zhangsan","password":"wrong"}')
chk "错密码 401" "401" "$(echo "$W" | jget "d['code']")"

T=$(curl -s -X POST $BASE/login -H 'Content-Type: application/json' -d '{"username":"zhangsan","password":"secret123"}')
chk "登录成功" "0" "$(echo "$T" | jget "d['code']")"

N=$(curl -s $BASE/bills)
chk "无 token 拒绝" "401" "$(echo "$N" | jget "d['code']")"

S1=$(curl -s -X POST $BASE/bills/sync -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"bills":[{"uid":"u1","room":"526","tenant":"张三","month":"2026-11","note":"","waterMeters":[{"label":"水表1","start":0,"end":65,"usage":65,"fee":195}],"gasMeters":[],"meters":[{"label":"电表1","start":800,"end":858,"usage":58,"fee":58}],"waterFee":195,"gasFee":0,"elecFee":58,"extras":[{"name":"卫生费","usageBased":false,"usage":0,"amount":10}],"updatedAt":1000}]}')
chk "上传成功 1 条" "1" "$(echo "$S1" | jget "d['data']['uploaded']")"
chk "云端总数 1" "1" "$(echo "$S1" | jget "d['data']['count']")"

S2=$(curl -s -X POST $BASE/bills/sync -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"bills":[{"uid":"u1","room":"旧房间","month":"2026-11","updatedAt":500}]}')
chk "旧时间戳不覆盖" "526" "$(echo "$S2" | jget "[b for b in d['data']['bills'] if b['uid']=='u1'][0]['room']")"

S3=$(curl -s -X POST $BASE/bills/sync -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"bills":[{"uid":"u1","room":"新房间","month":"2026-11","waterMeters":[{"label":"水表1","start":0,"end":65,"usage":65,"fee":195}],"waterFee":195,"updatedAt":2000},{"uid":"u2","room":"202","month":"2026-11","updatedAt":2000}]}')
chk "新时间戳覆盖" "新房间" "$(echo "$S3" | jget "[b for b in d['data']['bills'] if b['uid']=='u1'][0]['room']")"
chk "追加后共 2 条" "2" "$(echo "$S3" | jget "d['data']['count']")"
chk "字段保留 waterFee" "195" "$(echo "$S3" | jget "[b for b in d['data']['bills'] if b['uid']=='u1'][0]['waterFee']")"

R2=$(curl -s -X POST $BASE/register -H 'Content-Type: application/json' -d '{"username":"lisi","password":"secret123"}')
TOK2=$(echo "$R2" | jget "d['data']['token']")
B2=$(curl -s $BASE/bills -H "Authorization: Bearer $TOK2")
chk "账号隔离（新账号 0 条）" "0" "$(echo "$B2" | jget "d['data']['count']")"

DEL=$(curl -s -X POST $BASE/bills/delete -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"uid":"u1"}')
chk "删除成功" "u1" "$(echo "$DEL" | jget "d['data']['uid']")"
AFTER=$(curl -s $BASE/bills -H "Authorization: Bearer $TOKEN")
chk "删除后剩 1 条" "1" "$(echo "$AFTER" | jget "d['data']['count']")"

L=$(curl -s -X POST $BASE/logout -H "Authorization: Bearer $TOKEN")
chk "登出成功" "0" "$(echo "$L" | jget "d['code']")"
G=$(curl -s $BASE/bills -H "Authorization: Bearer $TOKEN")
chk "登出后 token 失效" "401" "$(echo "$G" | jget "d['code']")"

NF=$(curl -s $BASE/nope -H "Authorization: Bearer $TOK2")
chk "未知接口 404" "404" "$(echo "$NF" | jget "d['code']")"

echo
echo "$pass passed, $fail failed"
[ $fail -eq 0 ] && echo "ALL PASS"
