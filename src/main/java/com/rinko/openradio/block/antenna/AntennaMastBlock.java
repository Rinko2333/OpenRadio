package com.rinko.openradio.block.antenna;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;

/**
 * 天线的延长杆。
 *
 * <p>纯粹的高度来源，本身不决定波段。为什么要它单独成一种方块：
 * 玩家应该能"用便宜的杆把振子顶上去"，而不是每加一格就多消耗一个振子。
 * 这让"高度"成为一个可以自由投入的资源，而不是绑定在昂贵的部件上。
 *
 * <p>形状是一根 4×4 的细杆，整格贯穿 —— 叠起来才连续。
 */
public class AntennaMastBlock extends Block {
    private static final VoxelShape SHAPE = Block.box(6.0D, 0.0D, 6.0D, 10.0D, 16.0D, 10.0D);

    public AntennaMastBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }
}
