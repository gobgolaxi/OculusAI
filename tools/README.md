# Автообновление исходников

`git-autosync.sh` раз в минуту проверяет `origin/main` и подтягивает изменения,
если это чистый fast-forward. Незакоммиченные правки и собственные неотправленные
коммиты скрипт не трогает — в этих случаях он пишет в `.git/autosync.log` и
показывает уведомление.

## Установка (Linux, systemd)

```bash
cd ~/projects/OculusAI
ln -sf "$PWD/tools/systemd/oculusai-sync.service" ~/.config/systemd/user/
ln -sf "$PWD/tools/systemd/oculusai-sync.timer"   ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now oculusai-sync.timer
```

Проверить: `systemctl --user list-timers oculusai-sync.timer`,
лог: `tail -f .git/autosync.log`.

Юниты рассчитаны на путь `~/projects/OculusAI`; если репозиторий лежит в другом
месте, поправь `ExecStart` в `oculusai-sync.service`.

## Вручную

```bash
tools/git-autosync.sh
```
