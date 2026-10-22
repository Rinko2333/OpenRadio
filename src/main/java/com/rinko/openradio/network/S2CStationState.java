package com.rinko.openradio.network;

import java.util.ArrayList;
import java.util.List;

import com.rinko.openradio.OpenRadio;
import com.rinko.openradio.client.ClientStationState;
import com.rinko.openradio.radio.AntennaKind;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C：一台固定台的完整快照。
 *
 * <h2>为什么全量发，而不是增量</h2>
 *
 * <p>只在界面打开期间每秒发 3 次（客户端主动请求），一次约 200 字节 + 收件箱。
 * 增量同步需要服务端记住"这个玩家看到了什么"，那是一份额外的、会出错的状态；
 * 而全量的成本在这个频率下完全可接受。宁可多发几十字节，也不要多一份要维护的一致性。
 *
 * <p>收件箱也一起带过来 —— 它是界面的一部分，拆成第二个包只会让"状态和日志不一致"
 * 变成一个可能出现的 bug。
 *
 * @param pos        固定台位置，客户端据此确认自己看的还是同一台
 * @param frequency  当前频率
 * @param energy     当前电量（FE）
 * @param capacity   电容上限（FE）
 * @param powered    是否开机
 * @param element    顶端振子的种类；{@link AntennaKind#BARE} 表示没接天线
 * @param height     天线总高度（杆 + 振子）
 * @param gain       当前波段上的射程系数
 * @param mismatched 振子是否装错了波段
 * @param quality    能通到的最好那条链路的 Q
 * @param heard      能通到的同频设备数量（含固定台）
 * @param inbox      最近的收信记录，最新的在前
 */
public record S2CStationState(BlockPos pos, int frequency, int energy, int capacity,
                              boolean powered, AntennaKind element, int height, float gain,
                              boolean mismatched, float quality, int heard,
                              List<InboxEntry> inbox) implements CustomPacketPayload {

    /** 一条收信记录。文本已经在服务端按 Q 降级过，客户端直接显示即可。 */
    public record InboxEntry(String senderName, float quality, String text) {
    }

    public static final Type<S2CStationState> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(OpenRadio.MODID, "station_state"));

    /** 收件箱上限与 BE 保持一致；多传的部分没有意义。 */
    private static final int MAX_INBOX = 16;

    private static final StreamCodec<ByteBuf, List<InboxEntry>> INBOX_CODEC = StreamCodec.of(
            (buf, entries) -> {
                buf.writeByte(Math.min(entries.size(), MAX_INBOX));
                for (int i = 0; i < entries.size() && i < MAX_INBOX; i++) {
                    InboxEntry entry = entries.get(i);
                    ByteBufCodecs.STRING_UTF8.encode(buf, entry.senderName());
                    buf.writeFloat(entry.quality());
                    ByteBufCodecs.STRING_UTF8.encode(buf, entry.text());
                }
            },
            buf -> {
                int size = buf.readUnsignedByte();
                List<InboxEntry> entries = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    String sender = ByteBufCodecs.STRING_UTF8.decode(buf);
                    float quality = buf.readFloat();
                    String text = ByteBufCodecs.STRING_UTF8.decode(buf);
                    entries.add(new InboxEntry(sender, quality, text));
                }
                return entries;
            });

    /**
     * 手写编解码而不是 {@code StreamCodec.composite}：字段有 12 个，
     * composite 的重载最多到 8 个左右，硬凑只会更难读。
     */
    public static final StreamCodec<ByteBuf, S2CStationState> STREAM_CODEC = StreamCodec.of(
            (buf, state) -> {
                buf.writeLong(state.pos().asLong());
                buf.writeInt(state.frequency());
                buf.writeInt(state.energy());
                buf.writeInt(state.capacity());
                buf.writeBoolean(state.powered());
                buf.writeByte(state.element().ordinal());
                buf.writeShort(state.height());
                buf.writeFloat(state.gain());
                buf.writeBoolean(state.mismatched());
                buf.writeFloat(state.quality());
                buf.writeByte(Math.min(state.heard(), 255));
                INBOX_CODEC.encode(buf, state.inbox());
            },
            buf -> {
                BlockPos pos = BlockPos.of(buf.readLong());
                int frequency = buf.readInt();
                int energy = buf.readInt();
                int capacity = buf.readInt();
                boolean powered = buf.readBoolean();
                AntennaKind element = AntennaKind.values()[buf.readUnsignedByte()];
                int height = buf.readUnsignedShort();
                float gain = buf.readFloat();
                boolean mismatched = buf.readBoolean();
                float quality = buf.readFloat();
                int heard = buf.readUnsignedByte();
                List<InboxEntry> inbox = INBOX_CODEC.decode(buf);
                return new S2CStationState(pos, frequency, energy, capacity, powered, element,
                        height, gain, mismatched, quality, heard, inbox);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(S2CStationState payload, net.neoforged.neoforge.network.handling.IPayloadContext context) {
        // 纯客户端展示数据，不需要 enqueueWork —— 写的是一个 volatile 引用。
        ClientStationState.accept(payload);
    }
}
