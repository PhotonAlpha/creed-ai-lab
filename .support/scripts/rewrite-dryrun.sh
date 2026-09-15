#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
# OpenRewrite dry-run driver  --  Spring Boot 3 -> 4 and Jackson 2 -> 3
#
# Runs `rewrite-maven-plugin:dryRun`, which PRODUCES A PATCH AND CHANGES NOTHING.
# Every run writes to tmp/rewrite/<plan>-<timestamp>/ (git-ignored, repo root):
#
#   rewrite.patch   the proposed diff        -> review, then `git apply` if you want it
#   mvn.log         the full Maven output    -> which recipes actually matched
#   summary.txt     files touched, per module
#   datatables/     CSVs the recipes emitted (--datatables)
#
# Usage
#   .support/scripts/rewrite-dryrun.sh                     # list the plans
#   .support/scripts/rewrite-dryrun.sh boot4               # whole reactor
#   .support/scripts/rewrite-dryrun.sh jackson3 -- -pl creed-simple-metrics -am
#   .support/scripts/rewrite-dryrun.sh all                 # boot4 then jackson3, separate patches
#
# Options (before the plan name or after it, both work)
#   --per-module        one patch per Maven module instead of one for the reactor
#   --datatables        export the recipes' data tables (CSV) as well
#   --fail-on-changes   exit non-zero when the patch is non-empty (for CI gates)
#   --offline           pass -o to Maven (recipe jars must already be in the local repo)
#   --                  everything after this goes straight to Maven (e.g. -pl/-am/-T)
#
# Notes that will save you an afternoon
#   * `boot4` ALREADY CONTAINS `jackson3`: UpgradeSpringBoot_4_0 -> UpgradeSpringFramework_7_0
#     -> org.openrewrite.java.jackson.UpgradeJackson_2_3 (verified by running it). The
#     separate `jackson3` plan exists so you can review/apply the Jackson change on its
#     own, or migrate a module that is not going to Boot 4 yet. Running both means the
#     second one finds nothing left to do — that is expected, not a bug.
#   * Recipes chain downwards: the Boot 4 umbrella runs the whole 2.0 -> 3.5 ladder first,
#     so a dry run on an already-modern project still lists dozens of recipes. Read the
#     patch, not the recipe list.
#   * `failOnDryRunResults` and `reportOutputDirectory` have no `rewrite.` prefix
#     (unlike every other plugin property). --fail-on-changes handles that for you.
#   * Recipe jars are ~150 MB on the first run; they land in the repo-local Maven
#     repository configured for this machine, not in ~/.m2.
#   * Lombok + JDK 25: rewrite parses with its Java 21 parser. If a module fails to
#     parse, re-run it with `-- -Dmaven.compiler.release=21` — parsing only, the real
#     build is untouched.
#   * Licensing: rewrite-jackson is Apache-2.0, but rewrite-spring (6.x) ships under the
#     Moderne Source Available License — fine for using the recipes on your own code,
#     but it is no longer Apache-2.0, so check it before vendoring it into a product.
#   * This script never writes to the working tree; it verifies that afterwards.
# ============================================================================

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"   # every module here assumes the repo root is the working directory

# --- pinned versions (override with env vars) -------------------------------
REWRITE_PLUGIN_VERSION="${REWRITE_PLUGIN_VERSION:-6.46.1}"
REWRITE_SPRING_VERSION="${REWRITE_SPRING_VERSION:-6.37.1}"   # brings rewrite-migrate-java,
REWRITE_JACKSON_VERSION="${REWRITE_JACKSON_VERSION:-1.29.0}" # -hibernate, -testing-frameworks, … transitively
PLUGIN="org.openrewrite.maven:rewrite-maven-plugin:${REWRITE_PLUGIN_VERSION}"
RECIPE_ARTIFACTS="org.openrewrite.recipe:rewrite-spring:${REWRITE_SPRING_VERSION},org.openrewrite.recipe:rewrite-jackson:${REWRITE_JACKSON_VERSION}"

