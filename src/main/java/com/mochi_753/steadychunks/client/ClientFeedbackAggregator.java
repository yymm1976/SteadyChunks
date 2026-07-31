package com.mochi_753.steadychunks.client;

import com.mochi_753.steadychunks.SteadyChunks;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端反馈聚合器，对应开发计划 §5.4。
 * <p>
 * 服务端侧：接收并聚合来自客户端的反馈快照。
 * <p>
 * 技术指导 §9.3：
 * <ul>
 *   <li>不每帧发包，每 1-2 秒发送聚合值</li>
 *   <li>服务端只将其视为提示，不信任绝对值</li>
 *   <li>不允许它改变游戏逻辑或获得额外权限</li>
 *   <li>远程服务器必须允许关闭或忽略客户端反馈</li>
 *   <li>客户端不安装模组时退化为服务端模式</li>
 * </ul>
 */
public final class ClientFeedbackAggregator {
    private static ClientFeedbackAggregator instance;

    /** 每玩家最新反馈快照 */
    private final ConcurrentHashMap<UUID, ClientFeedbackSnapshot> snapshots = new ConcurrentHashMap<>();
    /** 是否接受客户端反馈（独立服务器可关闭） */
    private volatile boolean acceptClientFeedback = true;
    /** 客户端是否有模组（握手后设置） */
    private final ConcurrentHashMap<UUID, Boolean> clientHasMod = new ConcurrentHashMap<>();

    private ClientFeedbackAggregator() {
    }

    public static synchronized ClientFeedbackAggregator getInstance() {
        if (instance == null) {
            instance = new ClientFeedbackAggregator();
        }
        return instance;
    }

    /**
     * 接收客户端反馈。
     * <p>
     * 仅在 {@code acceptClientFeedback} 为 true 且客户端有模组时接受。
     */
    public void receive(ClientFeedbackSnapshot snapshot) {
        if (!acceptClientFeedback) {
            return;
        }
        Boolean hasMod = clientHasMod.get(snapshot.playerId());
        if (hasMod == null || !hasMod) {
            return;
        }
        snapshots.put(snapshot.playerId(), snapshot);
    }

    /**
     * 获取玩家最新的客户端反馈。
     *
     * @return 反馈快照，null 表示无反馈或已过期
     */
    public ClientFeedbackSnapshot get(UUID playerId) {
        ClientFeedbackSnapshot snapshot = snapshots.get(playerId);
        if (snapshot == null || snapshot.isStale()) {
            return null;
        }
        return snapshot;
    }

    /**
     * 标记玩家客户端是否安装了 SteadyChunks 模组（握手结果）。
     */
    public void setClientHasMod(UUID playerId, boolean hasMod) {
        clientHasMod.put(playerId, hasMod);
        if (!hasMod) {
            snapshots.remove(playerId);
        }
    }

    /**
     * 玩家断开时清理。
     */
    public void clearPlayer(UUID playerId) {
        snapshots.remove(playerId);
        clientHasMod.remove(playerId);
    }

    public void setAcceptClientFeedback(boolean accept) {
        this.acceptClientFeedback = accept;
        if (!accept) {
            snapshots.clear();
            SteadyChunks.LOGGER.info("SteadyChunks 客户端反馈已关闭");
        }
    }

    public boolean isAcceptClientFeedback() {
        return acceptClientFeedback;
    }

    /**
     * 获取所有有有效反馈的玩家 ID。
     */
    public java.util.Set<UUID> playersWithFeedback() {
        return snapshots.keySet();
    }

    /**
     * 获取综合客户端压力评估（所有玩家的最差情况）。
     */
    public ClientFeedbackSnapshot.ClientPressureLevel aggregatePressure() {
        ClientFeedbackSnapshot.ClientPressureLevel worst =
                ClientFeedbackSnapshot.ClientPressureLevel.HEALTHY;
        for (ClientFeedbackSnapshot snapshot : snapshots.values()) {
            if (snapshot.isStale()) {
                continue;
            }
            ClientFeedbackSnapshot.ClientPressureLevel level = snapshot.evaluatePressure();
            if (level.ordinal() > worst.ordinal()) {
                worst = level;
            }
        }
        return worst;
    }
}
