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

package baritone.api.process;

/**
 * A client mod that moves the elytra glide itself: each tick it moves the player by a step, in place of the glide's
 * physics. While it is {@link #isActive() active}, elytra flight hands it that step through
 * {@link IElytraProcess#getGlideStep()} instead of steering the glide with pitch and fireworks.
 *
 * @see IElytraProcess#setGlideControl(IGlideControl)
 */
public interface IGlideControl {

    /**
     * @return Whether the mod moves the glide by {@link IElytraProcess#getGlideStep()} right now
     */
    boolean isActive();

    /**
     * @return The longest step across it takes, in blocks per tick
     */
    double maxHorizontalStep();

    /**
     * @return The longest step up or down it takes, in blocks per tick
     */
    double maxVerticalStep();

    /**
     * @return Whether the server has the player standing for part of the glide, so the glide has to fit a standing
     * player's hitbox rather than a gliding one
     */
    boolean standsWhileGliding();
}
