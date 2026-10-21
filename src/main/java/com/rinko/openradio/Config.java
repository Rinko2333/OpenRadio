package com.rinko.openradio;

import java.util.List;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 通用配置。所有平衡参数都放这里，服务器管理员可调。
 */
public final class Config {
    public static final ModConfigSpec SPEC;

    // ---------------------------------------------------------- 调度

    /** 传播计算的调度间隔（tick）。越大越省性能，越小信号条越跟手。 */
    public static final ModConfigSpec.IntValue PROPAGATION_INTERVAL_TICKS;

    /** 每个 tick 允许花在传播计算上的时间预算（微秒），超预算的任务顺延到下一 tick。 */
    public static final ModConfigSpec.IntValue TICK_BUDGET_MICROS;

    /** 单次体素射线遮挡采样的最大步数，防止长距离射线性能爆炸。 */
    public static final ModConfigSpec.IntValue MAX_RAY_STEPS;

    /** Q 值变化超过该阈值才向客户端同步，避免每 tick 刷包。 */
    public static final ModConfigSpec.DoubleValue Q_SYNC_THRESHOLD;

    // ---------------------------------------------------------- 传播

    /** 所有频段射程的统一缩放系数。想整体拉远/拉近通信距离时调这个。 */
    public static final ModConfigSpec.DoubleValue RANGE_MULTIPLIER;

    /** 每个不透明方块造成的信号损失比例（默认值，可被覆盖表逐方块改写）。 */
    public static final ModConfigSpec.DoubleValue BLOCK_LOSS_PER_BLOCK;

    /** 遮挡损失的封顶比例。即使隔着一整座山，也不会完全归零。 */
    public static final ModConfigSpec.DoubleValue MAX_BLOCK_LOSS;

    /** 逐方块的损失覆盖表，格式 "minecraft:block_id=0.05"。 */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> BLOCK_LOSS_OVERRIDES;

    /** 下雨时的基础损失（会乘以频段的天气敏感度）。 */
    public static final ModConfigSpec.DoubleValue WEATHER_LOSS_RAIN;

    /** 雷暴时的基础损失（会乘以频段的天气敏感度）。 */
    public static final ModConfigSpec.DoubleValue WEATHER_LOSS_THUNDER;

    // ---------------------------------------------------------- 信道

    /** 服务端广播一条无线电消息时，同频接收者的最大数量。 */
    public static final ModConfigSpec.IntValue MAX_RECEIVERS_PER_CHANNEL;

    /** 单条无线电消息允许的最大字符数。 */
    public static final ModConfigSpec.IntValue MAX_MESSAGE_LENGTH;

    /** 同一个玩家两次发射之间的最小间隔（tick），用于反刷屏。 */
    public static final ModConfigSpec.IntValue TRANSMIT_COOLDOWN_TICKS;

    // ---------------------------------------------------------- 效果映射

    /** Q 高于此值视为完全清晰，不做任何降级。 */
    public static final ModConfigSpec.DoubleValue EFFECT_CLEAR_THRESHOLD;

    /** Q 低于此值视为完全不可用，文本全乱码。 */
    public static final ModConfigSpec.DoubleValue EFFECT_UNUSABLE_THRESHOLD;

    /** 可读度为 0 时的低通截止频率（Hz）。越低越闷、越难分辨。 */
    public static final ModConfigSpec.IntValue LOWPASS_MIN_HZ;

    /** 可读度为 1 时的低通截止频率（Hz）。 */
    public static final ModConfigSpec.IntValue LOWPASS_MAX_HZ;

    /** 可读度为 0 时的断音率。语音层用它决定丢包/断音的比例。 */
    public static final ModConfigSpec.DoubleValue MAX_DROPOUT_RATE;

    // ---------------------------------------------------------- 语音

    /** 语音功能总开关。关闭后本 mod 完全不干预 Simple Voice Chat。 */
    public static final ModConfigSpec.BooleanValue VOICE_ENABLED;

