package com.rinko.openradio.spike;

import com.rinko.openradio.OpenRadio;
import com.rinko.openradio.voice.OpenRadioVoicePlugin;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 自动化验证：让"进世界 → 推送 → 录音 → 落盘"全流程不需要人手操作。<b>正式版会删掉。</b>
 *
 * <p>只在环境变量 {@code OPENRADIO_SPIKE_AUTO} 存在时注册，值取 {@code audio} 或 {@code voip}：
 *
 * <pre>
 * OPENRADIO_SPIKE_AUTO=audio ./gradlew runClient --args="--quickPlaySingleplayer 新的世界 (1)"
 * </pre>
 *
 * <h2>为什么要等，而不是玩家一进来就推</h2>
 *
 * <p>SVC 的语音连接是<b>独立于 Minecraft 主连接</b>的 UDP 握手，玩家进世界时它往往还没建好。
 * 这时候 {@code getConnectionOf} 返回 null，音频会推给零个人 —— 表现为"命令成功但没声音"。
 * 所以先等固定时间，再看拿到几个听众；拿到 0 个就退避重试，
 * 而不是相信"玩家已经在世界里了所以 SVC 一定就绪"这种假设。
 *
 * <p>这套重试对手动跑命令也适用：{@code SpikeTransmitter.start} 返回的听众数就是诊断依据。
 */
public final class SpikeAutoRunner {
    /** 进世界后先等这么久：SVC 的 UDP 握手 + 客户端音频初始化都需要时间。 */
    private static final int INITIAL_DELAY_TICKS = 200;
    /** 拿到 0 个听众时的退避间隔。 */
    private static final int RETRY_DELAY_TICKS = 100;
    private static final int MAX_ATTEMPTS = 4;

    private static String mode;
    private static int countdown = -1;
    private static int attempts;
    private static boolean finished;

    public static void register() {
        String configured = System.getenv("OPENRADIO_SPIKE_AUTO");
        if (configured == null || configured.isBlank()) {
            return;
        }
        String normalized = configured.trim().toLowerCase();
        if (!normalized.equals("audio") && !normalized.equals("voip")) {
            OpenRadio.LOGGER.warn("[spike] OPENRADIO_SPIKE_AUTO 只能是 audio 或 voip，收到 {}", configured);
            return;
        }
        mode = normalized;
        OpenRadio.LOGGER.info("[spike] 自动验证已启用，模式 {}", mode);

        NeoForge.EVENT_BUS.addListener(SpikeAutoRunner::onPlayerJoin);
        NeoForge.EVENT_BUS.addListener(SpikeAutoRunner::onServerTick);
    }

    private static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (finished || countdown > 0) {
            return;
        }
        if (!event.getEntity().level().isClientSide()) {
            countdown = INITIAL_DELAY_TICKS;
            OpenRadio.LOGGER.info("[spike] 玩家已进入世界，{} tick 后开始自动推送", countdown);
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (finished || countdown < 0) {
            return;
        }
        if (--countdown > 0) {
            return;
        }

        VoicechatServerApi api = OpenRadioVoicePlugin.serverApi();
        if (api == null) {
            scheduleRetry("SVC 服务端 API 还没就绪");
            return;
        }

        OpusEncoderMode encoderMode = mode.equals("audio") ? OpusEncoderMode.AUDIO : OpusEncoderMode.VOIP;
        int listeners = SpikeTransmitter.start(api, encoderMode);
        attempts++;

        if (listeners <= 0) {
            scheduleRetry("没有可用的 SVC 听众");
            return;
        }
        finished = true;
        OpenRadio.LOGGER.info("[spike] 自动推送完成（第 {} 次尝试，听众 {} 人）", attempts, listeners);
    }

    private static void scheduleRetry(String reason) {
        if (attempts >= MAX_ATTEMPTS) {
            finished = true;
            OpenRadio.LOGGER.warn("[spike] 自动推送放弃：{}（已尝试 {} 次）", reason, attempts);
            return;
        }
        countdown = RETRY_DELAY_TICKS;
        OpenRadio.LOGGER.info("[spike] {}，{} tick 后重试", reason, countdown);
    }

    private SpikeAutoRunner() {
    }
}
