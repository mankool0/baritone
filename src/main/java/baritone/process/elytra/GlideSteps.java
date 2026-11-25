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

import baritone.Baritone;

/**
 * How elytra flight moves the glide by steps ({@link baritone.api.Settings#elytraFlyBySteps}): the most a step moves
 * the player, and the hitbox the steps have to fit.
 */
public final class GlideSteps {

    /**
     * @return The longest step across, in blocks per tick
     */
    public double maxHorizontalStep() {
        return Baritone.settings().elytraStepMaxHorizontalSpeed.value;
    }

    /**
     * @return The longest step up or down, in blocks per tick
     */
    public double maxVerticalStep() {
        return Baritone.settings().elytraStepMaxVerticalSpeed.value;
    }

    /**
     * @return Whether the server has the player standing for part of the glide
     * ({@link baritone.api.Settings#elytraSaveDurability}), so the glide has to fit a standing player's hitbox rather
     * than a gliding one
     */
    public boolean standsWhileGliding() {
        return Baritone.settings().elytraSaveDurability.value;
    }
}
