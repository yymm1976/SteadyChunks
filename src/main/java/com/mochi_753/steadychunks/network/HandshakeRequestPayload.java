package com.mochi_753.steadychunks.network;

import com.mochi_753.steadychunks.SteadyChunks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 握手请求包（服务端→客户端），对应开发计划 §5.4。
 * <p>
 * 服务端在玩家加入时发送，询问客户端是否安装 SteadyChunks 模组。
 * 客户端安装则回应 {@link HandshakeResponsePayload}，未安装则无回应。
 */
public record HandshakeRequestPayload(int protocolVersion) implements CustomPacketPayload {

    public static final Type<HandshakeRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SteadyChunks.MOD_ID, "handshake_request"));

    public static final StreamCodec<FriendlyByteBuf, HandshakeRequestPayload> STREAM_CODEC =
            StreamCodec.ofMember(HandshakeRequestPayload::write, HandshakeRequestPayload::read);

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(protocolVersion);
    }

    public static HandshakeRequestPayload read(FriendlyByteBuf buf) {
        return new HandshakeRequestPayload(buf.readVarInt());
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
