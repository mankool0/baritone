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

import baritone.api.Settings;

/**
 * Whether an elytra flight has to come down for safety, and why, said once it has held for {@link #HOLD_TICKS}: a
 * tick's flicker of the chest slot or of the glide control (an elytra swapped in, a slot update from the server) would
 * otherwise send the whole flight down to land.
 */
public final class ElytraSafety {

    // a reason has to hold this long before the flight lands for it
    static final int HOLD_TICKS = 10;

    private int heldTicks;

    /**
     * @param chestIsElytra   Whether an elytra is in the chest slot
     * @param durabilityLeft  The elytra's durability left
     * @param canGlide        Whether the elytra can glide (it isn't about to break)
     * @param glidesBySteps   Whether the glide is moved by steps right now
     * @param fireworks       The fireworks in the inventory
     * @return Why the flight should land now, as the "Emergency landing - " line goes on; {@code null} when it needn't
     */
    public static String reason(boolean chestIsElytra, int durabilityLeft, boolean canGlide, boolean glidesBySteps, int fireworks, Settings settings) {
        if (!chestIsElytra) {
            return "there is no elytra in the chest slot";
        }
        final int minimum = settings.elytraMinimumDurability.value;
        if (durabilityLeft < minimum) {
            return "the elytra is down to " + durabilityLeft + " durability, under elytraMinimumDurability (" + minimum + ")";
        }
        if (glidesBySteps) {
            // a glide moved by steps uses no fireworks
            return canGlide ? null : "the elytra can't glide any more";
        }
        final int least = settings.elytraMinFireworksBeforeLanding.value;
        if (fireworks <= least) {
            return fireworks == 0
                    ? "there are no fireworks, and nothing moves the glide by steps"
                    : fireworks + " fireworks left, no more than elytraMinFireworksBeforeLanding (" + least + ")";
        }
        return null;
    }

    /**
     * @param glidedBySteps Whether this flight has been moved by glide steps at some point
     * @return Whether the glide steps stopped mid-flight and there are no fireworks to fly on instead: then the flight
     * is better left alone than steered by the physics of a glide that nothing pushes
     */
    public static boolean stepsStopped(boolean glidedBySteps, boolean glidesBySteps, int fireworks) {
        return glidedBySteps && !glidesBySteps && fireworks == 0;
    }

    /**
     * Counts a tick with {@code reason} ({@code null} for none)
     *
     * @return The reason once it has held for {@link #HOLD_TICKS} ticks in a row, else {@code null}
     */
    public String hold(String reason) {
        if (reason == null) {
            this.heldTicks = 0;
            return null;
        }
        // a reason that changes as it holds (a count going down) is still one
        this.heldTicks++;
        return this.heldTicks >= HOLD_TICKS ? reason : null;
    }

    public void reset() {
        this.heldTicks = 0;
    }
}
