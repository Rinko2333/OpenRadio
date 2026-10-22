package com.rinko.openradio.client;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.rinko.openradio.OpenRadio;
import com.rinko.openradio.spike.SpikeSignal;
import com.rinko.openradio.spike.WavWriter;

import net.neoforged.fml.loading.FMLPaths;

/**
 * M6 可行性验证：把收到的 spike 音频落盘。<b>正式版会删掉。</b>
 *
 * <p>没有它就无法测量 Opus 的损伤 —— 只有"听得出有点糊"这种主观描述，
 * 而我们需要知道 2300Hz 到底衰减了几个 dB、扫频有没有被抹平。
 *
 * <h2>两个刻意的处理</h2>
 *
 * <ul>
 *   <li><b>不进电台 DSP</b>：spike 音频直接原样播放。走 DSP 会引入
 *       我们自己的低通和噪声，测量结果就不再是"Opus 做了什么"，而是"Opus + 我们做了什么"。</li>
 *   <li><b>立即 clone</b>：{@code getRawAudio()} 返回的数组归 SVC 所有，
 *       它可能在下一帧被复用。攒引用而不复制，最后写出来的会是一堆重复的末帧。</li>
 * </ul>
 */
public final class SpikeRecorder {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** 这个时长没有新帧就认为这一段播完了。丢帧也能落盘，不依赖"收齐"这种脆弱条件。 */
    private static final long QUIET_MILLIS = 900L;

    private static final List<short[]> FRAMES = Collections.synchronizedList(new ArrayList<>());

    private static volatile String mode;
    private static volatile long lastFrameAt;

    public static boolean isSpikeChannel(UUID id) {
        return SpikeSignal.CHANNEL_AUDIO.equals(id) || SpikeSignal.CHANNEL_VOIP.equals(id);
    }

    /** 在 SVC 的音频线程上调用，必须只做最轻的事。 */
    public static void capture(UUID channelId, short[] audio) {
        if (audio == null || audio.length == 0) {
            return;
        }
        mode = SpikeSignal.CHANNEL_AUDIO.equals(channelId) ? "audio" : "voip";
        FRAMES.add(audio.clone());
        lastFrameAt = System.currentTimeMillis();
    }

    /** 客户端 tick 调用。写文件放在这里，不占音频线程。 */
    public static void clientTick() {
        if (FRAMES.isEmpty()) {
            return;
        }
        if (System.currentTimeMillis() - lastFrameAt < QUIET_MILLIS) {
            return;
        }
        flush();
    }

    private static void flush() {
        List<short[]> frames;
        String label;
        // synchronizedList 的迭代必须自己加锁，否则并发 add 会抛 ConcurrentModificationException。
        synchronized (FRAMES) {
            frames = new ArrayList<>(FRAMES);
            FRAMES.clear();
            label = mode == null ? "unknown" : mode;
        }

        int total = 0;
        for (short[] frame : frames) {
            total += frame.length;
        }
        short[] merged = new short[total];
        int cursor = 0;
        for (short[] frame : frames) {
            System.arraycopy(frame, 0, merged, cursor, frame.length);
            cursor += frame.length;
        }

        Path file = FMLPaths.GAMEDIR.get()
                .resolve("spike")
                .resolve("rx-" + label + "-" + LocalDateTime.now().format(STAMP) + ".wav");
        try {
            WavWriter.write(file, merged, SpikeSignal.SAMPLE_RATE);
            OpenRadio.LOGGER.info("[spike] 收到 {} 帧 / {} 样本（预期 {} 帧），已写入 {}",
                    frames.size(), total, SpikeSignal.totalFrames(), file);
        } catch (IOException e) {
            OpenRadio.LOGGER.warn("[spike] 接收音频写入失败", e);
        }
    }

    private SpikeRecorder() {
    }
}
