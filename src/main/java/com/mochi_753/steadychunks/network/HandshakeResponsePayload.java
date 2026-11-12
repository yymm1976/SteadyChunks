package com.mochi_753.steadychunks.network;

import com.mochi_753.steadychunks.SteadyChunks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 客户端握手回应包，对应开发计划 §5.4 风险"客户端协议兼容"。
 * <p>
 * 服务端发送 {@link HandshakeRequestPayload} 询问客户端是否安装 SteadyChunks，
 * 客户端回应此包告知服务端是否安装及协议版本。
 * <p>
 * 无握手时服务端只做服务器端调速，客户端不安装模组时服务端自动退化为服务端模式。
 */
public record HandshakeResponsePayload(
        boolean hasMod,
        int protocolVersion,
        boolean acceptsCompileGovernance
) implements CustomPacketPayload {

    public static final Type<HandshakeResponsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SteadyChunks.MOD_ID, "handshake_response"));

    public static final StreamCodec<FriendlyByteBuf, HandshakeResponsePayload> STREAM_CODEC =
            StreamCodec.ofMember(HandshakeResponsePayload::write, HandshakeResponsePayload::read);

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(hasMod);
        buf.writeVarInt(protocolVersion);
        buf.writeBoolean(acceptsCompileGovernance);
    }

    public static HandshakeResponsePayload read(FriendlyByteBuf buf) {
        return new HandshakeResponsePayload(
                buf.readBoolean(),
                buf.readVarInt(),
                buf.readBoolean()
        );
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