    /**
     * 是否由电台接管全部语音。
     *
     * <p>开启时 SVC 原本的"贴近才能听见"行为被完全移除：不在无线电链路上的人听不到，
     * 在链路上的人按 Q 值劣化。关闭时退回 SVC 原版的距离语音，本 mod 只做文本。
     */
    public static final ModConfigSpec.BooleanValue VOICE_REPLACE_VANILLA;

    /** 验证用：把所有收到的语音静音。确认钩子生效后应关闭并删除。 */
    public static final ModConfigSpec.BooleanValue VOICE_DEBUG_SILENCE;

    /** 白噪声的基准幅度（相对满量程）。noiseFloor 为 1 时实际叠加的噪声强度。 */
    public static final ModConfigSpec.DoubleValue VOICE_NOISE_GAIN;

    /** 电台音色的高通截止频率（Hz）。真实电台没有低频，300Hz 左右最像。 */
    public static final ModConfigSpec.IntValue VOICE_HIGHPASS_HZ;

    /** 可读度为 0 时保留的音量比例。避免完全听不见，只留下"很远的闷响"。 */
    public static final ModConfigSpec.DoubleValue VOICE_MIN_GAIN;

    /** VoiceDistanceEvent 允许把语音广播到的最远距离上限（方块）。 */
    public static final ModConfigSpec.IntValue VOICE_MAX_RADIUS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("信号传播与计算调度").push("propagation");
        PROPAGATION_INTERVAL_TICKS = builder
                .comment("传播计算的调度间隔（tick）。默认 10 = 每半秒一次。")
                .defineInRange("intervalTicks", 10, 1, 200);
        TICK_BUDGET_MICROS = builder
                .comment("每 tick 传播计算的时间预算（微秒）。超出的任务顺延到下一 tick。")
                .defineInRange("tickBudgetMicros", 2000, 100, 50_000);
        MAX_RAY_STEPS = builder
                .comment("体素射线遮挡采样的最大步数。被截断的射线会低估遮挡程度。")
                .defineInRange("maxRaySteps", 256, 8, 4096);
        Q_SYNC_THRESHOLD = builder
                .comment("Q 值变化超过该阈值才同步给客户端。")
                .defineInRange("qSyncThreshold", 0.05D, 0.0D, 1.0D);
        builder.pop();

        builder.comment("传播模型").push("loss");
        RANGE_MULTIPLIER = builder
                .comment("所有频段射程的统一缩放系数。")
                .defineInRange("rangeMultiplier", 1.0D, 0.1D, 10.0D);
        BLOCK_LOSS_PER_BLOCK = builder
                .comment("每个不透明方块造成的信号损失比例。0.02 = 穿过 10 个方块损失 20%。")
                .defineInRange("blockLossPerBlock", 0.020D, 0.0D, 1.0D);
        MAX_BLOCK_LOSS = builder
                .comment("遮挡损失封顶比例。0.85 表示最坏情况仍保留 15% 的信号。")
                .defineInRange("maxBlockLoss", 0.85D, 0.0D, 1.0D);
        BLOCK_LOSS_OVERRIDES = builder
                .comment("逐方块损失覆盖，格式 minecraft:block_id=0.05。",
                        "玻璃、树叶这类特殊方块在这里单独调。")
                .defineListAllowEmpty("blockLossOverrides", List.of(
                        "minecraft:glass=0.005",
                        "minecraft:glass_pane=0.005",
                        "minecraft:water=0.015",
                        "minecraft:oak_leaves=0.012",
                        "minecraft:iron_block=0.08",
                        "minecraft:obsidian=0.12"), o -> o instanceof String);
        WEATHER_LOSS_RAIN = builder
                .comment("下雨时的基础损失（再乘以频段的天气敏感度）。")
                .defineInRange("weatherLossRain", 0.10D, 0.0D, 1.0D);
        WEATHER_LOSS_THUNDER = builder
                .comment("雷暴时的基础损失（再乘以频段的天气敏感度）。")
                .defineInRange("weatherLossThunder", 0.24D, 0.0D, 1.0D);
        builder.pop();

