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
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.schematic.CellOverrides;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.behavior.highway.enums.HighwayState;
import baritone.pathing.movement.CalculationContext;
import baritone.process.BuilderProcess;
import baritone.test.HeadlessClient;
import baritone.utils.BlockStateInterface;
import baritone.utils.PortalPop;
import baritone.utils.pathing.BetterWorldBorder;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraft.world.phys.AABB;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A lit portal standing across the road. Its inside fills the road's whole cross-section, floor
 * and rails included, and its obsidian frame lies wholly outside the road: the sides in the
 * tunnel walls, the top over the ceiling, the bottom under the floor. With the portal blocks read
 * as built, a short pave leg that ends on it stops on the spot, with nothing mined or placed.
 * <p>
 * Here the leg is started the way nhwbuild starts it, and the builder's own code is run on it:
 * its scan of the slice, its choice of frame blocks and the goal it heads for. A frame block is
 * mined that the bot sees from the road with the aim {@code toBreakNearPlayer} uses, and the
 * portal reacts to that the way vanilla's {@code NetherPortalBlock.updateShape} does. What's left
 * is the floor and rails to pave, and once they're down the end check reads the leg done. The
 * builder's tick itself doesn't run headless (its calculation context reads the client's chunk
 * cache), so each step calls the method the tick would.
 */
public class PortalAcrossRoadTest {

    /**
     * The portal's plane across a 7-wide diagonal, top row (y 123) first, from six blocks before
     * the slice's first column to six past its last: n netherrack, B bedrock, O obsidian, a portal,
     * . air. Its 9 x 4 inside is the slice's whole cross-section.
     */
    private static final String[] DIAGONAL_PLANE = {
            "nBnBBnOOOOOOOOOnnBnnn",
            "nnnnnOaaaaaaaaaOnnnnn",
            "nnnnnOaaaaaaaaaOnnnnn",
            "nnnn.OaaaaaaaaaOnnnnn",
            "nnnn.OaaaaaaaaaOnnnnn",
            "nnnnnnOOOOOOOOOnnnnnn",
    };
    private static final int DIAGONAL_PLANE_LEAD = 6;

    /** The same across the stock 4-wide axis road: a 6 x 4 inside, rail columns included. */
    private static final String[] AXIS_PLANE = {
            "nnnnOOOOOOnnnn",
            "nnnOaaaaaaOnnn",
            "nnnOaaaaaaOnnn",
            "nnnOaaaaaaOnnn",
            "nnnOaaaaaaOnnn",
            "nnnnOOOOOOnnnn",
    };
    private static final int AXIS_PLANE_LEAD = 4;
    private static final int PLANE_TOP_Y = 123;

    private static final Vec3i DIAGONAL = new Vec3i(1, 0, -1);
    private static final Vec3i PLUS_X = new Vec3i(1, 0, 0);

    private HeadlessClient client;
    private Baritone baritone;
    private NetherHighwayBuilderBehavior behavior;

    @Before
    public void setUp() throws ReflectiveOperationException {
        client = HeadlessClient.get();
        baritone = client.baritone();
        behavior = baritone.getNetherHighwayBuilderBehavior();
        client.clearWorld();
        client.advanceTime(20 * 60); // a leg of its own, long after the last
        readyToAim();
    }

    @After
    public void tearDown() {
        Baritone.settings().highwayWidth.value = Baritone.settings().highwayWidth.defaultValue;
    }

    @Test
    public void aShortLegEndingOnAPortalAcrossADiagonalPopsItAndPaves() throws ReflectiveOperationException {
        Baritone.settings().highwayWidth.value = 7;
        HighwayContext context = startLeg(DIAGONAL, new Vec3i(-1001, 0, 1001), new Vec3i(-1000, 0, 1000));
        BlockPos end = context.layout().endPos();
        writePlane(end.offset(-DIAGONAL_PLANE_LEAD, 0, 0), Direction.EAST, DIAGONAL_PLANE, Direction.Axis.X);
        popAndPave(context, 9, Direction.Axis.X);
    }

