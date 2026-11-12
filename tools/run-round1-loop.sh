#!/bin/bash
# 阶段 1 临时重跑循环：完整 GameTest 直到 3 连过或达到轮次上限。
# 每轮：清环境 → 启动（记录 PID）→ 轮询结果/卡死 → 保留证据 → 终止本轮进程树。
set -u
REPO="/c/Users/杨铭/Desktop/SteadyChunks"
ART="$REPO/artifacts/round14-stalls"
MAX_ROUNDS=${1:-12}
NEED_PASS=${2:-5}
cd "$REPO"
mkdir -p "$ART"
PASS=0
declare -a RESULTS=()
for i in $(seq 1 "$MAX_ROUNDS"); do
  echo "================ round $i ================"
  rm -rf run-server/world
  rm -f run-server/logs/latest.log
  # 启动 GameTest（后台），记录包装 PID
  powershell -NoProfile -Command 'Set-Location C:\Users\杨铭\Desktop\SteadyChunks; rtk err gradlew runGameTestServer' > "$ART/run-$i.out" 2>&1 &
  WRAPPER_PID=$!
  RESULT="TIMEOUT"
  PREV_BATCHES=-1
  STALL_STREAK=0
  for t in $(seq 1 110); do   # 最多 ~550 秒
    sleep 5
    if [ -f run-server/logs/latest.log ]; then
      if grep -qE "All [0-9]+ required tests passed" run-server/logs/latest.log; then
        RESULT="PASS"; break
      fi
      if grep -q "failed at" run-server/logs/latest.log; then
        RESULT="FAIL"; break
      fi
      # 卡死检测：批次计数 90 秒不增
      B=$(grep -c "Running test batch" run-server/logs/latest.log)
      if [ "$B" -eq "$PREV_BATCHES" ]; then
        STALL_STREAK=$((STALL_STREAK + 1))
      else
        STALL_STREAK=0
      fi
      PREV_BATCHES=$B
      if [ "$STALL_STREAK" -ge 18 ]; then RESULT="STALL"; break; fi
    fi
    # wrapper 退出不再视为异常：gradle daemon 可能先退而服务器子进程继续跑
    # （实测 EXITED-UNMARKED 轮次服务器日志仍在推进）。PASS/FAIL 标记与 STALL
    # 检测（日志不增长）已覆盖所有终态；wrapper 死亡只记录，不中断轮询。
    if ! kill -0 "$WRAPPER_PID" 2>/dev/null; then
      WRAPPER_DEAD=1
    fi
  done
  # 收集证据
  cp run-server/logs/latest.log "$ART/latest-round$i.log" 2>/dev/null || true
  SRV_PID=$(powershell -NoProfile -Command "(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Sort-Object WorkingSetSize -Descending | Select-Object -First 1).ProcessId" 2>/dev/null | tr -d '\r')
  if [ -n "$SRV_PID" ] && [ "$SRV_PID" != "0" ] && [ "$RESULT" != "PASS" ]; then
    "D:\zulu21.44.17-ca-jdk21.0.8-win_x64\bin\jstack" "$SRV_PID" > "$ART/jstack-round$i-$SRV_PID.txt" 2>/dev/null || true
    powershell -NoProfile -Command "Stop-Process -Id $SRV_PID -Force" 2>/dev/null
    echo "round $i: $RESULT (stopped server PID $SRV_PID)"
  else
    if [ -n "$SRV_PID" ] && [ "$SRV_PID" != "0" ]; then
      # PASS 后也要收尾：终止本轮 java 进程树（服务器 + gradle daemon 是复用的，只停服务器）
      powershell -NoProfile -Command "Stop-Process -Id $SRV_PID -Force" 2>/dev/null
    fi
    echo "round $i: $RESULT"
  fi
  RESULTS+=("$i=$RESULT${WRAPPER_DEAD:+[wrapper-dead]}")
  WRAPPER_DEAD=
  if [ "$RESULT" = "PASS" ]; then
    PASS=$((PASS + 1))
    if [ "$PASS" -ge "$NEED_PASS" ]; then
      echo "=== ${NEED_PASS} consecutive passes achieved at round $i ==="
      break
    fi
  else
    PASS=0
  fi
  # 等 wrapper/gradle 退出
  sleep 8
done
echo "=== results: ${RESULTS[*]} ==="
