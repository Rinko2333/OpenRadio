package com.rinko.openradio.network;

import com.rinko.openradio.OpenRadio;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：请求设置开关机状态。
 *
 * <p>用"设置"而不是"切换"，因为这是幂等的 —— 网络重发或界面刷新都不会
 * 让电台莫名其妙地反向开关。
 */
public record C2SSetPowered(boolean powered) implements CustomPacketPayload {
    public static final Type<C2SSetPowered> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(OpenRadio.MODID, "set_powered"));

    public static final StreamCodec<ByteBuf, C2SSetPowered> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, C2SSetPowered::powered,
                    C2SSetPowered::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
