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

package baritone.process;

import baritone.Baritone;
import baritone.api.behavior.INetherHighwayBuilderBehavior;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.movement.IMovement;
import baritone.api.process.IBuilderProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.schematic.*;
import baritone.api.schematic.format.ISchematicFormat;
import baritone.api.utils.*;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.behavior.LookBehavior;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.Moves;
import baritone.pathing.path.PathExecutor;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockBreakHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.DoubleMine;
import baritone.utils.EntityHole;
import baritone.utils.PathingCommandContext;
import baritone.utils.PendingPlaces;
import baritone.utils.PlaceRateLimiter;
import baritone.utils.accessor.IClientLevel;
import baritone.utils.accessor.IPlayerControllerMP;
import baritone.utils.schematic.MapArtSchematic;
import baritone.utils.schematic.SchematicSystem;
import baritone.utils.schematic.SelectionSchematic;
import baritone.utils.schematic.litematica.LitematicaHelper;
import baritone.utils.schematic.schematica.SchematicaHelper;
import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.util.Tuple;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

public final class BuilderProcess extends BaritoneProcessHelper implements IBuilderProcess {

    private static final Set<Property<?>> ORIENTATION_PROPS =
            ImmutableSet.of(
                    RotatedPillarBlock.AXIS, HorizontalDirectionalBlock.FACING,
                    StairBlock.FACING, StairBlock.HALF, StairBlock.SHAPE,
                    PipeBlock.NORTH, PipeBlock.EAST, PipeBlock.SOUTH, PipeBlock.WEST, PipeBlock.UP,
                    TrapDoorBlock.OPEN, TrapDoorBlock.HALF
            );

    private HashSet<BetterBlockPos> incorrectPositions;
    private LongOpenHashSet observedCompleted; // positions that are completed even if they're out of render distance and we can't make sure right now
    private String name;
    private ISchematic realSchematic;
    private ISchematic schematic;
    private Vec3i origin;
    private int ticks;
    private boolean paused;
    private int layer;
    private int numRepeats;
    private List<BlockState> approxPlaceable;
    /**
     * The block last placed from each hotbar slot. When a slot runs dry, the server puts the next
     * pickup in it, since it's the first empty one. Refilling it anyway, rather than the next empty
     * slot, keeps each block where it was instead of moving one slot along every stack.
     */
    private final Item[] placedFrom = new Item[9];
    /** Cells of {@link #entityHole} still to dig. */
    private Set<BetterBlockPos> toBreakEntity = new LinkedHashSet<>();
    /**
     * Every cell of the holes entities are being dropped through, dug out or not: nothing gets
     * placed in them, or the support we just dug comes straight back and gets dug out again.
     */
    private final Set<BetterBlockPos> entityHole = new HashSet<>();
    /**
     * The columns of {@link #entityHole}, as {@code longHash(x, 0, z)}, and the level we dig them out from. Replaced,
     * never changed, since the goals that read them do it from the path search's thread.
     */
    private LongSet entityHoleColumns = LongSets.EMPTY_SET;
    private int entityHoleDigY;
    /** Cells missing a block that an entity is standing in. */
    private final Set<BetterBlockPos> entityBlocked = new HashSet<>();
    /** The ones of those a boat is in; the highway's BoatRemoval deals with boats. */
    private final Set<BetterBlockPos> boatBlocked = new HashSet<>();
    public int stopAtHeight = 0;
    /**
     * Positions completed within the last {@link #COMPLETED_WATCH_TICKS}, kept even after the window
     * they were in has repeated away. Main thread only; calculation contexts get {@link #snapshotCompletedWatch}.
     */
    private final Long2ObjectOpenHashMap<CompletedWatch> completedWatch = new Long2ObjectOpenHashMap<>();
    private Long2ObjectMap<CompletedWatch> completedWatchSnapshot; // never mutated once handed out, null when stale
    private long builderTick;

    /**
     * How long a completed position stays watched for a server revert. Completion is the client's
     * own prediction, so a window repeats away the moment its last placement or break goes out,
     * and the server's verdict on that action arrives a round trip later. Without the watch a
     * revert there is outside the schematic and nothing looks at it again.
     */
    private static final int COMPLETED_WATCH_TICKS = 40;

    /** Where {@link #reachableTopFace} aims across a top face, along each axis. */
    private static final double[] TOP_FACE_POINTS = {0.15, 0.5, 0.85};

    /**
     * How far the aimed-at yaw may sit from the movement yaw before a pitch-only aim is hopeless.
     */
    private static final float PRINTER_MAX_PITCH_ONLY_YAW_DIFF = 25;

    /**
     * Consecutive placement ticks before the printer stops placing to let breaking catch up.
     */
    private static final int PRINTER_MAX_BREAK_STARVATION = 20;

    /**
     * How many nodes ahead on the current path the printer treats as off limits for breaking, and
     * how many movements ahead the builder won't place into.
     */
    private static final int PRINTER_PATH_PROTECT_LENGTH = 24;

    /**
     * Consecutive unverified no-rotation placements before the mode suppresses itself.
     */
    private static final int PRINTER_NO_ROTATE_MAX_STREAK = 60;

    /**
     * Ticks before a no-rotation probe placement is re-checked against the world.
     */
    private static final int PRINTER_NO_ROTATE_VERIFY_TICKS = 10;

    // How far a placement got before it stopped, for the idle reason: a target in reach that
    // needs breaking first, one with nothing to click within reach, one whose click was tried
    private static final int PRINTER_IDLE_NEEDS_BREAK = 1;
    private static final int PRINTER_IDLE_NO_FACE = 2;
    private static final int PRINTER_IDLE_TRIED = 3;

    // a place's use and swing, and a hotbar switch to its slot
    private static final int PRINTER_PLACE_PACKETS = 3;

    private int printerPlaceCooldown;
    private int printerBreakCooldown;
    private int printerBreakGrace;
    private boolean legitBreakSessionLastTick;
    /**
     * What the stationary break/place path did on the builder's last tick: whether its break target
     * needed more than one tick of digging, and the block it aimed at. Both are read by the highway
     * walk-creep, which ticks before any process does, so they describe the tick before this one.
     */
    private boolean legitSlowBreakLastTick;
    private BetterBlockPos legitAimTargetLastTick;
    private final DoubleMine doubleMine;
    private final PlaceRateLimiter placeRateLimiter = new PlaceRateLimiter();
    // why printerPlacesLeft allowed none this tick
    private String printerPlaceHold;
    // a place waited for room under printerPacketsPerTick, so the next tick's breaks leave it some
    private boolean printerPlaceWaitedForPackets;
    // this tick's places waited on the server's place limit or printerPacketsPerTick
    private boolean printerPlacesHeld;
    // the packets this tick's breaks leave for such a place
    private int printerPlaceReserve;
    // the hotbar slot the last place went out from, kept to while it lasts so places don't switch back and forth
    private int lastPlaceSlot = -1;
    private int printerBreakStarvation;
    /**
     * Snapshot of {@link LookBehavior#isMovementInputForced()} taken before the forced inputs are
     * cleared for this tick, so it lines up with the impulses the last physics step actually used.
     */
    private boolean printerMovementForced;
    private int printerNoRotateStreak;
    private BetterBlockPos printerNoRotateVerifyPos;
    private int printerNoRotateVerifyAge;
    private boolean printerNoRotateSuppressed;
    private int printerNoRotateSuppressedTicks;
    /**
     * Why the printer placed nothing on the builder's last tick, for the highway's nhwstatus. Null
     * when it placed.
     */
    private String printerIdleReason;
    // set once the last tick's report is read, so a tick the builder didn't run at all can be told apart
    private boolean printerReported = true;
    private int printerPlacedSinceReport;
    private int printerRevertsSinceReport;
    private PendingPlaces.Unanswered printerUnhandledSinceReport = PendingPlaces.Unanswered.NONE;
    // the printerMultiPlace the limiter learned under
    private int printerMultiPlaceSeen = 1;
    // the furthest a placement got on this tick's fire before it stopped; the reason kept is that one's
    private int printerIdleRank;
    // the builder's last tick left the printer idle only for want of a face to click from here
    private boolean printerOutOfReach;
    private String printerPlaceFailure;
    // what last kept restockHotbarEarly from moving, so a hotbar that ran dry anyway says why; null once one went through
    private String restockEarlyBlocker;

    private record PrinterAction(boolean placed, boolean broke) {

        private static final PrinterAction NONE = new PrinterAction(false, false);

        boolean acted() {
            return placed || broke;
        }
    }

    /**
     * What the printer did on one builder tick: the blocks it placed, or if none why not, and how
     * many recently completed positions were seen undone again since the last report.
     *
     * @param unhandled         places the server never handled
     * @param placeWindowMillis the window printerMultiPlace keeps at most the server's limit in
     * @param onePlaceATick     printerMultiPlace gave up on placing more than one a tick
     * @param setbackBackOffs   how many times since the last report set-backs held placing to one a tick
     * @param setbackBackOff    whether set-backs hold placing to one a tick now
     */
    public record PrinterReport(int placed, String idleReason, int reverts, PendingPlaces.Unanswered unhandled,
                                int placeWindowMillis, boolean onePlaceATick, int setbackBackOffs, boolean setbackBackOff) {}

    /**
     * A recently completed position and the schematic and origin of the window it was completed
     * in, so it is judged exactly as it was there.
     */
    private static final class CompletedWatch {

        private final BetterBlockPos pos;
        private final ISchematic schematic;
        private final int originX;
        private final int originY;
        private final int originZ;
        private long completedTick; // main thread only, calculation threads only read the window

        private CompletedWatch(BetterBlockPos pos, ISchematic schematic, int originX, int originY, int originZ, long completedTick) {
            this.pos = pos;
            this.schematic = schematic;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.completedTick = completedTick;
        }
    }

    public BuilderProcess(Baritone baritone) {
        super(baritone);
        doubleMine = new DoubleMine(ctx, baritone.getServerDigSpeed());
        baritone.getGameEventHandler().registerEventListener(doubleMine);
        baritone.getGameEventHandler().registerEventListener(placeRateLimiter);
    }

    /** The substitute/mirror/rotate/skip-blocks wrappers every schematic handed to the builder gets. */
    private ISchematic wrapForBuild(ISchematic raw) {
        ISchematic wrapped = raw;
        if (!Baritone.settings().buildSubstitutes.value.isEmpty()) {
            wrapped = new SubstituteSchematic(wrapped, Baritone.settings().buildSubstitutes.value);
        }
        if (Baritone.settings().buildSchematicMirror.value != net.minecraft.world.level.block.Mirror.NONE) {
            wrapped = new MirroredSchematic(wrapped, Baritone.settings().buildSchematicMirror.value);
        }
        if (Baritone.settings().buildSchematicRotation.value != net.minecraft.world.level.block.Rotation.NONE) {
            wrapped = new RotatedSchematic(wrapped, Baritone.settings().buildSchematicRotation.value);
        }
        // TODO this preserves the old behavior, but maybe we should bake the setting value right here
        return new MaskSchematic(wrapped) {
            @Override
            public boolean partOfMask(int x, int y, int z, BlockState current) {
                // partOfMask is only called inside the schematic so desiredState is not null
                return !Baritone.settings().buildSkipBlocks.value.contains(this.desiredState(x, y, z, current, Collections.emptyList()).getBlock());
            }
        };
    }

    @Override
    public void build(String name, ISchematic schematic, Vec3i origin) {
        this.name = name;
        this.realSchematic = null;
        boolean buildingSelectionSchematic = schematic instanceof SelectionSchematic;
        this.schematic = wrapForBuild(schematic);
        int x = origin.getX();
        int y = origin.getY();
        int z = origin.getZ();
        if (Baritone.settings().schematicOrientationX.value) {
            x += schematic.widthX();
        }
        if (Baritone.settings().schematicOrientationY.value) {
            y += schematic.heightY();
        }
        if (Baritone.settings().schematicOrientationZ.value) {
            z += schematic.lengthZ();
        }
        this.origin = new Vec3i(x, y, z);
        this.paused = false;
        this.layer = Baritone.settings().startAtLayer.value;
        this.stopAtHeight = schematic.heightY();
        if (Baritone.settings().buildOnlySelection.value && buildingSelectionSchematic) {  // currently redundant but safer maybe
            if (baritone.getSelectionManager().getSelections().length == 0) {
                logDirect("Poor little kitten forgot to set a selection while BuildOnlySelection is true");
                this.stopAtHeight = 0;
            } else if (Baritone.settings().buildInLayers.value) {
                OptionalInt minim = Stream.of(baritone.getSelectionManager().getSelections()).mapToInt(sel -> sel.min().y).min();
                OptionalInt maxim = Stream.of(baritone.getSelectionManager().getSelections()).mapToInt(sel -> sel.max().y).max();
                if (minim.isPresent() && maxim.isPresent()) {
                    int startAtHeight = Baritone.settings().layerOrder.value ? y + schematic.heightY() - maxim.getAsInt() : minim.getAsInt() - y;
                    this.stopAtHeight = (Baritone.settings().layerOrder.value ? y + schematic.heightY() - minim.getAsInt() : maxim.getAsInt() - y) + 1;
                    this.layer = Math.max(this.layer, startAtHeight / Baritone.settings().layerHeight.value);  // startAtLayer or startAtHeight, whichever is highest
                    logDebug(String.format("Schematic starts at y=%s with height %s", y, schematic.heightY()));
                    logDebug(String.format("Selection starts at y=%s and ends at y=%s", minim.getAsInt(), maxim.getAsInt()));
                    logDebug(String.format("Considering relevant height %s - %s", startAtHeight, this.stopAtHeight));
                }
            }
        }

        this.numRepeats = 0;
        this.observedCompleted = new LongOpenHashSet();
        this.incorrectPositions = null;
        clearEntityHoles();
        clearCompletedWatch();
        printerResetNoRotateDetection();
    }

    public void resume() {
        paused = false;
    }

