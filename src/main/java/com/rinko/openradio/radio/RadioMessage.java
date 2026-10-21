package com.rinko.openradio.radio;

import net.minecraft.server.level.ServerPlayer;

/**
 * 一条已经通过服务端校验、并且算好逐接收者信号质量的无线电消息。
 *
 * <p>注意这个对象携带的是<b>原文</b>而不是降级后的文本 —— 降级是"投递"环节
 * 的责任，因为每个接收者的 Q 值不同，同一个发送者对不同人说的话，听到的清晰度
 * 也不该一样。
 *
 * @param senderName 发送者显示名
 * @param frequency  发射频率
 * @param quality    这条消息抵达该接收者时的信号质量
 * @param text       原始文本
 */
public record RadioMessage(String senderName, int frequency, double quality, String text) {
}
