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
import baritone.behavior.highway.enums.HighwayState;
import baritone.behavior.highway.enums.LocationType;
import baritone.process.BuilderProcess;
import baritone.test.HeadlessClient;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * A leg of one or two slices with work left in its first slice, interrupted by a fight. The fight
 * cancels the builder and hands back to BuildingHighway, whose first check is the end check, and
 * near a leg's start that check's check-back clamps onto the first slice. A scan that begins one
 * slice past where it starts never looks at it, and the leg ends on the spot with the block still
 * there.
 */
public class ShortLegFirstSliceTest {

    private static final int START_X = 10; // a +X road with its low rail on z = -3, stock profile
    private static final int ROAD_Z = -3;

    private HeadlessClient client;
    private Baritone baritone;
    private NetherHighwayBuilderBehavior behavior;

    @Before
    public void setUp() {
        client = HeadlessClient.get();
        baritone = client.baritone();
        behavior = baritone.getNetherHighwayBuilderBehavior();
        client.clearWorld();
        // every test is a leg of its own, long after the last: the end-stall clock lives on in the context
        client.advanceTime(20 * 60);
    }

    @Test
    public void aFightOnATwoSliceLegRestartsTheBuilderStandingOnTheEndSlice() {
        HighwayContext context = startLeg(START_X + 1, START_X + 1);
        BlockPos chest = new BlockPos(START_X, context.layout().highwayFeetY(), ROAD_Z + 1);
        client.setBlock(chest, Blocks.ENDER_CHEST.defaultBlockState());
        fightThenRestartsOnTheFirstSlice(context);
    }

    @Test
    public void aFightOnATwoSliceLegRestartsTheBuilderStandingOnTheFirstSlice() {
        HighwayContext context = startLeg(START_X + 1, START_X);
        BlockPos chest = new BlockPos(START_X, context.layout().highwayFeetY(), ROAD_Z + 1);
        client.setBlock(chest, Blocks.ENDER_CHEST.defaultBlockState());
        fightThenRestartsOnTheFirstSlice(context);
    }

    @Test
    public void aFightOnAOneSliceLegRestartsTheBuilder() {
        HighwayContext context = startLeg(START_X, START_X);
        BlockPos floor = new BlockPos(START_X, context.layout().highwayFloorY(), ROAD_Z + 1);
        assertSame("the road's floor", Blocks.OBSIDIAN, client.getBlock(floor).getBlock());
        client.setBlock(floor, Blocks.AIR.defaultBlockState());
        fightThenRestartsOnTheFirstSlice(context);
    }

    /** The same fight with nothing left to build does end the leg, so the harness can tell the two apart. */
    @Test
    public void aFightOnAFinishedTwoSliceLegEndsIt() {
        HighwayContext context = startLeg(START_X + 1, START_X + 1);
        fight(context);
        assertTrue("the end check on a finished leg", context.endTracker().isHighwayEndComplete());
        context.currentState().handle(context);
        assertTrue("the leg ended", context.paused());
    }

