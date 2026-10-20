#!/usr/bin/env bash
# Подтягивает изменения из origin/main, когда это безопасно.
#
# Логика намеренно осторожная: скрипт никогда не трогает незакоммиченные
# правки и не делает merge/rebase за вас. Он обновляет дерево только если
# это чистый fast-forward, то есть у вас нет своих неотправленных коммитов.
#
# Запускается таймером systemd (см. tools/systemd/), но можно и вручную:
#     tools/git-autosync.sh

set -u

branch="main"
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
log="$repo/.git/autosync.log"
state="$repo/.git/autosync.state"

cd "$repo" || exit 1

mkdir -p "$(dirname "$log")"
say() { printf '%s %s\n' "$(date '+%F %T')" "$*" >>"$log"; }
notify() { notify-send -a OculusAI "$1" "$2" >/dev/null 2>&1 || true; }

# Только один экземпляр за раз.
exec 9>"$repo/.git/autosync.lock"
flock -n 9 || exit 0

# Не трогаем грязное дерево: чужие правки дороже автоматизации.
if ! git diff --quiet || ! git diff --cached --quiet; then
    if [ "$(cat "$state" 2>/dev/null)" != "dirty" ]; then
        say "skip: есть незакоммиченные изменения"
        notify "OculusAI: автообновление на паузе" "Есть незакоммиченные правки"
        printf 'dirty' >"$state"
    fi
    exit 0
fi

# Нет сети — просто ждём следующего запуска, без уведомлений.
if ! GIT_TERMINAL_PROMPT=0 git fetch --quiet origin "$branch"; then
    say "skip: не удалось получить origin/$branch"
    exit 0
fi

upstream="origin/$branch"
head="$(git rev-parse HEAD)"
up="$(git rev-parse "$upstream")"

# Уже всё есть.
if [ "$head" = "$up" ]; then
    exit 0
fi

# Мы впереди: свои коммиты ещё не отправлены.
if git merge-base --is-ancestor "$up" HEAD; then
    if [ "$(cat "$state" 2>/dev/null)" != "ahead:$head" ]; then
        say "skip: есть неотправленные коммиты, сначала git push"
        notify "OculusAI: есть свои коммиты" "Запушь их, иначе автообновление стоит"
        printf 'ahead:%s' "$head" >"$state"
    fi
    exit 0
fi

# История разошлась: нужен ручной merge/rebase.
if ! git merge-base --is-ancestor HEAD "$up"; then
    if [ "$(cat "$state" 2>/dev/null)" != "diverged:$head:$up" ]; then
        say "skip: ветки разошлись ($head vs $up) — нужен ручной git pull"
        notify "OculusAI: ветки разошлись" "Сделай git pull вручную"
        printf 'diverged:%s:%s' "$head" "$up" >"$state"
    fi
    exit 0
fi

# Чистый fast-forward — можно обновляться.
old="$(git rev-parse --short HEAD)"
if git merge --ff-only --quiet "$upstream"; then
    new="$(git rev-parse --short HEAD)"
    say "обновлено $old -> $new"
    notify "OculusAI обновлён" "$old -> $new"
    printf 'synced:%s' "$new" >"$state"
else
    say "skip: fast-forward не удался"
fi
