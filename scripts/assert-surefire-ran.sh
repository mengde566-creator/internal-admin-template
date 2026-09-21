#!/usr/bin/env bash
# 校验 surefire 报告确实由**本次运行**执行过指定的测试类。
#
# 存在理由：`quality.sh` 用 `-Dtest=<类名>` 选择测试，而 Maven 在"选择器零匹配"时
# 可以配置成不报错（`-Dsurefire.failIfNoSpecifiedTests=false`，本项目为配合 `-am`
# 必须这样配）。一旦测试类被改名、移动包或拼错，门禁会**静默少跑甚至不跑**却仍然通过
# ——DEF-005 就是这样红着没人知道的。
#
# 因此本脚本不看选择器，只看产物，并且要求产物是**新鲜的**：`target/surefire-reports`
# 会保留上一次运行的 XML，只看"报告存在"会拿旧报告顶替本次运行（改名后仍然变绿，
# 该漏洞由一次故意的改名实验暴露）。所以必须传入本次运行开始时创建的标记文件
# `QUALITY_RUN_MARKER`，报告修改时间早于该标记的一律视为陈旧、不计入通过。
#
# 用法：QUALITY_RUN_MARKER=<标记文件> bash scripts/assert-surefire-ran.sh <简单类名或 *约定模式> [...]
# 参数按 `TEST-*.<参数>.xml` 匹配，因此既支持具体类名（quality.sh），也支持 *ScenarioIT 约定（scenario-gate.sh）。
# 手工排查（明确接受陈旧报告）时：ALLOW_STALE_REPORTS=1 bash scripts/assert-surefire-ran.sh <简单类名>
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [ "$#" -eq 0 ]; then
  echo "错误：至少需要一个测试类名。" >&2
  exit 64
fi

MARKER="${QUALITY_RUN_MARKER:-}"
if [ -z "$MARKER" ] && [ "${ALLOW_STALE_REPORTS:-0}" != "1" ]; then
  echo "错误：未提供 QUALITY_RUN_MARKER，无法确认报告属于本次运行。" >&2
  echo "      surefire 报告会跨运行保留，只看报告存在会拿上一次的旧报告顶替本次结果。" >&2
  echo "      由 scripts/quality.sh 调用时应由它导出该标记；手工排查时显式设置 ALLOW_STALE_REPORTS=1。" >&2
  exit 64
fi
if [ -n "$MARKER" ] && [ ! -f "$MARKER" ]; then
  echo "错误：QUALITY_RUN_MARKER 指向的标记文件不存在：${MARKER}" >&2
  exit 64
fi

status=0
for name in "$@"; do
  found=0
  stale=0
  tests=0
  failed=0
  while IFS= read -r report; do
    found=$((found + 1))
    if [ -n "$MARKER" ] && [ ! "$report" -nt "$MARKER" ]; then
      stale=$((stale + 1))
      continue
    fi
    header="$(grep -m1 '<testsuite ' "$report" || true)"
    current_tests="$(printf '%s' "$header" | sed -n 's/.* tests="\([0-9]\{1,\}\)".*/\1/p')"
    current_failures="$(printf '%s' "$header" | sed -n 's/.* failures="\([0-9]\{1,\}\)".*/\1/p')"
    current_errors="$(printf '%s' "$header" | sed -n 's/.* errors="\([0-9]\{1,\}\)".*/\1/p')"
    tests=$((tests + ${current_tests:-0}))
    failed=$((failed + ${current_failures:-0} + ${current_errors:-0}))
  done < <(find "$ROOT/backend" -path '*/target/surefire-reports/*' -name "TEST-*.$name.xml" -type f 2>/dev/null)
  if [ "$found" -eq 0 ]; then
    echo "错误：${name} 没有产生 surefire 报告——它被改名、移动或根本没跑。" >&2
    echo "      选择器零匹配时 Maven 不报错，所以这里必须失败；请核对调用方选择器与本次校验清单是否仍然对得上" >&2
    echo "      （quality.sh 传具体类名，scenario-gate.sh 传 *ScenarioIT 约定）。" >&2
    status=1
    continue
  fi
  if [ "$tests" -eq 0 ]; then
    if [ "$stale" -eq "$found" ]; then
      echo "错误：${name} 只有上一次运行留下的陈旧报告（共 ${found} 份），本次运行没有执行它。" >&2
      echo "      选择器零匹配不会报错，因此这里必须失败；请核对 scripts/quality.sh 中的测试类名。" >&2
    else
      echo "错误：${name} 的报告存在但用例数为 0——视为未执行，不得当作通过。" >&2
    fi
    status=1
    continue
  fi
  if [ "$failed" -ne 0 ]; then
    echo "错误：${name} 报告中有 ${failed} 个失败或错误。" >&2
    status=1
    continue
  fi
  echo "    已核验执行：${name}（用例 ${tests}）"
done

exit "$status"
