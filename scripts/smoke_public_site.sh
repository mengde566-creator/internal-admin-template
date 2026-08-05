#!/usr/bin/env bash
# 公开主页改版冒烟测试（一次性、不修改业务代码、不删除项目文件）
# 仅启动服务、走关键接口、停止服务。测试库/上传目录隔离在 data/smoke-*。
# 注意：curl 是原生 Windows 程序，所有文件路径必须用 Windows 绝对路径（E:/...）。
set -u

BASE="http://127.0.0.1:18091"
JAVA="$JAVA_HOME/bin/java"
APP_JAR="E:/projects/internal-admin-template/backend/apps/app-server/target/app-server-0.1.0-SNAPSHOT.jar"
DB="E:/projects/internal-admin-template/data/smoke-test.db"
UPLOADS="E:/projects/internal-admin-template/data/smoke-uploads"
DATA="E:/projects/internal-admin-template/data"
CK="$DATA/smoke-cookies.txt"
BODYDIR="$DATA/smoke-bodies"
HERO="$DATA/smoke-hero.png"
PASS=0; FAIL=0

mkdir -p "$BODYDIR"
: > "$CK"   # 确保 cookie jar 存在（空文件，curl 会追加）

ck() { grep -i 'XSRF-TOKEN' "$CK" | awk '!/^#/ {print $NF}' | tail -1; }
assert() { local desc="$1" act="$2" exp="$3"
  if [ "$act" = "$exp" ]; then echo "  PASS  $desc (=$act)"; PASS=$((PASS+1));
  else echo "  FAIL  $desc (实际=$act, 期望=$exp)"; FAIL=$((FAIL+1)); fi; }
assert_contains() { local desc="$1" hay="$2" nd="$3"
  if printf '%s' "$hay" | grep -q -- "$nd"; then echo "  PASS  $desc (含 '$nd')"; PASS=$((PASS+1));
  else echo "  FAIL  $desc (未含 '$nd')"; FAIL=$((FAIL+1)); fi; }

