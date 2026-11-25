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

/**
 * A flight moved by glide steps at the end of its path with nowhere found to land holds still there and looks again
 * as chunks come in, for so long, then ends in the air. It says so when it starts holding and when it gives up, and
 * nothing in between, or the search that finds nothing starts again and says so every tick. Over lava it doesn't end:
 * the flight ended in the air there falls in.
 */
public final class LandingHold {

    private final int radius;
    private final int holdTicks;
    private int since = -1;
    private boolean givenUp;
    private boolean lavaSaid;

    /**
     * @param radius    How far across the search looks, to say
     * @param holdTicks How long to hold
     */
    public LandingHold(int radius, int holdTicks) {
        this.radius = radius;
        this.holdTicks = holdTicks;
    }

    /**
     * Counts a search at {@code now} that came to its end with nowhere to land
     *
     * @return The line to say, {@code null} for none
     */
    public String nothingFound(int now) {
        return this.nothingFound(now, this.radius, false);
    }

    /**
     * @param radius   How far across the search looked, to say
     * @param overLava Whether there's lava under the player: the hold goes on past its time, and says so once
     */
    public String nothingFound(int now, int radius, boolean overLava) {
        if (this.givenUp) {
            return null;
        }
        if (this.since < 0) {
            this.since = now;
            return "No spot to land on within " + radius + " blocks of here, holding still for up to "
                    + this.holdTicks / 20 + " s while chunks come in";
        }
        if (now - this.since < this.holdTicks) {
            return null;
        }
        if (overLava) {
            if (this.lavaSaid) {
                return null;
            }
            this.lavaSaid = true;
            return "No spot to land on within " + radius + " blocks in " + this.holdTicks / 20
                    + " s, and there's lava below: holding on and looking again, not ending the flight over it";
        }
        this.givenUp = true;
        return "Gave up landing: no spot to land on within " + radius + " blocks in " + this.holdTicks / 20
                + " s, ending the flight in the air";
    }

    /**
     * @return Whether the hold is over and the flight ends
     */
    public boolean givenUp() {
        return this.givenUp;
    }

    /**
     * Somewhere to land was found, or the flight goes on: a later hold is held for as long again
     */
    public void reset() {
        this.since = -1;
        this.givenUp = false;
        this.lavaSaid = false;
    }
}
