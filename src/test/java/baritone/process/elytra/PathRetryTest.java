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

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

import static org.junit.Assert.*;

/**
 * A path search from a sealed start, asked again as the flight asks: never twice in a tick, each retry in a round from
 * a start not tried in it, a longer wait after each round, one line about why, and one line to give up with. And with
 * the real planner, the start a few blocks up out of the pocket finds the path the feet don't.
 */
public class PathRetryTest {

    // the kinds of start from the player, as ElytraBehavior has them: feet, over the head, higher, back along the
    // path, bigger cubes
    private static final int KINDS = 5;
    private static final int HIGHER = 2;
    private static final int BACK = 3;

    @Test
    public void aSealedStartIsAskedAgainFromOtherStartsWithGrowingWaitsThenGivenUpOnce() {
        final PathRetry retry = new PathRetry(KINDS);
        // no path to go back along yet
        final IntPredicate usable = kind -> kind != BACK;
        final String reason = "sealed start: the cube of air it is in leads nowhere";
        final List<int[]> asked = new ArrayList<>();
        int reasonLines = 0;
        String giveUp = null;
        for (int tick = 0; tick < 5000 && giveUp == null; tick++) {
            if (!retry.mayAsk(tick)) {
                continue;
            }
            final int kind = retry.kind(usable);
            asked.add(new int[]{tick, kind});
            if (retry.failed(tick, 20, kind, usable)) {
                giveUp = PathRetry.againGivesUpLine(retry.failures(), reason);
            } else if (retry.sayReason()) {
                reasonLines++;
            }
        }
        assertEquals("Failed to recompute segment after 20 tries (sealed start: the cube of air it is in leads nowhere)", giveUp);
        assertTrue(giveUp.startsWith("Failed to recompute"));
        assertEquals(1, reasonLines);
        assertEquals(20, asked.size());
        // rounds of four (the start back along the path isn't one), each from all four, in order
        final int[] kinds = {0, 1, 2, 4};
        int previousGap = 0;
        for (int i = 0; i < asked.size(); i++) {
            assertEquals("ask " + i, kinds[i % 4], asked.get(i)[1]);
            if (i == 0) {
                continue;
            }
            final int gap = asked.get(i)[0] - asked.get(i - 1)[0];
            if (i % 4 != 0) {
                assertEquals("within a round, a tick apart", 1, gap);
            } else {
                assertTrue("rounds wait longer each time: " + gap + " after " + previousGap, gap > previousGap || gap == 200);
                assertTrue(gap >= 40 && gap <= 200);
                previousGap = gap;
            }
        }
        // after giving up nothing is asked again
        assertFalse(retry.mayAsk(Integer.MAX_VALUE - 1));
    }

    @Test
    public void theFirstPathGivesUpAfterOneRound() {
        final PathRetry retry = new PathRetry(KINDS);
        final IntPredicate usable = kind -> kind != BACK;
        int searches = 0;
        boolean gaveUp = false;
        for (int tick = 0; tick < 100 && !gaveUp; tick++) {
            if (!retry.mayAsk(tick)) {
                continue;
            }
            final int kind = retry.kind(usable);
            searches++;
            // as ElytraBehavior gives the first path up: retries for a round, and no further round
            gaveUp = retry.failed(tick, KINDS, kind, usable) || retry.rounds() > 0;
        }
        assertTrue(gaveUp);
        assertEquals(4, searches);
        assertEquals("Failed to compute path to destination (sealed start: x)", PathRetry.firstGivesUpLine("sealed start: x"));
    }

    @Test
    public void whenTheGoalIsTheTroubleEachRoundIsOneSearch() {
        // a sealed goal, or a search that timed out: another start finds the same nothing, and each try can be a
        // search that ran its full time
        final PathRetry retry = new PathRetry(KINDS);
        final IntPredicate all = kind -> true;
        final List<Integer> ticks = new ArrayList<>();
        boolean last = false;
        for (int tick = 0; tick < 5000 && !last; tick++) {
            if (!retry.mayAsk(tick)) {
                continue;
            }
            ticks.add(tick);
            final int kind = retry.kind(all);
            assertEquals("always the usual start", 0, kind);
            // as ElytraBehavior counts them: elytraPathRetries over the kinds of start
            last = retry.failed(tick, 20 / KINDS, kind, all, false);
        }
        assertTrue(last);
        assertEquals(4, ticks.size());
        assertEquals(40, ticks.get(1) - ticks.get(0));
        assertEquals(80, ticks.get(2) - ticks.get(1));
        assertEquals(160, ticks.get(3) - ticks.get(2));
    }

