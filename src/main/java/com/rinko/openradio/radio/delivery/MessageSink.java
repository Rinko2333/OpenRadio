package com.rinko.openradio.radio.delivery;

import com.rinko.openradio.radio.RadioMessage;

import net.minecraft.server.level.ServerPlayer;

/**
 * 投递通道抽象。
 *
 * <p>这是整个 M1 里为语音系统预留的最关键的一个接口：发送链路
 * （校验 → 找同频 → 逐接收者算 Q）完全不关心消息最后以什么形式抵达，
 * 换一条投递通道就等于换一种通信形态。
 *
 * <ul>
 *   <li>{@link ChatMessageSink} —— 现在的实现，按 Q 值降级文本后进聊天栏。</li>
 *   <li>将来的 VoiceSink —— 忽略 {@code text}，用 {@code quality} 驱动
 *       噪声音量、低通截止频率与断音。</li>
 * </ul>
 */
public interface MessageSink {
    /**
     * 把消息投递给单个接收者。
     *
     * @param receiver 接收者
     * @param message  消息。通道可以自由决定读 {@code text} 还是只读 {@code quality}
     */
    void deliver(ServerPlayer receiver, RadioMessage message);
}
