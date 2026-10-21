package com.rinko.openradio.energy;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 语音发射的耗电记账。
 *
 * <p>存在的唯一原因是<b>线程模型</b>：语音按帧计费最准，而帧回调
 * （{@code MicrophonePacketEvent}）跑在 SVC 的 {@code VoiceChatPacketProcessingThread} 上，
 * 那里碰 {@code ItemStack} 和背包就是数据竞争。
 *
 * <p>所以包线程只做一件事：往这个账本里累加"这次欠了多少 FE"。
 * 主线程每个传播周期结算一次，真正写进电池。
 *
 * <h2>为什么要按帧攒而不是每帧直接扣</h2>
 *
 * <p>一帧 20ms，即每秒 50 帧。按默认 40 FE/s 算，每帧 0.8 FE —— 直接取整就是 0，
 * 语音会变成完全免费。所以先数帧，满 50 帧（正好 1 秒）才记一次整数账。
 */
public final class VoicePowerLedger {
    private static final int FRAMES_PER_SECOND = 50;

    /** 待结算的电量（FE）。 */
    private static final Map<UUID, AtomicInteger> PENDING = new ConcurrentHashMap<>();
    /** 帧计数器，满一秒结算一次。 */
    private static final Map<UUID, AtomicInteger> FRAMES = new ConcurrentHashMap<>();

    /**
     * 记一帧语音。可以从任意线程调用。
     *
     * @param energyPerSecond 该玩家当前的语音功耗配置（FE/s）
     */
    public static void onVoiceFrame(UUID player, int energyPerSecond) {
        if (energyPerSecond <= 0) {
            return;
        }
        AtomicInteger frames = FRAMES.computeIfAbsent(player, key -> new AtomicInteger());
        if (frames.incrementAndGet() < FRAMES_PER_SECOND) {
            return;
        }
        frames.addAndGet(-FRAMES_PER_SECOND);
        PENDING.computeIfAbsent(player, key -> new AtomicInteger()).addAndGet(energyPerSecond);
    }

    /**
     * 取出并清空欠账。
     *
     * <p>用 remove 而不是 get+set：拿走之后立刻从表里消失，
     * 不会因为玩家在这个周期内退出而留下一条永远不清的账。
     */
    public static int take(UUID player) {
        AtomicInteger counter = PENDING.remove(player);
        return counter == null ? 0 : counter.get();
    }

    public static void clear() {
        PENDING.clear();
        FRAMES.clear();
    }

    private VoicePowerLedger() {
    }
}
