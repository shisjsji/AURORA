#!/bin/bash
# 覆盖本轮修复场景的全链路测试
set -u
B=http://127.0.0.1:5003
pass=0; fail=0
ok()   { pass=$((pass+1)); echo "PASS: $1"; }
bad()  { fail=$((fail+1)); echo "FAIL: $1"; }
jq_get() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d$1)" 2>/dev/null; }

echo "=== 1. 恢复码格式与密码学随机性 ==="
K1=$(curl -s -X POST $B/account -H 'Content-Type: application/json' -d '{"anonId":"fix-anon-1"}' | jq_get "['recoveryKey']")
K2=$(curl -s -X POST $B/account -H 'Content-Type: application/json' -d '{"anonId":"fix-anon-2"}' | jq_get "['recoveryKey']")
echo "keys: $K1 / $K2"
echo "$K1" | grep -qE '^[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}$' && ok "key format" || bad "key format"
[ "$K1" != "$K2" ] && ok "keys differ" || bad "keys identical"

echo "=== 2. 找回冲突：anonId 已被另一个账户占用 ==="
# anonX 发布一条 → 账户 X 持有 anonX
curl -s -X POST $B/presets -H 'Content-Type: application/json' \
  -d '{"anonId":"fix-anon-x","name":"X的曲线","curve":[10,0,0,0,0,0],"model":"25098PN5AC/pandora","deviceLabel":"17 Pro"}' > /dev/null
# 同一台设备拿 K1 认领 → 应该成功而不是 500
R=$(curl -s -w '|%{http_code}' -X POST $B/account/recover -H 'Content-Type: application/json' -d "{\"recoveryKey\":\"$K1\",\"anonId\":\"fix-anon-x\"}")
echo "recover: $R"
echo "$R" | grep -q '200' && ok "recover over existing binding" || bad "recover over existing binding"

echo "=== 3. 小写/无连字符找回码 ==="
K1LOW=$(echo "$K1" | tr -d '-' | tr 'A-Z' 'a-z')
R=$(curl -s -w '|%{http_code}' -X POST $B/account/recover -H 'Content-Type: application/json' -d "{\"recoveryKey\":\"$K1LOW\",\"anonId\":\"fix-anon-1b\"}")
echo "$R" | grep -q '200' && ok "normalized key accepted" || bad "normalized key accepted"

echo "=== 4. 点赞时建账户要返回找回码 ==="
R=$(curl -s -X POST $B/presets/1/like -H 'Content-Type: application/json' -d '{"anonId":"fix-anon-liker"}')
echo "like: $R"
echo "$R" | grep -q 'recoveryKey' && ok "like returns key on account creation" || bad "like returns key on account creation"

echo "=== 5. 喜欢计数下限 ==="
curl -s -X DELETE $B/presets/1/like -H 'Content-Type: application/json' -d '{"anonId":"fix-anon-liker"}' > /dev/null
curl -s -X DELETE $B/presets/1/like -H 'Content-Type: application/json' -d '{"anonId":"fix-anon-liker"}' > /dev/null
C=$(curl -s "$B/presets" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['items'][0]['likes'])" 2>/dev/null)
echo "likes after double unlike: $C"
[ "$C" -ge 0 ] 2>/dev/null && ok "likes floor" || bad "likes floor ($C)"

echo "=== 6. feed 批量 liked 状态 ==="
curl -s -X POST $B/presets/1/like -H 'Content-Type: application/json' -d '{"anonId":"fix-anon-x"}' > /dev/null
L=$(curl -s "$B/presets?anonId=fix-anon-x" | python3 -c "import sys,json;d=json.load(sys.stdin);print([i['liked'] for i in d['items']])" 2>/dev/null)
echo "liked flags: $L"
echo "$L" | grep -q 'True' && ok "feed liked flag" || bad "feed liked flag"

echo "=== 7. 老接口回归 ==="
curl -s $B/health | grep -q ok && ok "health" || bad "health"
curl -s "$B/presets" | grep -q '"items"' && ok "feed" || bad "feed"
curl -s "$B/presets/1?anonId=fix-anon-x" | grep -q commentList && ok "detail" || bad "detail"
curl -s "$B/likes?anonId=fix-anon-x" | grep -q '"ok":true' && ok "my likes" || bad "my likes"

echo
echo "RESULT: pass=$pass fail=$fail"