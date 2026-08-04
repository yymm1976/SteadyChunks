# 阶段 5：GameTest 整夜 soak 运行器（默认 50 轮，失败证据采集）。
# 用法（原路径 + 默认 gradle 缓存）：
#   powershell -ExecutionPolicy Bypass -File tools/run-gametest-soak.ps1 [-Rounds 50] [-OutDir artifacts/soak]
#
# 硬性约束（遵循 /goal）：
#   - 禁止 taskkill /F /IM java.exe：一律按 PID 记录并终止本轮进程树
#     （残留 java 先记录 PID/命令行再终止，写入 summary）；
#   - 超时/卡死终止顺序：先保存日志 → jstack → 记录 PID/命令行 → 写 state → 再终止；
#   - 每轮独立目录 artifacts/soak/round-<N>/ 保留全部证据；
#   - 只诊断采集，不修改被测代码。
param(
    [int]$Rounds = 50,
    [string]$OutDir = "artifacts/soak",
    [int]$NeedPass = 5
)

$ErrorActionPreference = "Continue"
$Repo = "C:\Users\杨铭\Desktop\SteadyChunks"
$JStack = "D:\zulu21.44.17-ca-jdk21.0.8-win_x64\bin\jstack.exe"
$PollSeconds = 5
$PollMax = 110          # 每轮最多 ~550 秒
$StallStreakMax = 18    # 批次计数 90 秒不增 → STALL

Set-Location $Repo
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$results = @()
$incidentsTotal = 0

# ---- 审查 P0-2：只处理本仓库相关的 java（禁止全局终止） ----
function Test-IsRepoJava {
    param($Process)
    if (-not $Process -or -not $Process.CommandLine) {
        return $false
    }
    return $Process.CommandLine.IndexOf(
        $Repo,
        [System.StringComparison]::OrdinalIgnoreCase
    ) -ge 0
}

# 预清理：记录并仅终止命令行含本仓库路径的 java（IDE/其他服务器/用户程序不动）
$stale = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { Test-IsRepoJava $_ }
$staleNote = @()
foreach ($p in $stale) {
    $staleNote += "stale java PID=$($p.ProcessId) cmd=$($p.CommandLine)"
    Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
}
if ($staleNote.Count -gt 0) {
    $staleNote | Set-Content "$OutDir\pre-cleanup-stale.txt"
    Write-Host "预清理本仓库相关 java $($staleNote.Count) 个（仅限命令行含仓库路径者）"
}

# 精确识别本服务器 java（命令行含本仓库路径 且 -Dfml.modFolders）
function Get-ServerJavaPid {
    $procs = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue
    foreach ($p in $procs) {
        if ((Test-IsRepoJava $p) -and $p.CommandLine -match "-Dfml\.modFolders") {
            return $p.ProcessId
        }
    }
    return $null
}

# 审查 P1：以根 PID 递归收集后代并逆序终止（先子后父）；只终止命令行含仓库
# 路径的 java 与非 java wrapper——gradle daemon（全局复用）不在仓库路径内，不误杀。
function Stop-ProcessTree {
    param([int]$RootPid)
    $all = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue
    $children = @{}
    foreach ($p in $all) {
        if ($p.ParentProcessId -gt 0) {
            if (-not $children.ContainsKey($p.ParentProcessId)) { $children[$p.ParentProcessId] = @() }
            $children[$p.ParentProcessId] += $p.ProcessId
        }
    }
    $toKill = @()
    $queue = @($RootPid)
    while ($queue.Count -gt 0) {
        $cur = $queue[0]
        $queue = $queue[1..($queue.Count - 1)]
        if (-not $cur) { continue }
        $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$cur" -ErrorAction SilentlyContinue
        $isJava = $proc -and $proc.Name -eq "java.exe"
        if (-not $isJava -or (Test-IsRepoJava $proc)) {
            $toKill += $cur
        }
        if ($children.ContainsKey($cur)) { $queue += $children[$cur] }
    }
    for ($k = $toKill.Count - 1; $k -ge 0; $k--) {
        Stop-Process -Id $toKill[$k] -Force -ErrorAction SilentlyContinue
    }
}

