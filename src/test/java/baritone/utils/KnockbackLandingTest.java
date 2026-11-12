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

import baritone.test.HeadlessGame;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** A player standing on an obsidian floor at {@link #FLOOR}, shoved along +X. */
public class KnockbackLandingTest {

    private static final int FLOOR = 64;
    private static final Vec3 FEET = new Vec3(0.5, FLOOR + 1, 0.5);
    private static final EntityDimensions PLAYER = EntityDimensions.scalable(0.6F, 1.8F).withEyeHeight(1.62F);

    private final Map<BlockPos, BlockState> blocks = new HashMap<>();
    private final BlockGetter world = new BlockGetter() {
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
            return 384;
        }

        @Override
        public int getMinY() {
            return -64;
        }
    };

    @BeforeClass
    public static void bootstrap() {
        HeadlessGame.bootstrap();
    }

    @Before
    public void clear() {
        blocks.clear();
    }

    private void floor(int fromX, int toX) {
        for (int x = fromX; x <= toX; x++) {
            for (int z = -3; z <= 3; z++) {
                blocks.put(new BlockPos(x, FLOOR, z), Blocks.OBSIDIAN.defaultBlockState());
            }
        }
    }

    @Test
    public void noShoveStaysPut() {
        floor(-5, 5);
        KnockbackLanding.Landing landing = KnockbackLanding.land(world, FEET, Vec3.ZERO, PLAYER, 4);
        assertNotNull(landing);
        assertEquals(FEET.x, landing.feet().x, 1e-6);
        assertEquals(FEET.y, landing.feet().y, 1e-6);
        assertEquals(0, landing.fallDamage(), 0);
    }

    @Test
    public void shoveSlidesUsAlongTheFloor() {
        floor(-5, 20);
        KnockbackLanding.Landing landing = KnockbackLanding.land(world, FEET, new Vec3(0.5, 0.3, 0), PLAYER, 4);
        assertNotNull(landing);
        assertTrue("carried a good way", landing.feet().x > FEET.x + 3);
        assertEquals(FEET.y, landing.feet().y, 1e-6);
    }

    @Test
    public void wallStopsTheShove() {
        floor(-5, 20);
        for (int y = FLOOR + 1; y <= FLOOR + 3; y++) {
            for (int z = -3; z <= 3; z++) {
                blocks.put(new BlockPos(2, y, z), Blocks.OBSIDIAN.defaultBlockState());
            }
        }
        KnockbackLanding.Landing landing = KnockbackLanding.land(world, FEET, new Vec3(0.5, 0.3, 0), PLAYER, 4);
        assertNotNull(landing);
        assertEquals("against the wall", 1.7, landing.feet().x, 1e-6);
    }

    @Test
    public void shoveOffTheEdgeIsNoLanding() {
        floor(-5, 1); // and nothing past it
        assertNull(KnockbackLanding.land(world, FEET, new Vec3(0.5, 0.3, 0), PLAYER, 4));
    }

    @Test
    public void lavaUnderTheEdgeIsNoLanding() {
        floor(-5, 1);
        for (int x = 2; x <= 8; x++) {
            for (int z = -3; z <= 3; z++) {
                blocks.put(new BlockPos(x, FLOOR - 1, z), Blocks.LAVA.defaultBlockState());
                blocks.put(new BlockPos(x, FLOOR - 2, z), Blocks.OBSIDIAN.defaultBlockState());
            }
        }
        assertNull(KnockbackLanding.land(world, FEET, new Vec3(0.5, 0.3, 0), PLAYER, 4));
    }
}
