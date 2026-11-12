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

package baritone.api.utils;

import baritone.test.HeadlessGame;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A wall sign hung on the side of the block the bot stands on: {@code oak_wall_sign[facing=west]}
 * in the paving floor row, hung on the block east of it, with the bot standing on that block. Rays
 * are cast through a stand-in world with the game's own clip, the way {@link RayTraceUtils} casts
 * them: outlines, fluids ignored.
 */
public class OutlineFacePointsTest {

    private static final double EYE_HEIGHT = 1.62;
    private static final double REACH = 4.5;
    private static final BlockPos SIGN = new BlockPos(0, 64, 0);
    private static final BlockPos SUPPORT = SIGN.east();

    private static final Map<BlockPos, BlockState> blocks = new HashMap<>();
    private static AABB signBox;

    private static final BlockGetter world = new BlockGetter() {
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return 256;
        }

        @Override
        public int getMinY() {
            return 0;
        }
    };

    @BeforeClass
    public static void bootstrap() {
        HeadlessGame.bootstrap();
        // the floor row and everything under it netherrack, the lane above it open
        for (int x = SIGN.getX() - 4; x <= SIGN.getX() + 4; x++) {
            for (int z = SIGN.getZ() - 4; z <= SIGN.getZ() + 4; z++) {
                for (int y = SIGN.getY() - 2; y <= SIGN.getY(); y++) {
                    blocks.put(new BlockPos(x, y, z), Blocks.NETHERRACK.defaultBlockState());
                }
            }
        }
        BlockState sign = Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.WEST);
        blocks.put(SIGN, sign);
        signBox = sign.getShape(world, SIGN).bounds().move(SIGN.getX(), SIGN.getY(), SIGN.getZ());
    }

    /** Standing on the sign's own support block, at {@code x} across it. */
    private static Vec3 eyeOnSupport(double x) {
        return new Vec3(x, SUPPORT.getY() + 1 + EYE_HEIGHT, SUPPORT.getZ() + 0.5);
    }

    private static boolean hitsSign(Vec3 eye, Vec3 point) {
        Vec3 end = eye.add(point.subtract(eye).normalize().scale(REACH));
        BlockHitResult hit = world.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(SIGN);
    }

    @Test
    public void theFarRimOfTheTopFaceShows() {
        // anywhere a bot stands on the support block clear of its west edge
        for (double x : new double[]{SUPPORT.getX() + 0.3, SUPPORT.getX() + 0.5, SUPPORT.getX() + 0.7}) {
            Vec3 eye = eyeOnSupport(x);
            Vec3 seen = null;
            for (Vec3 point : RotationUtils.outlineFacePoints(signBox, eye, RotationUtils.OUTLINE_FACE_GRID)) {
                if (eye.distanceTo(point) <= REACH && hitsSign(eye, point)) {
                    seen = point;
                    break;
                }
            }
            assertTrue("nothing of the sign seen from x " + x, seen != null);
            assertEquals("seen on its top face from x " + x, signBox.maxY, seen.y, 1e-9);
        }
    }

    @Test
    public void onlyTheFacesTheEyeIsInFrontOf() {
        Vec3 eye = eyeOnSupport(SUPPORT.getX() + 0.5);
        int n = RotationUtils.OUTLINE_FACE_GRID;
        List<Vec3> points = RotationUtils.outlineFacePoints(signBox, eye, n);
        // above the plate and east of it: its top and its east face, nothing else
        assertEquals(2 * n * n, points.size());
        int top = 0;
        int east = 0;
        for (Vec3 p : points) {
            if (p.y == signBox.maxY) {
                top++;
                assertInside(p.x, signBox.minX, signBox.maxX);
                assertInside(p.z, signBox.minZ, signBox.maxZ);
            } else if (p.x == signBox.maxX) {
                east++;
                assertInside(p.y, signBox.minY, signBox.maxY);
                assertInside(p.z, signBox.minZ, signBox.maxZ);
            }
        }
        assertEquals(n * n, top);
        assertEquals(n * n, east);
    }

    private static void assertInside(double v, double min, double max) {
        assertTrue(v + " outside " + min + ".." + max, v > min && v < max);
    }
}