    public void pause() {
        paused = true;
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public boolean isBreakingInPlace() {
        return legitSlowBreakLastTick;
    }

    /**
     * @return what the printer did on the builder's last tick, or null if the builder hasn't
     * ticked since this was last asked
     */
    public PrinterReport consumePrinterReport() {
        if (printerReported) {
            return null;
        }
        PrinterReport report = new PrinterReport(printerPlacedSinceReport, printerIdleReason, printerRevertsSinceReport,
                printerUnhandledSinceReport, placeRateLimiter.windowMillis(), placeRateLimiter.multiPlaceGivenUp(),
                placeRateLimiter.takeBackOffsStarted(), placeRateLimiter.backOffSecondsLeft() > 0);
        printerReported = true;
        printerPlacedSinceReport = 0;
        printerRevertsSinceReport = 0;
        printerUnhandledSinceReport = PendingPlaces.Unanswered.NONE;
        return report;
    }

    /**
     * Whether the printer, on the builder's last tick, had places to spare and work in reach, but
     * nothing next to any of it that the server takes a click on from where we stand. False if the
     * builder hasn't ticked since this was last asked.
     */
    public boolean consumePrinterOutOfReach() {
        boolean outOfReach = printerOutOfReach;
        printerOutOfReach = false;
        return outOfReach;
    }

    @Override
    public BlockPos stationaryAimTarget() {
        return legitAimTargetLastTick;
    }

    @Override
    public boolean build(String name, File schematic, Vec3i origin) {
        Optional<ISchematicFormat> format = SchematicSystem.INSTANCE.getByFile(schematic);
        if (!format.isPresent()) {
            return false;
        }
        IStaticSchematic parsed;
        try {
            parsed = format.get().parse(new FileInputStream(schematic));
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        ISchematic schem = applyMapArtAndSelection(origin, parsed);
        build(name, schem, origin);
        return true;
    }

    private ISchematic applyMapArtAndSelection(Vec3i origin, IStaticSchematic parsed) {
        ISchematic schematic = parsed;
        if (Baritone.settings().mapArtMode.value) {
            schematic = new MapArtSchematic(parsed);
        }
        if (Baritone.settings().buildOnlySelection.value) {
            schematic = new SelectionSchematic(schematic, origin, baritone.getSelectionManager().getSelections());
        }
        return schematic;
    }

    @Override
    public void buildOpenSchematic() {
        if (SchematicaHelper.isSchematicaPresent()) {
            Optional<Tuple<IStaticSchematic, BlockPos>> schematic = SchematicaHelper.getOpenSchematic();
            if (schematic.isPresent()) {
                IStaticSchematic raw = schematic.get().getA();
                BlockPos origin = schematic.get().getB();
                ISchematic schem = applyMapArtAndSelection(origin, raw);
                this.build(raw.toString(), schem, origin);
            } else {
                logDirect("No schematic currently open");
            }
        } else {
            logDirect("Schematica is not present");
        }
    }

    @Override
    public void buildOpenLitematic(int i) {
        if (LitematicaHelper.isLitematicaPresent()) {
            //if java.lang.NoSuchMethodError is thrown see comment in SchematicPlacementManager
            if (LitematicaHelper.hasLoadedSchematic(i)) {
                Tuple<IStaticSchematic, Vec3i> schematic = LitematicaHelper.getSchematic(i);
                Vec3i correctedOrigin = schematic.getB();
                ISchematic schematic2 = applyMapArtAndSelection(correctedOrigin, schematic.getA());
                build(schematic.getA().toString(), schematic2, correctedOrigin);
            } else {
                logDirect(String.format("List of placements has no entry %s", i + 1));
            }
        } else {
            logDirect("Litematica is not present");
        }
    }

    public void clearArea(BlockPos corner1, BlockPos corner2) {
        BlockPos origin = new BlockPos(Math.min(corner1.getX(), corner2.getX()), Math.min(corner1.getY(), corner2.getY()), Math.min(corner1.getZ(), corner2.getZ()));
        int widthX = Math.abs(corner1.getX() - corner2.getX()) + 1;
        int heightY = Math.abs(corner1.getY() - corner2.getY()) + 1;
        int lengthZ = Math.abs(corner1.getZ() - corner2.getZ()) + 1;
        build("clear area", new FillSchematic(widthX, heightY, lengthZ, Blocks.AIR.defaultBlockState()), origin);
    }

    @Override
    public List<BlockState> getApproxPlaceable() {
        return new ArrayList<>(approxPlaceable);
    }

    @Override
    public boolean isActive() {
        return schematic != null;
    }

    public BlockState placeAt(int x, int y, int z, BlockState current) {
        if (!isActive()) {
            return null;
        }
        if (!schematic.inSchematic(x - origin.getX(), y - origin.getY(), z - origin.getZ(), current)) {
            return null;
        }
        BlockState state = schematic.desiredState(x - origin.getX(), y - origin.getY(), z - origin.getZ(), current, this.approxPlaceable);
        if (state.getBlock() instanceof AirBlock) {
            return null;
        }
        return state;
    }

    private List<Optional<Tuple<BetterBlockPos, Rotation>>> toBreakNearPlayer(BuilderCalculationContext bcc) {
        BetterBlockPos center = ctx.playerFeet();
        BetterBlockPos pathStart = baritone.getPathingBehavior().pathStart();
        List<Optional<Tuple<BetterBlockPos, Rotation>>> toReturn = new ArrayList<>();

        for (BetterBlockPos pos : toBreakEntity) {
            if (pos.y == center.y - 1 && pos.x == pathStart.x && pos.z == pathStart.z) {
                continue; // same as below: a hole for something else is no reason to drop ourselves in it
            }
            BlockState curr = bcc.bsi.get0(pos);
            if (curr.getBlock() != Blocks.AIR && !(curr.getBlock() instanceof LiquidBlock) && !valid(curr, Blocks.AIR.defaultBlockState(), false)) {
                Optional<Rotation> rot = RotationUtils.reachable(ctx, pos, ctx.playerController().getBlockReachDistance());
                if (rot.isEmpty()) {
                    rot = reachableTopFace(pos, ctx.playerController().getBlockReachDistance());
                }
                rot.ifPresent(rotation -> toReturn.add(Optional.of(new Tuple<>(pos, rotation))));
            }
        }

        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = Baritone.settings().breakFromAbove.value ? -1 : 0; dy <= 5; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    if (dy == -1 && x == pathStart.x && z == pathStart.z) {
                        continue; // dont mine what we're supported by, but not directly standing on
                    }
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired == null) {
                        continue; // irrelevant
                    }
                    BlockState curr = bcc.bsi.get0(x, y, z);
                    if (!(curr.getBlock() instanceof AirBlock) && !(curr.getBlock() == Blocks.WATER || curr.getBlock() == Blocks.LAVA) && !valid(curr, desired, false)) {
                        Optional<Rotation> rot = RotationUtils.reachable(ctx, new BlockPos(x, y, z), ctx.playerController().getBlockReachDistance());
                        rot.ifPresent(rotation -> toReturn.add(Optional.of(new Tuple<>(new BetterBlockPos(x, y, z), rotation))));
                    }
                }
            }
        }
        return toReturn;
    }

    /**
     * Seen from beside a narrow hole, all that shows of its bottom layer is the far side of the top face
     * ({@link EntityHole#lowestDiggableLayer}). {@link RotationUtils#reachable} only aims at the middles of faces, and
     * from there the rim is in the way.
     */
    private Optional<Rotation> reachableTopFace(BetterBlockPos pos, double reach) {
        BlockPos block = new BlockPos(pos.x, pos.y, pos.z);
        for (double dx : TOP_FACE_POINTS) {
            for (double dz : TOP_FACE_POINTS) {
                Vec3 point = new Vec3(pos.x + dx, pos.y + 1, pos.z + dz);
                Optional<Rotation> rot = RotationUtils.reachableOffset(ctx, block, point, reach, false);
                if (rot.isPresent()) {
                    return rot;
                }
            }
        }
        return Optional.empty();
    }

    public static class Placement {

        private final int hotbarSelection;
        private final BlockPos placeAgainst;
        private final Direction side;
        private final Rotation rot;

        public Placement(int hotbarSelection, BlockPos placeAgainst, Direction side, Rotation rot) {
            this.hotbarSelection = hotbarSelection;
            this.placeAgainst = placeAgainst;
            this.side = side;
            this.rot = rot;
        }
    }

    private Collection<BetterBlockPos> currentStrip() {
        INetherHighwayBuilderBehavior highway = baritone.getNetherHighwayBuilderBehavior();
        if (!highway.isBuildingHighwayState() || !highway.needsLateralTraverses()) {
            return incorrectPositions;
        }
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (BetterBlockPos pos : incorrectPositions) {
            int column = highway.lateralColumn(pos.x, pos.z);
            lowest = Math.min(lowest, column);
            highest = Math.max(highest, column);
        }

        int here = highway.lateralColumn(ctx.playerFeet().x, ctx.playerFeet().z);
        int band = Baritone.settings().blockReachDistance.value.intValue();
        boolean upward;
        if (here - lowest <= band) {
            upward = true;
        } else if (highest - here <= band) {
            upward = false;
        } else {
            upward = here - lowest <= highest - here;
        }
        int cutoff = upward ? lowest + band : highest - band;
        List<BetterBlockPos> strip = new ArrayList<>();
        for (BetterBlockPos pos : incorrectPositions) {
            int column = highway.lateralColumn(pos.x, pos.z);
            if (upward ? column <= cutoff : column >= cutoff) {
                strip.add(pos);
            }
        }
        return strip;
    }

    private Optional<Placement> searchForPlacables(BuilderCalculationContext bcc, List<BlockState> desirableOnHotbar) {
        BetterBlockPos center = ctx.playerFeet();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -5; dy <= 1; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired == null) {
                        continue; // irrelevant
                    }
                    BlockState curr = bcc.bsi.get0(x, y, z);
                    if (MovementHelper.isReplaceable(x, y, z, curr, bcc.bsi) && !valid(curr, desired, false)) {
                        if (dy == 1 && bcc.bsi.get0(x, y + 1, z).getBlock() instanceof AirBlock) {
                            continue;
                        }
                        if (entityHole.contains(new BetterBlockPos(x, y, z))) {
                            continue; // kept open for an entity to drop through
                        }
                        desirableOnHotbar.add(desired);
                        if (pathAheadNeedsClear(new BlockPos(x, y, z))) {
                            continue;
                        }
                        Optional<Placement> opt = possibleToPlace(desired, x, y, z, bcc.bsi);
                        if (opt.isPresent()) {
                            return opt;
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    public boolean placementPlausible(BlockPos pos, BlockState state) {
        VoxelShape voxelshape = state.getCollisionShape(ctx.world(), pos);
        return voxelshape.isEmpty() || ctx.world().isUnobstructed(null, voxelshape.move(pos.getX(), pos.getY(), pos.getZ()));
    }

    private Optional<Placement> possibleToPlace(BlockState toPlace, int x, int y, int z, BlockStateInterface bsi) {
        // these two don't care which face we're placing against, but they were asked for every face
        // placementPlausible is an entity collision query, so that's up to six of those per position per tick
        // still only asked once we've found something solid to place against, like before
        boolean checkedPlaceable = false;
        for (Direction against : Direction.values()) {
            BetterBlockPos placeAgainstPos = new BetterBlockPos(x, y, z).relative(against);
            BlockState placeAgainstState = bsi.get0(placeAgainstPos);
            if (MovementHelper.isReplaceable(placeAgainstPos.x, placeAgainstPos.y, placeAgainstPos.z, placeAgainstState, bsi)) {
                continue;
            }
            if (!checkedPlaceable) {
                BlockPos pos = new BlockPos(x, y, z);
                if (!toPlace.canSurvive(ctx.world(), pos) || !placementPlausible(pos, toPlace)) {
                    return Optional.empty();
                }
                checkedPlaceable = true;
            }
            // Prevent BetterBlockPos from leaking into Minecraft's block entity map
            VoxelShape shape = placeAgainstState.getShape(ctx.world(), new BlockPos(placeAgainstPos.x, placeAgainstPos.y, placeAgainstPos.z));
            if (shape.isEmpty()) {
                continue;
            }
            AABB aabb = shape.bounds();
            for (Vec3 placementMultiplier : aabbSideMultipliers(against)) {
                double placeX = placeAgainstPos.x + aabb.minX * placementMultiplier.x + aabb.maxX * (1 - placementMultiplier.x);
                double placeY = placeAgainstPos.y + aabb.minY * placementMultiplier.y + aabb.maxY * (1 - placementMultiplier.y);
                double placeZ = placeAgainstPos.z + aabb.minZ * placementMultiplier.z + aabb.maxZ * (1 - placementMultiplier.z);
                Rotation rot = RotationUtils.calcRotationFromVec3d(RayTraceUtils.inferSneakingEyePosition(ctx.player()), new Vec3(placeX, placeY, placeZ), ctx.playerRotations());
                Rotation actualRot = baritone.getLookBehavior().getAimProcessor().peekRotation(rot);
                HitResult result = RayTraceUtils.rayTraceTowards(ctx.player(), actualRot, ctx.playerController().getBlockReachDistance(), true);
                if (result != null && result.getType() == HitResult.Type.BLOCK && ((BlockHitResult) result).getBlockPos().equals(placeAgainstPos) && ((BlockHitResult) result).getDirection() == against.getOpposite()) {
                    OptionalInt hotbar = hasAnyItemThatWouldPlace(toPlace, result, actualRot);
                    if (hotbar.isPresent()) {
                        return Optional.of(new Placement(hotbar.getAsInt(), placeAgainstPos, against.getOpposite(), rot));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private OptionalInt hasAnyItemThatWouldPlace(BlockState desired, HitResult result, Rotation rot) {
        // a slot a swap just filled sits out its settle window while another stack still places
        int unsettled = -1;
        for (int n = 0; n < 9; n++) {
            // the slot the last place went out from first: a stack refilled meanwhile in a lower slot
            // waits until this one runs out, instead of costing a hotbar switch each way
            int i = lastPlaceSlot < 0 ? n : n == 0 ? lastPlaceSlot : n <= lastPlaceSlot ? n - 1 : n;
            ItemStack stack = ctx.player().getInventory().getNonEquipmentItems().get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem) || !baritone.getInventoryBehavior().hotbarStackLasts(i, stack)) {
                continue;
            }
            float originalYaw = ctx.player().getYRot();
            float originalPitch = ctx.player().getXRot();
            // the state depends on the facing of the player sometimes
            ctx.player().setYRot(rot.getYaw());
            ctx.player().setXRot(rot.getPitch());
            BlockPlaceContext meme = new BlockPlaceContext(new UseOnContext(
                    ctx.world(),
                    ctx.player(),
                    InteractionHand.MAIN_HAND,
                    stack,
                    (BlockHitResult) result
            ) {}); // that {} gives us access to a protected constructor lmfao
            BlockState wouldBePlaced = ((BlockItem) stack.getItem()).getBlock().getStateForPlacement(meme);
            ctx.player().setYRot(originalYaw);
            ctx.player().setXRot(originalPitch);
            if (wouldBePlaced == null) {
                continue;
            }
            if (!meme.canPlace()) {
                continue;
            }
            if (valid(wouldBePlaced, desired, true)) {
                if (!printerSlotUnsettled(i)) {
                    return OptionalInt.of(i);
                }
                if (unsettled == -1) {
                    unsettled = i;
                }
            }
        }
        return unsettled == -1 ? OptionalInt.empty() : OptionalInt.of(unsettled);
    }

    private static Vec3[] aabbSideMultipliers(Direction side) {
        switch (side) {
            case UP:
                return new Vec3[]{new Vec3(0.5, 1, 0.5), new Vec3(0.1, 1, 0.5), new Vec3(0.9, 1, 0.5), new Vec3(0.5, 1, 0.1), new Vec3(0.5, 1, 0.9)};
            case DOWN:
                return new Vec3[]{new Vec3(0.5, 0, 0.5), new Vec3(0.1, 0, 0.5), new Vec3(0.9, 0, 0.5), new Vec3(0.5, 0, 0.1), new Vec3(0.5, 0, 0.9)};
            case NORTH:
            case SOUTH:
            case EAST:
            case WEST:
                double x = side.getStepX() == 0 ? 0.5 : (1 + side.getStepX()) / 2D;
                double z = side.getStepZ() == 0 ? 0.5 : (1 + side.getStepZ()) / 2D;
                return new Vec3[]{new Vec3(x, 0.25, z), new Vec3(x, 0.75, z)};
            default: // null
                throw new IllegalStateException("Unexpected side " + side);
        }
    }

    /**
     * Printer: act on whatever the rotation that was last sent to the server can already justify
     * (fire), then steer the next rotation toward the closest outstanding work (aim). Firing
     * raytraces the server-visible rotation, so the clicked block and cursor come from a hit the
     * server can reproduce.
     *
     * @return the actions performed this tick, if any
     */
    private PrinterAction printerTick(BuilderCalculationContext bcc, boolean mayPlace) {
        PendingPlaces.Unanswered unhandled = ((IClientLevel) ctx.world()).getPendingPlaces().takeUnanswered();
        printerUnhandledSinceReport = printerUnhandledSinceReport.plus(unhandled);
        int multiPlace = Math.max(1, Baritone.settings().printerMultiPlace.value);
        if (multiPlace != printerMultiPlaceSeen) {
            printerMultiPlaceSeen = multiPlace;
            placeRateLimiter.retryMultiPlace();
        }
        if (multiPlace > 1) {
            // at one a tick there's nothing to learn, and refusals of other uses would only mislead it
            placeRateLimiter.noteUnhandled(unhandled);
        }
        if (printerPlaceCooldown > 0) {
            printerPlaceCooldown--;
        }
        if (printerBreakCooldown > 0) {
            printerBreakCooldown--;
        }
        if (printerBreakGrace > 0) {
            printerBreakGrace--;
        }
        printerPlaceReserve = printerPlaceWaitedForPackets ? PRINTER_PLACE_PACKETS : 0;
        printerPlaceWaitedForPackets = false;
        if (Baritone.settings().printerNoRotate.value && printerNoRotateSuppressed) {
            int retryTicks = Baritone.settings().printerNoRotateRetryTicks.value;
            if (retryTicks > 0 && ++printerNoRotateSuppressedTicks >= retryTicks) {
                // transient causes (like a lag-spike setback window eating placements) clear on
                // their own, so probe again instead of staying suppressed for the whole build
                logDebug("Retrying no-rotation printing after suppression");
                printerResetNoRotateDetection();
            }
        }
        if (Baritone.settings().printerNoRotate.value && !printerNoRotateSuppressed) {
            printerTickNoRotateVerify(bcc); // may rescue the streak before the check below
            if (printerNoRotateStreak >= PRINTER_NO_ROTATE_MAX_STREAK) {
                logDirect("Server keeps reverting no-rotation placements. Printer falling back to rotation-based mode.");
                printerNoRotateSuppressed = true;
                printerNoRotateStreak = 0;
                printerNoRotateVerifyPos = null;
                printerNoRotateSuppressedTicks = 0;
                // fall through to the rotation-based path this same tick
            } else {
                return printerFireNoRotate(bcc, mayPlace);
            }
        }
        PrinterAction action = printerFire(bcc, mayPlace);
        printerAim(bcc, printerMovementForced, mayPlace);
        return action;
    }

    /**
     * How many places this tick's fire may send: printerMultiPlace, but above one only as many as
     * the server's spam limit has room for, and one once the server turned down placing more. When
     * that's none, printerPlaceHold says why.
     */
    private int printerPlacesLeft(boolean mayPlace) {
        if (!mayPlace || printerPlaceCooldown > 0) {
            printerPlaceHold = "place delay";
            return 0;
        }
        int multiPlace = Math.max(1, Baritone.settings().printerMultiPlace.value);
        if (multiPlace == 1 || placeRateLimiter.multiPlaceGivenUp() || placeRateLimiter.backOffSecondsLeft() > 0) {
            return 1; // a place a tick stays under the limit by itself
        }
        int allowed = placeRateLimiter.placesAllowed();
        if (allowed <= 0) {
            printerPlaceHold = "held under the server's place limit";
            printerPlacesHeld = true;
            return 0;
        }
        return Math.min(multiPlace, allowed);
    }

    private PrinterAction printerFire(BuilderCalculationContext bcc, boolean mayPlace) {
        Rotation rot = baritone.getLookBehavior().getServerRotation().orElse(null);
        if (rot == null) {
            printerResetIdle("no rotation sent yet");
            return PrinterAction.NONE;
        }
        double reach = ctx.playerController().getBlockReachDistance();
        Vec3 predictedEye = ctx.player().getEyePosition(1.0f).add(ctx.player().getDeltaMovement());
        int breaksLeft = printerBreakCooldown <= 0 ? Math.max(1, Baritone.settings().multiBreak.value) : 0;
        int placesLeft = printerPlacesLeft(mayPlace);
        printerResetIdle(placesLeft > 0 ? "rotation not on anything to place" : printerPlaceHold);
        boolean placed = false;
        boolean broke = false;
        int placeSlot = -1;
        while (breaksLeft > 0 || placesLeft > 0) {
            HitResult hit = RayTraceUtils.rayTraceTowards(ctx.player(), rot, reach, false);
            if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) {
                break;
            }
            BetterBlockPos clicked = BetterBlockPos.from(bhr.getBlockPos());
            // the server re-checks the rotation against the movement packet that follows the
            // action, so the ray has to still reach the cell from where we'll be next tick
            if (!printerRayIntersectsCell(predictedEye, rot, clicked, reach)) {
                break;
            }
            int spent = breaksLeft > 0 && !placed ? printerTryBreak(bcc, bhr, clicked, breaksLeft, !broke) : 0;
            if (spent > 0) {
                // the block is gone client-side, the same ray now continues to whatever is behind it
                broke = true;
                breaksLeft -= spent;
                continue;
            }
            int usedSlot = placesLeft > 0 ? printerTryPlace(bcc, bhr, clicked, rot, placed ? placeSlot : -1, false) : -1;
            if (usedSlot >= 0) {
                placed = true;
                placeSlot = usedSlot;
                placesLeft--;
                continue;
            }
            if (placesLeft > 0) {
                printerNoteIdle(PRINTER_IDLE_TRIED, printerPlaceFailure);
            }
            break;
        }
        return printerFinish(placed, broke);
    }

    private PrinterAction printerFinish(boolean placed, boolean broke) {
        if (broke) {
            printerBreakCooldown = Math.max(1, Baritone.settings().printerBreakDelay.value);
        }
        if (placed) {
            printerPlaceCooldown = Math.max(1, Baritone.settings().printerPlaceDelay.value);
        }
        if (placed || broke) {
            baritone.getInputOverrideHandler().suppressClicksThisTick();
        }
        return new PrinterAction(placed, broke);
    }

    private PrinterAction printerFireNoRotate(BuilderCalculationContext bcc, boolean mayPlace) {
        double reach = ctx.playerController().getBlockReachDistance();
        double reachSq = reach * reach;
        Vec3 eye = ctx.player().getEyePosition(1.0f);
        Vec3 predictedEye = eye.add(ctx.player().getDeltaMovement());
        Vec3 feet = ctx.player().position();
        Vec3 predictedFeet = feet.add(ctx.player().getDeltaMovement());
        // only used to predict getStateForPlacement orientation for directional blocks
        Rotation rot = baritone.getLookBehavior().getServerRotation().orElse(ctx.playerRotations());
        int breaksLeft = printerBreakCooldown <= 0 ? Math.max(1, Baritone.settings().multiBreak.value) : 0;
        int placesLeft = printerPlacesLeft(mayPlace);

        List<BetterBlockPos> candidates = printerCandidates();
        printerResetIdle(placesLeft <= 0 ? printerPlaceHold : candidates.isEmpty() ? "nothing left to build" : "nothing within reach");
        double maxDistSq = (reach + 1) * (reach + 1); // same block-center slack as printerAim
        candidates.removeIf(pos -> eye.distanceToSqr(VecUtils.getBlockPosCenter(pos)) > maxDistSq);
        candidates.sort(Comparator.comparingDouble(pos -> eye.distanceToSqr(VecUtils.getBlockPosCenter(pos))));

        boolean placed = false;
        boolean broke = false;
        int placeSlot = -1;

        // break pass first; printerTryBreak re-checks every gate (breakAllowed, breakSafe,
        // wrong-block, instant-only)
        for (BetterBlockPos pos : candidates) {
            if (breaksLeft <= 0) {
                break;
            }
            Vec3 center = VecUtils.getBlockPosCenter(pos);
            Direction face = Direction.getApproximateNearest(eye.subtract(center)); // face pointing back at the eye
            AABB cell = new AABB(pos);
            if (cell.distanceToSqr(eye) > reachSq || cell.distanceToSqr(predictedEye) > reachSq) {
                continue;
            }
            Vec3 hitVec = center.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            BlockHitResult bhr = new BlockHitResult(hitVec, face, new BlockPos(pos.x, pos.y, pos.z), false);
            int spent = printerTryBreak(bcc, bhr, pos, breaksLeft, !broke);
            if (spent > 0) {
                broke = true;
                breaksLeft -= spent;
                // candidate stays: the place pass may fill this same cell this tick
            }
        }

        // place pass
        for (BetterBlockPos pos : printerPlaceOrder(candidates)) {
            if (placesLeft <= 0) {
                break;
            }
            BlockState curr = bcc.bsi.get0(pos);
            if (!(curr.getBlock() instanceof AirBlock) && !(curr.getBlock() instanceof LiquidBlock)
                    && !MovementHelper.isReplaceable(pos.x, pos.y, pos.z, curr, bcc.bsi)) {
                printerNoteIdle(PRINTER_IDLE_NEEDS_BREAK, "only blocks to break within reach");
                continue; // needs breaking, not placing
            }
            printerNoteIdle(PRINTER_IDLE_NO_FACE, "no face to click within reach");
            int usedSlot = -1;
            for (Direction d : Direction.values()) { // DOWN first: click the UP face of the support below
                BetterBlockPos neighbor = pos.relative(d);
                if (!MovementHelper.canPlaceAgainst(bcc.bsi, neighbor)) {
                    continue;
                }
                usedSlot = printerTryPlaceFrom(bcc, neighbor, d.getOpposite(), false, feet, predictedFeet, reachSq, rot, placed ? placeSlot : -1);
                if (usedSlot >= 0) {
                    break;
                }
            }
            if (usedSlot < 0 && Baritone.settings().printerAirPlace.value) {
                // nothing next to it the server takes a click on from here, so click the cell itself
                for (Direction face : Direction.values()) {
                    usedSlot = printerTryPlaceFrom(bcc, pos, face, true, feet, predictedFeet, reachSq, rot, placed ? placeSlot : -1);
                    if (usedSlot >= 0) {
                        break;
                    }
                }
            }
            if (usedSlot >= 0) {
                placed = true;
                placeSlot = usedSlot;
                placesLeft--;
                printerNoteNoRotatePlace(pos);
            }
        }
        return printerFinish(placed, broke);
    }

    /**
     * A no-rotation place by a click on {@code face} of {@code clicked}, if the server takes it from
     * where we are.
     *
     * @param air {@code clicked} is the empty cell to place in, rather than the block to place
     *            against
     * @return the hotbar slot used, or -1 if nothing was placed
     */
    private int printerTryPlaceFrom(BuilderCalculationContext bcc, BetterBlockPos clicked, Direction face, boolean air,
                                    Vec3 feet, Vec3 predictedFeet, double reachSq, Rotation rot, int requiredSlot) {
        if (!printerPlaceReaches(clicked, face, feet, reachSq) || !printerPlaceReaches(clicked, face, predictedFeet, reachSq)) {
            return -1;
        }
        Vec3 hitVec = VecUtils.getBlockPosCenter(clicked)
                .add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        // plain BlockPos on purpose: the hit result flows into vanilla world mutation, and
        // BetterBlockPos's different hashCode must not leak into the block entity map
        BlockHitResult bhr = new BlockHitResult(hitVec, face, new BlockPos(clicked.x, clicked.y, clicked.z), false);
        int usedSlot = printerTryPlace(bcc, bhr, clicked, rot, requiredSlot, air);
        if (usedSlot < 0) {
            printerNoteIdle(PRINTER_IDLE_TRIED, printerPlaceFailure);
        }
        return usedSlot;
    }

    /**
     * Whether the server takes a no-rotation place against {@code face} of {@code clicked} from a
     * player standing at {@code feet}. Grim's FarPlace measures to the closest point of the clicked
     * block from every eye height the player could have, crouching included, whatever its pose. That
     * is the reach the stationary place path gets by crouching for real, so measuring the face centre
     * from the standing eye left a ring of cells just past the printer that the stationary path
     * stopped and crouched for every time the printer ran out of work in reach. Grim's PositionPlace
     * also wants the clicked face turned towards those eyes, and measured to the block rather than
     * the face, a face on its far side would pass the reach check as easily as a near one.
     */
    private boolean printerPlaceReaches(BetterBlockPos clicked, Direction face, Vec3 feet, double reachSq) {
        double standingEye = feet.y + ctx.player().getEyeHeight(Pose.STANDING);
        double crouchingEye = feet.y + ctx.player().getEyeHeight(Pose.CROUCHING);
        boolean facing = switch (face) {
            case UP -> standingEye >= clicked.y + 1;
            case DOWN -> crouchingEye <= clicked.y;
            case EAST -> feet.x >= clicked.x + 1;
            case WEST -> feet.x <= clicked.x;
            case SOUTH -> feet.z >= clicked.z + 1;
            case NORTH -> feet.z <= clicked.z;
        };
        AABB box = new AABB(clicked.x, clicked.y, clicked.z, clicked.x + 1, clicked.y + 1, clicked.z + 1);
        return facing && (box.distanceToSqr(new Vec3(feet.x, standingEye, feet.z)) <= reachSq
                || box.distanceToSqr(new Vec3(feet.x, crouchingEye, feet.z)) <= reachSq);
    }

    private void printerResetIdle(String reason) {
        printerIdleRank = 0;
        printerIdleReason = reason;
    }

    /**
     * Keeps the reason from the attempt that got furthest, and of those the first in the order the
     * printer tries them: that's the one it would have placed.
     */
    private void printerNoteIdle(int rank, String reason) {
        if (rank > printerIdleRank) {
            printerIdleRank = rank;
            printerIdleReason = reason;
        }
    }

    private void printerNoteNoRotatePlace(BetterBlockPos target) {
        printerNoRotateStreak++;
        if (printerNoRotateVerifyPos == null) {
            printerNoRotateVerifyPos = target;
            printerNoRotateVerifyAge = 0;
        }
    }

    private void printerTickNoRotateVerify(BuilderCalculationContext bcc) {
        if (printerNoRotateVerifyPos == null || ++printerNoRotateVerifyAge < PRINTER_NO_ROTATE_VERIFY_TICKS) {
            return;
        }
        BetterBlockPos probe = printerNoRotateVerifyPos;
        BlockState curr = bcc.bsi.get0(probe);
        BlockState desired = bcc.getSchematic(probe.x, probe.y, probe.z, curr);
        if (valid(curr, desired, false)) { // a probe that scrolled out of the schematic can't count as a rejection
            printerNoRotateStreak = 0;
        }
        printerNoRotateVerifyPos = null;
    }

    private void printerResetNoRotateDetection() {
        printerNoRotateSuppressed = false;
        printerNoRotateStreak = 0;
        printerNoRotateVerifyPos = null;
        printerNoRotateVerifyAge = 0;
        printerNoRotateSuppressedTicks = 0;
    }

    private List<BetterBlockPos> printerCandidates() {
        if (toBreakEntity.isEmpty()) {
            return new ArrayList<>(incorrectPositions);
        }
        Set<BetterBlockPos> candidates = new LinkedHashSet<>(toBreakEntity);
        candidates.addAll(incorrectPositions);
        return new ArrayList<>(candidates);
    }

    /**
     * Nearest first, but on a highway the cells beside the walk lane before the lane's own. A rail
     * over open air only goes against the rail before it, from the few blocks past that rail's face,
     * and the creep doesn't wait for rails. Nearest first, the floor ahead always came first and the
     * rails fell out of reach behind us, for the builder to path back to.
     */
    private List<BetterBlockPos> printerPlaceOrder(List<BetterBlockPos> nearestFirst) {
        INetherHighwayBuilderBehavior highway = baritone.getNetherHighwayBuilderBehavior();
        if (!highway.isBuildingHighwayState()) {
            return nearestFirst;
        }
        List<BetterBlockPos> order = new ArrayList<>(nearestFirst);
        order.sort(Comparator.comparing(pos -> highway.inWalkLane(pos.x, pos.z))); // stable, so still nearest first within each
        return order;
    }

    private BlockState printerDesired(BuilderCalculationContext bcc, BetterBlockPos pos, BlockState curr) {
        if (entityHole.contains(pos)) {
            BlockState air = Blocks.AIR.defaultBlockState();
            // the same emptiness test toBreakNearPlayer uses, so both paths agree on what's a target;
            // a cell already dug out has nothing left to do until its entity is gone
            return valid(curr, air, false) ? null : air;
        }
        if (!incorrectPositions.contains(pos)) {
            return null;
        }
        BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, curr);
        return desired == null || valid(curr, desired, false) ? null : desired;
    }

    /**
     * @param breaksLeft dig packets {@link baritone.api.Settings#multiBreak} still allows this tick
     * @param first      whether nothing was broken yet this tick; the first break always goes
     *                   through, even a near-instant one over a budget of 1
     * @return the dig packets the break spent, including aborting a vanilla dig in progress, or 0
     * if nothing was broken
     */
    private int printerTryBreak(BuilderCalculationContext bcc, BlockHitResult bhr, BetterBlockPos clicked, int breaksLeft, boolean first) {
        if (!printerBreakAllowed() || !printerBreakSafe(clicked)) {
            return 0;
        }
        BlockState state = bcc.bsi.get0(clicked);
        if (state.getBlock() instanceof AirBlock || state.getBlock() instanceof LiquidBlock
                || MovementHelper.isReplaceable(clicked.x, clicked.y, clicked.z, state, bcc.bsi)) {
            return 0;
        }
        if (printerDesired(bcc, clicked, state) == null) {
            return 0;
        }
        int packets = printerBreakPackets(printerBreakProgress(state, clicked));
        if (packets == 0) {
            return 0; // only single-tick breaks can be done without stopping
        }
        if (packets > breaksLeft && !first) {
            return 0;
        }
        int previous = ctx.player().getInventory().getSelectedSlot();
        MovementHelper.switchToBestToolFor(ctx, state);
        if (printerSlotUnsettled(ctx.player().getInventory().getSelectedSlot())) {
            ctx.player().getInventory().setSelectedSlot(previous);
            return 0;
        }
        // its dig packets, the abort below, the tick's one swing and a switch to the tool
        int sending = packets + (ctx.playerController().hasBrokenBlock() ? 0 : 1) + (first ? 1 : 0)
                + (ctx.player().getInventory().getSelectedSlot() != carriedSlot() ? 1 : 0);
        if (!printerPacketsFit(sending, printerPlaceReserve)) {
            ctx.player().getInventory().setSelectedSlot(previous);
            return 0;
        }
        // clickBlock doesn't sync the hotbar the way the use path does, so the tool we just
        // picked would otherwise reach the server a tick after the dig it applies to
        ctx.playerController().syncHeldItem();
        int spent = packets;
        if (!ctx.playerController().hasBrokenBlock()) {
            // the stationary path left a vanilla dig going. Abort it once here: an instant START
            // leaves vanilla's dig state set, so every later break this tick would abort it again
            ctx.playerController().resetBlockRemoving();
            spent++;
        }
        // plain BlockPos on purpose: the position flows into vanilla world mutation
        BlockPos pos = new BlockPos(clicked.x, clicked.y, clicked.z);
        // instant by the server's judgment, which the client's may not share yet
        boolean dug = packets == 1
                ? ctx.playerController().clickBlockInstant(pos, bhr.getDirection())
                : ctx.playerController().clickBlockFinishing(pos, bhr.getDirection());
        if (!dug) {
            return 0;
        }
        if (first) {
            // one swing covers every break this tick, like a held attack, and keeps the packet
            // count down
            ctx.player().swing(InteractionHand.MAIN_HAND);
        }
        ctx.playerController().setDestroyDelay(0);
        return spent;
    }

    /**
     * @param requiredSlot if >= 0, only place when this hotbar slot works: changing the selected
     *                     slot after a use packet within the same tick is not vanilla-plausible
     * @param air          the click is on the empty cell to place in, not on a block next to it
     * @return the hotbar slot used, or -1 if nothing was placed
     */
    private int printerTryPlace(BuilderCalculationContext bcc, BlockHitResult bhr, BetterBlockPos clicked, Rotation rot, int requiredSlot, boolean air) {
        // a click on a cell that can be replaced places in it, like on tall grass
        BetterBlockPos placeAt = air ? clicked : BetterBlockPos.from(clicked.relative(bhr.getDirection()));
        if (!incorrectPositions.contains(placeAt)) {
            return printerPlaceFailed("not a target");
        }
        if (entityHole.contains(placeAt)) {
            // being cleared for an entity; filling it back in would just fight the break
            return printerPlaceFailed("kept open for an entity");
        }
        if (pathAheadNeedsClear(placeAt)) {
            return printerPlaceFailed("the path needs it clear");
        }
        if (!air && (!MovementHelper.canPlaceAgainst(bcc.bsi, clicked) || printerAvoidClicking(bcc.bsi.get0(clicked)))) {
            return printerPlaceFailed("can't click what it's against");
        }
        BlockState curr = bcc.bsi.get0(placeAt);
        if (!MovementHelper.isReplaceable(placeAt.x, placeAt.y, placeAt.z, curr, bcc.bsi)) {
            return printerPlaceFailed("occupied");
        }
        BlockState desired = bcc.getSchematic(placeAt.x, placeAt.y, placeAt.z, curr);
        if (desired == null || desired.getBlock() instanceof AirBlock || valid(curr, desired, false)) {
            return printerPlaceFailed("already correct");
        }
        if (!desired.canSurvive(ctx.world(), placeAt) || !placementPlausible(placeAt, desired)) {
            return printerPlaceFailed("an entity is in the way");
        }
        OptionalInt slot = hasAnyItemThatWouldPlace(desired, bhr, rot);
        if (!slot.isPresent()) {
            // also a hotbar stack whose last items are all in flight
            if (approxPlaceable.subList(9, 36).stream().noneMatch(state -> valid(state, desired, true))) {
                return printerPlaceFailed("nothing on the hotbar to place, none in the inventory either");
            }
            return printerPlaceFailed(restockEarlyBlocker == null ? "nothing on the hotbar to place"
                    : "nothing on the hotbar to place, early restock: " + restockEarlyBlocker);
        }
        if (requiredSlot >= 0 && slot.getAsInt() != requiredSlot) {
            return printerPlaceFailed("needs another hotbar slot");
        }
        if (printerSlotUnsettled(slot.getAsInt())) {
            return printerPlaceFailed("hotbar slot settling after a swap");
        }
        if (!printerPacketsFit(PRINTER_PLACE_PACKETS - (slot.getAsInt() == carriedSlot() ? 1 : 0), 0)) {
            printerPlaceWaitedForPackets = true;
            printerPlacesHeld = true;
            return printerPlaceFailed("held under the per-tick packet limit");
        }
        ctx.player().getInventory().setSelectedSlot(slot.getAsInt());
        lastPlaceSlot = slot.getAsInt();
        placedFrom[slot.getAsInt()] = ctx.player().getMainHandItem().getItem();
        InteractionResult result = ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, bhr);
        if (!result.consumesAction()) {
            return printerPlaceFailed("click did nothing");
        }
        if (result == InteractionResult.SUCCESS) {
            // vanilla only swings on SUCCESS, not CONSUME
            ctx.player().swing(InteractionHand.MAIN_HAND);
        }
        if (requiredSlot >= 0) {
            placeRateLimiter.noteSecondPlace();
        }
        printerPlacedSinceReport++;
        return slot.getAsInt();
    }

    private int printerPlaceFailed(String reason) {
        printerPlaceFailure = reason;
        return -1;
    }

    /**
     * @param packets what a break or place would send
     * @param reserve what to leave for a place later in the tick
     * @return whether that fits under printerPacketsPerTick with what this tick already sent; the
     * tick's first action always does, unless room is being kept for a place
     */
    private boolean printerPacketsFit(int packets, int reserve) {
        int sent = baritone.getPacketTrace().actionsThisTick();
        return sent == 0 && reserve == 0 || sent + packets + reserve <= Baritone.settings().printerPacketsPerTick.value;
    }

    /**
     * @return the hotbar slot the server last heard was selected
     */
    private int carriedSlot() {
        return ((IPlayerControllerMP) ctx.minecraft().gameMode).getCarriedIndex();
    }

    /**
     * Picks the closest outstanding block that the printer could act on and requests the rotation
     * for it. While moving only the pitch may be steered, since the yaw has to stay with the
     * movement; when idle the rotation is free.
     */
    private void printerAim(BuilderCalculationContext bcc, boolean moving, boolean mayPlace) {
        LookBehavior look = baritone.getLookBehavior();
        double reach = ctx.playerController().getBlockReachDistance();
        Vec3 eye = ctx.player().getEyePosition(1.0f);
        Rotation current = ctx.playerRotations();
        float predictedYaw = look.getServerRotation().map(Rotation::getYaw).orElse(current.getYaw());
        boolean breakAllowed = printerBreakAllowed();
        List<BlockState> hotbar = approxPlaceable.subList(0, 9);

        List<BetterBlockPos> candidates = printerCandidates();
        // measured to the block center, so allow a block's bounding radius (sqrt(3)/2) of slack
        // before discarding it; the exact reach is enforced per hit point further down
        double maxDistSq = (reach + 1) * (reach + 1);
        candidates.removeIf(pos -> eye.distanceToSqr(VecUtils.getBlockPosCenter(pos)) > maxDistSq);
        candidates.sort(Comparator.comparingDouble(pos -> eye.distanceToSqr(VecUtils.getBlockPosCenter(pos))));

        for (BetterBlockPos pos : candidates) {
            BlockState curr = bcc.bsi.get0(pos);
            BlockState desired = printerDesired(bcc, pos, curr);
            if (desired == null) {
                continue;
            }
            if (!(curr.getBlock() instanceof AirBlock) && !(curr.getBlock() instanceof LiquidBlock)
                    && !MovementHelper.isReplaceable(pos.x, pos.y, pos.z, curr, bcc.bsi)) {
                // wrong solid block, needs to be broken first
                if (!breakAllowed || !printerBreakSafe(pos) || !printerCanInstaBreak(curr, pos)) {
                    continue;
                }
                Rotation aim = printerAimRotation(eye, VecUtils.getBlockPosCenter(pos), current, predictedYaw, moving);
                if (aim != null && printerAimHits(aim, reach, hr -> hr.getBlockPos().equals(pos))) {
                    look.updateSecondaryTarget(aim, moving);
                    return;
                }
                if (!moving) {
                    // center is occluded; look for any visible spot on the block
                    Optional<Rotation> reachable = RotationUtils.reachable(ctx.player(), pos, reach);
                    if (reachable.isPresent() && printerAimHits(reachable.get(), reach, hr -> hr.getBlockPos().equals(pos))) {
                        look.updateSecondaryTarget(reachable.get(), false);
                        return;
                    }
                }
            } else {
                // missing block, needs to be placed
                if (!mayPlace
                        || desired.getBlock() instanceof AirBlock
                        || !containsBlockState(hotbar, desired)
                        || !desired.canSurvive(ctx.world(), pos)
                        || !placementPlausible(pos, desired)
                        || pathAheadNeedsClear(pos)) {
                    continue;
                }
                for (Direction d : Direction.values()) { // same faces, same order, as possibleToPlace
                    BetterBlockPos clickPos = pos.relative(d);
                    if (!MovementHelper.canPlaceAgainst(bcc.bsi, clickPos) || printerAvoidClicking(bcc.bsi.get0(clickPos))) {
                        continue;
                    }
                    Direction face = d.getOpposite();
                    VoxelShape shape = bcc.bsi.get0(clickPos).getShape(ctx.world(), clickPos);
                    if (shape.isEmpty()) {
                        continue;
                    }
                    AABB aabb = shape.bounds();
                    for (Vec3 mult : aabbSideMultipliers(d)) {
                        double px = clickPos.x + aabb.minX * mult.x + aabb.maxX * (1 - mult.x);
                        double py = clickPos.y + aabb.minY * mult.y + aabb.maxY * (1 - mult.y);
                        double pz = clickPos.z + aabb.minZ * mult.z + aabb.maxZ * (1 - mult.z);
                        Vec3 point = new Vec3(px, py, pz);
                        if (eye.distanceToSqr(point) > reach * reach) {
                            continue;
                        }
                        Rotation aim = printerAimRotation(eye, point, current, predictedYaw, moving);
                        if (aim != null && printerAimHits(aim, reach, hr -> hr.getBlockPos().equals(clickPos) && hr.getDirection() == face)) {
                            look.updateSecondaryTarget(aim, moving);
                            return;
                        }
                    }
                }
            }
        }
    }

    /**
     * @param predictedYaw the yaw the movement is expected to hold, used for pitch-only aims. It's
     *                     only a prediction; {@link #printerFire} re-checks the rotation the
     *                     server actually received before anything is clicked.
     */
    private Rotation printerAimRotation(Vec3 eye, Vec3 point, Rotation current, float predictedYaw, boolean moving) {
        Rotation exact = RotationUtils.calcRotationFromVec3d(eye, point, current);
        if (!moving) {
            return exact;
        }
        if (Math.abs(Mth.degreesDifference(exact.getYaw(), predictedYaw)) > PRINTER_MAX_PITCH_ONLY_YAW_DIFF) {
            return null; // too far off the movement yaw for a pitch-only aim to ever hit
        }
        return new Rotation(predictedYaw, exact.getPitch());
    }

    private boolean printerAimHits(Rotation aim, double reach, Predicate<BlockHitResult> test) {
        // check the rotation as it would actually be sent (mouse-quantized + randomLooking)
        Rotation actual = baritone.getLookBehavior().getAimProcessor().peekRotation(aim);
        HitResult result = RayTraceUtils.rayTraceTowards(ctx.player(), actual, reach, false);
        return result instanceof BlockHitResult hr && result.getType() == HitResult.Type.BLOCK && test.test(hr);
    }

    /**
     * @return why the printer can't act right now, or null if it can
     */
    private String printerUnusableReason() {
        if (ctx.player() == null || ctx.minecraft().gameMode == null) {
            return "not in game";
        }
        if (!(ctx.minecraft().screen == null || ctx.minecraft().screen instanceof ChatScreen || ctx.minecraft().screen instanceof InventoryScreen || ctx.minecraft().screen instanceof PauseScreen)) {
            return "screen open";
        }
        if (ctx.minecraft().isPaused()) {
            return "game paused";
        }
        if (!ctx.player().containerMenu.getCarried().isEmpty()) {
            return "item on the cursor";
        }
        if (ctx.player().isUsingItem()) {
            return "using an item";
        }
        if (ctx.player().isHandsBusy()) {
            return "hands busy";
        }
        if (ctx.player().isFallFlying()) {
            return "flying";
        }
        if (ctx.player().isPassenger()) {
            return "riding";
        }
        return null;
    }

    /**
     * @return whether a hotbar swap is still unconfirmed. Not applicable when the builder isn't
     * allowed to touch the inventory, since nothing swaps and the counter never advances.
     */
    private boolean printerInventoryUnsettled() {
        return Baritone.settings().allowInventory.value
                && baritone.getInventoryBehavior().ticksSinceLastInventoryMove() < Baritone.settings().printerInventorySettleTicks.value;
    }

    /**
     * @return whether a swap that changed this hotbar slot is still unconfirmed, so the server may
     * hold something else there. Swaps that leave the slot alone don't hold it up.
     */
    private boolean printerSlotUnsettled(int slot) {
        return Baritone.settings().allowInventory.value
                && baritone.getInventoryBehavior().ticksSinceHotbarMove(slot) < Baritone.settings().printerInventorySettleTicks.value;
    }

    /**
     * @return the hotbar slots this item was last placed from, where a restock puts it back
     */
    private List<Integer> placedFromSlots(Item item) {
        List<Integer> homes = new ArrayList<>();
        for (int h = 0; h < 9; h++) {
            if (placedFrom[h] == item) {
                homes.add(h);
            }
        }
        return homes;
    }

    /**
     * Never dig out our own support or the floor the current path is about to walk on.
     */
    private boolean printerBreakSafe(BlockPos pos) {
        BetterBlockPos feet = ctx.playerFeet();
        if (Math.abs(pos.getX() - feet.x) <= 1 && Math.abs(pos.getZ() - feet.z) <= 1
                && pos.getY() <= feet.y - 1 && pos.getY() >= feet.y - 3) {
            return false;
        }
        PathExecutor current = baritone.getPathingBehavior().getCurrent();
        if (current != null && current.getPath() != null) {
            List<BetterBlockPos> positions = current.getPath().positions();
            int start = Math.max(0, current.getPosition());
            int end = Math.min(positions.size(), current.getPosition() + PRINTER_PATH_PROTECT_LENGTH);
            for (int i = start; i < end; i++) {
                BetterBlockPos p = positions.get(i);
                if (pos.equals(p.below())) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Whether the current path still has to move through this cell. Filling it makes the path
     * break it straight back out or fail, and under the highway it can seal the hole that a tunnel
     * beneath the road climbs out through, leaving us shut in below the road.
     */
    private boolean pathAheadNeedsClear(BlockPos pos) {
        PathExecutor current = baritone.getPathingBehavior().getCurrent();
        if (current == null || current.getPath() == null) {
            return false;
        }
        List<IMovement> movements = current.getPath().movements();
        int start = Math.max(0, current.getPosition());
        int end = Math.min(movements.size(), current.getPosition() + PRINTER_PATH_PROTECT_LENGTH);
        for (int i = start; i < end; i++) {
            // everything the movement needs clear: feet and head along the way, jump headroom
            for (BlockPos clear : ((Movement) movements.get(i)).toBreakAll()) {
                if (clear.equals(pos)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean printerBreakAllowed() {
        return Baritone.settings().printerBreak.value && printerBreakGrace <= 0 && ctx.player().onGround();
    }

    /**
     * @return whether the printer can break this block within a single tick with the tool
     * {@link MovementHelper#switchToBestToolFor} would pick, including the near-instant blocks
     * {@link baritone.api.Settings#printerBreakNearInstant} adds
     */
    private boolean printerCanInstaBreak(BlockState state, BlockPos pos) {
        return printerBreakPackets(printerBreakProgress(state, pos)) > 0;
    }

    /**
     * @return the dig packets the printer sends to break a block with this per-tick progress: a
     * START for an instant break, START and STOP for a near-instant one, or 0 if it can't break it
     * within a tick
     */
    private static int printerBreakPackets(float progress) {
        if (progress >= 1.0f) {
            return 1;
        }
        return Baritone.settings().printerBreakNearInstant.value && progress >= IPlayerController.SERVER_FINISH_DIG_PROGRESS ? 2 : 0;
    }

    /**
     * The break targets {@link DoubleMine} takes: everything that needs more than one tick, minus
     * the near-instant blocks a single finishing dig already handles.
     */
    private List<Tuple<BetterBlockPos, Rotation>> doubleMineCandidates(BuilderCalculationContext bcc, List<Optional<Tuple<BetterBlockPos, Rotation>>> toBreak) {
        List<Tuple<BetterBlockPos, Rotation>> slow = new ArrayList<>();
        for (Optional<Tuple<BetterBlockPos, Rotation>> info : toBreak) {
            if (info.isEmpty()) {
                continue;
            }
            BetterBlockPos pos = info.get().getA();
            float progress = printerBreakProgress(bcc.get(pos), pos);
            // a start the client's own judgment has instant clears the block in an anticheat's
            // copy of the world while the server is still digging it; the two agree again shortly
            if (progress > 0 && progress < IPlayerController.SERVER_FINISH_DIG_PROGRESS && !printerClientInstant(bcc.get(pos), pos)) {
                slow.add(info.get());
            }
        }
        return slow;
    }

    /**
     * Per-tick break progress with the tool {@link MovementHelper#switchToBestToolFor} would pick,
     * as the server judges a dig sent this tick, and instant only if the client's judgment agrees;
     * 0 while only the client's has it instant.
     * Probes with the same switch the dig itself performs, so aiming and firing can never disagree
     * about what is instant.
     */
    private float printerBreakProgress(BlockState state, BlockPos pos) {
        var inventory = ctx.player().getInventory();
        int previous = inventory.getSelectedSlot();
        MovementHelper.switchToBestToolFor(ctx, state);
        float progress = baritone.getServerDigSpeed().progress(state, pos);
        inventory.setSelectedSlot(previous);
        return progress;
    }

    /**
     * Whether the client's own judgment has the block instant with the tool
     * {@link MovementHelper#switchToBestToolFor} would pick, probing like printerBreakProgress
     */
    private boolean printerClientInstant(BlockState state, BlockPos pos) {
        var inventory = ctx.player().getInventory();
        int previous = inventory.getSelectedSlot();
        MovementHelper.switchToBestToolFor(ctx, state);
        boolean instant = baritone.getServerDigSpeed().clientInstant(state, pos);
        inventory.setSelectedSlot(previous);
        return instant;
    }

    /**
     * Blocks that would open a GUI or otherwise activate when right clicked; the legit place path
     * handles those by sneaking, the printer just leaves them alone.
     */
    private static boolean printerAvoidClicking(BlockState state) {
        Block block = state.getBlock();
        return block instanceof EntityBlock
                || block instanceof DoorBlock
                || block instanceof TrapDoorBlock
                || block instanceof FenceGateBlock
                || block instanceof ButtonBlock
                || block instanceof LeverBlock
                || block instanceof CraftingTableBlock
                || block instanceof AnvilBlock
                || block instanceof CakeBlock
                || block instanceof RepeaterBlock
                || block instanceof ComparatorBlock
                || block instanceof NoteBlock
                || block instanceof BedBlock;
    }

    private static boolean printerRayIntersectsCell(Vec3 eye, Rotation rotation, BlockPos cell, double range) {
        Vec3 direction = RotationUtils.calcLookDirectionFromRotation(rotation);
        return new AABB(cell).clip(eye, eye.add(direction.scale(range))).isPresent();
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        return onTick(calcFailed, isSafeToCancel, 0, null);
    }

    private PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel, int recursions, BuilderCalculationContext reuse) {
        if (recursions > 100) { // onTick calls itself, don't crash
            return new PathingCommand(null, PathingCommandType.SET_GOAL_AND_PATH);
        }
        approxPlaceable = approxPlaceable(36);
        if (baritone.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT)) {
            ticks = 5;
        } else {
            ticks--;
        }
        if (recursions == 0) {
            printerMovementForced = baritone.getLookBehavior().isMovementInputForced();
            builderTick++;
        }
        baritone.getInputOverrideHandler().clearAllKeys();
        if (paused) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (Baritone.settings().buildInLayers.value) {
            if (realSchematic == null) {
                realSchematic = schematic;
            }
            ISchematic realSchematic = this.realSchematic; // wrap this properly, dont just have the inner class refer to the builderprocess.this
            int minYInclusive;
            int maxYInclusive;
            // layer = 0 should be nothing
            // layer = realSchematic.heightY() should be everything
            if (Baritone.settings().layerOrder.value) { // top to bottom
                maxYInclusive = realSchematic.heightY() - 1;
                minYInclusive = realSchematic.heightY() - layer * Baritone.settings().layerHeight.value;
            } else {
                maxYInclusive = layer * Baritone.settings().layerHeight.value - 1;
                minYInclusive = 0;
            }
            schematic = new ISchematic() {
                @Override
                public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
                    return realSchematic.desiredState(x, y, z, current, BuilderProcess.this.approxPlaceable);
                }

                @Override
                public boolean inSchematic(int x, int y, int z, BlockState currentState) {
                    return ISchematic.super.inSchematic(x, y, z, currentState) && y >= minYInclusive && y <= maxYInclusive && realSchematic.inSchematic(x, y, z, currentState);
                }

                @Override
                public void reset() {
                    realSchematic.reset();
                }

                @Override
                public int widthX() {
                    return realSchematic.widthX();
                }

                @Override
                public int heightY() {
                    return realSchematic.heightY();
                }

                @Override
                public int lengthZ() {
                    return realSchematic.lengthZ();
                }
            };
        }
        // one context per tick, not per recursion: with no end set this recurses to the cap every tick
        // the builder has nothing loaded to do, and a threaded context isn't cheap to build
        BuilderCalculationContext bcc = reuse == null ? new BuilderCalculationContext() : reuse.retarget();
        if (!recalc(bcc)) {
            if (Baritone.settings().buildInLayers.value && layer * Baritone.settings().layerHeight.value < stopAtHeight) {
                logDirect("Starting layer " + layer);
                layer++;
                return onTick(calcFailed, isSafeToCancel, recursions + 1, bcc);
            }
            Vec3i repeat = Baritone.settings().buildRepeat.value;
            int max = Baritone.settings().buildRepeatCount.value;
            numRepeats++;
            if (repeat.equals(new Vec3i(0, 0, 0)) || (max != -1 && numRepeats >= max)) {
                logDirect("Done building");
                if (Baritone.settings().notificationOnBuildFinished.value) {
                    logNotification("Done building", false);
                }
                onLostControl();
                return null;
            }
            // build repeat time
            layer = 0;
            Vec3i step = repeat;
            INetherHighwayBuilderBehavior highwayRepeat = baritone.getNetherHighwayBuilderBehavior();
            boolean patternRepeat = highwayRepeat.isBuildingHighwayState() && highwayRepeat.hasCustomPattern();
            if (patternRepeat) {
                // Angled highway: the next origin follows the configured slice pattern. The step
                // is derived from the current origin's position, so builder restarts and detours
                // can never desync the pattern phase.
                step = highwayRepeat.repeatAdvance(new BlockPos(origin));
            }
            origin = new BlockPos(origin).offset(step);
            if (patternRepeat) {
                // ... and so is the cross-section: with rails on, the slices sharing a driving-axis
                // coordinate split the rail columns between them.
                ISchematic nextSlice = highwayRepeat.schematicForOrigin(new BlockPos(origin));
                if (nextSlice != null) {
                    if (realSchematic != null) {
                        realSchematic = wrapForBuild(nextSlice); // buildInLayers rewraps this next tick
                    } else {
                        schematic = wrapForBuild(nextSlice);
                    }
                }
            }
            if (!Baritone.settings().buildRepeatSneaky.value) {
                schematic.reset();
            }
            if (Baritone.settings().buildRepeatLog.value) {
                logDirect("Repeating build in vector " + step + ", new origin is " + origin);
            }
            return onTick(calcFailed, isSafeToCancel, recursions + 1, bcc);
        }
        if (Baritone.settings().distanceTrim.value) {
            trim();
        }
        updateEntityHoles(bcc);

        List<Optional<Tuple<BetterBlockPos, Rotation>>> toBreak = toBreakNearPlayer(bcc);

        // The printer acts without stopping pathing; the stationary break/place paths below only
        // engage on ticks it didn't act, since they'd send conflicting packets otherwise.
        boolean breakSession = legitBreakSessionLastTick;
        legitBreakSessionLastTick = false;
        legitSlowBreakLastTick = false;
        legitAimTargetLastTick = null;
        // Never place while a stationary break is being lined up: a placement skips the break
        // branch for that tick, releasing the attack and resetting the dig progress, and the
        // placed block can wall off the break target. Nor let placements starve breaking forever.
        String placeHold = breakSession ? "held for a stationary break"
                : printerBreakStarvation >= PRINTER_MAX_BREAK_STARVATION ? "paused to let breaking catch up" : null;
        boolean mayPlace = placeHold == null;
        PrinterAction printerAction = PrinterAction.NONE;
        String printerUnusable = Baritone.settings().printer.value ? printerUnusableReason() : "printer off";
        boolean printerReady = printerUnusable == null;
        printerPlacesHeld = false;
        if (printerReady) {
            printerAction = printerTick(bcc, mayPlace);
        }
        printerReported = false;
        printerIdleReason = printerAction.placed() ? null : !printerReady ? printerUnusable : placeHold != null ? placeHold : printerIdleReason;
        printerOutOfReach = printerReady && !printerAction.acted() && placeHold == null && printerIdleRank == PRINTER_IDLE_NO_FACE;
        if (!toBreak.isEmpty() && printerAction.placed() && !printerAction.broke()) {
            printerBreakStarvation++;
        } else {
            printerBreakStarvation = 0;
        }

        if (!toBreak.isEmpty() && isSafeToCancel && ctx.player().onGround() && !printerAction.acted()) {
            legitBreakSessionLastTick = true;
            if (Baritone.settings().builderDoubleMine.value) {
                BlockPos mining = doubleMine.tick(doubleMineCandidates(bcc, toBreak));
                if (mining != null) {
                    Optional<Rotation> aim = toBreak.stream().filter(info -> info.isPresent() && info.get().getA().equals(mining))
                            .map(info -> info.get().getB()).findFirst();
                    // what the slots hold is theirs; a vanilla dig below only gets the rest
                    toBreak.removeIf(info -> info.isPresent() && doubleMine.isMining(info.get().getA()));
                    if (doubleMine.digging() || toBreak.stream().noneMatch(Optional::isPresent)) {
                        // stay in reach of the regular dig, and look at it the way a player digging would
                        legitAimTargetLastTick = BetterBlockPos.from(mining);
                        legitSlowBreakLastTick = true;
                        aim.ifPresent(rot -> baritone.getLookBehavior().updateTarget(rot, true));
                        if (doubleMine.digging()) {
                            // any other dig packet would restart or move the server's regular dig
                            printerBreakGrace = Math.max(printerBreakGrace, Math.max(1, Baritone.settings().blockBreakSpeed.value));
                        }
                        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                    }
                    // only a parked block is left digging and it finishes on its own: carry on
                    // with the quicker blocks
                }
            }
            // we'd like to pause to break this block
            // only change look direction if it's safe (don't want to fuck up an in progress parkour for example
            // prefer a candidate that is already in the crosshair so we don't thrash the aim between targets
            Optional<Tuple<BetterBlockPos, Rotation>> chosen = Optional.empty();
            for (Optional<Tuple<BetterBlockPos, Rotation>> blockInfo : toBreak) {
                if (blockInfo.isPresent() && ctx.isLookingAt(blockInfo.get().getA())) {
                    chosen = blockInfo;
                    break;
                }
            }
            if (!chosen.isPresent()) {
                chosen = toBreak.stream().filter(Optional::isPresent).findFirst().orElse(Optional.empty());
            }
            if (chosen.isPresent()) {
                Rotation rot = chosen.get().getB();
                BetterBlockPos pos = chosen.get().getA();
                legitAimTargetLastTick = pos;
                baritone.getLookBehavior().updateTarget(rot, true);
                MovementHelper.switchToBestToolFor(ctx, bcc.get(pos));
                if (ctx.player().isCrouching()) {
                    // really horrible bug where a block is visible for breaking while sneaking but not otherwise
                    // so you can't see it, it goes to place something else, sneaks, then the next tick it tries to break
                    // and is unable since it's unsneaked in the intermediary tick
                    baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
                }
                if (ctx.isLookingAt(pos)) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                }
                BlockState state = bcc.get(pos);
                float progress = printerBreakProgress(state, pos);
                // We aim at the block now, so the printer breaks it next tick. Holding the printer
                // off for this dig would also hold it off for the next one: we'd stay here and
                // dig near-instant blocks one at a time with the vanilla click
                boolean printerTakesIt = printerReady && Baritone.settings().printerBreak.value
                        && printerBreakSafe(pos) && printerBreakPackets(progress) > 0;
                // instant once the server's dig speed agrees with the client's, a tick or two away
                boolean waits = progress == 0 && printerClientInstant(state, pos);
                if (progress < 1.0f && !printerTakesIt && !waits && !BlockBreakHelper.breaksNearInstant(progress)) {
                    // a dig that spans ticks only lands if the crosshair stays on the block, so
                    // anything moving the player has to let go until it's done
                    legitSlowBreakLastTick = true;
                    printerBreakGrace = Math.max(printerBreakGrace, Math.max(1, Baritone.settings().blockBreakSpeed.value));
                }
            }

            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        List<BlockState> desirableOnHotbar = new ArrayList<>();
        Optional<Placement> toPlace = searchForPlacables(bcc, desirableOnHotbar);
        // a printer only waiting on a place limit places next tick; the click here would stop and
        // sneak for a block that's about to be placed anyway, and go over the limit doing it
        if (!printerAction.acted() && !printerPlacesHeld && toPlace.isPresent() && isSafeToCancel && ctx.player().onGround() && ticks <= 0) {
            if (!printerSlotUnsettled(toPlace.get().hotbarSelection)) {
                Rotation rot = toPlace.get().rot;
                legitAimTargetLastTick = new BetterBlockPos(toPlace.get().placeAgainst);
                baritone.getLookBehavior().updateTarget(rot, true);
                ctx.player().getInventory().setSelectedSlot(toPlace.get().hotbarSelection);
                lastPlaceSlot = toPlace.get().hotbarSelection;
                placedFrom[toPlace.get().hotbarSelection] = ctx.player().getMainHandItem().getItem();
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
                if (ctx.isLookingAt(toPlace.get().placeAgainst) && ((BlockHitResult) ctx.objectMouseOver()).getDirection().equals(toPlace.get().side)) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                }
            }
            // hold position even while the hotbar is unconfirmed, rather than falling through to
            // goal-following and walking on with placements suspended
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        if (Baritone.settings().allowInventory.value) {
            ArrayList<Integer> usefulSlots = new ArrayList<>();
            List<BlockState> noValidHotbarOption = new ArrayList<>();
            List<BlockState> onLastHotbarStack = new ArrayList<>();
            // every nearby cell that wants a block adds it again
            for (BlockState desired : new LinkedHashSet<>(desirableOnHotbar)) {
                int first = -1;
                int stacks = 0;
                for (int i = 0; i < 9; i++) {
                    if (valid(approxPlaceable.get(i), desired, true)) {
                        if (first == -1) {
                            first = i;
                        }
                        stacks++;
                    }
                }
                if (first == -1) {
                    noValidHotbarOption.add(desired);
                    continue;
                }
                usefulSlots.add(first);
                if (stacks == 1) {
                    onLastHotbarStack.add(desired);
                }
            }

            outer:
            for (int i = 9; i < 36; i++) {
                for (BlockState desired : noValidHotbarOption) {
                    if (valid(approxPlaceable.get(i), desired, true)) {
                        if (printerInventoryUnsettled()) {
                            // a move is already in flight; hold instead of clicking again
                            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                        }
                        List<Integer> homes = placedFromSlots(ctx.player().getInventory().getNonEquipmentItems().get(i).getItem());
                        boolean requested;
                        if (Baritone.settings().fillEmptyHotbarSlots.value) {
                            // pull every matching stack the empty slots have room for, not just this one
                            List<Integer> sources = new ArrayList<>();
                            for (int j = i; j < 36; j++) {
                                if (valid(approxPlaceable.get(j), desired, true)) {
                                    sources.add(j);
                                }
                            }
                            requested = baritone.getInventoryBehavior().attemptToFillHotbarFrom(sources, homes, usefulSlots::contains);
                        } else {
                            requested = baritone.getInventoryBehavior().attemptToPutOnHotbar(i, homes, usefulSlots::contains);
                        }
                        if (!requested) {
                            // awaiting inventory move, so pause
                            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                        }
                        restockEarlyBlocker = null;
                        break outer;
                    }
                }
            }

            // Restocking only once the hotbar is dry leaves nothing to place while the last places
            // are acked, the move waits for a calm tick and the new slots settle. Doing it while the
            // last stack still lasts hides all of that behind it.
            if (Baritone.settings().restockHotbarEarly.value && !printerInventoryUnsettled()) {
                for (BlockState desired : onLastHotbarStack) {
                    List<Integer> sources = new ArrayList<>();
                    for (int j = 9; j < 36; j++) {
                        if (valid(approxPlaceable.get(j), desired, true)) {
                            sources.add(j);
                        }
                    }
                    if (sources.isEmpty()) {
                        continue;
                    }
                    List<Integer> homes = placedFromSlots(ctx.player().getInventory().getNonEquipmentItems().get(sources.get(0)).getItem());
                    List<Integer> targets = baritone.getInventoryBehavior().topUpTargets(homes, Baritone.settings().fillEmptyHotbarSlots.value, usefulSlots::contains);
                    if (targets == null) {
                        restockEarlyBlocker = "a slot it ran out of is still busy";
                        continue;
                    }
                    if (targets.isEmpty()) {
                        // one more stack is all it takes to hide the next restock behind this one
                        int evict = baritone.getInventoryBehavior().topUpEvictionSlot(usefulSlots::contains);
                        if (evict == -1) {
                            restockEarlyBlocker = "no free hotbar slot and nothing to swap out";
                            continue;
                        }
                        targets = List.of(evict);
                    }
                    if (printerAction.broke()) {
                        // The clicks would add to this tick's dig packets, and digging a tunnel
                        // breaks on nearly every tick. printerTick counts the cooldown down first,
                        // so this holds the next tick's breaks for the move instead.
                        printerBreakCooldown = Math.max(printerBreakCooldown, 2);
                        restockEarlyBlocker = "waiting for a tick without breaking";
                        break;
                    }
                    if (baritone.getInventoryBehavior().topUpHotbar(sources, targets)) {
                        restockEarlyBlocker = null;
                        break;
                    }
                    // a calm tick takes the inventory pauser two ticks to set up; keep the breaks
                    // off the tick the move goes out on
                    printerBreakCooldown = Math.max(printerBreakCooldown, 2);
                    restockEarlyBlocker = "waiting for the inventory move gates";
                    break;
                }
            }
        }

        INetherHighwayBuilderBehavior highway = baritone.getNetherHighwayBuilderBehavior();
        boolean printerBlocked = Baritone.settings().printer.value && !printerReady;
        if (Baritone.settings().highwayEndDistance.value != -1
                && highway.isBuildingHighwayState()
                && !highway.isFixingInvalidBlocks()
                && !(highway.needsLateralTraverses() && baritone.getPathingBehavior().isPathing())
                && isSafeToCancel
                && (printerAction.acted() || highway.isEndDistanceWalkHeld() || printerBlocked)) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        // Aim at the strip we're standing in before anything further across the width, so a sweep
        // finishes what it is on instead of setting off across the highway and leaving holes.
        Collection<BetterBlockPos> strip = currentStrip();
        Goal goal = null;
        if (strip != incorrectPositions) {
            goal = assemble(bcc, approxPlaceable.subList(0, 9), false, strip);
            if (goal == null) {
                goal = assemble(bcc, approxPlaceable, false, strip);
            }
        }
        if (goal == null) {
            // nothing actionable in our own strip; look at the full width rather than give up
            goal = assemble(bcc, approxPlaceable.subList(0, 9), false, incorrectPositions);
            if (goal == null) {
                goal = assemble(bcc, approxPlaceable, true, incorrectPositions); // we're far away, so assume that we have our whole inventory to recalculate placeable properly
            }
        }
        if (goal == null) {
            if (entityBlocked.stream().anyMatch(pos -> !boatBlocked.contains(pos))) {
                // What's left is waiting on an entity: still falling through the hole we just dug,
                // or walking off. A pause would sit idle long after it's gone, so check every tick.
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            if (Baritone.settings().skipFailedLayers.value && Baritone.settings().buildInLayers.value && layer * Baritone.settings().layerHeight.value < realSchematic.heightY()) {
                logDirect("Skipping layer that I cannot construct! Layer #" + layer);
                layer++;
                return onTick(calcFailed, isSafeToCancel, recursions + 1, bcc);
            }
            logDirect("Unable to do it. Pausing. resume to resume, cancel to cancel");
            paused = true;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        return new PathingCommandContext(goal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH, bcc);
    }

    private boolean recalc(BuilderCalculationContext bcc) {
        if (incorrectPositions == null) {
            incorrectPositions = new HashSet<>();
            fullRecalc(bcc);
            if (incorrectPositions.isEmpty()) {
                return false;
            }
        }
        recalcNearby(bcc);
        // before the emptiness check, so a revert behind us holds the window where it is
        recheckCompletedWatch(bcc);
        if (incorrectPositions.isEmpty()) {
            fullRecalc(bcc);
        }
        return !incorrectPositions.isEmpty();
    }

    private void watchCompleted(BuilderCalculationContext bcc, BetterBlockPos pos, BlockState current) {
        long hash = BetterBlockPos.longHash(pos);
        CompletedWatch watch = completedWatch.get(hash);
        if (watch != null) {
            watch.completedTick = builderTick; // fixed again after a revert, which can be reverted too
            return;
        }
        if (!bcc.schematic.inSchematic(pos.x - bcc.originX, pos.y - bcc.originY, pos.z - bcc.originZ, current)) {
            return;
        }
        completedWatch.put(hash, new CompletedWatch(pos, bcc.schematic, bcc.originX, bcc.originY, bcc.originZ, builderTick));
        completedWatchSnapshot = null;
    }

    /**
     * Puts watched positions that have been undone back into {@link #incorrectPositions}, wherever
     * they are; the nearby scan only covers the ones close by.
     */
    private void recheckCompletedWatch(BuilderCalculationContext bcc) {
        ObjectIterator<CompletedWatch> it = completedWatch.values().iterator();
        while (it.hasNext()) {
            CompletedWatch watch = it.next();
            BetterBlockPos pos = watch.pos;
            BlockState current = bcc.bsi.get0(pos);
            // the builder's own view: the current window wins where it covers the position
            BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, current);
            if (desired == null) {
                // masked out of its window by what's there now, so there's nothing to judge it by
                incorrectPositions.remove(pos);
                it.remove();
                completedWatchSnapshot = null;
            } else if (!valid(current, desired, false)) {
                // reverted: stays watched until it's fixed, or getSchematic would stop knowing it
                if (incorrectPositions.add(pos)) {
                    printerRevertsSinceReport++;
                }
            } else if (incorrectPositions.remove(pos)) {
                watch.completedTick = builderTick;
            } else if (builderTick - watch.completedTick >= COMPLETED_WATCH_TICKS) {
                it.remove();
                completedWatchSnapshot = null;
            }
        }
    }

    private Long2ObjectMap<CompletedWatch> snapshotCompletedWatch() {
        if (completedWatchSnapshot == null) {
            completedWatchSnapshot = completedWatch.isEmpty() ? Long2ObjectMaps.emptyMap() : new Long2ObjectOpenHashMap<>(completedWatch);
        }
        return completedWatchSnapshot;
    }

    private void clearCompletedWatch() {
        completedWatch.clear();
        completedWatchSnapshot = null;
    }

    private void trim() {
        HashSet<BetterBlockPos> copy = new HashSet<>(incorrectPositions);
        copy.removeIf(pos -> pos.distSqr(ctx.player().blockPosition()) > 200);
        if (!copy.isEmpty()) {
            incorrectPositions = copy;
        }
    }

    private void recalcNearby(BuilderCalculationContext bcc) {
        BetterBlockPos center = ctx.playerFeet();
        int radius = Baritone.settings().builderTickScanRadius.value;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    BlockState current = bcc.bsi.get0(x, y, z);
                    BlockState desired = bcc.getSchematic(x, y, z, current);
                    if (desired != null) {
                        // we care about this position
                        BetterBlockPos pos = new BetterBlockPos(x, y, z);
                        if (valid(current, desired, false)) {
                            if (incorrectPositions.remove(pos)) {
                                watchCompleted(bcc, pos, current);
                            }
                            observedCompleted.add(BetterBlockPos.longHash(pos));
                        } else {
                            if (incorrectPositions.add(pos) && completedWatch.containsKey(BetterBlockPos.longHash(pos))) {
                                printerRevertsSinceReport++; // completed a moment ago, now undone
                            }
                            observedCompleted.remove(BetterBlockPos.longHash(pos));
                        }
                    }
                }
            }
        }
    }

    private void fullRecalc(BuilderCalculationContext bcc) {
        incorrectPositions = new HashSet<>();
        for (int y = 0; y < schematic.heightY(); y++) {
            for (int z = 0; z < schematic.lengthZ(); z++) {
                for (int x = 0; x < schematic.widthX(); x++) {
                    int blockX = x + origin.getX();
                    int blockY = y + origin.getY();
                    int blockZ = z + origin.getZ();
                    BlockState current = bcc.bsi.get0(blockX, blockY, blockZ);
                    if (!schematic.inSchematic(x, y, z, current)) {
                        continue;
                    }
                    if (bcc.bsi.worldContainsLoadedChunk(blockX, blockZ)) { // check if its in render distance, not if its in cache
                        // we can directly observe this block, it is in render distance
                        if (valid(bcc.bsi.get0(blockX, blockY, blockZ), schematic.desiredState(x, y, z, current, this.approxPlaceable), false)) {
                            observedCompleted.add(BetterBlockPos.longHash(blockX, blockY, blockZ));
                        } else {
                            WhiteBlackSchematic ourSchem = null;
                            ISchematic schematicToCheck = this.schematic;
                            if (schematicToCheck instanceof MaskSchematic) {
                                schematicToCheck = ((MaskSchematic) schematicToCheck).getSchematic();
                            }

                            if (schematicToCheck instanceof CompositeSchematic) {
                                CompositeSchematic compositeSchematic = (CompositeSchematic) schematicToCheck;
                                ISchematic innerSchematic = compositeSchematic.getSchematic(x, y, z, current).schematic;
                                if (innerSchematic instanceof WhiteBlackSchematic) {
                                    ourSchem = (WhiteBlackSchematic) innerSchematic;
                                }
                            }
                            else if (schematicToCheck instanceof WhiteBlackSchematic) {
                                ourSchem = (WhiteBlackSchematic) schematicToCheck;
                            }
                            BlockState tempState = ctx.world().getBlockState(new BlockPos(blockX, blockY + 1, blockZ));
                            if (ourSchem != null && ourSchem.isValidIfUnder() && MovementHelper.isBlockNormalCube(tempState)) {
                                observedCompleted.add(BetterBlockPos.longHash(blockX, blockY, blockZ));
                            }
                            else {
                                incorrectPositions.add(new BetterBlockPos(blockX, blockY, blockZ));
                                observedCompleted.remove(BetterBlockPos.longHash(blockX, blockY, blockZ));
                            }

                            if (incorrectPositions.size() > Baritone.settings().incorrectSize.value) {
                                return;
                            }
                        }
                        continue;
                    }
                    // this is not in render distance
                    if (!observedCompleted.contains(BetterBlockPos.longHash(blockX, blockY, blockZ))) {
                        // and we've never seen this position be correct
                        // therefore mark as incorrect
                        incorrectPositions.add(new BetterBlockPos(blockX, blockY, blockZ));
                        if (incorrectPositions.size() > Baritone.settings().incorrectSize.value) {
                            return;
                        }
                    }
                }
            }
        }
    }

    public boolean checkNoEntityCollision(AABB bb, @Nullable Entity entityIgnore) {
        List<Entity> list = ctx.world().getEntities((Entity)null, bb);

        for (Entity entity4 : list) {
            if (entity4.isAlive() && entity4.blocksBuilding && entity4 != entityIgnore) {
                return false;
            }
        }

        return true;
    }

    private void clearEntityHoles() {
        toBreakEntity.clear();
        entityHole.clear();
        entityBlocked.clear();
        boatBlocked.clear();
    }

    /**
     * Finds the entities standing where a missing block goes and plans the hole each one drops
     * through ({@link EntityHole}). Redone every tick from where they are now, so a dug hole cell
     * stays protected for as long as its entity is still in the way, not just while there's still
     * something in it to dig.
     */
    private void updateEntityHoles(BuilderCalculationContext bcc) {
        clearEntityHoles();
        LongSet cells = new LongOpenHashSet();
        LongSet columns = new LongOpenHashSet();
        Entity self = ctx.player().getRootVehicle();
        BlockState air = Blocks.AIR.defaultBlockState();
        // Only as deep as we can dig from up here beside it. Further down, digging it out takes climbing in
        // after the entity, where the block we're building over it shuts us in.
        entityHoleDigY = standingY();
        int lowestLayer = EntityHole.lowestDiggableLayer(entityHoleDigY, ctx.player().getEyeHeight(Pose.STANDING),
                ctx.playerController().getBlockReachDistance());
        // each vehicle once with everything riding it, since they drop together
        List<Entity> roots = ctx.entitiesStream()
                .filter(e -> e.isAlive() && e.blocksBuilding)
                .map(Entity::getRootVehicle)
                .filter(root -> root != self)
                .distinct()
                .toList();
        for (Entity root : roots) {
            List<BetterBlockPos> blocked = new ArrayList<>();
            boolean boat = false;
            for (Entity member : (Iterable<Entity>) root.getSelfAndPassengers()::iterator) {
                if (!member.isAlive() || !member.blocksBuilding) {
                    continue;
                }
                boat |= member instanceof AbstractBoat;
                AABB box = member.getBoundingBox();
                // the cells it overlaps, by the same strict test checkNoEntityCollision makes
                for (int x = Mth.floor(box.minX); x < box.maxX; x++) {
                    for (int y = Mth.floor(box.minY); y < box.maxY; y++) {
                        for (int z = Mth.floor(box.minZ); z < box.maxZ; z++) {
                            BetterBlockPos pos = new BetterBlockPos(x, y, z);
                            if (incorrectPositions.contains(pos) && bcc.bsi.get0(pos).getBlock() instanceof AirBlock) {
                                blocked.add(pos);
                            }
                        }
                    }
                }
            }
            if (blocked.isEmpty()) {
                continue;
            }
            entityBlocked.addAll(blocked);
            if (boat) {
                boatBlocked.addAll(blocked);
                continue;
            }
            if (!EntityHole.falls(root)) {
                continue;
            }
            int clearY = blocked.stream().mapToInt(pos -> pos.y).min().getAsInt();
            List<BlockPos> hole = EntityHole.plan(ctx.world(), EntityHole.groupBox(root), clearY, pos -> {
                BlockState desired = bcc.getSchematic(pos.getX(), pos.getY(), pos.getZ(), air);
                return desired != null && !(desired.getBlock() instanceof AirBlock);
            }, lowestLayer);
            if (hole == null) {
                continue; // nothing we'd dig drops it, so its cell just waits for it to move
            }
            for (BlockPos cell : hole) {
                BetterBlockPos pos = new BetterBlockPos(cell);
                entityHole.add(pos);
                cells.add(BetterBlockPos.longHash(pos.x, pos.y, pos.z));
                columns.add(BetterBlockPos.longHash(pos.x, 0, pos.z));
                BlockState state = bcc.bsi.get0(pos);
                if (!(state.getBlock() instanceof AirBlock) && !(state.getBlock() instanceof LiquidBlock)
                        && !state.getCollisionShape(ctx.world(), cell).isEmpty()) {
                    toBreakEntity.add(pos);
                }
            }
        }
        bcc.entityHoleCells = cells;
        entityHoleColumns = columns;
    }

    /** The level we're standing at: our feet, or the block under them mid-jump, so a jump doesn't change it. */
    private int standingY() {
        BetterBlockPos feet = ctx.playerFeet();
        return ctx.player().onGround() || MovementHelper.canWalkOn(ctx, feet.below()) ? feet.y : feet.y - 1;
    }

    private Goal assemble(BuilderCalculationContext bcc, List<BlockState> approxPlaceable, boolean logMissing, Collection<BetterBlockPos> candidates) {
        List<BetterBlockPos> placeable = new ArrayList<>();
        List<BetterBlockPos> breakable = new ArrayList<>();
        List<BetterBlockPos> sourceLiquids = new ArrayList<>();
        List<BetterBlockPos> flowingLiquids = new ArrayList<>();
        Map<BlockState, Integer> missing = new HashMap<>();
        AtomicBoolean liquidDetected = new AtomicBoolean(false);
        AtomicBoolean entityDetected = new AtomicBoolean(false);
        AtomicBoolean boatDetected = new AtomicBoolean(false);
        List<BetterBlockPos> outOfBounds = new ArrayList<>();
        candidates.forEach(pos -> {
            BlockState state = bcc.bsi.get0(pos);
            if (state.getBlock() instanceof AirBlock) {
                if (entityBlocked.contains(pos)) {
                    // waits for the entity to drop through the hole updateEntityHoles planned under it
                    entityDetected.set(true);
                    if (boatBlocked.contains(pos)) {
                        boatDetected.set(true); // can't do boats lol
                    }
                } else if (!entityHole.contains(pos)) { // dug out for an entity to drop through: stays empty until it has
                    BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, state);
                    if (desired == null) {
                        outOfBounds.add(pos);
                    } else if (containsBlockState(approxPlaceable, desired)) {
                        placeable.add(pos);
                    } else {
                        missing.put(desired, 1 + missing.getOrDefault(desired, 0));
                    }
                }
            } else {
                if (state.getBlock() instanceof LiquidBlock) {
                    // if the block itself is JUST a liquid (i.e. not just a waterlogged block), we CANNOT break it
                    // TODO for 1.13 make sure that this only matches pure water, not waterlogged blocks
                    if (!MovementHelper.possiblyFlowing(state)) {
                        // if it's a source block then we want to replace it with a throwaway
                        sourceLiquids.add(pos);
                    } else {
                        if (Baritone.settings().placeOnFlowingLiquid.value) {
                            sourceLiquids.add(pos);
                        }
                        flowingLiquids.add(pos);
                    }
                } else {
                    breakable.add(pos);
                }
            }
        });
        incorrectPositions.removeAll(outOfBounds);

        if (boatDetected.get()) {
            return null;
        }

        if (!breakable.isEmpty()) {
            placeable.clear();
        }


        List<Goal> toBreak = new ArrayList<>();
        if (entityDetected.get()) {
            toBreakEntity.forEach(pos -> toBreak.add(new GoalBesideEntityHole(pos, entityHoleDigY, entityHoleColumns)));
            if (!toBreak.isEmpty()) {
                return new GoalComposite(toBreak.toArray(new Goal[0]));
            }
        }
        breakable.forEach(pos -> toBreak.add(breakGoal(pos, bcc)));
        List<Goal> toPlace = new ArrayList<>();
        // List.contains for every position was quadratic, and placeable is in the thousands on a big build
        Set<BetterBlockPos> placeableSet = new HashSet<>(placeable);
        placeable.forEach(pos -> {
            if (!placeableSet.contains(pos.below()) && !placeableSet.contains(pos.below(2))) {
                toPlace.add(placementGoal(pos, bcc));
            }
        });
        sourceLiquids.forEach(pos -> toPlace.add(new GoalBlock(pos.above())));

        if (!toPlace.isEmpty()) {
            return new JankyGoalComposite(new GoalComposite(toPlace.toArray(new Goal[0])), new GoalComposite(toBreak.toArray(new Goal[0])));
        }
        if (toBreak.isEmpty()) {
            if (logMissing && !missing.isEmpty()) {
                logDirect("Missing materials for at least:");
                logDirect(missing.entrySet().stream()
                        .map(e -> String.format("%sx %s", e.getValue(), e.getKey()))
                        .collect(Collectors.joining("\n")));
            }
            if (logMissing && !flowingLiquids.isEmpty()) {
                logDirect("Unreplaceable liquids at at least:");
                logDirect(flowingLiquids.stream()
                        .map(p -> String.format("%s %s %s", p.x, p.y, p.z))
                        .collect(Collectors.joining("\n")));
            }
            return null;
        }
        return new GoalComposite(toBreak.toArray(new Goal[0]));
    }

    public static class JankyGoalComposite implements Goal {

        private final Goal primary;
        private final Goal fallback;

        public JankyGoalComposite(Goal primary, Goal fallback) {
            this.primary = primary;
            this.fallback = fallback;
        }


        @Override
        public boolean isInGoal(int x, int y, int z) {
            return primary.isInGoal(x, y, z) || fallback.isInGoal(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return primary.heuristic(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            JankyGoalComposite goal = (JankyGoalComposite) o;
            return Objects.equals(primary, goal.primary)
                    && Objects.equals(fallback, goal.fallback);
        }

        @Override
        public int hashCode() {
            int hash = -1701079641;
            hash = hash * 1196141026 + primary.hashCode();
            hash = hash * -80327868 + fallback.hashCode();
            return hash;
        }

        @Override
        public String toString() {
            return "JankyComposite Primary: " + primary + " Fallback: " + fallback;
        }

        public Goal getPrimary() {
            return primary;
        }

        public Goal getFallback() {
            return fallback;
        }
    }

    public static class GoalBreak extends GoalGetToBlock {

        public GoalBreak(BlockPos pos) {
            super(pos);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            // can't stand right on top of a block, that might not work (what if it's unsupported, can't break then)
            if (y > this.y) {
                return false;
            }
            // but any other adjacent works for breaking, including inside or below
            return super.isInGoal(x, y, z);
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalBreak{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 1636324008;
        }
    }

    private Goal placementGoal(BlockPos pos, BuilderCalculationContext bcc) {
        if (!(ctx.world().getBlockState(pos).getBlock() instanceof AirBlock)) {  // TODO can this even happen?
            return new GoalPlace(pos);
        }
        boolean allowSameLevel = !(ctx.world().getBlockState(pos.above()).getBlock() instanceof AirBlock);
        BlockState current = ctx.world().getBlockState(pos);
        for (Direction facing : Movement.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP) {
            //noinspection ConstantConditions
            if (MovementHelper.canPlaceAgainst(ctx, pos.relative(facing)) && placementPlausible(pos, bcc.getSchematic(pos.getX(), pos.getY(), pos.getZ(), current))) {
                return new GoalAdjacent(pos, pos.relative(facing), allowSameLevel);
            }
        }
        return new GoalPlace(pos);
    }

    private Goal breakGoal(BlockPos pos, BuilderCalculationContext bcc) {
        if (Baritone.settings().goalBreakFromAbove.value && bcc.bsi.get0(pos.above()).getBlock() instanceof AirBlock && bcc.bsi.get0(pos.above(2)).getBlock() instanceof AirBlock) { // TODO maybe possible without the up(2) check?
            return new JankyGoalComposite(new GoalBreak(pos), new GoalGetToBlock(pos.above()) {
                @Override
                public boolean isInGoal(int x, int y, int z) {
                    if (y > this.y || (x == this.x && y == this.y && z == this.z)) {
                        return false;
                    }
                    return super.isInGoal(x, y, z);
                }
            });
        }
        return new GoalBreak(pos);
    }

    public static class GoalAdjacent extends GoalGetToBlock {

        private boolean allowSameLevel;
        private BlockPos no;

        public GoalAdjacent(BlockPos pos, BlockPos no, boolean allowSameLevel) {
            super(pos);
            this.no = no;
            this.allowSameLevel = allowSameLevel;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            if (x == this.x && y == this.y && z == this.z) {
                return false;
            }
            if (x == no.getX() && y == no.getY() && z == no.getZ()) {
                return false;
            }
            if (!allowSameLevel && y == this.y - 1) {
                return false;
            }
            if (y < this.y - 1) {
                return false;
            }
            return super.isInGoal(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            // prioritize lower y coordinates
            return this.y * 100 + super.heuristic(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            if (!super.equals(o)) {
                return false;
            }

            GoalAdjacent goal = (GoalAdjacent) o;
            return allowSameLevel == goal.allowSameLevel
                    && Objects.equals(no, goal.no);
        }

        @Override
        public int hashCode() {
            int hash = 806368046;
            hash = hash * 1412661222 + super.hashCode();
            hash = hash * 1730799370 + (int) BetterBlockPos.longHash(no.getX(), no.getY(), no.getZ());
            hash = hash * 260592149 + (allowSameLevel ? -1314802005 : 1565710265);
            return hash;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalAdjacent{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    public static class GoalPlace extends GoalBlock {

        public GoalPlace(BlockPos placeAt) {
            super(placeAt.above());
        }

        @Override
        public double heuristic(int x, int y, int z) {
            // prioritize lower y coordinates
            return this.y * 100 + super.heuristic(x, y, z);
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 1910811835;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalPlace{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    /**
     * Next to a cell of the hole an entity drops through, at the level it's dug from ({@link EntityHole#lowestDiggableLayer})
     * and not over the hole. A {@link GoalBreak} takes standing on the cell or level with it, which down a hole means
     * climbing in after the entity, or tunnelling in beside it under the floor.
     */
    public static class GoalBesideEntityHole implements Goal, IGoalRenderPos {

        private final BetterBlockPos cell;
        private final int digY;
        private final LongSet holeColumns;

        public GoalBesideEntityHole(BetterBlockPos cell, int digY, LongSet holeColumns) {
            this.cell = cell;
            this.digY = digY;
            this.holeColumns = holeColumns;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return y == digY && Math.abs(x - cell.x) <= 1 && Math.abs(z - cell.z) <= 1
                    && !holeColumns.contains(BetterBlockPos.longHash(x, 0, z));
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return GoalBlock.calculate(x - cell.x, y - digY, z - cell.z);
        }

        @Override
        public BlockPos getGoalPos() {
            return cell;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            GoalBesideEntityHole goal = (GoalBesideEntityHole) o;
            return digY == goal.digY && cell.equals(goal.cell) && holeColumns.equals(goal.holeColumns);
        }

        @Override
        public int hashCode() {
            return 31 * cell.hashCode() + digY;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalBesideEntityHole{x=%s,y=%s,z=%s,digY=%s}",
                    SettingsUtil.maybeCensor(cell.x),
                    SettingsUtil.maybeCensor(cell.y),
                    SettingsUtil.maybeCensor(cell.z),
                    SettingsUtil.maybeCensor(digY)
            );
        }
    }

    @Override
    public void onLostControl() {
        incorrectPositions = null;
        clearEntityHoles();
        name = null;
        schematic = null;
        realSchematic = null;
        layer = Baritone.settings().startAtLayer.value;
        numRepeats = 0;
        paused = false;
        observedCompleted = null;
        printerPlaceCooldown = 0;
        printerBreakCooldown = 0;
        printerBreakGrace = 0;
        legitBreakSessionLastTick = false;
        legitSlowBreakLastTick = false;
        legitAimTargetLastTick = null;
        doubleMine.reset();
        printerBreakStarvation = 0;
        printerMovementForced = false;
        clearCompletedWatch();
        printerResetNoRotateDetection();
    }

    @Override
    public String displayName0() {
        return paused ? "Builder Paused" : "Building " + name;
    }

    @Override
    public Optional<Integer> getMinLayer() {
        if (Baritone.settings().buildInLayers.value) {
            return Optional.of(this.layer);
        }
        return Optional.empty();
    }

    @Override
    public Optional<Integer> getMaxLayer() {
        if (Baritone.settings().buildInLayers.value) {
            return Optional.of(this.stopAtHeight);
        }
        return Optional.empty();
    }

    private List<BlockState> approxPlaceable(int size) {
        List<BlockState> result = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ItemStack stack = ctx.player().getInventory().getNonEquipmentItems().get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem) || (i < 9 && !baritone.getInventoryBehavior().hotbarStackLasts(i, stack))) {
                result.add(Blocks.AIR.defaultBlockState());
                continue;
            }
            // <toxic cloud>
            BlockState itemState = ((BlockItem) stack.getItem())
                .getBlock()
                .getStateForPlacement(
                    new BlockPlaceContext(
                        new UseOnContext(ctx.world(), ctx.player(), InteractionHand.MAIN_HAND, stack, new BlockHitResult(new Vec3(ctx.player().position().x, ctx.player().position().y, ctx.player().position().z), Direction.UP, ctx.playerFeet(), false)) {}
                    )
                );
            if (itemState != null) {
                result.add(itemState);
            } else {
                result.add(Blocks.AIR.defaultBlockState());
            }
            // </toxic cloud>
        }
        return result;
    }

    private static boolean sameBlockstate(BlockState first, BlockState second) {
        if (first.getBlock() != second.getBlock()) {
            return false;
        }
        boolean ignoreDirection = Baritone.settings().buildIgnoreDirection.value;
        List<String> ignoredProps = Baritone.settings().buildIgnoreProperties.value;
        if (!ignoreDirection && ignoredProps.isEmpty()) {
            return first.equals(second); // early return if no properties are being ignored
        }
        Map<Property<?>, Comparable<?>> map1 = first.getValues();
        Map<Property<?>, Comparable<?>> map2 = second.getValues();
        for (Property<?> prop : map1.keySet()) {
            if (!Objects.equals(map1.get(prop), map2.get(prop))
                    && !(ignoreDirection && ORIENTATION_PROPS.contains(prop))
                    && !ignoredProps.contains(prop.getName())) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsBlockState(Collection<BlockState> states, BlockState state) {
        for (BlockState testee : states) {
            if (sameBlockstate(testee, state)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return what the schematic placed at the origin wants at this world position, or null if the
     * position isn't part of it
     */
    private BlockState desiredIn(ISchematic schematic, int originX, int originY, int originZ, int x, int y, int z, BlockState current) {
        if (schematic.inSchematic(x - originX, y - originY, z - originZ, current)) {
            // Case of special schematic
            if (schematic instanceof MaskSchematic && ((MaskSchematic)schematic).getSchematic() instanceof CompositeSchematic) {
                ISchematic ourSchem = ((CompositeSchematic)((MaskSchematic)schematic).getSchematic()).getSchematic(x - originX, y - originY, z - originZ, current).schematic;
                if (ourSchem instanceof WhiteBlackSchematic && ((WhiteBlackSchematic) ourSchem).isValidIfUnder() &&
                        MovementHelper.isBlockNormalCube(ctx.world().getBlockState(new BlockPos(x, y + 1, z)))) {
                    return current;
                }
            }

            return schematic.desiredState(x - originX, y - originY, z - originZ, current, this.approxPlaceable);
        } else {
            return null;
        }
    }

    private static boolean valid(BlockState current, BlockState desired, boolean itemVerify) {
        if (desired == null) {
            return true;
        }
        if (current.getBlock() instanceof NetherPortalBlock) {
            return true;
        }
        if (current.getBlock() instanceof LiquidBlock && Baritone.settings().okIfWater.value) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && desired.getBlock() instanceof AirBlock) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && Baritone.settings().okIfAir.value.contains(desired.getBlock())) {
            return true;
        }
        if (desired.getBlock() instanceof AirBlock && Baritone.settings().buildIgnoreBlocks.value.contains(current.getBlock())) {
            return true;
        }
        if (!(current.getBlock() instanceof AirBlock) && Baritone.settings().buildIgnoreExisting.value && !itemVerify) {
            return true;
        }
        if (Baritone.settings().buildValidSubstitutes.value.getOrDefault(desired.getBlock(), Collections.emptyList()).contains(current.getBlock()) && !itemVerify) {
            return true;
        }
        if (current.equals(desired)) {
            return true;
        }
        return sameBlockstate(current, desired);
    }

    public class BuilderCalculationContext extends CalculationContext {

        private List<BlockState> placeable;
        private ISchematic schematic;
        private int originX;
        private int originY;
        private int originZ;
        private Long2ObjectMap<CompletedWatch> watched;
        private final boolean highwayRoad;
        /** {@link BuilderProcess#entityHole} as {@code longHash}es, set by {@link #updateEntityHoles} before any path is planned with this. */
        private LongSet entityHoleCells = LongSets.EMPTY_SET;

        public BuilderCalculationContext() {
            super(BuilderProcess.this.baritone, true); // wew lad
            retarget();
            this.highwayRoad = BuilderProcess.this.baritone.getNetherHighwayBuilderBehavior().isBuildingHighwayState();

            this.jumpPenalty += 10;
            this.backtrackCostFavoringCoefficient = 1;
            this.avoidNetherPortals = true;
        }

        /**
         * Picks up the builder's current schematic and origin, for onTick re-running itself within the same tick
         * (next layer, next repeat, skipped layer). Everything underneath (the world snapshot, tools, settings) is
         * the same for the whole tick, and building it is the expensive part.
         */
        private BuilderCalculationContext retarget() {
            this.placeable = approxPlaceable(9);
            this.schematic = BuilderProcess.this.schematic;
            this.originX = origin.getX();
            this.originY = origin.getY();
            this.originZ = origin.getZ();
            this.watched = snapshotCompletedWatch();
            return this;
        }

        private BlockState getSchematic(int x, int y, int z, BlockState current) {
            BlockState desired = desiredIn(schematic, originX, originY, originZ, x, y, z, current);
            if (desired != null || watched.isEmpty()) {
                return desired;
            }
            CompletedWatch watch = watched.get(BetterBlockPos.longHash(x, y, z));
            return watch == null ? null : desiredIn(watch.schematic, watch.originX, watch.originY, watch.originZ, x, y, z, current);
        }

        @Override
        public double costOfPlacingAt(int x, int y, int z, BlockState current) {
            if (isPossiblyProtected(x, y, z) || !worldBorder.canPlaceAt(x, z) || isOccupiedByEntity(x, y, z)) { // make calculation fail properly if we can't build
                return COST_INF;
            }
            if (inEntityHole(x, y, z)) {
                return COST_INF; // a bridge block in it stops the drop, and gets dug straight back out
            }
            BlockState sch = getSchematic(x, y, z, current);
            if (sch != null) {
                // TODO this can return true even when allowPlace is off.... is that an issue?
                if (sch.getBlock() instanceof AirBlock) {
                    if (highwayRoad) {
                        // A road has no use for scaffolding, and the builder digs a wrong block out
                        // of it as soon as it's in reach, cancelling the path to do so. The path
                        // loses the step it just put down, and the next one plans the same step.
                        return COST_INF;
                    }
                    // we want this to be air, but they're asking if they can place here
                    // this won't be a schematic block, this will be a throwaway
                    return placeBlockCost * Baritone.settings().placeIncorrectBlockPenaltyMultiplier.value; // we're going to have to break it eventually
                }
                if (placeable.contains(sch)) {
                    return 0; // thats right we gonna make it FREE to place a block where it should go in a structure
                    // no place block penalty at all 😎
                    // i'm such an idiot that i just tried to copy and paste the epic gamer moment emoji too
                    // get added to unicode when?
                }
                if (!hasThrowaway) {
                    return COST_INF;
                }
                // we want it to be something that we don't have
                // even more of a pain to place something wrong
                return placeBlockCost * 1.5 * Baritone.settings().placeIncorrectBlockPenaltyMultiplier.value;
            } else {
                if (hasThrowaway) {
                    return placeBlockCost;
                } else {
                    return COST_INF;
                }
            }
        }

        /**
         * Never stand on, in or under a hole dug for an entity to drop through. It stays open until the entity has
         * dropped, and whatever gets built back over it then shuts in anything still down there.
         */
        @Override
        public double entityInTheWayCost(Moves move, int srcX, int srcY, int srcZ, int destX, int destY, int destZ) {
            if (inEntityHole(destX, destY - 1, destZ) || inEntityHole(destX, destY, destZ) || inEntityHole(destX, destY + 1, destZ)) {
                return COST_INF;
            }
            return super.entityInTheWayCost(move, srcX, srcY, srcZ, destX, destY, destZ);
        }

        private boolean inEntityHole(int x, int y, int z) {
            return !entityHoleCells.isEmpty() && entityHoleCells.contains(BetterBlockPos.longHash(x, y, z));
        }

        @Override
        public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
            if ((!allowBreak && !allowBreakAnyway.contains(current.getBlock())) || isPossiblyProtected(x, y, z)) {
                return COST_INF;
            }
            BlockState sch = getSchematic(x, y, z, current);
            if (sch != null) {
                if (sch.getBlock() instanceof AirBlock) {
                    // it should be air
                    // regardless of current contents, we can break it
                    return 1;
                }
                // it should be a real block
                // is it already that block?
                if (valid(bsi.get0(x, y, z), sch, false)) {
                    return Baritone.settings().breakCorrectBlockPenaltyMultiplier.value;
                } else {
                    // can break if it's wrong
                    // would be great to return less than 1 here, but that would actually make the cost calculation messed up
                    // since we're breaking a block, if we underestimate the cost, then it'll fail when it really takes the correct amount of time
                    return 1;

                }
                // TODO do blocks in render distace only?
                // TODO allow breaking blocks that we have a tool to harvest and immediately place back?
            } else {
                return 1; // why not lol
            }
        }
    }
}
