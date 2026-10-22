package com.rinko.openradio.block.antenna;

import com.rinko.openradio.Config;
import com.rinko.openradio.radio.AntennaProfile;
import com.rinko.openradio.registry.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 天线基座的实体：负责扫描结构、缓存结果。
 *
 * <h2>缓存策略</h2>
 *
 * <p>两层：邻居变化时立即失效（{@link #invalidate()}），外加一个定时兜底重扫。
 * 只有前者不够 —— 结构可能跨区块，远处那一格被拆掉，基座这边收不到邻居通知；
 * 只靠后者也不够 —— 玩家拆掉杆子后要等半秒才看到增益变化，会被当成 bug。
 * 两个一起用才是最省事又不出错的组合。
 */
public class AntennaBaseBlockEntity extends BlockEntity {
    /** 兜底重扫间隔。20 tick = 1 秒，足够快，也不会造成负担。 */
    private static final int REFRESH_TICKS = 20;

    private AntennaProfile cached = AntennaProfile.EMPTY;
    private long cachedAt = Long.MIN_VALUE;
    private boolean dirty = true;

    public AntennaBaseBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ANTENNA_BASE.get(), pos, state);
    }

    public void invalidate() {
        dirty = true;
    }

    /**
     * 当前结构的天线参数。
     *
     * <p>服务端每传播周期会问一次，所以必须便宜：正常情况下直接返回缓存，
     * 只有失效或过期才真的去扫描。
     */
    public AntennaProfile profile() {
        Level level = getLevel();
        if (level == null) {
            return AntennaProfile.EMPTY;
        }
        long now = level.getGameTime();
        if (!dirty && now - cachedAt < REFRESH_TICKS) {
            return cached;
        }
        cached = scan(level);
        cachedAt = now;
        dirty = false;
        return cached;
    }

    /**
     * 从基座正上方开始往上扫：连续延长杆，最后必须是一个振子。
     *
     * @return 结构成立时返回振子种类与总高度；否则 {@link AntennaProfile#EMPTY}
     */
    private AntennaProfile scan(Level level) {
        int limit = Config.ANTENNA_MAX_MAST_BLOCKS.get();
        for (int offset = 1; offset <= limit; offset++) {
            BlockPos pos = worldPosition.above(offset);
            if (!level.isLoaded(pos)) {
                // 未加载等于"看不到"，按结构不完整处理。
                // 绝不能让 getBlockState 在这里触发区块加载。
                return AntennaProfile.EMPTY;
            }
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof AntennaElementBlock element) {
                return new AntennaProfile(element.kind(), offset);
            }
            if (!(state.getBlock() instanceof AntennaMastBlock)) {
                return AntennaProfile.EMPTY;
            }
        }
        // 杆子顶到上限还没有振子：无效。不给"高度封顶的裸杆"任何增益，
        // 否则玩家会以为只要堆杆就行。
        return AntennaProfile.EMPTY;
    }
}
