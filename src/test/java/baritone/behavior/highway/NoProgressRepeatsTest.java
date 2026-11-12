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

import baritone.test.HeadlessGame;
import net.minecraft.core.BlockPos;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The liquid removal's loop breaker: lava in the floor row that the removal's own re-scan doesn't
 * see, flagged again ten ticks after every removal.
 */
public class NoProgressRepeatsTest {

    private static final BlockPos LAVA = new BlockPos(0, 64, 0);
    private static final BlockPos FLOWING = LAVA.east();

    @BeforeClass
    public static void bootstrap() {
        HeadlessGame.bootstrap();
    }

    @Test
    public void builderProgressStartsOver() {
        NoProgressRepeats<BlockPos> repeats = new NoProgressRepeats<>();
        repeats.note(LAVA, 10);
        repeats.note(LAVA, 10);
        repeats.note(LAVA, 10);
        // the builder placed or broke something in between
        assertEquals(1, repeats.note(LAVA, 11));
        assertEquals(2, repeats.note(LAVA, 11));
    }

    @Test
    public void anotherLiquidStartsOver() {
        NoProgressRepeats<BlockPos> repeats = new NoProgressRepeats<>();
        repeats.note(LAVA, 0);
        repeats.note(LAVA, 0);
        // the source got filled and the flowing lava next to it is what the scan finds now
        assertEquals(1, repeats.note(FLOWING, 0));
        assertEquals(1, repeats.note(LAVA, 0));
    }

    @Test
    public void clearStartsOver() {
        NoProgressRepeats<BlockPos> repeats = new NoProgressRepeats<>();
        repeats.note(LAVA, 0);
        repeats.note(LAVA, 0);
        repeats.clear();
        assertEquals(1, repeats.note(LAVA, 0));
    }
}
