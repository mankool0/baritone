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

import baritone.api.utils.BetterBlockPos;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * The end of a path in a cave under a roof, where the search for a firework glide's landing (15 blocks of air over
 * the floor, a 9 block bubble of it) finds nothing: a landing moved by steps comes down on the floor below or near it.
 * With nothing to stand on near, it holds still and says so once, then gives up once. Over a lava sea it looks along
 * the path ahead first, then far around, and never gives up holding over the lava.
 */
public class GlideLandingSearchTest {

    /** Blocks by position: 'r' rock, 'l' lava, 'f' fire; anything else is air. Standable is a sturdy floor (rock)
     * under two blocks of air. */
    private static final class Blocks implements GlideLandingSearch.World {
        final Map<Long, Character> blocks = new HashMap<>();
        int loadedMinX = Integer.MIN_VALUE;

        void set(int x, int y, int z, char block) {
            this.blocks.put(BetterBlockPos.longHash(x, y, z), block);
        }

        char at(int x, int y, int z) {
            return this.blocks.getOrDefault(BetterBlockPos.longHash(x, y, z), ' ');
        }

        @Override
        public boolean open(int x, int y, int z) {
            return this.at(x, y, z) == ' ';
        }

        @Override
        public boolean standable(int x, int y, int z) {
            return this.at(x, y - 1, z) == 'r' && this.open(x, y, z) && this.open(x, y + 1, z);
        }

        @Override
        public boolean loaded(int x, int z) {
            return x >= this.loadedMinX;
        }
    }

    /** A cave 6 high over x and z -30 to 30: floor at y 40, roof at y 47 */
    private static Blocks cave() {
        final Blocks world = new Blocks();
        for (int x = -30; x <= 30; x++) {
            for (int z = -30; z <= 30; z++) {
                world.set(x, 40, z, 'r');
                world.set(x, 47, z, 'r');
            }
        }
        return world;
    }

    @Test
    public void inACaveTheFloorStraightBelow() {
        final Blocks world = cave();
        final GlideLandingSearch search = new GlideLandingSearch(world, new BetterBlockPos(3, 45, -2), 24, 96, 0);
        assertEquals(new BetterBlockPos(3, 41, -2), search.advance(Long.MAX_VALUE));
    }

