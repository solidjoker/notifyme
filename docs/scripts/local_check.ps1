# notifyme 本地全量自检（Windows / PowerShell 5.1+ 均可）
# 一次跑完 AGENTS.md 第 1 节的验收清单，任何一步失败退出码为 1。
# 七步：server-tests / python-syntax / privacy-guard / version-check /
#       android-test-assemble-lint / lint-summary（含基线漂移）/ static-check（ktlint --strict）。
# 用法：
#   powershell -ExecutionPolicy Bypass -File docs/scripts/local_check.ps1
#   powershell -ExecutionPolicy Bypass -File docs/scripts/local_check.ps1 -SkipAndroid
#   powershell -ExecutionPolicy Bypass -File docs/scripts/local_check.ps1 -SkipServer

[CmdletBinding()]
param(
    [switch]$SkipAndroid,
    [switch]$SkipServer,
    [string]$Root
)

$ErrorActionPreference = 'Continue'

if (-not $Root) {
    $Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
}
$Root = (Resolve-Path $Root).Path
Set-Location -LiteralPath $Root

$LogDir = Join-Path $env:TEMP ('notifyme-local-check-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

$Results = New-Object System.Collections.ArrayList

function Invoke-Step {
    param(
        [string]$Name,
        [string]$File,
        [string[]]$StepArgs
    )
    Write-Host ''
    Write-Host ('=== [' + $Name + '] ' + $File + ' ' + ($StepArgs -join ' ')) -ForegroundColor Cyan
    $out = & $File @StepArgs 2>&1
    $code = $LASTEXITCODE
    $logPath = Join-Path $LogDir ((($Name -replace '[^A-Za-z0-9._-]', '_')) + '.log')
    ($out | Out-String) | Out-File -FilePath $logPath -Encoding UTF8
    $tail = @($out | Select-Object -Last 20)
    foreach ($line in $tail) { Write-Host ('    ' + $line) }
    $ok = ($code -eq 0)
    $script:Results.Add([pscustomobject]@{ Step = $Name; Exit = $code; Ok = $ok; Log = $logPath }) | Out-Null
    if (-not $ok) {
        Write-Host ('    [FAIL] 退出码 ' + $code + '，完整日志：' + $logPath) -ForegroundColor Red
    }
    return $ok
}

function Get-Python {
    foreach ($cand in @('python', 'python3', 'py')) {
        $cmd = Get-Command $cand -ErrorAction SilentlyContinue
        if ($cmd) { return $cmd.Source }
    }
    return $null
}

$python = Get-Python
if (-not $python) {
    Write-Host '找不到 python 解释器，先装 Python 3 并加入 PATH' -ForegroundColor Red
    exit 1
}

if (-not $SkipServer) {
    Invoke-Step -Name 'server-tests'   -File $python -StepArgs @('-m', 'pytest', 'server/', '-q') | Out-Null
    Invoke-Step -Name 'python-syntax'  -File $python -StepArgs @('-m', 'compileall', '-q', 'server') | Out-Null
    Invoke-Step -Name 'privacy-guard'  -File $python -StepArgs @('docs/scripts/privacy_guard.py') | Out-Null
    Invoke-Step -Name 'version-check'  -File $python -StepArgs @('docs/scripts/version_check.py') | Out-Null
}

if (-not $SkipAndroid) {
    if (-not $env:JAVA_HOME) {
        $jbr = Join-Path $env:ProgramFiles 'Android/Android Studio/jbr'
        if (Test-Path -LiteralPath $jbr) { $env:JAVA_HOME = $jbr }
    }
    if (-not $env:ANDROID_HOME -and $env:LOCALAPPDATA) {
        $sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
        if (Test-Path -LiteralPath $sdk) { $env:ANDROID_HOME = $sdk }
    }
    $gradlew = Join-Path $Root 'gradlew.bat'
    if (-not (Test-Path -LiteralPath $gradlew)) {
        Write-Host '找不到 gradlew.bat，仓库根目录不对？' -ForegroundColor Red
        exit 1
    }
    Invoke-Step -Name 'android-test-assemble-lint' -File $gradlew -StepArgs @(
        'testOpenDebugUnitTest', 'assembleOpenDebug', 'assembleBetaDebug', 'lintOpenDebug', '--console=plain'
    ) | Out-Null

    $lintXml = Join-Path $Root 'app/build/reports/lint-results-openDebug.xml'
    if (Test-Path -LiteralPath $lintXml) {
        $content = Get-Content -LiteralPath $lintXml -Raw -Encoding UTF8
        $issues = ([regex]::Matches($content, '(?m)^\s*<issue\s*$')).Count
        $errors = ([regex]::Matches($content, 'severity="Error"')).Count

        # lint.xml 的基线口径（app/lint.xml 只有注释、刻意不 ignore 任何东西）：
        # 报告里的 issue id 集合必须「恰好等于」下面这三条。多出一条就本地失败 ——
        # 这样新增问题一定会被人看到，而不是被 ignore 掉后悄悄消失。
        $baseline = @('ChromeOsAbiSupport', 'ObsoleteLintCustomCheck', 'OldTargetApi')
        $ids = @([regex]::Matches($content, 'id="([A-Za-z0-9_]+)"') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
        $extra = @($ids | Where-Object { $baseline -notcontains $_ })
        $missing = @($baseline | Where-Object { $ids -notcontains $_ })
        $drift = @($extra + $missing).Count
        Write-Host ''
        Write-Host ('=== [lint-summary] issues=' + $issues + ' errors=' + $errors + ' baseline-drift=' + $drift) -ForegroundColor Cyan
        if ($extra.Count -gt 0) {
            Write-Host ('    出现基线外的新 issue：' + ($extra -join ', ')) -ForegroundColor Yellow
            Write-Host '    要么修掉它，要么（确认无害后）把 id 加进 docs/scripts/local_check.ps1 与 app/lint.xml 的基线清单。' -ForegroundColor Yellow
        }
        if ($missing.Count -gt 0) {
            Write-Host ('    基线里的 issue 不见了：' + ($missing -join ', ')) -ForegroundColor Yellow
            Write-Host '    说明规则集或 SDK 版本变了，同步更新基线清单。' -ForegroundColor Yellow
        }
        $script:Results.Add([pscustomobject]@{ Step = 'lint-summary'; Exit = ($errors + $drift); Ok = (($errors -eq 0) -and ($drift -eq 0)); Log = $lintXml }) | Out-Null
    }

    # 第 7 步：Kotlin 静态检查（ktlint，规则集在 .editorconfig 里收窄到 5 条）。
    # 本地用 --strict（有命中就算失败），CI 用默认 --report（只报警不阻断）。
    # jar 缓存到 ~/.notifyme-tools/ktlint/1.8.0/，可用 NOTIFYME_KTLINT_JAR 直接指定。
    Invoke-Step -Name 'static-check' -File $python -StepArgs @('docs/scripts/static_check.py', '--strict') | Out-Null
}

Write-Host ''
Write-Host '================ 本地自检汇总 ================' -ForegroundColor White
foreach ($r in $Results) {
    $flag = if ($r.Ok) { 'PASS' } else { 'FAIL' }
    $color = if ($r.Ok) { 'Green' } else { 'Red' }
    Write-Host ('  {0,-28} {1}  (exit {2})' -f $r.Step, $flag, $r.Exit) -ForegroundColor $color
}
Write-Host ('  日志目录：' + $LogDir)
$failed = @($Results | Where-Object { -not $_.Ok })
if ($failed.Count -gt 0) {
    Write-Host ('  有 ' + $failed.Count + ' 步失败，别开 PR，先修。') -ForegroundColor Red
    exit 1
}
Write-Host '  全部通过，可以开 PR。' -ForegroundColor Green
exit 0