    @Test
    public void aShortLegEndingOnAPortalAcrossAnAxisRoadPopsItAndPaves() throws ReflectiveOperationException {
        HighwayContext context = startLeg(PLUS_X, new Vec3i(10, 0, 0), new Vec3i(11, 0, 0));
        BlockPos end = context.layout().endPos();
        writePlane(end.offset(0, 0, -AXIS_PLANE_LEAD), Direction.SOUTH, AXIS_PLANE, Direction.Axis.Z);
        popAndPave(context, 6, Direction.Axis.Z);
    }

    /**
     * Every block of the frame {@link PortalPop} lists pops the portal, by vanilla's own shape
     * test, and the corners, which it leaves out, don't.
     */
    @Test
    public void theFrameBlocksListedAreExactlyTheOnesThatPopThePortal() throws ReflectiveOperationException {
        Baritone.settings().highwayWidth.value = 7;
        HighwayContext context = startLeg(DIAGONAL, new Vec3i(-1001, 0, 1001), new Vec3i(-1000, 0, 1000));
        BlockPos end = context.layout().endPos();
        BlockPos first = end.offset(-DIAGONAL_PLANE_LEAD, 0, 0);
        writePlane(first, Direction.EAST, DIAGONAL_PLANE, Direction.Axis.X);
        BlockPos inside = end.above(); // the slice's first column, floor level

        PortalPop.Plan plan = PortalPop.plan(client::getBlock, inside);
        assertNotNull(plan);
        assertEquals(Direction.Axis.X, plan.axis());
        assertEquals("the portal's blocks", 9 * 4, plan.cells().size());
        assertEquals("both sides, the top and the bottom", 2 * 4 + 2 * 9, plan.frames().size());
        assertTrue(plan.unsafe().isEmpty());
        assertNull(plan.heldBack());
        BlockGetter level = level();
        assertTrue("lit to begin with", PortalShape.findAnyShape(level, inside, Direction.Axis.X).isComplete());
        for (BlockPos frame : plan.frames()) {
            client.setBlock(frame, Blocks.AIR.defaultBlockState());
            assertFalse("without " + frame.toShortString(), PortalShape.findAnyShape(level, inside, Direction.Axis.X).isComplete());
            client.setBlock(frame, Blocks.OBSIDIAN.defaultBlockState());
        }
        BlockPos[] corners = {end.offset(-1, 0, 0), end.offset(9, 0, 0), end.offset(-1, 5, 0), end.offset(9, 5, 0)};
        for (BlockPos corner : corners) {
            assertFalse(corner.toShortString() + " is part of the frame", plan.frames().contains(corner));
            client.setBlock(corner, Blocks.OBSIDIAN.defaultBlockState());
            client.setBlock(corner, Blocks.AIR.defaultBlockState());
            assertTrue("without the corner " + corner.toShortString(), PortalShape.findAnyShape(level, inside, Direction.Axis.X).isComplete());
        }
    }

    /** A frame block with lava behind it in the wall is left alone, and the builder goes for another. */
    @Test
    public void aFrameBlockWithLavaBehindItIsLeftAlone() throws ReflectiveOperationException {
        Baritone.settings().highwayWidth.value = 7;
        HighwayContext context = startLeg(DIAGONAL, new Vec3i(-1001, 0, 1001), new Vec3i(-1000, 0, 1000));
        BlockPos end = context.layout().endPos();
        writePlane(end.offset(-DIAGONAL_PLANE_LEAD, 0, 0), Direction.EAST, DIAGONAL_PLANE, Direction.Axis.X);
        BlockPos side = end.offset(-1, 3, 0); // the low side's frame at head height
        client.setBlock(side.west(), Blocks.LAVA.defaultBlockState());

        Builder builder = new Builder();
        builder.scan(end);
        Set<BetterBlockPos> frames = builder.portalFrames();
        assertFalse("the frame block with lava behind it", frames.contains(new BetterBlockPos(side)));
        assertFalse("the others", frames.isEmpty());
        assertNull(builder.portalStuck());
    }

