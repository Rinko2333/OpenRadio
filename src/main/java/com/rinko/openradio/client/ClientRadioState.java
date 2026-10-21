package com.rinko.openradio.client;

import java.util.List;
import java.util.UUID;

import com.rinko.openradio.network.S2CRadioState;

/**
 * 客户端持有的电台状态。M1 里供面板显示，接语音后它就是音频处理的输入。
 *
 * <p>注意 {@link #quality()} 是<b>汇总值</b>：与同频电台之间最好那条链路的 Q。
 * 真正决定"这句话能不能听清"的是逐条链路的 Q，那要等语音层引入逐说话人状态才会下发给客户端。
 */
public final class ClientRadioState {
    private static volatile float quality;
    private static volatile int heard;
    private static volatile boolean active;
    /** 逐说话人链路。文本模式用不到，它是为语音音频处理准备的数据源。 */
    private static volatile List<S2CRadioState.SpeakerLink> links = List.of();

    public static void accept(S2CRadioState payload) {
        quality = payload.quality();
        heard = payload.heard();
        active = payload.active();
        links = payload.links().isEmpty() ? List.of() : List.copyOf(payload.links());
    }

    public static float quality() {
        return quality;
    }

    public static int heard() {
        return heard;
    }

    public static boolean active() {
        return active;
    }

    public static List<S2CRadioState.SpeakerLink> links() {
        return links;
    }

    /**
     * 查某个说话者到我的链路质量。
     *
     * <p>这是语音层接入时要调的方法：拿到 SVC 音频通道对应的玩家 UUID，
     * 用它去查这条链路该用多少噪声、多低的低通、多少断音。
     *
     * @return 0.0–1.0 的质量，链路上没有这个人时返回 0
     */
    public static float qualityFrom(UUID speaker) {
        for (S2CRadioState.SpeakerLink link : links) {
            if (link.speaker().equals(speaker)) {
                return link.quality();
            }
        }
        return 0.0F;
    }

    /** Q 值对应的可读等级，返回语言文件的键。 */
    public static String qualityKey() {
        if (quality >= 0.80F) {
            return "gui.openradio.quality.clear";
        }
        if (quality >= 0.50F) {
            return "gui.openradio.quality.fair";
        }
        if (quality >= 0.20F) {
            return "gui.openradio.quality.poor";
        }
        return "gui.openradio.quality.unusable";
    }

    private ClientRadioState() {
    }
}
