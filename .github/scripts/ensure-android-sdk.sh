#!/usr/bin/env bash
# 确保 AGP 能找到 compileSdk 37 对应的平台目录，且是它精确要求的那个修订。
#
# 背景（2026-08 全量升级时踩到）：
#   - GitHub runner 镜像把 android-37 平台装成带修订号的目录名：
#     platforms/android-37.0、platforms/android-37.1（sdkmanager 12.0 的命名）。
#   - AGP 9.3 起平台匹配是「ApiLevel 精确」的：它查找的 package path 是
#     platforms;android-37.0。镜像里若只存在 android-37.1（或链接到 37.1），
#     构建会直接失败：
#         Failed to find Platform SDK with path: platforms;android-37.0
#     AGP 9.1 时代不校验修订号，所以同一份 runner 镜像在升级前是绿的。
#   - 更隐蔽的一点：手工 curl 解压出来的 platform 目录没有 package.xml，
#     而 AGP 靠 package.xml 识别「已安装包」，缺了会报一模一样的错。
#     sdkmanager 装的包自带该文件，所以只有 curl 兜底路径需要补。
#
# 用法：bash .github/scripts/ensure-android-sdk.sh
# 依赖环境变量：ANDROID_SDK_ROOT（各 workflow 的 job env 里已设置）
set -euo pipefail

PLAT="${ANDROID_SDK_ROOT:?ANDROID_SDK_ROOT 未设置}/platforms"
WANT_API="37.0"      # AGP 9.3 精确匹配的 ApiLevel
WANT_DIR="android-37.0"

mkdir -p "$PLAT"

pick_platform() {
    # 优先挑 ApiLevel 恰好为 37.0 的目录
    local d sp lvl
    for d in $(ls -d "$PLAT"/android-37.* 2>/dev/null | sort -V); do
        sp="$d/source.properties"
        [ -f "$sp" ] || continue
        lvl=$(grep -i 'AndroidVersion.ApiLevel' "$sp" | head -1 | cut -d= -f2 | tr -d ' ')
        echo "candidate $(basename "$d") ApiLevel=$lvl"
        if [ "$lvl" = "$WANT_API" ]; then
            echo "$d"
            return 0
        fi
    done
    return 1
}

# 1) 已经有 android-37 且 ApiLevel 正确 —— 直接用
if [ -d "$PLAT/android-37" ]; then
    api=$(grep -i 'AndroidVersion.ApiLevel' "$PLAT/android-37/source.properties" 2>/dev/null | head -1 | cut -d= -f2 | tr -d ' ' || true)
    if [ "$api" = "$WANT_API" ]; then
        echo "android-37 already present with ApiLevel=$WANT_API"
        exit 0
    fi
    echo "::warning title=android-37 wrong ApiLevel::$(basename "$(readlink -f "$PLAT/android-37")") ApiLevel=$api, expected $WANT_API"
    rm -f "$PLAT/android-37"
fi

# 2) 在已装的 android-37.* 里挑 ApiLevel 恰好 37.0 的，符号链接成 AGP 要的 platforms/android-37
if chosen=$(pick_platform); then
    ln -s "$chosen" "$PLAT/android-37"
    echo "symlinked $(basename "$chosen") -> android-37 (ApiLevel=$WANT_API)"
else
    # 3) 没有 37.0：curl 直下 platform-37.0 压缩包（runner 到 dl.google.com 可达）
    echo "::warning title=no android-37.0 preinstalled::downloading via curl"
    curl -sSL -m 60 -o /tmp/repo2.xml "https://dl.google.com/android/repository/repository2-3.xml"
    REL=$(grep -oE 'platform-37\.0_r[0-9]+\.zip' /tmp/repo2.xml | sort -u | tail -1)
    if [ -z "$REL" ]; then
        echo "::error title=cannot resolve platform-37.0::no platform-37.0_rNN.zip in repository2-3.xml"
        exit 1
    fi
    echo "platform zip: $REL"
    curl -sSL -m 300 -o /tmp/platform37.zip "https://dl.google.com/android/repository/$REL"
    unzip -q -o /tmp/platform37.zip -d "$PLAT"
    if [ -d "$PLAT/$WANT_DIR" ] && [ ! -d "$PLAT/android-37" ]; then
        ln -s "$PLAT/$WANT_DIR" "$PLAT/android-37"
        echo "symlinked $WANT_DIR -> android-37 (downloaded)"
    fi
fi

# 4) 手工解压的 platform 缺 package.xml —— 补上，否则 AGP 仍报找不到平台。
#    sdkmanager 安装的包自带该文件，故此段只在 curl 兜底路径生效。
for d in "$PLAT/$WANT_DIR"; do
    [ -d "$d" ] || continue
    [ -f "$d/package.xml" ] && continue
    rev=$(grep -i '^Pkg.Revision=' "$d/source.properties" | cut -d= -f2 | tr -d ' ')
    ext=$(grep -i '^AndroidVersion.ExtensionLevel=' "$d/source.properties" | cut -d= -f2 | tr -d ' ')
    printf '%s' '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><ns2:repository xmlns:ns2="http://schemas.android.com/repository/android/common/02" xmlns:ns5="http://schemas.android.com/repository/android/generic/02" xmlns:ns9="http://schemas.android.com/sdk/android/repo/repository2/03"><license id="license-2AAB78AE" type="text"/><localPackage path="platforms;android-37.0" obsolete="false"><type-details xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="ns9:platformDetailsType"><api-level>37</api-level><codename></codename><extension-level>'"$ext"'</extension-level><layoutlib><api>15</api></layoutlib></type-details><revision><major>'"$rev"'</major></revision><display-name>Android SDK Platform 37.0</display-name><uses-license ref="license-2AAB78AE"/></localPackage></ns2:repository>' > "$d/package.xml"
    echo "generated package.xml for $(basename "$d") (rev=$rev ext=$ext)"
done

# 5) 校验
if [ ! -f "$PLAT/android-37/source.properties" ]; then
    echo "::error title=android-37 invalid::source.properties missing"
    ls -la "$PLAT"
    exit 1
fi
api=$(grep -i 'AndroidVersion.ApiLevel' "$PLAT/android-37/source.properties" | head -1 | cut -d= -f2 | tr -d ' ')
echo "android-37 ApiLevel=$api"
case "$api" in
    37.0) echo "android-37 ready (AGP 9.3 精确匹配 $WANT_API)";;
    37*) echo "::error title=android-37 ApiLevel mismatch::$api, AGP 9.3 requires exactly $WANT_API"; exit 1;;
    *) echo "::error title=android-37 wrong ApiLevel::$api (expected $WANT_API)"; exit 1;;
esac
