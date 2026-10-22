package com.rinko.openradio.network;

import com.rinko.openradio.OpenRadio;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：用固定台发射一条文本。
 *
 * <p>文本长度在服务端会被裁剪（{@code Config.MAX_MESSAGE_LENGTH}），
 * 这里不做限制 —— 客户端校验只是体验，不是防线。
 */
public record C2SStationTransmit(BlockPos pos, String text) implements CustomPacketPayload {

    public static final Type<C2SStationTransmit> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(OpenRadio.MODID, "station_transmit"));

    public static final StreamCodec<ByteBuf, C2SStationTransmit> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, C2SStationTransmit::pos,
                    ByteBufCodecs.STRING_UTF8, C2SStationTransmit::text,
                    C2SStationTransmit::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
