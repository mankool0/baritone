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
import baritone.api.BaritoneAPI;
import baritone.api.behavior.INetherHighwayBuilderBehavior;
import baritone.api.event.events.PacketEvent;
import baritone.api.event.events.RenderEvent;
import baritone.api.event.events.SprintStateEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.pathing.goals.*;
import baritone.api.schematic.CompositeSchematic;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.WhiteBlackSchematic;
import baritone.api.utils.*;
import baritone.behavior.Behavior;
import baritone.behavior.highway.enums.HighwayState;
import baritone.behavior.highway.enums.LocationType;
import baritone.behavior.highway.enums.ShulkerType;
import baritone.utils.BlockStateInterface;
import baritone.utils.IRenderer;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.core.*;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.awt.*;
import java.util.*;

public final class NetherHighwayBuilderBehavior extends Behavior implements INetherHighwayBuilderBehavior, IRenderer {

    /**
     * When true, the MixinMinecraft hitResult redirect returns null inside startUseItem(),
     * preventing block/container interaction while eating gapples (echest in offhand would
     * otherwise be placed on blocks, or containers would be opened).
     */
    public static boolean suppressHitResult = false;

    private final HighwayContext highwayContext;

    private boolean walkKeyHeld = false;

    /** Dimension the build was started in; a mismatch means a portal teleported us away. */
    private ResourceKey<Level> startDimension = null;
    private boolean wrongDimension = false;
    private final PortalReturn portalReturn;


    public NetherHighwayBuilderBehavior(Baritone baritone) {
        super(baritone);
        this.highwayContext = new HighwayContext(baritone);
        this.portalReturn = new PortalReturn(highwayContext);
    }

    @Override
    public boolean isBuildingHighwayState() {
        return highwayContext.currentState().getState() == HighwayState.BuildingHighway;
    }

    @Override
    public boolean isHighwayActive() {
        return highwayContext.schematic() != null && !highwayContext.paused();
    }

    @Override
    public void build(int startX, int startZ, Vec3i direct, boolean selfSolve, boolean pave) {
        build(startX, startZ, direct, selfSolve, pave, null);
    }

