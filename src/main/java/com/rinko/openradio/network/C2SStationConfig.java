package com.rinko.openradio.network;

import com.rinko.openradio.OpenRadio;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：设置固定台的频率与开关。
 *
 * <p>两个字段合在一个包里：界面里它们是同一组"状态"，分开传会出现
 * "频率改了但开关还是旧的"这种中途状态。合并之后一次写入是原子的。
 *
 * <p>服务端会重新校验频率合法性并核对距离 —— 客户端说的任何东西都不可信。
 */
public record C2SStationConfig(BlockPos pos, int frequency, boolean powered)
        implements CustomPacketPayload {

    public static final Type<C2SStationConfig> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(OpenRadio.MODID, "station_config"));

    public static final StreamCodec<ByteBuf, C2SStationConfig> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, C2SStationConfig::pos,
                    ByteBufCodecs.VAR_INT, C2SStationConfig::frequency,
                    ByteBufCodecs.BOOL, C2SStationConfig::powered,
                    C2SStationConfig::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
