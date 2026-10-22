#!/usr/bin/env bash
#
# Выпускает версию плагина: номер, сборка, тег, релиз.
#
# Существует потому, что номер раньше печатали руками при каждой сборке, а
# запасное значение в build.gradle.kts устаревало на следующий же день. Номер
# в jar, в теге и в релизе обязан быть одним и тем же: плагин сравнивает свой
# штамп с тем, что отдаёт сервис, и разошедшиеся номера означают сервер,
# который никогда не узнает, что устарел.
#
# Что считается патчем, минором и мажором - в VERSIONING.md. Главное правило
# оттуда: одна функция - один минор, даже если она делалась неделю.
#
#     ./tools/release.sh patch
#     ./tools/release.sh minor
#     ./tools/release.sh 2.0.0
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
PROPS="$HERE/gradle.properties"
FLAVOURS=("1.21.11" "1.16.5")

cd "$HERE"

if [ $# -lt 1 ]; then
    echo "использование: ./tools/release.sh patch|minor|major|<номер>" >&2
    exit 1
fi

current=$(sed -n 's/^pluginVersion=//p' "$PROPS")
[ -n "$current" ] || { echo "в gradle.properties нет pluginVersion" >&2; exit 1; }

case "$1" in
    patch|minor|major)
        next=$(python3 - "$current" "$1" <<'PY'
import sys
major, minor, patch = (int(part) for part in sys.argv[1].split("."))
step = sys.argv[2]
if step == "major":
    major, minor, patch = major + 1, 0, 0
elif step == "minor":
    minor, patch = minor + 1, 0
else:
    patch += 1
print(f"{major}.{minor}.{patch}")
PY
)
        ;;
    *)
        next="$1"
        echo "$next" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$' \
            || { echo "номер должен быть вида 1.2.3" >&2; exit 1; }
        ;;
esac

# Грязное дерево означает, что в релиз уедет не то, что лежит в теге.
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
    echo "есть незакоммиченные правки — сначала разберитесь с ними" >&2
    git status --short
    exit 1
fi

echo "==> $current -> $next"

sed -i "s/^pluginVersion=.*/pluginVersion=$next/" "$PROPS"

echo "==> проверяю"
./gradlew test --offline -q >/dev/null

echo "==> собираю"
rm -f build/libs/OculusAI-"$next"-*
for flavour in "${FLAVOURS[@]}"; do
    ./gradlew shadowJar --offline -q -PmcVersion="$flavour" >/dev/null
    jar="build/libs/OculusAI-$next-$flavour.jar"
    [ -f "$jar" ] || { echo "не собрался $jar" >&2; exit 1; }
    # Штамп внутри jar - то, что плагин сравнивает с ответом сервиса. Если он
    # разошёлся с номером релиза, обновление сломано, и лучше узнать здесь.
    stamped=$(unzip -p "$jar" build.properties | sed -n 's/^version=//p')
    [ "$stamped" = "$next" ] || { echo "в $jar штамп $stamped" >&2; exit 1; }
    echo "    $flavour  $(stat -c%s "$jar") б"
done

git add gradle.properties
git commit -q -m "Версия $next"
git tag "v$next"

echo "==> исходники"
git archive --format=zip --prefix="OculusAI-$next/" \
    -o "build/libs/OculusAI-$next-plugin-src.zip" HEAD

echo "==> публикую"
git push -q origin main
git push -q origin "v$next"

title="${RELEASE_TITLE:-OculusAI v$next}"
notes_arg=(--generate-notes)
if [ -n "${RELEASE_NOTES:-}" ]; then
    notes_arg=(--notes-file "$RELEASE_NOTES")
fi

gh release create "v$next" --title "$title" --latest "${notes_arg[@]}" \
    "build/libs/OculusAI-$next-1.21.11.jar" \
    "build/libs/OculusAI-$next-1.16.5.jar" \
    "build/libs/OculusAI-$next-plugin-src.zip"

echo "==> готово: v$next"
echo "    плагины узнают о нём после ./oaiapi/deploy.sh"
