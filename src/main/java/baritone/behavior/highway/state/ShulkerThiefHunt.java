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

package baritone.behavior.highway.state;

import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.Helper;
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import net.minecraft.world.entity.Entity;

import java.util.Optional;

public class ShulkerThiefHunt extends State {

    private static final int STALL_TIMEOUT_TICKS = 1200; // give up after 60s without ever getting closer to the thief
    private static final int FAIL_COOLDOWN_TICKS = 1200; // how long the one thief we gave up on stays ignored; other thieves still trigger hunts

    public ShulkerThiefHunt(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        HighwayState returnState = context.thiefHuntReturnState() != null ? context.thiefHuntReturnState() : HighwayState.Nothing;

        int swordSlot = context.putBestSwordHotbar();
        if (swordSlot != -1) {
            context.playerContext().player().getInventory().selected = swordSlot;
        }

        Entity target = context.thiefTarget();

        if (target != null && !target.isAlive() && !target.isRemoved()) {
            Helper.HELPER.logDirect("Shulker thief is dead, collecting the drop.");
            finish(context, returnState);
            return;
        }

        if (target == null || target.isRemoved()) {
            // The entity vanished without an observed death (unloaded); try to reacquire.
            // No cooldown on giving up here, a thief that comes back into view is worth re-engaging.
            Optional<Entity> thief = context.findShulkerThief();
            if (thief.isPresent()) {
                context.setThiefTarget(thief.get());
                return;
            }
            Helper.HELPER.logDirect("Lost track of the shulker thief.");
            finish(context, returnState);
            return;
        }

        // The per-thief cooldown keeps a failed target from chaining fresh hunts (each with a new
        // start pos, so an un-cooldowned range abort would let one thief lure us out indefinitely).
        boolean chasedTooFar = context.thiefHuntStartPos() != null
                && target.distanceToSqr(context.thiefHuntStartPos().getX() + 0.5, context.thiefHuntStartPos().getY() + 0.5, context.thiefHuntStartPos().getZ() + 0.5)
                > context.settings().highwayStolenShulkerChaseRange.value * context.settings().highwayStolenShulkerChaseRange.value;
        if (chasedTooFar) {
            Helper.HELPER.logDirect("Shulker thief got beyond the chase range, going back.");
            context.ignoreThief(target, FAIL_COOLDOWN_TICKS);
            finish(context, returnState);
            return;
        }

        double dist = Math.sqrt(context.playerContext().player().distanceToSqr(target));
        if (dist < context.thiefHuntBestDist() - 0.5) {
            context.setThiefHuntBestDist(dist);
            context.resetTimer();
        }
        if (dist <= 3.0) {
            context.attackEntity(target);
            context.resetTimer(); // in melee range, fighting, not stalled
        } else if (context.timer() > STALL_TIMEOUT_TICKS) {
            // A minute without ever closing in: the thief is unreachable or we're stuck. The chase
            // range is the only cap on how long a hunt that IS gaining ground may run.
            Helper.HELPER.logDirect("Can't gain on the shulker thief, giving up on it.");
            context.ignoreThief(target, FAIL_COOLDOWN_TICKS);
            finish(context, returnState);
            return;
        }

        context.baritone().getCustomGoalProcess().setGoalAndPath(new GoalNear(target.blockPosition(), 1));
    }

    private void finish(HighwayContext context, HighwayState returnState) {
        context.setThiefTarget(null);
        context.baritone().getPathingBehavior().cancelEverything();
        context.transitionTo(returnState);
        context.resetTimer();
    }
}
