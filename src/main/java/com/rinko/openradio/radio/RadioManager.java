package com.rinko.openradio.radio;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.rinko.openradio.Config;
import com.rinko.openradio.block.FixedStationBlockEntity;
import com.rinko.openradio.energy.RadioEnergy;
import com.rinko.openradio.energy.VoicePowerLedger;
import com.rinko.openradio.item.HandheldRadioItem;
import com.rinko.openradio.network.S2CRadioState;
import com.rinko.openradio.radio.delivery.ChatMessageSink;
import com.rinko.openradio.radio.delivery.MessageSink;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 服务端的电台管理器，也是唯一有权判定"谁能收到谁"的地方。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>不持有持久化的设备注册表</b>。玩家电台每次重算时现扫手上的物品；
 *       固定台走 {@link FixedStationIndex}，而那个索引完全由"哪些区块是加载的"决定，
 *       不落盘、不会腐坏。设备状态永远来自设备自己
 *       （ItemStack 的组件 / BlockEntity 的字段）。</li>
 *   <li><b>端点（{@link RadioEndpoint}）是统一抽象</b>。玩家和固定台在这里没有区别：
 *       都能算增益、都能被通到。差别只在"怎么把消息交出去"和"要不要推 HUD"。</li>
 *   <li><b>Q 是成对且定向的</b>。{@code LINK_QUALITY} 存的是
 *       {@code (发射者 → 接收者) → Q}，不是"信道质量"。</li>
 *   <li><b>按时间预算切片</b>。N 个设备有 N² 条链路，一次算完会卡 tick，
 *       所以每 tick 只用一点预算、下次从断点继续。</li>
 *   <li><b>投递通道可替换</b>。现在注入的是 {@link ChatMessageSink}，
 *       接语音时换成音频实现，上面整条链路不动。</li>
 * </ul>
 */
public final class RadioManager {
    /** (发射者 → 接收者) → 信号质量。成对、定向，这是整个模型的事实来源。 */
    private static final Map<LinkKey, Double> LINK_QUALITY = new HashMap<>();
    /** 上一次给某个玩家同步的汇总状态，用于变化阈值过滤。 */
    private static final Map<UUID, SentState> LAST_SENT = new HashMap<>();
    /** 上一次发射的 tick，用于反刷屏。 */
    private static final Map<UUID, Long> LAST_TRANSMIT_TICK = new HashMap<>();
    /** 待机耗电的小数余额。FE 是整数，但每分钟 60 FE 摊到每个周期不到 1，必须攒着。 */
    private static final Map<UUID, Double> IDLE_RESIDUAL = new HashMap<>();
    /** 已经提示过"电量低"的玩家，用于只提示一次。 */
    private static final Set<UUID> LOW_POWER_WARNED = new HashSet<>();
    /** 每台设备当前"能通到的最好链路 + 能听到几个台"。固定台界面直接读它。 */
    private static final Map<UUID, Meter> METERS = new HashMap<>();

    /** 当前的投递通道。将来替换成 VoiceSink 即可。 */
    private static MessageSink sink = ChatMessageSink.INSTANCE;

    /**
     * 语音层读取的只读快照。由主线程整体替换，SVC 的包处理线程只读。
     * 见 {@link VoiceSnapshot} 里关于线程安全的说明。
     */
    private static volatile VoiceSnapshot voiceSnapshot = VoiceSnapshot.EMPTY;

    private static int tickCounter;
    private static int cursor;
    private static int pairTotal;

    public static void setSink(MessageSink newSink) {
        sink = newSink;
    }

    public static VoiceSnapshot voiceSnapshot() {
        return voiceSnapshot;
    }

    // ---------------------------------------------------------------- tick

    public static void onServerTick(ServerTickEvent.Post event) {
        int interval = Math.max(1, Config.PROPAGATION_INTERVAL_TICKS.get());
        if (++tickCounter < interval) {
            return;
        }
        tickCounter = 0;

        long deadline = System.nanoTime() + Config.TICK_BUDGET_MICROS.get() * 1_000L;
        Map<UUID, Float> radii = new HashMap<>();
        Map<UUID, Map<UUID, Float>> routes = new HashMap<>();
        Set<UUID> blocked = new HashSet<>();
        MinecraftServer server = event.getServer();

        // 先把语音欠账结掉：账本是包处理线程写的，写不进 ItemStack。
        settleVoiceEnergy(server);

        for (ServerLevel level : server.getAllLevels()) {
            processLevel(level, deadline, radii, routes, blocked, interval);
        }
        // 整个替换引用，不做原地修改 —— 读线程永远看不到半成品。
        voiceSnapshot = new VoiceSnapshot(Map.copyOf(radii), freeze(routes), Set.copyOf(blocked));
    }

