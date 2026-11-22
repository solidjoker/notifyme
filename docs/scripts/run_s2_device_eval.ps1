# notifyme · 真机端侧 S2 采集「一条命令」脚本（Windows / PowerShell 5.1+）
#
# 把 docs/评测-云端vs端侧S2.md §7.1 的三步手敲（push 样本 → 起采集 → pull 报告）
# 串成一条命令，并把**装包**这一步补进去：
#
#   装包（可选 --skip-install）→ push 样本（可选 --skip-push）→ 起采集 → 等 → pull 报告 → 打印结论摘要
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File docs/scripts/run_s2_device_eval.ps1 --help
#   powershell -ExecutionPolicy Bypass -File docs/scripts/run_s2_device_eval.ps1 --serial <设备序列号>
#   powershell -ExecutionPolicy Bypass -File docs/scripts/run_s2_device_eval.ps1 --serial <serial> --skip-install --skip-push --limit 5
#
# 退出码：0 = 成功并拿到报告；1 = 用法/环境错误（没装上、推不上去、Activity 不存在…）；
#         2 = 采集没跑完（只落了 .partial 中间结果）；3 = 设备上没产出任何报告。
#
# 两个踩过的坑（别改回去）：
#   ① 样本**不能**推到 /sdcard/Android/data/<pkg>/files/：那个目录由 adb（shell 用户）创建，
#      权限 0770 shell:ext_data_rw，App 进程（另一个 uid）读不到 —— Android 11+ 上实测
#      「Permission denied」，采集器只会报「样本不存在」。这里改走 App 的**内部目录**
#      （adb push 到 /data/local/tmp，再 run-as cp 进 files/），报告也从内部目录读回。
#   ② 采集期间 App 自己的周期分析会跟采集器**共用同一个 llama context**（LocalLlmEngines
#      是全局单例），两边互相清 KV，输出会走形。所以起采集前先 force-stop 掉 App。

$ErrorActionPreference = 'Continue'

# ---------------------------------------------------------------------------
# 参数解析（不写 param 块：这样 --name value / --name=value / -Name value 都能收）
# ---------------------------------------------------------------------------

function Show-Usage {
    $lines = @(
        '用法：',
        '  powershell -ExecutionPolicy Bypass -File docs/scripts/run_s2_device_eval.ps1 --serial <设备序列号> [选项]',
        '',
        '选项（-- 与 - 两种写法都认，值可写成 --name value 或 --name=value）：',
        '  --serial <S>          目标设备序列号（adb devices 里的那一列）。不填且只有一台设备时自动选。',
        '  --apk <路径>          要装的 debug APK（open flavor）。默认 app/build/outputs/apk/open/debug/notifyme-open.apk',
        '  --labels <路径>       标注样本 JSON。默认 app/src/test/resources/eval-labels.json',
        '  --out <路径>          报告拉到本地的位置。默认 docs/s2-usability-device.md',
        '  --limit <N>           采前 N 条样本（0 = 不限）。默认 20',
        '  --budget-ms <N>       整轮预算（毫秒）。默认 1500000（25min）；S1EvalBudgetCore 上限 60min',
        '  --timeout-ms <N>      单次调用上限（毫秒）。默认 120000（120s）；S1EvalBudgetCore 上限 10min',
        '  --skip-install        不装包（设备上已经有带 S2EvalActivity 的 debug 包时用）',
        '  --skip-push           不推样本（设备上已经有 eval-labels.json 时用）',
        '  --package <包名>      应用包名。默认 com.notifyme.android（beta flavor 用 com.notifyme.android.test）',
        '  --adb <路径>          adb 可执行文件。不填自动找 SDK 里的 platform-tools/adb.exe',
        '  --report-name <文件名> 设备上的报告文件名。默认 s2-usability.md',
        '  --wait-seconds <N>    等报告的最长秒数。默认 = 整轮预算 + 300s',
        '  --legacy-sdcard       用 /sdcard/Android/data/<包名>/files/ 存取样本与报告（老系统兜底，默认不用）',
        '  --help                打印本帮助',
        '',
        '退出码：0 成功 / 1 用法或环境错误 / 2 采集未完成（只有 .partial）/ 3 设备没产出报告'
    )
    foreach ($l in $lines) { Write-Host $l }
}