# --- plan -> recipe list ----------------------------------------------------
# (bash 3.2 on macOS has no associative arrays, hence the case statement)
plan_recipes() {
  case "$1" in
    # ---------- Spring Boot 3 -> 4 ----------
    boot4)              echo "org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0" ;;
    boot35)             echo "org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_5" ;;
    framework7)         echo "org.openrewrite.java.spring.framework.UpgradeSpringFramework_7_0" ;;
    security7)          echo "org.openrewrite.java.spring.security7.UpgradeSpringSecurity_7_0" ;;
    cloud2025)          echo "org.openrewrite.java.spring.cloud2025.UpgradeSpringCloud_2025_1" ;;
    boot4-props)        echo "org.openrewrite.java.spring.boot4.SpringBootProperties_4_0" ;;
    boot4-starters)     echo "org.openrewrite.java.spring.boot4.MigrateToModularStarters,org.openrewrite.java.spring.boot4.RenameDeprecatedStartersManagedVersions" ;;
    boot4-tests)        echo "org.openrewrite.java.spring.boot4.ReplaceMockBeanAndSpyBean" ;;
    boot4-webserver)    echo "org.openrewrite.java.spring.boot4.RelocateWebServerClasses" ;;
    boot4-autoconfig)   echo "org.openrewrite.java.spring.boot4.MigrateAutoconfigurePackages" ;;
    boot4-jackson-bom)  echo "org.openrewrite.java.spring.boot4.MigrateJacksonBomProperty" ;;
    # ---------- Jackson 2 -> 3 ----------
    jackson3)           echo "org.openrewrite.java.jackson.UpgradeJackson_2_3" ;;
    jackson3-deps)      echo "org.openrewrite.java.jackson.UpgradeJackson_2_3_Dependencies" ;;
    jackson3-packages)  echo "org.openrewrite.java.jackson.UpgradeJackson_2_3_PackageChanges" ;;
    jackson3-types)     echo "org.openrewrite.java.jackson.UpgradeJackson_2_3_TypeChanges" ;;
    jackson3-methods)   echo "org.openrewrite.java.jackson.UpgradeJackson_2_3_MethodRenames" ;;
    # ---------- JDK ----------
    java21)             echo "org.openrewrite.java.migrate.UpgradeToJava21" ;;
    java25)             echo "org.openrewrite.java.migrate.UpgradeToJava25" ;;
    *)                  return 1 ;;
  esac
}

plan_description() {
  case "$1" in
    boot4)             echo "Boot 3.5 -> 4.0 全量伞配方（含 boot35 + framework7 + security7 + cloud2025.1 + 属性/starter/测试改名 + BOM/parent 升级）" ;;
    boot35)            echo "先升到 Boot 3.5 的最新小版本并清掉弃用 API（boot4 的第一步，单跑用于分批验证）" ;;
    framework7)        echo "只跑 Spring Framework 6.2 -> 7.0" ;;
    security7)         echo "只跑 Spring Security 6 -> 7" ;;
    cloud2025)         echo "只跑 Spring Cloud 2025.0 -> 2025.1（Boot 4 对应的 train）" ;;
    boot4-props)       echo "只改 application*.yml/properties 里改名的配置键" ;;
    boot4-starters)    echo "只改 starter：模块化 starter + 被改名的 starter（oauth2-* -> security-oauth2-*）" ;;
    boot4-tests)       echo "只改测试：@MockBean/@SpyBean -> @MockitoBean/@MockitoSpyBean" ;;
    boot4-webserver)   echo "只改被搬家的 web server 类（Tomcat/Jetty 定制点）" ;;
    boot4-autoconfig)  echo "只改 @AutoConfiguration 包/imports 文件位置" ;;
    boot4-jackson-bom) echo "只改 pom 里的 jackson-bom.version 属性（Boot 4 的 Jackson 3 BOM）" ;;
    jackson3)          echo "Jackson 2 -> 3 全量：包名 com.fasterxml.jackson -> tools.jackson、依赖、类/异常/方法改名、Builder 化配置" ;;
    jackson3-deps)     echo "只改依赖坐标（com.fasterxml.jackson.* -> tools.jackson.*）" ;;
    jackson3-packages) echo "只改 import 包名" ;;
    jackson3-types)    echo "只改类型/异常改名" ;;
    jackson3-methods)  echo "只改方法改名（writeObject->writePOJO、getCurrentValue->currentValue 等）" ;;
    java21)            echo "JDK -> 21 的语言/API 现代化（本仓库已是 21，通常只剩零散清理）" ;;
    java25)            echo "JDK -> 25 的语言/API 现代化（可选，和 Boot 4 无关）" ;;
    *)                 echo "" ;;
  esac
}

