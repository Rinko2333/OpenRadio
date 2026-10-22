package com.rinko.openradio.block;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.serialization.MapCodec;
import com.rinko.openradio.Config;
import com.rinko.openradio.block.antenna.AntennaBaseBlockEntity;
import com.rinko.openradio.radio.AntennaProfile;
import com.rinko.openradio.radio.FeederNetwork;
import com.rinko.openradio.radio.FixedStationIndex;
import com.rinko.openradio.radio.RadioConstants;
import com.rinko.openradio.radio.RadioMessage;
import com.rinko.openradio.radio.TextDegrader;
import com.rinko.openradio.registry.ModBlockEntities;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
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
    private static final String TAG_ID = "station_id";

    /** 收到消息时"喊"出去的半径。临时方案，M6.4 的界面会替代它。 */
    private static final double SPEAKER_RADIUS = 16.0D;
    /** 收件箱上限。不落盘，重启就没了 —— 它是给界面看的近期记录，不是存档数据。 */
    private static final int MAX_INBOX = 16;

    private int energy;
    private int frequency = RadioConstants.DEFAULT_FREQUENCY;
    private boolean powered;

    /**
     * 这台电台的永久身份。
     *
     * <p>存在方块实体自己身上而不是全局表里：表是"当前加载了哪些"，重启就空；
     * 身份必须跨重启稳定，否则区块卸载再加载就会换一个 UUID，别人记着的链路全断。
     */
    @Nullable
    private UUID stationId;

    /** 最近收到的消息，最新的在前。 */
    private final List<LoggedMessage> inbox = new ArrayList<>();

    /** 待机耗电的小数余额。按 tick 扣 0.x FE 时，直接取整会永远是 0。 */
    private double idleResidual;

    /** 天线查找结果缓存。馈线网络的 BFS 不该每 tick 跑一次。 */
    @Nullable
    private AntennaProfile antennaCache;
    private long antennaCachedAt = Long.MIN_VALUE;
    private static final int ANTENNA_REFRESH_TICKS = 20;

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

    // ------------------------------------------------------------ 身份与生命周期

    /** 稳定的设备 ID。第一次被访问时生成，之后随存档带走。 */
    public UUID stationId() {
        if (stationId == null) {
            stationId = UUID.randomUUID();
            setChanged();
        }
        return stationId;
    }

    /**
     * 区块加载时登记到索引。
     *
     * <p>用 {@code onLoad} 而不是在构造里登记：构造发生在区块反序列化过程中，
     * 那时 {@code getLevel()} 可能还没设置好。
     */
    @Override
    public void onLoad() {
        super.onLoad();
        // 先把身份落地：万一放下来之后一直没人查询链路，保存时也该带着 UUID。
        stationId();
        if (getLevel() instanceof ServerLevel) {
            FixedStationIndex.register(this);
        }
    }

    /**
     * 方块被破坏或区块卸载时注销。
     *
     * <p>{@code setRemoved} 两种情况下都会调用，正好对应索引需要的语义 ——
     * 索引只反映"现在加载着哪些"，不试图记录"世界上存在哪些"。
     */
    @Override
    public void setRemoved() {
        if (getLevel() instanceof ServerLevel) {
            FixedStationIndex.unregister(this);
        }
        super.setRemoved();
    }

    // ------------------------------------------------------------ 收发

    /**
     * 收到一条同频消息。
     *
     * <p>降级和其他接收者一样按 Q 计算 —— 固定台不该有"听得比别人清楚"的特权，
     * 它的优势应该来自天线和功率，而不是代码里的特例。
     */
    public void receive(RadioMessage message) {
        if (!(getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        String degraded = TextDegrader.degrade(message.text(), message.quality(), serverLevel.getRandom());

        inbox.add(0, new LoggedMessage(message.senderName(), message.quality(), degraded,
                serverLevel.getGameTime()));
        while (inbox.size() > MAX_INBOX) {
            inbox.remove(inbox.size() - 1);
        }

        // 临时表现：让站在旁边的人听见。真机上固定台确实是个喇叭，
        // 但这终究是占位 —— M6.4 的界面才是它该有的样子。
        Component line = Component.literal("[")
                .append(Component.translatable("block.openradio.fixed_station"))
                .append(Component.literal("] "))
                .append(Component.literal(message.senderName()).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(": ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(degraded).withStyle(ChatFormatting.GRAY));

        Vec3 center = Vec3.atCenterOf(worldPosition);
        for (ServerPlayer near : serverLevel.players()) {
            if (near.distanceToSqr(center) <= SPEAKER_RADIUS * SPEAKER_RADIUS) {
                near.displayClientMessage(line, false);
            }
        }
    }

    /** 收件箱快照。界面会用它，现在只给诊断日志用。 */
    public List<LoggedMessage> inbox() {
        return List.copyOf(inbox);
    }

    /** 一条已降级的收信记录。 */
    public record LoggedMessage(String senderName, double quality, String text, long gameTime) {
    }

    // ------------------------------------------------------------ 天线

    /**
     * 这台电台接到的天线。
     *
     * <p>沿馈线走一遍找基座，结果缓存 1 秒。客户端不跑 BFS —— 它没有完整的区块数据，
     * 而且界面显示会走服务端同步过来的值。
     */
    public AntennaProfile antenna() {
        Level level = getLevel();
        if (level == null || level.isClientSide) {
            return AntennaProfile.EMPTY;
        }
        long now = level.getGameTime();
        if (antennaCache == null || now - antennaCachedAt >= ANTENNA_REFRESH_TICKS) {
            AntennaBaseBlockEntity base = FeederNetwork.findAntenna(level, worldPosition);
            antennaCache = base == null ? AntennaProfile.EMPTY : base.profile();
            antennaCachedAt = now;
        }
        return antennaCache;
    }

    /** 旁边方块变了（比如接了/拆了馈线），下一次查询必须重新走网络。 */
    public void invalidateAntenna() {
        antennaCache = null;
        antennaCachedAt = Long.MIN_VALUE;
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
        AntennaProfile antenna = antenna();
        Component antennaText = antenna.present()
                ? Component.translatable("message.openradio.station.antenna",
                        Component.translatable(antenna.element().translationKey()), antenna.height())
                : Component.translatable("message.openradio.station.no_antenna");

        return Component.translatable("message.openradio.station.status",
                String.format("%05d", frequency),
                String.format("%,d", energy),
                String.format("%,d", getCapacity()),
                Component.translatable(powered ? "gui.openradio.on" : "gui.openradio.off"),
                antennaText);
    }

    // ------------------------------------------------------------ 持久化

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt(TAG_ENERGY, energy);
        tag.putInt(TAG_FREQUENCY, frequency);
        tag.putBoolean(TAG_POWERED, powered);
        if (stationId != null) {
            tag.putUUID(TAG_ID, stationId);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        setEnergyDirect(tag.getInt(TAG_ENERGY));
        frequency = tag.contains(TAG_FREQUENCY)
                ? RadioConstants.clampFrequency(tag.getInt(TAG_FREQUENCY))
                : RadioConstants.DEFAULT_FREQUENCY;
        powered = tag.getBoolean(TAG_POWERED);
        // 老存档没有这个字段：留空，第一次 stationId() 时补上。
        if (tag.contains(TAG_ID, Tag.TAG_INT_ARRAY)) {
            stationId = tag.getUUID(TAG_ID);
        }
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
