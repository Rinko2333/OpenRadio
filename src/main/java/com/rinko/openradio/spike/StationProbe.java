package com.rinko.openradio.spike;

import com.rinko.openradio.OpenRadio;
import com.rinko.openradio.block.FixedStationBlock;
import com.rinko.openradio.block.FixedStationBlockEntity;
import com.rinko.openradio.block.SolarPowerBlock;
import com.rinko.openradio.registry.ModBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 自动探测固定台的能量能力。<b>正式版会删掉。</b>
 *
 * <p>为什么必须自动测：本项目没有 Mekanism 依赖，玩家装没装、装哪个版本都不确定。
 * 但"正面不接管道、其余面能接"这条规则完全由<b>能力注册</b>决定 —— 也就是
 * {@code getCapability} 在那一面返回 null 还是对象。这不需要任何管道就能验证：
 * 逐面查询一遍，看返回什么。
 *
 * <p>触发：环境变量 {@code OPENRADIO_PROBE_STATION=1}，玩家进世界 2 秒后自动执行。
 * 一次性跑完就结束，不会持续占用世界。
 */
public final class StationProbe {
    private static final int START_DELAY = 40;
    private static final int SOLAR_WAIT = 80;

    private static int countdown = -1;
    private static int phase;
    private static BlockPos targetPos;
    private static int energyBeforeSolar;
    /** 探测前的游戏时间，结束后还原 —— 不要在玩家存档里留下"永远正午"。 */
    private static long savedDayTime = Long.MIN_VALUE;

    public static void register() {
        if (System.getenv("OPENRADIO_PROBE_STATION") == null) {
            return;
        }
        OpenRadio.LOGGER.info("[probe] 固定台能力探测已启用");

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

        ServerPlayer player = server.getPlayerList().getPlayers().isEmpty()
                ? null
                : server.getPlayerList().getPlayers().get(0);
        if (player == null) {
            countdown = START_DELAY;
            return;
        }

        if (phase == 0) {
            probeCapabilities(player);
            phase = 1;
            countdown = SOLAR_WAIT;
            return;
        }

        if (phase == 1) {
            reportSolar(player);
            countdown = -1;
        }
    }

    private static void probeCapabilities(ServerPlayer player) {
        ServerLevel level = player.serverLevel();

        // 第一次跑的时候正好是夜晚，测不出太阳能。固定成正午再测，
        // 结束后还原 —— 否则等于偷偷改了玩家存档的昼夜。
        savedDayTime = level.getDayTime();
        level.setDayTime(6000L);

        targetPos = player.blockPosition().offset(2, 0, 0);
        level.setBlockAndUpdate(targetPos, ModBlocks.FIXED_STATION.get().defaultBlockState()
                .setValue(FixedStationBlock.FACING, Direction.NORTH));

        BlockEntity blockEntity = level.getBlockEntity(targetPos);
        if (!(blockEntity instanceof FixedStationBlockEntity station)) {
            OpenRadio.LOGGER.warn("[probe] 方块实体没有生成，方块={}", level.getBlockState(targetPos));
            return;
        }

        OpenRadio.LOGGER.info("[probe] ---- 能力面检查（正面 = NORTH）----");
        for (Direction side : Direction.values()) {
            IEnergyStorage capability = level.getCapability(
                    Capabilities.EnergyStorage.BLOCK, targetPos, side);
            OpenRadio.LOGGER.info("[probe]   {} -> {}", side,
                    capability == null ? "null（不接）" : "可用");
        }
        IEnergyStorage unsided = level.getCapability(
                Capabilities.EnergyStorage.BLOCK, targetPos, null);
        OpenRadio.LOGGER.info("[probe]   （未指定面）-> {}", unsided == null ? "null" : "可用");

        // 每个可用面各充 500 FE，验证按面限流与累计都正常。
        int accepted = 0;
        for (Direction side : Direction.values()) {
            IEnergyStorage capability = level.getCapability(
                    Capabilities.EnergyStorage.BLOCK, targetPos, side);
            if (capability != null) {
                accepted += capability.receiveEnergy(500, false);
            }
        }
        OpenRadio.LOGGER.info("[probe] 各面共充入 {} FE，当前 {} / {}，开机={}",
                accepted, station.getEnergy(), station.getCapacity(), station.isPowered());

        // 顶上放太阳能，验证"直接叠放就能供电"这条路径。
        energyBeforeSolar = station.getEnergy();
        level.setBlockAndUpdate(targetPos.above(),
                ModBlocks.SOLAR_POWER.get().defaultBlockState());
        OpenRadio.LOGGER.info("[probe] 已在正上方放置太阳能，等待 {} tick 观察电量变化", SOLAR_WAIT);
    }

    private static void reportSolar(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        if (targetPos == null) {
            return;
        }
        BlockEntity blockEntity = level.getBlockEntity(targetPos);
        if (!(blockEntity instanceof FixedStationBlockEntity station)) {
            return;
        }

        // 发电条件是"有天空光 + 白天 + 头顶露天"，三者任一不满足都是 0。
        // 把三项都打出来，否则只能靠猜 —— 而"没发电"和"逻辑写错了"的外部表现完全一样。
        BlockPos solarPos = targetPos.above();
        BlockEntity solarBlock = level.getBlockEntity(solarPos);
        int buffered = solarBlock instanceof com.rinko.openradio.block.SolarPowerBlockEntity solar
                ? solar.getEnergy()
                : -1;
        OpenRadio.LOGGER.info("[probe] 太阳能诊断：天空光={} 白天={} 头顶露天={} 雨={} 缓冲={} FE",
                level.dimensionType().hasSkyLight(),
                level.isDay(),
                level.canSeeSky(solarPos.above()),
                level.getRainLevel(1.0F),
                buffered);

        int delta = station.getEnergy() - energyBeforeSolar;
        OpenRadio.LOGGER.info("[probe] 太阳能观察结果：电量 {} → {}（{} FE，{} tick）",
                energyBeforeSolar, station.getEnergy(), delta, SOLAR_WAIT);
        if (delta <= 0) {
            OpenRadio.LOGGER.warn("[probe] 没有充上电 —— 对照上面三项条件判断是环境问题还是逻辑问题");
        }
        OpenRadio.LOGGER.info("[probe] ---- 探测结束 ----");

        if (savedDayTime != Long.MIN_VALUE) {
            level.setDayTime(savedDayTime);
        }
    }

    private StationProbe() {
    }
}
