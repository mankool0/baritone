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

package baritone.utils.accessor;

public interface ILivingEntity {

    /**
     * The using-item flag as the server last synced it. LocalPlayer.isUsingItem() is the client's
     * own prediction instead, which a use the server dropped never clears.
     */
    boolean isServerUsingItem();

    /**
     * The gliding flag as it was last set, by the server's reports or the client's own start, under any glide held by
     * {@link #setGlideHeld(boolean)}
     */
    boolean isFallFlyingFlagSet();

    /**
     * Whether elytra flight holds the player's glide, standing ({@link baritone.api.Settings#elytraSaveDurability})
     */
    boolean isGlideHeld();

    /**
     * Holds the glide: {@code isFallFlying()} says gliding whatever the server reports, and the pose is standing, so
     * the hitbox is a standing player's. The caller refreshes the dimensions.
     */
    void setGlideHeld(boolean held);
}
