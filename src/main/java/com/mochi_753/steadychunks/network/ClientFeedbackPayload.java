package com.mochi_753.steadychunks.network;

import com.mochi_753.steadychunks.SteadyChunks;
import com.mochi_753.steadychunks.client.ClientFeedbackSnapshot;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 客户端反馈网络包，对应开发计划 §5.4，P0-7 修复。
 * <p>
 * <b>安全修复</b>：包中不再包含 {@code playerId}，服务端从
 * {@code context.player().getUUID()} 取身份，防止客户端伪造其他玩家。
 * 所有数值在服务端侧 clamp 限幅，防止恶意超大值。
 * <p>
 * 协议：客户端每 2 秒发送一次聚合反馈，服务端接收后 clamp 并交给
 * {@link com.mochi_753.steadychunks.client.ClientFeedbackAggregator}。
 */
public record ClientFeedbackPayload(
        double p95FrameTimeMs,
        double p99FrameTimeMs,
        double maxFrameTimeMs,
        int sectionCompileQueueDepth,
        int chunksReceivedLastWindow,
        double chunkApplyTimeMs,
        int visibleGaps,
        int protocolVersion
) implements CustomPacketPayload {

    /** 包 ID */
    public static final Type<ClientFeedbackPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SteadyChunks.MOD_ID, "client_feedback"));

    /** 流编解码器 */
    public static final StreamCodec<FriendlyByteBuf, ClientFeedbackPayload> STREAM_CODEC =
            StreamCodec.ofMember(ClientFeedbackPayload::write, ClientFeedbackPayload::read);

    public ClientFeedbackPayload(ClientFeedbackSnapshot snapshot) {
        this(snapshot.p95FrameTimeMs(), snapshot.p99FrameTimeMs(),
                snapshot.maxFrameTimeMs(), snapshot.sectionCompileQueueDepth(),
                snapshot.chunksReceivedLastWindow(), snapshot.chunkApplyTimeMs(),
                snapshot.visibleGaps(), snapshot.protocolVersion());
    }

    /**
     * 序列化到字节缓冲。不含 playerId（服务端从 context 取）。
     */
    public void write(FriendlyByteBuf buf) {
        buf.writeDouble(p95FrameTimeMs);
        buf.writeDouble(p99FrameTimeMs);
        buf.writeDouble(maxFrameTimeMs);
        buf.writeVarInt(sectionCompileQueueDepth);
        buf.writeVarInt(chunksReceivedLastWindow);
        buf.writeDouble(chunkApplyTimeMs);
        buf.writeVarInt(visibleGaps);
        buf.writeVarInt(protocolVersion);
    }

    /**
     * 从字节缓冲反序列化。
     */
    public static ClientFeedbackPayload read(FriendlyByteBuf buf) {
        return new ClientFeedbackPayload(
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readDouble(),
                buf.readVarInt(),
                buf.readVarInt()
        );
    }

    /**
     * 转换为反馈快照，使用服务端提供的玩家 ID（P0-7 安全修复）。
     * <p>
     * 所有数值经 clamp 限幅，防止恶意客户端发送 NaN/Infinity/超大值。
     *
     * @param playerId 服务端从 context.player().getUUID() 取得的可信玩家 ID
     */
    public ClientFeedbackSnapshot toSnapshot(UUID playerId) {
        // clamp 限幅：帧时间 0-10000ms，队列 0-100000，缺口 0-10000
        double p95 = clampFinite(p95FrameTimeMs, 0, 10_000);
        double p99 = clampFinite(p99FrameTimeMs, 0, 10_000);
        double max = clampFinite(maxFrameTimeMs, 0, 10_000);
        int queue = Mth.clamp(sectionCompileQueueDepth, 0, 100_000);
        int chunks = Mth.clamp(chunksReceivedLastWindow, 0, 100_000);
        double apply = clampFinite(chunkApplyTimeMs, 0, 10_000);
        int gaps = Mth.clamp(visibleGaps, 0, 10_000);
        return new ClientFeedbackSnapshot(playerId, p95, p99, max, queue, chunks, apply, gaps);
    }

    /** 将 double 限制在 [min,max] 并过滤 NaN/Infinity */
    private static double clampFinite(double v, double min, double max) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return min;
        }
        return Mth.clamp(v, min, max);
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