ALL_PLANS="boot4 boot35 framework7 security7 cloud2025 boot4-props boot4-starters boot4-tests boot4-webserver boot4-autoconfig boot4-jackson-bom jackson3 jackson3-deps jackson3-packages jackson3-types jackson3-methods java21 java25"

usage() {
  cat <<USAGE
用法: .support/scripts/rewrite-dryrun.sh <plan> [选项] [-- 额外的 mvn 参数]

可用 plan:
USAGE
  for p in $ALL_PLANS; do printf "  %-18s %s\n" "$p" "$(plan_description "$p")"; done
  cat <<'USAGE'
  all                串行跑 boot4 和 jackson3，各自出一份独立的 patch

选项:
  --per-module       每个 Maven 模块各出一份 patch（默认整个 reactor 一份）
  --datatables       同时导出配方的数据表（CSV）
  --fail-on-changes  patch 非空时以非 0 退出（CI 门禁用）
  --offline          Maven 离线模式（配方 jar 必须已在本地仓库）
  -- <mvn args>      其后的参数原样传给 Maven，例如  -- -pl creed-simple-metrics -am

产物: tmp/rewrite/<plan>-<时间戳>/{rewrite.patch,mvn.log,summary.txt[,datatables/]}
USAGE
}

# --- arg parsing ------------------------------------------------------------
PLAN=""; PER_MODULE=false; DATATABLES=false; FAIL_ON_CHANGES=false; OFFLINE=false
MVN_EXTRA=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --per-module)      PER_MODULE=true ;;
    --datatables)      DATATABLES=true ;;
    --fail-on-changes) FAIL_ON_CHANGES=true ;;
    --offline)         OFFLINE=true ;;
    -h|--help)         usage; exit 0 ;;
    --)                shift; MVN_EXTRA+=("$@"); break ;;
    -*)                echo "未知选项: $1" >&2; usage; exit 2 ;;
    *)                 if [[ -z "$PLAN" ]]; then PLAN="$1"; else echo "只能指定一个 plan（多余的: $1）" >&2; exit 2; fi ;;
  esac
  shift
done
[[ -z "$PLAN" ]] && { usage; exit 0; }