$Opt = @{
    serial        = ''
    apk           = ''
    labels        = ''
    out           = ''
    limit         = '20'
    budget_ms     = '1500000'
    timeout_ms    = '120000'
    package       = 'com.notifyme.android'
    adb           = ''
    report_name   = 's2-usability.md'
    wait_seconds  = '0'
    skip_install  = $false
    skip_push     = $false
    legacy_sdcard = $false
}

# 参数名归一：大小写不敏感，'-' 与 '_' 等价（内部统一用下划线，与 $Opt 的键一致）
function Normalize-Name([string]$n) {
    $n = $n.ToLowerInvariant().TrimStart('-')
    return $n.Replace('-', '_')
}

$switchNames = @('skip_install', 'skip_push', 'legacy_sdcard', 'help')

$argList = @($args)
$i = 0
while ($i -lt $argList.Count) {
    $a = [string]$argList[$i]
    if ($a -eq '--help' -or $a -eq '-h' -or $a -eq '-?' -or $a -eq '/?' -or $a -eq '/help') {
        Show-Usage
        exit 0
    }
    if (-not $a.StartsWith('-')) {
        Write-Host ('[ERROR] 位置参数不被接受：' + $a + '（本脚本只认 --name value 形式）') -ForegroundColor Red
        Show-Usage
        exit 1
    }
    $raw = $a -replace '^-+', ''
    $val = $null
    if ($raw.Contains('=')) {
        $parts = $raw.Split('=', 2)
        $raw = $parts[0]
        $val = $parts[1]
    }
    $name = Normalize-Name $raw
    if ($switchNames -contains $name) {
        if ($null -ne $val) {
            Write-Host ('[ERROR] --' + $name + ' 是开关，不接受值') -ForegroundColor Red
            exit 1
        }
        if ($name -eq 'help') { Show-Usage; exit 0 }
        $Opt[$name] = $true
        $i++
        continue
    }
    if (-not $Opt.ContainsKey($name)) {
        Write-Host ('[ERROR] 未知参数：--' + $raw) -ForegroundColor Red
        Show-Usage
        exit 1
    }
    if ($null -eq $val) {
        if (($i + 1) -ge $argList.Count) {
            Write-Host ('[ERROR] --' + $name + ' 缺值') -ForegroundColor Red
            exit 1
        }
        $val = [string]$argList[$i + 1]
        $i++
    }
    $Opt[$name] = $val
    $i++
}

# ---------------------------------------------------------------------------
# 小工具
# ---------------------------------------------------------------------------

function Write-Step([string]$m) { Write-Host ('==> ' + $m) -ForegroundColor Cyan }
function Write-Info([string]$m) { Write-Host ('    ' + $m) }
function Write-Warn2([string]$m) { Write-Host ('    [WARN] ' + $m) -ForegroundColor Yellow }
function Write-Err2([string]$m) { Write-Host ('[ERROR] ' + $m) -ForegroundColor Red }

function Add-Num([hashtable]$h, [string]$key) {
    $n = 0
    if (-not [int]::TryParse([string]$h[$key], [ref]$n)) {
        Write-Err2 ('--' + $key + ' 必须是整数，收到：' + $h[$key])
        exit 1
    }
    $h[$key] = [string]$n
}

Add-Num $Opt 'limit'
Add-Num $Opt 'budget_ms'
Add-Num $Opt 'timeout_ms'
Add-Num $Opt 'wait_seconds'

$limit = [int]$Opt['limit']
$budgetMs = [int]$Opt['budget_ms']
$timeoutMs = [int]$Opt['timeout_ms']
$waitSeconds = [int]$Opt['wait_seconds']

if ($limit -lt 0) { Write-Err2 '--limit 不能为负'; exit 1 }
if ($budgetMs -le 0) { Write-Err2 '--budget-ms 必须为正'; exit 1 }
if ($timeoutMs -le 0) { Write-Err2 '--timeout-ms 必须为正'; exit 1 }

# S1EvalBudgetCore 的硬夹取（app/src/main/java/com/notifyme/android/S1EvalBudgetCore.kt）：
# 整轮预算 ≤ 60min、单次上限 ≤ 10min。超了会被**静默夹到边界**，这里先提醒。
if ($budgetMs -gt 3600000) { Write-Warn2 '--budget-ms 超过 60min，S1EvalBudgetCore 会夹到 60min' }
if ($timeoutMs -gt 600000) { Write-Warn2 '--timeout-ms 超过 10min，S1EvalBudgetCore 会夹到 10min' }
if ($timeoutMs -gt $budgetMs) {
    Write-Warn2 '--timeout-ms 大于 --budget-ms，单次调用会被整轮预算截断'
}

