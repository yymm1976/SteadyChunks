package com.mochi_753.steadychunks.consistency;

/**
 * 一致性检查发现的问题，对应开发计划 Phase 11.1。
 * <p>
 * 问题级别：
 * <ul>
 *   <li>ERROR：严重问题，必须修复（如区块未加载、Heightmap 缺失）</li>
 *   <li>WARN：潜在问题，需要关注（如哈希差异、引用距离异常）</li>
 *   <li>INFO：信息性提示，不影响正确性</li>
 * </ul>
 */
public record ConsistencyIssue(Level level, String message) {
    public enum Level {
        ERROR, WARN, INFO
    }

    public static ConsistencyIssue error(String msg) {
        return new ConsistencyIssue(Level.ERROR, msg);
    }

    public static ConsistencyIssue warn(String msg) {
        return new ConsistencyIssue(Level.WARN, msg);
    }

    public static ConsistencyIssue info(String msg) {
        return new ConsistencyIssue(Level.INFO, msg);
    }

    @Override
    public String toString() {
        return "[" + level + "] " + message;
    }
}
