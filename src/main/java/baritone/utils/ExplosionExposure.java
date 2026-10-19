/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.Set;

/**
 * How much of a box an explosion reaches. Vanilla scales both the damage and the knockback by this: the share of a
 * grid of points over the box with nothing solid between them and the blast ({@code ServerExplosion.getSeenPercent}).
 * Taken here for any box in any world, so it can say how exposed we'd be standing somewhere else, behind blocks that
 * aren't placed yet.
 */
public final class ExplosionExposure {

    private ExplosionExposure() {}

    /** Vanilla's sample grid and ray test as they are there, the grid's lopsided offset included. */
    public static float seenPercent(BlockGetter world, Vec3 center, AABB box) {
        double stepX = 1.0 / ((box.maxX - box.minX) * 2.0 + 1.0);
        double stepY = 1.0 / ((box.maxY - box.minY) * 2.0 + 1.0);
        double stepZ = 1.0 / ((box.maxZ - box.minZ) * 2.0 + 1.0);
        double offsetX = (1.0 - Math.floor(1.0 / stepX) * stepX) / 2.0;
        double offsetZ = (1.0 - Math.floor(1.0 / stepZ) * stepZ) / 2.0;
        int seen = 0;
        int total = 0;
        for (double fx = 0.0; fx <= 1.0; fx += stepX) {
            for (double fy = 0.0; fy <= 1.0; fy += stepY) {
                for (double fz = 0.0; fz <= 1.0; fz += stepZ) {
                    Vec3 point = new Vec3(Mth.lerp(fx, box.minX, box.maxX) + offsetX, Mth.lerp(fy, box.minY, box.maxY), Mth.lerp(fz, box.minZ, box.maxZ) + offsetZ);
                    if (world.clip(new ClipContext(point, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty())).getType() == HitResult.Type.MISS) {
                        seen++;
                    }
                    total++;
                }
            }
        }
        return (float) seen / total;
    }

    /** The world with these cells filled with {@code state}, for what blocks we haven't placed yet would shield. */
    public static BlockGetter withBlocks(BlockGetter world, Set<BlockPos> cells, BlockState state) {
        if (cells.isEmpty()) {
            return world;
        }
        return new BlockGetter() {
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return cells.contains(pos) ? null : world.getBlockEntity(pos);
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                return cells.contains(pos) ? state : world.getBlockState(pos);
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                return cells.contains(pos) ? Fluids.EMPTY.defaultFluidState() : world.getFluidState(pos);
            }

            @Override
            public int getHeight() {
                return world.getHeight();
            }

            @Override
            public int getMinY() {
                return world.getMinY();
            }
        };
    }
}
