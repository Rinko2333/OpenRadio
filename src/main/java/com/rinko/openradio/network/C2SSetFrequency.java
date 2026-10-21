package com.rinko.openradio.network;

import com.rinko.openradio.OpenRadio;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：客户端请求修改手持机频率。
 *
 * <p>客户端只上报"意图"，不参与判定。服务端会重新校验玩家手里是否真的拿着电台、
 * 以及频率是否合法，避免客户端伪造。
 */
public record C2SSetFrequency(int frequency) implements CustomPacketPayload {
    public static final Type<C2SSetFrequency> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(OpenRadio.MODID, "set_frequency"));

    public static final StreamCodec<ByteBuf, C2SSetFrequency> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, C2SSetFrequency::frequency,
                    C2SSetFrequency::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
