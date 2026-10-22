package com.rinko.openradio.block.feeder;

import java.util.Map;

import com.mojang.serialization.MapCodec;
import com.rinko.openradio.block.FixedStationBlock;
import com.rinko.openradio.block.antenna.AntennaBaseBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 馈线：把天线基座和电台连起来的射频线。
 *
 * <h2>为什么它没有方块实体</h2>
 *
 * <p>真实线缆最容易写坏的地方不是渲染，是"网络状态要维护"。但只要连接关系完全由
 * <b>邻居方块</b>推导出来，就不需要任何持久状态：这条线连没连到天线，
 * 是电台沿着线走一遍得出的（见 {@code FeederNetwork}），不是线自己记的。
 *
 * <p>于是整个方块退化成一个带六向布尔属性的普通方块 —— 可放置、可破坏、
 * 能被活塞推、能随区块正常卸载，所有原版机制都不需要额外处理。
 *
 * <h2>正面不接</h2>
 *
 * <p>固定台的正面是操作面板（{@code FACING} 指向那面），馈线不连上去。
 * 这和能量管道绕开正面是同一个规则的两次应用 —— 玩家不用记两套习惯。
 */
public class FeederCableBlock extends Block {
    public static final MapCodec<FeederCableBlock> CODEC = simpleCodec(FeederCableBlock::new);
    public static final Map<Direction, BooleanProperty> PROPERTY_BY_DIRECTION =
            PipeBlock.PROPERTY_BY_DIRECTION;

    private static final VoxelShape CORE = Block.box(6.0D, 6.0D, 6.0D, 10.0D, 10.0D, 10.0D);

    /** 按方向预计算臂的形状：中心到各面，止于核心边缘，避免与核心重叠产生 z-fighting。 */
    private static final VoxelShape[] ARMS = new VoxelShape[6];

    /** 64 种连接组合的形状全部预先算好 —— getShape 是渲染热路径，不能每次都 Shapes.or。 */
    private static final VoxelShape[] SHAPES = new VoxelShape[64];

    static {
        for (Direction side : Direction.values()) {
            ARMS[side.ordinal()] = switch (side) {
                case NORTH -> Block.box(6.0D, 6.0D, 0.0D, 10.0D, 10.0D, 6.0D);
                case SOUTH -> Block.box(6.0D, 6.0D, 10.0D, 10.0D, 10.0D, 16.0D);
                case WEST -> Block.box(0.0D, 6.0D, 6.0D, 6.0D, 10.0D, 10.0D);
                case EAST -> Block.box(10.0D, 6.0D, 6.0D, 16.0D, 10.0D, 10.0D);
                case DOWN -> Block.box(6.0D, 0.0D, 6.0D, 10.0D, 6.0D, 10.0D);
                case UP -> Block.box(6.0D, 10.0D, 6.0D, 10.0D, 16.0D, 10.0D);
            };
        }
        for (int mask = 0; mask < 64; mask++) {
            VoxelShape shape = CORE;
            for (Direction side : Direction.values()) {
                if ((mask & (1 << side.ordinal())) != 0) {
                    shape = Shapes.or(shape, ARMS[side.ordinal()]);
                }
            }
            SHAPES[mask] = shape;
        }
    }

    public FeederCableBlock(Properties properties) {
        super(properties);
        BlockState state = stateDefinition.any();
        for (BooleanProperty property : PROPERTY_BY_DIRECTION.values()) {
            state = state.setValue(property, false);
        }
        registerDefaultState(state);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PROPERTY_BY_DIRECTION.values().toArray(BooleanProperty[]::new));
    }

    /** 放置时逐个方向判断 —— 原版不会在放下的瞬间调 updateShape。 */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState();
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        for (Direction side : Direction.values()) {
            state = state.setValue(PROPERTY_BY_DIRECTION.get(side), connects(level, pos, side));
        }
        return state;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     net.minecraft.world.level.LevelAccessor level, BlockPos pos,
                                     BlockPos neighborPos) {
        return state.setValue(PROPERTY_BY_DIRECTION.get(direction),
                connectsTo(neighborState, direction.getOpposite()));
    }

    private static boolean connects(Level level, BlockPos pos, Direction side) {
        BlockPos neighborPos = pos.relative(side);
        // 未加载就当作不连 —— 绝不让 getBlockState 在这里触发区块加载。
        if (!level.isLoaded(neighborPos)) {
            return false;
        }
        return connectsTo(level.getBlockState(neighborPos), side.getOpposite());
    }

    /**
     * @param neighborFace 从邻居方块看向我们的方向
     */
    private static boolean connectsTo(BlockState neighbor, Direction neighborFace) {
        Block block = neighbor.getBlock();
        if (block instanceof FeederCableBlock || block instanceof AntennaBaseBlock) {
            return true;
        }
        if (block instanceof FixedStationBlock) {
            // 正面是面板，不接线。FACING 缺失时保守地当作可以连。
            return !neighbor.hasProperty(FixedStationBlock.FACING)
                    || neighbor.getValue(FixedStationBlock.FACING) != neighborFace;
        }
        return false;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        int mask = 0;
        for (Direction side : Direction.values()) {
            if (state.getValue(PROPERTY_BY_DIRECTION.get(side))) {
                mask |= 1 << side.ordinal();
            }
        }
        return SHAPES[mask];
    }
}