    @Override
    public void build(int startX, int startZ, Vec3i direct, boolean selfSolve, boolean pave, Vec3i endCoords) {
        highwayContext.setEndPos(null); // a stale end would clamp the projections below
        highwayContext.setHighwayDirection(direct);
        highwayContext.setPaving(pave);
        highwayContext.setCachedHealth(ctx.player().getHealth());
        highwayContext.setCachedAbsorption(ctx.player().getAbsorptionAmount());
        startDimension = ctx.world().dimension();
        wrongDimension = false;

        if (!highwayContext.paving()) {
            // Only digging so any pickaxe works
            highwayContext.setPicksToUse(ShulkerType.AnyPickaxe);
            highwayContext.setPicksToHave(settings.highwayPicksToHaveDigging.value);
        } else {
            // If paving then we mine echests and need non-silk picks
            highwayContext.setPicksToUse(ShulkerType.NonSilkPickaxe);
            highwayContext.setPicksToHave(settings.highwayPicksToHavePaving.value);
        }

        int highwayWidth = settings.highwayWidth.value;
        int highwayHeight = settings.highwayHeight.value;
        int supportWidth = settings.highwaySupportWidth.value;
        int supportOffset = settings.highwaySupportOffset.value;
        boolean highwayRail = settings.highwayRail.value;
        boolean diag = Math.abs(highwayContext.highwayDirection().getX()) == Math.abs(highwayContext.highwayDirection().getZ()) && Math.abs(highwayContext.highwayDirection().getZ()) == 1;
        int highwayWidthOffset = -((Math.round(highwayWidth / 2.0f)) + 1);
        int highwayWidthLiqOffset = -(Math.round(highwayWidth / 2.0f)) - 1;
        int highwayWidthLiqOffsetRail = -(Math.round(highwayWidth / 2.0f)) - 2;

        ISchematic obsidSchemBot;
        WhiteBlackSchematic topAir;
        WhiteBlackSchematic noLavaBotSides = new WhiteBlackSchematic(1, 1, 1, Collections.singletonList(Blocks.LAVA.defaultBlockState()), Blocks.NETHERRACK.defaultBlockState(), false, true, true);
        WhiteBlackSchematic supportNetherRack;
        WhiteBlackSchematic sideRailSupport;
        ISchematic sideRail;
        if (pave)
            sideRail = new WhiteBlackSchematic(1, 1, 1, Arrays.asList(Blocks.OBSIDIAN.defaultBlockState(), Blocks.CRYING_OBSIDIAN.defaultBlockState()), Blocks.OBSIDIAN.defaultBlockState(), true, false, false);
        else
            sideRail = new WhiteBlackSchematic(1, 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.OBSIDIAN.defaultBlockState(), Blocks.CRYING_OBSIDIAN.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false);

        WhiteBlackSchematic sideRailAir = new WhiteBlackSchematic(1, 2, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false);
        CompositeSchematic fullSchem = new CompositeSchematic(0, 0,0);

        // +X, -X, +X +Z, -X -Z
        if ((highwayContext.highwayDirection().getZ() == 0 && (highwayContext.highwayDirection().getX() == -1 || highwayContext.highwayDirection().getX() == 1)) ||
                (highwayContext.highwayDirection().getX() == 1 && highwayContext.highwayDirection().getZ() == 1) || (highwayContext.highwayDirection().getX() == -1 && highwayContext.highwayDirection().getZ() == -1)) {
            if (selfSolve) {
                highwayContext.setOriginVector(new Vec3(0, 0, highwayWidthOffset));
                if (highwayRail) {
                    highwayContext.setLiqOriginVector(new Vec3(0, 0, highwayWidthLiqOffsetRail));
                } else {
                    highwayContext.setLiqOriginVector(new Vec3(0, 0, highwayWidthLiqOffset));
                }
                highwayContext.setBackPathOriginVector(new Vec3(0, 0, -1));
                highwayContext.seteChestEmptyShulkOriginVector(new Vec3(0, 0, highwayWidthLiqOffsetRail));
            } else {
                highwayContext.setOriginVector(new Vec3(startX, 0, startZ - highwayWidthOffset));
                if (highwayRail) {
                    highwayContext.setLiqOriginVector(new Vec3(startX, 0, startZ - highwayWidthLiqOffsetRail - 2));
                } else {
                    highwayContext.setLiqOriginVector(new Vec3(startX, 0, startZ - highwayWidthLiqOffset));
                }
                highwayContext.setBackPathOriginVector(new Vec3(startX, 0, startZ - highwayWidthLiqOffsetRail + 1));
                highwayContext.seteChestEmptyShulkOriginVector(new Vec3(startX, 0, startZ - highwayWidthOffset - 1));
            }

            topAir = new WhiteBlackSchematic(1, highwayHeight - 1, highwayWidth, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false);
            if (pave) {
                obsidSchemBot = new WhiteBlackSchematic(1, 1, highwayWidth, Arrays.asList(Blocks.OBSIDIAN.defaultBlockState(), Blocks.CRYING_OBSIDIAN.defaultBlockState()), Blocks.OBSIDIAN.defaultBlockState(), true, false, false);
                highwayContext.setLiqOriginVector(highwayContext.liqOriginVector().add(0, 0, 1));
                if (highwayRail) {
                    highwayContext.setLiqCheckSchem(new WhiteBlackSchematic(1, highwayHeight - 1, highwayWidth + 2, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                } else {
                    highwayContext.setLiqCheckSchem(new WhiteBlackSchematic(1, highwayHeight - 1, highwayWidth, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                }

                if (diag && highwayRail) {
                    sideRailSupport = new WhiteBlackSchematic(1, 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.LAVA.defaultBlockState(), Blocks.FIRE.defaultBlockState()), Blocks.NETHERRACK.defaultBlockState(), false, true, true);
                    sideRailSupport.setThrowawayFallback(Blocks.OBSIDIAN.defaultBlockState());
                    fullSchem.put(sideRailSupport, 0, 1, 0);
                    fullSchem.put(sideRailSupport, 0, 1, highwayWidth + 1);
                }
            } else {
                obsidSchemBot = new WhiteBlackSchematic(1, 1, highwayWidth, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.OBSIDIAN.defaultBlockState(), Blocks.CRYING_OBSIDIAN.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false); // Allow only air and obsidian
                supportNetherRack = new WhiteBlackSchematic(1, 1, supportWidth, highwayContext.blackListBlocks(), Blocks.NETHERRACK.defaultBlockState(), false, true, true); // Allow everything other than air and lava
                if (highwayRail) {
                    highwayContext.setLiqCheckSchem(new WhiteBlackSchematic(1, highwayHeight + 1, highwayWidth + 4, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                } else {
                    highwayContext.setLiqCheckSchem(new WhiteBlackSchematic(1, highwayHeight + 1, highwayWidth + 2, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                }
                if (supportWidth + 2 <= highwayWidth) {
                    fullSchem.put(noLavaBotSides, 0, 0, 1);
                    fullSchem.put(noLavaBotSides, 0, 0, highwayWidth);
                }
                fullSchem.put(supportNetherRack, 0, 0, supportOffset);
            }

            fullSchem.put(obsidSchemBot, 0, 1, 1);
            if (highwayRail) {
                fullSchem.put(sideRail, 0, 2, 0);
                fullSchem.put(sideRail, 0, 2, highwayWidth + 1);
                fullSchem.put(sideRailAir, 0, 3, 0);
                fullSchem.put(sideRailAir, 0, 3, highwayWidth + 1);
            }
            fullSchem.put(topAir, 0, 2, 1);
        }
        // +Z, -Z, +X -Z, -X +Z
        else if ((highwayContext.highwayDirection().getX() == 0 && (highwayContext.highwayDirection().getZ() == -1 || highwayContext.highwayDirection().getZ() == 1)) ||
                (highwayContext.highwayDirection().getX() == 1 && highwayContext.highwayDirection().getZ() == -1) || (highwayContext.highwayDirection().getX() == -1 && highwayContext.highwayDirection().getZ() == 1)) {
            if (selfSolve) {
                highwayContext.setOriginVector(new Vec3(highwayWidthOffset, 0, 0));
                if (highwayRail) {
                    highwayContext.setLiqOriginVector(new Vec3(highwayWidthLiqOffsetRail, 0, 0));
                } else {
                    highwayContext.setLiqOriginVector(new Vec3(highwayWidthLiqOffset, 0, 0));
                }
                highwayContext.setBackPathOriginVector(new Vec3(-1, 0, 0));
                highwayContext.seteChestEmptyShulkOriginVector(new Vec3(highwayWidthLiqOffsetRail, 0, 0));
            } else {
                highwayContext.setOriginVector(new Vec3(startX - highwayWidthOffset, 0, startZ));
                if (highwayRail) {
                    highwayContext.setLiqOriginVector(new Vec3(startX - highwayWidthLiqOffsetRail - 2, 0, startZ));
                } else {
                    highwayContext.setLiqOriginVector(new Vec3(startX - highwayWidthLiqOffset, 0, startZ));
                }
                highwayContext.setBackPathOriginVector(new Vec3(startX - highwayWidthLiqOffsetRail + 1, 0, startZ));
                highwayContext.seteChestEmptyShulkOriginVector(new Vec3(startX - highwayWidthOffset - 1, 0, startZ));
            }

            topAir = new WhiteBlackSchematic(highwayWidth, highwayHeight - 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false);
            if (pave) {
                obsidSchemBot = new WhiteBlackSchematic(highwayWidth, 1, 1, Arrays.asList(Blocks.OBSIDIAN.defaultBlockState(), Blocks.CRYING_OBSIDIAN.defaultBlockState()), Blocks.OBSIDIAN.defaultBlockState(), true, false, false);
                highwayContext.setLiqOriginVector(highwayContext.liqOriginVector().add(1, 0, 0));
                if (highwayRail) {
                    highwayContext.setLiqCheckSchem(new WhiteBlackSchematic(highwayWidth + 2, highwayHeight - 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                } else {
                    highwayContext.setLiqCheckSchem(new WhiteBlackSchematic(highwayWidth, highwayHeight - 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                }

                if (diag && highwayRail) {
                    sideRailSupport = new WhiteBlackSchematic(1, 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.LAVA.defaultBlockState(), Blocks.FIRE.defaultBlockState()), Blocks.NETHERRACK.defaultBlockState(), false, true, true);
                    sideRailSupport.setThrowawayFallback(Blocks.OBSIDIAN.defaultBlockState());
                    fullSchem.put(sideRailSupport, 0, 1, 0);
                    fullSchem.put(sideRailSupport, highwayWidth + 1, 1, 0);
                }
            } else {
                obsidSchemBot = new WhiteBlackSchematic(highwayWidth, 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.OBSIDIAN.defaultBlockState(), Blocks.CRYING_OBSIDIAN.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false); // Allow only air and obsidian
                supportNetherRack = new WhiteBlackSchematic(supportWidth, 1, 1, highwayContext.blackListBlocks(), Blocks.NETHERRACK.defaultBlockState(), false, true, true); // Allow everything other than air and lava
                if (highwayRail) {
                    highwayContext.setLiqCheckSchem(new WhiteBlackSchematic(highwayWidth + 4, highwayHeight + 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                } else {
                    highwayContext.setLiqCheckSchem(new WhiteBlackSchematic(highwayWidth + 2, highwayHeight + 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                }
                if (supportWidth + 2 <= highwayWidth) {
                    fullSchem.put(noLavaBotSides, 1, 0, 0);
                    fullSchem.put(noLavaBotSides, highwayWidth, 0, 0);
                }

                fullSchem.put(supportNetherRack, supportOffset, 0, 0);
            }

            fullSchem.put(obsidSchemBot, 1, 1, 0);
            if (highwayRail) {
                fullSchem.put(sideRail, 0, 2, 0);
                fullSchem.put(sideRail, highwayWidth + 1, 2, 0);
                fullSchem.put(sideRailAir, 0, 3, 0);
                fullSchem.put(sideRailAir, highwayWidth + 1, 3, 0);
            }

            fullSchem.put(topAir, 1, 2, 0);
        }

        highwayContext.setSchematic(fullSchem);

        Vec3 origin = new Vec3(highwayContext.originVector().x, highwayContext.originVector().y, highwayContext.originVector().z);
        Vec3 direction = new Vec3(highwayContext.highwayDirection().getX(), highwayContext.highwayDirection().getY(), highwayContext.highwayDirection().getZ());
        Vec3 curPos = new Vec3(ctx.playerFeet().getX(), ctx.playerFeet().getY(), ctx.playerFeet().getZ());
        highwayContext.setOriginBuild(highwayContext.getClosestPoint(origin, direction, curPos, LocationType.HighwayBuild));

        highwayContext.setFirstStartingPos(new BetterBlockPos(highwayContext.originBuild()));

        if (endCoords != null) {
            BetterBlockPos end = highwayContext.getClosestPoint(origin, direction, new Vec3(endCoords.getX(), 0, endCoords.getZ()), LocationType.HighwayBuild);
            int endSteps = highwayContext.stepsAlongHighway(highwayContext.firstStartingPos(), end);
            if (endSteps <= 0) {
                Helper.HELPER.logDirect("End " + endCoords.getX() + ", " + endCoords.getZ() + " is not ahead of the start " + highwayContext.firstStartingPos().toString() + ", not starting");
                stop();
                return;
            }
            highwayContext.setEndPos(end);
            Helper.HELPER.logDirect("Stopping at " + end.toString() + " (" + endSteps + " blocks ahead)");
        }

        Helper.HELPER.logDirect("Building from " + highwayContext.originBuild().toString());
        settings.buildRepeat.value = new Vec3i(highwayContext.highwayDirection().getX(), 0, highwayContext.highwayDirection().getZ());
        baritone.getPathingBehavior().cancelEverything();


        highwayContext.setEnderChestHasPickShulks(true);
        highwayContext.setEnderChestHasEnderShulks(true);
        highwayContext.setRefillingEnderChests(false);
        highwayContext.setStashingEnderShulker(false);
        highwayContext.setEnderChestAccessLoc(null);
        highwayContext.setRepeatCheck(false);
        highwayContext.setStartShulkerCount(highwayContext.getShulkerCountInventory(ShulkerType.Any));
        highwayContext.setPaused(false);
        highwayContext.resetThroughWallDetection();
        highwayContext.transitionTo(HighwayState.Nothing);
    }

    @Override
    public void stop() {
        Helper.HELPER.logDirect("STOPPING NETHERHIGHWAYBUILDER");
        Helper.HELPER.logDirect("Was at state " + highwayContext.currentState().getState() + " before termination");

        highwayContext.setPaused(true);
        highwayContext.setRepeatCheck(false);
        wrongDimension = false;
        highwayContext.resetRecovery(); // restore any settings (e.g. allowSwimThroughLava) overridden during recovery
        highwayContext.transitionTo(HighwayState.Nothing);
        setWalkForward(false);
        baritone.getPathingBehavior().cancelEverything();
        highwayContext.setFirstStartingPos(null);
        highwayContext.setOriginBuild(null);
        highwayContext.setEndPos(null);
        settings.buildRepeatCount.value = -1; // Nothing clamps it while an end is set
    }

    @Override
    public void printStatus() {
        Helper.HELPER.logDirect("State: " + highwayContext.currentState().getState());
        if (highwayContext.endPos() != null) {
            Helper.HELPER.logDirect("End: " + highwayContext.endPos().toString());
        }
        Helper.HELPER.logDirect("Paused: " + highwayContext.paused());
        Helper.HELPER.logDirect("Timer: " + highwayContext.timer());
        Helper.HELPER.logDirect("startShulkerCount: " + highwayContext.startShulkerCount());
    }

    @Override
    public void onTick(TickEvent event) {
        if (highwayContext.paused() || highwayContext.schematic() == null || ctx.player() == null || ctx.player().getInventory().isEmpty() || event.getType() == TickEvent.Type.OUT) {
            setWalkForward(false); // never leave the walk key latched while the state machine isn't driving
            return;
        }

        highwayContext.incrementTimers();

        if (startDimension != null && !highwayContext.isInQueue()) {
            if (ctx.world().dimension() != startDimension) {
                if (!wrongDimension) {
                    wrongDimension = true;
                    portalReturn.reset();
                    Helper.HELPER.logDirect("Now in " + ctx.world().dimension().location() + " instead of " + startDimension.location()
                            + " (portal teleport?). Stepping out of the exit portal and back in to get sent home.");
                    baritone.getInputOverrideHandler().clearAllKeys();
                    baritone.getPathingBehavior().cancelEverything();
                }
                setWalkForward(false);
                portalReturn.tick(startDimension);
                return;
            }
            if (wrongDimension) {
                wrongDimension = false;
                Helper.HELPER.logDirect("Back in " + startDimension.location() + ", restarting the builder.");
                baritone.getInputOverrideHandler().clearAllKeys();
                baritone.getPathingBehavior().cancelEverything();
                highwayContext.resetTimer();
                highwayContext.resetStuckTimer();
                highwayContext.resetWalkBackTimer();
                highwayContext.resetCheckBackTimer();
                highwayContext.transitionTo(HighwayState.Nothing);
            }
        }

        HighwayState curState = highwayContext.currentState().getState();
        if (curState != HighwayState.PortalEscape && curState != HighwayState.InQueue && highwayContext.isPlayerInPortal()) {
            Helper.HELPER.logDirect("Standing inside a nether portal, stepping out.");
            baritone.getInputOverrideHandler().clearAllKeys();
            baritone.getPathingBehavior().cancelEverything();
            highwayContext.transitionTo(HighwayState.PortalEscape);
        }
        boolean escaping = highwayContext.currentState().getState() == HighwayState.PortalEscape;

        // Offhand rescue/autoTotem/clearCursorItem pause the state machine while they work, but the
        // stuck/health watchdogs must run regardless: a cursor stack that can never be placed (full
        // inventory) used to starve them and freeze the state machine forever with its keys latched.
        // Rescue runs before autoTotem so stranded chests merge onto a loose stack instead of being
        // swapped into the totem's slot.
        boolean pauseStateMachine = !escaping && (highwayContext.rescueOffhandEnderChests() || highwayContext.autoTotem() || highwayContext.clearCursorItem());
        if (highwayContext.stuckCheck() || highwayContext.healthCheck() || pauseStateMachine) {
            setWalkForward(false);
            return;
        }

        // Handle distance to keep from end of highway
        boolean walk = false;
        if (settings.highwayEndDistance.value != -1) {
            walk = highwayContext.getHighwayLengthFront() >= settings.highwayEndDistance.value
                    && highwayContext.currentState().getState() == HighwayState.BuildingHighway
                    && highwayContext.canWalkOnFloorAhead()
                    // never creep into a lit portal waiting for its frame to be mined
                    && highwayContext.noPortalAhead()
                    // a pathing pause can't lift this real key, so release it ourselves while an
                    // inventory move waits for a tick without movement input
                    && !baritone.getInventoryPauserProcess().calmPausePending();
            if (walk) {
                if (!highwayContext.paving() && baritone.getLookBehavior().hasInteractTargetThisTick()) {
                    // in a tunnel, walking while something aims at a block veers toward the aim;
                    // stand still for the interaction instead
                    walk = false;
                } else if (!baritone.getLookBehavior().hasTargetThisTick() && !baritone.getPathingBehavior().isPathing()) {
                    // nothing owns the look this tick; without this the held key walks wherever
                    // the last rotation happened to leave the camera
                    highwayContext.faceHighwayDirection();
                }
            }
        }
        setWalkForward(walk);

        highwayContext.handle();
    }

    private void setWalkForward(boolean walk) {
        if (walk) {
            walkKeyHeld = true;
            ctx.minecraft().options.keyUp.setDown(true);
        } else if (walkKeyHeld) {
            walkKeyHeld = false;
            ctx.minecraft().options.keyUp.setDown(false);
        }
    }

    @Override
    public void onPlayerSprintState(SprintStateEvent event) {
        if (walkKeyHeld && settings.highwaySprint.value && !baritone.getPathingBehavior().isPathing()) {
            event.setState(true);
        }
    }

    @Override
    public void onRenderPass(RenderEvent event) {
        Entity player = ctx.minecraft().getCameraEntity();
        if (player == null) {
            return;
        }

        if (settings.highwayRenderLiquidScanArea.value) {
            highwayContext.renderLockLiquid().lock();
            AABB liquidArea = highwayContext.renderAreaLiquid();
            highwayContext.renderLockLiquid().unlock();

            if (liquidArea != null) {
                BufferBuilder bufferBuilder = IRenderer.startLines(Color.BLUE, 2, settings.renderSelectionBoxesIgnoreDepth.value);
                // Inflate a bit more than the building-area boxes: when paving, the scan area hugs
                // the building area, so equal-size boxes would z-fight
                IRenderer.emitAABB(bufferBuilder, event.getModelViewStack(), liquidArea, .05D);
                IRenderer.endLines(bufferBuilder, settings.renderSelectionBoxesIgnoreDepth.value);
            }
        }

        if (settings.highwayRenderBuildingArea.value) {
            highwayContext.renderLockBuilding().lock();
            BlockStateInterface bsi = new BlockStateInterface(BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext());
            highwayContext.renderBlocksBuilding().forEach((pos, color) -> {
                BufferBuilder bufferBuilder = IRenderer.startLines(color, 2, settings.renderSelectionBoxesIgnoreDepth.value);

                BlockState state = bsi.get0(pos);
                VoxelShape shape;
                AABB toDraw;

                if (state.getBlock() instanceof AirBlock) {
                    shape = Blocks.DIRT.defaultBlockState().getShape(player.level(), pos);
                } else {
                    shape = state.getShape(player.level(), pos);
                }
                if (!shape.isEmpty()) {
                    AABB bounds = shape.bounds();
                    toDraw = new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + bounds.getXsize(), pos.getY() + bounds.getYsize(), pos.getZ() + bounds.getZsize());
                    IRenderer.emitAABB(bufferBuilder, event.getModelViewStack(), toDraw, .002D);

                    IRenderer.endLines(bufferBuilder, settings.renderSelectionBoxesIgnoreDepth.value);
                }
            });
            highwayContext.renderLockBuilding().unlock();
        }
    }

    @Override
    public void onReceivePacket(PacketEvent event) {
        if (event.getPacket() instanceof ClientboundSystemChatPacket packet && event.getState() == EventState.POST) {
            String message = packet.content().getString();
            
            if (message.contains(settings.highwayQueueEnterMessage.value)) {
                Helper.HELPER.logDirect("Queue detected: " + message);
                highwayContext.enterQueue();
            } else if (highwayContext.isInQueue() && message.contains(settings.highwayQueueExitMessage.value)) {
                Helper.HELPER.logDirect("Queue exit detected: " + message);
                highwayContext.exitQueue();
            }
        }
    }

}