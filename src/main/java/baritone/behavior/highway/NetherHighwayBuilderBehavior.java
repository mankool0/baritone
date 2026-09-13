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
import baritone.api.Settings;
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
import baritone.api.utils.input.Input;
import baritone.behavior.Behavior;
import baritone.behavior.highway.enums.HighwayState;
import baritone.behavior.highway.enums.LocationType;
import baritone.behavior.highway.enums.ShulkerType;
import baritone.utils.BlockStateInterface;
import baritone.utils.IRenderer;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.core.*;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
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
        build(startX, startZ, direct, selfSolve, pave, null, null);
    }

    @Override
    public void build(int startX, int startZ, Vec3i direct, boolean selfSolve, boolean pave, Vec3i endCoords) {
        build(startX, startZ, direct, selfSolve, pave, endCoords, null);
    }

    @Override
    public void build(int startX, int startZ, Vec3i direct, boolean selfSolve, boolean pave, Vec3i endCoords, Vec3i startCoords) {
        teardown(); // nhwbuild is a restart, not an overlay: nothing the last build held survives it
        highwayContext.resetStateDwell(); // per build, so a stop leaves the last run's timings readable
        highwayContext.setHighwayDirection(direct);
        highwayContext.setPaving(pave);
        highwayContext.setCachedHealth(ctx.player().getHealth());
        highwayContext.setCachedAbsorption(ctx.player().getAbsorptionAmount());
        startDimension = ctx.world().dimension();

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
        boolean highwayRail = settings.highwayRail.value;
        boolean railLow = highwayRail && settings.highwayRailLow.value;
        boolean railHigh = highwayRail && settings.highwayRailHigh.value;
        int highwayWidthOffset = -((Math.round(highwayWidth / 2.0f)) + 1);
        int highwayWidthLiqOffset = -(Math.round(highwayWidth / 2.0f)) - 1;
        int highwayWidthLiqOffsetRail = -(Math.round(highwayWidth / 2.0f)) - 2;
        int highwayWidthCenterOffset = 1 + (highwayWidth - 1) / 2;

        // +X, -X, +X +Z, -X -Z
        if (isGroupA(highwayContext.highwayDirection())) {
            if (selfSolve) {
                highwayContext.setOriginVector(canonicalOriginVector(highwayContext.highwayDirection(), settings));
                if (highwayRail) {
                    highwayContext.setLiqOriginVector(new Vec3(0, 0, highwayWidthLiqOffsetRail));
                } else {
                    highwayContext.setLiqOriginVector(new Vec3(0, 0, highwayWidthLiqOffset));
                }
                highwayContext.setBackPathOriginVector(new Vec3(0, 0, highwayWidthOffset + highwayWidthCenterOffset));
                highwayContext.seteChestEmptyShulkOriginVector(new Vec3(0, 0, highwayWidthLiqOffsetRail));
            } else {
                highwayContext.setOriginVector(new Vec3(startX, 0, startZ - highwayWidthOffset));
                if (highwayRail) {
                    highwayContext.setLiqOriginVector(new Vec3(startX, 0, startZ - highwayWidthLiqOffsetRail - 2));
                } else {
                    highwayContext.setLiqOriginVector(new Vec3(startX, 0, startZ - highwayWidthLiqOffset));
                }
                highwayContext.setBackPathOriginVector(new Vec3(startX, 0, startZ - highwayWidthOffset + highwayWidthCenterOffset));
                highwayContext.seteChestEmptyShulkOriginVector(new Vec3(startX, 0, startZ - highwayWidthOffset - 1));
            }

            if (pave) {
                highwayContext.setLiqOriginVector(highwayContext.liqOriginVector().add(0, 0, 1));
                if (highwayRail) {
                    highwayContext.setLiqCheckSchem(new WhiteBlackSchematic(1, highwayHeight - 1, highwayWidth + 2, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                } else {
                    highwayContext.setLiqCheckSchem(new WhiteBlackSchematic(1, highwayHeight - 1, highwayWidth, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                }
            } else {
                if (highwayRail) {
                    highwayContext.setLiqCheckSchem(new WhiteBlackSchematic(1, highwayHeight + 1, highwayWidth + 4, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                } else {
                    highwayContext.setLiqCheckSchem(new WhiteBlackSchematic(1, highwayHeight + 1, highwayWidth + 2, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                }
            }
        }
        // +Z, -Z, +X -Z, -X +Z
        else if (isGroupB(highwayContext.highwayDirection())) {
            if (selfSolve) {
                highwayContext.setOriginVector(canonicalOriginVector(highwayContext.highwayDirection(), settings));
                if (highwayRail) {
                    highwayContext.setLiqOriginVector(new Vec3(highwayWidthLiqOffsetRail, 0, 0));
                } else {
                    highwayContext.setLiqOriginVector(new Vec3(highwayWidthLiqOffset, 0, 0));
                }
                highwayContext.setBackPathOriginVector(new Vec3(highwayWidthOffset + highwayWidthCenterOffset, 0, 0));
                highwayContext.seteChestEmptyShulkOriginVector(new Vec3(highwayWidthLiqOffsetRail, 0, 0));
            } else {
                highwayContext.setOriginVector(new Vec3(startX - highwayWidthOffset, 0, startZ));
                if (highwayRail) {
                    highwayContext.setLiqOriginVector(new Vec3(startX - highwayWidthLiqOffsetRail - 2, 0, startZ));
                } else {
                    highwayContext.setLiqOriginVector(new Vec3(startX - highwayWidthLiqOffset, 0, startZ));
                }
                highwayContext.setBackPathOriginVector(new Vec3(startX - highwayWidthOffset + highwayWidthCenterOffset, 0, startZ));
                highwayContext.seteChestEmptyShulkOriginVector(new Vec3(startX - highwayWidthOffset - 1, 0, startZ));
            }

            if (pave) {
                highwayContext.setLiqOriginVector(highwayContext.liqOriginVector().add(1, 0, 0));
                if (highwayRail) {
                    highwayContext.setLiqCheckSchem(new WhiteBlackSchematic(highwayWidth + 2, highwayHeight - 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                } else {
                    highwayContext.setLiqCheckSchem(new WhiteBlackSchematic(highwayWidth, highwayHeight - 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                }
            } else {
                if (highwayRail) {
                    highwayContext.setLiqCheckSchem(new WhiteBlackSchematic(highwayWidth + 4, highwayHeight + 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                } else {
                    highwayContext.setLiqCheckSchem(new WhiteBlackSchematic(highwayWidth + 2, highwayHeight + 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                }
            }
        }

        highwayContext.setSchematic(composeHighwaySchematic(highwayContext.highwayDirection(), pave, settings));

        Vec3 origin = new Vec3(highwayContext.originVector().x, highwayContext.originVector().y, highwayContext.originVector().z);
        Vec3 direction = new Vec3(highwayContext.highwayDirection().getX(), highwayContext.highwayDirection().getY(), highwayContext.highwayDirection().getZ());
        // A named start replaces the feet projection wholesale: firstStartingPos then clamps every
        // later projection to it, so a start ahead of the bot needs no special handling.
        Vec3 startFrom = (startCoords != null)
                ? new Vec3(startCoords.getX(), 0, startCoords.getZ())
                : new Vec3(ctx.playerFeet().getX(), ctx.playerFeet().getY(), ctx.playerFeet().getZ());
        highwayContext.setOriginBuild(highwayContext.getClosestPoint(origin, direction, startFrom, LocationType.HighwayBuild));

        highwayContext.setFirstStartingPos(new BetterBlockPos(highwayContext.originBuild()));

        if (endCoords != null) {
            BetterBlockPos end = highwayContext.getClosestPoint(origin, direction, new Vec3(endCoords.getX(), 0, endCoords.getZ()), LocationType.HighwayBuild);
            int endSteps = highwayContext.stepsAlongHighway(highwayContext.firstStartingPos(), end);
            if (endSteps < 0) {
                Helper.HELPER.logDirect("End " + endCoords.getX() + ", " + endCoords.getZ() + " is behind the start " + highwayContext.firstStartingPos().toString() + ", not starting");
                stop();
                return;
            }
            highwayContext.setEndPos(end);
            Helper.HELPER.logDirect("Stopping at " + end.toString() + " (" + endSteps + " blocks ahead)");
        }

        Helper.HELPER.logDirect("Profile: width=" + highwayWidth + " height=" + highwayHeight
                + " y=" + settings.highwayLowestY.value + "/" + settings.highwayMainY.value
                + " rails=" + (railLow ? "low" : "") + (railHigh ? "high" : "")
                + (!railLow && !railHigh ? "none" : ""));
        Helper.HELPER.logDirect("Building from " + highwayContext.originBuild().toString());
        settings.buildRepeat.value = new Vec3i(highwayContext.highwayDirection().getX(), 0, highwayContext.highwayDirection().getZ());

        highwayContext.setEnderChestHasPickShulks(true);
        highwayContext.setEnderChestHasEnderShulks(true);
        highwayContext.setEnderChestHasGappleShulks(true);
        highwayContext.setEnderChestHasTotemShulks(true);
        highwayContext.setRefillingEnderChests(false);
        highwayContext.setRefillingGapples(false);
        highwayContext.setRefillingTotems(false);
        highwayContext.setStashingShulker(false);
        highwayContext.setEnderChestAccessLoc(null);
        highwayContext.resetThiefHunt();
        highwayContext.clearLostShulkerRelogAttempt();
        highwayContext.setStartShulkerCount(highwayContext.getShulkerCountInventory(ShulkerType.Any));
        highwayContext.setPaused(false);
        highwayContext.resetThroughWallDetection();
        highwayContext.transitionTo(HighwayState.Nothing);
    }

    private void teardown() {
        highwayContext.clearThresholdConfirm();
        wrongDimension = false;
        highwayContext.resetRecovery(); // held keys, suppressHitResult, and settings (e.g. allowSwimThroughLava) overridden during recovery
        highwayContext.clearInvalidBlockFix();
        highwayContext.stopTravelTowardsEnd();
        highwayContext.transitionTo(HighwayState.Nothing); // onExit: whatever the state we interrupt holds
        setWalkForward(false);
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getPathingBehavior().cancelEverything();
        highwayContext.setFirstStartingPos(null); // a stale start or end clamps every projection the next build makes
        highwayContext.setOriginBuild(null);
        highwayContext.setEndPos(null);
        settings.buildRepeatCount.value = -1; // Nothing clamps it while an end is set
    }

    /** +X, -X, +X+Z, -X-Z: the schematic is 1 wide in X and extends in Z; the cross-axis is Z. */
    public static boolean isGroupA(Vec3i direction) {
        return (direction.getZ() == 0 && (direction.getX() == -1 || direction.getX() == 1)) ||
                (direction.getX() == 1 && direction.getZ() == 1) || (direction.getX() == -1 && direction.getZ() == -1);
    }

    /** +Z, -Z, +X-Z, -X+Z: the schematic extends in X and is 1 wide in Z; the cross-axis is X. */
    public static boolean isGroupB(Vec3i direction) {
        return (direction.getX() == 0 && (direction.getZ() == -1 || direction.getZ() == 1)) ||
                (direction.getX() == 1 && direction.getZ() == -1) || (direction.getX() == -1 && direction.getZ() == 1);
    }

    /** The schematic's origin for a selfSolve build. */
    public static Vec3 canonicalOriginVector(Vec3i direction, Settings settings) {
        int highwayWidthOffset = -((Math.round(settings.highwayWidth.value / 2.0f)) + 1);
        if (isGroupA(direction)) {
            return new Vec3(0, 0, highwayWidthOffset);
        }
        if (isGroupB(direction)) {
            return new Vec3(highwayWidthOffset, 0, 0);
        }
        throw new IllegalArgumentException("Not a highway direction: " + direction);
    }

    /**
     * Compose the one-slice highway schematic for the given direction and mode. Pure - reads only
     * the highway profile settings - so HighwayConformanceDump emits exactly what the
     * builder targets.
     */
    public static CompositeSchematic composeHighwaySchematic(Vec3i direction, boolean pave, Settings settings) {
        int highwayWidth = settings.highwayWidth.value;
        int highwayHeight = settings.highwayHeight.value;
        int supportWidth = settings.highwaySupportWidth.value;
        int supportOffset = settings.highwaySupportOffset.value;
        boolean rails = settings.highwayRail.value;
        boolean railLow = rails && settings.highwayRailLow.value;
        boolean railHigh = rails && settings.highwayRailHigh.value;
        boolean diag = Math.abs(direction.getX()) == Math.abs(direction.getZ()) && Math.abs(direction.getZ()) == 1;

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
        // The rail column asserted EMPTY: sideRail's cell plus sideRailAir's two above it, which is
        // exactly the set of cells the rail component owns. Air-only whitelist, so an obsidian rail
        // mismatches and BuilderProcess mines it.
        WhiteBlackSchematic railClear = new WhiteBlackSchematic(1, 3, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false);
        CompositeSchematic fullSchem = new CompositeSchematic(0, 0, 0);

        // +X, -X, +X +Z, -X -Z
        if (isGroupA(direction)) {
            topAir = new WhiteBlackSchematic(1, highwayHeight - 1, highwayWidth, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false);
            if (pave) {
                obsidSchemBot = new WhiteBlackSchematic(1, 1, highwayWidth, Arrays.asList(Blocks.OBSIDIAN.defaultBlockState(), Blocks.CRYING_OBSIDIAN.defaultBlockState()), Blocks.OBSIDIAN.defaultBlockState(), true, false, false);

                if (diag && (railLow || railHigh)) {
                    sideRailSupport = new WhiteBlackSchematic(1, 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.LAVA.defaultBlockState(), Blocks.FIRE.defaultBlockState()), Blocks.NETHERRACK.defaultBlockState(), false, true, true);
                    sideRailSupport.setThrowawayFallback(Blocks.OBSIDIAN.defaultBlockState());
                    if (railLow) {
                        fullSchem.put(sideRailSupport, 0, 1, 0);
                    }
                    if (railHigh) {
                        fullSchem.put(sideRailSupport, 0, 1, highwayWidth + 1);
                    }
                }
            } else {
                obsidSchemBot = new WhiteBlackSchematic(1, 1, highwayWidth, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.OBSIDIAN.defaultBlockState(), Blocks.CRYING_OBSIDIAN.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false); // Allow only air and obsidian
                supportNetherRack = new WhiteBlackSchematic(1, 1, supportWidth, HighwayContext.blackListBlocks, Blocks.NETHERRACK.defaultBlockState(), false, true, true); // Allow everything other than air and lava
                if (supportWidth + 2 <= highwayWidth) {
                    fullSchem.put(noLavaBotSides, 0, 0, 1);
                    fullSchem.put(noLavaBotSides, 0, 0, highwayWidth);
                }
                fullSchem.put(supportNetherRack, 0, 0, supportOffset);
            }

            fullSchem.put(obsidSchemBot, 0, 1, 1);
            if (railLow) {
                fullSchem.put(sideRail, 0, 2, 0);
                fullSchem.put(sideRailAir, 0, 3, 0);
            } else if (rails) {
                fullSchem.put(railClear, 0, 2, 0);
            }
            if (railHigh) {
                fullSchem.put(sideRail, 0, 2, highwayWidth + 1);
                fullSchem.put(sideRailAir, 0, 3, highwayWidth + 1);
            } else if (rails) {
                fullSchem.put(railClear, 0, 2, highwayWidth + 1);
            }
            fullSchem.put(topAir, 0, 2, 1);
        }
        // +Z, -Z, +X -Z, -X +Z
        else if (isGroupB(direction)) {
            topAir = new WhiteBlackSchematic(highwayWidth, highwayHeight - 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false);
            if (pave) {
                obsidSchemBot = new WhiteBlackSchematic(highwayWidth, 1, 1, Arrays.asList(Blocks.OBSIDIAN.defaultBlockState(), Blocks.CRYING_OBSIDIAN.defaultBlockState()), Blocks.OBSIDIAN.defaultBlockState(), true, false, false);

                if (diag && (railLow || railHigh)) {
                    sideRailSupport = new WhiteBlackSchematic(1, 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.LAVA.defaultBlockState(), Blocks.FIRE.defaultBlockState()), Blocks.NETHERRACK.defaultBlockState(), false, true, true);
                    sideRailSupport.setThrowawayFallback(Blocks.OBSIDIAN.defaultBlockState());
                    if (railLow) {
                        fullSchem.put(sideRailSupport, 0, 1, 0);
                    }
                    if (railHigh) {
                        fullSchem.put(sideRailSupport, highwayWidth + 1, 1, 0);
                    }
                }
            } else {
                obsidSchemBot = new WhiteBlackSchematic(highwayWidth, 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.OBSIDIAN.defaultBlockState(), Blocks.CRYING_OBSIDIAN.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false); // Allow only air and obsidian
                supportNetherRack = new WhiteBlackSchematic(supportWidth, 1, 1, HighwayContext.blackListBlocks, Blocks.NETHERRACK.defaultBlockState(), false, true, true); // Allow everything other than air and lava
                if (supportWidth + 2 <= highwayWidth) {
                    fullSchem.put(noLavaBotSides, 1, 0, 0);
                    fullSchem.put(noLavaBotSides, highwayWidth, 0, 0);
                }

                fullSchem.put(supportNetherRack, supportOffset, 0, 0);
            }

            fullSchem.put(obsidSchemBot, 1, 1, 0);
            if (railLow) {
                fullSchem.put(sideRail, 0, 2, 0);
                fullSchem.put(sideRailAir, 0, 3, 0);
            } else if (rails) {
                fullSchem.put(railClear, 0, 2, 0);
            }
            if (railHigh) {
                fullSchem.put(sideRail, highwayWidth + 1, 2, 0);
                fullSchem.put(sideRailAir, highwayWidth + 1, 3, 0);
            } else if (rails) {
                fullSchem.put(railClear, highwayWidth + 1, 2, 0);
            }

            fullSchem.put(topAir, 1, 2, 0);
        } else {
            throw new IllegalArgumentException("Not a highway direction: " + direction);
        }

        return fullSchem;
    }

    @Override
    public void stop() {
        Helper.HELPER.logDirect("STOPPING NETHERHIGHWAYBUILDER");
        Helper.HELPER.logDirect("Was at state " + highwayContext.currentState().getState() + " before termination");

        teardown();
        highwayContext.setPaused(true);
    }

    @Override
    public void printStatus() {
        Helper.HELPER.logDirect("State: " + highwayContext.currentState().getState());
        if (highwayContext.endPos() != null) {
            Helper.HELPER.logDirect("End: " + highwayContext.endPos().toString());
        }
        Helper.HELPER.logDirect("Paused: " + highwayContext.paused()); // true/false alone: hive.py reads this line
        if (highwayContext.pauseReason() != null) {
            Helper.HELPER.logDirect("Pause reason: " + highwayContext.pauseReason());
        }
        Helper.HELPER.logDirect("Timer: " + highwayContext.timer());
        Helper.HELPER.logDirect("startShulkerCount: " + highwayContext.startShulkerCount());
        Helper.HELPER.logDirect(highwayContext.frontDistanceDiagnostic());
        Helper.HELPER.logDirect(highwayContext.walkGateDiagnostic());
        highwayContext.stateDwellDiagnostic(12).forEach(Helper.HELPER::logDirect);
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
        if (settings.highwayEndDistance.value != -1 && highwayContext.currentState().getState() == HighwayState.BuildingHighway) {
            boolean pathing = baritone.getPathingBehavior().isPathing();
            BlockPos builderAim = baritone.getBuilderProcess().stationaryAimTarget();
            if (!pathing && builderAim == null && baritone.getInputOverrideHandler().isInputForcedDown(Input.SNEAK)) {
                // forced sneak left over from a finished stationary placement: with no path
                // running nothing ever clears it, and a latched movement override keeps the
                // override movement input installed, which ignores the real walk key entirely
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
            }
            // both sides of these comparisons are blocks along the highway, including creepMaxOvershoot
            int frontDistance = highwayContext.getHighwayFrontDistance();
            // a dispatched repair needs to path to a block behind us; creeping forward (and
            // steering back to the lane center) would drag the bot off that path every tick
            // Same conditions in the same order as before, evaluated one at a time so nhwstatus can
            // say which one is actually holding the key down
            String walkBlocker = null;
            if (highwayContext.invalidBlockFixActive()) {
                walkBlocker = "invalid-block repair";
            } else if (frontDistance < settings.highwayEndDistance.value) {
                walkBlocker = "front too close";
            } else if (frontDistance >= settings.highwayEndDistance.value + highwayContext.creepMaxOvershoot()) {
                // only creep while the build front is right in front of us
                walkBlocker = "front too far";
            } else if (!highwayContext.canWalkOnFloorAhead()) {
                walkBlocker = "no floor ahead";
            } else if (!highwayContext.canWalkThroughAhead()) {
                // a solid at body height on an all-correct stretch is schematic-valid (the
                // ledge of a paved section, the wall of a pocket in the floor) and will never
                // be dug; the creep can't jump it, so release and let the builder path instead
                walkBlocker = "blocked at body height ahead";
            } else if (!highwayContext.noPortalAhead()) {
                // never creep into a lit portal waiting for its frame to be mined
                walkBlocker = "portal ahead";
            } else if (baritone.getInventoryPauserProcess().calmPausePending()) {
                // release the key while an inventory move waits for a tick without movement input
                walkBlocker = "inventory pause";
            } else if (pathing) {
                // while a segment executes the path executor owns the movement keys; forcing
                // the walk key under it would shove the player off the moves it planned
                walkBlocker = "pathing";
            } else if (baritone.getBuilderProcess().isBreakingInPlace()) {
                // a dig that spans ticks only finishes if the crosshair holds on the block: walking
                // slides the hit result onto a neighbour and vanilla restarts the progress there
                walkBlocker = "breaking in place";
            } else if (builderAim != null && builderAim.getY() >= ctx.playerFeet().y
                    && !highwayContext.inWalkLane(builderAim.getX(), builderAim.getZ())) {
                // A rail column is outside the lane the front scan watches, so nothing above knows
                // the builder is working on one. The walk key runs along the aim yaw, which points
                // at the rail, so it walks the bot into the rail it's trying to clear and slides it
                // along the face until the block it was aiming at isn't in the crosshair any more.
                // Only from our own feet up: nothing lower than that is in the way of a walk, and
                // a support column offset out under the rails is dug from the lane all day.
                walkBlocker = "interacting off the lane";
            } else if (builderAim != null && baritone.getInputOverrideHandler().isInputForcedDown(Input.SNEAK)) {
                // a stationary sneak-placement needs stillness in either mode. The key is read a
                // tick late like the aim is, which is right: the builder clears every forced input
                // at the top of its own tick, so what's still down is what it set on the last one
                walkBlocker = "sneak placement";
            } else if (builderAim != null && !highwayContext.paving() && !aimAlongHighway(builderAim)) {
                walkBlocker = "aim off the highway";
            }
            walk = walkBlocker == null;
            highwayContext.noteWalkGate(walkBlocker);
            if (walk) {
                // the key walks along the player's yaw, so point it down the highway every tick we
                // hold it; without this it walks wherever the last rotation left the camera
                highwayContext.faceHighwayDirection();
            }
        }
        setWalkForward(walk);

        highwayContext.handle();
    }

    /**
     * Whether an aim at the given block still points roughly down the highway
     */
    private boolean aimAlongHighway(BlockPos target) {
        float yaw = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(target), ctx.playerRotations()).getYaw();
        return Math.abs(Mth.degreesDifference(yaw, highwayContext.highwayDirectionYaw())) <= 50;
    }

    private void setWalkForward(boolean walk) {
        if (walk) {
            walkKeyHeld = true;
            ctx.minecraft().options.keyUp.setDown(true);
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        } else if (walkKeyHeld) {
            walkKeyHeld = false;
            ctx.minecraft().options.keyUp.setDown(false);
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
        }
    }

    @Override
    public boolean isEndDistanceWalkHeld() {
        return walkKeyHeld;
    }

    @Override
    public boolean isFixingInvalidBlocks() {
        return highwayContext.invalidBlockFixActive();
    }

    @Override
    public int lateralColumn(int x, int z) {
        return highwayContext.lateralColumn(x, z);
    }

    @Override
    public boolean needsLateralTraverses() {
        int crossWidth = settings.highwayWidth.value + (settings.highwayRail.value ? 2 : 0);
        return crossWidth > 2 * settings.blockReachDistance.value;
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
        if (event.getState() == EventState.POST) {
            // Runs on the netty thread, so only flags are written here.
            if (event.getPacket() instanceof ClientboundContainerSetContentPacket contents) {
                highwayContext.noteContainerSync(contents.getContainerId());
            } else if (event.getPacket() instanceof ClientboundLoginPacket
                    || event.getPacket() instanceof ClientboundRespawnPacket) {
                highwayContext.noteInventoryDesynced();
            }
        }

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