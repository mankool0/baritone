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

import baritone.api.utils.Helper;
import baritone.behavior.highway.enums.HighwayState;
import baritone.utils.accessor.ILivingEntity;
import baritone.utils.accessor.IMinecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Gapple and golden carrot eating: the thresholds that start an eat, and holding use until the
 * server confirms it.
 */
public class Eating extends ContextComponent {
    private HighwayState emergencyEatReturnState = HighwayState.Nothing;
    private HighwayState goldenCarrotEatReturnState = HighwayState.Nothing;

    // Ticks a predicted eat may go without the server confirming it before we press use again
    private static final int EAT_CONFIRM_TICKS = 20;

    Eating(HighwayContext ctx) {
        super(ctx);
    }

    /**
     * Eats the selected food for this tick. Call it with no container open; a screen is fine.
     * Vanilla presses a held use key only from handleKeybinds(), which never runs while a screen is
     * open (an unfocused window opens the pause menu), so we press it ourselves. That sets
     * rightClickDelay, so handleKeybinds() won't start a second use this tick. The held key keeps
     * it from releasing ours.
     * The client predicts a use as soon as it sends one, and only the server's using-item flag
     * ends it again. A use the server dropped stays predicted, and nothing sends another. Once a use
     * goes unconfirmed for EAT_CONFIRM_TICKS, release it and leave the key up this tick, so the
     * next tick starts a fresh one.
     */
    public void holdUseToEat() {
        LocalPlayer player = playerContext.player();
        if (player.isUsingItem()) {
            if (player.getTicksUsingItem() > EAT_CONFIRM_TICKS && !((ILivingEntity) player).isServerUsingItem()) {
                Helper.HELPER.logDiagnostic("Server never confirmed the eat after " + player.getTicksUsingItem() + " ticks, pressing use again.");
                releaseUseToEat();
                return;
            }
        } else if (player.connection.hasClientLoaded() && ((IMinecraft) playerContext.minecraft()).getRightClickDelay() == 0) {
            // The server drops uses until the client has loaded, and the loading screen no longer stops us pressing
            ((IMinecraft) playerContext.minecraft()).callStartUseItem();
        }
        playerContext.minecraft().options.keyUse.setDown(true);
    }

    /** Lets go of an eat. Releasing the key alone does that only through handleKeybinds(), which a screen stops. */
    public void releaseUseToEat() {
        playerContext.minecraft().options.keyUse.setDown(false);
        LocalPlayer player = playerContext.player();
        if (player != null && player.isUsingItem()) {
            playerContext.minecraft().gameMode.releaseUsingItem(player);
        }
    }

    public float gappleEatHealthThreshold() {
        return Math.min(settings.highwayGappleEatHealthThreshold.value, 20f);
    }

    public int gappleEatFoodThreshold() {
        return Math.min(settings.highwayGappleEatFoodThreshold.value, 19);
    }

    /** Capped at 19: a golden carrot can't be eaten on a full bar, unlike a gapple. */
    public int goldenCarrotEatFoodThreshold() {
        return Math.min(settings.highwayGoldenCarrotEatFoodThreshold.value, 19);
    }

    /** Hunger goes to golden carrots while we have any, and back to gapples once they run out. */
    public boolean canEatGoldenCarrots() {
        return goldenCarrotEatFoodThreshold() > 0 && ctx.inventory().getGoldenCarrotCountInventory() > 0;
    }

    /**
     * Whether hunger in this state is golden carrot work. Not in a fight: combat preempts a carrot
     * eat, unlike the emergency gapple one, so a carrot eat started there would bounce straight back.
     */
    public boolean goldenCarrotsFeed(HighwayState state) {
        return canEatGoldenCarrots() && state != HighwayState.MobCombat && state != HighwayState.MobCombatReturn;
    }

    /**
     * The food level a golden carrot eat stops above: past both thresholds that start one, or a
     * carrot threshold under the emergency one would hand us straight back to another eat.
     */
    public int goldenCarrotEatTarget() {
        return Math.max(goldenCarrotEatFoodThreshold(), gappleEatFoodThreshold());
    }

    public int fireRestMinDuration() {
        return Math.min(settings.highwayFireRestMinDuration.value, 5900);
    }

    public HighwayState emergencyEatReturnState() {
        return emergencyEatReturnState;
    }

    public void setEmergencyEatReturnState(HighwayState state) {
        this.emergencyEatReturnState = state;
    }

    public HighwayState goldenCarrotEatReturnState() {
        return goldenCarrotEatReturnState;
    }

    public void setGoldenCarrotEatReturnState(HighwayState state) {
        this.goldenCarrotEatReturnState = state;
    }
}
