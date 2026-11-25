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

import baritone.process.elytra.pathfinder.NetherPathfinder;

/**
 * @author Brady
 */
public final class PathCalculationException extends RuntimeException {

    /**
     * Why the search found nothing, said the way a chat line puts it; {@code null} when it doesn't say
     */
    public final String reason;
    /**
     * Why the search found nothing, {@code null} when it doesn't say
     */
    public final NetherPathfinder.Failure failure;

    public PathCalculationException(final String message) {
        this(message, null, null);
    }

    public PathCalculationException(final String message, final NetherPathfinder.Outcome outcome) {
        this(message, outcome.describe(), outcome.failure);
    }

    private PathCalculationException(final String message, final String reason, final NetherPathfinder.Failure failure) {
        super(reason != null ? message + " (" + reason + ")" : message);
        this.reason = reason;
        this.failure = failure;
    }

    /**
     * @return Whether a search from another start could find what this one didn't: it was shut in where it started,
     * or it doesn't say. When the goal is the trouble, a start anywhere else finds the same nothing.
     */
    public boolean startMatters() {
        return this.failure == null || this.failure == NetherPathfinder.Failure.SEALED_START;
    }

    /**
     * @return Whether the air the search went through ends at chunks the game hasn't sent, where the seed has rock: a
     * search once more of them are packed can get further. A start elsewhere in that air can't.
     */
    public boolean waitsForChunks() {
        return this.failure == NetherPathfinder.Failure.ENDS_AT_GUESS;
    }
}
