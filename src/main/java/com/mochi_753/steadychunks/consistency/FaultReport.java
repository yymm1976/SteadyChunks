package com.mochi_753.steadychunks.consistency;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 故障注入报告，对应开发计划 Phase 11.2。
 * <p>
 * 记录故障注入后的资源泄漏检测结果。
 * 每个泄漏项包含模块名和残留计数。
 */
public final class FaultReport {
    private final Map<String, Integer> leaks = new LinkedHashMap<>();
    private final long timestamp = System.currentTimeMillis();

    public void addLeak(String module, int count) {
        leaks.put(module, count);
    }

    public boolean hasLeaks() {
        return !leaks.isEmpty();
    }

    public int leakCount() {
        return leaks.size();
    }

    public Map<String, Integer> leaks() {
        return leaks;
    }

    public long timestamp() {
        return timestamp;
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("FaultReport[timestamp=").append(timestamp)
                .append(" leaks=").append(leakCount());
        for (var entry : leaks.entrySet()) {
            sb.append(" ").append(entry.getKey()).append("=").append(entry.getValue());
        }
        sb.append("]");
        return sb.toString();
    }
}
