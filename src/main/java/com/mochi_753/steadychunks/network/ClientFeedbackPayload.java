package com.mochi_753.steadychunks.network;

import com.mochi_753.steadychunks.SteadyChunks;
import com.mochi_753.steadychunks.client.ClientFeedbackSnapshot;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 客户端反馈网络包，对应开发计划 §5.4。
 * <p>
 * NeoForge 1.21.x 使用 {@link CustomPacketPayload} 替代旧网络 API。
 * <p>
 * 协议：客户端每 2 秒发送一次聚合反馈，服务端接收后交给
 * {@link com.mochi_753.steadychunks.client.ClientFeedbackAggregator}。
 * <p>
 * 技术指导 §5.4 风险：客户端协议兼容——使用可选自定义握手；
 * 无握手时只做服务器端调速，客户端不安装模组时服务端自动退化为服务端模式。
 */
public record ClientFeedbackPayload(
        UUID playerId,
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
        this(snapshot.playerId(), snapshot.p95FrameTimeMs(), snapshot.p99FrameTimeMs(),
                snapshot.maxFrameTimeMs(), snapshot.sectionCompileQueueDepth(),
                snapshot.chunksReceivedLastWindow(), snapshot.chunkApplyTimeMs(),
                snapshot.visibleGaps(), snapshot.protocolVersion());
    }

    /**
     * 序列化到字节缓冲。
     */
    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(playerId);
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
                buf.readUUID(),
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
     * 转换为反馈快照。
     */
    public ClientFeedbackSnapshot toSnapshot() {
        return new ClientFeedbackSnapshot(playerId, p95FrameTimeMs, p99FrameTimeMs,
                maxFrameTimeMs, sectionCompileQueueDepth, chunksReceivedLastWindow,
                chunkApplyTimeMs, visibleGaps);
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
