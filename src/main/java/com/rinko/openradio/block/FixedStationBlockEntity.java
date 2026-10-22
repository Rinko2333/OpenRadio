package com.rinko.openradio.block;

import javax.annotation.Nullable;

import com.mojang.serialization.MapCodec;
import com.rinko.openradio.Config;
import com.rinko.openradio.radio.RadioConstants;
import com.rinko.openradio.registry.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * 固定台的核心逻辑：储能、频率、开机状态、天线。
 *
 * <h2>为什么不装电池</h2>
 *
 * <p>手台靠可换电池，因为它要随身携带；固定台钉在地上，天生应该接电。所以它没有
 * "电池物品"这个概念，只有一个内部电容。少一个可丢可复制的物品，就少一片漏洞面。
 *
 * <h2>为什么用 NeoForge Energy 而不是自己造一套</h2>
 *
 * <p>{@code Capabilities.EnergyStorage.BLOCK} 是跨 mod 的通用接口。暴露它之后，
 * Mekanism 的通用线缆、热力膨胀的能量管道、以及任何走 Forge/NeoForge Energy 的
 * 发电机都能直接接上，<b>不需要为任何一个 mod 写适配代码</b>。
 * （Mekanism 内部用 Joules，但它会在接 FE 设备时自己换算。）
 *
 * <h2>正面不接管道</h2>
 *
 * <p>{@link #storageFor} 在正面返回 {@code null}，能力注册处据此拒绝连接 ——
 * 原版能力系统对"该面没有能力"的语义就是 null，线缆自然不会连上来。
 * 这是靠数据表达，不是靠事件拦截，所以不依赖任何 mod 的实现细节。
 */
public class FixedStationBlockEntity extends BlockEntity {
    private static final String TAG_ENERGY = "energy";
    private static final String TAG_FREQUENCY = "frequency";
    private static final String TAG_POWERED = "powered";

    private int energy;
    private int frequency = RadioConstants.DEFAULT_FREQUENCY;
    private boolean powered;

    /** 待机耗电的小数余额。按 tick 扣 0.x FE 时，直接取整会永远是 0。 */
    private double idleResidual;

    /** 按面缓存，避免每次能力查询都新建对象 —— 管道是每 tick 查的。 */
    private final IEnergyStorage[] sidedStorage = new IEnergyStorage[6];
    private final IEnergyStorage unsidedStorage;

    public FixedStationBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FIXED_STATION.get(), pos, state);
        for (Direction side : Direction.values()) {
            sidedStorage[side.ordinal()] = new SidedStorage(side);
        }
        unsidedStorage = new SidedStorage(null);
    }

    // ------------------------------------------------------------ 能量

    public int getEnergy() {
        return energy;
    }

    public int getCapacity() {
        return Config.STATION_CAPACITY.get();
    }

    public float getEnergyRatio() {
        int capacity = getCapacity();
        return capacity <= 0 ? 0.0F : (float) energy / capacity;
    }

    /** 能不能发射。低于阈值只限制发射，接收照样可用 —— 不做变砖。 */
    public boolean canTransmit() {
        return getEnergyRatio() >= Config.ENERGY_LOW_THRESHOLD.get();
    }

    /**
     * 取某个面的能量能力。
     *
     * @param side {@code null} 表示"不指定面"（其他 mod 的整体查询、比较器等）
     * @return 正面返回 {@code null}，其余面返回该面的限流包装
     */
    @Nullable
    public IEnergyStorage storageFor(@Nullable Direction side) {
        if (side == null) {
            return unsidedStorage;
        }
        if (side == facing()) {
            return null;
        }
        return sidedStorage[side.ordinal()];
    }

    /** 方块朝向。放在默认值上兜底 —— 方块被移除后 {@code getBlockState()} 会是空气。 */
    private Direction facing() {
        BlockState state = getBlockState();
        return state.hasProperty(FixedStationBlock.FACING)
                ? state.getValue(FixedStationBlock.FACING)
                : Direction.NORTH;
    }

    private int receiveInternal(int amount) {
        int accepted = Math.min(getCapacity() - energy, Math.max(0, amount));
        if (accepted > 0) {
            energy += accepted;
            setChanged();
        }
        return accepted;
    }

    private int drainInternal(int amount) {
        int taken = Math.min(energy, Math.max(0, amount));
        if (taken > 0) {
            energy -= taken;
            setChanged();
        }
        return taken;
    }

    /** 扣电。给将来的发射逻辑用。 */
    public int drain(int amount) {
        return drainInternal(amount);
    }

    private void setEnergyDirect(int value) {
        energy = Math.max(0, Math.min(getCapacity(), value));
        setChanged();
    }

    // ------------------------------------------------------------ tick

    public void serverTick() {
        if (!powered) {
            return;
        }
        double perMinute = Config.STATION_IDLE_DRAIN_PER_MINUTE.get();
        if (perMinute <= 0.0D) {
            return;
        }
        idleResidual += perMinute / 1200.0D;
        if (idleResidual >= 1.0D) {
            int whole = (int) idleResidual;
            idleResidual -= whole;
            drainInternal(whole);
        }
    }

    // ------------------------------------------------------------ 状态

    public int getFrequency() {
        return frequency;
    }

    public void setFrequency(int value) {
        frequency = RadioConstants.clampFrequency(value);
        setChanged();
    }

    public boolean isPowered() {
        return powered;
    }

    public void setPowered(boolean value) {
        if (powered == value) {
            return;
        }
        powered = value;
        idleResidual = 0.0D;
        setChanged();
    }

    /** 给右键交互用的简短状态描述。GUI 做好之前先这样反馈。 */
    public Component describe() {
        return Component.translatable("message.openradio.station.status",
                String.format("%05d", frequency),
                String.format("%,d", energy),
                String.format("%,d", getCapacity()),
                Component.translatable(powered ? "gui.openradio.on" : "gui.openradio.off"));
    }

    // ------------------------------------------------------------ 持久化

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt(TAG_ENERGY, energy);
        tag.putInt(TAG_FREQUENCY, frequency);
        tag.putBoolean(TAG_POWERED, powered);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        setEnergyDirect(tag.getInt(TAG_ENERGY));
        frequency = tag.contains(TAG_FREQUENCY)
                ? RadioConstants.clampFrequency(tag.getInt(TAG_FREQUENCY))
                : RadioConstants.DEFAULT_FREQUENCY;
        powered = tag.getBoolean(TAG_POWERED);
    }

    /**
     * 单个面的能量能力。
     *
     * <p>只进不出：固定台是耗电终端，不是储能设备。允许抽取的话，
     * 玩家把它接到电网上就能反向"偷电"，而它的容量比电池大得多。
     */
    private final class SidedStorage implements IEnergyStorage {
        @Nullable
        private final Direction side;

        private SidedStorage(@Nullable Direction side) {
            this.side = side;
        }

        @Override
        public int receiveEnergy(int amount, boolean simulate) {
            if (amount <= 0) {
                return 0;
            }
            int limited = Math.min(amount, Config.STATION_MAX_RECEIVE.get());
            if (simulate) {
                return Math.min(getCapacity() - energy, limited);
            }
            return receiveInternal(limited);
        }

        @Override
        public int extractEnergy(int amount, boolean simulate) {
            return 0;
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
            return false;
        }

        @Override
        public boolean canReceive() {
            // 正面不接管道：那一面是操作面板。
            return side != facing();
        }
    }
}
