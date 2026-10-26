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

import net.minecraft.core.BlockPos;

/**
 * Attempts counted against one spot. Counting at a different spot starts over from zero, so the
 * budget a caller gives a spot is only ever spent on that spot.
 */
final class SpotAttempts {
    private BlockPos spot;
    private int count;

    /** Counts at {@code spot} from now on, starting over if the count so far was for another one. */
    SpotAttempts at(BlockPos spot) {
        if (!spot.equals(this.spot)) {
            this.spot = spot;
            count = 0;
        }
        return this;
    }

    /** Counts one more attempt at the current spot and returns how many that makes. */
    int next() {
        return ++count;
    }

    /** Forgets the spot along with its count. */
    void clear() {
        spot = null;
        count = 0;
    }
}
