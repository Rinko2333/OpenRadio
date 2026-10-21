package com.rinko.openradio.radio.delivery;

import com.rinko.openradio.radio.RadioMessage;
import com.rinko.openradio.radio.TextDegrader;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;

/**
 * 聊天栏投递通道。M1 用它来验证整条链路，同时它也是"数据模式"的正式呈现方式。
 *
 * <p>降级发生在这一层，而不是发送时 —— 因为同一条消息对不同接收者的 Q 值不同，
 * 每个人"听"到的清晰度也不该一样。
 */
public final class ChatMessageSink implements MessageSink {
    public static final ChatMessageSink INSTANCE = new ChatMessageSink();

    @Override
    public void deliver(ServerPlayer receiver, RadioMessage message) {
        RandomSource random = receiver.getRandom();
        String degraded = TextDegrader.degrade(message.text(), message.quality(), random);

        ChatFormatting bodyStyle = message.quality() < 0.35D
                ? ChatFormatting.DARK_GRAY
                : ChatFormatting.GRAY;

        Component line = Component.literal("[")
                .append(Component.literal(String.format("%05d", message.frequency()))
                        .withStyle(ChatFormatting.AQUA))
                .append(Component.literal("] ")
                        .withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(message.senderName())
                        .withStyle(ChatFormatting.WHITE))
                .append(Component.literal(": ")
                        .withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(degraded).withStyle(bodyStyle));

        receiver.displayClientMessage(line, false);
    }

    private ChatMessageSink() {
    }
}
