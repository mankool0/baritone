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

import java.util.function.IntPredicate;

/**
 * When an elytra path search that found nothing is asked again, and from where. The same search asked again finds
 * the same nothing, so each retry in a round starts from a kind of start not tried yet in it, the first of them that
 * is usable where the player is (kind 0, the usual start, always is), one a tick. Once every usable kind has failed
 * the round is over, and the next one waits, longer after each ({@link #gapTicks}). After the last retry the flight
 * gives up. A search that finds a path starts the count again.
 */
public final class PathRetry {

    private static final int FIRST_GAP_TICKS = 40;
    private static final int LONGEST_GAP_TICKS = 200;

    private final int kinds;
    private int failures;
    private int rounds;
    // the kinds tried this round, a bit each
    private int tried;
    private int nextTick = Integer.MIN_VALUE;
    private boolean reasonSaid;

    /**
     * @param kinds How many kinds of start there are, at most 31
     */
    public PathRetry(int kinds) {
        this.kinds = Math.max(1, Math.min(kinds, 31));
    }

    /**
     * @return Whether a search may be asked for at {@code now}
     */
    public boolean mayAsk(int now) {
        return now >= this.nextTick;
    }

    /**
     * @return The kind of start the next search takes: the first not tried this round that is {@code usable}, kind 0
     * (the usual start) first of all
     */
    public int kind(IntPredicate usable) {
        for (int kind = 0; kind < this.kinds; kind++) {
            if ((this.tried & (1 << kind)) == 0 && (kind == 0 || usable.test(kind))) {
                return kind;
            }
        }
        return 0;
    }

    /**
     * Counts a search at {@code now} from a start of kind {@code kind} that found nothing
     *
     * @param retries How many may fail in a row before the flight gives up
     * @param usable  The kinds of start usable now
     * @return Whether that was the last, and the flight gives up
     */
    public boolean failed(int now, int retries, int kind, IntPredicate usable) {
        return this.failed(now, retries, kind, usable, true);
    }

    /**
     * @param startMatters Whether a search from another start could do better; when not (the goal is shut off, or
     *                     nothing got closer to it), the round is over at once
     */
    public boolean failed(int now, int retries, int kind, IntPredicate usable, boolean startMatters) {
        this.failures++;
        this.tried |= 1 << Math.max(0, Math.min(kind, this.kinds - 1));
        if (this.failures >= Math.max(1, retries)) {
            this.nextTick = Integer.MAX_VALUE;
            return true;
        }
        for (int next = 1; startMatters && next < this.kinds; next++) {
            if ((this.tried & (1 << next)) == 0 && usable.test(next)) {
                this.nextTick = now + 1;
                return false;
            }
        }
        // every kind usable here has failed: the next round, after a wait
        this.rounds++;
        this.tried = 0;
        this.nextTick = now + gapTicks(this.rounds);
        return false;
    }

    /**
     * Past the last retry, but not to give up yet: on at the longest gap
     */
    public void keepTrying(int now) {
        this.tried = 0;
        this.nextTick = now + LONGEST_GAP_TICKS;
    }

    public int failures() {
        return this.failures;
    }

    /**
     * @return How many rounds of starts have failed through
     */
    public int rounds() {
        return this.rounds;
    }

    /**
     * @return Whether this is the first failure since the count began, to say why once
     */
    public boolean sayReason() {
        if (this.reasonSaid) {
            return false;
        }
        this.reasonSaid = true;
        return true;
    }

    public void succeeded() {
        this.failures = 0;
        this.rounds = 0;
        this.tried = 0;
        this.nextTick = Integer.MIN_VALUE;
        this.reasonSaid = false;
    }

    /**
     * @return The line said once when searches from {@code from} start failing
     */
    public static String retryingLine(String from, String reason) {
        return "Couldn't find a way on from " + from + " (" + reason + "), trying other starts";
    }

    /**
     * @return The line said once a flight when the way on from the end of the path runs out of air at the edge of the
     * chunks the game has sent, the seed having rock past them
     */
    public static String chunkEdgeLine() {
        return "The air on from the end of the path ends at the edge of the chunks the game has sent, and the seed's"
                + " terrain past them is solid (a tunnel dug since the world was made?): the path goes on as more come in";
    }

    /**
     * @return The line the flight gives up on its first path with; scripts read "Failed to compute" as no path
     */
    public static String firstGivesUpLine(String reason) {
        return "Failed to compute path to destination (" + reason + ")";
    }

    /**
     * @return The line the flight gives up on a path again from the player with
     */
    public static String againGivesUpLine(int tries, String reason) {
        return "Failed to recompute segment after " + tries + " tries (" + reason + ")";
    }

    /**
     * @return The line the flight gives up on the way on from the end of its path with
     */
    public static String onwardGivesUpLine(int tries, String reason) {
        return "Failed to compute next segment after " + tries + " tries (" + reason + ")";
    }

    /**
     * 2 s after the first round, twice as long after each one after it, at most 10 s
     */
    static int gapTicks(int rounds) {
        final int doublings = Math.max(0, Math.min(rounds - 1, 3));
        return Math.min(LONGEST_GAP_TICKS, FIRST_GAP_TICKS << doublings);
    }
}
