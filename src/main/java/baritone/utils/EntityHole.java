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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.FlyingAnimal;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Plans the hole that drops an entity out of the way of a block that has to go where it stands:
 * only the columns under its bounding box, and only as deep as its head has to sink to clear the
 * cell. A wider hole digs out blocks the entity isn't even standing on, built supports included,
 * which the builder then puts straight back and digs out again for as long as the entity stays.
 */
public final class EntityHole {

    /** Deepest a hole goes below the cell it frees; anything needing more isn't worth digging for. */
    private static final int MAX_DEPTH = 6;
    /** Vanilla's collision tolerance: a box overlapping a column by less than this still falls past it. */
    private static final double COLLISION_EPSILON = 1.0E-7;

    private EntityHole() {}

    /** Whether the entity drops into a hole dug under it at all; flyers just hover over it. */
    public static boolean falls(Entity entity) {
        return !entity.isNoGravity()
                && !(entity instanceof FlyingAnimal)
                && !(entity instanceof Ghast)
                && !(entity instanceof HappyGhast)
                && !(entity instanceof Phantom)
                && !(entity instanceof Bat)
                && !(entity instanceof Vex);
    }

    /** The box an entity and everything riding it fall as. */
    public static AABB groupBox(Entity root) {
        AABB box = root.getBoundingBox();
        for (Entity passenger : root.getIndirectPassengers()) {
            box = box.minmax(passenger.getBoundingBox());
        }
        return box;
    }

    /**
     * @param box         what has to drop, riders included ({@link #groupBox})
     * @param clearY      the lowest cell it's in the way of; its top has to end up at or below this
     * @param needsRefill cells that get a block put back once they're empty. It has to drop below
     *                    those as well, or the hole only moves the problem down a block
     * @return every cell of the hole, already empty ones included, since a block put back in any
     *         of them stops the drop; null when it can't be dropped. Obsidian gets dug like anything
     *         else: a few seconds of mining and a block to put back beat waiting for a mob to move
     */
    public static List<BlockPos> plan(BlockGetter world, AABB box, int clearY, Predicate<BlockPos> needsRefill) {
        int minX = Mth.floor(box.minX + COLLISION_EPSILON);
        int maxX = Mth.ceil(box.maxX - COLLISION_EPSILON) - 1;
        int minZ = Mth.floor(box.minZ + COLLISION_EPSILON);
        int maxZ = Mth.ceil(box.maxZ - COLLISION_EPSILON) - 1;
        double height = box.maxY - box.minY;
        int deepest = clearY - MAX_DEPTH;

        // it comes to rest on a block top, so its feet end up on a whole y
        int land = Mth.floor(clearY - height);
        int y = land;
        while (y < clearY) {
            if (land < deepest) {
                return null;
            }
            if (layerNeedsRefill(needsRefill, y, minX, maxX, minZ, maxZ)) {
                // it can't come to rest across a cell that's getting built back either
                clearY = y;
                land = Mth.floor(clearY - height);
                y = land;
            } else {
                y++;
            }
        }

        // everything under its feet, down to where it lands
        int top = Mth.ceil(box.minY) - 1;
        List<BlockPos> hole = new ArrayList<>();
        for (int hy = land; hy <= top; hy++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, hy, z);
                    BlockState state = world.getBlockState(pos);
                    if (state.getBlock().defaultDestroyTime() < 0 && !state.getCollisionShape(world, pos).isEmpty()) {
                        return null; // unbreakable, so the rest of the hole would be for nothing
                    }
                    hole.add(pos);
                }
            }
        }
        return hole;
    }

    private static boolean layerNeedsRefill(Predicate<BlockPos> needsRefill, int y, int minX, int maxX, int minZ, int maxZ) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (needsRefill.test(new BlockPos(x, y, z))) {
                    return true;
                }
            }
        }
        return false;
    }
}
