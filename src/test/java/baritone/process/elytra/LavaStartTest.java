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

import baritone.process.elytra.pathfinder.Chunk;
import baritone.process.elytra.pathfinder.NetherPathfinder;
import baritone.process.elytra.pathfinder.PathSegment;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * A player deep in a lava lake. The planner packs lava as rock and starts a path from the nearest air cube it finds,
 * which can be a sealed pocket beside the player: then no path leaves it and the flight fails the moment it is asked
 * for. Started from the lake's surface straight over the feet, it is found.
 */
public class LavaStartTest {

    /** Lava (rock to the planner) under y 32 over x and z 0 to 31, a cave of air over it to y 60, and a sealed
     * 2-block cube of air at x 10, y 4, z 8, beside feet at (8, 5, 8). */
    private static NetherPathfinder lake() {
        final NetherPathfinder ctx = new NetherPathfinder(1, null, NetherPathfinder.Dimension.NETHER, 128);
        for (int cx = -1; cx <= 2; cx++) {
            for (int cz = -1; cz <= 2; cz++) {
                final Chunk chunk = ctx.allocateAndInsertChunk(cx, cz);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        final int wx = cx * 16 + x, wz = cz * 16 + z;
                        for (int y = 0; y < 128; y++) {
                            final boolean cave = wx >= 0 && wx < 32 && wz >= 0 && wz < 32 && y >= 32 && y <= 60;
                            final boolean pocket = wx >= 10 && wx <= 11 && wz >= 8 && wz <= 9 && y >= 4 && y <= 5;
                            chunk.setBlock(x, y, z, !cave && !pocket);
                        }
                    }
                }
            }
        }
        return ctx;
    }

    @Test
    public void fromTheSurfaceStraightOverTheFeetThePathIsFound() {
        final int y = ElytraBehavior.lavaStartY(5, h -> h <= 31, h -> h >= 32 && h <= 60, ElytraBehavior.LAVA_START_RISE);
        assertEquals(32, y);
        final PathSegment segment = lake().pathFind(8, y, 8, 16, 50, 16, false, false, 10000, true, 8.0);
        assertNotNull(segment);
        assertTrue(segment.finished);
    }

    @Test
    public void atTheSurfaceTheBlockOverTheFeet() {
        assertEquals(6, ElytraBehavior.lavaStartY(5, h -> false, h -> true, 64));
    }

    @Test
    public void underRockTheFeetAsBefore() {
        assertEquals(5, ElytraBehavior.lavaStartY(5, h -> h <= 20, h -> false, 64));
    }

    @Test
    public void noFurtherUpThanTheRise() {
        assertEquals(69, ElytraBehavior.lavaStartY(5, h -> h <= 68, h -> true, 64));
        assertEquals(5, ElytraBehavior.lavaStartY(5, h -> h <= 69, h -> true, 64));
    }
}
