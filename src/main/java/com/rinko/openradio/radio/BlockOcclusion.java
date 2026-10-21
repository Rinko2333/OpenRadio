package com.rinko.openradio.radio;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.rinko.openradio.Config;
import com.rinko.openradio.OpenRadio;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 方块遮挡采样。
 *
 * <p>用 Amanatides–Woo 体素遍历（而不是按固定间距采样）：只访问射线真正穿过的方块，
 * 没有重复采样、没有漏格，长射线的开销只和"实际穿过的方块数"成正比。
 *
 * <p>两个必须注意的边界：
 * <ul>
 *   <li><b>不触发区块加载。</b>未加载区块直接按平均遮挡估算，绝不让一次射线把地图跑起来 ——
 *       那会让一次 HUD 刷新变成世界生成，是真正的性能灾难。</li>
 *   <li><b>有硬上限。</b>步数上限 + 损失封顶，保证最坏情况可控，也保证"隔着山也不是绝对静默"，
 *       玩家贴着地形边缘总有救回来的机会。</li>
 * </ul>
 */
public final class BlockOcclusion {
    /** 按方块实例查覆盖损失，避免每次走注册表查找。 */
    private static Map<Block, Double> overrideLoss;
    private static List<? extends String> overrideSource;

    /**
     * 计算射线路径上累计被遮挡的比例。
     *
     * @return 0.0（完全通透）– cap（被彻底挡住）之间的比例
     */
    public static double fraction(ServerLevel level, Vec3 from, Vec3 to, RadioBand band, int maxSteps) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 1.0E-4D) {
            return 0.0D;
        }

        double unitX = dx / distance;
        double unitY = dy / distance;
        double unitZ = dz / distance;

        int x = Mth.floor(from.x);
        int y = Mth.floor(from.y);
        int z = Mth.floor(from.z);
        int endX = Mth.floor(to.x);
        int endY = Mth.floor(to.y);
        int endZ = Mth.floor(to.z);

        int stepX = unitX > 0 ? 1 : (unitX < 0 ? -1 : 0);
        int stepY = unitY > 0 ? 1 : (unitY < 0 ? -1 : 0);
        int stepZ = unitZ > 0 ? 1 : (unitZ < 0 ? -1 : 0);

        // 沿射线的参数用"世界距离"表示，直行到下一个格边界的距离 / 每格所需距离。
        double tMaxX = stepX == 0 ? Double.MAX_VALUE
                : (stepX > 0 ? (x + 1 - from.x) : (from.x - x)) / Math.abs(unitX);
        double tMaxY = stepY == 0 ? Double.MAX_VALUE
                : (stepY > 0 ? (y + 1 - from.y) : (from.y - y)) / Math.abs(unitY);
        double tMaxZ = stepZ == 0 ? Double.MAX_VALUE
                : (stepZ > 0 ? (z + 1 - from.z) : (from.z - z)) / Math.abs(unitZ);

        double tDeltaX = stepX == 0 ? Double.MAX_VALUE : 1.0D / Math.abs(unitX);
        double tDeltaY = stepY == 0 ? Double.MAX_VALUE : 1.0D / Math.abs(unitY);
        double tDeltaZ = stepZ == 0 ? Double.MAX_VALUE : 1.0D / Math.abs(unitZ);

        double multiplier = band.blockLossMultiplier();
        double cap = Math.min(band.maxBlockLoss(), Config.MAX_BLOCK_LOSS.get());
        double defaultLoss = Config.BLOCK_LOSS_PER_BLOCK.get();
        double loss = 0.0D;

        int steps = 0;
        while (steps++ < maxSteps) {
            if (x == endX && y == endY && z == endZ) {
                break;
            }

            double along;
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                x += stepX;
                along = tMaxX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                y += stepY;
                along = tMaxY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                along = tMaxZ;
                tMaxZ += tDeltaZ;
            }

            if (along > distance) {
                break;
            }

            loss += lossAt(level, x, y, z, multiplier, defaultLoss);
            if (loss >= cap) {
                return cap;
            }
        }
        return Math.min(loss, cap);
    }

    private static double lossAt(ServerLevel level, int x, int y, int z,
                                 double multiplier, double defaultLoss) {
        BlockPos pos = new BlockPos(x, y, z);
        if (!level.isLoaded(pos)) {
            // 刻意不加载区块：按平均遮挡估一个值，宁可偏保守也不让射线去生成世界。
            return defaultLoss * multiplier;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return 0.0D;
        }
        // 覆盖表优先，这样树叶、玻璃这类"不阻挡视线但会削弱信号"的方块才能被单独指定。
        Double override = overrides().get(state.getBlock());
        if (override != null) {
            return override * multiplier;
        }
        // 没有覆盖项时，只有真正能遮挡的方块才计入 —— 花草、火把、藤蔓不算。
        // canOcclude() 是 BlockState 上缓存的布尔值，不分配对象，可以放心放在热路径。
        if (!state.canOcclude()) {
            return 0.0D;
        }
        return defaultLoss * multiplier;
    }

    private static Map<Block, Double> overrides() {
        List<? extends String> source = Config.BLOCK_LOSS_OVERRIDES.get();
        Map<Block, Double> cached = overrideLoss;
        if (cached != null && source.equals(overrideSource)) {
            return cached;
        }

        Map<Block, Double> parsed = new IdentityHashMap<>();
        for (String entry : source) {
            int split = entry.indexOf('=');
            if (split <= 0) {
                OpenRadio.LOGGER.warn("忽略非法的方块遮挡配置项: {}", entry);
                continue;
            }
            ResourceLocation id = ResourceLocation.tryParse(entry.substring(0, split).trim());
            if (id == null) {
                OpenRadio.LOGGER.warn("忽略无法解析的方块 ID: {}", entry);
                continue;
            }
            double value;
            try {
                value = Double.parseDouble(entry.substring(split + 1).trim());
            } catch (NumberFormatException e) {
                OpenRadio.LOGGER.warn("忽略无法解析的损失数值: {}", entry);
                continue;
            }
            Block block = BuiltInRegistries.BLOCK.get(id);
            if (block == Blocks.AIR) {
                OpenRadio.LOGGER.warn("忽略未知方块: {}", id);
                continue;
            }
            parsed.put(block, value);
        }

        overrideLoss = parsed;
        overrideSource = source;
        return parsed;
    }

    private BlockOcclusion() {
    }
}