    /**
     * 结算语音耗电。
     *
     * <p>遍历所有在线玩家而不是只遍历电台持有者 —— 因为账本条目可能在
     * "电台被拆走"之后才被取走，只遍历持有者会让那条账永远清不掉。
     */
    private static void settleVoiceEnergy(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                int owed = VoicePowerLedger.take(player.getUUID());
                if (owed <= 0) {
                    continue;
                }
                ItemStack radio = HandheldRadioItem.findPoweredRadio(player);
                if (!radio.isEmpty()) {
                    RadioEnergy.drain(radio, owed);
                }
            }
        }
    }

    /** 把可变的嵌套表冻结成不可变快照。内层也必须复制，否则读线程会看到后续写入。 */
    private static Map<UUID, Map<UUID, Float>> freeze(Map<UUID, Map<UUID, Float>> routes) {
        Map<UUID, Map<UUID, Float>> frozen = new HashMap<>(routes.size());
        routes.forEach((speaker, listeners) -> frozen.put(speaker, Map.copyOf(listeners)));
        return Map.copyOf(frozen);
    }

    private static void processLevel(ServerLevel level, long deadline, Map<UUID, Float> radii,
                                     Map<UUID, Map<UUID, Float>> routes, Set<UUID> blocked,
                                     int intervalTicks) {
        List<RadioEndpoint> endpoints = collectEndpoints(level);

        // 广播半径按"频段射程 × 自己的天线增益"算：长波能覆盖 2048 格，就必须让 SVC
        // 把包发那么远，否则远处的人根本收不到音频，我们连"劣化"的机会都没有。
        // 只有玩家端点需要 —— 固定台不参与 SVC 语音。
        double multiplier = Config.RANGE_MULTIPLIER.get();
        float cap = Config.VOICE_MAX_RADIUS.get();
        for (RadioEndpoint endpoint : endpoints) {
            ServerPlayer player = endpoint.player();
            if (player == null) {
                continue;
            }
            RadioBand band = RadioBands.of(endpoint.frequency());
            double range = band.maxRange() * multiplier * endpoint.gainFor(band);
            radii.merge(player.getUUID(), (float) Math.min(range, cap), Math::max);

            drainIdle(endpoint, intervalTicks);
            if (!endpoint.canTransmit()) {
                blocked.add(player.getUUID());
            }
        }

        computeLinks(level, endpoints, deadline);
        updateClientStates(level, endpoints, routes);
    }

    /**
     * 待机耗电。
     *
     * <p>默认每分钟 60 FE，摊到 10 tick 一个周期是 0.5 FE —— 取整会永远是 0，
     * 所以必须攒小数余额。这也是为什么不能简单地在每个周期扣 1。
     *
     * <p>只处理玩家。固定台的待机耗电在自己的方块实体 tick 里结算，
     * 因为那里本来就要跑，没必要绕一圈。
     */
    private static void drainIdle(RadioEndpoint endpoint, int intervalTicks) {
        double perMinute = Config.ENERGY_IDLE_DRAIN_PER_MINUTE.get();
        if (perMinute <= 0.0D) {
            return;
        }
        ServerPlayer player = endpoint.player();
        if (player == null) {
            return;
        }
        double perCycle = perMinute / 1200.0D * intervalTicks;
        double balance = IDLE_RESIDUAL.merge(player.getUUID(), perCycle, Double::sum);
        int whole = (int) balance;
        if (whole <= 0) {
            return;
        }
        IDLE_RESIDUAL.put(player.getUUID(), balance - whole);
        endpoint.drain(whole);
    }

    /**
     * 扫描当前维度里所有能用的电台：玩家的手持机 + 已加载的固定台。
     *
     * <p>不缓存玩家侧 —— 现扫永远比维护缓存更不容易出 bug。固定台侧靠索引，
     * 而索引本身只反映"现在加载着哪些"，同样不需要维护一致性。
     */
    private static List<RadioEndpoint> collectEndpoints(ServerLevel level) {
        List<RadioEndpoint> endpoints = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            ItemStack stack = HandheldRadioItem.findPoweredRadio(player);
            if (!stack.isEmpty()) {
                endpoints.add(new PlayerEndpoint(player, stack, Antennas.resolve(player)));
            }
        }
        for (FixedStationBlockEntity station : FixedStationIndex.in(level)) {
            if (station.isPowered() && !station.isRemoved()) {
                endpoints.add(new StationEndpoint(station));
            }
        }
        return endpoints;
    }

    /**
     * 按预算增量地算成对链路质量。
     *
     * <p>用线性游标扫 N×N 的矩阵，超出预算就从断点返回，下个周期接着算。
     * 中途返回时未算到的链路保留上一轮的值 —— 传播间隔本来就是 10 tick，
     * 一条链路的数据稍微旧一点无关紧要，但绝不能为此卡住主线程。
     */
    private static void computeLinks(ServerLevel level, List<RadioEndpoint> endpoints, long deadline) {
        int count = endpoints.size();
        int total = count * count;
        if (total != pairTotal) {
            pairTotal = total;
            cursor = 0;
        }
        if (total == 0) {
            return;
        }

        int maxSteps = Config.MAX_RAY_STEPS.get();
        int visited = 0;
        while (visited < total) {
            if ((visited & 7) == 0 && System.nanoTime() > deadline) {
                return;
            }

            int index = cursor;
            cursor = (cursor + 1) % total;
            visited++;

            int fromIndex = index / count;
            int toIndex = index % count;
            if (fromIndex == toIndex) {
                continue;
            }

            RadioEndpoint from = endpoints.get(fromIndex);
            RadioEndpoint to = endpoints.get(toIndex);
            if (from.frequency() != to.frequency()) {
                continue;
            }

            RadioBand band = RadioBands.of(from.frequency());
            double gain = Antennas.linkGain(from.gainFor(band), to.gainFor(band));
            double quality = SignalPropagation.compute(level,
                    from.position(), to.position(), band, maxSteps, gain);
            LINK_QUALITY.put(new LinkKey(from.id(), to.id()), quality);
        }
    }

    /**
     * 把成对结果汇总成玩家看到的单一状态。
     *
     * <p>汇总方式是"最好那条链路 + 能听到几个台"。之所以不给一个平均 Q：
     * 平均值在 3 人以上会骗人（一个近的 + 两个远的，平均下来像是中等质量，
     * 但远处那两个人你根本听不清）。分开显示才诚实。
     *
     * <p>"听到几个台"包含固定台，但逐说话人链路只列玩家 —— 因为那张表是给语音用的，
     * 固定台不会说话。这是两个不同的量，不该为了省一个字段而混在一起。
     */
    private static void updateClientStates(ServerLevel level, List<RadioEndpoint> endpoints,
                                           Map<UUID, Map<UUID, Float>> routes) {
        Set<UUID> handled = new HashSet<>();

        for (RadioEndpoint self : endpoints) {
            ServerPlayer player = self.player();
            if (player == null) {
                continue;
            }

            List<S2CRadioState.SpeakerLink> links = new ArrayList<>();
            double best = 0.0D;
            int heard = 0;
            for (RadioEndpoint other : endpoints) {
                if (other.id().equals(self.id()) || other.frequency() != self.frequency()) {
                    continue;
                }
                Double quality = LINK_QUALITY.get(new LinkKey(other.id(), self.id()));
                if (quality == null || quality <= 0.0D) {
                    continue;
                }
                heard++;
                best = Math.max(best, quality);

                ServerPlayer speaker = other.player();
                if (speaker == null) {
                    continue;
                }
                links.add(new S2CRadioState.SpeakerLink(speaker.getUUID(), quality.floatValue()));
                // 顺手把这张表反过来记一份：说话者 → 该转发给谁。
                // 语音包到达时跑在 SVC 线程上，没有机会遍历世界，只能查这张现成的表。
                routes.computeIfAbsent(speaker.getUUID(), key -> new HashMap<>())
                        .put(player.getUUID(), quality.floatValue());
            }
            links.sort(Comparator.comparingDouble(S2CRadioState.SpeakerLink::quality).reversed());

            handled.add(self.id());
            METERS.put(self.id(), new Meter(best, heard));
            warnLowPower(self);
            pushState(player, best, heard, true, links);
        }

        // 关机 / 没拿电台 / 刚下线的玩家：推一个静默状态，让客户端把读数清掉。
        for (ServerPlayer player : level.players()) {
            if (!handled.contains(player.getUUID())) {
                LOW_POWER_WARNED.remove(player.getUUID());
                METERS.remove(player.getUUID());
                pushState(player, 0.0D, 0, false, List.of());
            }
        }
    }

    /** 某台设备当前的信号计。设备离线或还没算过时返回零值。 */
    public static Meter meterOf(UUID deviceId) {
        return METERS.getOrDefault(deviceId, Meter.NONE);
    }

    /** 电量刚跌破阈值时提示一次。跨回去再跌破会重新提示。 */
    private static void warnLowPower(RadioEndpoint self) {
        ServerPlayer player = self.player();
        if (player == null) {
            return;
        }
        UUID id = player.getUUID();
        if (self.canTransmit()) {
            LOW_POWER_WARNED.remove(id);
            return;
        }
        if (LOW_POWER_WARNED.add(id)) {
            player.displayClientMessage(Component.translatable("message.openradio.low_power",
                    String.format("%.0f%%", self.energyRatio() * 100.0F))
                    .withStyle(ChatFormatting.YELLOW), true);
        }
    }

    /**
     * 只在状态变化超过阈值时才发包，避免每 tick 刷屏。
     *
     * <p>逐说话人链路也要参与变化判定 —— 否则"人没变、但我和某个人之间的地形变了"
     * 这种情况不会触发同步，语音端就会一直用旧的 Q 去处理音频。
     */
    private static void pushState(ServerPlayer player, double quality, int heard, boolean active,
                                  List<S2CRadioState.SpeakerLink> links) {
        SentState previous = LAST_SENT.get(player.getUUID());
        double threshold = Config.Q_SYNC_THRESHOLD.get();

        boolean changed = previous == null
                || previous.active() != active
                || previous.heard() != heard
                || Math.abs(previous.quality() - quality) > threshold
                || linksChanged(previous.links(), links, threshold);

        if (!changed) {
            return;
        }
        LAST_SENT.put(player.getUUID(), new SentState(quality, heard, active, links));
        PacketDistributor.sendToPlayer(player,
                new S2CRadioState((float) quality, heard, active, links));
    }

    private static boolean linksChanged(List<S2CRadioState.SpeakerLink> previous,
                                        List<S2CRadioState.SpeakerLink> current,
                                        double threshold) {
        if (previous.size() != current.size()) {
            return true;
        }
        for (int i = 0; i < previous.size(); i++) {
            if (!previous.get(i).speaker().equals(current.get(i).speaker())) {
                return true;
            }
            if (Math.abs(previous.get(i).quality() - current.get(i).quality()) > threshold) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ transmit

    /**
     * 处理一次发射请求。所有校验都在这里做，客户端说的任何东西都不可信。
     *
     * <p>接收方同时包含玩家和固定台 —— 固定台虽然不是人，但它确实是一台能收的设备，
     * 而且它的天线增益在链路里是实实在在生效的。
     */
    public static void transmit(ServerPlayer sender, String rawText) {
        long now = sender.server.getTickCount();
        long cooldown = Config.TRANSMIT_COOLDOWN_TICKS.get();
        Long last = LAST_TRANSMIT_TICK.get(sender.getUUID());
        if (last != null && now - last < cooldown) {
            return;
        }

        ItemStack stack = HandheldRadioItem.findRadio(sender);
        if (stack.isEmpty()) {
            sender.displayClientMessage(Component.translatable("message.openradio.tx_no_device")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        if (!HandheldRadioItem.isPowered(stack)) {
            sender.displayClientMessage(Component.translatable("message.openradio.tx_off")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        if (!RadioEnergy.hasBattery(stack)) {
            sender.displayClientMessage(Component.translatable("message.openradio.no_battery")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        if (!RadioEnergy.canTransmit(stack)) {
            sender.displayClientMessage(Component.translatable("message.openradio.low_power",
                            String.format("%.0f%%", RadioEnergy.ratio(stack) * 100.0F))
                    .withStyle(ChatFormatting.RED), true);
            return;
        }

        String text = sanitize(rawText, Config.MAX_MESSAGE_LENGTH.get());
        if (text.isEmpty()) {
            return;
        }

        LAST_TRANSMIT_TICK.put(sender.getUUID(), now);
        RadioSounds.keyDown(sender);
        // 电能花在"按下发射键"这个动作上，而不是"有人收到"上：
        // 对着空频率喊话一样要费电，这符合真机的直觉。
        RadioEnergy.drain(stack, Config.ENERGY_COST_MESSAGE.get());

        int frequency = HandheldRadioItem.getFrequency(stack);
        PlayerEndpoint endpoint = new PlayerEndpoint(sender, stack, Antennas.resolve(sender));
        int delivered = broadcast(sender.serverLevel(), endpoint,
                sender.getGameProfile().getName(), frequency, text);
        reportDelivery(sender, delivered);
    }

    /**
     * 从固定台发射一条文本。
     *
     * <p>校验全部在服务端：客户端的界面只是输入框，它说自己"开着机、有电"一概不算。
     * 冷却按固定台自己记 —— 一台固定台可以被多个人操作，按玩家记会漏。
     *
     * @param operator 操作者，只用于把提示送回给他
     * @return 是否真的发出去了
     */
    public static boolean transmitFromStation(FixedStationBlockEntity station, ServerPlayer operator,
                                              long nowTick, String rawText) {
        if (!station.isPowered()) {
            operator.displayClientMessage(Component.translatable("message.openradio.tx_off")
                    .withStyle(ChatFormatting.RED), true);
            return false;
        }
        if (station.getEnergy() <= 0) {
            operator.displayClientMessage(Component.translatable("message.openradio.station.no_power")
                    .withStyle(ChatFormatting.RED), true);
            return false;
        }
        if (!station.canTransmit()) {
            operator.displayClientMessage(Component.translatable("message.openradio.low_power",
                            String.format("%.0f%%", station.getEnergyRatio() * 100.0F))
                    .withStyle(ChatFormatting.RED), true);
            return false;
        }
        if (!station.tryTransmit(nowTick)) {
            return false;
        }

        String text = sanitize(rawText, Config.MAX_MESSAGE_LENGTH.get());
        if (text.isEmpty()) {
            return false;
        }

        station.drain(Config.STATION_COST_MESSAGE.get());

        // 呼号用操作者的名字：固定台自己没有名字，而接收方需要知道是谁在呼叫。
        String callSign = operator.getGameProfile().getName();
        int delivered = broadcast(operator.serverLevel(), new StationEndpoint(station),
                callSign, station.getFrequency(), text);
        reportDelivery(operator, delivered);
        return true;
    }

    /**
     * 把一条消息按当前地形和天气投递给所有能收到的同频设备。
     *
     * <p>玩家和固定台走同一条路径 —— 唯一的区别是发送方的增益来自哪里，
     * 那个差异已经被 {@link RadioEndpoint#gainFor} 消化掉了。
     */
    private static int broadcast(ServerLevel level, RadioEndpoint sender, String senderName,
                                 int frequency, String text) {
        RadioBand band = RadioBands.of(frequency);
        double senderGain = sender.gainFor(band);
        int maxSteps = Config.MAX_RAY_STEPS.get();
        int maxReceivers = Config.MAX_RECEIVERS_PER_CHANNEL.get();
        int delivered = 0;

        for (RadioEndpoint receiver : collectEndpoints(level)) {
            if (receiver.id().equals(sender.id()) || receiver.frequency() != frequency) {
                continue;
            }
            // 发射时重新算一次，而不是读缓存：这一句话必须按"此刻"的地形和天气送达。
            double gain = Antennas.linkGain(senderGain, receiver.gainFor(band));
            double quality = SignalPropagation.compute(level,
                    sender.position(), receiver.position(), band, maxSteps, gain);
            if (quality <= 0.0D) {
                // 完全超出射程：连噪声都收不到，静默。
                continue;
            }

            receiver.receive(new RadioMessage(senderName, frequency, quality, text));
            if (++delivered >= maxReceivers) {
                break;
            }
        }
        return delivered;
    }

    private static void reportDelivery(ServerPlayer sender, int delivered) {
        if (delivered == 0) {
            RadioSounds.noCarrier(sender);
            sender.displayClientMessage(Component.translatable("message.openradio.tx_no_receiver")
                    .withStyle(ChatFormatting.DARK_GRAY), true);
        } else {
            sender.displayClientMessage(Component.translatable("message.openradio.tx_sent", delivered)
                    .withStyle(ChatFormatting.GREEN), true);
        }
    }

    /**
     * 清理用户输入：去掉控制字符、压缩空白、截断超长内容。
     * 文本最终会进 Component.literal，不存在格式化字符串注入的风险，
     * 但仍要限制长度避免有人用超长消息刷爆带宽。
     */
    private static String sanitize(String raw, int maxLength) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), maxLength));
        for (int i = 0; i < raw.length() && sb.length() < maxLength; i++) {
            char c = raw.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                sb.append(' ');
            } else if (c >= ' ' && c != 0x7F) {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    // ----------------------------------------------------------- lifecycle

    public static void onServerStopping() {
        LINK_QUALITY.clear();
        LAST_SENT.clear();
        LAST_TRANSMIT_TICK.clear();
        IDLE_RESIDUAL.clear();
        LOW_POWER_WARNED.clear();
        METERS.clear();
        VoicePowerLedger.clear();
        FixedStationIndex.clear();
        voiceSnapshot = VoiceSnapshot.EMPTY;
        tickCounter = 0;
        cursor = 0;
        pairTotal = 0;
    }

    // -------------------------------------------------------------- 端点

    /**
     * 参与链路计算的一台设备。
     *
     * <p>玩家和固定台在链路模型里没有区别：都有位置、频率、天线增益和能量状态。
     * 把它们的差异（怎么投递、要不要推 HUD）留给实现，链路计算本身就不必分叉。
     *
     * <p>{@link #player()} 为 null 表示这不是一个玩家设备 —— 调用方据此决定
     * 要不要发 HUD 包、要不要算语音半径。
     */
    private interface RadioEndpoint {
        UUID id();

        Vec3 position();

        int frequency();

        /** 这条设备在某波段上的射程系数。玩家来自副手天线，固定台来自多方块结构。 */
        double gainFor(RadioBand band);

        boolean canTransmit();

        /** 电量比例，只用于"低电"提示。 */
        float energyRatio();

        void drain(int amount);

        void receive(RadioMessage message);

        @Nullable
        ServerPlayer player();
    }

    /** 玩家手持机。{@code radio} 是本 tick 的活引用，不能跨 tick 保存。 */
    private record PlayerEndpoint(ServerPlayer player, ItemStack radio, AntennaKind antenna)
            implements RadioEndpoint {
        @Override
        public UUID id() {
            return player.getUUID();
        }

        @Override
        public Vec3 position() {
            return player.position();
        }

        @Override
        public int frequency() {
            return HandheldRadioItem.getFrequency(radio);
        }

        @Override
        public double gainFor(RadioBand band) {
            return antenna.gainFor(band);
        }

        @Override
        public boolean canTransmit() {
            return RadioEnergy.canTransmit(radio);
        }

        @Override
        public float energyRatio() {
            return RadioEnergy.ratio(radio);
        }

        @Override
        public void drain(int amount) {
            RadioEnergy.drain(radio, amount);
        }

        @Override
        public void receive(RadioMessage message) {
            sink.deliver(player, message);
            RadioSounds.squelch(player);
        }
    }

    /** 固定台。位置取方块中心，增益来自它的多方块天线。 */
    private record StationEndpoint(FixedStationBlockEntity station) implements RadioEndpoint {
        @Override
        public UUID id() {
            return station.stationId();
        }

        @Override
        public Vec3 position() {
            return Vec3.atCenterOf(station.getBlockPos());
        }

        @Override
        public int frequency() {
            return station.getFrequency();
        }

        @Override
        public double gainFor(RadioBand band) {
            return station.antenna().gainFor(band);
        }

        @Override
        public boolean canTransmit() {
            return station.canTransmit();
        }

        @Override
        public float energyRatio() {
            return station.getEnergyRatio();
        }

        @Override
        public void drain(int amount) {
            station.drain(amount);
        }

        @Override
        public void receive(RadioMessage message) {
            station.receive(message);
        }

        @Override
        public ServerPlayer player() {
            return null;
        }
    }

    /** 有向链路：从 {@code from} 发射、被 {@code to} 接收。 */
    private record LinkKey(UUID from, UUID to) {
    }

    /** 上一次同步出去的状态。 */
    private record SentState(double quality, int heard, boolean active,
                             List<S2CRadioState.SpeakerLink> links) {
    }

    /**
     * 一台设备当前的信道读数。
     *
     * @param quality 能通到的最好那条链路的 Q
     * @param heard   能收/能通到的同频设备数量（含固定台）
     */
    public record Meter(double quality, int heard) {
        public static final Meter NONE = new Meter(0.0D, 0);
    }

    private RadioManager() {
    }
}
