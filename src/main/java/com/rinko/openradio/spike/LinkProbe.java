package com.rinko.openradio.spike;

import com.rinko.openradio.OpenRadio;
import com.rinko.openradio.block.FixedStationBlock;
import com.rinko.openradio.block.FixedStationBlockEntity;
import com.rinko.openradio.energy.RadioEnergy;
import com.rinko.openradio.item.BatteryItem;
import com.rinko.openradio.item.HandheldRadioItem;
import com.rinko.openradio.radio.AntennaKind;
import com.rinko.openradio.radio.AntennaProfile;
import com.rinko.openradio.radio.Antennas;
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
 * 验证固定台接进链路：能不能被玩家的电台通到，天线增益有没有真的生效。
 * <b>正式版会删掉。</b>
 *
 * <p>为什么这条必须端到端测：链路计算里"增益"是唯一一个跨设备类型的输入。
 * 如果 `StationEndpoint.gainFor` 取错了对象，编译照样通过、HUD 照样显示，
 * 只有在远距离才表现为"怎么搭天线都没用"—— 而那是最难归因的一类问题。
 *
 * <p>触发：{@code OPENRADIO_PROBE_LINK=1}。
 */
public final class LinkProbe {
    private static final int START_DELAY = 40;
    private static final int PHASE_GAP = 25;

    private static int countdown = -1;
    private static int phase;
    private static BlockPos stationPos;

    public static void register() {
        if (System.getenv("OPENRADIO_PROBE_LINK") == null) {
            return;
        }
        OpenRadio.LOGGER.info("[probe] 链路端点探测已启用");
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
            case 0 -> buildStation(level, player);
            case 1 -> transmit(level, player);
            case 2 -> reportGain(level, player);
            default -> {
                countdown = -1;
                return;
            }
        }
        phase++;
        countdown = PHASE_GAP;
    }

    private static void buildStation(ServerLevel level, ServerPlayer player) {
        stationPos = player.blockPosition().offset(3, 0, 0);
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
            OpenRadio.LOGGER.warn("[probe] 固定台没生成");
            return;
        }

        // 充满电再开机，否则 canTransmit 是 false。
        IEnergyStorage storage = level.getCapability(Capabilities.EnergyStorage.BLOCK,
                stationPos, Direction.UP);
        int accepted = storage == null ? 0 : storage.receiveEnergy(1_000_000, false);
        station.setPowered(true);

        AntennaProfile antenna = station.antenna();
        OpenRadio.LOGGER.info("[probe] 固定台已建好：id={} 频率={} 电量={} 充电={} 天线={} 高度={} 增益={}",
                station.stationId(), station.getFrequency(), station.getEnergy(), accepted,
                antenna.element(), antenna.height(),
                String.format("%.2f", antenna.gainFor(RadioBands.SHORT)));

        // 顺便验证索引：固定台必须已经登记，否则 RadioManager 根本看不到它。
        OpenRadio.LOGGER.info("[probe] 固定台索引登记数={}", com.rinko.openradio.radio.FixedStationIndex.size());
    }

    private static void transmit(ServerLevel level, ServerPlayer player) {
        ItemStack radio = new ItemStack(ModItems.HANDHELD_RADIO.get());
        RadioEnergy.install(radio, BatteryItem.capacity());
        HandheldRadioItem.setPowered(radio, true);
        player.setItemInHand(InteractionHand.MAIN_HAND, radio);

        RadioManager.transmit(player, "probe transmission");

        BlockEntity entity = level.getBlockEntity(stationPos);
        if (!(entity instanceof FixedStationBlockEntity station)) {
            return;
        }
        OpenRadio.LOGGER.info("[probe] 发射后固定台收件箱条数={}", station.inbox().size());
        for (FixedStationBlockEntity.LoggedMessage message : station.inbox()) {
            OpenRadio.LOGGER.info("[probe]   来自 {} Q={} 内容=\"{}\"",
                    message.senderName(), String.format("%.2f", message.quality()), message.text());
        }
        if (station.inbox().isEmpty()) {
            OpenRadio.LOGGER.warn("[probe] 固定台没收到 —— 检查索引、频率、开机状态、射程");
        }
    }

    /**
     * 纯数值对照：同一个 600 格距离，裸机和带天线分别能拿到多少 Q。
     *
     * <p>这段不依赖世界地形，所以能直接说明"增益确实进了公式"，
     * 而不只是"链路碰巧通了"。
     */
    private static void reportGain(ServerLevel level, ServerPlayer player) {
        BlockEntity entity = level.getBlockEntity(stationPos);
        if (!(entity instanceof FixedStationBlockEntity station)) {
            return;
        }
        AntennaKind playerAntenna = AntennaKind.BARE;
        double bareStation = AntennaKind.BARE.gainFor(RadioBands.SHORT);
        double realStation = station.antenna().gainFor(RadioBands.SHORT);

        double bareLink = Antennas.linkGain(playerAntenna.gainFor(RadioBands.SHORT), bareStation);
        double realLink = Antennas.linkGain(playerAntenna.gainFor(RadioBands.SHORT), realStation);

        double baseRange = RadioBands.SHORT.maxRange();
        OpenRadio.LOGGER.info("[probe] 链路增益对照（短波）：裸固定台 单端={} 链路={} 射程≈{} 格",
                String.format("%.2f", bareStation), String.format("%.2f", bareLink),
                Math.round(baseRange * bareLink));
        OpenRadio.LOGGER.info("[probe] 链路增益对照（短波）：带天线 单端={} 链路={} 射程≈{} 格",
                String.format("%.2f", realStation), String.format("%.2f", realLink),
                Math.round(baseRange * realLink));
        OpenRadio.LOGGER.info("[probe] ---- 链路探测结束 ----");
    }

    private LinkProbe() {
    }
}
