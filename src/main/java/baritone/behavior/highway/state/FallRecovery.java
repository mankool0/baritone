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

import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.input.Input;
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.NetherHighwayBuilderBehavior;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class FallRecovery extends State {
    private boolean holdingJump = false;

    public FallRecovery(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        Player player = context.playerContext().player();
        boolean inLava = player.isInLava();
        boolean onFire = player.isOnFire();
        MobEffectInstance fireRes = player.getEffect(MobEffects.FIRE_RESISTANCE);
        int fireResDur = fireRes == null ? 0 : fireRes.getDuration();
        boolean needFireRes = (inLava || onFire) && fireResDur < context.settings().highwayFireRestMinDuration.value;

        boolean pathing = context.baritone().getPathingBehavior().isPathing();

        // Float at the lava surface while we eat and while baritone is still planning
        boolean wantJump = inLava && !pathing;
        if (wantJump != holdingJump) {
            context.playerContext().minecraft().options.keyJump.setDown(wantJump);
            holdingJump = wantJump;
        }

        if (inLava) {
            // Treat lava as a swimmable fluid so baritone can swim us up and out of it.
            context.enableLavaSwimmingForRecovery();
        }

        if (needFireRes && eatGapple(context)) {
            return;
        }
        // Done eating
        NetherHighwayBuilderBehavior.suppressHitResult = false;
        context.playerContext().minecraft().options.keyUse.setDown(false);

        if (context.recoveryTarget() == null) {
            BetterBlockPos target = context.computeRecoveryTarget();
            if (target == null) {
                Helper.HELPER.logDebug("Fall recovery: no built spot to return to yet, retrying.");
                return;
            }
            Helper.HELPER.logDirect("Fall recovery: returning to " + target);
            context.setRecoveryTarget(target);
            context.baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(target));
            return;
        }

        //Wait until we're back on the highway.
        BetterBlockPos feet = context.playerContext().playerFeet();
        if (feet.equals(context.recoveryTarget())) {
            Helper.HELPER.logDirect("Fall recovery complete, resuming build.");
            context.resetRecovery(); // releases keyJump/keyUse and restores allowSwimThroughLava
            context.baritone().getPathingBehavior().cancelEverything();
            context.transitionTo(HighwayState.Nothing);
            return;
        }

        if (!context.baritone().getCustomGoalProcess().isActive()) {
            // Path ended/failed without reaching the target, search further back and try again.
            context.growRecoveryBack();
            BetterBlockPos target = context.computeRecoveryTarget();
            if (target != null) {
                context.setRecoveryTarget(target);
            }
            context.baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(context.recoveryTarget()));
        }
        // else: still pathing back, wait.
    }

    private boolean eatGapple(HighwayContext context) {
        int gappleSlot = context.putItemHotbar(Item.getId(Items.ENCHANTED_GOLDEN_APPLE));
        if (gappleSlot == -1) {
            Helper.HELPER.logDirect("Fall recovery: no gapple to eat for fire resistance!");
            return false;
        }
        ItemStack stack = context.playerContext().player().getInventory().items.get(gappleSlot);
        if (Item.getId(stack.getItem()) == Item.getId(Items.ENCHANTED_GOLDEN_APPLE)) {
            context.playerContext().player().getInventory().selected = gappleSlot;
        }
        // suppressHitResult nulls the hitResult inside startUseItem() via a mixin redirect, preventing
        // block/container interaction while keyUse is held.
        NetherHighwayBuilderBehavior.suppressHitResult = true;
        if (context.playerContext().minecraft().screen == null) {
            context.playerContext().minecraft().options.keyUse.setDown(true);
        } else {
            context.baritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
        }
        return true;
    }
}
