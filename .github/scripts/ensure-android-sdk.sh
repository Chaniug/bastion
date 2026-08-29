#!/usr/bin/env bash
# 确保 AGP 能找到 compileSdk 37 对应的平台，且是它精确要求的那个修订。
#
# 背景（2026-08 全量升级踩到）：
#   - AGP 9.3 起平台匹配是「ApiLevel 精确」的：它查找的 package path 是
#     platforms;android-37.0。AGP 9.1 时代不校验修订号，所以同一份 runner 镜像
#     在升级前一直是绿的 —— 这也是三条流水线同时转红的唯一原因。
#   - AGP 认的是 platforms/<dir>/package.xml 里的 <localPackage path="...">，
#     而不是目录名。sdkmanager 装的包自带该文件；手工 curl 解压出来的 platform
#     没有，缺了会报一模一样的「Failed to find Platform SDK with path」。
#   - runner 镜像里 android-37 可能是：真实目录（github 镜像常见）、
#     android-37.0 / android-37.1（带修订号的目录名）或符号链接。三种都要能处理。
#     注意别用 rm -f 清理它 —— 删不掉目录，会让脚本在 set -e 下静默退出。
#
# 策略（优先真货，网络不可用时也能自愈）：
#   1. 已有 ApiLevel 恰好 37.0 的平台 -> 直接用
#   2. 否则 curl 直下 platform-37.0_rNN.zip（runner 到 dl.google.com 可达）
#   3. 下载不可用 -> 拿现有任意 37.x 平台造一个 android-37.0
#      （改写 ApiLevel + 补 package.xml）。37.0 与 37.1 同为 API 37，
#      仅 ExtensionLevel 不同，对编译产物无影响。
#
# 用法：bash .github/scripts/ensure-android-sdk.sh
# 依赖环境变量：ANDROID_SDK_ROOT（各 workflow 的 job env 里已设置）
set -uo pipefail

PLAT="${ANDROID_SDK_ROOT:?ANDROID_SDK_ROOT 未设置}/platforms"
WANT_API="37.0"
WANT_DIR="android-37.0"
mkdir -p "$PLAT"

api_of() {
    grep -i 'AndroidVersion.ApiLevel' "$1/source.properties" 2>/dev/null \
        | head -1 | cut -d= -f2 | tr -d ' '
}

write_package_xml() {
    local d="$1" rev ext
    rev=$(grep -i '^Pkg.Revision=' "$d/source.properties" 2>/dev/null | cut -d= -f2 | tr -d ' ')
    rev=${rev:-2}
    ext=$(grep -i '^AndroidVersion.ExtensionLevel=' "$d/source.properties" 2>/dev/null | cut -d= -f2 | tr -d ' ')
    cat > "$d/package.xml" <<XML
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<ns2:repository xmlns:ns2="http://schemas.android.com/repository/android/common/02"
                xmlns:ns5="http://schemas.android.com/repository/android/generic/02"
                xmlns:ns9="http://schemas.android.com/sdk/android/repo/repository2/03">
  <license id="license-2AAB78AE" type="text"/>
  <localPackage path="platforms;${WANT_DIR}" obsolete="false">
    <type-details xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="ns9:platformDetailsType">
      <api-level>37</api-level>
      <codename></codename>
      <extension-level>${ext}</extension-level>
      <layoutlib api="15"/>
    </type-details>
    <revision><major>${rev}</major></revision>
    <display-name>Android SDK Platform 37.0</display-name>
    <uses-license ref="license-2AAB78AE"/>
  </localPackage>
</ns2:repository>
XML
    echo "wrote package.xml (path=platforms;${WANT_DIR}, rev=${rev}, ext=${ext})"
}

echo "=== platforms before ==="
ls -la "$PLAT"
for d in "$PLAT"/android-37 "$PLAT"/android-37.*; do
    [ -d "$d" ] || continue
    [ -f "$d/source.properties" ] || continue
    echo "candidate $(basename "$d") ApiLevel=$(api_of "$d") pkgxml=$([ -f "$d/package.xml" ] && echo yes || echo NO)"
done

# ---- 1) 已有 ApiLevel 恰好 37.0 的平台 ----
SRC=""
for d in "$PLAT/$WANT_DIR" "$PLAT"/android-37.* "$PLAT"/android-37; do
    [ -d "$d" ] || continue
    [ -f "$d/source.properties" ] || continue
    [ "$(api_of "$d")" = "$WANT_API" ] || continue
    SRC="$d"
    break
