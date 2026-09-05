#!/usr/bin/env bash
# localcheck.sh —— 本地轻量编译检查
#
# 用途: 改完 Kotlin/Java 后先在本地把编译跑绿再推送，避免用 CI 轮次试错。
#       GitHub Actions 只负责构建完整安装包 (assembleDebug / assembleRelease)。
#
# 用法:
#   ./scripts/localcheck.sh                     # 默认编译 :app:compileDebugKotlin
#   ./scripts/localcheck.sh :app:compileDebugKotlin :app:compileDebugUnitTestKotlin
#   ./scripts/localcheck.sh :app:lintDebug      # 任何任务都可以透传
#
# 环境要求 (一次性搭建, 详见 docs/local-dev-编译环境搭建.md):
#   - JDK 17+ (项目 sourceCompatibility/jvmTarget = 17)
#   - Gradle 9.5.1 (默认探测 /opt/gradle/gradle-9.5.1, 或 export GRADLE_BIN=...)
#   - Android SDK (local.properties 的 sdk.dir; AGP 9 需要 package.xml 元数据)
#
set -uo pipefail

# ---- 1) 定位 Gradle -------------------------------------------------------
GRADLE_BIN="${GRADLE_BIN:-}"
if [[ -z "$GRADLE_BIN" ]]; then
  for c in /opt/gradle/gradle-9.5.1/bin/gradle gradle; do
    if command -v "$c" >/dev/null 2>&1; then GRADLE_BIN="$c"; break; fi
  done
fi
if [[ -z "$GRADLE_BIN" ]]; then
  echo "❌ 未找到 gradle。安装方法 (腾讯镜像):"
  echo "   curl -L -o /tmp/gradle.zip https://mirrors.cloud.tencent.com/gradle/gradle-9.5.1-bin.zip"
  echo "   unzip /tmp/gradle.zip -d /opt/gradle"
  echo "   或: export GRADLE_BIN=/path/to/gradle"
  exit 1
fi

# ---- 2) 定位项目根 (脚本位于 <repo>/scripts/, Gradle 根在 <repo>/Bastion/) -
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE_ROOT="$ROOT/Bastion"
[[ -f "$GRADLE_ROOT/settings.gradle" ]] || GRADLE_ROOT="$ROOT"
cd "$GRADLE_ROOT" || exit 1

# ---- 3) SDK 自检 -----------------------------------------------------------
SDK_DIR=$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null)
if [[ -z "$SDK_DIR" || ! -d "$SDK_DIR" ]]; then
  echo "❌ local.properties 缺少有效的 sdk.dir (应为 local.properties: sdk.dir=/path/to/android-sdk)"
  exit 1
fi
if [[ ! -d "$SDK_DIR/platforms" ]]; then
  echo "❌ $SDK_DIR/platforms 不存在, SDK 不完整 (搭建方法见 docs/local-dev-编译环境搭建.md)"
  exit 1
fi

# ---- 4) 任务列表 -----------------------------------------------------------
if [[ $# -gt 0 ]]; then
  TASKS=("$@")
else
  TASKS=(":app:compileDebugKotlin")
fi

echo "== Gradle : $GRADLE_BIN"
echo "== SDK    : $SDK_DIR"
echo "== 任务   : ${TASKS[*]}"
echo

# ---- 5) 首选 configuration cache; 失败时停 daemon + 关配置缓存重试一次 -----
# (SDK 元数据修复后, 长驻 daemon 的 DefaultSdkLoader/AndroidTargetManager 缓存了旧结果,
#  必须先 `gradle --stop`; 详见 docs/local-dev-编译环境搭建.md 踩坑清单第 1 条)
if ! "$GRADLE_BIN" --console=plain "${TASKS[@]}"; then
  echo
  echo "⚠️  首次尝试失败, 停止 daemon + 关闭 configuration cache 后重试一次 (SDK 状态可能刚变化) ..."
  "$GRADLE_BIN" --stop >/dev/null 2>&1
  if "$GRADLE_BIN" --console=plain --no-configuration-cache "${TASKS[@]}"; then
    exit 0
  fi
  echo
  echo "❌ 编译失败。修复后再推送; 完整报错见上方输出。"
  exit 1
fi

echo
echo "✅ 本地编译通过, 可以提交推送 (CI 负责完整安装包)。"
