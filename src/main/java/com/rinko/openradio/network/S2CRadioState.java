package com.rinko.openradio.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.rinko.openradio.OpenRadio;
import com.rinko.openradio.client.ClientRadioState;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C：下发当前玩家的电台状态。
 *
 * <p>Q 本身是成对且定向的，服务端按每条链路单独算。这个包把结果分两部分送到客户端：
 * 一个<b>汇总值</b>（HUD 用）和一串<b>逐说话人链路</b>（语音用）。
 *
 * <p>为什么现在就把逐说话人列表发下去，虽然文本模式用不到 ——
 * 因为语音的音频处理必须知道"正在说话的这个人是多少质量"，而这个数据
 * 只能在服务端算出来。提前把协议定下来，语音接入时就不用再改网络层和状态机。
 *
 * @param quality 与同频电台之间最好那条链路的 Q 值，仅用于 HUD 展示
 * @param heard   能收到（Q &gt; 0）的同频电台数量
 * @param active  该玩家此刻是否有一台开机的电台在手
 * @param links   逐说话人链路，按 Q 从高到低排列
 */
public record S2CRadioState(float quality, int heard, boolean active, List<SpeakerLink> links)
        implements CustomPacketPayload {

    /**
     * 一条"某人 → 我"的链路。
     *
     * @param speaker 说话者
     * @param quality 他的声音抵达我这里的质量
     */
    public record SpeakerLink(UUID speaker, float quality) {
    }

    /**
     * 链路列表的编解码。
     *
     * <p>这里手写而不是用 {@code ByteBufCodecs} 的集合支持，是因为 {@code StreamCodec.of}
     * 拿到的是 netty 的 {@link ByteBuf}，它没有 {@code writeVarInt} / {@code writeUUID}
     * （那是 {@code FriendlyByteBuf} 才有的）。用基础写入即可，一个 UUID 两个 long。
     *
     * <p>数量用单字节存：信道接收者上限是 32，1 字节约 255 个，余量足够。
     */
    private static final StreamCodec<ByteBuf, List<SpeakerLink>> LINK_LIST_CODEC = StreamCodec.of(
            (buf, links) -> {
                buf.writeByte(links.size());
                for (SpeakerLink link : links) {
                    buf.writeLong(link.speaker().getMostSignificantBits());
                    buf.writeLong(link.speaker().getLeastSignificantBits());
                    buf.writeFloat(link.quality());
                }
            },
            buf -> {
                int size = buf.readUnsignedByte();
                List<SpeakerLink> links = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    long most = buf.readLong();
                    long least = buf.readLong();
                    links.add(new SpeakerLink(new UUID(most, least), buf.readFloat()));
                }
                return links;
            });

    public static final Type<S2CRadioState> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(OpenRadio.MODID, "radio_state"));

    public static final StreamCodec<ByteBuf, S2CRadioState> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.FLOAT, S2CRadioState::quality,
                    ByteBufCodecs.VAR_INT, S2CRadioState::heard,
                    ByteBufCodecs.BOOL, S2CRadioState::active,
                    LINK_LIST_CODEC, S2CRadioState::links,
                    S2CRadioState::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(S2CRadioState payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientRadioState.accept(payload));
    }
}