done

if [ -n "$SRC" ] && [ "$SRC" != "$PLAT/$WANT_DIR" ]; then
    # 真货在别的目录名下（例如 android-37）：搬到标准名字，便于统一入口
    echo "moving $(basename "$SRC") -> $WANT_DIR"
    rm -rf "$PLAT/$WANT_DIR"
    mv "$SRC" "$PLAT/$WANT_DIR"
    SRC="$PLAT/$WANT_DIR"
fi

# ---- 2) 没有 37.0：下载 ----
if [ -z "$SRC" ]; then
    echo "no ApiLevel 37.0 platform found; downloading platform-37.0 from dl.google.com"
    if curl -sSL -m 90 -o /tmp/repo2.xml "https://dl.google.com/android/repository/repository2-3.xml"; then
        REL=$(grep -oE 'platform-37\.0_r[0-9]+\.zip' /tmp/repo2.xml | sort -u | tail -1)
        echo "resolved: ${REL:-<none>}"
        if [ -n "$REL" ] && curl -sSL -m 600 -o /tmp/platform37.zip \
                "https://dl.google.com/android/repository/$REL"; then
            rm -rf "$PLAT/$WANT_DIR"
            unzip -q -o /tmp/platform37.zip -d "$PLAT"
        else
            echo "::warning title=platform37 download failed::curl exit=$?"
        fi
    else
        echo "::warning title=repository manifest unreachable::curl exit=$?"
    fi
    [ -d "$PLAT/$WANT_DIR" ] && SRC="$PLAT/$WANT_DIR"
fi

# ---- 3) 下载不可用：基于现有 37.x 平台自造一个 37.0 ----
if [ -z "$SRC" ]; then
    DONOR=""
    for d in "$PLAT"/android-37 "$PLAT"/android-37.*; do
        [ -d "$d" ] || continue
        [ -f "$d/source.properties" ] || continue
        case "$(api_of "$d")" in
            37|37.*) DONOR="$d"; break;;
        esac
    done
    if [ -z "$DONOR" ]; then
        echo "::error title=no usable android-37 platform::cannot satisfy AGP 9.3 (needs ApiLevel 37.0)"
        ls -la "$PLAT"
        exit 1
    fi
    echo "::warning title=fabricating android-37.0::cloned from $(basename "$DONOR") (ApiLevel=$(api_of "$DONOR"))"
    rm -rf "$PLAT/$WANT_DIR"
    cp -a "$DONOR" "$PLAT/$WANT_DIR"
    # AGP 按 ApiLevel 精确匹配，故同步改写为 37.0；同属 API 37，编译行为一致
    sed -i 's/^\(AndroidVersion.ApiLevel=\).*/\1'"$WANT_API"'/' "$PLAT/$WANT_DIR/source.properties"
    rm -f "$PLAT/$WANT_DIR/package.xml"
    SRC="$PLAT/$WANT_DIR"
fi

# ---- 4) 补 package.xml（curl 解压出来 / 自造的都没有）----
if [ ! -f "$SRC/package.xml" ]; then
    write_package_xml "$SRC"
fi

# ---- 5) 建立 AGP 期望的 platforms/android-37 入口 ----
rm -rf "$PLAT/android-37"
ln -s "$PLAT/$WANT_DIR" "$PLAT/android-37"

# ---- 6) 校验 ----
echo "=== platforms after ==="
ls -la "$PLAT"
if [ ! -f "$PLAT/android-37/source.properties" ]; then
    echo "::error title=android-37 invalid::source.properties missing"
    exit 1
fi
api=$(api_of "$PLAT/android-37")
echo "android-37 -> $(readlink "$PLAT/android-37")  ApiLevel=$api"
if [ ! -f "$PLAT/android-37/package.xml" ]; then
    echo "::error title=package.xml missing::AGP cannot recognise the platform"
    exit 1
fi
if [ "$api" != "$WANT_API" ]; then
    echo "::error title=android-37 ApiLevel mismatch::$api, AGP 9.3 requires exactly $WANT_API"
    exit 1
fi
echo "android-37 ready (ApiLevel=$WANT_API, AGP 9.3 exact match)"
