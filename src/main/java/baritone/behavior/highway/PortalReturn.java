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

import baritone.Baritone;
import baritone.api.schematic.CompositeSchematic;
import baritone.api.schematic.WhiteBlackSchematic;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;

public final class PortalReturn {

    private enum Phase { STEP_OUT, WAIT_COOLDOWN, STEP_IN, WAIT_TELEPORT }

    private final HighwayContext context;
    private final Baritone baritone;
    private final IPlayerContext ctx;

    private Phase phase = Phase.STEP_OUT;
    private int phaseTimer = 0;
    private BlockPos returnPortalPos = null;
    private boolean exitPrepStarted = false;
    private int exitPrepTimer = 0;

    public PortalReturn(HighwayContext context) {
        this.context = context;
        this.baritone = context.baritone();
        this.ctx = context.playerContext();
    }

    /** Called on the tick a dimension mismatch is first detected. */
    public void reset() {
        phase = Phase.STEP_OUT;
        phaseTimer = 0;
        returnPortalPos = null;
        exitPrepStarted = false;
    }

    public void tick(ResourceKey<Level> startDimension) {
        switch (phase) {
            case STEP_OUT -> {
                if (!context.isPlayerInPortal()) {
                    baritone.getInputOverrideHandler().clearAllKeys();
                    cancelExitPrep();
                    phase = Phase.WAIT_COOLDOWN;
                    phaseTimer = 0;
                    return;
                }
                if (returnPortalPos == null) {
                    returnPortalPos = ctx.playerFeet();
                }
                BlockPos out = context.findPortalEscapeTarget();
                if (out != null) {
                    cancelExitPrep();
                    context.walkTowardBlock(out);
                    return;
                }
                prepareExit();
            }
            case WAIT_COOLDOWN -> {
                if (context.isPlayerInPortal()) {
                    phase = Phase.STEP_OUT;
                    return;
                }
                if (++phaseTimer >= 30) {
                    phase = Phase.STEP_IN;
                    phaseTimer = 0;
                }
            }
            case STEP_IN -> {
                if (returnPortalPos == null || !(ctx.world().getBlockState(returnPortalPos).getBlock() instanceof NetherPortalBlock)) {
                    baritone.getInputOverrideHandler().clearAllKeys();
                    if (context.timer() % 1200 == 0) {
                        Helper.HELPER.logDirect("The exit portal is gone; stand us in a portal back to " + startDimension.location() + " or stop the builder.");
                    }
                    return;
                }
                Vec3 pos = ctx.player().position();
                double dx = returnPortalPos.getX() + 0.5 - pos.x;
                double dz = returnPortalPos.getZ() + 0.5 - pos.z;
                if (dx * dx + dz * dz <= 0.09) {
                    baritone.getInputOverrideHandler().clearAllKeys();
                    phase = Phase.WAIT_TELEPORT;
                    phaseTimer = 0;
                    return;
                }
                context.walkTowardBlock(returnPortalPos);
            }
            case WAIT_TELEPORT -> {
                if (!context.isPlayerInPortal()) {
                    phase = Phase.STEP_IN;
                    return;
                }
                if (++phaseTimer > 200) {
                    // mistimed (lag spike?): redo the dance from the top
                    phase = Phase.STEP_OUT;
                    phaseTimer = 0;
                }
            }
        }
    }

    private void prepareExit() {
        BlockPos target = context.portalExitPrepTarget();
        if (target == null) {
            baritone.getInputOverrideHandler().clearAllKeys();
            if (context.timer() % 1200 == 0) {
                Helper.HELPER.logDirect("No usable cell next to the exit portal; help us out or stop the builder.");
            }
            return;
        }

        BlockPos open = context.findPortalEscapeTarget(false);
        if (open != null) {
            // walkable but no floor (floating portal): fill it directly
            cancelExitPrep();
            directFillFloor(open.below());
            return;
        }

        if (!exitPrepStarted) {
            if (!nudgeTowardExit(target)) {
                return; // still shuffling into position
            }
            exitPrepStarted = true;
            exitPrepTimer = 0;
            Helper.HELPER.logDirect("Exit portal is blocked; clearing a step-out spot at " + target.toShortString() + ".");
            context.settings().buildRepeat.value = new Vec3i(0, 0, 0);
            WhiteBlackSchematic floor = new WhiteBlackSchematic(1, 1, 1, context.blackListBlocks(), Blocks.NETHERRACK.defaultBlockState(), false, true, true);
            floor.setThrowawayFallback(Blocks.OBSIDIAN.defaultBlockState());
            WhiteBlackSchematic clear = new WhiteBlackSchematic(1, 2, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false);
            CompositeSchematic schem = new CompositeSchematic(0, 0, 0);
            schem.put(floor, 0, 0, 0);
            schem.put(clear, 0, 1, 0);
            baritone.getBuilderProcess().build("portalExitPrep", schem, target.below());
            return;
        }
        exitPrepTimer++;
        boolean builderWorking = baritone.getBuilderProcess().isActive() && !baritone.getBuilderProcess().isPaused();
        if (builderWorking && exitPrepTimer <= 300) {
            return; // builder is breaking the blockage; we wait inside the portal
        }
        if (builderWorking) {
            baritone.getPathingBehavior().cancelEverything();
        }
        if (context.timer() % 1200 == 0) {
            Helper.HELPER.logDirect("Still opening a step-out spot next to the exit portal.");
        }
    }

    private boolean nudgeTowardExit(BlockPos exitCell) {
        BetterBlockPos feet = ctx.playerFeet();
        double targetX = feet.x + 0.5 + 0.28 * Math.signum(exitCell.getX() - feet.x);
        double targetZ = feet.z + 0.5 + 0.28 * Math.signum(exitCell.getZ() - feet.z);
        Vec3 pos = ctx.player().position();
        double dx = targetX - pos.x;
        double dz = targetZ - pos.z;
        if (dx * dx + dz * dz <= 0.01) {
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
            return true;
        }
        ctx.player().setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        return false;
    }

    private void directFillFloor(BlockPos floorPos) {
        int slot = context.putAcceptableThrowawayHotbar();
        if (slot == -1) {
            baritone.getInputOverrideHandler().clearAllKeys();
            if (context.timer() % 1200 == 0) {
                Helper.HELPER.logDirect("Exit portal has no footing and no acceptableThrowawayItems block in inventory; place one for us or stop the builder.");
            }
            return;
        }
        if (slot < 9) {
            ctx.player().getInventory().selected = slot;
            float reach = (float) ctx.playerController().getBlockReachDistance();
            HighwayContext.PlaceResult result = context.place(floorPos, reach, true, false, InteractionHand.MAIN_HAND);
            if (result == HighwayContext.PlaceResult.CantPlace) {
                context.place(floorPos, reach, false, false, InteractionHand.MAIN_HAND);
            }
        }
        if (context.timer() % 1200 == 0) {
            Helper.HELPER.logDirect("Still placing footing outside the exit portal.");
        }
    }

    private void cancelExitPrep() {
        if (exitPrepStarted) {
            exitPrepStarted = false;
            baritone.getPathingBehavior().cancelEverything();
        }
    }
}
