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

package baritone.process.elytra;

import baritone.test.HeadlessGame;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The game works out what a move went through one axis at a time, up or down first, then the longer way across, so a
 * diagonal glide step past a lavafall sets the player on fire though the straight line it was checked along misses
 * it. The step is shortened until its path does too.
 */
public class GlideBurnPathTest {

    @BeforeClass
    public static void bootstrap() {
        HeadlessGame.bootstrap();
    }

    // a lavafall, a column of lava from the ceiling, beside where the glide goes
    private static final BlockPos LAVA = new BlockPos(2, 10, 0);
    private static final ElytraBehavior.Burns LAVAFALL = (x, y, z, minY) -> x == LAVA.getX() && z == LAVA.getZ() && y >= 5 && y <= 20;
    // a gliding box with its feet in the middle of block (0, 10, 0)
    private static final AABB BOX = new AABB(0.2, 10, 0.2, 0.8, 10.6, 0.8);

    @Test
    public void aDiagonalStepWhoseLineMissesTheLavaGoesThroughItTheGamesWay() {
        final Vec3 step = new Vec3(2.0, 0, 1.6);
        // the box along the straight line: by the time its side reaches the lava's column it's past it
        for (int i = 0; i <= 100; i++) {
            final AABB at = BOX.move(step.scale(i / 100.0)).deflate(1.0E-5);
            assertFalse("the straight line at " + i, at.intersects(new AABB(LAVA)));
        }
        // across the longer way first, then the shorter: straight through it
        assertEquals(LAVA, ElytraBehavior.burnsOnTheWay(BOX, step, LAVAFALL));
        // the longer way the other way round, the shorter first: missed
        assertNull(ElytraBehavior.burnsOnTheWay(BOX, new Vec3(1.6, 0, 2.0), LAVAFALL));
    }

    @Test
    public void theStepIsShortenedUntilItsPathMissesTheLava() {
        final Vec3 step = new Vec3(2.0, 0, 1.6);
        final Vec3 kept = ElytraBehavior.unburntStep(BOX, step, (box, s) -> ElytraBehavior.burnsOnTheWay(box, s, LAVAFALL) != null);
        assertNotNull(kept);
        assertNull(ElytraBehavior.burnsOnTheWay(BOX, kept, LAVAFALL));
        // as far as the box's side gets short of the lava's column: 0.8 + 2.0 * scale < 2
        assertEquals(0.6, kept.x / step.x, 0.01);
        assertEquals(kept.x / step.x, kept.z / step.z, 1.0E-9);
        // a step that doesn't burn is left alone
        final Vec3 away = new Vec3(-2.0, 0, 1.6);
        assertSame(away, ElytraBehavior.unburntStep(BOX, away, (box, s) -> ElytraBehavior.burnsOnTheWay(box, s, LAVAFALL) != null));
    }

    @Test
    public void upOrDownGoesFirst() {
        // lava overhead, in the block over the box: a step up and across clears it along the straight line
        final BlockPos over = new BlockPos(0, 11, 0);
        final ElytraBehavior.Burns overhead = (x, y, z, minY) -> x == 0 && y == 11 && z == 0;
        final Vec3 step = new Vec3(1.5, 0.5, 0);
        for (int i = 0; i <= 100; i++) {
            final AABB at = BOX.move(step.scale(i / 100.0)).deflate(1.0E-5);
            assertFalse("the straight line at " + i, at.intersects(new AABB(over)));
        }
        // but up goes first, into it
        assertEquals(over, ElytraBehavior.burnsOnTheWay(BOX, step, overhead));
        // a box in lava already burns where it is
        assertEquals(over, ElytraBehavior.burnsOnTheWay(BOX.move(0, 1, 0), Vec3.ZERO, overhead));
    }

    @Test
    public void pressedUpAgainstFireItSlidesAlongItRatherThanHoldingStill() {
        // a fire block, and the box flush against its face, wanting on past it and across
        final ElytraBehavior.Burns fire = (x, y, z, minY) -> x == 1 && y == 10 && z == 0;
        final AABB flush = new AABB(0.4, 10, 0.2, 1.0, 10.6, 0.8);
        // across the longer way first: into the face
        final Vec3 step = new Vec3(1.5, 0, 1.0);
        final java.util.function.BiPredicate<AABB, Vec3> burns = (box, s) -> ElytraBehavior.burnsOnTheWay(box, s, fire) != null;
        // any of the step into its face burns, however short
        assertNull(ElytraBehavior.unburntStep(flush, step, burns));
        // along its face instead
        final Vec3 slid = ElytraBehavior.unburntSlidStep(flush, step, burns);
        assertNotNull(slid);
        assertEquals(0, slid.x, 1.0E-9);
        assertEquals(1.0, slid.z, 1.0E-9);
        assertNull(ElytraBehavior.burnsOnTheWay(flush, slid, fire));
    }

    @Test
    public void aPullDownSinksAtGlideSpeedAndBarelyGoesAcross() {
        // from a standstill at a setback, facing south (yaw 0), looking level
        Vec3 velocity = Vec3.ZERO;
        double down = 0;
        double across = 0;
        for (int tick = 0; tick < 40; tick++) {
            final Vec3 move = ElytraBehavior.pullStep(velocity, 0);
            down -= move.y;
            across += move.z;
            assertEquals(0, move.x, 1.0E-9);
            velocity = move;
        }
        // two seconds: it sinks a few blocks, nowhere near a fall's 30, and goes far less across than down
        assertTrue("down " + down, down > 2 && down < 6);
        assertTrue("across " + across, across > 0 && across < down / 2);
        // it settles sinking about 0.15 a tick
        for (int tick = 0; tick < 200; tick++) {
            velocity = ElytraBehavior.pullStep(new Vec3(0, velocity.y, 0), 0);
        }
        assertEquals(-0.15, velocity.y, 0.02);
        // facing east (yaw -90), the little it goes across goes east
        assertTrue(ElytraBehavior.pullStep(new Vec3(0, -0.1, 0), -90).x > 0);
    }
}
