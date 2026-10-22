package com.rinko.openradio.network;

import com.rinko.openradio.OpenRadio;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：界面打开时请求一次固定台快照。
 *
 * <p>做成"请求-响应"而不是服务端定时推送：服务端不需要知道谁在看哪台机器。
 * 客户端关掉界面就不再请求，服务端自然停止发送，没有需要清理的订阅状态。
 *
 * <p>客户端每 20 tick 发一次（约 1 秒 3 次），让信号质量这类会变的读数跟上。
 */
public record C2SStationRequest(BlockPos pos) implements CustomPacketPayload {

    public static final Type<C2SStationRequest> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(OpenRadio.MODID, "station_request"));

    public static final StreamCodec<ByteBuf, C2SStationRequest> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, C2SStationRequest::pos,
                    C2SStationRequest::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
