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
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a shove leaves a player standing still with no keys down: the explosion knockback the client adds to its own
 * motion, played out tick by tick the way vanilla moves it, against the blocks' collision shapes. Gravity 0.08 and
 * vertical drag 0.98, horizontal drag 0.91 in the air and the block's friction times that on the ground.
 */
public final class KnockbackLanding {

    private static final int MAX_TICKS = 100;

    private KnockbackLanding() {}

    /** Where we come to rest, and the fall damage of getting there. */
    public record Landing(Vec3 feet, float fallDamage) {}

    /**
     * Where {@code push} leaves us, from standing at {@code feet}, or null when it doesn't end well: we touch lava,
     * drop further than {@code maxDrop} below where we started, or are still moving when the time's up.
     */
    public static Landing land(BlockGetter world, Vec3 feet, Vec3 push, EntityDimensions dims, int maxDrop) {
        Vec3 pos = feet;
        Vec3 v = push;
        double highest = feet.y;
        for (int tick = 0; tick < MAX_TICKS; tick++) {
            AABB box = dims.makeBoundingBox(pos);
            double dy = collide(world, box, Direction.Axis.Y, v.y);
            box = box.move(0, dy, 0);
            // vanilla moves along the larger of the two horizontal speeds first
            boolean xFirst = Math.abs(v.x) >= Math.abs(v.z);
            double dx = 0;
            double dz = 0;
            if (xFirst) {
                dx = collide(world, box, Direction.Axis.X, v.x);
                box = box.move(dx, 0, 0);
            }
            dz = collide(world, box, Direction.Axis.Z, v.z);
            box = box.move(0, 0, dz);
            if (!xFirst) {
                dx = collide(world, box, Direction.Axis.X, v.x);
                box = box.move(dx, 0, 0);
            }
            pos = new Vec3((box.minX + box.maxX) / 2, box.minY, (box.minZ + box.maxZ) / 2);
            highest = Math.max(highest, pos.y);
            if (touchesLava(world, box) || feet.y - pos.y > maxDrop || pos.y < world.getMinY()) {
                return null;
            }
            boolean onGround = v.y < 0 && dy != v.y;
            double friction = 0.91;
            if (onGround) {
                BlockPos under = BlockPos.containing(pos.x, pos.y - 0.5, pos.z);
                friction *= world.getBlockState(under).getBlock().getFriction();
            }
            v = new Vec3(dx != v.x ? 0 : v.x * friction, ((dy != v.y ? 0 : v.y) - 0.08) * 0.98, dz != v.z ? 0 : v.z * friction);
            if (onGround && v.horizontalDistanceSqr() < 1e-5) {
                return new Landing(pos, (float) Math.max(0, Math.ceil(highest - pos.y - 3)));
            }
        }
        return null;
    }

    private static double collide(BlockGetter world, AABB box, Direction.Axis axis, double delta) {
        if (Math.abs(delta) < 1e-7) {
            return 0;
        }
        AABB swept = box.expandTowards(axis == Direction.Axis.X ? delta : 0, axis == Direction.Axis.Y ? delta : 0, axis == Direction.Axis.Z ? delta : 0);
        List<VoxelShape> shapes = new ArrayList<>();
        for (int x = Mth.floor(swept.minX); x <= Mth.floor(swept.maxX); x++) {
            for (int y = Mth.floor(swept.minY); y <= Mth.floor(swept.maxY); y++) {
                for (int z = Mth.floor(swept.minZ); z <= Mth.floor(swept.maxZ); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    VoxelShape shape = world.getBlockState(pos).getCollisionShape(world, pos);
                    if (!shape.isEmpty()) {
                        shapes.add(shape.move(x, y, z));
                    }
                }
            }
        }
        return Shapes.collide(axis, box, shapes, delta);
    }

    private static boolean touchesLava(BlockGetter world, AABB box) {
        for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX); x++) {
            for (int y = Mth.floor(box.minY); y <= Mth.floor(box.maxY); y++) {
                for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ); z++) {
                    BlockState state = world.getBlockState(new BlockPos(x, y, z));
                    if (state.is(Blocks.LAVA)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
