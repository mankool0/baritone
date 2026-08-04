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
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.process.IBuilderProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.schematic.*;
import baritone.api.schematic.format.ISchematicFormat;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.behavior.LookBehavior;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.path.PathExecutor;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.PathingCommandContext;
import baritone.utils.schematic.MapArtSchematic;
import baritone.utils.schematic.SelectionSchematic;
import baritone.utils.schematic.SchematicSystem;
import baritone.utils.schematic.litematica.LitematicaHelper;
import baritone.utils.schematic.schematica.SchematicaHelper;
import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.util.Tuple;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.BlockItem;
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
    private List<BetterBlockPos> toBreakEntity = new ArrayList<>();
    public int stopAtHeight = 0;

    /**
     * How far the aimed-at yaw may sit from the movement yaw before a pitch-only aim is hopeless.
     */
    private static final float PRINTER_MAX_PITCH_ONLY_YAW_DIFF = 25;

    /**
     * Consecutive placement ticks before the printer stops placing to let breaking catch up.
     */
    private static final int PRINTER_MAX_BREAK_STARVATION = 20;

    /**
     * How many nodes ahead on the current path the printer treats as off limits for breaking.
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

    private int printerPlaceCooldown;
    private int printerBreakCooldown;
    private int printerBreakGrace;
    private boolean legitBreakSessionLastTick;
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

    private record PrinterAction(boolean placed, boolean broke) {

        private static final PrinterAction NONE = new PrinterAction(false, false);

        boolean acted() {
            return placed || broke;
        }
    }

    public BuilderProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void build(String name, ISchematic schematic, Vec3i origin) {
        this.name = name;
        this.schematic = schematic;
        this.realSchematic = null;
        boolean buildingSelectionSchematic = schematic instanceof SelectionSchematic;
        if (!Baritone.settings().buildSubstitutes.value.isEmpty()) {
            this.schematic = new SubstituteSchematic(this.schematic, Baritone.settings().buildSubstitutes.value);
        }
        if (Baritone.settings().buildSchematicMirror.value != net.minecraft.world.level.block.Mirror.NONE) {
            this.schematic = new MirroredSchematic(this.schematic, Baritone.settings().buildSchematicMirror.value);
        }
        if (Baritone.settings().buildSchematicRotation.value != net.minecraft.world.level.block.Rotation.NONE) {
            this.schematic = new RotatedSchematic(this.schematic, Baritone.settings().buildSchematicRotation.value);
        }
        // TODO this preserves the old behavior, but maybe we should bake the setting value right here
        this.schematic = new MaskSchematic(this.schematic) {
            @Override
            public boolean partOfMask(int x, int y, int z, BlockState current) {
                // partOfMask is only called inside the schematic so desiredState is not null
                return !Baritone.settings().buildSkipBlocks.value.contains(this.desiredState(x, y, z, current, Collections.emptyList()).getBlock());
            }
        };
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
            BlockState curr = bcc.bsi.get0(pos);
            if (curr.getBlock() != Blocks.AIR && !(curr.getBlock() instanceof LiquidBlock) && !valid(curr, Blocks.AIR.defaultBlockState(), false)) {
                Optional<Rotation> rot = RotationUtils.reachable(ctx, pos, ctx.playerController().getBlockReachDistance());
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
                        desirableOnHotbar.add(desired);
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
        for (Direction against : Direction.values()) {
            BetterBlockPos placeAgainstPos = new BetterBlockPos(x, y, z).relative(against);
            BlockState placeAgainstState = bsi.get0(placeAgainstPos);
            if (MovementHelper.isReplaceable(placeAgainstPos.x, placeAgainstPos.y, placeAgainstPos.z, placeAgainstState, bsi)) {
                continue;
            }
            if (!toPlace.canSurvive(ctx.world(), new BlockPos(x, y, z))) {
                continue;
            }
            if (!placementPlausible(new BetterBlockPos(x, y, z), toPlace)) {
                continue;
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
        for (int i = 0; i < 9; i++) {
            ItemStack stack = ctx.player().getInventory().items.get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
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
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
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
        if (printerPlaceCooldown > 0) {
            printerPlaceCooldown--;
        }
        if (printerBreakCooldown > 0) {
            printerBreakCooldown--;
        }
        if (printerBreakGrace > 0) {
            printerBreakGrace--;
        }
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

    private PrinterAction printerFire(BuilderCalculationContext bcc, boolean mayPlace) {
        if (printerInventoryUnsettled()) {
            return PrinterAction.NONE; // hotbar contents not confirmed by the server yet
        }
        Rotation rot = baritone.getLookBehavior().getServerRotation().orElse(null);
        if (rot == null) {
            return PrinterAction.NONE;
        }
        double reach = ctx.playerController().getBlockReachDistance();
        Vec3 predictedEye = ctx.player().getEyePosition(1.0f).add(ctx.player().getDeltaMovement());
        int breaksLeft = printerBreakCooldown <= 0 ? Math.max(1, Baritone.settings().multiBreak.value) : 0;
        int placesLeft = mayPlace && printerPlaceCooldown <= 0 ? Math.max(1, Baritone.settings().printerMultiPlace.value) : 0;
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
            if (breaksLeft > 0 && !placed && printerTryBreak(bcc, bhr, clicked)) {
                // the block is gone client-side, the same ray now continues to whatever is behind it
                broke = true;
                breaksLeft--;
                continue;
            }
            int usedSlot = placesLeft > 0 ? printerTryPlace(bcc, bhr, clicked, rot, placed ? placeSlot : -1) : -1;
            if (usedSlot >= 0) {
                placed = true;
                placeSlot = usedSlot;
                placesLeft--;
                continue;
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
        if (printerInventoryUnsettled()) {
            return PrinterAction.NONE;
        }
        double reach = ctx.playerController().getBlockReachDistance();
        double reachSq = reach * reach;
        Vec3 eye = ctx.player().getEyePosition(1.0f);
        Vec3 predictedEye = eye.add(ctx.player().getDeltaMovement());
        // only used to predict getStateForPlacement orientation for directional blocks
        Rotation rot = baritone.getLookBehavior().getServerRotation().orElse(ctx.playerRotations());
        int breaksLeft = printerBreakCooldown <= 0 ? Math.max(1, Baritone.settings().multiBreak.value) : 0;
        int placesLeft = mayPlace && printerPlaceCooldown <= 0 ? Math.max(1, Baritone.settings().printerMultiPlace.value) : 0;

        List<BetterBlockPos> candidates = new ArrayList<>(incorrectPositions);
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
            if (printerTryBreak(bcc, bhr, pos)) {
                broke = true;
                breaksLeft--;
                // candidate stays: the place pass may fill this same cell this tick
            }
        }

        // place pass
        for (BetterBlockPos pos : candidates) {
            if (placesLeft <= 0) {
                break;
            }
            BlockState curr = bcc.bsi.get0(pos);
            if (!(curr.getBlock() instanceof AirBlock) && !(curr.getBlock() instanceof LiquidBlock)
                    && !MovementHelper.isReplaceable(pos.x, pos.y, pos.z, curr, bcc.bsi)) {
                continue; // needs breaking, not placing
            }
            for (Direction d : Direction.values()) { // DOWN first: click the UP face of the support below
                BetterBlockPos neighbor = pos.relative(d);
                if (!MovementHelper.canPlaceAgainst(bcc.bsi, neighbor)) {
                    continue;
                }
                Direction face = d.getOpposite();
                Vec3 hitVec = VecUtils.getBlockPosCenter(neighbor)
                        .add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
                if (eye.distanceToSqr(hitVec) > reachSq || predictedEye.distanceToSqr(hitVec) > reachSq) {
                    continue;
                }
                // plain BlockPos on purpose: the hit result flows into vanilla world mutation, and
                // BetterBlockPos's different hashCode must not leak into the block entity map
                BlockHitResult bhr = new BlockHitResult(hitVec, face, new BlockPos(neighbor.x, neighbor.y, neighbor.z), false);
                int usedSlot = printerTryPlace(bcc, bhr, neighbor, rot, placed ? placeSlot : -1);
                if (usedSlot >= 0) {
                    placed = true;
                    placeSlot = usedSlot;
                    placesLeft--;
                    printerNoteNoRotatePlace(pos);
                    break; // next candidate
                }
            }
        }
        return printerFinish(placed, broke);
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

    private boolean printerTryBreak(BuilderCalculationContext bcc, BlockHitResult bhr, BetterBlockPos clicked) {
        if (!printerBreakAllowed() || !incorrectPositions.contains(clicked) || !printerBreakSafe(clicked)) {
            return false;
        }
        BlockState state = bcc.bsi.get0(clicked);
        if (state.getBlock() instanceof AirBlock || state.getBlock() instanceof LiquidBlock
                || MovementHelper.isReplaceable(clicked.x, clicked.y, clicked.z, state, bcc.bsi)) {
            return false;
        }
        BlockState desired = bcc.getSchematic(clicked.x, clicked.y, clicked.z, state);
        if (desired == null || valid(state, desired, false)) {
            return false;
        }
        if (!printerCanInstaBreak(state, clicked)) {
            return false; // only single-tick breaks can be done without stopping
        }
        MovementHelper.switchToBestToolFor(ctx, state);
        // clickBlock doesn't sync the hotbar the way the use path does, so the tool we just
        // picked would otherwise reach the server a tick after the dig it applies to
        ctx.playerController().syncHeldItem();
        if (!ctx.playerController().clickBlock(clicked, bhr.getDirection())) {
            return false;
        }
        ctx.player().swing(InteractionHand.MAIN_HAND);
        ctx.playerController().setDestroyDelay(0);
        return true;
    }

    /**
     * @param requiredSlot if >= 0, only place when this hotbar slot works: changing the selected
     *                     slot after a use packet within the same tick is not vanilla-plausible
     * @return the hotbar slot used, or -1 if nothing was placed
     */
    private int printerTryPlace(BuilderCalculationContext bcc, BlockHitResult bhr, BetterBlockPos clicked, Rotation rot, int requiredSlot) {
        BetterBlockPos placeAt = BetterBlockPos.from(clicked.relative(bhr.getDirection()));
        if (!incorrectPositions.contains(placeAt)) {
            return -1;
        }
        if (!MovementHelper.canPlaceAgainst(bcc.bsi, clicked) || printerAvoidClicking(bcc.bsi.get0(clicked))) {
            return -1;
        }
        BlockState curr = bcc.bsi.get0(placeAt);
        if (!MovementHelper.isReplaceable(placeAt.x, placeAt.y, placeAt.z, curr, bcc.bsi)) {
            return -1;
        }
        BlockState desired = bcc.getSchematic(placeAt.x, placeAt.y, placeAt.z, curr);
        if (desired == null || desired.getBlock() instanceof AirBlock || valid(curr, desired, false)) {
            return -1;
        }
        if (!desired.canSurvive(ctx.world(), placeAt) || !placementPlausible(placeAt, desired)) {
            return -1;
        }
        OptionalInt slot = hasAnyItemThatWouldPlace(desired, bhr, rot);
        if (!slot.isPresent() || (requiredSlot >= 0 && slot.getAsInt() != requiredSlot)) {
            return -1;
        }
        ctx.player().getInventory().selected = slot.getAsInt();
        InteractionResult result = ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, bhr);
        if (!result.consumesAction()) {
            return -1;
        }
        if (result == InteractionResult.SUCCESS) {
            // vanilla only swings on SUCCESS, not CONSUME
            ctx.player().swing(InteractionHand.MAIN_HAND);
        }
        return slot.getAsInt();
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

        List<BetterBlockPos> candidates = new ArrayList<>(incorrectPositions);
        // measured to the block center, so allow a block's bounding radius (sqrt(3)/2) of slack
        // before discarding it; the exact reach is enforced per hit point further down
        double maxDistSq = (reach + 1) * (reach + 1);
        candidates.removeIf(pos -> eye.distanceToSqr(VecUtils.getBlockPosCenter(pos)) > maxDistSq);
        candidates.sort(Comparator.comparingDouble(pos -> eye.distanceToSqr(VecUtils.getBlockPosCenter(pos))));

        for (BetterBlockPos pos : candidates) {
            BlockState curr = bcc.bsi.get0(pos);
            BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, curr);
            if (desired == null || valid(curr, desired, false)) {
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
                        || !placementPlausible(pos, desired)) {
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
     *                     only a prediction — {@link #printerFire} re-checks the rotation the
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

    private boolean printerUsable() {
        return ctx.player() != null
                && ctx.minecraft().gameMode != null
                && (ctx.minecraft().screen == null || ctx.minecraft().screen instanceof ChatScreen || ctx.minecraft().screen instanceof InventoryScreen)
                && ctx.player().containerMenu.getCarried().isEmpty()
                && !ctx.player().isUsingItem()
                && !ctx.player().isHandsBusy()
                && !ctx.player().isFallFlying()
                && !ctx.player().isPassenger();
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

    private boolean printerBreakAllowed() {
        return Baritone.settings().printerBreak.value && printerBreakGrace <= 0 && ctx.player().onGround();
    }

    /**
     * @return whether the tool {@link MovementHelper#switchToBestToolFor} would pick breaks this
     * block within a single tick. Probes with the same switch the dig itself performs, so aiming
     * and firing can never disagree about what is instant.
     */
    private boolean printerCanInstaBreak(BlockState state, BlockPos pos) {
        var inventory = ctx.player().getInventory();
        int previous = inventory.selected;
        MovementHelper.switchToBestToolFor(ctx, state);
        boolean instant = state.getDestroyProgress(ctx.player(), ctx.world(), pos) >= 1.0f;
        inventory.selected = previous;
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
        return onTick(calcFailed, isSafeToCancel, 0);
    }

    private PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel, int recursions) {
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
        BuilderCalculationContext bcc = new BuilderCalculationContext();
        if (!recalc(bcc)) {
            if (Baritone.settings().buildInLayers.value && layer * Baritone.settings().layerHeight.value < stopAtHeight) {
                logDirect("Starting layer " + layer);
                layer++;
                return onTick(calcFailed, isSafeToCancel, recursions + 1);
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
            origin = new BlockPos(origin).offset(repeat);
            if (!Baritone.settings().buildRepeatSneaky.value) {
                schematic.reset();
            }
            if (Baritone.settings().buildRepeatLog.value) {
                logDirect("Repeating build in vector " + repeat + ", new origin is " + origin);
            }
            return onTick(calcFailed, isSafeToCancel, recursions + 1);
        }
        if (Baritone.settings().distanceTrim.value) {
            trim();
        }

        List<Optional<Tuple<BetterBlockPos, Rotation>>> toBreak = toBreakNearPlayer(bcc);

        // The printer acts without stopping pathing; the stationary break/place paths below only
        // engage on ticks it didn't act, since they'd send conflicting packets otherwise.
        boolean breakSession = legitBreakSessionLastTick;
        legitBreakSessionLastTick = false;
        // Never place while a stationary break is being lined up: a placement skips the break
        // branch for that tick, releasing the attack and resetting the dig progress, and the
        // placed block can wall off the break target. Nor let placements starve breaking forever.
        boolean mayPlace = !breakSession && printerBreakStarvation < PRINTER_MAX_BREAK_STARVATION;
        PrinterAction printerAction = PrinterAction.NONE;
        if (Baritone.settings().printer.value && printerUsable()) {
            printerAction = printerTick(bcc, mayPlace);
        }
        if (!toBreak.isEmpty() && printerAction.placed() && !printerAction.broke()) {
            printerBreakStarvation++;
        } else {
            printerBreakStarvation = 0;
        }

        if (!toBreak.isEmpty() && isSafeToCancel && ctx.player().onGround() && !printerAction.acted()) {
            legitBreakSessionLastTick = true;
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
                if (!printerCanInstaBreak(bcc.get(pos), pos)) {
                    printerBreakGrace = Math.max(printerBreakGrace, Math.max(1, Baritone.settings().blockBreakSpeed.value));
                }
            }

            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        List<BlockState> desirableOnHotbar = new ArrayList<>();
        Optional<Placement> toPlace = searchForPlacables(bcc, desirableOnHotbar);
        if (!printerAction.acted() && toPlace.isPresent() && isSafeToCancel && ctx.player().onGround() && ticks <= 0) {
            if (!printerInventoryUnsettled()) {
                Rotation rot = toPlace.get().rot;
                baritone.getLookBehavior().updateTarget(rot, true);
                ctx.player().getInventory().selected = toPlace.get().hotbarSelection;
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
            outer:
            for (BlockState desired : desirableOnHotbar) {
                for (int i = 0; i < 9; i++) {
                    if (valid(approxPlaceable.get(i), desired, true)) {
                        usefulSlots.add(i);
                        continue outer;
                    }
                }
                noValidHotbarOption.add(desired);
            }

            outer:
            for (int i = 9; i < 36; i++) {
                for (BlockState desired : noValidHotbarOption) {
                    if (valid(approxPlaceable.get(i), desired, true)) {
                        if (printerInventoryUnsettled()) {
                            // a move is already in flight; hold instead of clicking again
                            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                        }
                        boolean requested;
                        if (Baritone.settings().fillEmptyHotbarSlots.value) {
                            // pull every matching stack the empty slots have room for, not just this one
                            List<Integer> sources = new ArrayList<>();
                            for (int j = i; j < 36; j++) {
                                if (valid(approxPlaceable.get(j), desired, true)) {
                                    sources.add(j);
                                }
                            }
                            requested = baritone.getInventoryBehavior().attemptToFillHotbarFrom(sources, usefulSlots::contains);
                        } else {
                            requested = baritone.getInventoryBehavior().attemptToPutOnHotbar(i, usefulSlots::contains);
                        }
                        if (!requested) {
                            // awaiting inventory move, so pause
                            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                        }
                        break outer;
                    }
                }
            }
        }

        if (printerAction.acted() && isSafeToCancel
                && Baritone.settings().highwayEndDistance.value != -1
                && baritone.getNetherHighwayBuilderBehavior().isBuildingHighwayState()) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        Goal goal = assemble(bcc, approxPlaceable.subList(0, 9));
        if (goal == null) {
            goal = assemble(bcc, approxPlaceable, true); // we're far away, so assume that we have our whole inventory to recalculate placeable properly
            if (goal == null) {
                if (Baritone.settings().skipFailedLayers.value && Baritone.settings().buildInLayers.value && layer * Baritone.settings().layerHeight.value < realSchematic.heightY()) {
                    logDirect("Skipping layer that I cannot construct! Layer #" + layer);
                    layer++;
                    return onTick(calcFailed, isSafeToCancel, recursions + 1);
                }
                logDirect("Unable to do it. Pausing. resume to resume, cancel to cancel");
                paused = true;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
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
        if (incorrectPositions.isEmpty()) {
            fullRecalc(bcc);
        }
        return !incorrectPositions.isEmpty();
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
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired != null) {
                        // we care about this position
                        BetterBlockPos pos = new BetterBlockPos(x, y, z);
                        if (valid(bcc.bsi.get0(x, y, z), desired, false)) {
                            incorrectPositions.remove(pos);
                            observedCompleted.add(BetterBlockPos.longHash(pos));
                        } else {
                            incorrectPositions.add(pos);
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

    private Goal assemble(BuilderCalculationContext bcc, List<BlockState> approxPlaceable) {
        return assemble(bcc, approxPlaceable, false);
    }

    private Goal assemble(BuilderCalculationContext bcc, List<BlockState> approxPlaceable, boolean logMissing) {
        List<BetterBlockPos> placeable = new ArrayList<>();
        List<BetterBlockPos> breakable = new ArrayList<>();
        List<BetterBlockPos> sourceLiquids = new ArrayList<>();
        List<BetterBlockPos> flowingLiquids = new ArrayList<>();
        Map<BlockState, Integer> missing = new HashMap<>();
        AtomicBoolean liquidDetected = new AtomicBoolean(false);
        AtomicBoolean entityDetected = new AtomicBoolean(false);
        AtomicBoolean boatDetected = new AtomicBoolean(false);
        List<BetterBlockPos> outOfBounds = new ArrayList<>();
        incorrectPositions.forEach(pos -> {
            BlockState state = bcc.bsi.get0(pos);
            if (state.getBlock() instanceof AirBlock) {
                // Mine out blocks below if entity in the way
                toBreakEntity.clear();
                if (!checkNoEntityCollision(new AABB(pos), ctx.player())) {
                    entityDetected.set(true);
                    int xSize = 1;
                    int zSize = 1;
                    int ySize = 2;
                    boolean mineObby = false;
                    List<Entity> entityList = ctx.world().getEntities((Entity)null, new AABB(pos));
                    for (Entity entity : entityList) {
                        if (entity instanceof EnderMan) {
                            ySize = 3;
                        }
                        else if (entity instanceof Boat) {
                            //xSize = 2;
                            //ySize = 2;
                            //mineObby = true;
                            // can't do boats lol
                            boatDetected.set(true);
                        }
                    }
                    for (int x = -xSize; x <= xSize; x++) {
                        for (int z = -zSize; z <= zSize; z++) {
                            for (int y = -ySize; y <= 0; y++) {
                                BlockState lowerState = bcc.bsi.get0(pos.offset(x, y, z));
                                if ((lowerState.is(Blocks.OBSIDIAN) || lowerState.is(Blocks.CRYING_OBSIDIAN)) && !mineObby) {
                                    continue;
                                }
                                if (!(lowerState.getBlock() instanceof AirBlock) && !(lowerState.getBlock() instanceof LiquidBlock)) {
                                    breakable.add(new BetterBlockPos(pos.offset(x, y, z)));
                                    toBreakEntity.add(new BetterBlockPos(pos.offset(x, y, z)));
                                }
                            }
                        }
                    }
                } else {
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
            toBreakEntity.forEach(pos -> toBreak.add(breakGoal(pos, bcc)));
            if (!toBreak.isEmpty()) {
                return new GoalComposite(toBreak.toArray(new Goal[0]));
            }
        }
        breakable.forEach(pos -> toBreak.add(breakGoal(pos, bcc)));
        List<Goal> toPlace = new ArrayList<>();
        placeable.forEach(pos -> {
            if (!placeable.contains(pos.below()) && !placeable.contains(pos.below(2))) {
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

    @Override
    public void onLostControl() {
        incorrectPositions = null;
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
        printerBreakStarvation = 0;
        printerMovementForced = false;
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
            ItemStack stack = ctx.player().getInventory().items.get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
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
            if (map1.get(prop) != map2.get(prop)
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

        private final List<BlockState> placeable;
        private final ISchematic schematic;
        private final int originX;
        private final int originY;
        private final int originZ;

        public BuilderCalculationContext() {
            super(BuilderProcess.this.baritone, true); // wew lad
            this.placeable = approxPlaceable(9);
            this.schematic = BuilderProcess.this.schematic;
            this.originX = origin.getX();
            this.originY = origin.getY();
            this.originZ = origin.getZ();

            this.jumpPenalty += 10;
            this.backtrackCostFavoringCoefficient = 1;
            this.avoidNetherPortals = true;
        }

        private BlockState getSchematic(int x, int y, int z, BlockState current) {
            if (schematic.inSchematic(x - originX, y - originY, z - originZ, current)) {
                // Case of special schematic
                if (schematic instanceof MaskSchematic && ((MaskSchematic)schematic).getSchematic() instanceof CompositeSchematic) {
                    ISchematic ourSchem = ((CompositeSchematic)((MaskSchematic)schematic).getSchematic()).getSchematic(x - originX, y - originY, z - originZ, current).schematic;
                    if (ourSchem instanceof WhiteBlackSchematic && ((WhiteBlackSchematic) ourSchem).isValidIfUnder() &&
                            MovementHelper.isBlockNormalCube(ctx.world().getBlockState(new BlockPos(x, y + 1, z)))) {
                        return current;
                    }
                }

                return schematic.desiredState(x - originX, y - originY, z - originZ, current, BuilderProcess.this.approxPlaceable);
            } else {
                return null;
            }
        }

        @Override
        public double costOfPlacingAt(int x, int y, int z, BlockState current) {
            if (isPossiblyProtected(x, y, z) || !worldBorder.canPlaceAt(x, z)) { // make calculation fail properly if we can't build
                return COST_INF;
            }
            BlockState sch = getSchematic(x, y, z, current);
            if (sch != null) {
                // TODO this can return true even when allowPlace is off.... is that an issue?
                if (sch.getBlock() instanceof AirBlock) {
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
