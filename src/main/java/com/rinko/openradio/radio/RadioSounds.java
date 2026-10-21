package com.rinko.openradio.radio;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerPlayer;

/**
 * 电台的操作反馈音。
 *
 * <p>刻意全部复用原版音效，不引入任何自定义音频资产 —— 因为接入语音后，
 * 噪声、低通、断音这些连续的声音表现会由音频通道接管，现在做一套自定义噪声循环
 * 会被整体替换掉。这里只保留"按一下就响一声"的离散反馈，这部分不会白做。
 */
public final class RadioSounds {

    public static void powerOn(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_BIT.value(), 0.5F, 1.80F);
    }

    public static void powerOff(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_BIT.value(), 0.4F, 0.70F);
    }

    /** 发射键按下去的"咔"声。 */
    public static void keyDown(ServerPlayer player) {
        play(player, SoundEvents.UI_BUTTON_CLICK.value(), 0.5F, 0.90F);
    }

    /** 静噪：收到载波时的那一声"嘶"。这是无线电最有辨识度的反馈。 */
    public static void squelch(ServerPlayer player) {
        play(player, SoundEvents.UI_BUTTON_CLICK.value(), 0.6F, 1.30F);
    }

    /** 调谐到空频率时的低沉提示。 */
    public static void noCarrier(ServerPlayer player) {
        play(player, SoundEvents.NOTE_BLOCK_BASS.value(), 0.5F, 0.60F);
    }

    private static void play(ServerPlayer player, SoundEvent sound, float volume, float pitch) {
        player.level().playSound(null, player.blockPosition(), sound,
                SoundSource.PLAYERS, volume, pitch);
    }

    private RadioSounds() {
    }
}
