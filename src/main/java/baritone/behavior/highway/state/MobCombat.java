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

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.utils.BetterBlockPos;
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import java.util.Optional;

public class MobCombat extends State {

    public MobCombat(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        int swordSlot = context.putBestSwordHotbar();
        if (swordSlot != -1) {
            context.playerContext().player().getInventory().selected = swordSlot;
        }

        Optional<Entity> mob = context.findMobTargetingPlayer();

        if (mob.isEmpty()) {
            // All threats gone, path back to where we were before resuming
            context.baritone().getPathingBehavior().cancelEverything();
            context.setCurrentMobTarget(null);
            context.transitionTo(HighwayState.MobCombatReturn);
            return;
        }

        Entity target = mob.get();
        context.setCurrentMobTarget(target);

        // Only aim-and-swing in melee range; a forced look target further out would fight the
        // pathing rotations while we're still closing in
        if (context.settings().highwayMobCombatAttack.value
                && context.playerContext().player().distanceToSqr(target) <= 3.0 * 3.0) {
            context.attackEntity(target);
        }

        // Mirror FollowProcess.towards()
        BlockPos pos;
        if (Baritone.settings().followOffsetDistance.value == 0) {
            pos = target.blockPosition();
        } else {
            GoalXZ g = GoalXZ.fromDirection(
                    target.position(),
                    Baritone.settings().followOffsetDirection.value,
                    Baritone.settings().followOffsetDistance.value
            );
            pos = new BetterBlockPos(g.getX(), (int) target.position().y, g.getZ());
        }

        Goal goal = new GoalNear(pos, Baritone.settings().followRadius.value);
        context.baritone().getCustomGoalProcess().setGoalAndPath(goal);
    }
}