package com.rinko.openradio.block;

import javax.annotation.Nullable;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 固定电台。
 *
 * <p>{@link #FACING} 指向<b>玩家交互的那一面</b>（操作面板），放置时背对玩家。
 * 能量能力在那一面不暴露，所以 Mekanism 线缆和其他管道会自然绕开正面 ——
 * 玩家不会把线插到面板上，也不需要任何额外的连接规则。
 */
public class FixedStationBlock extends BaseEntityBlock {
    public static final MapCodec<FixedStationBlock> CODEC = simpleCodec(FixedStationBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    public FixedStationBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 正面朝向玩家：玩家面对哪边，面板就朝哪边。
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FixedStationBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> type) {
        // 待机耗电只在服务端结算。客户端完全是看客。
        if (level.isClientSide) {
            return null;
        }
        return (lvl, pos, st, be) -> {
            if (be instanceof FixedStationBlockEntity station) {
                station.serverTick();
            }
        };
    }

    /**
     * 右键：开关机。
     *
     * <p>没有电就不让开机 —— 一台"看起来开着但什么都不做"的机器比明确的拒绝更难排查。
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof FixedStationBlockEntity station)) {
            return InteractionResult.PASS;
        }

        if (!station.isPowered()) {
            if (station.getEnergy() <= 0) {
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable("message.openradio.station.no_power"),
                        true);
                return InteractionResult.CONSUME;
            }
            station.setPowered(true);
        } else {
            station.setPowered(false);
        }

        player.displayClientMessage(station.describe(), true);
        return InteractionResult.CONSUME;
    }

    /** 旁边接了/拆了馈线，天线缓存必须失效，否则要等下一次定时重扫。 */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof FixedStationBlockEntity station) {
            station.invalidateAntenna();
        }
    }
}
