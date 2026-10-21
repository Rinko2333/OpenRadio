package com.rinko.openradio.network;

import com.rinko.openradio.OpenRadio;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：请求设置静噪阈值。
 *
 * <p>静噪本身只影响<b>本地播放</b>，看起来完全可以做成纯客户端设置。之所以仍然发给服务端，
 * 是因为它必须持久化在电台的组件上 —— 只存在客户端的话，重登或换维度就被重置了。
 *
 * <p>服务端只做范围和取值校验，不做任何"该不该静噪"的判定：
 * 那不是安全边界，玩家自己调自己的收音机而已。
 */
public record C2SSetSquelch(float squelch) implements CustomPacketPayload {
    public static final Type<C2SSetSquelch> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(OpenRadio.MODID, "set_squelch"));

    public static final StreamCodec<ByteBuf, C2SSetSquelch> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.FLOAT, C2SSetSquelch::squelch,
                    C2SSetSquelch::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
