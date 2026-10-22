package com.rinko.openradio.item;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.rinko.openradio.Config;
import com.rinko.openradio.block.FixedStationBlockEntity;
import com.rinko.openradio.radio.FixedStationIndex;
import com.rinko.openradio.registry.ModDataComponents;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 无线手咪。固定台的话筒。
 *
 * <h2>它和手持电台的区别</h2>
 *
 * <p>手台是一台完整的电台：有自己的频率、开关机、电池、文本收发。手咪<b>什么都没有</b> ——
 * 没有频率、没有开关机、不能发文本。它只做一件事：把玩家的语音接到绑定的固定台上，
 * 用那台机器的大天线和频率收发。
 *
 * <p>这不是偷懒，是刻意的：手咪一旦有了自己的频率，就必然要有"调频"这个操作，
 * 于是玩家会遇到"手咪频率和固定台对不上，怎么不说话"这种毫无乐趣的故障。
 * 让它完全跟随固定台之后，玩家心里的模型只有一句话：<b>我在用这台机器</b>。
 *
 * <h2>为什么绑定存 UUID 而不是坐标</h2>
 *
 * <p>坐标会被"拆了在原地重建"骗到：原地盖一台新机器（可能是别人的、可能波段完全不同），
 * 手咪会静默地绑上去，玩家看起来一切正常，实际已经连到别的台了。
 * UUID 则让新机器是一个新身份，手咪会明确报"绑定的固定台已不存在"。
 *
 * <h2>为什么绑定要蹲下</h2>
 *
 * <p>固定台右键是开界面。不蹲的话，拿着手咪点固定台会同时触发绑定和开界面，
 * 而绑定是个会改动持久状态的操作，不该藏在"顺手点一下"里。
 */
public class HandheldMicItem extends Item {
    public HandheldMicItem(Properties properties) {
        super(properties);
    }

