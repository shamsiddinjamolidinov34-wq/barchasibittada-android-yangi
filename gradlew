#!/bin/sh
#
# gradlew - gradle-wrapper.jar talab qilmaydigan, o'zi yetarli Gradle wrapper skripti.
#
# Nima qiladi:
#  1. gradle/wrapper/gradle-wrapper.properties ichidagi distributionUrl ni o'qiydi.
#  2. Agar u Gradle 8.9 dan eski bo'lsa (masalan, 8.6), Android Gradle Plugin 8.7.3
#     talabi uchun avtomatik Gradle 8.9 ga almashtiradi.
#  3. Gradle ni bir marta yuklab oladi (GRADLE_USER_HOME/wrapper/dists), ochadi va ishga tushiradi.
#
set -eu

MIN_MAJOR=8
MIN_MINOR=9
FALLBACK_URL=${GRADLEW_FALLBACK_URL:-https://services.gradle.org/distributions/gradle-8.9-all.zip}

die() {
    echo "gradlew: $*" >&2
    exit 1
}

# Skript papkasi (symlink bo'lsa ham)
app_path=$0
while [ -h "$app_path" ]; do
    ls_out=$(ls -ld "$app_path")
    link=${ls_out#*' -> '}
    case $link in
        /*) app_path=$link ;;
        *) app_path=$(dirname "$app_path")/$link ;;
    esac
done
APP_HOME=$(cd "$(dirname "$app_path")" > /dev/null && pwd -P)
PROPS="$APP_HOME/gradle/wrapper/gradle-wrapper.properties"

# distributionUrl ni o'qish (properties faylidagi "\:" belgilarini tozalaymiz)
URL=""
if [ -f "$PROPS" ]; then
    URL=$(sed -n 's/^distributionUrl=//p' "$PROPS" | head -n 1 | tr -d '\r' | sed 's/\\//g')
fi

# Versiya: .../gradle-8.6-all.zip -> 8.6
VER=$(printf '%s\n' "$URL" | sed -n 's#.*/gradle-\([0-9][0-9]*\.[0-9][0-9]*\)[^/]*\.zip$#\1#p')

NEED_FALLBACK=1
if [ -n "$VER" ]; then
    MAJOR=${VER%%.*}
    MINOR=${VER#*.}
    if [ "$MAJOR" -gt "$MIN_MAJOR" ] || { [ "$MAJOR" -eq "$MIN_MAJOR" ] && [ "$MINOR" -ge "$MIN_MINOR" ]; }; then
        NEED_FALLBACK=0
    fi
fi
if [ "$NEED_FALLBACK" -eq 1 ]; then
    echo "gradlew: so'ralgan Gradle (${URL:-yo'q}) $MIN_MAJOR.$MIN_MINOR dan eski; $FALLBACK_URL ishlatiladi." >&2
    URL=$FALLBACK_URL
fi

GRADLE_USER_HOME=${GRADLE_USER_HOME:-$HOME/.gradle}
DIST_NAME=${URL##*/}
DIST_NAME=${DIST_NAME%.zip}
URL_HASH=$(printf '%s' "$URL" | cksum | cut -d' ' -f1)
DIST_DIR="$GRADLE_USER_HOME/wrapper/dists/$DIST_NAME/$URL_HASH"

find_gradle() {
    find "$DIST_DIR" -maxdepth 3 -type f -path '*/bin/gradle' 2>/dev/null | head -n 1
}

GRADLE_BIN=$(find_gradle)

if [ -z "$GRADLE_BIN" ]; then
    mkdir -p "$DIST_DIR"
    ZIP="$DIST_DIR/$DIST_NAME.zip"
    echo "gradlew: Gradle yuklab olinmoqda: $URL" >&2
    attempt=0
    while :; do
        attempt=$((attempt + 1))
        ok=0
        if command -v curl > /dev/null 2>&1; then
            if curl -fsSL --retry 3 -o "$ZIP.part" "$URL"; then ok=1; fi
        elif command -v wget > /dev/null 2>&1; then
            if wget -q -O "$ZIP.part" "$URL"; then ok=1; fi
        else
            die "curl yoki wget kerak"
        fi
        if [ "$ok" -eq 1 ]; then break; fi
        if [ "$attempt" -ge 3 ]; then die "Gradle yuklab olinmadi: $URL"; fi
        echo "gradlew: urinish $attempt muvaffaqiyatsiz, 5 soniyadan keyin qayta uriniladi..." >&2
        sleep 5
    done
    mv "$ZIP.part" "$ZIP"
    if command -v unzip > /dev/null 2>&1; then
        unzip -q -o "$ZIP" -d "$DIST_DIR"
    elif command -v python3 > /dev/null 2>&1; then
        python3 -m zipfile -e "$ZIP" "$DIST_DIR"
    else
        die "unzip yoki python3 kerak"
    fi
    rm -f "$ZIP"
    GRADLE_BIN=$(find_gradle)
    if [ -z "$GRADLE_BIN" ]; then die "yuklangan arxivda bin/gradle topilmadi"; fi
    chmod +x "$GRADLE_BIN"
fi

exec "$GRADLE_BIN" "$@"
