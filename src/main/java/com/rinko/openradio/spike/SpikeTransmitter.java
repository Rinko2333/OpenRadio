package com.rinko.openradio.spike;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.rinko.openradio.OpenRadio;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * M6 可行性验证：把一段自己生成的音频通过 SVC 推给在线玩家。<b>正式版会删掉。</b>
 *
 * <h2>它验证的是 M6 的地基</h2>
 *
 * <p>此前我们只用过 {@code MicrophonePacketEvent} 转发<b>别人的</b>麦克风帧。
 * SSTV 需要的是完全不同的能力：<b>服务端自己生成音频并推出去</b>。
 * 这条路依赖三个 API，缺一个 SSTV 就得换方案：
 *
 * <pre>
 * createStaticAudioChannel(UUID)             建一条固定通道
 * StaticAudioChannel.addTarget(connection)   指定听众
 * createAudioPlayer(channel, encoder, 取帧)   由 SVC 按 20ms 节奏取帧并编码
 * </pre>
 *
 * <p>反编译确认了两个关键行为：
 * <ul>
 *   <li>{@code AudioPlayerImpl} 是<b>独立线程</b>，用 {@code startNano + frameIndex * 20ms}
 *       排定每一帧的时刻，不是简单 sleep —— 所以它不会累积漂移。
 *       这正是"时钟在服务端"所需要的性质。</li>
 *   <li>取帧返回 {@code null} 表示播放结束；返回长度不是 960 的数组会直接报错停止。</li>
 * </ul>
 *
 * <h2>为什么同时写一份参考文件</h2>
 *
 * <p>测量 Opus 损伤必须有"发出去的原始波形"作为基准。服务端把原始 PCM 落盘，
 * 客户端把收到的落盘，两份做频谱对比才能看出编码器对哪个频段做了什么。
 */
public final class SpikeTransmitter {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * 开始一次验证。
     *
     * @return 成功拿到 SVC 连接的听众数量；0 表示只有发送方自己在听或者没人装 SVC
     */
    public static int start(VoicechatServerApi api, OpusEncoderMode mode) {
        short[] waveform = SpikeSignal.build();
        UUID channelId = mode == OpusEncoderMode.AUDIO ? SpikeSignal.CHANNEL_AUDIO : SpikeSignal.CHANNEL_VOIP;
        String label = mode == OpusEncoderMode.AUDIO ? "audio" : "voip";

        writeReference(waveform, label);

        StaticAudioChannel channel = api.createStaticAudioChannel(channelId);
        int targets = attachTargets(api, channel);

        // 编码器模式是关键：SSTV 副载波在 1.5–2.3kHz，VOIP 模式会把这个区间削掉。
        // 两种模式各跑一次，就是为了拿到这个差别有多大。
        OpusEncoder encoder = api.createEncoder(mode);
        AudioPlayer player = api.createAudioPlayer(channel, encoder, frames(waveform));
        // encoder 是本地资源，播放线程结束时必须关掉，否则每次测试漏一个 native 句柄。
        player.setOnStopped(encoder::close);
        player.startPlaying();

        OpenRadio.LOGGER.info("[spike] 开始推送测试音频：模式={} 时长={} 听众={} 人",
                label, SpikeSignal.describe(), targets);
        return targets;
    }

    /** 把原始波形落盘，作为对比基准。失败不影响推送 —— 验证本身比留档重要。 */
    private static void writeReference(short[] waveform, String label) {
        Path file = FMLPaths.GAMEDIR.get()
                .resolve("spike")
                .resolve("tx-" + label + "-" + LocalDateTime.now().format(STAMP) + ".wav");
        try {
            WavWriter.write(file, waveform, SpikeSignal.SAMPLE_RATE);
            OpenRadio.LOGGER.info("[spike] 参考波形已写入 {}", file);
        } catch (IOException e) {
            OpenRadio.LOGGER.warn("[spike] 参考波形写入失败", e);
        }
    }

    /** 发给所有在线且 SVC 已连接的玩家，包括命令执行者自己 —— 单人也能测。 */
    private static int attachTargets(VoicechatServerApi api, StaticAudioChannel channel) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return 0;
        }
        int attached = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            VoicechatConnection connection = api.getConnectionOf(player.getUUID());
            if (connection == null || !connection.isConnected() || connection.isDisabled()) {
                continue;
            }
            channel.addTarget(connection);
            attached++;
        }
        return attached;
    }

    /**
     * 按 960 样本一帧取波形。
     *
     * <p>只有 {@code AudioPlayer} 的播放线程会调用它，所以用 {@link AtomicInteger} 做游标
     * 就足够了 —— 不需要锁，也不会被并发调用。
     *
     * <p>最后一帧不足 960 时补零而不是返短数组：SVC 会校验长度，
     * 返短数组会直接报 "invalid audio frame size" 并停掉播放。
     */
    private static Supplier<short[]> frames(short[] waveform) {
        AtomicInteger cursor = new AtomicInteger();
        return () -> {
            int from = cursor.getAndAdd(SpikeSignal.FRAME_SIZE);
            if (from >= waveform.length) {
                return null;
            }
            short[] frame = new short[SpikeSignal.FRAME_SIZE];
            int count = Math.min(SpikeSignal.FRAME_SIZE, waveform.length - from);
            System.arraycopy(waveform, from, frame, 0, count);
            return frame;
        };
    }

    private SpikeTransmitter() {
    }
}