    /**
     * Fluids can't flow into portal blocks, so lava beside one is held back by it; popping it
     * would let the lava out over the road. The builder leaves it, and says why.
     */
    @Test
    public void aPortalHoldingBackLavaIsNotPopped() throws ReflectiveOperationException {
        Baritone.settings().highwayWidth.value = 7;
        HighwayContext context = startLeg(DIAGONAL, new Vec3i(-1001, 0, 1001), new Vec3i(-1000, 0, 1000));
        BlockPos end = context.layout().endPos();
        writePlane(end.offset(-DIAGONAL_PLANE_LEAD, 0, 0), Direction.EAST, DIAGONAL_PLANE, Direction.Axis.X);
        // the slice on past the portal starts a column further on, so this is wall beside its first block
        BlockPos lava = end.offset(0, 2, -1);
        assertEquals(Blocks.NETHERRACK, client.getBlock(lava).getBlock());
        client.setBlock(lava, Blocks.LAVA.defaultBlockState());

        Builder builder = new Builder();
        builder.scan(end);
        assertTrue(builder.portalFrames().isEmpty());
        assertNotNull(builder.portalStuck());
        assertTrue(builder.portalStuck(), builder.portalStuck().contains("lava"));
    }

    /**
     * The leg is under way with the portal standing. The builder finds the floor and rails
     * unbuilt and heads for the frame. From the road it sees a block of the frame, and mining that
     * pops the whole portal. The floor and rails then get paved and the leg is done.
     *
     * @param obsidianCells the floor and rail cells across the slice
     */
    private void popAndPave(HighwayContext context, int obsidianCells, Direction.Axis axis) throws ReflectiveOperationException {
        BlockPos end = context.layout().endPos();
        BlockPos first = context.layout().firstStartingPos();
        BlockPos feet = standOnFirstSlice(context);
        client.moveTo(feet);
        assertFalse("the end check with the portal standing", context.endTracker().isHighwayEndComplete());

        Builder builder = new Builder();
        assertTrue("the first slice is paved already", builder.scan(first).isEmpty());
        Set<BetterBlockPos> unbuilt = builder.scan(end); // onTick's repeat steps onto the end slice
        assertEquals("the floor and rails, every one a portal block", obsidianCells, unbuilt.size());
        for (BetterBlockPos pos : unbuilt) {
            assertTrue(pos.toShortString(), client.getBlock(pos).getBlock() instanceof NetherPortalBlock);
            assertEquals("the builder and the scan agree on " + pos.toShortString(), false, builder.cellCorrect(context, end, pos));
        }
        Set<BetterBlockPos> frames = builder.portalFrames();
        assertFalse("frame blocks to mine", frames.isEmpty());
        assertNull(builder.portalStuck());
        Goal goal = builder.goal(unbuilt);
        assertTrue("heading for the frame: " + goal, goal instanceof GoalComposite);
        for (Goal part : ((GoalComposite) goal).goals()) {
            BlockPos at = ((GoalGetToBlock) part).getGoalPos();
            assertTrue("a goal beside " + at.toShortString() + ", which isn't a frame block", frames.contains(new BetterBlockPos(at)));
        }

        // where the bot stands at the start of the leg, it sees one with the aim toBreakNearPlayer takes
        BlockPos mined = null;
        BlockPos minedFrom = null;
        for (BlockPos stand : firstSliceStands(context)) {
            client.moveTo(stand);
            for (BetterBlockPos frame : frames) {
                if (sees(frame)) {
                    mined = frame;
                    minedFrom = stand;
                    break;
                }
            }
            if (mined != null) {
                break;
            }
        }
        assertNotNull("a frame block in sight from the leg's first slice", mined);
        AABB body = baritone.getPlayerContext().player().getBoundingBox();
        for (BlockPos cell : PortalPop.plan(client::getBlock, unbuilt.iterator().next()).cells()) {
            assertFalse("standing at " + minedFrom.toShortString() + " puts us in the portal", body.intersects(new AABB(cell)));
        }

        mineAndUpdate(mined);
        for (BetterBlockPos pos : unbuilt) {
            assertTrue("popped: " + pos.toShortString(), client.getBlock(pos).isAir());
        }
        Set<BetterBlockPos> toPave = builder.scan(end);
        assertTrue("the floor and rails, now empty", toPave.containsAll(unbuilt));
        assertTrue("nothing of the portal left", builder.portalFrames().isEmpty());
        // assemble's placement goals ask MovementHelper about a client world, so this is the test
        // it makes before them: an empty cell, wanting a block we carry
        for (BetterBlockPos pos : toPave) {
            BlockState desired = builder.desired(pos);
            assertTrue(pos.toShortString(), client.getBlock(pos).isAir());
            assertTrue(pos.toShortString() + " wants " + desired, desired.is(Blocks.OBSIDIAN) || desired.is(Blocks.NETHERRACK));
            client.setBlock(pos, desired);
        }
        assertTrue("paved", builder.scan(end).isEmpty());
        client.moveTo(feet);
        assertTrue("the end check", context.endTracker().isHighwayEndComplete());
    }

