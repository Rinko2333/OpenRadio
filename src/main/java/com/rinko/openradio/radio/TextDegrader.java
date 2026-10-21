package com.rinko.openradio.radio;

import net.minecraft.util.RandomSource;

/**
 * 把 Q 值映射成文本损伤。
 *
 * <p>这里做的是"效果模拟"而不是误码率仿真：不建模调制方式、不计算误码，
 * 只保证玩家能从文本的可读程度上直观判断信号质量。
 *
 * <p>具体的 Q → 感官参数换算全部交给 {@link SignalEffects}，这个类只负责把
 * {@code readability} / {@code dropoutRate} 翻译成"错字"和"掉字"。
 * 这样等语音层接入时，两边用的是同一份映射，不会出现文字乱码但声音清晰的矛盾。
 */
public final class TextDegrader {
    /** 用于替换字符的噪声符号集。刻意混入少量字形相近的字符，制造"看错"的效果。 */
    private static final char[] NOISE_CHARS = {
            '#', '@', '%', '&', '*', '~', '?', '/', '\\', '|', '<', '>', '^', '=', '+', '§'
    };

    /** 可读度为 0 时的错字率。不给到 1.0，留一点残字反而更像"听到了一些"。 */
    private static final double MAX_ERROR_RATE = 0.62D;
    /** 断音率到掉字率的折算，避免低质量时整句连字都不剩。 */
    private static final double DROPOUT_TO_CHAR_LOSS = 0.18D;

    /**
     * 按 Q 值降级一段文本。
     *
     * @param text    原文
     * @param quality 0.0–1.0 的信号质量
     * @param random  随机源，每条消息独立
     * @return 降级后的文本
     */
    public static String degrade(String text, double quality, RandomSource random) {
        SignalEffects effects = SignalEffects.of(quality);

        if (effects.readability() <= 0.0D) {
            // 完全听不清：整段变成噪声，长度保留，让接收方知道"有人在说话"。
            StringBuilder garbled = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                garbled.append(text.charAt(i) == ' ' ? ' ' : noise(random));
            }
            return garbled.toString();
        }

        double errorRate = (1.0D - effects.readability()) * MAX_ERROR_RATE;
        double dropRate = effects.dropoutRate() * DROPOUT_TO_CHAR_LOSS;

        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (random.nextDouble() < dropRate) {
                continue;
            }
            if (c != ' ' && random.nextDouble() < errorRate) {
                out.append(noise(random));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static char noise(RandomSource random) {
        return NOISE_CHARS[random.nextInt(NOISE_CHARS.length)];
    }

    private TextDegrader() {
    }
}
