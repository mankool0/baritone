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

package baritone.cache;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * The cached world's locations of a block are looked for a ring of regions at a time, and the search stops when it has
 * enough: only when none of a region still to look in can be nearer than them.
 */
public class CachedLocationsRingTest {

    /**
     * Two portals' worth of portal blocks, 6 each, at the far side of region (0, 0)
     */
    private static List<BlockPos> twoPortalsAt(int x, int z) {
        final List<BlockPos> found = new ArrayList<>();
        for (int portal = 0; portal < 2; portal++) {
            for (int y = 0; y < 3; y++) {
                for (int w = 0; w < 2; w++) {
                    found.add(new BlockPos(x + portal * 4 + w, 70 + y, z));
                }
            }
        }
        return found;
    }

    @Test
    public void nearTheEdgeTheNextRegionIsLookedInToo() {
        // the player 20 blocks from region (0, 0)'s east edge, the 12 blocks found 480 blocks west: a portal in region
        // (1, 0) could be 21 blocks off
        final List<BlockPos> found = twoPortalsAt(10, 256);
        assertFalse(CachedWorld.nearerThanTheRest(found, 10, 491, 256, 0, 2));
    }

    @Test
    public void enoughCloseByEndsTheSearch() {
        // the same 12 blocks right by the player in the middle of the region: every other region is 200 or more off
        final List<BlockPos> found = twoPortalsAt(250, 256);
        assertTrue(CachedWorld.nearerThanTheRest(found, 10, 256, 256, 0, 2));
    }

    @Test
    public void tooFewCloseByIsNotEnough() {
        // 6 by the player and 6 far off: of the 10 asked for, only 6 are sure to be nearer than the next region
        final List<BlockPos> found = new ArrayList<>();
        found.addAll(twoPortalsAt(250, 256).subList(0, 6));
        found.addAll(twoPortalsAt(10, 10).subList(0, 6));
        assertFalse(CachedWorld.nearerThanTheRest(found, 10, 256, 256, 0, 2));
    }

    @Test
    public void afterTheLastRingThereIsNothingLeftToBeNearer() {
        final List<BlockPos> found = twoPortalsAt(10, 256);
        assertTrue(CachedWorld.nearerThanTheRest(found, 10, 491, 256, 2, 2));
    }

    @Test
    public void distanceToARegion() {
        assertEquals(0, CachedWorld.distanceSqToRegion(100, 100, 0, 0));
        // 21 blocks west of region (1, 0), level with it
        assertEquals(21 * 21, CachedWorld.distanceSqToRegion(491, 256, 1, 0));
        // diagonally off region (-1, -1)'s corner at (-1, -1)
        assertEquals(2 * 2 + 3 * 3, CachedWorld.distanceSqToRegion(1, 2, -1, -1));
    }
}