    /**
     * A road already paved from well behind the leg to well past it, in netherrack, and the leg
     * built by nhwbuild with a start and an end and taken up by the builder from Nothing.
     */
    private HighwayContext startLeg(Vec3i direction, Vec3i start, Vec3i end) throws ReflectiveOperationException {
        HighwayContext context = context();
        behavior.build(0, 0, direction, false, true, end, start);
        BlockPos endSlice = context.layout().endPos();
        assertNotNull("the leg has an end", endSlice);
        assertEquals("a one slice step to the end", 1, context.layout().stepsAlongHighway(context.layout().firstStartingPos(), endSlice));
        for (int x = endSlice.getX() - 30; x <= endSlice.getX() + 30; x++) {
            for (int z = endSlice.getZ() - 30; z <= endSlice.getZ() + 30; z++) {
                for (int y = 112; y <= 127; y++) {
                    client.setBlock(new BlockPos(x, y, z), Blocks.NETHERRACK.defaultBlockState());
                }
            }
        }
        for (int i = -20; i <= 20; i++) {
            BlockPos anchor = endSlice.offset(direction.getX() * i, 0, direction.getZ() * i);
            for (int[] cell : HighwayConformanceDump.projectSlice(context.layout().schematic(), anchor.getX(), anchor.getY(), anchor.getZ())) {
                client.setBlock(new BlockPos(cell[0], cell[1], cell[2]), cell[3] == 1 ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
        }
        client.moveTo(standOnFirstSlice(context));
        context.currentState().handle(context);
        assertEquals(HighwayState.BuildingHighway, context.currentState().getState());
        assertTrue("the builder took the leg", baritone.getBuilderProcess().isActive());
        return context;
    }

    /** The cells of the leg's first slice the bot can stand in, the middle of the road first. */
    private List<BlockPos> firstSliceStands(HighwayContext context) {
        BlockPos first = context.layout().firstStartingPos();
        List<BlockPos> stands = new ArrayList<>();
        int feetY = context.layout().highwayFeetY();
        for (int[] cell : HighwayConformanceDump.projectSlice(context.layout().schematic(), first.getX(), first.getY(), first.getZ())) {
            BlockPos feet = new BlockPos(cell[0], feetY, cell[2]);
            if (cell[1] == feetY - 1 && cell[3] == 1 && client.getBlock(feet).isAir() && client.getBlock(feet.above()).isAir()) {
                stands.add(feet);
            }
        }
        BlockPos middle = stands.get(stands.size() / 2);
        stands.sort((a, b) -> Double.compare(a.distSqr(middle), b.distSqr(middle)));
        return stands;
    }

    private BlockPos standOnFirstSlice(HighwayContext context) {
        return firstSliceStands(context).get(0);
    }

    /** What toBreakNearPlayer asks of a frame block: in reach and in sight, a face middle or else the outline grid. */
    private boolean sees(BlockPos frame) {
        IPlayerContext ctx = baritone.getPlayerContext();
        double reach = ctx.playerController().getBlockReachDistance();
        Optional<Rotation> rot = RotationUtils.reachable(ctx, frame, reach);
        return rot.isPresent() || RotationUtils.reachableOutline(ctx, frame, reach, false).isPresent();
    }

    /** Lays {@code rows} out from {@code first}, top row at y 123, each row running {@code along}. */
    private void writePlane(BlockPos first, Direction along, String[] rows, Direction.Axis portalAxis) {
        BlockState portal = Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, portalAxis);
        for (int row = 0; row < rows.length; row++) {
            for (int i = 0; i < rows[row].length(); i++) {
                BlockState state = switch (rows[row].charAt(i)) {
                    case 'n' -> Blocks.NETHERRACK.defaultBlockState();
                    case 'B' -> Blocks.BEDROCK.defaultBlockState();
                    case 'O' -> Blocks.OBSIDIAN.defaultBlockState();
                    case 'a' -> portal;
                    case '.' -> Blocks.AIR.defaultBlockState();
                    default -> throw new IllegalArgumentException(rows[row]);
                };
                client.setBlock(first.relative(along, i).atY(PLANE_TOP_Y - row), state);
            }
        }
    }

    /**
     * Mines {@code frame}, and lets the portal react as vanilla's {@code NetherPortalBlock.updateShape}
     * does: a portal block turns to air when the block beside it along its own axis, above or
     * below it stops being portal and {@link PortalShape} no longer finds a complete portal from
     * it. Every block that goes updates its own neighbours in turn.
     */
    private void mineAndUpdate(BlockPos frame) {
        BlockGetter level = level();
        client.setBlock(frame, Blocks.AIR.defaultBlockState());
        ArrayDeque<BlockPos> changed = new ArrayDeque<>(List.of(frame));
        while (!changed.isEmpty()) {
            BlockPos from = changed.poll();
            for (Direction dir : Direction.values()) {
                BlockPos pos = from.relative(dir);
                BlockState state = client.getBlock(pos);
                if (!(state.getBlock() instanceof NetherPortalBlock)) {
                    continue;
                }
                Direction.Axis axis = state.getValue(NetherPortalBlock.AXIS);
                if (dir.getAxis().isHorizontal() && dir.getAxis() != axis) {
                    continue; // across the thin axis: never re-checked
                }
                if (!PortalShape.findAnyShape(level, pos, axis).isComplete()) {
                    client.setBlock(pos, Blocks.AIR.defaultBlockState());
                    changed.add(pos);
                }
            }
        }
    }

    /** The test world as vanilla code reads it. */
    private BlockGetter level() {
        return new BlockGetter() {
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                return client.getBlock(pos);
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                return getBlockState(pos).getFluidState();
            }

            @Override
            public int getHeight() {
                return 256;
            }

            @Override
            public int getMinY() {
                return 0;
            }
        };
    }