# --- one dry run ------------------------------------------------------------
run_plan() {
  local plan="$1" recipes outdir
  recipes="$(plan_recipes "$plan")" || { echo "未知 plan: $plan" >&2; usage; exit 2; }
  outdir="tmp/rewrite/${plan}-$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$outdir"

  echo "──────────────────────────────────────────────────────────────"
  echo " plan     : $plan — $(plan_description "$plan")"
  echo " recipes  : $recipes"
  echo " 产物     : $outdir"
  echo "──────────────────────────────────────────────────────────────"

  local before after args=()
  before="$(git status --porcelain)"

  args+=("-Drewrite.activeRecipes=${recipes}")
  args+=("-Drewrite.recipeArtifactCoordinates=${RECIPE_ARTIFACTS}")
  args+=("-Drewrite.failOnInvalidActiveRecipes=true")   # 配方名打错时立刻报错，而不是安静地产出空 patch
  $PER_MODULE      && args+=("-Drewrite.runPerSubmodule=true")
  $DATATABLES      && args+=("-Drewrite.exportDatatables=true")
  # 注意: 这两个参数没有 rewrite. 前缀 —— 插件里就是这么定义的
  $FAIL_ON_CHANGES && args+=("-DfailOnDryRunResults=true")
  $OFFLINE         && args+=("-o")

  set +e
  mvn "${PLUGIN}:dryRun" "${args[@]}" "${MVN_EXTRA[@]+"${MVN_EXTRA[@]}"}" 2>&1 | tee "$outdir/mvn.log"
  local status=${PIPESTATUS[0]}
  set -e

  # 收集 patch（reactor 模式在根模块，per-module / -pl 模式每个模块一份）
  local patches=0
  while IFS= read -r p; do
    local rel module
    rel="${p#./}"
    module="${rel%target/rewrite/rewrite.patch}"; module="${module%/}"
    if [[ -z "$module" ]]; then
      cp "$p" "$outdir/rewrite.patch"                              # 根（整个 reactor 一份）
    else
      cp "$p" "$outdir/$(echo "$module" | tr '/' '_').patch"       # 单个模块
    fi
    patches=$((patches + 1))
  done < <(find . -path ./tmp -prune -o -path '*/target/rewrite/rewrite.patch' -print 2>/dev/null)

  # patch 里的路径是仓库根相对的，所以多个模块的 patch 直接拼起来就能 git apply
  if [[ ! -f "$outdir/rewrite.patch" && $patches -gt 0 ]]; then
    cat "$outdir"/*.patch > "$outdir/rewrite.patch"
  fi

  if $DATATABLES; then
    mkdir -p "$outdir/datatables"
    find . -path ./tmp -prune -o -path '*/target/rewrite/datatables/*' -print 2>/dev/null \
      | while IFS= read -r f; do cp "$f" "$outdir/datatables/" 2>/dev/null || true; done
  fi

  # 摘要
  {
    echo "plan     : $plan"
    echo "recipes  : $recipes"
    echo "plugin   : $PLUGIN"
    echo "artifacts: $RECIPE_ARTIFACTS"
    echo "mvn exit : $status"
    echo "patches  : $patches"
    echo
    echo "== 会被改动的文件 =="
    cat "$outdir"/*.patch 2>/dev/null | grep -E '^\+\+\+ b/' | sed 's|^+++ b/||' | sort -u || true
    echo
    echo "== 实际命中的配方（来自 mvn.log；日志行带 [WARNING] 前缀）=="
    sed -n 's/^\[WARNING\][[:space:]]\{1,\}\(org\.openrewrite\.[A-Za-z0-9_.]*\).*/  \1/p' "$outdir/mvn.log" \
      | sort -u || true
  } > "$outdir/summary.txt"

  after="$(git status --porcelain)"
  if [[ "$before" != "$after" ]]; then
    echo "!! 警告: 工作区发生了变化 —— dryRun 不应该改动源码，请用 git diff 确认" >&2
  fi

  echo
  sed -n '1,40p' "$outdir/summary.txt"
  echo
  echo "完整 patch : $outdir/rewrite.patch"
  echo "要应用的话 : git apply $outdir/rewrite.patch      # 本脚本不会替你应用"
  return $status
}

if [[ "$PLAN" == "all" ]]; then
  run_plan boot4
  echo; echo "### boot4 完成。接着单独跑 jackson3 —— 它是 boot4 的子集（经 UpgradeSpringFramework_7_0"
  echo "### 引入），单独出一份 patch 只是为了方便分开 review / 分批应用。"; echo
  run_plan jackson3
else
  run_plan "$PLAN"
fi
