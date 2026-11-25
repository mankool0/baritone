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

package baritone.process.elytra.pathfinder;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * A search that finds nothing says why: shut in a pocket at the start, or the goal shut off from a big stretch of air
 * the start is in.
 */
public class SearchFailureTest {

    private static NetherPathfinder solid(int minCx, int maxCx, int minCz, int maxCz) {
        final NetherPathfinder ctx = new NetherPathfinder(1, null, NetherPathfinder.Dimension.NETHER, 128);
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                final Chunk chunk = ctx.allocateAndInsertChunk(cx, cz);
                for (int section = 0; section < 8; section++) {
                    chunk.fillSection(section, true);
                }
            }
        }
        return ctx;
    }

    private static void air(NetherPathfinder ctx, int x0, int x1, int y0, int y1, int z0, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                final Chunk chunk = ctx.getChunk(x >> 4, z >> 4);
                for (int y = y0; y <= y1; y++) {
                    chunk.setBlock(x & 15, y, z & 15, false);
                }
            }
        }
    }

    @Test
    public void shutInAPocketAtTheStart() {
        final NetherPathfinder ctx = solid(-1, 1, -1, 1);
        air(ctx, 2, 5, 40, 41, 2, 3);
        final NetherPathfinder.Outcome outcome = new NetherPathfinder.Outcome();
        // the goal is in chunks the table doesn't have, air to the search
        assertNull(ctx.pathFind(2, 40, 2, 300, 60, 0, false, false, 10000, true, 8.0, outcome));
        assertEquals(NetherPathfinder.Failure.SEALED_START, outcome.failure);
        assertEquals(2, outcome.nodes);
        assertEquals("sealed start: the 2 cubes of air it is in lead nowhere", outcome.describe());
    }

    @Test
    public void theGoalShutOffFromALongTunnel() {
        // a 2 by 2 tunnel 600 blocks long in rock, and beside its end, past a wall, a pocket with the goal in it
        final NetherPathfinder ctx = solid(-1, 40, -1, 1);
        air(ctx, 8, 607, 40, 41, 8, 9);
        air(ctx, 2, 3, 40, 41, 8, 9);
        final NetherPathfinder.Outcome outcome = new NetherPathfinder.Outcome();
        assertNull(ctx.pathFind(10, 40, 8, 2, 40, 8, false, false, 10000, true, 8.0, outcome));
        assertEquals(NetherPathfinder.Failure.SEALED_GOAL, outcome.failure);
        assertTrue(outcome.nodes > 256);
        assertEquals("sealed goal: the air at the goal is closed off", outcome.describe());
    }

    /**
     * Chunks the seed made up, not from the game, each made all rock: what the seed has where a tunnel was dug since
     */
    private static void guessedRock(NetherPathfinder ctx, int minCx, int maxCx, int minCz, int maxCz) {
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                final Chunk chunk = ctx.getOrGenChunk(cx, cz);
                for (int section = 0; section < 8; section++) {
                    chunk.fillSection(section, true);
                }
            }
        }
    }

    @Test
    public void aTunnelThatGoesOnPastTheChunksFromTheGame() {
        // a 2 by 2 tunnel 600 blocks long through the game's chunks, to the last of them, where the seed has rock. the
        // goal is far off in the seed's rock too, but that isn't why: the next chunks from the game can open the way
        final NetherPathfinder ctx = solid(-1, 37, -1, 1);
        air(ctx, 8, 607, 40, 41, 8, 9);
        guessedRock(ctx, 38, 46, -1, 1);
        final Chunk goalChunk = ctx.getOrGenChunk(43, 0);
        for (int x = 700; x <= 701; x++) {
            for (int y = 40; y <= 41; y++) {
                for (int z = 8; z <= 9; z++) {
                    goalChunk.setBlock(x & 15, y, z & 15, false);
                }
            }
        }
        final NetherPathfinder.Outcome outcome = new NetherPathfinder.Outcome();
        // from the far end of the tunnel, as the way on from the end of a path that got there
        assertNull(ctx.pathFind(606, 40, 8, 700, 40, 8, false, false, 10000, false, 8.0, outcome));
        assertEquals(NetherPathfinder.Failure.ENDS_AT_GUESS, outcome.failure);
        assertTrue(outcome.nodes > 256);
        assertEquals("the air it is in ends at the edge of the chunks the game has sent, and the seed's terrain past them is solid", outcome.describe());
    }

    @Test
    public void aGoalShutOffInTheGamesChunksIsTheGoalsFault() {
        // the same tunnel on into the seed's rock, but the goal is in a pocket the game has sent all of: no chunk still
        // to come gets there
        final NetherPathfinder ctx = solid(-1, 37, -1, 1);
        air(ctx, 8, 607, 40, 41, 8, 9);
        air(ctx, 2, 3, 40, 41, 8, 9);
        guessedRock(ctx, 38, 40, -1, 1);
        final NetherPathfinder.Outcome outcome = new NetherPathfinder.Outcome();
        assertNull(ctx.pathFind(10, 40, 8, 2, 40, 8, false, false, 10000, false, 8.0, outcome));
        assertEquals(NetherPathfinder.Failure.SEALED_GOAL, outcome.failure);
    }

    @Test
    public void aPathFoundSaysNothing() {
        final NetherPathfinder ctx = solid(-1, 1, -1, 1);
        air(ctx, 2, 30, 40, 50, 2, 30);
        final NetherPathfinder.Outcome outcome = new NetherPathfinder.Outcome();
        final PathSegment segment = ctx.pathFind(4, 42, 4, 28, 48, 28, false, false, 10000, true, 8.0, outcome);
        assertNotNull(segment);
        assertNull(outcome.failure);
        assertNull(outcome.describe());
    }

    @Test
    public void theSealedCheckSeesThroughToOpenAir() {
        final NetherPathfinder ctx = solid(-1, 1, -1, 1);
        air(ctx, 2, 3, 40, 41, 2, 3);
        assertTrue(PathFinder.sealedAround(ctx, new NodePos(Size.X2, 2, 40, 2), true, 4096));
        // a tunnel from the pocket to the edge of the allocated chunks, past which is air
        air(ctx, 2, 31, 40, 41, 2, 3);
        assertFalse(PathFinder.sealedAround(ctx, new NodePos(Size.X2, 2, 40, 2), true, 4096));
    }
}