    /**
     * The builder on the leg it was given, with a calculation context whose blocks are the test
     * world's and whose schematic and origin are the builder's own, as {@code retarget} sets them.
     */
    private final class Builder {
        private final BuilderProcess process = baritone.getBuilderProcess();
        private final Class<?> contextClass;
        private final Object bcc;

        Builder() throws ReflectiveOperationException {
            contextClass = Class.forName("baritone.process.BuilderProcess$BuilderCalculationContext");
            bcc = unsafe().allocateInstance(contextClass);
            put(contextClass, bcc, "this$0", process);
            put(CalculationContext.class, bcc, "bsi", new BlockStateInterface(new BetterWorldBorder(new WorldBorder()), 0, 256) {
                @Override
                protected BlockState getUncached(int x, int y, int z) {
                    return client.getBlock(new BlockPos(x, y, z));
                }

                @Override
                public boolean isLoaded(int x, int z) {
                    return true;
                }

                @Override
                public boolean worldContainsLoadedChunk(int blockX, int blockZ) {
                    return true;
                }
            });
            put(contextClass, bcc, "entityHoleCells", LongSets.EMPTY_SET);
            put(contextClass, bcc, "watched", Long2ObjectMaps.emptyMap());
            put(contextClass, bcc, "overrides", CellOverrides.NONE);
            put(contextClass, bcc, "schematic", get(BuilderProcess.class, process, "schematic"));
            // what we carry: obsidian, netherrack and the rest of the hotbar empty
            List<BlockState> placeable = new ArrayList<>(Collections.nCopies(36, Blocks.AIR.defaultBlockState()));
            placeable.set(0, Blocks.OBSIDIAN.defaultBlockState());
            placeable.set(1, Blocks.NETHERRACK.defaultBlockState());
            put(BuilderProcess.class, process, "approxPlaceable", placeable);
            put(contextClass, bcc, "placeable", placeable.subList(0, 9));
        }