    @Test
    public void overLavaTheNearestFloorBesideIt() {
        final Blocks world = cave();
        // a lava pool 5 wide under the player
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                world.set(x, 40, z, 'l');
            }
        }
        final BetterBlockPos found = new GlideLandingSearch(world, new BetterBlockPos(0, 45, 0), 24, 96, 0).advance(Long.MAX_VALUE);
        assertNotNull(found);
        assertEquals(41, found.y);
        // the pool's edge, nearest first
        assertEquals(9, found.x * found.x + found.z * found.z);
    }

    @Test
    public void aColumnWithSomethingInTheWayDownIsntOne() {
        final Blocks world = cave();
        // a ledge of rock over the floor under the player, and fire on the floor beside it
        world.set(0, 43, 0, 'f');
        world.set(1, 41, 0, 'f');
        final BetterBlockPos found = new GlideLandingSearch(world, new BetterBlockPos(0, 45, 0), 24, 96, 0).advance(Long.MAX_VALUE);
        assertNotNull(found);
        assertNotEquals(new BetterBlockPos(0, 41, 0), found);
        assertNotEquals(new BetterBlockPos(1, 42, 0), found);
        assertEquals(41, found.y);
    }

    @Test
    public void nothingWithinReachIsExhaustedNotForever() {
        final Blocks world = new Blocks();
        // lava as far as the search looks
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                world.set(x, 20, z, 'l');
            }
        }
        final GlideLandingSearch search = new GlideLandingSearch(world, new BetterBlockPos(0, 60, 0), 8, 96, 0);
        assertNull(search.advance(Long.MAX_VALUE));
        assertTrue(search.exhausted());
    }

    @Test
    public void unloadedColumnsAreLeftOut() {
        final Blocks world = cave();
        world.loadedMinX = 5;
        final BetterBlockPos found = new GlideLandingSearch(world, new BetterBlockPos(0, 45, 0), 24, 96, 0).advance(Long.MAX_VALUE);
        assertEquals(new BetterBlockPos(5, 41, 0), found);
    }

    @Test
    public void aShortBudgetGoesOnWhereItLeftOff() {
        final Blocks world = new Blocks();
        // the one floor, at the edge of reach
        world.set(20, 30, 0, 'r');
        final GlideLandingSearch search = new GlideLandingSearch(world, new BetterBlockPos(0, 60, 0), 20, 96, 0);
        BetterBlockPos found = null;
        int calls = 0;
        while (found == null && !search.exhausted()) {
            found = search.advance(0);
            calls++;
        }
        assertEquals(new BetterBlockPos(20, 31, 0), found);
        assertTrue(calls > 1);
    }

    @Test
    public void withNowhereToLandItHoldsSayingSoOnceThenGivesUpOnce() {
        final LandingHold hold = new LandingHold(24, 600);
        assertEquals("No spot to land on within 24 blocks of here, holding still for up to 30 s while chunks come in", hold.nothingFound(1000));
        int lines = 0;
        // looked again every 2 s
        for (int t = 1040; t < 1600; t += 40) {
            if (hold.nothingFound(t) != null) {
                lines++;
            }
            assertFalse(hold.givenUp());
        }
        assertEquals(0, lines);
        assertEquals("Gave up landing: no spot to land on within 24 blocks in 30 s, ending the flight in the air", hold.nothingFound(1600));
        assertTrue(hold.givenUp());
        assertNull(hold.nothingFound(1640));
        hold.reset();
        assertFalse(hold.givenUp());
        assertNotNull(hold.nothingFound(2000));
    }

    /** A lava sea's surface at y 31 from x -120 to 120 and z -40 to 40 */
    private static Blocks lavaSea() {
        final Blocks world = new Blocks();
        for (int x = -120; x <= 120; x++) {
            for (int z = -40; z <= 40; z++) {
                world.set(x, 31, z, 'l');
            }
        }
        return world;
    }

    /** A rock island 3 across in the sea, its middle at (x, z) */
    private static void island(Blocks world, int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.set(x + dx, 31, z + dz, 'r');
            }
        }
    }

    /** The path on from the player at (0, 60, 0): east along z 0, a point every 4 blocks */
    private static List<BetterBlockPos> pathEast() {
        final List<BetterBlockPos> path = new ArrayList<>();
        for (int x = 4; x <= 200; x += 4) {
            path.add(new BetterBlockPos(x, 60, 0));
        }
        return path;
    }

    @Test
    public void overALavaSeaTheFloorAlongThePathAheadComesBeforeANearerOneBehind() {
        final Blocks world = lavaSea();
        island(world, 40, 0);
        island(world, -25, 0);
        final BetterBlockPos from = new BetterBlockPos(0, 60, 0);
        // nothing near, as the pull-down's search looks
        final GlideLandingSearch near = new GlideLandingSearch(world, from, 16, 96, 0);
        assertNull(near.advance(Long.MAX_VALUE));
        assertTrue(near.exhausted());

        final BetterBlockPos found = GlideLandingSearch.aheadThenAround(world, from, pathEast(), 64, 8, 64, 96, 0).advance(Long.MAX_VALUE);
        assertNotNull(found);
        assertEquals(32, found.y);
        assertTrue("on the island ahead: " + found, found.x >= 39 && found.x <= 41);
    }

    @Test
    public void overALavaSeaWithNothingAheadTheFloorFurtherAround() {
        final Blocks world = lavaSea();
        island(world, -25, 10);
        final BetterBlockPos found = GlideLandingSearch.aheadThenAround(world, new BetterBlockPos(0, 60, 0), pathEast(), 64, 8, 64, 96, 0).advance(Long.MAX_VALUE);
        assertNotNull(found);
        assertTrue("on the island behind: " + found, found.x >= -26 && found.x <= -24 && found.z >= 9 && found.z <= 11);
    }

    @Test
    public void thePathAheadIsLookedAlongNoFurtherThanAsked() {
        final Blocks world = lavaSea();
        island(world, 100, 0);
        final GlideLandingSearch search = GlideLandingSearch.aheadThenAround(world, new BetterBlockPos(0, 60, 0), pathEast(), 64, 8, 64, 96, 0);
        assertNull(search.advance(Long.MAX_VALUE));
        assertTrue(search.exhausted());
    }

    @Test
    public void overLavaTheHoldGoesOnAndSaysSoOnce() {
        final LandingHold hold = new LandingHold(24, 600);
        assertEquals("No spot to land on within 64 blocks of here, holding still for up to 30 s while chunks come in", hold.nothingFound(1000, 64, true));
        assertNull(hold.nothingFound(1040, 64, true));
        assertEquals("No spot to land on within 64 blocks in 30 s, and there's lava below: holding on and looking again, not ending the flight over it", hold.nothingFound(1600, 64, true));
        assertFalse(hold.givenUp());
        for (int t = 1640; t < 4000; t += 40) {
            assertNull(hold.nothingFound(t, 64, true));
            assertFalse(hold.givenUp());
        }
        // drifted off the lava: now it ends
        assertEquals("Gave up landing: no spot to land on within 24 blocks in 30 s, ending the flight in the air", hold.nothingFound(4000, 24, false));
        assertTrue(hold.givenUp());
    }
}
