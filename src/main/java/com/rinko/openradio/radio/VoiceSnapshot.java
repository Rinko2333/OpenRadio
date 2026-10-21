package com.rinko.openradio.radio;

import java.util.Map;
import java.util.UUID;

/**
 * 语音层的只读快照：广播半径 + 语音路由表。
 *
 * <p>存在的唯一原因是<b>线程安全</b>。{@code VoiceDistanceEvent} 和
 * {@code MicrophonePacketEvent} 都跑在 SVC 的 {@code VoiceChatPacketProcessingThread} 上，
 * 而不是服务端主线程 —— 在那里直接读 {@link RadioManager} 的可变集合就是数据竞争。
 *
 * <p>所以主线程每个传播周期构造一个<b>不可变</b>快照，整体替换引用。
 * SVC 线程只读不写，天然安全，也不需要任何锁。
 *
 * @param radiusByPlayer  有开机电台的玩家 → 该频率的广播半径；没有电台的玩家不在表里
 * @param routesBySpeaker 说话者 → (听众 → Q)。只有"同频 + 双方都开机 + Q > 0"才在表里。
 *                        语音包就是照这张表逐个转发的。
 */
public record VoiceSnapshot(Map<UUID, Float> radiusByPlayer,
                            Map<UUID, Map<UUID, Float>> routesBySpeaker) {
    public static final VoiceSnapshot EMPTY = new VoiceSnapshot(Map.of(), Map.of());

    /**
     * @return 该玩家的广播半径，0 表示他没有电台（不应该扩大广播范围）
     */
    public float radiusOf(UUID player) {
        Float radius = radiusByPlayer.get(player);
        return radius == null ? 0.0F : radius;
    }

    /**
     * 查"这个人说话时该转发给谁"。
     *
     * @return 听众 → Q 的映射；这个人没有电台、或同频上没人时返回空表
     */
    public Map<UUID, Float> listenersOf(UUID speaker) {
        Map<UUID, Float> listeners = routesBySpeaker.get(speaker);
        return listeners == null ? Map.of() : listeners;
    }
}