# 等待进程退出（审查 P1：跨轮确认整棵进程树退出，避免 world/log 锁与 PID 误识别）
function Wait-ProcessGone {
    param([int]$Pid, [int]$Seconds = 10)
    for ($t = 0; $t -lt $Seconds; $t++) {
        $p = Get-Process -Id $Pid -ErrorAction SilentlyContinue
        if (-not $p) { return $true }
        Start-Sleep -Seconds 1
    }
    return $false
}

$passStreak = 0
$bestStreak = 0
for ($i = 1; $i -le $Rounds; $i++) {
    $roundDir = Join-Path $OutDir ("round-{0:D3}" -f $i)
    New-Item -ItemType Directory -Force -Path $roundDir | Out-Null
    Write-Host "================ round $i / $Rounds ================"

    # 本轮环境清理（只清本轮：world/log/incident；不杀 daemon）
    Remove-Item -Recurse -Force "$Repo\run-server\world" -ErrorAction SilentlyContinue
    Remove-Item -Force "$Repo\run-server\logs\latest.log" -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force "$Repo\run-server\steadychunks-incidents" -ErrorAction SilentlyContinue

    # 审查 P0-2：旧日志删除成功与否必须在 Start-Process 之前确认——残留日志
    # 可能含旧 PASS 标记制造假结果；删除失败 → INFRA_FAILURE，不启动服务器
    $result = "TIMEOUT"
    if (Test-Path "$Repo\run-server\logs\latest.log") {
        $result = "INFRA_FAILURE"
        Write-Host "round ${i}: INFRA_FAILURE (latest.log 删除失败，可能残留旧 PASS 标记)"
    }

    if ($result -ne "INFRA_FAILURE") {
    # 启动服务器（后台），记录包装 PID
    $wrapper = Start-Process -FilePath "powershell" -ArgumentList @(
        "-NoProfile", "-Command",
        "Set-Location '$Repo'; `$env:PATH += ';C:\Users\杨铭\bin'; rtk err gradlew runGameTestServer"
    ) -PassThru -RedirectStandardOutput "$roundDir\run.out" -RedirectStandardError "$roundDir\run.err"

    $prevBatches = -1
    $roundStartUtc = (Get-Date).ToUniversalTime()
    $stallStreak = 0
    for ($t = 0; $t -lt $PollMax -and $result -eq "TIMEOUT"; $t++) {
        Start-Sleep -Seconds $PollSeconds
        $log = "$Repo\run-server\logs\latest.log"
        if (Test-Path $log) {
            $content = Get-Content $log -Raw -ErrorAction SilentlyContinue
            if ($null -eq $content) { continue }   # 文件被写锁/未就绪：跳过本轮轮询
            # 审查 P0：旧日志假 PASS 防护——latest.log 必须在本轮启动后写入
            # （删除失败残留的旧日志含 PASS 标记时，时间戳校验拒绝接受）
            $logWrite = (Get-Item $log -ErrorAction SilentlyContinue).LastWriteTimeUtc
            $fresh = $logWrite -and $logWrite -ge $roundStartUtc
            if ($fresh -and $content -match "All \d+ required tests passed") { $result = "PASS"; break }
            if ($fresh -and $content -match "failed at") { $result = "FAIL"; break }
            $batches = ([regex]::Matches($content, "Running test batch")).Count
            if ($batches -eq $prevBatches) { $stallStreak++ } else { $stallStreak = 0 }
            $prevBatches = $batches
            if ($stallStreak -ge $StallStreakMax) { $result = "STALL"; break }
        }
        # wrapper 死亡不中断轮询（gradle daemon 先退而服务器子进程继续跑的已知现象）
    }
    }

    # ---- 证据采集（顺序遵循约束：日志 → jstack → PID/命令行 → state → 终止） ----
    # 1. 保存日志
    Copy-Item "$Repo\run-server\logs\latest.log" "$roundDir\latest.log" -ErrorAction SilentlyContinue
    # 2. 服务器 java 识别（先记录 PID/命令行）
    $serverPid = Get-ServerJavaPid
    $serverCmd = ""
    if ($serverPid) {
        $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$serverPid" -ErrorAction SilentlyContinue
        if ($proc) { $serverCmd = $proc.CommandLine }
    }
    # 2b. jstack（仅非 PASS 轮；PASS 轮服务器正常收尾）
    $jstackFile = ""
    if ($result -ne "PASS" -and $serverPid) {
        $jstackFile = "jstack-$serverPid.txt"
        & $JStack $serverPid 2>$null | Out-File "$roundDir\$jstackFile" -Encoding utf8
    }
    # 3. 事故快照目录（只诊断数据）
    if (Test-Path "$Repo\run-server\steadychunks-incidents") {
        Copy-Item -Recurse -Force "$Repo\run-server\steadychunks-incidents" "$roundDir\incidents" -ErrorAction SilentlyContinue
        $incidentsTotal += (Get-ChildItem "$Repo\run-server\steadychunks-incidents" -Directory -ErrorAction SilentlyContinue).Count
    }
    # 4. state 文件
    $state = @(
        "round=$i", "result=$result", "serverPid=$serverPid",
        "serverCmd=$serverCmd", "jstack=$jstackFile",
        "time=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
    )
    $state | Set-Content "$roundDir\state.txt"
    # 5. 终止本轮进程树（审查 P1：以 wrapper 为根递归；服务器 java 按
    #    PID 先终止；仅终止命令行含仓库路径的 java——gradle daemon 不误杀）
    if ($serverPid) {
        $exited = Wait-ProcessGone -Pid $serverPid
        if (-not $exited) {
            Add-Content "$roundDir\state.txt" "serverNotExited=true"
            Write-Host "round ${i}: $result (server PID $serverPid 未在 10 秒内退出)"
        } else {
            Write-Host "round ${i}: $result (stopped server PID $serverPid)"
        }
    } else {
        Write-Host "round ${i}: $result (no server java found)"
    }
    # wrapper 树收尾（含残留的 gradle client java——命令行含仓库路径者）
    if ($wrapper -and -not $wrapper.HasExited) {
        Stop-ProcessTree -RootPid $wrapper.Id
        $wrapper.WaitForExit(15000) | Out-Null
    }

    # 结果登记 + 连过统计
    $results += "$i=$result"
    if ($result -eq "PASS") {
        $passStreak++
        if ($passStreak -gt $bestStreak) { $bestStreak = $passStreak }
    } else {
        $passStreak = 0
    }
    if ($bestStreak -ge $NeedPass) {
        Write-Host "=== ${NeedPass} 连过已达成（round $i），继续跑满 ${Rounds} 轮 ==="
    }
    Start-Sleep -Seconds 8   # 等 wrapper/gradle 退出，端口释放
}

# ---- 汇总 ----
$pass = ($results | Where-Object { $_ -match "=PASS$" }).Count
$fail = ($results | Where-Object { $_ -match "=FAIL$" }).Count
$stall = ($results | Where-Object { $_ -match "=STALL$" }).Count
$timeout = ($results | Where-Object { $_ -match "=TIMEOUT$" }).Count
$summary = @(
    "=== SOAK SUMMARY ===",
    "rounds=$Rounds pass=$pass fail=$fail stall=$stall timeout=$timeout",
    "passRate=$([math]::Round(100.0 * $pass / $Rounds, 1))% bestStreak=$bestStreak",
    "incidents=$incidentsTotal",
    "results=$($results -join ' ')"
)
$summary | Set-Content "$OutDir\summary.txt"
$summary | ForEach-Object { Write-Host $_ }
Write-Host "=== soak 完成，证据目录: $OutDir ==="
