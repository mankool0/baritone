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

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.Helper;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.player.Player;

import java.util.Optional;

public class MobCombat extends State {

    private static final int STALL_TIMEOUT_TICKS = 200; // give up on a mob after 10s of chasing without getting any closer
    private static final int IGNORE_TICKS = 1200; // how long a mob we gave up on stays ignored, unless it comes within reach

    public MobCombat(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        int swordSlot = context.putBestSwordHotbar();
        if (swordSlot != -1 && swordSlot < 9) {
            context.playerContext().player().getInventory().setSelectedSlot(swordSlot);
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
        Player player = context.playerContext().player();

        // Only aim-and-swing in melee range; a forced look target further out would fight the
        // pathing rotations while we're still closing in
        if (context.settings().highwayMobCombatAttack.value
                && target.getBoundingBox().distanceToSqr(player.getEyePosition()) <= 3.0 * 3.0) {
            context.attackEntity(target);
        }

        double approach = context.settings().highwayMobCombatApproachDistance.value;
        double reach = context.mobReachDistance(target);
        if (reach <= approach) {
            context.resetChaseStall(); // fighting, not stalled
            // Close enough for the killaura: stand still instead of walking into the mob. This also
            // drops a builder still running when combat began with the mob already this close
            if (context.baritone().getPathingControlManager().mostRecentInControl().isPresent()
                    || context.baritone().getPathingBehavior().isPathing()) {
                context.baritone().getPathingBehavior().cancelEverything();
            }
            return;
        }

        // A mob we can't get closer to (no path, across lava or a gap, up on a ledge) would hold us
        // here, or keep pulling us back to it, for as long as it stays angry. Ghasts are left out:
        // findMobTargetingPlayer keeps a ghast engaged on purpose while it's within 40 blocks, and
        // this shouldn't undercut that.
        if (!(target instanceof Ghast) && context.chaseStalled(target, reach, STALL_TIMEOUT_TICKS)) {
            Helper.HELPER.logDirect("Can't get to the " + target.getName().getString() + ", ignoring it for a while.");
            context.ignoreMob(target, IGNORE_TICKS);
            return;
        }

        BlockPos mobPos = target.blockPosition();
        Goal goal = new GoalInReach(mobPos.getX(), mobPos.getY(), mobPos.getZ(),
                target.getBbWidth() / 2, target.getBbHeight(), player.getEyeHeight(), approach);
        context.baritone().getCustomGoalProcess().setGoalAndPath(goal);
    }

    /**
     * Standing spots from anywhere inside which {@link HighwayContext#mobReachDistance} is at most
     * {@code distance}, wherever in its own block the mob stands (both of us on full-block floors).
     * Every spot outside reach is outside the goal, so the chase can't settle short of reach the way
     * a plain {@code GoalNear} did. Built only from block positions and sizes, so it stays equal to last
     * tick's goal until the mob changes block, and a moving mob doesn't force a repath every tick.
     */
    private record GoalInReach(int mobX, int mobY, int mobZ, double halfWidth, double height,
                               double eyeHeight, double distance) implements Goal, IGoalRenderPos {

        @Override
        public boolean isInGoal(int x, int y, int z) {
            // Farthest we and the mob's hitbox can be apart along each axis from within these blocks
            double gapX = Math.max(0, Math.abs(x - mobX) + 1 - halfWidth);
            double gapZ = Math.max(0, Math.abs(z - mobZ) + 1 - halfWidth);
            double gapY = Math.max(verticalGap(y), verticalGap(y + eyeHeight));
            return gapX * gapX + gapY * gapY + gapZ * gapZ <= distance * distance;
        }

        private double verticalGap(double ourY) {
            return Math.max(0, Math.max(mobY - ourY, ourY - (mobY + height)));
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return GoalBlock.calculate(x - mobX, y - mobY, z - mobZ);
        }

        @Override
        public BlockPos getGoalPos() {
            return new BlockPos(mobX, mobY, mobZ);
        }
    }
}