# 仓库根目录：本脚本在 <repo>/docs/scripts/ 下
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$Root = (Resolve-Path -LiteralPath $Root).Path

if (-not $Opt['apk']) { $Opt['apk'] = Join-Path $Root 'app/build/outputs/apk/open/debug/notifyme-open.apk' }
if (-not $Opt['labels']) { $Opt['labels'] = Join-Path $Root 'app/src/test/resources/eval-labels.json' }
if (-not $Opt['out']) { $Opt['out'] = Join-Path $Root 'docs/s2-usability-device.md' }
if ($waitSeconds -le 0) {
    $waitSeconds = [int][math]::Ceiling($budgetMs / 1000.0) + 300
}

# ---------------------------------------------------------------------------
# 找 adb（逐个试，跑不起来的直接跳过）
#
# 为什么不能只认 PATH 上的 adb：本机 C:\mumu_adb\adb.exe 缺依赖 DLL，
# 直接执行会以 STATUS_DLL_NOT_FOUND（退出码 -1073741515）失败，
# 而它在 `adb devices` 里表现为「设备全没了」—— 很容易被误判成「设备没连」。
# 所以这里逐个候选跑 `adb version`，只有能正常打印版本号的才算数。
# ---------------------------------------------------------------------------

function Test-Adb([string]$path) {
    if (-not $path) { return $false }
    if (-not (Test-Path -LiteralPath $path)) { return $false }
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    try {
        $out = & $path version 2>&1
        if ($LASTEXITCODE -eq 0 -and (($out | Out-String) -match 'Android Debug Bridge')) { return $true }
    } catch {
        # 进程起不来（缺 DLL 之类）：当候选不合格
    } finally {
        $ErrorActionPreference = $prev
    }
    return $false
}

