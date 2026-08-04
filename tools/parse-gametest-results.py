#!/usr/bin/env python3
# 阶段 5：GameTest soak 结果解析与卡死分类。
# 读取 artifacts/soak/round-*/ 证据目录，按 Server thread 忙转点分类 STALL 轮，
# 汇总 PASS/FAIL/STALL/TIMEOUT、连过、incident 快照与事故分类。
# 用法：python tools/parse-gametest-results.py [artifacts/soak]
import io, os, re, sys, json
from collections import Counter

BASE = sys.argv[1] if len(sys.argv) > 1 else "artifacts/soak"

def classify_jstack(path):
    """按 Server thread 首个 minecraft 栈帧分类忙转点；返回 (类别, 具体方法)。"""
    try:
        with io.open(path, "r", encoding="utf-8", errors="replace") as f:
            text = f.read()
    except OSError:
        return "no-jstack", ""
    m = re.search(r'"Server thread".*?\n(.*?)\n\s+at (net\.minecraft[^\s(]+)', text, re.S)
    if not m:
        # Server thread 存在但无 minecraft 栈帧
        if '"Server thread"' in text:
            return "server-thread-no-mc-frame", ""
        return "no-server-thread", ""
    frame = m.group(2)
    if "processUnloads" in frame:
        return "processUnloads", frame
    if "tickChunks" in frame:
        return "tickChunks-shuffle", frame
    if "saveChunkIfNeeded" in frame:
        return "saveChunkIfNeeded", frame
    if "purgeStaleTickets" in frame:
        return "purgeStaleTickets", frame
    if "canPositionTick" in frame:
        return "canPositionTick", frame
    return "other-minecraft", frame

def main():
    rounds = []
    for entry in sorted(os.listdir(BASE), key=lambda s: int(re.search(r"\d+", s).group()) if re.search(r"\d+", s) else 0):
        d = os.path.join(BASE, entry)
        if not os.path.isdir(d) or not entry.startswith("round-"):
            continue
        n = int(re.search(r"\d+", entry).group())
        state = {}
        st = os.path.join(d, "state.txt")
        if os.path.exists(st):
            with io.open(st, "r", encoding="utf-8", errors="replace") as f:
                for line in f:
                    if "=" in line:
                        k, v = line.strip().split("=", 1)
                        state[k] = v
        result = state.get("result", "?")
        cls, frame = "", ""
        jstack = state.get("jstack", "")
        if result == "STALL" or result == "TIMEOUT":
            jp = os.path.join(d, jstack) if jstack else None
            if jp and os.path.exists(jp):
                cls, frame = classify_jstack(jp)
            else:
                # 回退：目录下任意 jstack 文件
                for fn in os.listdir(d):
                    if fn.startswith("jstack-"):
                        cls, frame = classify_jstack(os.path.join(d, fn))
                        break
            if not cls:
                cls = "no-jstack"
        incidents = []
        inc = os.path.join(d, "incidents")
        if os.path.isdir(inc):
            incidents = [x for x in os.listdir(inc) if os.path.isdir(os.path.join(inc, x))]
        rounds.append({"round": n, "result": result, "class": cls, "frame": frame,
                       "incidents": incidents, "pid": state.get("serverPid", "")})

    counter = Counter(r["result"] for r in rounds)
    cls_counter = Counter(r["class"] for r in rounds if r["class"])
    inc_counter = Counter()
    for r in rounds:
        for i in r["incidents"]:
            t = i.split("-", 2)[-1] if "-" in i else i
            inc_counter[t] += 1

    # 连过统计
    best = cur = 0
    for r in rounds:
        if r["result"] == "PASS":
            cur += 1
            best = max(best, cur)
        else:
            cur = 0

    total = len(rounds)
    print("=== SOAK 解析结果 ===")
    print(f"轮数={total} PASS={counter['PASS']} FAIL={counter['FAIL']} STALL={counter['STALL']} TIMEOUT={counter['TIMEOUT']}")
    if total:
        print(f"过率={100.0 * counter['PASS'] / total:.1f}% 最佳连过={best}")
    print("--- STALL/TIMEOUT 卡死分类（按 Server thread 忙转点） ---")
    for cls, cnt in cls_counter.most_common():
        print(f"  {cls}: {cnt}")
    print("--- 事故快照分类 ---")
    for t, cnt in inc_counter.most_common():
        print(f"  {t}: {cnt}")
    print("--- 失败/卡死轮明细 ---")
    for r in rounds:
        if r["result"] != "PASS":
            print(f"  round {r['round']}: {r['result']} class={r['class']} "
                  f"pid={r['pid']} incidents={r['incidents']}")
            if r["frame"]:
                print(f"      frame: {r['frame'][:120]}")

    # JSON 供报告引用
    summary = {"rounds": rounds, "counters": dict(counter),
               "classCounter": dict(cls_counter), "incidentCounter": dict(inc_counter),
               "bestStreak": best, "passRate": round(100.0 * counter["PASS"] / total, 1) if total else 0}
    with io.open(os.path.join(BASE, "summary.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=1)
    print(f"JSON 汇总已写入: {os.path.join(BASE, 'summary.json')}")

if __name__ == "__main__":
    main()