    /**
     * 和手台同样的理由：电量变化不该被当成"换了物品"。
     *
     * <p>手咪每帧语音都会扣电，不覆写这个方法的话，说话期间视角里的物品会持续
     * 下沉再抬起 —— 比手台那次更明显，因为它是连续扣电而不是每 10 tick 一次。
     */
    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged || !ItemStack.isSameItem(oldStack, newStack);
    }

    // ------------------------------------------------------------ 电量

    public static int capacity() {
        return Config.MIC_CAPACITY.get();
    }

    /**
     * 当前电量。
     *
     * <p><b>没有这个组件 = 满电。</b>手咪内置电池、没有"装电池"这一步，
     * 所以刚合成出来的手咪本来就不该带一个"0 电"的组件 ——
     * 那会让玩家以为物品是坏的。配方里也没法写"充满"，因为容量是配置项，
     * 写死一个数字会在管理员改了容量之后对不上。
     */
    public static int stored(ItemStack mic) {
        Integer value = mic.get(ModDataComponents.ENERGY.get());
        return value == null ? capacity() : value;
    }

    public static void setStored(ItemStack mic, int energy) {
        mic.set(ModDataComponents.ENERGY.get(), Mth.clamp(energy, 0, capacity()));
    }

    public static float ratio(ItemStack mic) {
        int cap = capacity();
        return cap <= 0 ? 0.0F : Mth.clamp((float) stored(mic) / cap, 0.0F, 1.0F);
    }

    /** 电量是否足以发射。低于阈值只限制发射，收听照旧 —— 不做变砖。 */
    public static boolean canTransmit(ItemStack mic) {
        return ratio(mic) >= Config.ENERGY_LOW_THRESHOLD.get();
    }

    public static int drain(ItemStack mic, int amount) {
        if (amount <= 0) {
            return 0;
        }
        int have = stored(mic);
        int taken = Math.min(have, amount);
        if (taken > 0) {
            setStored(mic, have - taken);
        }
        return taken;
    }

    public static int charge(ItemStack mic, int amount) {
        if (amount <= 0) {
            return 0;
        }
        int have = stored(mic);
        int accepted = Math.min(capacity() - have, amount);
        if (accepted > 0) {
            setStored(mic, have + accepted);
        }
        return Math.max(0, accepted);
    }

    // ------------------------------------------------------------ 绑定

    /** 全新手咪。不带 ENERGY 组件，也就是满电 —— 见 {@link #stored(ItemStack)}。 */
    public static ItemStack fresh(Item item) {
        return new ItemStack(item);
    }

    @Nullable
    public static UUID boundStation(ItemStack mic) {
        return mic.get(ModDataComponents.MIC_STATION.get());
    }

    public static boolean isBound(ItemStack mic) {
        return boundStation(mic) != null;
    }

    public static void bind(ItemStack mic, UUID stationId) {
        mic.set(ModDataComponents.MIC_STATION.get(), stationId);
    }

    public static void unbind(ItemStack mic) {
        mic.remove(ModDataComponents.MIC_STATION.get());
    }

    // ------------------------------------------------------------ 查找

    /**
     * 找玩家手里的手咪。只看双手 —— 和手台一致。
     *
     * <p>不做"放包里也算"：那样玩家背包里塞着三只手咪时，
     * 到底哪只在工作会变成一个没法解释的问题。
     */
    public static ItemStack findMic(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof HandheldMicItem) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    // ------------------------------------------------------------ 生效判定

    /**
     * 手咪此刻能不能用。
     *
     * <p>四个条件全部在服务端重新判定，客户端说什么都不算数：
     * <ol>
     *   <li>绑过固定台</li>
     *   <li>那台固定台在当前维度、当前加载着（区块没被卸载）</li>
     *   <li>固定台开着机并且有电</li>
     *   <li>玩家在 {@link Config#MIC_REACH} 格以内</li>
     * </ol>
     *
     * @return 可用的固定台；任何一条不满足都返回 {@code null}
     */
    @Nullable
    public static FixedStationBlockEntity resolve(Player player, ItemStack mic) {
        UUID stationId = boundStation(mic);
        if (stationId == null) {
            return null;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return null;
        }
        FixedStationBlockEntity station = FixedStationIndex.find(level, stationId);
        if (station == null || station.isRemoved() || !station.isPowered()) {
            return null;
        }
        if (distanceSqr(player, station.getBlockPos()) > reachSqr()) {
            return null;
        }
        return station;
    }

    public static double reach() {
        return Config.MIC_REACH.get();
    }

    public static double reachSqr() {
        double reach = reach();
        return reach * reach;
    }

    public static double distanceSqr(Player player, BlockPos pos) {
        return player.distanceToSqr(Vec3.atCenterOf(pos));
    }

    /**
     * 手咪当前的状态，用于给玩家一句明确的话。
     *
     * <p>区分的不是"好/坏"，而是<b>四种完全不同的下一步</b>：
     * 没绑就去绑、超出范围就靠近、固定台没开就去开、没电就充电。
     * 只说"手咪不可用"等于什么都没说。
     */
    public enum Status {
        /** 能用。 */
        LINKED,
        /** 没绑过固定台。 */
        UNBOUND,
        /** 绑过，但那台机器现在找不到（被拆了 / 区块没加载 / 不在这个维度）。 */
        STATION_GONE,
        /** 机器在，但没开机。 */
        STATION_OFF,
        /** 都正常，就是走太远了。 */
        TOO_FAR
    }

    public static Status statusOf(Player player, ItemStack mic) {
        UUID stationId = boundStation(mic);
        if (stationId == null) {
            return Status.UNBOUND;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return Status.STATION_GONE;
        }
        FixedStationBlockEntity station = FixedStationIndex.find(level, stationId);
        if (station == null || station.isRemoved()) {
            return Status.STATION_GONE;
        }
        if (!station.isPowered()) {
            return Status.STATION_OFF;
        }
        if (distanceSqr(player, station.getBlockPos()) > reachSqr()) {
            return Status.TOO_FAR;
        }
        return Status.LINKED;
    }

    // ------------------------------------------------------------ 交互

    /**
     * 蹲下右键固定台 = 绑定。
     *
     * <p>不蹲就 {@code PASS}，让固定台自己的右键（开界面）正常走。
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!(level.getBlockEntity(pos) instanceof FixedStationBlockEntity station)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            // 客户端只负责让动作看起来成功，真正的状态改在服务端。
            return InteractionResult.SUCCESS;
        }

        ItemStack mic = context.getItemInHand();
        UUID previous = boundStation(mic);
        bind(mic, station.stationId());
        level.playSound(null, player.blockPosition(), SoundEvents.NOTE_BLOCK_PLING.value(),
                SoundSource.PLAYERS, 0.6F, 1.6F);

        if (previous == null) {
            player.displayClientMessage(Component.translatable(
                    "message.openradio.mic.bound", formatPos(pos)).withStyle(ChatFormatting.GREEN), false);
        } else {
            player.displayClientMessage(Component.translatable(
                    "message.openradio.mic.rebound", formatPos(pos)).withStyle(ChatFormatting.YELLOW), false);
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * 蹲下右键空气 = 解绑。
     *
     * <p>需要一个"离开这台机器"的动作，否则手咪一旦绑上就永远跟着那台固定台，
     * 而玩家可能只是临时借用别人的台站。
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack mic = player.getItemInHand(hand);
        if (!player.isShiftKeyDown() || !isBound(mic)) {
            return InteractionResultHolder.pass(mic);
        }
        if (!level.isClientSide) {
            unbind(mic);
            player.displayClientMessage(Component.translatable("message.openradio.mic.unbound")
                    .withStyle(ChatFormatting.GRAY), false);
            level.playSound(null, player.blockPosition(), SoundEvents.NOTE_BLOCK_BASS.value(),
                    SoundSource.PLAYERS, 0.6F, 0.8F);
        }
        return InteractionResultHolder.sidedSuccess(mic, level.isClientSide);
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    // ------------------------------------------------------------ 提示

    @Override
    public void appendHoverText(ItemStack mic, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.openradio.mic.energy",
                        String.format("%,d", stored(mic)), String.format("%,d", capacity()))
                .withStyle(ChatFormatting.GRAY));
        // 客户端拿不到服务端的设备索引，所以这里只能说"绑没绑过"。
        // 具体连没连上由快捷栏上方那行状态文字负责，那里是服务端判定的。
        lines.add(Component.translatable(isBound(mic)
                        ? "tooltip.openradio.mic.bound"
                        : "tooltip.openradio.mic.unbound")
                .withStyle(isBound(mic) ? ChatFormatting.DARK_AQUA : ChatFormatting.DARK_GRAY));
    }
}