        builder.comment("信道行为").push("channel");
        MAX_RECEIVERS_PER_CHANNEL = builder
                .comment("单个信道一次广播的最大接收者数量。")
                .defineInRange("maxReceiversPerChannel", 32, 1, 256);
        MAX_MESSAGE_LENGTH = builder
                .comment("单条无线电消息允许的最大字符数。")
                .defineInRange("maxMessageLength", 120, 8, 512);
        TRANSMIT_COOLDOWN_TICKS = builder
                .comment("同一玩家两次发射之间的最小间隔（tick），用于反刷屏。")
                .defineInRange("transmitCooldownTicks", 10, 0, 200);
        builder.pop();

        builder.comment("Q 值到感官效果的映射。文本与将来的语音共用这一层。").push("effects");
        EFFECT_CLEAR_THRESHOLD = builder
                .comment("Q 高于此值视为完全清晰，不做任何降级。")
                .defineInRange("clearThreshold", 0.80D, 0.0D, 1.0D);
        EFFECT_UNUSABLE_THRESHOLD = builder
                .comment("Q 低于此值视为完全不可用，文本全乱码。")
                .defineInRange("unusableThreshold", 0.20D, 0.0D, 1.0D);
        LOWPASS_MIN_HZ = builder
                .comment("可读度为 0 时的低通截止频率（Hz）。")
                .defineInRange("lowpassMinHz", 300, 80, 8000);
        LOWPASS_MAX_HZ = builder
                .comment("可读度为 1 时的低通截止频率（Hz）。")
                .defineInRange("lowpassMaxHz", 8000, 1000, 20000);
        MAX_DROPOUT_RATE = builder
                .comment("可读度为 0 时的断音率，0.8 表示约八成内容被打断。")
                .defineInRange("maxDropoutRate", 0.80D, 0.0D, 1.0D);
        builder.pop();

        builder.comment("语音（Simple Voice Chat 集成）").push("voice");
        VOICE_ENABLED = builder
                .comment("语音功能总开关。关闭后本 mod 完全不干预 Simple Voice Chat。")
                .define("enabled", true);
        VOICE_REPLACE_VANILLA = builder
                .comment("由电台接管全部语音：语音包按链路表逐人转发，不在链路上的人完全听不到。",
                        "关闭后退回 SVC 原版的距离语音（此时远距离会因实体不在视距内而失效），本 mod 只做文本模式。",
                        "接管模式下会把收到的语音转为非定位音频，听感上声音来自电台而非某个方向。")
                .define("replaceVanillaBehavior", true);
        VOICE_DEBUG_SILENCE = builder
                .comment("调试用：静音所有收到的语音，用于确认音频钩子确实生效。")
                .define("debugSilenceAllVoice", false);
        VOICE_NOISE_GAIN = builder
                .comment("白噪声基准幅度（相对满量程）。噪声强度 = (1 - Q) × 此值。")
                .defineInRange("noiseGain", 0.30D, 0.0D, 1.0D);
        VOICE_HIGHPASS_HZ = builder
                .comment("电台音色的高通截止频率（Hz）。真实电台没有低频。")
                .defineInRange("highpassHz", 300, 20, 2000);
        VOICE_MIN_GAIN = builder
                .comment("可读度为 0 时保留的音量比例。")
                .defineInRange("minVoiceGain", 0.55D, 0.0D, 1.0D);
        VOICE_MAX_RADIUS = builder
                .comment("仅接管模式关闭时生效：VoiceDistanceEvent 允许把原版语音广播到的最远距离（方块）。",
                        "接管模式下语音按链路表直发，不受此项限制。")
                .defineInRange("maxBroadcastRadius", 4096, 64, 16384);
        builder.pop();

        SPEC = builder.build();
    }

    private Config() {
    }
}
