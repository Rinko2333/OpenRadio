package com.rinko.openradio.client;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.rinko.openradio.Config;
import com.rinko.openradio.radio.SignalEffects;
import com.rinko.openradio.voice.RadioVoiceProcessor;
import com.rinko.openradio.voice.VoiceChannelState;

import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

/**
 * 语音接收端的音频处理。
 *
 * <p>单独成类只为一个原因：<b>专用服务器上没有 {@code net.minecraft.client.*}</b>。
 * 把这段放进插件类会让服务端在加载插件时解析客户端类型，直接
 * {@code NoClassDefFoundError}。放在这里，只要服务端不碰它就不会被加载。
 *
 * <p>两条接收路径：
 * <ul>
 *   <li>{@link #onEntitySound} —— SVC 原版路径，只在接管模式关闭时出现；</li>
 *   <li>{@link #onStaticSound} —— 接管模式下的正式路径，服务端转发过来的非定位音频。</li>
 * </ul>
 */
public final class VoiceClientHandlers {
    /** 按说话人保存滤波器状态。同一个 UUID 不会被两个线程同时处理。 */
    private static final Map<UUID, VoiceChannelState> CHANNELS = new ConcurrentHashMap<>();
    /** 上限，防止长时间运行后无限增长。超了就整体丢掉重建（滤波器重置无害）。 */
    private static final int MAX_CHANNELS = 64;

    /** SVC 原版路径。此时说话者实体一定在视距内，所以能走到这里。 */
    public static void onEntitySound(ClientReceiveSoundEvent.EntitySound event) {
        apply(event.getEntityId(), event.getRawAudio(), event::setRawAudio);
    }

    /**
     * 接管模式下的路径：服务端转发过来的静态音频。
     *
     * <p>{@code channelId} 就是说话者 UUID。但静态音频是所有 addon 共用的通道类型，
     * 所以先确认这个 id 是本服在线玩家 —— 不是的话原样放过，不能把别人的音频也静音了。
     */
    public static void onStaticSound(ClientReceiveSoundEvent.StaticSound event) {
        UUID speaker = event.getId();
        if (speaker == null || !isOnlinePlayer(speaker)) {
            return;
        }
        apply(speaker, event.getRawAudio(), event::setRawAudio);
    }

    /** 两个入口共用的 DSP 链。 */
    private static void apply(UUID speaker, short[] audio, Consumer<short[]> out) {
        if (!active() || speaker == null || audio == null || audio.length == 0) {
            return;
        }

        if (Config.VOICE_DEBUG_SILENCE.get()) {
            out.accept(RadioVoiceProcessor.silence(audio.length));
            return;
        }

        // links 里只有"同频 + 双方都开机"的人。查不到 = 不在链路上 = 静音。
        float quality = ClientRadioState.qualityFrom(speaker);
        if (quality <= 0.0F) {
            out.accept(RadioVoiceProcessor.silence(audio.length));
            return;
        }

        // 静噪：低于阈值就当作根本没有这个信号，连噪声也不输出。
        // 逐说话人判定 —— 真机的静噪也是对"当前这个信号"起作用的，
        // 不是对整条信道。所以邻居很响不代表远处那个人能挤进来。
        if (quality < ClientRadioState.squelch()) {
            out.accept(RadioVoiceProcessor.silence(audio.length));
            return;
        }

        out.accept(RadioVoiceProcessor.process(
                audio, SignalEffects.of(quality), stateFor(speaker)));
    }

    /**
     * @return 这个 UUID 是不是本服在线玩家。用玩家列表判断，不依赖实体是否加载 ——
     *         而"实体没加载"恰恰就是这套转发要解决的问题。
     */
    private static boolean isOnlinePlayer(UUID id) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return false;
        }
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            if (info.getProfile().getId().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static VoiceChannelState stateFor(UUID speaker) {
        VoiceChannelState existing = CHANNELS.get(speaker);
        if (existing != null) {
            return existing;
        }
        if (CHANNELS.size() >= MAX_CHANNELS) {
            CHANNELS.clear();
        }
        VoiceChannelState created = new VoiceChannelState();
        VoiceChannelState raced = CHANNELS.putIfAbsent(speaker, created);
        return raced != null ? raced : created;
    }

    private static boolean active() {
        return Config.VOICE_ENABLED.get() && Config.VOICE_REPLACE_VANILLA.get();
    }

    private VoiceClientHandlers() {
    }
}
