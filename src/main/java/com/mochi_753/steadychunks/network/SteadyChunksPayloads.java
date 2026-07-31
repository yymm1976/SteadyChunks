package com.mochi_753.steadychunks.network;

import com.mochi_753.steadychunks.SteadyChunks;
import com.mochi_753.steadychunks.client.ClientFeedbackAggregator;
import com.mochi_753.steadychunks.client.ClientFeedbackSnapshot;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.UUID;

/**
 * 网络包注册与处理器，对应开发计划 §5.4。
 * <p>
 * NeoForge 1.21.x 使用 {@link PayloadRegistrar} 注册自定义包，
 * 替代旧版 SimpleChannel API。
 * <p>
 * 包流向：
 * <ul>
 *   <li>S2C：{@link HandshakeRequestPayload}（服务端询问客户端是否安装模组）</li>
 *   <li>C2S：{@link HandshakeResponsePayload}（客户端回应握手）</li>
 *   <li>C2S：{@link ClientFeedbackPayload}（客户端定期发送反馈快照）</li>
 * </ul>
 */
public final class SteadyChunksPayloads {

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(SteadyChunksPayloads::onRegisterPayloads);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = new PayloadRegistrar("1");

        // S2C：握手请求
        registrar.playToClient(
                HandshakeRequestPayload.TYPE,
                HandshakeRequestPayload.STREAM_CODEC,
                SteadyChunksPayloads::handleHandshakeRequest
        );

        // C2S：握手回应
        registrar.playToServer(
                HandshakeResponsePayload.TYPE,
                HandshakeResponsePayload.STREAM_CODEC,
                SteadyChunksPayloads::handleHandshakeResponse
        );

        // C2S：客户端反馈
        registrar.playToServer(
                ClientFeedbackPayload.TYPE,
                ClientFeedbackPayload.STREAM_CODEC,
                SteadyChunksPayloads::handleClientFeedback
        );

        SteadyChunks.LOGGER.info("SteadyChunks 网络包已注册");
    }

    /**
     * 服务端处理客户端握手回应。
     */
    private static void handleHandshakeResponse(HandshakeResponsePayload payload, IPayloadContext context) {
        UUID playerId = context.player().getUUID();
        ClientFeedbackAggregator aggregator = ClientFeedbackAggregator.getInstance();
        aggregator.setClientHasMod(playerId, payload.hasMod());

        if (payload.hasMod()) {
            SteadyChunks.LOGGER.info("玩家 {} 客户端已安装 SteadyChunks (protocol={})",
                    playerId, payload.protocolVersion());
        } else {
            SteadyChunks.LOGGER.info("玩家 {} 客户端未安装 SteadyChunks，退化为服务端模式", playerId);
        }
    }

    /**
     * 客户端处理服务端握手请求。
     */
    private static void handleHandshakeRequest(HandshakeRequestPayload payload, IPayloadContext context) {
        // 客户端回应：已安装模组，协议版本匹配
        context.reply(new HandshakeResponsePayload(
                true,
                ClientFeedbackSnapshot.PROTOCOL_VERSION,
                true
        ));
    }

    /**
     * 服务端处理客户端反馈。
     */
    private static void handleClientFeedback(ClientFeedbackPayload payload, IPayloadContext context) {
        ClientFeedbackAggregator aggregator = ClientFeedbackAggregator.getInstance();
        aggregator.receive(payload.toSnapshot());
    }
}