    @Test
    public void aSearchThatFindsAPathStartsTheCountAgain() {
        final PathRetry retry = new PathRetry(KINDS);
        final IntPredicate all = kind -> true;
        assertFalse(retry.failed(0, 20, retry.kind(all), all));
        assertFalse(retry.failed(1, 20, retry.kind(all), all));
        assertEquals(2, retry.kind(all));
        assertFalse(retry.sayReason() && retry.sayReason());
        retry.succeeded();
        assertEquals(0, retry.failures());
        assertEquals(0, retry.kind(all));
        assertTrue(retry.mayAsk(2));
        assertTrue(retry.sayReason());
    }

    @Test
    public void pastTheLastRetryItKeepsTryingAtTheLongestGapWhenTold() {
        final PathRetry retry = new PathRetry(4);
        final IntPredicate all = kind -> true;
        int tick = 0;
        boolean last = false;
        while (!last) {
            while (!retry.mayAsk(tick)) {
                tick++;
            }
            last = retry.failed(tick, 6, retry.kind(all), all);
        }
        retry.keepTrying(tick);
        assertFalse(retry.mayAsk(tick + 199));
        assertTrue(retry.mayAsk(tick + 200));
    }

    /**
     * Rock everywhere in chunks -1 to 2 but a cave from y 34 to 60 over x and z 0 to 31, and a sealed 2-block cube of
     * air at x 8, y 30, z 8 under it
     */
    private static NetherPathfinder pocketUnderACave() {
        final NetherPathfinder ctx = new NetherPathfinder(1, null, NetherPathfinder.Dimension.NETHER, 128);
        for (int cx = -1; cx <= 2; cx++) {
            for (int cz = -1; cz <= 2; cz++) {
                final Chunk chunk = ctx.allocateAndInsertChunk(cx, cz);
                for (int section = 0; section < 8; section++) {
                    chunk.fillSection(section, true);
                }
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        final int wx = cx * 16 + x, wz = cz * 16 + z;
                        for (int y = 0; y < 128; y++) {
                            final boolean cave = wx >= 0 && wx < 32 && wz >= 0 && wz < 32 && y >= 34 && y <= 60;
                            final boolean pocket = wx >= 8 && wx <= 9 && wz >= 8 && wz <= 9 && y >= 30 && y <= 31;
                            if (cave || pocket) {
                                chunk.setBlock(x, y, z, false);
                            }
                        }
                    }
                }
            }
        }
        return ctx;
    }

    private static boolean airIn(int y) {
        return (y >= 30 && y <= 31) || (y >= 34 && y <= 60);
    }

    @Test
    public void fromTheSealedFeetNothingFromHigherUpThePath() {
        final NetherPathfinder ctx = pocketUnderACave();
        final int feet = 30;
        // over the head (feet + 2) is rock, higher (feet + 5) is the cave, as ElytraBehavior's airStart has them
        final IntPredicate usable = kind -> kind == 1 ? airIn(feet + 2) : kind == HIGHER ? airIn(feet + 5) : kind != BACK;
        final PathRetry retry = new PathRetry(KINDS);

        int kind = retry.kind(usable);
        assertEquals(0, kind);
        NetherPathfinder.Outcome outcome = new NetherPathfinder.Outcome();
        PathSegment segment = ctx.pathFind(8, feet, 8, 24, 50, 24, false, false, 10000, true, 8.0, outcome);
        assertNull(segment);
        assertEquals(NetherPathfinder.Failure.SEALED_START, outcome.failure);
        assertEquals("sealed start: the cube of air it is in leads nowhere", outcome.describe());
        assertFalse(retry.failed(0, 20, kind, usable));

        // the next start is a tick on, and not over the head, which is in the rock
        assertFalse(retry.mayAsk(0));
        assertTrue(retry.mayAsk(1));
        kind = retry.kind(usable);
        assertEquals(HIGHER, kind);
        outcome = new NetherPathfinder.Outcome();
        segment = ctx.pathFind(8, feet + 5, 8, 24, 50, 24, false, false, 10000, true, 8.0, outcome);
        assertNotNull(segment);
        assertTrue(segment.finished);
        assertNull(outcome.failure);
        assertNull(outcome.describe());
        retry.succeeded();
        assertEquals(0, retry.kind(usable));
    }
}
