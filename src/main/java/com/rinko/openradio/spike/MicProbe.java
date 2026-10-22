package com.rinko.openradio.spike;

import com.rinko.openradio.OpenRadio;
import com.rinko.openradio.block.FixedStationBlock;
import com.rinko.openradio.block.FixedStationBlockEntity;
import com.rinko.openradio.energy.RadioEnergy;
import com.rinko.openradio.item.BatteryItem;
import com.rinko.openradio.item.HandheldMicItem;
import com.rinko.openradio.item.HandheldRadioItem;
import com.rinko.openradio.radio.AntennaProfile;
import com.rinko.openradio.radio.FixedStationIndex;
import com.rinko.openradio.radio.RadioBands;
import com.rinko.openradio.radio.RadioManager;
import com.rinko.openradio.registry.ModBlocks;
import com.rinko.openradio.registry.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 验证两件事：固定台的信号计算不算得出来、无线手咪是不是真的走了固定台的天线。
 * <b>正式版会删掉。</b>
 *
 * <p>第一条是线上报的 bug：固定台界面一直显示"无同频设备"，但收件箱里有消息。
 * 根因是读数只在玩家端点写入，固定台那支被 {@code continue} 跳过了 ——
 * 这种错误编译通过、不抛异常、只有玩家打开界面才看得见，必须自动测。
 *
 * <p>第二条是整条手咪链路里唯一容易写错的地方：端点换了位置和增益，
 * 但 {@code id()} 必须仍是玩家 UUID，否则 SVC 那边的语音包找不到人。
 * 所以探针要把这两个值都打出来对照。
 *
 * <p>触发：{@code OPENRADIO_PROBE_MIC=1}。
 */
public final class MicProbe {
    private static final int START_DELAY = 40;
    /** 比一个传播周期（10 tick）长得多，确保 meter 已经算过两轮。 */
    private static final int PHASE_GAP = 30;

    private static int countdown = -1;
    private static int phase;
    private static BlockPos stationPos;
    private static BlockPos origin;
    private static java.util.UUID playerUuid;
    private static float radioRadius;

