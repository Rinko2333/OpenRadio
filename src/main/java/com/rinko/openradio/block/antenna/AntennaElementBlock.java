package com.rinko.openradio.block.antenna;

import com.rinko.openradio.radio.AntennaKind;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 天线的振子：整座结构里唯一决定波段的那一格。
 *
 * <p>只认 {@link AntennaKind} 里的四种"真天线"，{@link AntennaKind#BARE} 是
 * "什么都没有"的占位，不该有方块形态 —— 所以构造时不做校验，但注册处只给四个。
 *
 * <p>形状是"杆 + 顶部十字"：十字是振子的视觉标识，也让它一眼就能和延长杆区分开。
 * 十字比杆粗一圈，玩家从下面抬头就能看出这根天线是为哪个波段架的。
 */
public class AntennaElementBlock extends Block {
    private static final VoxelShape SHAPE = Shapes.or(
            Block.box(6.0D, 0.0D, 6.0D, 10.0D, 16.0D, 10.0D),
            Block.box(2.0D, 12.0D, 6.0D, 14.0D, 15.0D, 10.0D),
            Block.box(6.0D, 12.0D, 2.0D, 10.0D, 15.0D, 14.0D));

    private final AntennaKind kind;

    public AntennaElementBlock(AntennaKind kind, Properties properties) {
        super(properties);
        this.kind = kind;
    }

    public AntennaKind kind() {
        return kind;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }
}