    /**
     * Where the scans start: on the leg's first slice when the check-back clamps onto it, on the
     * build line and the liquid line alike, and one past their start anywhere else, as they always
     * have. Every direction, paving and digging, since the liquid line is offset across the road and
     * a diagonal's start clamp is a projection: digging +X+Z and -X+Z put the liquid line's clamp a
     * slice along from the build line's. And an angled pattern, whose slices each step a single axis.
     */
    @Test
    public void scansStartOnTheFirstSliceOnlyWhereTheCheckBackClampsOntoIt() {
        HighwayContext context = context();
        String[] patterns = {null, null, null, null, null, null, null, null, "XXZXXZXXXZ", "ZZX"};
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}, {1, 1}, {-1, 1}};
        for (int c = 0; c < 2 * directions.length; c++) {
            int d = c % directions.length;
            boolean pave = c < directions.length;
            int dx = directions[d][0];
            int dz = directions[d][1];
            String which = dx + "," + dz + (patterns[d] != null ? " " + patterns[d] : "") + (pave ? " paving" : " digging");
            behavior.build(0, 0, new Vec3i(dx, 0, dz), true, pave, null, new Vec3i(10 * dx, 0, 10 * dz), patterns[d]);
            HighwayLayout layout = context.layout();
            assertNotNull(which, layout.firstStartingPos());
            Vec3 first = vec(layout.firstStartingPos());
            Vec3 nearStart = vec(layout.sliceAlongLine(layout.originVector(), first, 3, LocationType.HighwayBuild));
            Vec3 farFromStart = vec(layout.sliceAlongLine(layout.originVector(), first, 3 * layout.highwayCheckBackDistance(), LocationType.HighwayBuild));
            Vec3[] lines = {layout.originVector(), layout.liqOriginVector()};
            LocationType[] types = {LocationType.HighwayBuild, LocationType.ShulkerEchestInteraction};
            for (int l = 0; l < lines.length; l++) {
                String line = which + (l == 0 ? " build line" : " liquid line");
                BlockPos clamped = layout.sliceAlongLine(lines[l], nearStart, -layout.highwayCheckBackDistance(), types[l]);
                assertTrue(line + ": the check-back clamped onto the first slice", layout.isFirstSlice(clamped, lines[l], types[l]));
                BlockPos next = layout.sliceAlongLine(lines[l], vec(clamped), 1, types[l]);
                assertFalse(line + ": the slice after it", layout.isFirstSlice(next, lines[l], types[l]));
                BlockPos unclamped = layout.sliceAlongLine(lines[l], farFromStart, -layout.highwayCheckBackDistance(), types[l]);
                assertFalse(line + ": a check-back clear of the start", layout.isFirstSlice(unclamped, lines[l], types[l]));
            }
        }
    }

    private static Vec3 vec(BlockPos pos) {
        return new Vec3(pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * A road already paved from well behind the start to well past the end, the leg built by
     * nhwbuild with a start and an end, and the builder started on it from Nothing.
     */
    private HighwayContext startLeg(int endX, int feetX) {
        HighwayContext context = context();
        behavior.build(0, ROAD_Z * 2, new Vec3i(1, 0, 0), false, true, new Vec3i(endX, 0, ROAD_Z), new Vec3i(START_X, 0, ROAD_Z));
        assertEquals("the leg's first slice", START_X, context.layout().firstStartingPos().getX());
        assertEquals("the leg's end slice", endX, context.layout().endPos().getX());
        BlockPos firstSlice = context.layout().firstStartingPos();
        for (int i = -40; i <= 40; i++) {
            BlockPos anchor = firstSlice.offset(i, 0, 0);
            List<int[]> cells = HighwayConformanceDump.projectSlice(context.layout().schematic(), anchor.getX(), anchor.getY(), anchor.getZ());
            for (int[] cell : cells) {
                if (cell[3] == 1) {
                    client.setBlock(new BlockPos(cell[0], cell[1], cell[2]), Blocks.OBSIDIAN.defaultBlockState());
                }
            }
        }
        client.moveTo(new BlockPos(feetX, context.layout().highwayFeetY(), ROAD_Z + 3));

        context.currentState().handle(context);
        assertEquals(HighwayState.BuildingHighway, context.currentState().getState());
        assertTrue("the builder took the leg", baritone.getBuilderProcess().isActive());
        return context;
    }

    /** What a fight leaves behind: the builder cancelled, and MobCombatReturn back in BuildingHighway. */
    private void fight(HighwayContext context) {
        context.setPreviousState(HighwayState.BuildingHighway);
        context.threats().setCombatReturnPos(null);
        context.transitionTo(HighwayState.MobCombat);
        baritone.getPathingBehavior().cancelEverything();
        assertFalse("the fight cancelled the builder", baritone.getBuilderProcess().isActive());
        context.transitionTo(HighwayState.MobCombatReturn);
        context.currentState().handle(context);
        assertEquals(HighwayState.BuildingHighway, context.currentState().getState());
    }

    private void fightThenRestartsOnTheFirstSlice(HighwayContext context) {
        fight(context);
        assertFalse("the end check passes with the leg's first slice unbuilt", context.endTracker().isHighwayEndComplete());

        context.currentState().handle(context);
        assertFalse("the leg ended", context.paused());
        assertNotNull(context.layout().endPos());
        assertEquals("restarting the builder", HighwayState.Nothing, context.currentState().getState());

        context.currentState().handle(context);
        assertEquals(HighwayState.BuildingHighway, context.currentState().getState());
        assertTrue("the builder took the leg again", baritone.getBuilderProcess().isActive());
        assertEquals("the restarted builder's first slice", new Vec3i(START_X, context.layout().firstStartingPos().getY(), ROAD_Z), builderOrigin());
    }

    private HighwayContext context() {
        try {
            Field field = NetherHighwayBuilderBehavior.class.getDeclaredField("highwayContext");
            field.setAccessible(true);
            return (HighwayContext) field.get(behavior);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private Vec3i builderOrigin() {
        try {
            Field field = BuilderProcess.class.getDeclaredField("origin");
            field.setAccessible(true);
            return (Vec3i) field.get(baritone.getBuilderProcess());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
