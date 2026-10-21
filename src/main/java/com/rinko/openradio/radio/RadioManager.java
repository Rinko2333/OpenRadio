package com.rinko.openradio.radio;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.rinko.openradio.Config;
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
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 服务端的电台管理器，也是唯一有权判定"谁能收到谁"的地方。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>不持有设备注册表</b>。每次重算时现扫在线玩家手里有没有开机的电台，
 *       设备状态永远来自 ItemStack 本身。玩家下线、丢物品、换手，全都自动正确。</li>
 *   <li><b>Q 是成对且定向的</b>。{@code LINK_QUALITY} 里存的是
 *       {@code (发射者 → 接收者) → Q}，不是"信道质量"。玩家看到的是一个汇总值
 *       （最好那条链路 + 能听到几个台），但每条链路本身是独立算出来的。</li>
 *   <li><b>按时间预算切片</b>。N 个电台有 N² 条链路，一次算完会卡 tick，
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
        MinecraftServer server = event.getServer();
        for (ServerLevel level : server.getAllLevels()) {
            processLevel(level, deadline, radii, routes);
        }
        // 整个替换引用，不做原地修改 —— 读线程永远看不到半成品。
        voiceSnapshot = new VoiceSnapshot(Map.copyOf(radii), freeze(routes));
    }

    /** 把可变的嵌套表冻结成不可变快照。内层也必须复制，否则读线程会看到后续写入。 */
    private static Map<UUID, Map<UUID, Float>> freeze(Map<UUID, Map<UUID, Float>> routes) {
        Map<UUID, Map<UUID, Float>> frozen = new HashMap<>(routes.size());
        routes.forEach((speaker, listeners) -> frozen.put(speaker, Map.copyOf(listeners)));
        return Map.copyOf(frozen);
    }

    private static void processLevel(ServerLevel level, long deadline, Map<UUID, Float> radii,
                                     Map<UUID, Map<UUID, Float>> routes) {
        List<Endpoint> endpoints = collectEndpoints(level);

        // 广播半径按频段射程算：长波能覆盖 2048 格，就必须让 SVC 把包发那么远，
        // 否则远处的人根本收不到音频，我们连"劣化"的机会都没有。
        double multiplier = Config.RANGE_MULTIPLIER.get();
        float cap = Config.VOICE_MAX_RADIUS.get();
        for (Endpoint endpoint : endpoints) {
            double range = RadioBands.of(endpoint.frequency()).maxRange() * multiplier;
            radii.merge(endpoint.player().getUUID(), (float) Math.min(range, cap), Math::max);
        }

        computeLinks(level, endpoints, deadline);
        updateClientStates(level, endpoints, routes);
    }

    /** 扫描当前维度里所有开着机的电台。不缓存 —— 现扫永远比维护缓存更不容易出 bug。 */
    private static List<Endpoint> collectEndpoints(ServerLevel level) {
        List<Endpoint> endpoints = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            ItemStack stack = HandheldRadioItem.findPoweredRadio(player);
            if (!stack.isEmpty()) {
                endpoints.add(new Endpoint(player, HandheldRadioItem.getFrequency(stack)));
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
    private static void computeLinks(ServerLevel level, List<Endpoint> endpoints, long deadline) {
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

            Endpoint from = endpoints.get(fromIndex);
            Endpoint to = endpoints.get(toIndex);
            if (from.frequency() != to.frequency()) {
                continue;
            }

            RadioBand band = RadioBands.of(from.frequency());
            double quality = SignalPropagation.compute(level,
                    from.player().position(), to.player().position(), band, maxSteps);
            LINK_QUALITY.put(new LinkKey(from.player().getUUID(), to.player().getUUID()), quality);
        }
    }

    /**
     * 把成对结果汇总成玩家看到的单一状态。
     *
     * <p>汇总方式是"最好那条链路 + 能听到几个台"。之所以不给一个平均 Q：
     * 平均值在 3 人以上会骗人（一个近的 + 两个远的，平均下来像是中等质量，
     * 但远处那两个人你根本听不清）。分开显示才诚实。
     */
    private static void updateClientStates(ServerLevel level, List<Endpoint> endpoints,
                                           Map<UUID, Map<UUID, Float>> routes) {
        Set<UUID> handled = new HashSet<>();

        for (Endpoint self : endpoints) {
            List<S2CRadioState.SpeakerLink> links = new ArrayList<>();
            double best = 0.0D;
            for (Endpoint other : endpoints) {
                if (other == self || other.frequency() != self.frequency()) {
                    continue;
                }
                Double quality = LINK_QUALITY.get(new LinkKey(
                        other.player().getUUID(), self.player().getUUID()));
                if (quality == null || quality <= 0.0D) {
                    continue;
                }
                links.add(new S2CRadioState.SpeakerLink(
                        other.player().getUUID(), quality.floatValue()));
                // 顺手把这张表反过来记一份：说话者 → 该转发给谁。
                // 语音包到达时跑在 SVC 线程上，没有机会遍历世界，只能查这张现成的表。
                routes.computeIfAbsent(other.player().getUUID(), key -> new HashMap<>())
                        .put(self.player().getUUID(), quality.floatValue());
                best = Math.max(best, quality);
            }
            links.sort(Comparator.comparingDouble(S2CRadioState.SpeakerLink::quality).reversed());

            handled.add(self.player().getUUID());
            pushState(self.player(), best, true, links);
        }

        // 关机 / 没拿电台 / 刚下线的玩家：推一个静默状态，让客户端把读数清掉。
        for (ServerPlayer player : level.players()) {
            if (!handled.contains(player.getUUID())) {
                pushState(player, 0.0D, false, List.of());
            }
        }
    }

    /**
     * 只在状态变化超过阈值时才发包，避免每 tick 刷屏。
     *
     * <p>逐说话人链路也要参与变化判定 —— 否则"人没变、但我和某个人之间的地形变了"
     * 这种情况不会触发同步，语音端就会一直用旧的 Q 去处理音频。
     */
    private static void pushState(ServerPlayer player, double quality, boolean active,
                                  List<S2CRadioState.SpeakerLink> links) {
        SentState previous = LAST_SENT.get(player.getUUID());
        double threshold = Config.Q_SYNC_THRESHOLD.get();

        boolean changed = previous == null
                || previous.active() != active
                || Math.abs(previous.quality() - quality) > threshold
                || linksChanged(previous.links(), links, threshold);

        if (!changed) {
            return;
        }
        LAST_SENT.put(player.getUUID(), new SentState(quality, active, links));
        PacketDistributor.sendToPlayer(player,
                new S2CRadioState((float) quality, links.size(), active, links));
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
     */
    public static void transmit(ServerPlayer sender, String rawText) {
        long now = sender.server.getTickCount();
        long cooldown = Config.TRANSMIT_COOLDOWN_TICKS.get();
        Long last = LAST_TRANSMIT_TICK.get(sender.getUUID());
        if (last != null && now - last < cooldown) {
            return;
        }

        ItemStack stack = HandheldRadioItem.findPoweredRadio(sender);
        if (stack.isEmpty()) {
            sender.displayClientMessage(Component.translatable("message.openradio.tx_no_device")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }

        String text = sanitize(rawText, Config.MAX_MESSAGE_LENGTH.get());
        if (text.isEmpty()) {
            return;
        }

        LAST_TRANSMIT_TICK.put(sender.getUUID(), now);
        RadioSounds.keyDown(sender);

        int frequency = HandheldRadioItem.getFrequency(stack);
        RadioBand band = RadioBands.of(frequency);
        int maxSteps = Config.MAX_RAY_STEPS.get();
        int maxReceivers = Config.MAX_RECEIVERS_PER_CHANNEL.get();
        ServerLevel level = sender.serverLevel();
        int delivered = 0;

        for (ServerPlayer receiver : level.players()) {
            if (receiver == sender) {
                continue;
            }
            ItemStack receiverStack = HandheldRadioItem.findPoweredRadio(receiver);
            if (receiverStack.isEmpty()
                    || HandheldRadioItem.getFrequency(receiverStack) != frequency) {
                continue;
            }

            // 发射时重新算一次，而不是读缓存：这一句话必须按"此刻"的地形和天气送达。
            double quality = SignalPropagation.compute(level,
                    sender.position(), receiver.position(), band, maxSteps);
            if (quality <= 0.0D) {
                // 完全超出射程：连噪声都收不到，静默。
                continue;
            }

            sink.deliver(receiver, new RadioMessage(
                    sender.getGameProfile().getName(), frequency, quality, text));
            RadioSounds.squelch(receiver);
            if (++delivered >= maxReceivers) {
                break;
            }
        }

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
        voiceSnapshot = VoiceSnapshot.EMPTY;
        tickCounter = 0;
        cursor = 0;
        pairTotal = 0;
    }

    /** 一个在线电台的临时快照，不跨 tick 保存。 */
    private record Endpoint(ServerPlayer player, int frequency) {
    }

    /** 有向链路：从 {@code from} 发射、被 {@code to} 接收。 */
    private record LinkKey(UUID from, UUID to) {
    }

    /** 上一次同步出去的状态。 */
    private record SentState(double quality, boolean active,
                             List<S2CRadioState.SpeakerLink> links) {
    }

    private RadioManager() {
    }
}