function Resolve-Adb {
    $cands = New-Object System.Collections.ArrayList
    if ($Opt['adb']) { [void]$cands.Add($Opt['adb']) }
    if ($env:ANDROID_HOME) { [void]$cands.Add((Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe')) }
    if ($env:LOCALAPPDATA) { [void]$cands.Add((Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe')) }
    [void]$cands.Add('C:\mumu_adb\adb.exe')
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($onPath) { [void]$cands.Add($onPath.Source) }
    foreach ($c in $cands) {
        if (Test-Adb $c) { return $c }
        if ($Opt['adb']) { Write-Err2 ('指定的 adb 不可用：' + $c); exit 1 }
    }
    return ''
}

Write-Step '准备环境'
$adb = Resolve-Adb
if (-not $adb) {
    Write-Err2 '找不到可用的 adb（候选都跑不起来）。用 --adb 指定 platform-tools/adb.exe 的完整路径。'
    exit 1
}
Write-Info ('adb：' + $adb)

if (-not (Test-Path -LiteralPath $Opt['labels'])) {
    Write-Err2 ('标注样本不存在：' + $Opt['labels'])
    exit 1
}

# 设备列表
$rawDevices = & $adb devices 2>&1
$devices = @()
foreach ($line in ($rawDevices | Out-String -Stream)) {
    if ($line -match '^(\S+)\s+device(\s|$)') { $devices += $Matches[1] }
}
if ($devices.Count -eq 0) {
    Write-Err2 'adb devices 里没有在线设备（device 状态）。插上设备 / 开 USB 调试，或先 adb connect。'
    Write-Info (($rawDevices | Out-String).Trim())
    exit 1
}
Write-Info ('在线设备：' + ($devices -join ', '))

if ($Opt['serial']) {
    # USB 链路偶尔会抖一下（设备短暂掉线再回来）。重试几次再判失败，
    # 别把「一瞬间没连上」当成「设备不在」。
    $ok = $false
    for ($try = 1; $try -le 6 -and -not $ok; $try++) {
        $rawDevices = & $adb devices 2>&1
        $devices = @()
        foreach ($line in ($rawDevices | Out-String -Stream)) {
            if ($line -match '^(\S+)\s+device(\s|$)') { $devices += $Matches[1] }
        }
        if ($devices -contains $Opt['serial']) {
            $ok = $true
        } else {
            Write-Warn2 ('设备 ' + $Opt['serial'] + ' 当前不在线（第 ' + $try + '/6 次），3 秒后重试……')
            Start-Sleep -Seconds 3
        }
    }
    if (-not $ok) {
        Write-Err2 ('设备 ' + $Opt['serial'] + ' 不在线（在线的是：' + ($devices -join ', ') + '）')
        exit 1
    }
} elseif ($devices.Count -eq 1) {
    $Opt['serial'] = $devices[0]
} else {
    Write-Err2 ('有多台设备，请用 --serial 指定一台（在线：' + ($devices -join ', ') + '）')
    exit 1
}
$serial = $Opt['serial']
Write-Info ('目标设备：' + $serial)

$pkg = $Opt['package']
$reportName = $Opt['report_name']

# ---------------------------------------------------------------------------
# 第 1 步：装包（可跳过）
# ---------------------------------------------------------------------------

if (-not $Opt['skip_install']) {
    Write-Step '第 1 步：安装 debug APK'
    if (-not (Test-Path -LiteralPath $Opt['apk'])) {
        Write-Err2 ('APK 不存在：' + $Opt['apk'] + '（先跑 .\gradlew.bat assembleOpenDebug）')
        exit 1
    }
    Write-Info ('APK：' + $Opt['apk'])
    $installOut = & $adb -s $serial install -r -d $Opt['apk'] 2>&1
    $installCode = $LASTEXITCODE
    Write-Info (($installOut | Out-String).Trim())
    if ($installCode -ne 0) {
        Write-Err2 ('安装失败（退出码 ' + $installCode + '）。常见原因：签名不一致（设备上是 beta/别的 key）→ 用 --skip-install。')
        exit 1
    }
    if ((($installOut | Out-String) -notmatch 'Success')) {
        Write-Err2 '安装没有返回 Success。'
        exit 1
    }
    Write-Info '提示：install -r 保留 App 数据（端侧模型不会被清掉）；卸载才会清。'
} else {
    Write-Step '第 1 步：安装（已用 --skip-install 跳过）'
}

# 采集入口只在 debug 源集里（app/src/debug/java/com/notifyme/android/S2EvalActivity.kt）。
#
# 注意：**别用 dumpsys package 查**。dumpsys 的 Activity Resolver Table 只列「带
# intent-filter 的 Activity」，S2EvalActivity 没有 filter（它是显式组件启动的），
# 靠 dumpsys 只会得到 false —— 误判成「装的包里没有采集入口」。
# 用 cmd package resolve-activity 查显式组件。
$resolved = (& $adb -s $serial shell ("cmd package resolve-activity --brief --components -n " +
    $pkg + '/.S2EvalActivity') 2>&1 | Out-String).Trim()
if ($resolved -notmatch 'S2EvalActivity') {
    Write-Err2 ('设备上的 ' + $pkg + ' 里没有 S2EvalActivity（resolve-activity 无结果：' + $resolved + '）—— 只有 debug 包才有采集入口。请用 --apk 指定 debug APK 并去掉 --skip-install。')
    exit 1
}
Write-Info ('采集入口就位：' + $resolved)

# ---------------------------------------------------------------------------
# 第 2 步：样本与报告放哪（App 内部目录 vs /sdcard）
#
# /sdcard/Android/data/<pkg>/files/ 这个目录是 adb（shell 用户）建的，权限 0770
# shell:ext_data_rw —— App 进程（另一个 uid）读不到。Android 11+ 上实测
# 「Permission denied」，采集器只会报「样本不存在」，很容易被误判成「采集器坏了」。
#
# 所以默认走 App 的**内部目录**（/data/user/0/<pkg>/files）：adb push 到
# /data/local/tmp，再 run-as cp 进 files/；报告也从内部目录读回。
# run-as 只对 debuggable 包可用 —— 本流程装的就是 debug 包，所以一定有。
# ---------------------------------------------------------------------------

$appDataDir = ''
$runAsOk = $false
if (-not $Opt['legacy_sdcard']) {
    $ra = (& $adb -s $serial shell ("run-as " + $pkg + " pwd") 2>&1 | Out-String).Trim()
    if ($ra -match '^/data/') {
        $runAsOk = $true
        $appDataDir = $ra
    } else {
        Write-Warn2 ('run-as 不可用（' + $ra + '）—— 回退到 /sdcard/Android/data/' + $pkg + '/files/。老系统可行，新系统上 App 可能读不到那个目录。')
    }
}

if ($runAsOk) {
    $workDir = $appDataDir + '/files'
    $remoteLabels = $workDir + '/eval-labels.json'
    $remoteReport = $workDir + '/' + $reportName
    $relReport = 'files/' + $reportName
} else {
    $workDir = '/sdcard/Android/data/' + $pkg + '/files'
    $remoteLabels = $workDir + '/eval-labels.json'
    $remoteReport = $workDir + '/' + $reportName
    $relReport = $null
}
$remotePartial = $remoteReport + '.partial'
Write-Info ('设备端工作目录：' + $workDir)

# 远端文件大小。
# 注意：**不能**写 `adb shell "wc -c < path"` —— 那个重定向是外层 shell（shell 用户）做的，
# 内部目录它读不到（会报 "can't open files/...: No such file or directory"）。
# 统一走 `ls -l` 解析第 5 个字段（大小）。
function Get-RemoteSize([string]$relPath, [string]$absPath) {
    $cmd = if ($relPath) { 'run-as ' + $pkg + ' ls -l ' + $relPath } else { 'ls -l ' + $absPath }
    $r = (& $adb -s $serial shell $cmd 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) { return '' }
    $m = [regex]::Match($r, '(?m)^\S+\s+\d+\s+\S+\s+\S+\s+(\d+)')
    if ($m.Success) { return $m.Groups[1].Value }
    return ''
}

# ---------------------------------------------------------------------------
# 第 3 步：push 样本（必须在装包之后）
# ---------------------------------------------------------------------------

if (-not $Opt['skip_push']) {
    Write-Step '第 3 步：推送标注样本'
    if ($runAsOk) {
        $tmpRemote = '/data/local/tmp/eval-labels.json'
        $pushOut = & $adb -s $serial push $Opt['labels'] $tmpRemote 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Err2 ('样本推送失败：' + (($pushOut | Out-String).Trim()))
            exit 1
        }
        $cpOut = (& $adb -s $serial shell ("run-as " + $pkg + " cp " + $tmpRemote + " files/eval-labels.json") 2>&1 | Out-String).Trim()
        if ($cpOut -match 'Permission denied|No such file|not debuggable') {
            Write-Err2 ('样本没能进 App 内部目录：' + $cpOut)
            exit 1
        }
        & $adb -s $serial shell "rm -f $tmpRemote" 2>&1 | Out-Null
        $remoteSize = Get-RemoteSize 'files/eval-labels.json' $remoteLabels
    } else {
        & $adb -s $serial shell "mkdir -p $workDir" 2>&1 | Out-Null
        $pushOut = & $adb -s $serial push $Opt['labels'] $remoteLabels 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Err2 ('样本推送失败：' + (($pushOut | Out-String).Trim()))
            exit 1
        }
        $remoteSize = Get-RemoteSize '' $remoteLabels
    }
    $localSize = (Get-Item -LiteralPath $Opt['labels']).Length
    Write-Info ('样本大小：本地 ' + $localSize + ' 字节 / 设备 ' + $remoteSize + ' 字节')
    if ($remoteSize -notmatch ([string]$localSize)) {
        Write-Err2 '设备上的样本大小与本地不一致 —— 采集结果不可信，先排查再跑。'
        exit 1
    }
} else {
    Write-Step '第 3 步：推送样本（已用 --skip-push 跳过）'
}

# ---------------------------------------------------------------------------
# 第 4 步：起采集
# ---------------------------------------------------------------------------

Write-Step '第 4 步：启动采集'
# 采集器与 App 的周期分析共用 LocalLlmEngines 的同一个 llama context（全局单例），
# 两边同时推理会互相清 KV、输出走形。先 force-stop 掉 App 再起采集。
& $adb -s $serial shell ("am force-stop " + $pkg) 2>&1 | Out-Null
Start-Sleep -Seconds 2
Write-Info '已 force-stop App（避免周期分析与采集器共用 llama context）'
Write-Info '提示：App 设置里的「周期分析」建议先关掉。'

Write-Info ('样本：' + $remoteLabels)
Write-Info ('报告：' + $remoteReport)
Write-Info ('limit=' + $limit + ' budget_ms=' + $budgetMs + ' timeout_ms=' + $timeoutMs)

# ★ 先删掉上一轮的报告（和 .partial）：
# 报告文件名固定，设备上还留着上一轮的结果 —— 不删的话，下面「等报告」会**立刻**
# 命中旧文件，把上一轮的数字当本轮的拉回来（实测踩过：拉回来的是 18:31 那轮的）。
if ($runAsOk) {
    & $adb -s $serial shell ("run-as " + $pkg + " rm -f files/" + $reportName + " files/" + $reportName + '.partial') 2>&1 | Out-Null
} else {
    & $adb -s $serial shell ("rm -f " + $remoteReport + ' ' + $remotePartial) 2>&1 | Out-Null
}
Write-Info '已清掉设备上的旧报告（避免拉到上一轮的结果）'

& $adb -s $serial logcat -c 2>&1 | Out-Null
# budget_ms / timeout_ms 必须用 **--el**（long extra），不能用 --ei。
# `am start --ei` 放的是 Integer，而 S2EvalActivity 用 getLongExtra 取；
# Android 14+ 的 BaseBundle 不再把 Integer 强转 Long，取不到就回默认值 ——
# 真机上表现为「预算永远是 25min / 单次上限永远是 120s」，用户改了也不生效。
# --ei limit 是 int extra，getIntExtra 取得到，不用改。
$startOut = (& $adb -s $serial shell ("am start -n " + $pkg + '/.S2EvalActivity' +
    ' --es dataset ' + $remoteLabels +
    ' --es out ' + $remoteReport +
    ' --ei limit ' + $limit +
    ' --el budget_ms ' + $budgetMs +
    ' --el timeout_ms ' + $timeoutMs) 2>&1 | Out-String)
Write-Info $startOut.Trim()
if ($startOut -match 'Error') {
    Write-Err2 'am start 报错（Activity 不存在 / 包没装 / 设备锁屏）。'
    exit 1
}

# ---------------------------------------------------------------------------
# 第 5 步：等报告
# ---------------------------------------------------------------------------

Write-Step ('第 5 步：等待报告（最多 ' + $waitSeconds + ' 秒）')

function Test-RemoteFile([string]$absPath, [string]$relPath) {
    if ($relPath) {
        $r = (& $adb -s $serial shell ("run-as " + $pkg + " ls " + $relPath) 2>&1 | Out-String)
        return ($LASTEXITCODE -eq 0 -and $r -match [regex]::Escape($relPath))
    }
    $r = (& $adb -s $serial shell ("if [ -f '" + $absPath + "' ]; then echo YES; else echo NO; fi") 2>&1 | Out-String).Trim()
    return ($r -match 'YES')
}

$deadline = (Get-Date).AddSeconds($waitSeconds)
$done = $false
$failed = $false
$logSeen = 0
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 15
    $log = (& $adb -s $serial logcat -d -s S2Eval 2>&1 | Out-String)
    # 只打**新增**的日志行（每轮都重打整段会把同一条刷几十遍）
    $logLines = @($log -split "`r?`n" | Where-Object { $_.Trim() -ne '' -and $_ -notmatch 'beginning of' })
    if ($logLines.Count -gt $logSeen) {
        for ($li = $logSeen; $li -lt $logLines.Count; $li++) {
            Write-Info ('[logcat] ' + $logLines[$li].Trim())
        }
        $logSeen = $logLines.Count
    }
    if ($log -match '采集失败') {
        $failed = $true
        break
    }
    if (Test-RemoteFile $remoteReport $relReport) {
        $done = $true
        break
    }
}

if ($failed) {
    Write-Err2 '采集线程抛异常（logcat 里出现「采集失败」）。上面的 logcat 是现场。'
    if (Test-RemoteFile $remotePartial $relReport) {
        $partialOut = [string]$Opt['out'] + '.partial'
        & $adb -s $serial pull $remotePartial $partialOut 2>&1 | Out-Null
        Write-Info ('中间结果已拉到：' + $partialOut)
    }
    exit 3
}

if (-not $done) {
    Write-Err2 ('等待超时（' + $waitSeconds + ' 秒）都没等到报告文件。')
    if (Test-RemoteFile $remotePartial $relReport) {
        $partialOut = [string]$Opt['out'] + '.partial'
        & $adb -s $serial pull $remotePartial $partialOut 2>&1 | Out-Null
        Write-Info ('只落了中间结果，已拉到：' + $partialOut + '（这不是最终报告）')
        exit 2
    }
    Write-Info '设备上连 .partial 都没有 —— 采集可能根本没起来（看 logcat）。'
    & $adb -s $serial logcat -d -s S2Eval 2>&1 | Select-Object -Last 20 | ForEach-Object { Write-Info $_ }
    exit 3
}

# ---------------------------------------------------------------------------
# 第 6 步：pull 报告
# ---------------------------------------------------------------------------

Write-Step '第 6 步：拉取报告'
$outPath = [string]$Opt['out']
$outDir = Split-Path -Parent $outPath
if ($outDir -and -not (Test-Path -LiteralPath $outDir)) {
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
}
if ($runAsOk) {
    # 内部目录拉不出来（adb pull 读不了别的 uid 的目录）→ 用 run-as cat + 二进制重定向
    $errFile = [IO.Path]::GetTempFileName()
    $proc = Start-Process -FilePath $adb `
        -ArgumentList @('-s', $serial, 'exec-out', 'run-as', $pkg, 'cat', $relReport) `
        -NoNewWindow -Wait -PassThru -RedirectStandardOutput $outPath -RedirectStandardError $errFile
    $pullErr = (Get-Content -LiteralPath $errFile -Raw -ErrorAction SilentlyContinue)
    Remove-Item -LiteralPath $errFile -Force -ErrorAction SilentlyContinue
    if ($proc.ExitCode -ne 0 -and $pullErr) {
        Write-Err2 ('报告拉取失败：' + $pullErr.Trim())
        exit 3
    }
} else {
    $pullOut = & $adb -s $serial pull $remoteReport $outPath 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Err2 ('报告拉取失败：' + (($pullOut | Out-String).Trim()))
        exit 3
    }
}
if (-not (Test-Path -LiteralPath $outPath)) {
    Write-Err2 '报告拉取后本地没有这个文件。'
    exit 3
}
Write-Info ('报告已保存：' + $outPath + '（' + (Get-Item -LiteralPath $outPath).Length + ' 字节）')

# ---------------------------------------------------------------------------
# 第 7 步：打印结论摘要
# ---------------------------------------------------------------------------

Write-Step '第 7 步：结论摘要'
$text = Get-Content -LiteralPath $outPath -Raw -Encoding UTF8
$lines = $text -split "`r?`n"

Write-Host ''
Write-Host '--- 报告头（口径行） ---' -ForegroundColor White
foreach ($l in $lines) {
    if ($l -match '^> (端侧|云端端点|时间预算|样本|样本指纹|判据)：') { Write-Host $l }
}

Write-Host ''
Write-Host '--- 一、综合可用性（云端 S2 | 端侧 S2） ---' -ForegroundColor White
$inTable = $false
foreach ($l in $lines) {
    if ($l -match '^## 一、') { $inTable = $true; continue }
    if ($inTable -and $l -match '^## ') { break }
    if ($inTable -and $l -match '^\|') { Write-Host $l }
}

$undelivered = @($lines | Where-Object { $_ -match '^- (端侧|云端) · ' })
if ($undelivered.Count -gt 0) {
    Write-Host ''
    Write-Host '--- 四、未交付明细 ---' -ForegroundColor White
    foreach ($l in $undelivered) { Write-Host $l }
}

$budgetNote = @($lines | Where-Object { $_ -match '本轮预算耗尽' })
if ($budgetNote.Count -gt 0) {
    Write-Host ''
    Write-Host '--- 四之二、预算耗尽 ---' -ForegroundColor White
    foreach ($l in $budgetNote) { Write-Host $l }
}

Write-Host ''
Write-Host '--- 怎么读这组数字 ---' -ForegroundColor White
Write-Host '  * 「六项全过（可用）」那格的**端侧 S2**列才是「端侧 2B 的可用率」；云端 S2 列是对照。'
Write-Host '  * 分母是**样本数**：端侧 0 交付时显示 0/20，读作「未测」，不是「2B 不行」。'
Write-Host '  * 「已交付」= 端侧真的跑出了可解析的输出；它为 0 说明设备/模型/引擎没就绪或全失败。'
Write-Host '  * 逐样本表里的「端侧时延」是真机单条耗时（含 prefill），用来估下一轮要留多少预算。'

Write-Host ''
Write-Host '完成。' -ForegroundColor Green
exit 0
