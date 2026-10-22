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

    // ---------------------------------------------------------- 能量

    /** 一块电池的容量（FE）。 */
    public static final ModConfigSpec.IntValue ENERGY_BATTERY_CAPACITY;

    /** 开机待机时每分钟的耗电。0 = 待机不耗电。 */
    public static final ModConfigSpec.DoubleValue ENERGY_IDLE_DRAIN_PER_MINUTE;

    /** 发一条文本消息的耗电。 */
    public static final ModConfigSpec.IntValue ENERGY_COST_MESSAGE;

    /** 语音发射每秒的耗电。 */
    public static final ModConfigSpec.IntValue ENERGY_COST_VOICE_PER_SECOND;

    /** 低于该电量比例后只能收不能发。 */
    public static final ModConfigSpec.DoubleValue ENERGY_LOW_THRESHOLD;

    // ---------------------------------------------------------- 无线手咪

    /** 手咪内置电池的容量（FE）。 */
    public static final ModConfigSpec.IntValue MIC_CAPACITY;

    /** 手咪能离固定台多远（格）。超出就脱网。 */
    public static final ModConfigSpec.DoubleValue MIC_REACH;

    // ---------------------------------------------------------- 固定台

    /** 固定台的内部电容（FE）。 */
    public static final ModConfigSpec.IntValue STATION_CAPACITY;

    /** 固定台每个面每 tick 最多接受的能量（FE/t）。 */
    public static final ModConfigSpec.IntValue STATION_MAX_RECEIVE;

    /** 固定台开机待机时每分钟的耗电（FE/min）。 */
    public static final ModConfigSpec.DoubleValue STATION_IDLE_DRAIN_PER_MINUTE;

    /** 固定台发一条文本的耗电（FE）。 */
    public static final ModConfigSpec.IntValue STATION_COST_MESSAGE;

    /** 太阳能电源在满日照下每 tick 的发电量（FE/t）。 */
    public static final ModConfigSpec.IntValue SOLAR_FE_PER_TICK;

    // ---------------------------------------------------------- 天线

    /** 没有天线时的射程系数。 */
    public static final ModConfigSpec.DoubleValue ANTENNA_GAIN_BARE;

    /** 专用天线在匹配波段上的射程系数。 */
    public static final ModConfigSpec.DoubleValue ANTENNA_GAIN_MATCHED;

    /** 专用天线装错波段时的射程系数。 */
    public static final ModConfigSpec.DoubleValue ANTENNA_GAIN_MISMATCHED;

    /** 宽带天线的射程系数（任何波段都一样）。 */
    public static final ModConfigSpec.DoubleValue ANTENNA_GAIN_WIDEBAND;

    /** 多方块天线：每格延长杆带来的额外增益。 */
    public static final ModConfigSpec.DoubleValue ANTENNA_MAST_GAIN_PER_BLOCK;

    /** 多方块天线：延长杆的高度上限。 */
    public static final ModConfigSpec.IntValue ANTENNA_MAX_MAST_BLOCKS;

    /** 馈线网络最多搜索多少个方块。 */
    public static final ModConfigSpec.IntValue ANTENNA_FEEDER_MAX_BLOCKS;

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

        builder.comment("能量。电台用 NeoForge Energy（FE），UI 上按社区习惯显示为 RF。").push("energy");
        ENERGY_BATTERY_CAPACITY = builder
                .comment("一块电池的容量（FE）。")
                .defineInRange("batteryCapacity", 20_000, 100, 2_000_000);
        ENERGY_IDLE_DRAIN_PER_MINUTE = builder
                .comment("开机待机时每分钟耗电（FE/min）。0 = 待机不耗电。",
                        "默认 60：一块满电池待机约 5.5 小时，够用但不至于永远不用管电。")
                .defineInRange("idleDrainPerMinute", 60.0D, 0.0D, 100_000.0D);
        ENERGY_COST_MESSAGE = builder
                .comment("发一条文本消息的耗电（FE）。")
                .defineInRange("costPerMessage", 40, 0, 100_000);
        ENERGY_COST_VOICE_PER_SECOND = builder
                .comment("语音发射每秒的耗电（FE/s）。按麦克风帧累计，每 20 tick 结算一次。")
                .defineInRange("costPerVoiceSecond", 40, 0, 100_000);
        ENERGY_LOW_THRESHOLD = builder
                .comment("低于该电量比例后只能收不能发。不做变砖 —— 换块电池就能继续用。")
                .defineInRange("lowThreshold", 0.10D, 0.0D, 1.0D);
        builder.pop();

        builder.comment("固定台。本身不装电池，靠外部供电（电网 / 太阳能）。",
                "暴露标准的 NeoForge Energy 能力，所以 Mekanism 通用线缆、热力膨胀的能量管道",
                "以及任何走 Forge/NeoForge Energy 的设备都能直接接上。",
                "刻意不兼容只有 RF 私有实现的东西 —— 那是另一套 API，接了等于给每个 mod 写一次适配。")
                .push("station");
        STATION_CAPACITY = builder
                .comment("固定台内部电容（FE）。比手台的电池大一个量级，",
                        "因为它要撑住整张 SSTV 图的发射，中途掉电会让接收端收到半张图。")
                .defineInRange("capacity", 400_000, 1_000, 100_000_000);
        STATION_MAX_RECEIVE = builder
                .comment("每个面每 tick 最多接受的输入（FE/t）。按面计而不是按设备计，",
                        "这样多面接管道时总输入能叠加，符合玩家对线缆并联的直觉。")
                .defineInRange("maxReceivePerSide", 1_000, 1, 1_000_000);
        STATION_IDLE_DRAIN_PER_MINUTE = builder
                .comment("开机待机耗电（FE/min）。")
                .defineInRange("idleDrainPerMinute", 120.0D, 0.0D, 100_000.0D);
        STATION_COST_MESSAGE = builder
                .comment("固定台发一条文本的耗电（FE）。比手台高，因为发射功率更大。")
                .defineInRange("costPerMessage", 200, 0, 1_000_000);
        builder.pop();

        builder.comment("无线手咪。固定台的话筒：只用固定台的天线和频率收发语音，自己不能调频、不能发文本。")
                .push("mic");
        MIC_CAPACITY = builder
                .comment("手咪内置电池的容量（FE）。比手台的电池小一半：它只驱动语音链路，",
                        "而且真正吃电的是固定台那边。不做成可更换电池 —— 手咪是附件，不是第二台电台。")
                .defineInRange("capacity", 8_000, 100, 2_000_000);
        MIC_REACH = builder
                .comment("手咪离固定台多远之后脱网（格）。",
                        "超出是<b>直接断</b>而不是逐渐变差：手咪是无线连固定台的，",
                        "走远了是掉线，不是信号弱。两者在界面上要给不同的提示，混淆了很难排查。")
                .defineInRange("reach", 10.0D, 1.0D, 64.0D);
        builder.pop();

        builder.comment("太阳能电源。放在固定台正上方即可供电，也可以用管道转接。").push("solar");
        SOLAR_FE_PER_TICK = builder
                .comment("满日照下每 tick 的发电量（FE/t）。夜晚为 0，下雨按 1/3 计。",
                        "默认 40 FE/t = 800 FE/s，约 8 分钟把固定台的 40 万 FE 充满。")
                .defineInRange("fePerTick", 40, 0, 100_000);
        builder.pop();

        builder.comment("天线。射程 = 频段射程 × √(发送端系数 × 接收端系数)。").push("antenna");
        ANTENNA_GAIN_BARE = builder
                .comment("没有天线时的射程系数。裸机很难用，但不是不能用。")
                .defineInRange("bareGain", 0.50D, 0.05D, 4.0D);
        ANTENNA_GAIN_MATCHED = builder
                .comment("专用天线在匹配波段上的射程系数。")
                .defineInRange("matchedGain", 1.60D, 0.05D, 8.0D);
        ANTENNA_GAIN_MISMATCHED = builder
                .comment("专用天线装错波段时的射程系数。刻意保留一点作用，",
                        "让装错天线只是效率低，而不是完全不能用。")
                .defineInRange("mismatchedGain", 0.80D, 0.05D, 4.0D);
        ANTENNA_GAIN_WIDEBAND = builder
                .comment("宽带天线的射程系数，任何波段都一样。它是不想挑波段时的折衷。")
                .defineInRange("widebandGain", 1.20D, 0.05D, 4.0D);
        ANTENNA_MAST_GAIN_PER_BLOCK = builder
                .comment("多方块天线每多一格延长杆的额外增益。默认 0.04 = 每格 +4%。",
                        "高度是真实无线电里最有效的增益手段，所以这一项刻意做得划算：",
                        "16 格杆 = +64%，但玩家要在露天搭一根天线，风险也是实打实的。")
                .defineInRange("mastGainPerBlock", 0.04D, 0.0D, 0.5D);
        ANTENNA_MAX_MAST_BLOCKS = builder
                .comment("延长杆的高度上限（含振子）。超过部分不再增加增益。")
                .defineInRange("maxMastBlocks", 16, 1, 64);
        ANTENNA_FEEDER_MAX_BLOCKS = builder
                .comment("馈线网络从电台出发最多搜索多少个方块。",
                        "限制它是为了防止玩家铺几千格线缆制造服务器负担 —— 超过就当没接上。")
                .defineInRange("feederMaxBlocks", 256, 8, 4096);
        builder.pop();

        builder.comment("语音（Simple Voice Chat 集成）").push("voice");        VOICE_ENABLED = builder
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
