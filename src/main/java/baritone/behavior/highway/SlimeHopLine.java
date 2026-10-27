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

package baritone.behavior.highway;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.MagmaCube;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Whether a slime or magma cube can hop to us. They don't path: {@code SlimeAttackGoal} turns them to
 * face us and they hop straight ahead, so whatever stands on the straight line to us that's taller than
 * their jump stops them, and so does lava that burns them. This moves the cube's box along that line,
 * hopping up onto what's in the way and stepping down off edges, until it would touch us. A drop of more
 * than a block on the way doesn't count, like in {@link Threats}' walk check: a cube that has to jump
 * down to us comes once it has.
 */
final class SlimeHopLine {
    private static final double STEP = 0.25; // how far the box moves between checks, under half the smallest cube's width
    private static final double ATTACK_REACH = Math.sqrt(2.04) - 0.6; // Mob.getAttackBoundingBox grows the box by this much sideways
    private static final double EPSILON = 1.0E-7;
    private static final int MAX_START_DROP = 8; // how far a cube in mid-hop may still be above where it lands
    private static final int NONE = Integer.MIN_VALUE;

    // what a cell is to the cube, cached since the box keeps overlapping the same cells
    private static final byte UNKNOWN = 0;
    private static final byte OPEN = 1; // nothing to collide with
    private static final byte LIQUID = 2; // passable, and the cube floats on it
    private static final byte SOLID = 3;
    private static final byte DEADLY = 4; // lava, for a cube it burns

    private final Level level;
    private final double halfWidth;
    private final double boxHeight;
    private final int cellsTall;
    private final int jump;
    private final boolean lava;
    private final Long2ByteOpenHashMap kinds = new Long2ByteOpenHashMap();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    private SlimeHopLine(Level level, Slime slime) {
        this.level = level;
        this.halfWidth = slime.getBbWidth() / 2;
        this.boxHeight = slime.getBbHeight();
        this.cellsTall = Math.max(1, Mth.ceil(boxHeight));
        this.jump = jumpBlocks(slime);
        this.lava = slime.fireImmune();
    }

    /** Whether {@code slime} can hop up to {@code target} along the straight line to it. */
    static boolean canReach(Level level, Slime slime, Entity target) {
        return new SlimeHopLine(level, slime).march(slime.getX(), slime.getY(), slime.getZ(), target.getBoundingBox());
    }

    // full blocks a hop gets it up onto: it jumps 0.42, plus 0.1 per size for a magma cube
    private static int jumpBlocks(Slime slime) {
        double velocity = 0.42 + (slime instanceof MagmaCube ? 0.1 * slime.getSize() : 0);
        double rise = 0;
        while (velocity > 0) {
            rise += velocity;
            velocity = (velocity - 0.08) * 0.98;
        }
        return Math.max(1, (int) rise);
    }

    private boolean march(double fromX, double fromY, double fromZ, AABB target) {
        // stand it on what's under it: up out of a soul sand or slab cell, or down from mid-hop
        int y = Mth.floor(fromY);
        if (!fits(fromX, y, fromZ)) {
            y++;
            if (!fits(fromX, y, fromZ)) {
                return true; // wedged somewhere this can't model: don't rule it out
            }
        }
        y = land(fromX, y, fromZ, y - MAX_START_DROP);
        if (y == NONE) {
            return false;
        }
        double toX = (target.minX + target.maxX) / 2;
        double toZ = (target.minZ + target.maxZ) / 2;
        int steps = Math.max(1, Mth.ceil(Math.sqrt(Mth.square(toX - fromX) + Mth.square(toZ - fromZ)) / STEP));
        double prevX = fromX;
        double prevZ = fromZ;
        for (int i = 0; i <= steps; i++) {
            double x = fromX + (toX - fromX) * i / steps;
            double z = fromZ + (toZ - fromZ) * i / steps;
            if (i > 0) {
                int up = 0;
                // in the way: hop up onto it, rising where it was, as high as it jumps
                while (!fits(x, y + up, z)) {
                    if (++up > jump || !fits(prevX, y + up, prevZ)) {
                        return false;
                    }
                }
                y = land(x, y + up, z, y + up - 1);
                if (y == NONE) {
                    return false;
                }
            }
            if (new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + boxHeight, z + halfWidth)
                    .inflate(ATTACK_REACH, 0, ATTACK_REACH).intersects(target)) {
                return true;
            }
            prevX = x;
            prevZ = z;
        }
        return false;
    }

    /** Where the box drops to from height y, or NONE if it lands in lava that burns it or below {@code lowest}. */
    private int land(double x, int y, double z, int lowest) {
        for (; y >= lowest; y--) {
            boolean held = false;
            boolean deadly = false;
            for (int cx = Mth.floor(x - halfWidth + EPSILON); cx <= Mth.floor(x + halfWidth - EPSILON); cx++) {
                for (int cz = Mth.floor(z - halfWidth + EPSILON); cz <= Mth.floor(z + halfWidth - EPSILON); cz++) {
                    byte below = kind(cx, y - 1, cz);
                    held |= below == SOLID || below == LIQUID;
                    deadly |= below == DEADLY;
                }
            }
            if (held) {
                return y;
            }
            if (deadly) {
                return NONE;
            }
        }
        return NONE;
    }

    // the box fits with its bottom at height y
    private boolean fits(double x, int y, double z) {
        for (int cx = Mth.floor(x - halfWidth + EPSILON); cx <= Mth.floor(x + halfWidth - EPSILON); cx++) {
            for (int cz = Mth.floor(z - halfWidth + EPSILON); cz <= Mth.floor(z + halfWidth - EPSILON); cz++) {
                for (int cy = y; cy < y + cellsTall; cy++) {
                    byte kind = kind(cx, cy, cz);
                    if (kind != OPEN && kind != LIQUID) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private byte kind(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        byte kind = kinds.get(key);
        if (kind == UNKNOWN) {
            kind = classify(x, y, z);
            kinds.put(key, kind);
        }
        return kind;
    }

    private byte classify(int x, int y, int z) {
        pos.set(x, y, z);
        BlockState state = level.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (!shape.isEmpty()) {
            return SOLID;
        }
        if (state.getFluidState().is(FluidTags.LAVA)) {
            return lava ? LIQUID : DEADLY;
        }
        return state.getFluidState().isEmpty() ? OPEN : LIQUID;
    }
}