echo '=== 0. 准备测试图片（Windows 路径）==='
python - "$HERO" <<'PY'
import base64, sys, os
p = sys.argv[1]
open(p,'wb').write(base64.b64decode(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=='))
print('hero.png ->', p, os.path.getsize(p), 'bytes')
PY

echo '=== 1. 启动服务（空库迁移 + 固定初始密码）==='
"$JAVA" -jar "$APP_JAR" \
  --spring.datasource.url="jdbc:sqlite:$DB?foreign_keys=on" \
  --app.admin-initial-password="SmokeTest123" \
  --app.storage-root="$UPLOADS" \
  --server.port=18091 > "$DATA/smoke-server.log" 2>&1 &
SRV=$!
echo "server pid=$SRV"

python - <<'PY'
import urllib.request, time, sys
ok=None
for _ in range(120):
    try:
        with urllib.request.urlopen("http://127.0.0.1:18091/actuator/health", timeout=1) as r:
            if b'"status":"UP"' in r.read():
                ok=True; break
    except Exception:
        pass
    time.sleep(1)
print("HEALTH_OK" if ok else "HEALTH_FAIL")
sys.exit(0 if ok else 1)
PY
[ $? -ne 0 ] && echo "服务未就绪，中止" && kill $SRV 2>/dev/null && exit 1

echo '=== 2. 匿名公开读（未发布）应 404 ==='
CODE=$(curl -s -o "$BODYDIR/r_pub_before.txt" -w "%{http_code}" "$BASE/api/public/site")
assert "匿名GET /api/public/site 未发布" "$CODE" "404"

echo '=== 3. 获取 CSRF token（GET /api/auth/me 种 cookie）==='
curl -s -c "$CK" -o /dev/null "$BASE/api/auth/me"
TOKEN=$(ck)
echo "XSRF-TOKEN len=${#TOKEN}"
[ -n "$TOKEN" ] && echo "  PASS  CSRF token 已获取" || { echo "  FAIL  CSRF token 缺失"; FAIL=$((FAIL+1)); }

echo '=== 4. 登录 admin ==='
CODE=$(curl -s -b "$CK" -c "$CK" -o "$BODYDIR/r_login.txt" -w "%{http_code}" \
  -X POST -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $TOKEN" \
  -d '{"username":"admin","password":"SmokeTest123"}' "$BASE/api/auth/login")
assert "POST /api/auth/login" "$CODE" "200"
TOKEN=$(ck)

echo '=== 5. 上传主图（需 site:homepage:edit）==='
CODE=$(curl -s -b "$CK" -c "$CK" -o "$BODYDIR/r_upload.txt" -w "%{http_code}" \
  -X POST -H "X-XSRF-TOKEN: $TOKEN" \
  -F "file=@$HERO;type=image/png" "$BASE/api/files")
assert "POST /api/files 上传" "$CODE" "200"
FILEID=$(python -c "import json;print(json.load(open('$BODYDIR/r_upload.txt'))['data']['fileId'])" 2>/dev/null || echo "")
echo "  fileId=$FILEID"

echo '=== 6. 保存草稿（含布局 GRID_SPLIT + 2 区块）==='
# 注意：curl 经 Git-Bash 发送非 ASCII 会被错误编码（服务端 Jackson 按 UTF-8 解析失败）。
# 冒烟仅验证逻辑，使用 ASCII 内容（系统只要求非空，不要求中文）。
DRAFT=$(cat <<EOF
{"siteName":"SmokeSite","introduction":"A short intro","contactText":"contact@example.com","colorScheme":"GRAPHITE","layoutCode":"GRID_SPLIT","heroFileId":$FILEID,"sections":[{"sectionType":"ABOUT","title":"About Us","content":"About content"},{"sectionType":"SERVICE","title":"Our Service","content":"Service content"}]}
EOF
)
CODE=$(curl -s -b "$CK" -c "$CK" -o "$BODYDIR/r_save.txt" -w "%{http_code}" \
  -X PUT -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $TOKEN" \
  -d "$DRAFT" "$BASE/api/site/draft")
assert "PUT /api/site/draft 保存草稿" "$CODE" "200"
python - "$BODYDIR/r_save.txt" <<'PY'
import json,sys
try:
    d=json.load(open(sys.argv[1]))['data']
    secs=d.get('sections') or []
    print('  layoutCode=',d.get('layoutCode'),'| colorScheme=',d.get('colorScheme'),'| sections=',len(secs))
    for s in secs:
        print('    section id=',s.get('id'),'type=',s.get('sectionType'),'sortOrder=',s.get('sortOrder'))
except Exception as e:
    print('  (解析失败:',e,')')
PY

echo '=== 7. 读取草稿 ==='
CODE=$(curl -s -b "$CK" -c "$CK" -o "$BODYDIR/r_draft_get.txt" -w "%{http_code}" "$BASE/api/site/draft")
assert "GET /api/site/draft" "$CODE" "200"

echo '=== 8. 发布 ==='
CODE=$(curl -s -b "$CK" -c "$CK" -o /dev/null -w "%{http_code}" \
  -X POST -H "X-XSRF-TOKEN: $TOKEN" "$BASE/api/site/publish")
assert "POST /api/site/publish 发布" "$CODE" "200"

echo '=== 9. 匿名公开读（已发布）应 200 且含布局/区块 ==='
CODE=$(curl -s -o "$BODYDIR/r_pub_after.txt" -w "%{http_code}" "$BASE/api/public/site")
assert "匿名GET /api/public/site 已发布" "$CODE" "200"
BODY=$(cat "$BODYDIR/r_pub_after.txt" 2>/dev/null || echo "")
assert_contains "公开内容含 layoutCode=GRID_SPLIT" "$BODY" "GRID_SPLIT"
assert_contains "公开内容含 colorScheme=GRAPHITE" "$BODY" "GRAPHITE"
python - "$BODYDIR/r_pub_after.txt" <<'PY'
import json,sys
try:
    d=json.load(open(sys.argv[1]))['data']
    secs=d.get('sections') or []
    print('  公开 sections=',len(secs),'| 第一区块 type=',(secs[0].get('sectionType') if secs else None))
except Exception as e:
    print('  (解析失败:',e,')')
PY

echo '=== 10. 撤回 ==='
CODE=$(curl -s -b "$CK" -c "$CK" -o /dev/null -w "%{http_code}" \
  -X POST -H "X-XSRF-TOKEN: $TOKEN" "$BASE/api/site/withdraw")
assert "POST /api/site/withdraw 撤回" "$CODE" "200"

echo '=== 11. 匿名公开读（已撤回）应 404 ==='
CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/public/site")
assert "匿名GET /api/public/site 已撤回" "$CODE" "404"

echo '=== 12. 越权：匿名保存草稿应 403 ==='
CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  -X PUT -H "Content-Type: application/json" \
  -d '{"siteName":"x","introduction":"y","contactText":"z","colorScheme":"GRAPHITE","layoutCode":"GRID_SPLIT","heroFileId":1,"sections":[]}' \
  "$BASE/api/site/draft")
assert "匿名PUT /api/site/draft 越权" "$CODE" "403"

echo '=== 13. 越权：匿名发布应 403 ==='
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/site/publish")
assert "匿名POST /api/site/publish 越权" "$CODE" "403"

echo '=== 14. 停止服务 ==='
kill $SRV 2>/dev/null && echo "server stopped (pid=$SRV)"

echo
echo "================ 冒烟结果 ================"
echo "PASS=$PASS  FAIL=$FAIL"
[ "$FAIL" -eq 0 ] && echo "结论：全部通过" || echo "结论：存在失败项"
exit $FAIL
