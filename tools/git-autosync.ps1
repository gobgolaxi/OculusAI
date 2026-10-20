# Подтягивает origin/main на Windows, когда это безопасно.
#
# Повторяет логику git-autosync.sh: не трогает незакоммиченные правки и
# собственные неотправленные коммиты, обновляет только fast-forward.
#
# Запуск вручную:
#     powershell -ExecutionPolicy Bypass -File tools\git-autosync.ps1
# Автозапуск - см. tools\README.md.

$ErrorActionPreference = 'Stop'

$branch = 'main'
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

Set-Location -LiteralPath $repo
$env:GIT_TERMINAL_PROMPT = '0'

# Грязное дерево не трогаем. Неотслеживаемые файлы (архивы, сборки) не мешают.
$dirty = & git status --porcelain --untracked-files=no 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Log 'skip: git status failed'
    exit 0
}
if ($dirty) {
    if ((Get-State) -ne 'dirty') {
        Write-Log 'skip: uncommitted changes'
        Show-Toast 'OculusAI: autosync paused' 'There are uncommitted changes'
        Set-State 'dirty'
    }
    exit 0
}

& git fetch --quiet origin $branch
if ($LASTEXITCODE -ne 0) {
    Write-Log 'skip: fetch failed'
    exit 0
}

$head = (& git rev-parse HEAD).Trim()
$upstream = (& git rev-parse "origin/$branch").Trim()

if ($head -eq $upstream) {
    exit 0
}

# Мы впереди: свои коммиты ещё не отправлены.
& git merge-base --is-ancestor "origin/$branch" HEAD
if ($LASTEXITCODE -eq 0) {
    $state = "ahead:$head"
    if ((Get-State) -ne $state) {
        Write-Log 'skip: unpushed commits, push first'
        Show-Toast 'OculusAI: unpushed commits' 'Push them or autosync stays paused'
        Set-State $state
    }
    exit 0
}

# История разошлась: нужен ручной merge/rebase.
& git merge-base --is-ancestor HEAD "origin/$branch"
if ($LASTEXITCODE -ne 0) {
    $state = "diverged:$head:$upstream"
    if ((Get-State) -ne $state) {
        Write-Log 'skip: branches diverged, pull manually'
        Show-Toast 'OculusAI: branches diverged' 'Run git pull manually'
        Set-State $state
    }
    exit 0
}

# Чистый fast-forward.
$old = (& git rev-parse --short HEAD).Trim()
& git merge --ff-only --quiet "origin/$branch"
if ($LASTEXITCODE -eq 0) {
    $new = (& git rev-parse --short HEAD).Trim()
    Write-Log "updated $old -> $new"
    Show-Toast 'OculusAI updated' "$old -> $new"
    Set-State "synced:$new"
} else {
    Write-Log 'skip: fast-forward failed'
}
