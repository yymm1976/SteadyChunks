package com.mochi_753.steadychunks.scheduler;

/**
 * 优先级评分模型，对应开发计划 §3.5 与技术指导 §6.1。
 * <p>
 * 评分公式（全部使用 long，避免浮点比较不稳定，§6.1）：
 * <pre>
 * priority =
 *   距离权重
 * + 可见范围紧迫度
 * + 当前阶段进度
 * + 排队年龄
 * + 玩家公平性
 * + 运动方向预测
 * - 已失去需求惩罚
 * </pre>
 * <p>
 * 评分必须防止：
 * <ul>
 *   <li>远处任务永久饥饿（年龄提升）</li>
 *   <li>多玩家时单个高速玩家占满全部资源（公平性因子）</li>
 *   <li>新维度任务被旧维度积压压制（维度配额）</li>
 *   <li>已到后期但不再需要的任务无限占优（失去需求惩罚）</li>
 * </ul>
 * <p>
 * 所有权重使用 long，0..1 范围的因子先乘以权重再转 long，保留整数精度。
 */
public final class PriorityModel {
    /** 距离权重系数：越近分越高 */
    private static final long WEIGHT_DISTANCE = 100_000L;
    /** 可见范围紧迫度权重 */
    private static final long WEIGHT_VISIBILITY = 50_000L;
    /** 完成进度权重：越接近完成分越高（完成优先） */
    private static final long WEIGHT_PROGRESS = 80_000L;
    /** 排队年龄权重（每毫秒 +10，每秒 +10000） */
    private static final long WEIGHT_AGE_PER_MS = 10L;
    /** 公平性因子权重 */
    private static final long WEIGHT_FAIRNESS = 40_000L;
    /** 运动方向匹配权重 */
    private static final long WEIGHT_DIRECTION = 30_000L;
    /** 失去需求惩罚 */
    private static final long PENALTY_NO_DEMAND = 200_000L;
    /** 年龄饥饿保底：超过此年龄后额外加分 */
    private static final long STARVATION_THRESHOLD_MS = 5000L;
    private static final long STARVATION_BONUS = 50_000L;

    private final FairnessManager fairness;

    public PriorityModel(FairnessManager fairness) {
        this.fairness = fairness;
    }

    /**
     * 计算任务优先级评分，分数越高越优先调度。
     * <p>
     * 返回 long（§6.1），调用方使用 {@link ChunkTask#setPriorityScore(long)} 缓存。
     *
     * @param maxVisibleDistance 当前可见范围最大距离（用于归一化）
     */
    public long score(ChunkTask task, double maxVisibleDistance) {
        long score = 0L;

        // 1. 距离权重：越近分越高，线性归一化
        double normalizedDistance = maxVisibleDistance > 0
                ? task.distance() / maxVisibleDistance : 1.0;
        score += (long) (WEIGHT_DISTANCE * (1.0 - Math.min(1.0, normalizedDistance)));

        // 2. 可见范围紧迫度：在可见范围内的区块额外加分
        if (task.distance() <= maxVisibleDistance) {
            score += WEIGHT_VISIBILITY;
        }

        // 3. 完成进度：越接近完成分越高（完成优先策略）
        score += (long) (WEIGHT_PROGRESS * task.progress());

        // 4. 排队年龄：越久越优先，防止饥饿
        long ageMs = task.queueAgeMs();
        score += WEIGHT_AGE_PER_MS * ageMs;
        // 超过饥饿阈值后额外加分
        if (ageMs > STARVATION_THRESHOLD_MS) {
            score += STARVATION_BONUS;
        }

        // 5. 公平性因子：已占用资源多的玩家/维度获得更低分（0..1）
        double fairnessFactor = fairness.fairnessFactor(task);
        score += (long) (WEIGHT_FAIRNESS * fairnessFactor);

        // 6. 运动方向预测：与玩家运动方向匹配的任务加分
        score += (long) (WEIGHT_DIRECTION * task.directionMatch());

        // 7. 失去需求惩罚：没有需求玩家的任务大幅降分
        if (task.requestingPlayers().isEmpty()) {
            score -= PENALTY_NO_DEMAND;
        }

        return score;
    }
}
