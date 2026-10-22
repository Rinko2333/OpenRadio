package com.rinko.openradio.spike;

import com.rinko.openradio.OpenRadio;
import com.rinko.openradio.block.FixedStationBlock;
import com.rinko.openradio.block.FixedStationBlockEntity;
import com.rinko.openradio.block.antenna.AntennaBaseBlockEntity;
import com.rinko.openradio.radio.AntennaProfile;
import com.rinko.openradio.radio.RadioBands;
import com.rinko.openradio.registry.ModBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 自动验证多方块天线与馈线。<b>正式版会删掉。</b>
 *
 * <p>要验证的四件事，任何一件错了都只能靠玩家实测发现：
 * <ol>
 *   <li>结构扫描：基座 → 连续杆 → 顶端振子，高度算对没有；</li>
 *   <li>结构破坏：中间少一格，增益必须立刻掉回去；</li>
 *   <li>馈线寻路：电台能沿着线找到基座；</li>
 *   <li>断开馈线：电台必须立刻失去天线。</li>
 * </ol>
 *
 * <p>触发：{@code OPENRADIO_PROBE_ANTENNA=1}。每个阶段间隔 30 tick，
 * 故意大于天线缓存与电台缓存的 20 tick，这样测的是真实行为而不是"缓存刚好过期"。
 */
public final class AntennaProbe {
    private static final int START_DELAY = 40;
    private static final int PHASE_GAP = 30;

    private static int countdown = -1;
    private static int phase;
    private static BlockPos basePos;
    private static BlockPos stationPos;
    private static BlockPos feederPos;

    public static void register() {
        if (System.getenv("OPENRADIO_PROBE_ANTENNA") == null) {
            return;
        }
        OpenRadio.LOGGER.info("[probe] 天线结构探测已启用");
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
            case 0 -> build(level, player);
            case 1 -> connectStation(level, player);
            case 2 -> breakStructure(level, player);
            case 3 -> repairStructure(level, player);
            default -> {
                countdown = -1;
                return;
            }
        }
        phase++;
        countdown = PHASE_GAP;
    }

    private static void build(ServerLevel level, ServerPlayer player) {
        basePos = player.blockPosition().offset(5, 0, 0);

        // 清出一根竖井，否则测试结果取决于玩家站在哪 —— 树、洞顶、雪都会干扰。
        for (int dy = 0; dy <= 12; dy++) {
            level.setBlockAndUpdate(basePos.above(dy), Blocks.AIR.defaultBlockState());
        }
        level.setBlockAndUpdate(basePos, ModBlocks.ANTENNA_BASE.get().defaultBlockState());
        for (int i = 1; i <= 3; i++) {
            level.setBlockAndUpdate(basePos.above(i), ModBlocks.ANTENNA_MAST.get().defaultBlockState());
        }
        level.setBlockAndUpdate(basePos.above(4),
                ModBlocks.ANTENNA_ELEMENT_SHORT.get().defaultBlockState());

        logProfile(level, "建成：基座 + 3 杆 + 短波振子");
    }

    private static void connectStation(ServerLevel level, ServerPlayer player) {
        stationPos = basePos.offset(2, 0, 0);
        feederPos = basePos.offset(1, 0, 0);
        level.setBlockAndUpdate(stationPos, ModBlocks.FIXED_STATION.get().defaultBlockState()
                .setValue(FixedStationBlock.FACING, Direction.NORTH));
        level.setBlockAndUpdate(feederPos, ModBlocks.FEEDER_CABLE.get().defaultBlockState());

        OpenRadio.LOGGER.info("[probe] ---- 馈线连接后 ----");
        logStationAntenna(level, "电台读到的天线");
    }

    private static void breakStructure(ServerLevel level, ServerPlayer player) {
        // 拆掉中间那根杆，结构断开。基座收不到邻居通知吗？收到了就要立刻失效。
        level.setBlockAndUpdate(basePos.above(2), Blocks.AIR.defaultBlockState());
        logProfile(level, "拆掉第 2 根杆后");
        logStationAntenna(level, "电台读到的天线");
    }

    private static void repairStructure(ServerLevel level, ServerPlayer player) {
        level.setBlockAndUpdate(basePos.above(2), ModBlocks.ANTENNA_MAST.get().defaultBlockState());
        logProfile(level, "补回杆子后");

        // 再把馈线拆掉，验证电台会失去天线。
        level.setBlockAndUpdate(feederPos, Blocks.AIR.defaultBlockState());
        logStationAntenna(level, "拆掉馈线后");

        OpenRadio.LOGGER.info("[probe] ---- 天线探测结束 ----");
    }

    private static void logProfile(ServerLevel level, String stage) {
        BlockEntity entity = level.getBlockEntity(basePos);
        if (!(entity instanceof AntennaBaseBlockEntity base)) {
            OpenRadio.LOGGER.warn("[probe] {}：基座方块实体不存在", stage);
            return;
        }
        AntennaProfile profile = base.profile();
        OpenRadio.LOGGER.info("[probe] {}：存在={} 振子={} 高度={} 短波增益={} 长波增益={}",
                stage, profile.present(), profile.element(), profile.height(),
                String.format("%.2f", profile.gainFor(RadioBands.SHORT)),
                String.format("%.2f", profile.gainFor(RadioBands.LONG)));
    }

    private static void logStationAntenna(ServerLevel level, String stage) {
        if (stationPos == null) {
            return;
        }
        BlockEntity entity = level.getBlockEntity(stationPos);
        if (!(entity instanceof FixedStationBlockEntity station)) {
            OpenRadio.LOGGER.warn("[probe] {}：固定台方块实体不存在", stage);
            return;
        }
        AntennaProfile profile = station.antenna();
        OpenRadio.LOGGER.info("[probe] {}：存在={} 振子={} 高度={} 增益={}",
                stage, profile.present(), profile.element(), profile.height(),
                String.format("%.2f", profile.gainFor(RadioBands.SHORT)));
    }

    private AntennaProbe() {
    }
}