        /** The builder's slice at {@code origin}, rescanned in full: the cells it has left to do. */
        @SuppressWarnings("unchecked")
        Set<BetterBlockPos> scan(BlockPos origin) throws ReflectiveOperationException {
            put(BuilderProcess.class, process, "origin", new Vec3i(origin.getX(), origin.getY(), origin.getZ()));
            put(contextClass, bcc, "originX", origin.getX());
            put(contextClass, bcc, "originY", origin.getY());
            put(contextClass, bcc, "originZ", origin.getZ());
            put(BuilderProcess.class, process, "incorrectPositions", null);
            call(BuilderProcess.class, process, "recalc", bcc);
            Set<BetterBlockPos> incorrect = (Set<BetterBlockPos>) get(BuilderProcess.class, process, "incorrectPositions");
            call(BuilderProcess.class, process, "updatePortalPops", bcc);
            return new LinkedHashSet<>(incorrect);
        }

        @SuppressWarnings("unchecked")
        Set<BetterBlockPos> portalFrames() throws ReflectiveOperationException {
            return new LinkedHashSet<>((Set<BetterBlockPos>) get(BuilderProcess.class, process, "toBreakPortal"));
        }

        String portalStuck() throws ReflectiveOperationException {
            return (String) get(BuilderProcess.class, process, "portalStuck");
        }

        Goal goal(Collection<BetterBlockPos> candidates) throws ReflectiveOperationException {
            return (Goal) call(BuilderProcess.class, process, "assemble", bcc, get(BuilderProcess.class, process, "approxPlaceable"), false, candidates);
        }

        BlockState desired(BlockPos pos) throws ReflectiveOperationException {
            return (BlockState) call(contextClass, bcc, "getSchematic", pos.getX(), pos.getY(), pos.getZ(), client.getBlock(pos));
        }

        /**
         * Whether the highway scan reads the cell as built, checked against the builder's own
         * {@code valid}: the two have to agree.
         */
        boolean cellCorrect(HighwayContext context, BlockPos slice, BlockPos pos) throws ReflectiveOperationException {
            Object scan = call(HighwayScanner.class, context.scanner(), "cellScan",
                    slice, pos.getX() - slice.getX(), pos.getY() - slice.getY(), pos.getZ() - slice.getZ());
            boolean valid = (Boolean) call(BuilderProcess.class, null, "valid", client.getBlock(pos), desired(pos), false);
            boolean correct = "CORRECT".equals(scan.toString());
            assertEquals("scan and builder at " + pos.toShortString(), valid, correct);
            return correct;
        }
    }

    /**
     * What the aim code reads off the client that HeadlessClient leaves unset: movement input, a
     * survival game mode for the reach, a standing eye, the ground under us, hands, and the mouse
     * sensitivity rotations are rounded to.
     */
    private void readyToAim() throws ReflectiveOperationException {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        player.input = new ClientInput();
        MultiPlayerGameMode gameMode = (MultiPlayerGameMode) unsafe().allocateInstance(MultiPlayerGameMode.class);
        put(MultiPlayerGameMode.class, gameMode, "localPlayerMode", GameType.SURVIVAL);
        put(Minecraft.class, mc, "gameMode", gameMode);
        put(Entity.class, player, "eyeHeight", 1.62f);
        put(Entity.class, player, "onGround", true);
        put(LivingEntity.class, player, "equipment", new EntityEquipment());
        OptionInstance<?> sensitivity = (OptionInstance<?>) unsafe().allocateInstance(OptionInstance.class);
        put(OptionInstance.class, sensitivity, "value", 0.5);
        put(Options.class, mc.options, "sensitivity", sensitivity);
        player.getInventory().setItem(0, new ItemStack(Items.OBSIDIAN, 64));
        player.getInventory().setItem(1, new ItemStack(Items.NETHERRACK, 64));
    }

    private HighwayContext context() throws ReflectiveOperationException {
        return (HighwayContext) get(NetherHighwayBuilderBehavior.class, behavior, "highwayContext");
    }

    private static Unsafe unsafe() throws ReflectiveOperationException {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static void put(Class<?> owner, Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object get(Class<?> owner, Object target, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object call(Class<?> owner, Object target, String name, Object... args) throws ReflectiveOperationException {
        for (Method method : owner.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                method.setAccessible(true);
                return method.invoke(target, args);
            }
        }
        throw new NoSuchMethodException(owner.getSimpleName() + "." + name + " with " + args.length + " parameters");
    }
}
