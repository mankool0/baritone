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

package baritone.pathing.movement;

import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * Where an entity is in the way of placing a block. Vanilla refuses a placement into any entity's box
 * ({@code BlockItem#canPlace}), so clicking there does nothing for as long as it stays.
 * <p>
 * Our own box is left out: the movements that place where we're standing time their click for when we've moved
 * clear of it (pillar waits until we're above the block, ascend backs off), and counting it would make every pillar
 * look impossible.
 */
public final class EntityObstruction {

    /** Vanilla's collision tolerance: a box that only touches a cell, give or take this much, isn't in it. */
    private static final double EPSILON = 1.0E-7;

    private EntityObstruction() {}

    /** Whether an entity is in the way of placing a block at this position right now. */
    public static boolean blocksPlacement(IPlayerContext ctx, BlockPos pos) {
        Entity self = ctx.player().getRootVehicle();
        AABB cell = new AABB(pos).deflate(EPSILON);
        return !ctx.world().getEntities((Entity) null, cell, entity -> obstructs(entity, self)).isEmpty();
    }

    /**
     * Every position {@link #blocksPlacement} is true for right now, as {@link BetterBlockPos#longHash}es. The path
     * search runs on another thread, where the entity list can't be touched.
     */
    public static LongSet occupiedCells(IPlayerContext ctx) {
        LongSet cells = new LongOpenHashSet();
        Entity self = ctx.player().getRootVehicle();
        for (Entity entity : ctx.entities()) {
            if (!obstructs(entity, self)) {
                continue;
            }
            // the same cells blocksPlacement's shrunken cell box intersects
            AABB box = entity.getBoundingBox();
            int maxX = Mth.ceil(box.maxX - EPSILON);
            int maxY = Mth.ceil(box.maxY - EPSILON);
            int maxZ = Mth.ceil(box.maxZ - EPSILON);
            for (int x = Mth.floor(box.minX + EPSILON); x < maxX; x++) {
                for (int y = Mth.floor(box.minY + EPSILON); y < maxY; y++) {
                    for (int z = Mth.floor(box.minZ + EPSILON); z < maxZ; z++) {
                        cells.add(BetterBlockPos.longHash(x, y, z));
                    }
                }
            }
        }
        return cells;
    }

    // what CollisionGetter#isUnobstructed counts, getEntities having already left out spectators
    private static boolean obstructs(Entity entity, Entity self) {
        return !entity.isRemoved() && entity.blocksBuilding && !entity.isSpectator() && entity.getRootVehicle() != self;
    }
}
