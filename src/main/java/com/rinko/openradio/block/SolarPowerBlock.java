package com.rinko.openradio.block;

import javax.annotation.Nullable;

import com.mojang.serialization.MapCodec;
import com.rinko.openradio.registry.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 太阳能电源。
 *
 * <p>两种用法都支持，而且互不冲突：
 * <ol>
 *   <li><b>直接放在固定台顶上</b> —— 每 tick 向下方的能量能力推送，不需要任何线缆；</li>
 *   <li><b>接管道</b> —— 它自己也暴露 {@code IEnergyStorage}（只出不进），
 *       可以用线缆把电送到别处。</li>
 * </ol>
 *
 * <p>为什么要有内部缓冲：如果不缓冲，直接"发电即推送"，那么管道从它抽电时
 * 就只能凭空造电（否则两条输出路径会争同一份额度）。有了缓冲，
 * 发电只管往缓冲里写，推送和抽取都从缓冲里拿，能量收支始终平衡。
 */
public class SolarPowerBlock extends BaseEntityBlock {
    public static final MapCodec<SolarPowerBlock> CODEC = simpleCodec(SolarPowerBlock::new);

    public SolarPowerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SolarPowerBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return (lvl, pos, st, be) -> {
            if (be instanceof SolarPowerBlockEntity solar) {
                solar.serverTick();
            }
        };
    }

    /** 供 {@link SolarPowerBlockEntity} 使用：正下方是不是能接电的方块。 */
    static boolean hasEnergyBelow(Level level, BlockPos pos) {
        return level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,
                pos.below(), Direction.UP) != null;
    }
}
