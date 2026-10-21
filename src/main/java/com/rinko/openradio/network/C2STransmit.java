package com.rinko.openradio.network;

import com.rinko.openradio.OpenRadio;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：客户端请求发射一条文本报文。
 *
 * <p>这是 M1 的临时发射入口，用来在没有语音的情况下验证整条链路。
 * 接入语音后会新增独立的 PTT 通道，本包保留作为"数据模式"的正式能力。
 */
public record C2STransmit(String message) implements CustomPacketPayload {
    public static final Type<C2STransmit> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(OpenRadio.MODID, "transmit"));

    public static final StreamCodec<ByteBuf, C2STransmit> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, C2STransmit::message,
                    C2STransmit::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
