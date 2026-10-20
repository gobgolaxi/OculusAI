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

## Windows

`git-autosync.ps1` делает то же самое: раз в минуту `git fetch` и
fast-forward, если дерево чистое и нет своих неотправленных коммитов.
Вместо `notify-send` показывает всплывающее уведомление в трее.

Нужен установленный Git for Windows (чтобы `git` был в `PATH`) и склонированный
репозиторий. Дальше — PowerShell:

```powershell
cd C:\путь\к\OculusAI

# проверить вручную
powershell -ExecutionPolicy Bypass -File .\tools\git-autosync.ps1

# поставить в планировщик задач (раз в минуту, без окна)
$script = Join-Path $PWD 'tools\git-autosync.ps1'
$action = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument ('-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "{0}"' -f $script)
$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) `
    -RepetitionInterval (New-TimeSpan -Minutes 1)
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -StartWhenAvailable
Register-ScheduledTask -TaskName 'OculusAI autosync' -Action $action `
    -Trigger $trigger -Settings $settings -Description 'Подтягивать origin/main'
```

Если `Register-ScheduledTask` ругается на доступ — запусти PowerShell от имени
администратора. Проверить и удалить:

```powershell
Get-ScheduledTask -TaskName 'OculusAI autosync'
Unregister-ScheduledTask -TaskName 'OculusAI autosync' -Confirm:$false
```

Лог тот же: `.git\autosync.log`.

Без планировщика можно положить ярлык на `tools\autosync.cmd` (см. ниже) в
`shell:startup`, но тогда при входе мелькнёт окно консоли.

### autosync.cmd

Обёртка для запуска из ярлыка или старой Windows:

```bat
@echo off
powershell.exe -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "%~dp0git-autosync.ps1"
```

