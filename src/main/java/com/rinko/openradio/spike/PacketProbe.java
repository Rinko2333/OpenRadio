package com.rinko.openradio.spike;

import java.util.ArrayList;
import java.util.List;

import com.rinko.openradio.OpenRadio;
import com.rinko.openradio.network.C2SStationConfig;
import com.rinko.openradio.network.C2SStationRequest;
import com.rinko.openradio.network.C2SStationTransmit;
import com.rinko.openradio.network.S2CStationState;
import com.rinko.openradio.radio.AntennaKind;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;

/**
 * 网络包的编解码往返测试。<b>正式版会删掉。</b>
 *
 * <p>手写编解码（因为 {@code ByteBuf} 没有 {@code writeVarInt} / {@code writeUUID}）
 * 是最容易在<b>运行时</b>而不是编译期出错的地方：字段顺序错一位、字符串长度用错类型、
 * 有符号/无符号读错，全都能通过编译，然后在联机时给出一堆乱码或者直接断连。
 *
 * <p>这里构造一份"最坏情况"的数据往返一遍：中文、空字符串、最大长度的收件箱、
 * 负数频率、以及 0 和 255 这种边界值。不需要进世界，{@code commonSetup} 里就能跑。
 */
public final class PacketProbe {

    public static void run() {
        if (System.getenv("OPENRADIO_PROBE_PACKET") == null) {
            return;
        }
        OpenRadio.LOGGER.info("[probe] 网络包编解码往返测试开始");
        boolean ok = true;
        ok &= checkStationState();
        ok &= checkSimplePackets();
        OpenRadio.LOGGER.info("[probe] ---- 编解码测试{} ----", ok ? "全部通过" : "有失败项");
    }

    private static boolean checkStationState() {
        List<S2CStationState.InboxEntry> inbox = new ArrayList<>();
        // 故意塞满 16 条、带中文和空串：长度前缀、UTF-8 多字节、空字符串都要过一遍。
        for (int i = 0; i < 16; i++) {
            inbox.add(new S2CStationState.InboxEntry(
                    i == 0 ? "" : "呼号-" + i,
                    i / 16.0F,
                    i == 1 ? "中文内容测试" : "msg " + i));
        }
        S2CStationState original = new S2CStationState(
                new BlockPos(-30000000, 255, 30000000),
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                true,
                AntennaKind.WIDEBAND,
                16,
                2.62F,
                true,
                1.0F,
                255,
                inbox);

        ByteBuf buffer = Unpooled.buffer();
        S2CStationState.STREAM_CODEC.encode(buffer, original);
        S2CStationState decoded = S2CStationState.STREAM_CODEC.decode(buffer);
        int remaining = buffer.readableBytes();
        buffer.release();

        boolean ok = decoded.pos().equals(original.pos())
                && decoded.frequency() == original.frequency()
                && decoded.energy() == original.energy()
                && decoded.capacity() == original.capacity()
                && decoded.powered() == original.powered()
                && decoded.element() == original.element()
                && decoded.height() == original.height()
                && Math.abs(decoded.gain() - original.gain()) < 1.0E-6F
                && decoded.mismatched() == original.mismatched()
                && Math.abs(decoded.quality() - original.quality()) < 1.0E-6F
                && decoded.heard() == original.heard()
                && decoded.inbox().size() == original.inbox().size()
                && inboxMatches(original.inbox(), decoded.inbox())
                && remaining == 0;

        if (!ok) {
            OpenRadio.LOGGER.warn("[probe] S2CStationState 往返不一致：剩余字节={} 原始收件箱={} 解码后={}",
                    remaining, original.inbox().size(), decoded.inbox().size());
        }
        OpenRadio.LOGGER.info("[probe] S2CStationState 往返：{}（剩余字节 {}）", ok ? "通过" : "失败", remaining);
        return ok;
    }

    private static boolean inboxMatches(List<S2CStationState.InboxEntry> a,
                                        List<S2CStationState.InboxEntry> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).senderName().equals(b.get(i).senderName())
                    || !a.get(i).text().equals(b.get(i).text())
                    || Math.abs(a.get(i).quality() - b.get(i).quality()) > 1.0E-6F) {
                return false;
            }
        }
        return true;
    }

    private static boolean checkSimplePackets() {
        BlockPos pos = new BlockPos(-1000, 64, 1000);

        C2SStationRequest request = new C2SStationRequest(pos);
        ByteBuf b1 = Unpooled.buffer();
        C2SStationRequest.STREAM_CODEC.encode(b1, request);
        C2SStationRequest r1 = C2SStationRequest.STREAM_CODEC.decode(b1);
        boolean ok1 = r1.pos().equals(pos) && b1.readableBytes() == 0;
        b1.release();

        C2SStationConfig config = new C2SStationConfig(pos, 99999, true);
        ByteBuf b2 = Unpooled.buffer();
        C2SStationConfig.STREAM_CODEC.encode(b2, config);
        C2SStationConfig r2 = C2SStationConfig.STREAM_CODEC.decode(b2);
        boolean ok2 = r2.pos().equals(pos) && r2.frequency() == 99999 && r2.powered()
                && b2.readableBytes() == 0;
        b2.release();

        C2SStationTransmit transmit = new C2SStationTransmit(pos, "测试报文 test 123");
        ByteBuf b3 = Unpooled.buffer();
        C2SStationTransmit.STREAM_CODEC.encode(b3, transmit);
        C2SStationTransmit r3 = C2SStationTransmit.STREAM_CODEC.decode(b3);
        boolean ok3 = r3.pos().equals(pos) && r3.text().equals(transmit.text())
                && b3.readableBytes() == 0;
        b3.release();

        OpenRadio.LOGGER.info("[probe] 请求包={} 配置包={} 发射包={}",
                ok1 ? "通过" : "失败", ok2 ? "通过" : "失败", ok3 ? "通过" : "失败");
        return ok1 && ok2 && ok3;
    }

    private PacketProbe() {
    }
}
