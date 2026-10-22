package com.rinko.openradio.block;

import com.rinko.openradio.Config;
import com.rinko.openradio.registry.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * 太阳能电源的实体。
 *
 * <p>每个 tick 做两件事：按日照发电进缓冲、把缓冲里的电推给正下方的能量能力。
 * 推送是"主动"的，所以直接叠在固定台顶上就能用，不需要任何线缆。
 *
 * <p>为什么不每 N tick 结算一次：固定台的接收端本来就是按 FE/t 限流的，
 * 这里每 tick 给一点反而更平滑，也不会在管道计数上出现"每次一大坨"的尖峰。
 */
public class SolarPowerBlockEntity extends BlockEntity {
    private static final String TAG_ENERGY = "energy";

    /** 缓冲约为 100 tick 的发电量；配置变了也会跟着变，但至少 1000 起步。 */
    private static final int MIN_BUFFER = 1_000;
    private static final int BUFFER_TICKS = 100;

    private int energy;

    public SolarPowerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SOLAR_POWER.get(), pos, state);
    }

    public int getEnergy() {
        return energy;
    }

    public int getCapacity() {
        return Math.max(MIN_BUFFER, Config.SOLAR_FE_PER_TICK.get() * BUFFER_TICKS);
    }

    public void serverTick() {
        Level level = getLevel();
        if (level == null || level.isClientSide) {
            return;
        }
        generate(level);
        pushDown(level);
    }

    private void generate(Level level) {
        int perTick = Config.SOLAR_FE_PER_TICK.get();
        if (perTick <= 0) {
            return;
        }
        double factor = sunlight(level);
        if (factor <= 0.0D) {
            return;
        }
        int produced = (int) Math.round(perTick * factor);
        if (produced > 0) {
            energy = Math.min(getCapacity(), energy + produced);
            setChanged();
        }
    }

    /**
     * 当前日照系数。
     *
     * <p>没有天空光的世界（下界、末地）直接 0 —— 那里本来就没有太阳。
     * 上方被遮挡也是 0，所以把它埋进山洞里会安静地不发电，而不是偷偷发电。
     */
    private double sunlight(Level level) {
        if (!level.dimensionType().hasSkyLight()) {
            return 0.0D;
        }
        if (!level.canSeeSky(worldPosition.above())) {
            return 0.0D;
        }
        if (!level.isDay()) {
            return 0.0D;
        }
        // 下雨按 1/3 计，雷暴再低一些。用 getRainLevel 而不是天气枚举，
        // 这样天气渐变的过渡也会反映在发电量上。
        float rain = level.getRainLevel(1.0F);
        if (rain <= 0.0F) {
            return 1.0D;
        }
        return Math.max(0.05D, 1.0D - 0.67D * rain);
    }

    /** 优先推给正下方 —— 这是"放顶上就能用"的那条路径。 */
    private void pushDown(Level level) {
        if (energy <= 0) {
            return;
        }
        IEnergyStorage below = level.getCapability(Capabilities.EnergyStorage.BLOCK,
                worldPosition.below(), Direction.UP);
        if (below == null) {
            return;
        }
        int offer = Math.min(energy, Math.max(1, Config.SOLAR_FE_PER_TICK.get() * 4));
        int accepted = below.receiveEnergy(offer, false);
        if (accepted > 0) {
            energy -= accepted;
            setChanged();
        }
    }

    /** 给管道用的能力：只出不进。面板自己不耗电，所以也没有内部消耗。 */
    private final IEnergyStorage extractOnly = new IEnergyStorage() {
        @Override
        public int receiveEnergy(int amount, boolean simulate) {
            return 0;
        }

        @Override
        public int extractEnergy(int amount, boolean simulate) {
            int taken = Math.min(energy, Math.max(0, amount));
            if (taken > 0 && !simulate) {
                energy -= taken;
                setChanged();
            }
            return taken;
        }

        @Override
        public int getEnergyStored() {
            return energy;
        }

        @Override
        public int getMaxEnergyStored() {
            return getCapacity();
        }

        @Override
        public boolean canExtract() {
            return true;
        }

        @Override
        public boolean canReceive() {
            return false;
        }
    };

    public IEnergyStorage energyStorage() {
        return extractOnly;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt(TAG_ENERGY, energy);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energy = Math.max(0, Math.min(getCapacity(), tag.getInt(TAG_ENERGY)));
    }
}
