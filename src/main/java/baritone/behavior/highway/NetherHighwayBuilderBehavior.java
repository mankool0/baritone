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
import baritone.api.schematic.CellOverrides;
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
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
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
    private final SetbackTally setbacks = new SetbackTally();

    private boolean walkKeyHeld = false;
    // the creep let go only because the build front is too far ahead for it, last tick
    private boolean frontBeyondCreep = false;

    private static final String FRONT_TOO_CLOSE = "front too close";
    private static final String FRONT_TOO_FAR = "front too far";
    private static final String NO_FLOOR_AHEAD = "no floor ahead";
    /** Ticks pressed against an edge with nothing placed before the creep lets go of it */
    private static final int EDGE_STILL_TICKS = 20;
    /** Ticks it then leaves the builder to deal with whatever the printer couldn't place */
    private static final int EDGE_GIVE_UP_TICKS = 40;
    // a sneak this behavior put down to keep the creep from walking off the edge of the floor
    private boolean edgeSneakHeld = false;
    private int edgeStillTicks;
    private int edgeGiveUpTicks;
    private Vec3 edgeLastPos;

    /**
     * States {@link #stopWhenSafe} stops in: nothing of the builder's is set down. Stopped mid-farm
     * or with a shulker or ender chest out, it leaves them there. Hive's SAFE_STOP_STATES.
     */
    static final Set<HighwayState> SAFE_STOP_STATES = EnumSet.of(
            HighwayState.Nothing, HighwayState.BuildingHighway, HighwayState.InQueue,
            HighwayState.FloatingFixPrep, HighwayState.FloatingFix,
            HighwayState.LiquidRemovalPrep, HighwayState.LiquidRemovalPrepWait,
            HighwayState.BoatRemoval, HighwayState.MobCombat, HighwayState.MobCombatReturn,
            HighwayState.PortalEscape);
    /** A safe stop still waiting after this long stops anyway: hive's SAFE_STOP_MAX_SECONDS. */
    static final int SAFE_STOP_MAX_TICKS = 20 * 900;
    private boolean stopWhenSafe = false;
    private int stopWhenSafeTicks;

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
        return highwayContext.layout().schematic() != null && !highwayContext.paused();
    }

    @Override
    public boolean hasCustomPattern() {
        return highwayContext.layout().pattern().isCustom();
    }

    @Override
    public Vec3i repeatAdvance(BlockPos currentOrigin) {
        return highwayContext.layout().repeatAdvance(currentOrigin);
    }

    @Override
    public ISchematic schematicForOrigin(BlockPos origin) {
        return highwayContext.layout().hasPatternSchematics() ? highwayContext.layout().schematicForOrigin(origin) : null;
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
        build(startX, startZ, direct, selfSolve, pave, endCoords, startCoords, null);
    }

    @Override
    public void build(int startX, int startZ, Vec3i direct, boolean selfSolve, boolean pave, Vec3i endCoords, Vec3i startCoords, String pattern) {
        HighwayLayout layout = highwayContext.layout();
        HighwayPattern parsedPattern;
        try {
            parsedPattern = HighwayPattern.parse(pattern, direct);
        } catch (IllegalArgumentException e) {
            Helper.HELPER.logDirect("pattern rejected: " + e.getMessage());
            stop();
            return;
        }
        if (parsedPattern.isCustom()) {
            // The pattern owns the angle; the direction only supplies the two signs, so a longer
            // vector would just desync the pieces that still read it (the diagonal rail supports,
            // the schematic group table, canonicalOriginVector).
            direct = new Vec3i(Integer.signum(direct.getX()), 0, Integer.signum(direct.getZ()));
        }
        teardown(); // nhwbuild is a restart, not an overlay: nothing the last build held survives it
        CellOverrides cellOverrides;
        try {
            cellOverrides = CellOverrides.parse(settings.highwayCellOverrides.value);
        } catch (IllegalArgumentException e) {
            // built without, a lane would dig the rails it was told to keep
            layout.setCellOverrides(CellOverrides.NONE);
            highwayContext.pause("highwayCellOverrides rejected, " + e.getMessage());
            return;
        }
        layout.setCellOverrides(cellOverrides);
        highwayContext.diagnostics().resetStateDwell(); // per build, so a stop leaves the last run's timings readable
        layout.setHighwayDirection(direct);
        layout.setPaving(pave);
        layout.setPattern(parsedPattern);
        if (parsedPattern.isCustom()) {
            Helper.HELPER.logDirect("Angled highway pattern: " + parsedPattern.describe());
        }
        highwayContext.setCachedHealth(ctx.player().getHealth());
        highwayContext.setCachedAbsorption(ctx.player().getAbsorptionAmount());
        startDimension = ctx.world().dimension();

        if (!layout.paving()) {
            // Only digging so any pickaxe works
            highwayContext.resupply().setPicksToUse(ShulkerType.AnyPickaxe);
            highwayContext.resupply().setPicksToHave(settings.highwayPicksToHaveDigging.value);
        } else {
            // If paving then we mine echests and need non-silk picks
            highwayContext.resupply().setPicksToUse(ShulkerType.NonSilkPickaxe);
            highwayContext.resupply().setPicksToHave(settings.highwayPicksToHavePaving.value);
        }

        int highwayWidth = settings.highwayWidth.value;
        int highwayHeight = settings.highwayHeight.value;
        boolean highwayRail = hasRails(settings);
        boolean railLow = highwayRail && settings.highwayRailLow.value;
        boolean railHigh = highwayRail && settings.highwayRailHigh.value;
        int highwayWidthOffset = -((Math.round(highwayWidth / 2.0f)) + 1);
        int highwayWidthLiqOffset = -(Math.round(highwayWidth / 2.0f)) - 1;
        int highwayWidthLiqOffsetRail = -(Math.round(highwayWidth / 2.0f)) - 2;
        int highwayWidthCenterOffset = 1 + (highwayWidth - 1) / 2;

        // Custom patterns pick the layout by their driving axis: the cross-section must span the
        // jog axis. Classic builds keep the legacy direction table.
        boolean groupA = parsedPattern.isCustom() ? parsedPattern.majorIsX() : isGroupA(layout.highwayDirection());
        boolean groupB = !groupA && (parsedPattern.isCustom() || isGroupB(layout.highwayDirection()));

        // +X, -X, +X +Z, -X -Z
        if (groupA) {
            if (selfSolve) {
                layout.setOriginVector(canonicalOriginVector(groupA, settings));
                if (highwayRail) {
                    layout.setLiqOriginVector(new Vec3(0, 0, highwayWidthLiqOffsetRail));
                } else {
                    layout.setLiqOriginVector(new Vec3(0, 0, highwayWidthLiqOffset));
                }
                layout.setBackPathOriginVector(new Vec3(0, 0, highwayWidthOffset + highwayWidthCenterOffset));
                layout.seteChestEmptyShulkOriginVector(new Vec3(0, 0, highwayWidthLiqOffsetRail));
            } else {
                layout.setOriginVector(new Vec3(startX, 0, startZ - highwayWidthOffset));
                if (highwayRail) {
                    layout.setLiqOriginVector(new Vec3(startX, 0, startZ - highwayWidthLiqOffsetRail - 2));
                } else {
                    layout.setLiqOriginVector(new Vec3(startX, 0, startZ - highwayWidthLiqOffset));
                }
                layout.setBackPathOriginVector(new Vec3(startX, 0, startZ - highwayWidthOffset + highwayWidthCenterOffset));
                layout.seteChestEmptyShulkOriginVector(new Vec3(startX, 0, startZ - highwayWidthOffset - 1));
            }

            if (pave) {
                layout.setLiqOriginVector(layout.liqOriginVector().add(0, 0, 1));
                if (highwayRail) {
                    layout.setLiqCheckSchem(new WhiteBlackSchematic(1, highwayHeight - 1, highwayWidth + 2, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                } else {
                    layout.setLiqCheckSchem(new WhiteBlackSchematic(1, highwayHeight - 1, highwayWidth, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                }
            } else {
                if (highwayRail) {
                    layout.setLiqCheckSchem(new WhiteBlackSchematic(1, highwayHeight + 1, highwayWidth + 4, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                } else {
                    layout.setLiqCheckSchem(new WhiteBlackSchematic(1, highwayHeight + 1, highwayWidth + 2, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                }
            }
        }
        // +Z, -Z, +X -Z, -X +Z
        else if (groupB) {
            if (selfSolve) {
                layout.setOriginVector(canonicalOriginVector(groupA, settings));
                if (highwayRail) {
                    layout.setLiqOriginVector(new Vec3(highwayWidthLiqOffsetRail, 0, 0));
                } else {
                    layout.setLiqOriginVector(new Vec3(highwayWidthLiqOffset, 0, 0));
                }
                layout.setBackPathOriginVector(new Vec3(highwayWidthOffset + highwayWidthCenterOffset, 0, 0));
                layout.seteChestEmptyShulkOriginVector(new Vec3(highwayWidthLiqOffsetRail, 0, 0));
            } else {
                layout.setOriginVector(new Vec3(startX - highwayWidthOffset, 0, startZ));
                if (highwayRail) {
                    layout.setLiqOriginVector(new Vec3(startX - highwayWidthLiqOffsetRail - 2, 0, startZ));
                } else {
                    layout.setLiqOriginVector(new Vec3(startX - highwayWidthLiqOffset, 0, startZ));
                }
                layout.setBackPathOriginVector(new Vec3(startX - highwayWidthOffset + highwayWidthCenterOffset, 0, startZ));
                layout.seteChestEmptyShulkOriginVector(new Vec3(startX - highwayWidthOffset - 1, 0, startZ));
            }

            if (pave) {
                layout.setLiqOriginVector(layout.liqOriginVector().add(1, 0, 0));
                if (highwayRail) {
                    layout.setLiqCheckSchem(new WhiteBlackSchematic(highwayWidth + 2, highwayHeight - 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                } else {
                    layout.setLiqCheckSchem(new WhiteBlackSchematic(highwayWidth, highwayHeight - 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                }
            } else {
                if (highwayRail) {
                    layout.setLiqCheckSchem(new WhiteBlackSchematic(highwayWidth + 4, highwayHeight + 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                } else {
                    layout.setLiqCheckSchem(new WhiteBlackSchematic(highwayWidth + 2, highwayHeight + 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                }
            }
        }

        layout.setSchematic(composeHighwaySchematic(layout.highwayDirection(), groupA, groupB, pave, settings));
        // Rails are the one component whose cells differ between slices that share a major
        // coordinate, so an angled build with rails needs the per-slice cross-sections.
        layout.setPatternSchematics(parsedPattern.isCustom() && highwayRail
                ? composePatternSchematics(layout.highwayDirection(), groupA, groupB, pave, settings)
                : null);
        // Side storage is one column below the cross-section's first, so the same column past its
        // last (W+1, a rail or the lane's wall) is W+3 further on along the cross axis
        Vec3 storageLine = layout.eChestEmptyShulkOriginVector();
        layout.setFarSideStorageOriginVector(groupA
                ? storageLine.add(0, 0, highwayWidth + 3)
                : storageLine.add(highwayWidth + 3, 0, 0));

        Vec3 origin = new Vec3(layout.originVector().x, layout.originVector().y, layout.originVector().z);
        Vec3 direction = new Vec3(layout.highwayDirection().getX(), layout.highwayDirection().getY(), layout.highwayDirection().getZ());
        // A named start replaces the feet projection wholesale: firstStartingPos then clamps every
        // later projection to it, so a start ahead of the bot needs no special handling.
        Vec3 startFrom = (startCoords != null)
                ? new Vec3(startCoords.getX(), 0, startCoords.getZ())
                : new Vec3(ctx.playerFeet().getX(), ctx.playerFeet().getY(), ctx.playerFeet().getZ());
        layout.setOriginBuild(layout.getClosestPoint(origin, direction, startFrom, LocationType.HighwayBuild));

        layout.setFirstStartingPos(new BetterBlockPos(layout.originBuild()));

        if (endCoords != null) {
            BetterBlockPos end = layout.getClosestPoint(origin, direction, new Vec3(endCoords.getX(), 0, endCoords.getZ()), LocationType.HighwayBuild);
            int endSteps = layout.stepsAlongHighway(layout.firstStartingPos(), end);
            if (endSteps < 0) {
                Helper.HELPER.logDirect("End " + endCoords.getX() + ", " + endCoords.getZ() + " is behind the start " + layout.firstStartingPos().toString() + ", not starting");
                stop();
                return;
            }
            layout.setEndPos(end);
            Helper.HELPER.logDirect("Stopping at " + end.toString() + " (" + endSteps + " blocks ahead)");
        }

        Helper.HELPER.logDirect("Profile: width=" + highwayWidth + " height=" + highwayHeight
                + " y=" + settings.highwayLowestY.value + "/" + settings.highwayMainY.value
                + " rails=" + (railLow ? "low" : "") + (railHigh ? "high" : "")
                + (!railLow && !railHigh ? "none" : "")
                + (settings.highwayExcavate.value ? " excavate" : "")
                + (cellOverrides.isEmpty() ? "" : " overrides=" + cellOverrides.size()));
        Helper.HELPER.logDirect("Building from " + layout.originBuild().toString());
        settings.buildRepeat.value = new Vec3i(layout.highwayDirection().getX(), 0, layout.highwayDirection().getZ());

        highwayContext.resupply().setEnderChestHasPickShulks(true);
        highwayContext.resupply().setEnderChestHasEnderShulks(true);
        highwayContext.resupply().setRefillingEnderChests(false);
        highwayContext.resupply().resetSupplies();
        highwayContext.resupply().setStashingShulker(false);
        highwayContext.resupply().setEnderChestAccessLoc(null);
        highwayContext.farm().resetFarmForRun();
        highwayContext.sync().resetObsidianDrop();
        highwayContext.threats().resetThiefHunt();
        highwayContext.threats().clearIgnoredMobs();
        highwayContext.containerRecovery().clearLostShulkerRelogAttempt();
        highwayContext.resupply().setStartShulkerCount(highwayContext.inventory().getShulkerCountInventory(ShulkerType.Any));
        highwayContext.setPaused(false);
        highwayContext.liquidRemoval().resetThroughWallDetection();
        highwayContext.transitionTo(HighwayState.Nothing);
    }

    private void teardown() {
        stopWhenSafe = false;
        highwayContext.sync().clearThresholdConfirm();
        wrongDimension = false;
        highwayContext.recovery().resetRecovery(); // held keys, suppressHitResult, and settings (e.g. allowSwimThroughLava) overridden during recovery
        highwayContext.clearInvalidBlockFix();
        highwayContext.endTracker().stopTravelTowardsEnd();
        highwayContext.transitionTo(HighwayState.Nothing); // onExit: whatever the state we interrupt holds
        setWalkForward(false);
        edgeStillTicks = 0;
        edgeGiveUpTicks = 0;
        edgeLastPos = null;
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getPathingBehavior().cancelEverything();
        highwayContext.layout().setFirstStartingPos(null); // a stale start or end clamps every projection the next build makes
        highwayContext.layout().setOriginBuild(null);
        highwayContext.layout().setEndPos(null);
        settings.buildRepeatCount.value = -1; // Nothing clamps it while an end is set
    }

    /** Whether the profile models rail columns: an excavated road has none. */
    public static boolean hasRails(Settings settings) {
        return settings.highwayRail.value && !settings.highwayExcavate.value;
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
        if (isGroupA(direction)) {
            return canonicalOriginVector(true, settings);
        }
        if (isGroupB(direction)) {
            return canonicalOriginVector(false, settings);
        }
        throw new IllegalArgumentException("Not a highway direction: " + direction);
    }

    /**
     * The schematic's origin for a selfSolve build, with the layout chosen explicitly. A custom
     * pattern picks its group from its own driving axis, not from the direction table, and every
     * parallel line has to agree on that choice: they are offset from each other on the cross axis
     * only, and the slice indexing relies on their driving-axis origin components matching.
     */
    public static Vec3 canonicalOriginVector(boolean groupA, Settings settings) {
        int highwayWidthOffset = -((Math.round(settings.highwayWidth.value / 2.0f)) + 1);
        return groupA ? new Vec3(0, 0, highwayWidthOffset) : new Vec3(highwayWidthOffset, 0, 0);
    }

    /**
     * Compose the one-slice highway schematic for the given direction and mode. Pure - reads only
     * the highway profile settings - so HighwayConformanceDump emits exactly what the
     * builder targets.
     */
    public static CompositeSchematic composeHighwaySchematic(Vec3i direction, boolean pave, Settings settings) {
        return composeHighwaySchematic(direction, isGroupA(direction), isGroupB(direction), pave, settings);
    }

    /**
     * @param groupA layout with the cross-section spanning Z (X-driving); for custom patterns the
     *               grouping follows the pattern's major axis rather than the legacy direction table
     * @param groupB layout with the cross-section spanning X (Z-driving)
     */
    public static CompositeSchematic composeHighwaySchematic(Vec3i direction, boolean groupA, boolean groupB, boolean pave, Settings settings) {
        return composeHighwaySchematic(direction, groupA, groupB, pave, settings, true, true);
    }

    /**
     * The four cross-sections an angled build alternates between, indexed by
     * {@code (lowRail ? 1 : 0) | (highRail ? 2 : 0)}. Slices that share a major coordinate overlap on
     * the cross axis, and only the outermost of them may carry the rail on that side - see
     * {@link HighwayPattern#ownsLowRail}. All four report the same dimensions, so callers can size
     * their loops against any of them and query the rest cell by cell.
     */
    public static CompositeSchematic[] composePatternSchematics(Vec3i direction, boolean groupA, boolean groupB, boolean pave, Settings settings) {
        CompositeSchematic[] variants = new CompositeSchematic[4];
        for (int bits = 0; bits < variants.length; bits++) {
            variants[bits] = composeHighwaySchematic(direction, groupA, groupB, pave, settings, (bits & 1) != 0, (bits & 2) != 0);
        }
        return variants;
    }

    /**
     * @param lowRailHere  emit the rail column at cross offset 0 (the {@code highwayRailLow} side)
     * @param highRailHere emit the rail column at cross offset width+1
     */
    public static CompositeSchematic composeHighwaySchematic(Vec3i direction, boolean groupA, boolean groupB, boolean pave, Settings settings,
                                                            boolean lowRailHere, boolean highRailHere) {
        int highwayWidth = settings.highwayWidth.value;
        int highwayHeight = settings.highwayHeight.value;
        int supportWidth = settings.highwaySupportWidth.value;
        int supportOffset = settings.highwaySupportOffset.value;
        boolean excavate = settings.highwayExcavate.value;
        boolean rails = hasRails(settings);
        boolean railLow = rails && settings.highwayRailLow.value;
        boolean railHigh = rails && settings.highwayRailHigh.value;
        boolean diag = Math.abs(direction.getX()) == Math.abs(direction.getZ()) && Math.abs(direction.getZ()) == 1;
        // an excavated floor row is the dig profile's, minus the obsidian
        boolean paveFloor = pave && !excavate;
        java.util.List<BlockState> floorKeeps = excavate
                ? Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState())
                : Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.OBSIDIAN.defaultBlockState(), Blocks.CRYING_OBSIDIAN.defaultBlockState());

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
        // A slice that hands one of its rail columns to a neighbour still reports the full
        // cross-section: callers size their loops against one variant and query the others cell by
        // cell, and the builder's own bounds must not shrink under it when the phase changes.
        CompositeSchematic fullSchem;
        if (rails && !(lowRailHere && highRailHere)) {
            CompositeSchematic complete = composeHighwaySchematic(direction, groupA, groupB, pave, settings, true, true);
            fullSchem = new CompositeSchematic(complete.widthX(), complete.heightY(), complete.lengthZ());
        } else {
            fullSchem = new CompositeSchematic(0, 0, 0);
        }

        // +X, -X, +X +Z, -X -Z
        if (groupA) {
            topAir = new WhiteBlackSchematic(1, highwayHeight - 1, highwayWidth, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false);
            if (paveFloor) {
                obsidSchemBot = new WhiteBlackSchematic(1, 1, highwayWidth, Arrays.asList(Blocks.OBSIDIAN.defaultBlockState(), Blocks.CRYING_OBSIDIAN.defaultBlockState()), Blocks.OBSIDIAN.defaultBlockState(), true, false, false);

                if (diag && (railLow || railHigh)) {
                    sideRailSupport = new WhiteBlackSchematic(1, 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.LAVA.defaultBlockState(), Blocks.FIRE.defaultBlockState()), Blocks.NETHERRACK.defaultBlockState(), false, true, true);
                    sideRailSupport.setThrowawayFallback(Blocks.OBSIDIAN.defaultBlockState());
                    if (railLow && lowRailHere) {
                        fullSchem.put(sideRailSupport, 0, 1, 0);
                    }
                    if (railHigh && highRailHere) {
                        fullSchem.put(sideRailSupport, 0, 1, highwayWidth + 1);
                    }
                }
            } else {
                obsidSchemBot = new WhiteBlackSchematic(1, 1, highwayWidth, floorKeeps, Blocks.AIR.defaultBlockState(), true, false, false); // Allow only air, and obsidian unless excavating
                supportNetherRack = new WhiteBlackSchematic(1, 1, supportWidth, HighwayContext.blackListBlocks, Blocks.NETHERRACK.defaultBlockState(), false, true, true); // Allow everything other than air and lava
                if (supportWidth + 2 <= highwayWidth) {
                    fullSchem.put(noLavaBotSides, 0, 0, 1);
                    fullSchem.put(noLavaBotSides, 0, 0, highwayWidth);
                }
                fullSchem.put(supportNetherRack, 0, 0, supportOffset);
            }

            fullSchem.put(obsidSchemBot, 0, 1, 1);
            if (lowRailHere) {
                if (railLow) {
                    fullSchem.put(sideRail, 0, 2, 0);
                    fullSchem.put(sideRailAir, 0, 3, 0);
                } else if (rails) {
                    fullSchem.put(railClear, 0, 2, 0);
                }
            }
            if (highRailHere) {
                if (railHigh) {
                    fullSchem.put(sideRail, 0, 2, highwayWidth + 1);
                    fullSchem.put(sideRailAir, 0, 3, highwayWidth + 1);
                } else if (rails) {
                    fullSchem.put(railClear, 0, 2, highwayWidth + 1);
                }
            }
            fullSchem.put(topAir, 0, 2, 1);
        }
        // +Z, -Z, +X -Z, -X +Z
        else if (groupB) {
            topAir = new WhiteBlackSchematic(highwayWidth, highwayHeight - 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false);
            if (paveFloor) {
                obsidSchemBot = new WhiteBlackSchematic(highwayWidth, 1, 1, Arrays.asList(Blocks.OBSIDIAN.defaultBlockState(), Blocks.CRYING_OBSIDIAN.defaultBlockState()), Blocks.OBSIDIAN.defaultBlockState(), true, false, false);

                if (diag && (railLow || railHigh)) {
                    sideRailSupport = new WhiteBlackSchematic(1, 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.LAVA.defaultBlockState(), Blocks.FIRE.defaultBlockState()), Blocks.NETHERRACK.defaultBlockState(), false, true, true);
                    sideRailSupport.setThrowawayFallback(Blocks.OBSIDIAN.defaultBlockState());
                    if (railLow && lowRailHere) {
                        fullSchem.put(sideRailSupport, 0, 1, 0);
                    }
                    if (railHigh && highRailHere) {
                        fullSchem.put(sideRailSupport, highwayWidth + 1, 1, 0);
                    }
                }
            } else {
                obsidSchemBot = new WhiteBlackSchematic(highwayWidth, 1, 1, floorKeeps, Blocks.AIR.defaultBlockState(), true, false, false); // Allow only air, and obsidian unless excavating
                supportNetherRack = new WhiteBlackSchematic(supportWidth, 1, 1, HighwayContext.blackListBlocks, Blocks.NETHERRACK.defaultBlockState(), false, true, true); // Allow everything other than air and lava
                if (supportWidth + 2 <= highwayWidth) {
                    fullSchem.put(noLavaBotSides, 1, 0, 0);
                    fullSchem.put(noLavaBotSides, highwayWidth, 0, 0);
                }

                fullSchem.put(supportNetherRack, supportOffset, 0, 0);
            }

            fullSchem.put(obsidSchemBot, 1, 1, 0);
            if (lowRailHere) {
                if (railLow) {
                    fullSchem.put(sideRail, 0, 2, 0);
                    fullSchem.put(sideRailAir, 0, 3, 0);
                } else if (rails) {
                    fullSchem.put(railClear, 0, 2, 0);
                }
            }
            if (highRailHere) {
                if (railHigh) {
                    fullSchem.put(sideRail, highwayWidth + 1, 2, 0);
                    fullSchem.put(sideRailAir, highwayWidth + 1, 3, 0);
                } else if (rails) {
                    fullSchem.put(railClear, highwayWidth + 1, 2, 0);
                }
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
        printBuildSummary();

        teardown();
        highwayContext.setPaused(true);
    }

    @Override
    public void stopWhenSafe() {
        if (safeToStop()) {
            stop();
            return;
        }
        if (!stopWhenSafe) {
            Helper.HELPER.logDirect("Stopping the nether highway builder once it is safe - now at state "
                    + highwayContext.currentState().getState());
            stopWhenSafeTicks = 0;
        }
        stopWhenSafe = true;
    }

    private boolean safeToStop() {
        return highwayContext.paused() || highwayContext.layout().schematic() == null
                || SAFE_STOP_STATES.contains(highwayContext.currentState().getState());
    }

    /** True when a pending {@link #stopWhenSafe} stopped the builder this tick. */
    private boolean stopIfSafe() {
        if (safeToStop()) {
            stop();
            return true;
        }
        if (++stopWhenSafeTicks >= SAFE_STOP_MAX_TICKS) {
            Helper.HELPER.logDirect("Still at state " + highwayContext.currentState().getState() + " after "
                    + SAFE_STOP_MAX_TICKS / 20 + "s - stopping anyway");
            stop();
            return true;
        }
        return false;
    }

    /**
     * The tallies nhwstatus prints, for the stretch since they were last read, so runs nobody
     * watched can be gone over from the log. Reading them resets them.
     */
    private void printBuildSummary() {
        if (!Baritone.settings().highwayLogBuildSummary.value) {
            return; // left for nhwstatus
        }
        if (!highwayContext.diagnostics().printerTallied()) {
            return; // a second stop right after the first
        }
        Helper.HELPER.logDiagnostic("Build summary up to " + (ctx.player() != null ? ctx.playerFeet() : "here")
                + (highwayContext.layout().endPos() != null ? ", building towards " + highwayContext.layout().endPos() : "") + ":");
        Helper.HELPER.logDiagnostic(highwayContext.diagnostics().walkGateDiagnostic());
        Helper.HELPER.logDiagnostic(highwayContext.diagnostics().printerDiagnostic());
        Helper.HELPER.logDiagnostic(baritone.getPacketTrace().diagnostic());
        Helper.HELPER.logDiagnostic(setbacks.diagnostic());
    }

    @Override
    public void printStatus() {
        Helper.HELPER.logDirect("State: " + highwayContext.currentState().getState());
        if (highwayContext.layout().endPos() != null) {
            Helper.HELPER.logDirect("End: " + highwayContext.layout().endPos().toString());
        }
        Helper.HELPER.logDirect("Paused: " + highwayContext.paused()); // true/false alone: hive.py reads this line
        if (highwayContext.pauseReason() != null) {
            Helper.HELPER.logDirect("Pause reason: " + highwayContext.pauseReason());
        }
        Helper.HELPER.logDirect("Timer: " + highwayContext.timer());
        Helper.HELPER.logDirect("startShulkerCount: " + highwayContext.resupply().startShulkerCount());
        // nothing reads these off chat, so they go to the game log unless chatDebug is on
        Helper.HELPER.logDiagnostic(highwayContext.scanner().frontDistanceDiagnostic());
        Helper.HELPER.logDiagnostic(highwayContext.diagnostics().walkGateDiagnostic());
        Helper.HELPER.logDiagnostic(highwayContext.diagnostics().printerDiagnostic());
        Helper.HELPER.logDiagnostic(baritone.getPacketTrace().diagnostic());
        Helper.HELPER.logDiagnostic(setbacks.diagnostic());
        highwayContext.diagnostics().stateDwellDiagnostic(12).forEach(Helper.HELPER::logDiagnostic);
    }

    @Override
    public void onTick(TickEvent event) {
        frontBeyondCreep = false; // only a tick that evaluates the creep says otherwise
        if (stopWhenSafe && event.getType() == TickEvent.Type.IN && ctx.player() != null && stopIfSafe()) {
            return;
        }
        if (highwayContext.paused() || highwayContext.layout().schematic() == null || ctx.player() == null || ctx.player().getInventory().isEmpty() || event.getType() == TickEvent.Type.OUT) {
            setWalkForward(false); // never leave the walk key latched while the state machine isn't driving
            if (ctx.player() != null && event.getType() != TickEvent.Type.OUT) {
                updateEdgeSneak(false, false); // stopped at the edge, the bot still slides
            }
            setbacks.skipTick(event.getType() == TickEvent.Type.OUT);
            return;
        }

        highwayContext.incrementTimers();
        // the processes ticked earlier this tick, so the builder's report is this tick's
        highwayContext.diagnostics().notePrinterTick(baritone.getBuilderProcess().consumePrinterReport());
        setbacks.noteTick(highwayContext.currentState().getState() == HighwayState.BuildingHighway, ctx.player());

        if (startDimension != null && !highwayContext.isInQueue()) {
            if (ctx.world().dimension() != startDimension) {
                if (!wrongDimension) {
                    wrongDimension = true;
                    portalReturn.reset();
                    Helper.HELPER.logDirect("Now in " + ctx.world().dimension().identifier() + " instead of " + startDimension.identifier()
                            + " (portal teleport?). Stepping out of the exit portal and back in to get sent home.");
                    baritone.getInputOverrideHandler().clearAllKeys();
                    baritone.getPathingBehavior().cancelEverything();
                }
                setWalkForward(false);
                updateEdgeSneak(false, false);
                portalReturn.tick(startDimension);
                return;
            }
            if (wrongDimension) {
                wrongDimension = false;
                Helper.HELPER.logDirect("Back in " + startDimension.identifier() + ", restarting the builder.");
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
        if (curState != HighwayState.PortalEscape && curState != HighwayState.InQueue && highwayContext.walk().isPlayerInPortal()) {
            Helper.HELPER.logDirect("Standing inside a nether portal, stepping out.");
            baritone.getInputOverrideHandler().clearAllKeys();
            baritone.getPathingBehavior().cancelEverything();
            highwayContext.transitionTo(HighwayState.PortalEscape);
        }
        boolean escaping = highwayContext.currentState().getState() == HighwayState.PortalEscape;

        highwayContext.inventory().closeStrayContainer();

        // Offhand rescue/autoTotem/clearCursorItem pause the state machine while they work, but the
        // stuck/health watchdogs must run regardless: a cursor stack that can never be placed (full
        // inventory) used to starve them and freeze the state machine forever with its keys latched.
        // Rescue runs before autoTotem so stranded chests merge onto a loose stack instead of being
        // swapped into the totem's slot.
        boolean pauseStateMachine = !escaping && (highwayContext.inventory().rescueOffhandEnderChests() || highwayContext.inventory().autoTotem() || highwayContext.inventory().clearCursorItem());
        if (highwayContext.stuckCheck() || highwayContext.healthCheck() || pauseStateMachine) {
            setWalkForward(false);
            updateEdgeSneak(false, false);
            return;
        }

        // Handle distance to keep from end of highway
        boolean walk = false;
        boolean mayEdgeSneak = false;
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
            int frontDistance = highwayContext.scanner().getHighwayFrontDistance();
            String walkBlocker = creepBlocker(frontDistance, pathing, builderAim, false);
            frontBeyondCreep = walkBlocker == FRONT_TOO_FAR;
            // read every tick, so a tick the builder didn't run never counts
            boolean printerOutOfReach = baritone.getBuilderProcess().consumePrinterOutOfReach();
            boolean toEdge = false;
            if ((walkBlocker == FRONT_TOO_CLOSE || walkBlocker == NO_FLOOR_AHEAD) && highwayContext.layout().paving()
                    && printerOutOfReach && edgeGiveUpTicks == 0 && ctx.player().onGround()) {
                // Out over open air the next block of floor only goes against the face of the last
                // one that looks along the road, and the server only takes a click on that face from
                // a player standing past it. The creep stopped short of it, so the builder pathed
                // over and bridged every block itself. Walk on instead, with a sneak down where the
                // floor runs out: vanilla stops the player at the edge, half out over the gap with
                // that face turned towards it. The rest of the gate still has its say.
                toEdge = creepBlocker(frontDistance, pathing, builderAim, true) == null;
            }
            noteEdgeProgress(toEdge);
            walk = walkBlocker == null || toEdge;
            highwayContext.diagnostics().noteWalkGate(walk ? null : walkBlocker, toEdge);
            if (walk) {
                // the key walks along the player's yaw, so point it down the highway every tick we
                // hold it; without this it walks wherever the last rotation left the camera
                highwayContext.walk().faceHighwayDirection();
            }
            mayEdgeSneak = highwayContext.layout().paving() && !pathing;
        }
        setWalkForward(walk);
        updateEdgeSneak(walk, mayEdgeSneak);

        highwayContext.handle();
    }

    /**
     * What holds the walk creep's key up this tick, or null if nothing does. Same conditions in the
     * same order as ever, evaluated one at a time so nhwstatus can say which one is actually
     * holding the key down.
     *
     * @param toEdge leave out the two that keep it short of the front and of the edge of the floor
     */
    private String creepBlocker(int frontDistance, boolean pathing, BlockPos builderAim, boolean toEdge) {
        // a dispatched repair needs to path to a block behind us; creeping forward (and
        // steering back to the lane center) would drag the bot off that path every tick
        if (highwayContext.invalidBlockFixActive()) {
            return "invalid-block repair";
        } else if (!toEdge && frontDistance < settings.highwayEndDistance.value) {
            return FRONT_TOO_CLOSE;
        } else if (frontDistance >= settings.highwayEndDistance.value + highwayContext.scanner().creepMaxOvershoot()) {
            // only creep while the build front is right in front of us
            return FRONT_TOO_FAR;
        } else if (!toEdge && !highwayContext.walk().canWalkOnFloorAhead()) {
            return NO_FLOOR_AHEAD;
        } else if (!highwayContext.walk().canWalkThroughAhead()) {
            // a solid at body height on an all-correct stretch is schematic-valid (the
            // ledge of a paved section, the wall of a pocket in the floor) and will never
            // be dug; the creep can't jump it, so release and let the builder path instead.
            // Vanilla moves the hitbox one axis at a time, so it's not only the cell ahead
            // of our feet: on a straight, the cell ahead of any column the hitbox overlaps
            // stops it too. On a diagonal, the hitbox cuts the corner through one of the two
            // cells beside the one ahead, the way a diagonal movement does, and with both
            // solid it's stuck in the corner. A narrower road already paved inside a dug lane
            // leaves a column like that beside it: its obsidian on one side, the rail
            // column's netherrack at feet level on the other.
            return "blocked at body height ahead";
        } else if (!highwayContext.walk().noPortalAhead()) {
            // never creep into a lit portal waiting for its frame to be mined
            return "portal ahead";
        } else if (baritone.getInventoryPauserProcess().calmPausePending()) {
            // release the key while an inventory move waits for a tick without movement input
            return "inventory pause";
        } else if (baritone.getBuilderProcess().placeInFlightLeftBehind(highwayContext.layout().highwayHeading())) {
            // a block further on and the printer couldn't place it again if the server turns it down
            return "waiting on a place behind";
        } else if (pathing) {
            // while a segment executes the path executor owns the movement keys; forcing
            // the walk key under it would shove the player off the moves it planned
            return "pathing";
        } else if (baritone.getBuilderProcess().isBreakingInPlace()) {
            // a dig that spans ticks only finishes if the crosshair holds on the block: walking
            // slides the hit result onto a neighbour and vanilla restarts the progress there
            return "breaking in place";
        } else if (builderAim != null && builderAim.getY() >= ctx.playerFeet().y
                && !highwayContext.layout().inWalkLane(builderAim.getX(), builderAim.getZ())) {
            // A rail column is outside the lane the front scan watches, so nothing above knows
            // the builder is working on one. The walk key runs along the aim yaw, which points
            // at the rail, so it walks the bot into the rail it's trying to clear and slides it
            // along the face until the block it was aiming at isn't in the crosshair any more.
            // Only from our own feet up: nothing lower than that is in the way of a walk, and
            // a support column offset out under the rails is dug from the lane all day.
            return "interacting off the lane";
        } else if (builderAim != null && baritone.getInputOverrideHandler().isInputForcedDown(Input.SNEAK)) {
            // a stationary sneak-placement needs stillness in either mode. The key is read a
            // tick late like the aim is, which is right: the builder clears every forced input
            // at the top of its own tick, so what's still down is what it set on the last one
            return "sneak placement";
        } else if (builderAim != null && !highwayContext.layout().paving() && !aimAlongHighway(builderAim)) {
            return "aim off the highway";
        }
        return null;
    }

    /**
     * Counts the ticks the creep walks out to the edge without getting anywhere. Pressed against the
     * edge with the printer still idle, whatever it can't place isn't the next block of floor, so
     * the creep lets go for a while and leaves it to the builder.
     */
    private void noteEdgeProgress(boolean toEdge) {
        if (edgeGiveUpTicks > 0) {
            edgeGiveUpTicks--;
        }
        Vec3 pos = ctx.player().position();
        if (toEdge && edgeLastPos != null && pos.distanceToSqr(edgeLastPos) < 1.0E-4) {
            if (++edgeStillTicks >= EDGE_STILL_TICKS) {
                edgeStillTicks = 0;
                edgeGiveUpTicks = EDGE_GIVE_UP_TICKS;
                highwayContext.diagnostics().noteEdgeGiveUp();
            }
        } else {
            edgeStillTicks = 0;
        }
        edgeLastPos = pos;
    }

    /**
     * Holds a sneak down while the rest of the player's slide would carry it off the edge of the
     * floor, so that vanilla stops it at the edge instead. Vanilla does that on the very tick the
     * sneak goes down, so the sneak only goes down on the tick a step would go over, and the creep
     * walks at full speed until then.
     *
     * @param walking whether the walk key is down this tick
     * @param start   whether a sneak may go down this tick, rather than only stay down: on the walk
     *                creep's own ticks, never under anything else's movement
     */
    private void updateEdgeSneak(boolean walking, boolean start) {
        boolean pathing = baritone.getPathingBehavior().isPathing();
        boolean sneak = (start || edgeSneakHeld) && !pathing && ctx.player().onGround()
                && highwayContext.walk().wouldSlideOffFloor(walking);
        if (sneak) {
            edgeSneakHeld = true;
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            // the input handler already picked this tick's movement input, and only the one it
            // swaps in for forced inputs reads a forced sneak
            baritone.getInputOverrideHandler().updateMovementInput();
        } else if (edgeSneakHeld) {
            edgeSneakHeld = false;
            if (!pathing && baritone.getBuilderProcess().stationaryAimTarget() == null) {
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, false); // otherwise it's theirs now
            }
        }
    }

    /**
     * Whether an aim at the given block still points roughly down the highway
     */
    private boolean aimAlongHighway(BlockPos target) {
        float yaw = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(target), ctx.playerRotations()).getYaw();
        return Math.abs(Mth.degreesDifference(yaw, highwayContext.layout().highwayDirectionYaw())) <= 50;
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
    public boolean isBuildFrontBeyondCreep() {
        return frontBeyondCreep;
    }

    @Override
    public boolean isFixingInvalidBlocks() {
        return highwayContext.invalidBlockFixActive();
    }

    @Override
    public int lateralColumn(int x, int z) {
        return highwayContext.layout().lateralColumn(x, z);
    }

    @Override
    public boolean inWalkLane(int x, int z) {
        return highwayContext.layout().inWalkLane(x, z);
    }

    @Override
    public Vec3 highwayHeading() {
        return highwayContext.layout().highwayHeading();
    }

    @Override
    public boolean needsLateralTraverses() {
        int crossWidth = settings.highwayWidth.value + (hasRails(settings) ? 2 : 0);
        return crossWidth > 2 * settings.blockReachDistance.value;
    }

    @Override
    public void onPlayerSprintState(SprintStateEvent event) {
        // a sneak stops a sprint anyway, and asking for one under it would only fight it
        if (walkKeyHeld && settings.highwaySprint.value && !baritone.getPathingBehavior().isPathing() && !edgeSneakHeld) {
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
            highwayContext.scanner().renderLockLiquid().lock();
            AABB liquidArea = highwayContext.scanner().renderAreaLiquid();
            highwayContext.scanner().renderLockLiquid().unlock();

            if (liquidArea != null) {
                BufferBuilder bufferBuilder = IRenderer.startLines(Color.BLUE);
                // Inflate a bit more than the building-area boxes: when paving, the scan area hugs
                // the building area, so equal-size boxes would z-fight
                IRenderer.emitAABB(bufferBuilder, event.getModelViewStack(), liquidArea, .05D, 2);
                IRenderer.endLines(bufferBuilder, settings.renderSelectionBoxesIgnoreDepth.value);
            }
        }

        if (settings.highwayRenderBuildingArea.value) {
            highwayContext.scanner().renderLockBuilding().lock();
            BlockStateInterface bsi = new BlockStateInterface(BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext());
            highwayContext.scanner().renderBlocksBuilding().forEach((pos, color) -> {
                BufferBuilder bufferBuilder = IRenderer.startLines(color);

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
                    IRenderer.emitAABB(bufferBuilder, event.getModelViewStack(), toDraw, .002D, 2);

                    IRenderer.endLines(bufferBuilder, settings.renderSelectionBoxesIgnoreDepth.value);
                }
            });
            highwayContext.scanner().renderLockBuilding().unlock();
        }
    }

    @Override
    public void onSendPacket(PacketEvent event) {
        if (event.getState() != EventState.PRE) {
            return;
        }
        if (event.getPacket() instanceof ServerboundUseItemOnPacket use) {
            highwayContext.sync().noteUseItemOn(use.getHitResult().getBlockPos());
        }
        setbacks.noteSent(event.getPacket());
    }

    @Override
    public void onReceivePacket(PacketEvent event) {
        if (event.getState() == EventState.POST) {
            // Runs on the netty thread, so only flags are written here.
            if (event.getPacket() instanceof ClientboundContainerSetContentPacket contents) {
                highwayContext.sync().noteContainerSync(contents.containerId());
            } else if (event.getPacket() instanceof ClientboundLoginPacket
                    || event.getPacket() instanceof ClientboundRespawnPacket) {
                highwayContext.sync().noteInventoryDesynced();
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