    public static void register() {
        if (System.getenv("OPENRADIO_PROBE_MIC") == null) {
            return;
        }
        OpenRadio.LOGGER.info("[probe] 手咪探测已启用");
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (countdown < 0 && !event.getEntity().level().isClientSide()) {
                countdown = START_DELAY;
            }
        });
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> tick(event.getServer()));
    }

    private static void tick(MinecraftServer server) {
        if (countdown < 0) {
            return;
        }
        if (--countdown > 0) {
            return;
        }
        if (server.getPlayerList().getPlayers().isEmpty()) {
            countdown = START_DELAY;
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        ServerLevel level = player.serverLevel();

        switch (phase) {
            case 0 -> build(player, level);
            case 1 -> reportMeter(level);
            case 2 -> bindMic(player, level);
            case 3 -> reportVoice(player);
            case 4 -> walkAway(player, level);
            case 5 -> powerOff(player, level);
            case 6 -> cleanup(player, level);
            default -> {
                countdown = -1;
                return;
            }
        }
        phase++;
        countdown = PHASE_GAP;
    }

    private static void build(ServerPlayer player, ServerLevel level) {
        origin = player.blockPosition();
        stationPos = origin.offset(3, 0, 0);
        BlockPos basePos = stationPos.offset(2, 0, 0);

        for (int dy = 0; dy <= 10; dy++) {
            level.setBlockAndUpdate(basePos.above(dy), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(stationPos.above(dy), Blocks.AIR.defaultBlockState());
        }
        level.setBlockAndUpdate(stationPos, ModBlocks.FIXED_STATION.get().defaultBlockState()
                .setValue(FixedStationBlock.FACING, Direction.NORTH));
        level.setBlockAndUpdate(stationPos.offset(1, 0, 0),
                ModBlocks.FEEDER_CABLE.get().defaultBlockState());
        level.setBlockAndUpdate(basePos, ModBlocks.ANTENNA_BASE.get().defaultBlockState());
        for (int i = 1; i <= 4; i++) {
            level.setBlockAndUpdate(basePos.above(i), ModBlocks.ANTENNA_MAST.get().defaultBlockState());
        }
        level.setBlockAndUpdate(basePos.above(5),
                ModBlocks.ANTENNA_ELEMENT_SHORT.get().defaultBlockState());

        BlockEntity entity = level.getBlockEntity(stationPos);
        if (!(entity instanceof FixedStationBlockEntity station)) {
            OpenRadio.LOGGER.warn("[probe] 固定台没生成，探测中止");
            countdown = -1;
            return;
        }

        // 逐个面充到满：单次 receiveEnergy 会被 maxReceivePerSide 截断，
        // 只充一次会得到"电量不足、canTransmit 为 false"的假故障。
        IEnergyStorage storage = level.getCapability(Capabilities.EnergyStorage.BLOCK,
                stationPos, Direction.UP);
        for (int i = 1_000_000; i > 0 && storage != null && storage.getEnergyStored() < storage.getMaxEnergyStored(); i -= 1_000) {
            storage.receiveEnergy(i, false);
        }
        station.setPowered(true);

        // 玩家拿一台开机的电台，否则他根本不在链路表里，固定台会读到"0 台"。
        ItemStack radio = new ItemStack(ModItems.HANDHELD_RADIO.get());
        RadioEnergy.install(radio, BatteryItem.capacity());
        HandheldRadioItem.setPowered(radio, true);
        HandheldRadioItem.setFrequency(radio, station.getFrequency());
        player.setItemInHand(InteractionHand.MAIN_HAND, radio);

        AntennaProfile antenna = station.antenna();
        OpenRadio.LOGGER.info("[probe] 固定台已建好：id={} 频率={} 电量={}/{} 天线={} 高度={} 增益={}",
                station.stationId(), station.getFrequency(), station.getEnergy(), station.getCapacity(),
                antenna.element(), antenna.height(),
                String.format("%.2f", antenna.gainFor(RadioBands.SHORT)));
    }

    /**
     * 线上 bug 的回归点：固定台自己必须有读数。
     *
     * <p>同时打印玩家的读数作对照 —— 如果玩家有、固定台没有，
     * 就说明"只给玩家写"的写法又回来了。
     */
    private static void reportMeter(ServerLevel level) {
        playerUuid = level.players().isEmpty() ? null : level.players().get(0).getUUID();
        BlockEntity entity = level.getBlockEntity(stationPos);
        if (!(entity instanceof FixedStationBlockEntity station)) {
            return;
        }
        RadioManager.Meter stationMeter = RadioManager.meterOf(station.stationId());
        OpenRadio.LOGGER.info("[probe] 固定台读数：Q={} 同频设备={}",
                String.format("%.2f", stationMeter.quality()), stationMeter.heard());
        if (stationMeter.heard() <= 0) {
            OpenRadio.LOGGER.warn("[probe] 固定台读到 0 台设备 —— 界面会显示\"无同频设备\"，这是 bug 复现");
        }
        radioRadius = RadioManager.voiceSnapshot().radiusOf(playerUuid);
        OpenRadio.LOGGER.info("[probe] 手台状态的语音广播半径={} 格（后面用来对照手咪）",
                String.format("%.0f", radioRadius));
    }

    /**
     * 手咪绑定后，端点必须落在固定台身上，但身份必须还是玩家。
     */
    private static void bindMic(ServerPlayer player, ServerLevel level) {
        BlockEntity entity = level.getBlockEntity(stationPos);
        if (!(entity instanceof FixedStationBlockEntity station)) {
            return;
        }
        ItemStack mic = HandheldMicItem.fresh(ModItems.HANDHELD_MIC.get());
        HandheldMicItem.bind(mic, station.stationId());
        player.setItemInHand(InteractionHand.MAIN_HAND, mic);

        ItemStack held = HandheldMicItem.findMic(player);
        FixedStationBlockEntity resolved = HandheldMicItem.resolve(player, held);
        OpenRadio.LOGGER.info("[probe] 手咪已绑定：status={} 解析到固定台={}",
                HandheldMicItem.statusOf(player, held),
                resolved == null ? "null" : resolved.getBlockPos().toString());
        if (resolved == null) {
            OpenRadio.LOGGER.warn("[probe] 手咪明明就在旁边却解析不到固定台");
            return;
        }
        OpenRadio.LOGGER.info("[probe]   手咪频率={}（固定台={}）",
                station.getFrequency(), resolved.getFrequency());
        OpenRadio.LOGGER.info("[probe]   手咪增益={}（固定台天线={}）",
                String.format("%.2f", resolved.antenna().gainFor(RadioBands.SHORT)),
                String.format("%.2f", station.antenna().gainFor(RadioBands.SHORT)));
        OpenRadio.LOGGER.info("[probe]   端点身份仍应是玩家 UUID={}，"
                        + "位置取固定台={} 玩家实际位置={}",
                player.getUUID(),
                resolved.getBlockPos(),
                player.blockPosition());
        OpenRadio.LOGGER.info("[probe] 手咪电量={}/{} 能发射={}",
                HandheldMicItem.stored(held), HandheldMicItem.capacity(),
                HandheldMicItem.canTransmit(held) && resolved.canTransmit());
    }

    /**
     * 最关键的一步：手咪的天线增益必须真的进到语音广播半径里。
     *
     * <p>前面验证的只是"端点解析出来的增益是 1.92"，那是<b>读数</b>。
     * 真正决定语音能不能到达远处的是 {@code voiceSnapshot} 里的半径，
     * 两者之间还隔着 {@code collectEndpoints} 一个函数。上一次的 meter bug
     * 就是"读数是 A、实际用 B"这类错位，所以这里必须读实际生效的那份数据。
     */
    private static void reportVoice(ServerPlayer player) {
        float micRadius = RadioManager.voiceSnapshot().radiusOf(player.getUUID());
        OpenRadio.LOGGER.info("[probe] 手咪状态的语音广播半径={} 格（手台时是 {} 格，比值 {}）",
                String.format("%.0f", micRadius), String.format("%.0f", radioRadius),
                String.format("%.2f", radioRadius <= 0 ? 0.0F : micRadius / radioRadius));
        if (micRadius <= radioRadius) {
            OpenRadio.LOGGER.warn("[probe] 手咪没有扩大语音半径 —— 增益没进链路，语音仍然通不远");
        }
        OpenRadio.LOGGER.info("[probe] 语音路由表里有 {} 个说话者",
                RadioManager.voiceSnapshot().routesBySpeaker().size());
    }

    /**
     * 走远之后必须脱网，而不是"信号变差"。
     *
     * <p>顺带验证回退：探针这段手里只有手咪，所以脱网后玩家应当从链路里消失。
     * 如果此时还能通到，说明 {@code resolve} 的距离判定没生效。
     */
    private static void walkAway(ServerPlayer player, ServerLevel level) {
        ItemStack held = HandheldMicItem.findMic(player);
        double before = Math.sqrt(HandheldMicItem.distanceSqr(player, stationPos));

        BlockPos target = origin.offset(0, 0, 20);
        level.setBlockAndUpdate(target.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(target, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(target.above(), Blocks.AIR.defaultBlockState());
        player.teleportTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D);

        double after = Math.sqrt(HandheldMicItem.distanceSqr(player, stationPos));
        HandheldMicItem.Status status = HandheldMicItem.statusOf(player, held);
        FixedStationBlockEntity resolved = HandheldMicItem.resolve(player, held);
        OpenRadio.LOGGER.info("[probe] 走远：距离 {} → {} 格（阈值 {}）status={} 解析={}",
                String.format("%.1f", before), String.format("%.1f", after),
                String.format("%.0f", HandheldMicItem.reach()), status,
                resolved == null ? "null（正确）" : "仍解析到" + resolved.getBlockPos());
        if (status != HandheldMicItem.Status.TOO_FAR || resolved != null) {
            OpenRadio.LOGGER.warn("[probe] 超出范围仍然连着 —— 距离判定有问题");
        }
    }

    private static void powerOff(ServerPlayer player, ServerLevel level) {
        origin = origin == null ? player.blockPosition() : origin;
        player.teleportTo(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D);

        BlockEntity entity = level.getBlockEntity(stationPos);
        if (!(entity instanceof FixedStationBlockEntity station)) {
            return;
        }
        station.setPowered(false);

        ItemStack held = HandheldMicItem.findMic(player);
        HandheldMicItem.Status status = HandheldMicItem.statusOf(player, held);
        FixedStationBlockEntity resolved = HandheldMicItem.resolve(player, held);
        OpenRadio.LOGGER.info("[probe] 固定台关机：status={} 解析={}",
                status, resolved == null ? "null（正确）" : "仍解析到");
        if (status != HandheldMicItem.Status.STATION_OFF) {
            OpenRadio.LOGGER.warn("[probe] 关机后状态不对，应该是 STATION_OFF");
        }
    }

    /** 把机器恢复开机，避免留下一台关着的固定台让人以为是坏的。 */
    private static void cleanup(ServerPlayer player, ServerLevel level) {
        BlockEntity entity = level.getBlockEntity(stationPos);
        if (entity instanceof FixedStationBlockEntity station) {
            station.setPowered(true);
            OpenRadio.LOGGER.info("[probe] 固定台已恢复开机，索引数={}", FixedStationIndex.size());
        }
        OpenRadio.LOGGER.info("[probe] ---- 手咪探测结束 ----");
    }

    private MicProbe() {
    }
}
