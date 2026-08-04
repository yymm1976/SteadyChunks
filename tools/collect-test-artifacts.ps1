# 阶段 5：GameTest 失败证据采集助手（soak 运行器每轮调用；也可独立使用）。
# 顺序遵循 /goal 约束：日志 → jstack → PID/命令行 → state → （终止由调用方执行）。
# 用法：
#   powershell -ExecutionPolicy Bypass -File tools/collect-test-artifacts.ps1 `
#     -RoundDir artifacts/soak/round-001 [-ServerPid 1234]
param(
    [Parameter(Mandatory = $true)][string]$RoundDir,
    [int]$ServerPid = 0
)

$ErrorActionPreference = "Continue"
$Repo = "C:\Users\杨铭\Desktop\SteadyChunks"
$JStack = "D:\zulu21.44.17-ca-jdk21.0.8-win_x64\bin\jstack.exe"

New-Item -ItemType Directory -Force -Path $RoundDir | Out-Null

# 1. 日志
Copy-Item "$Repo\run-server\logs\latest.log" "$RoundDir\latest.log" -ErrorAction SilentlyContinue

# 2. 服务器 java 识别（命令行含 forgeserverdev/GameTest，避免误杀 gradle daemon）
if (-not $ServerPid) {
    $procs = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue
    foreach ($p in $procs) {
        if ($p.CommandLine -match "-Dfml.modFolders") { $ServerPid = $p.ProcessId; break }
    }
}
$serverCmd = ""
if ($ServerPid) {
    $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$ServerPid" -ErrorAction SilentlyContinue
    if ($proc) { $serverCmd = $proc.CommandLine }
    # 2b. jstack
    & $JStack $ServerPid 2>$null | Out-File "$RoundDir\jstack-$ServerPid.txt" -Encoding utf8
}

# 3. 事故快照（只诊断数据）
if (Test-Path "$Repo\run-server\steadychunks-incidents") {
    Copy-Item -Recurse -Force "$Repo\run-server\steadychunks-incidents" "$RoundDir\incidents" -ErrorAction SilentlyContinue
}

# 4. state（PID/命令行/时间）
$state = @(
    "serverPid=$ServerPid",
    "serverCmd=$serverCmd",
    "time=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
)
$state | Set-Content "$RoundDir\state.txt"
Write-Host "证据已采集到 $RoundDir (serverPid=$ServerPid)"
