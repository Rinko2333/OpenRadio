package com.rinko.openradio.block.antenna;

import javax.annotation.Nullable;

import com.mojang.serialization.MapCodec;
import com.rinko.openradio.radio.AntennaProfile;
import com.rinko.openradio.registry.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 天线基座：多方块结构的锚点。
 *
 * <p>结构从基座正上方开始，必须是<b>连续的延长杆 + 顶端一个振子</b>。
 * 只要中间断一格、或者顶上不是振子，这条天线就无效 —— 玩家会看到增益掉回裸机。
 *
 * <p>为什么结构不做"合法性检查 + 整体消失"那种强约束：天线塌了不该连带方块一起消失，
 * 那只会让玩家丢材料。这里的做法是"结构不成立就只是没有增益"，方块该在哪还在哪，
 * 修好就立刻恢复。
 */
public class AntennaBaseBlock extends BaseEntityBlock {
    public static final MapCodec<AntennaBaseBlock> CODEC = simpleCodec(AntennaBaseBlock::new);

    private static final VoxelShape SHAPE = Shapes.or(
            Block.box(0.0D, 0.0D, 0.0D, 16.0D, 4.0D, 16.0D),
            Block.box(6.0D, 4.0D, 6.0D, 10.0D, 16.0D, 10.0D));

    public AntennaBaseBlock(Properties properties) {
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

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AntennaBaseBlockEntity(pos, state);
    }

    /**
     * 邻居变化时清掉缓存：上面被拆一格、或者顶上换了振子，增益必须立刻变，
     * 而不是等下一次定时重扫。定时重扫只是兜底（比如结构跨了区块边界）。
     */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof AntennaBaseBlockEntity base) {
            base.invalidate();
        }
    }
}
