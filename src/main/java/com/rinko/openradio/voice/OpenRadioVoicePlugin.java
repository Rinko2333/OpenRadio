package com.rinko.openradio.voice;

import java.util.Map;
import java.util.UUID;

import com.rinko.openradio.Config;
import com.rinko.openradio.OpenRadio;
import com.rinko.openradio.client.VoiceClientHandlers;
import com.rinko.openradio.radio.RadioManager;

import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoiceDistanceEvent;
import de.maxhenkel.voicechat.api.packets.StaticSoundPacket;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * Simple Voice Chat 集成。SVC 在这里被当作<b>纯传输层</b>使用：
 * 包收发、AES 加密、NAT 穿透、Opus 编解码全部交给它，我们只接管两个决策点 ——
 * <b>发给谁</b>和<b>收到后多清楚</b>。
 *
 * <h2>为什么不能直接用 SVC 的邻近语音</h2>
 *
 * <p>SVC 的 {@code PlayerSoundPacket} 在客户端是<b>按实体定位</b>的：收到包后先
 * {@code level.getPlayerByUUID(sender)} 找到说话者实体，再按两者距离算音量。找不到实体
 * 就把这一帧丢掉 —— 连插件回调都不会触发。而实体是按视距跟踪的，超过一百多格
 * 说话者的实体在听众客户端上根本不存在，SVC 的原版路径必然断掉。
 *
 * <p>这和 {@code max_voice_distance} 无关 —— 那个值我们早就撑到频段射程了。
 * 症状很有辨识度：<b>文字能收到、UI 显示链路正常、语音完全没声音</b>。
 *
 * <h2>解法：服务端转发静态音频</h2>
 *
 * <p>{@link MicrophonePacketEvent} 能拿到原始 Opus 帧。转成 {@link StaticSoundPacket}
 * （非定位音频）按链路表逐个发给听众：
 *
 * <ul>
 *   <li>静态包在客户端走 {@code GroupSoundPacket} 分支，<b>完全不查实体</b>，多远都能响；</li>
 *   <li>channelId 就是说话者 UUID，客户端据此回调
 *       {@code ClientReceiveSoundEvent.StaticSound#getId()}，正好用来查 Q；</li>
 *   <li>非定位正是电台该有的听感 —— 声音来自手里的电台，不来自某个方向。</li>
 * </ul>
 *
 * <p>代价是不在链路上的人一点声音都收不到，所以必须同时取消
 * {@link MicrophonePacketEvent} 掐掉原版广播，否则近处会听到两份叠加的音频。
 *
 * <p><b>线程安全</b>：这些回调都不在主线程 —— 前两个在 SVC 的
 * {@code VoiceChatPacketProcessingThread}，最后两个在每个音频通道自己的线程上。
 * 所以都只读不可变快照（{@link RadioManager#voiceSnapshot()} / {@code ClientRadioState}），
 * 不碰世界、不碰可变集合。
 */
@ForgeVoicechatPlugin
public class OpenRadioVoicePlugin implements VoicechatPlugin {
    /** 服务端 API。客户端上为 null —— 麦克风包和转发都只在服务端有意义。 */
    private VoicechatServerApi serverApi;

    /** 转发诊断日志的上一次输出时间，避免每个语音包刷一行。只在包处理线程访问。 */
    private long lastRelayLog;

    @Override
    public String getPluginId() {
        return OpenRadio.MODID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        if (api instanceof VoicechatServerApi server) {
            serverApi = server;
            OpenRadio.LOGGER.info("[voice] SVC 集成已加载（服务端），原始语音距离 = {}",
                    server.getVoiceChatDistance());
        } else {
            OpenRadio.LOGGER.info("[voice] SVC 集成已加载（客户端）");
        }
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        // 兜底路径：接管模式关掉时退回 SVC 原版距离语音，这个钩子把范围撑到频段射程。
        registration.registerEvent(VoiceDistanceEvent.class, this::onVoiceDistance);

        if (serverApi != null) {
            registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
        }

        if (FMLEnvironment.dist.isClient()) {
            // 客户端处理在单独的类里，避免专用服务器加载插件时解析客户端类型。
            registration.registerEvent(ClientReceiveSoundEvent.EntitySound.class,
                    VoiceClientHandlers::onEntitySound);
            registration.registerEvent(ClientReceiveSoundEvent.StaticSound.class,
                    VoiceClientHandlers::onStaticSound);
        }
    }

    // ------------------------------------------------------------ 服务端

    /**
     * 只做"扩大范围"，绝不缩小。接管模式下其实用不上（麦克风包被我们截走了），
     * 保留是为了 {@code replaceVanillaBehavior = false} 时仍能覆盖远距离。
     */
    private void onVoiceDistance(VoiceDistanceEvent event) {
        if (!active()) {
            return;
        }
        ServerPlayer sender = event.getSenderConnection().getPlayer();
        if (sender == null) {
            return;
        }
        float radius = RadioManager.voiceSnapshot().radiusOf(sender.getUuid());
        if (radius > event.getDistance()) {
            event.setDistance(radius);
        }
    }

    /**
     * 语音包入口：按链路表逐个转发，然后掐掉原版广播。
     *
     * <p>跑在 SVC 的包处理线程上，只能读不可变快照。
     */
    private void onMicrophonePacket(MicrophonePacketEvent event) {
        if (!active()) {
            return;
        }
        VoicechatConnection connection = event.getSenderConnection();
        if (connection == null || event.getPacket() == null) {
            return;
        }
        ServerPlayer sender = connection.getPlayer();
        if (sender == null) {
            return;
        }

        Map<UUID, Float> listeners =
                RadioManager.voiceSnapshot().listenersOf(sender.getUuid());

        if (!listeners.isEmpty()) {
            // 同一个包发给多人：底下的 GroupSoundPacket 不可变，可以复用同一份。
            StaticSoundPacket packet = event.getPacket().toStaticSoundPacket();
            int sent = 0;
            for (UUID listener : listeners.keySet()) {
                VoicechatConnection target = serverApi.getConnectionOf(listener);
                if (target == null || !target.isConnected() || target.isDisabled()) {
                    continue;
                }
                serverApi.sendStaticSoundPacketTo(target, packet);
                sent++;
            }
            logRelay(sent);
        } else {
            logRelay(0);
        }

        // 无论有没有电台都掐掉原版邻近广播：
        // 有电台的话已经手动转发过（不掐会听到两份叠加），
        // 没电台的话这正是"完全替代"的语义 —— 不在无线电链路上就没有语音。
        event.cancel();
    }

    /**
     * 转发诊断：每 5 秒最多一行。语音出问题时，先看这行有没有出现、人数对不对 ——
     * 「0 人」说明链路表里没有听众（频率不同 / 超出射程 / 对方没开机),
     * 「N 人」但听不到则问题在客户端接收侧。
     */
    private void logRelay(int sent) {
        long now = System.currentTimeMillis();
        if (now - lastRelayLog < 5_000L) {
            return;
        }
        lastRelayLog = now;
        OpenRadio.LOGGER.info("[voice] 转发一帧语音给 {} 人", sent);
    }

    /** 总开关 + 接管开关，两者都开才动手。 */
    private static boolean active() {
        return Config.VOICE_ENABLED.get() && Config.VOICE_REPLACE_VANILLA.get();
    }
}
