# Автокоммит и автосинк текущей ветки на Windows.
#
# Раз в минуту:
#   1. коммитит все изменения (кроме ignored-файлов);
#   2. подтягивает upstream (fast-forward или rebase);
#   3. пушит локальные коммиты в upstream.
#
# Запуск вручную:
#     powershell -ExecutionPolicy Bypass -File tools\git-autosync.ps1
# Автозапуск - см. tools\README.md.

$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
$log = Join-Path $repo '.git\autosync.log'
$stateFile = Join-Path $repo '.git\autosync.state'

function Write-Log([string]$text) {
    $line = '{0} {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $text
    Add-Content -LiteralPath $log -Value $line -Encoding UTF8
}

function Get-State {
    if (Test-Path -LiteralPath $stateFile) {
        return (Get-Content -LiteralPath $stateFile -Raw).Trim()
    }
    return ''
}

function Set-State([string]$value) {
    Set-Content -LiteralPath $stateFile -Value $value -NoNewline -Encoding ASCII
}

function Show-Toast([string]$title, [string]$text) {
    try {
        Add-Type -AssemblyName System.Windows.Forms
        Add-Type -AssemblyName System.Drawing
        $icon = New-Object System.Windows.Forms.NotifyIcon
        $icon.Icon = [System.Drawing.SystemIcons]::Information
        $icon.Visible = $true
        $icon.ShowBalloonTip(5000, $title, $text, [System.Windows.Forms.ToolTipIcon]::Info)
        Start-Sleep -Seconds 5
        $icon.Dispose()
    } catch {
        # Уведомление - необязательная часть, лог важнее.
    }
}

function Fail([string]$text) {
    Write-Log $text
    if ((Get-State) -ne $text) {
        Show-Toast 'OculusAI: autosync' $text
        Set-State $text
    }
    exit 0
}

Set-Location -LiteralPath $repo
$env:GIT_TERMINAL_PROMPT = '0'

$branch = (& git rev-parse --abbrev-ref HEAD 2>$null).Trim()
if ($LASTEXITCODE -ne 0 -or -not $branch -or $branch -eq 'HEAD') {
    Fail 'skip: detached HEAD'
}

# 1. Автокоммит.
& git add -A
if ($LASTEXITCODE -ne 0) {
    Fail 'skip: git add failed'
}
$staged = & git diff --cached --name-only
if ($staged) {
    $message = 'Автосохранение: ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
    & git commit --quiet -m $message
    if ($LASTEXITCODE -ne 0) {
        Fail 'skip: git commit failed'
    }
    Write-Log "committed: $message"
    Set-State ''
}

$upstream = (& git rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>$null).Trim()
if ($LASTEXITCODE -ne 0 -or -not $upstream) {
    Fail "skip: no upstream for $branch"
}

$slash = $upstream.IndexOf('/')
if ($slash -lt 1) {
    Fail "skip: bad upstream $upstream"
}
$remote = $upstream.Substring(0, $slash)
$remoteBranch = $upstream.Substring($slash + 1)

# 2. Синхронизация с upstream.
& git fetch --quiet $remote
if ($LASTEXITCODE -ne 0) {
    Fail 'skip: git fetch failed'
}

$remoteRef = "$remote/$remoteBranch"
$head = (& git rev-parse HEAD).Trim()
$remoteHead = (& git rev-parse $remoteRef 2>$null).Trim()
if ($LASTEXITCODE -ne 0 -or -not $remoteHead) {
    $remoteHead = ''
}

if ($remoteHead -and $head -ne $remoteHead) {
    & git merge-base --is-ancestor $remoteRef HEAD
    if ($LASTEXITCODE -ne 0) {
        & git merge-base --is-ancestor HEAD $remoteRef
        if ($LASTEXITCODE -eq 0) {
            $old = (& git rev-parse --short HEAD).Trim()
            & git merge --ff-only --quiet $remoteRef
            if ($LASTEXITCODE -ne 0) {
                Fail 'skip: fast-forward failed'
            }
            $new = (& git rev-parse --short HEAD).Trim()
            Write-Log "updated $old -> $new"
        } else {
            & git rebase --quiet $remoteRef
            if ($LASTEXITCODE -ne 0) {
                & git rebase --abort 2>$null
                Fail 'skip: rebase failed, resolve manually'
            }
            Write-Log "rebased onto $remoteRef"
        }
    }
}

# 3. Автопуш.
$head = (& git rev-parse HEAD).Trim()
$remoteHead = (& git rev-parse $remoteRef 2>$null).Trim()
if (-not $remoteHead -or $head -ne $remoteHead) {
    & git push --quiet $remote "HEAD:$remoteBranch"
    if ($LASTEXITCODE -ne 0) {
        Fail 'skip: git push failed'
    }
    $short = (& git rev-parse --short HEAD).Trim()
    Write-Log "pushed $short -> $remote/$remoteBranch"
    Set-State ''
}
