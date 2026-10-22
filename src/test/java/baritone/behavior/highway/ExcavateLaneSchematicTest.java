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

import baritone.api.Settings;
import baritone.api.TestSettings;
import baritone.api.schematic.CellOverrideSchematic;
import baritone.api.schematic.CellOverrides;
import baritone.api.schematic.CompositeSchematic;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.WhiteBlackSchematic;
import baritone.test.HeadlessGame;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * An area-clearing lane's schematic: highwayExcavate's all-air floor row, and highwayCellOverrides
 * bound at world origins the way the builder and the scans see them. The lane runs +Z, nine wide:
 * its slice anchor is x -6 (column 0, unmodeled without rails), the floor x -5..3, kept floor y 119.
 */
public class ExcavateLaneSchematicTest {

    private static final Vec3i PLUS_Z = new Vec3i(0, 0, 1);
    private static final int LOWEST_Y = 119;

    @BeforeClass
    public static void bootstrap() {
        HeadlessGame.bootstrap();
    }

    private static Settings lane(boolean excavate) {
        Settings s = TestSettings.fresh();
        s.highwayWidth.value = 9;
        s.highwayHeight.value = 3;
        s.highwayLowestY.value = LOWEST_Y;
        s.highwayMainY.value = LOWEST_Y + 1;
        s.highwayRail.value = false;
        s.highwayExcavate.value = excavate;
        s.highwaySupportWidth.value = 2;
        s.highwaySupportOffset.value = 4; // the server's support_strip(9)
        return s;
    }

    private static int anchorX(Settings s) {
        Vec3 origin = NetherHighwayBuilderBehavior.canonicalOriginVector(PLUS_Z, s);
        return (int) Math.round(origin.x);
    }

    private static BlockState desired(ISchematic schem, int c, int sy, BlockState current) {
        return schem.desiredState(c, sy, 0, current, Collections.emptyList());
    }

    private static boolean modeled(CompositeSchematic schem, int c, int sy) {
        return schem.inSchematic(c, sy, 0, Blocks.AIR.defaultBlockState());
    }

    @Test
    public void anExcavatedFloorRowIsAirLikeTheRowsAboveIt() {
        BlockState obsidian = Blocks.OBSIDIAN.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (boolean pave : new boolean[]{false, true}) {
            CompositeSchematic schem = NetherHighwayBuilderBehavior.composeHighwaySchematic(PLUS_Z, pave, lane(true));
            assertEquals(-6, anchorX(lane(true)));
            for (int c = 1; c <= 9; c++) {
                for (int sy = 1; sy <= 3; sy++) {
                    assertTrue(c + " " + sy, modeled(schem, c, sy));
                    assertEquals("obsidian is dug, c " + c + " sy " + sy, air, desired(schem, c, sy, obsidian).getBlock().defaultBlockState());
                    assertEquals(air, desired(schem, c, sy, air));
                }
            }
            assertFalse("no rail columns", modeled(schem, 0, 2));
            assertFalse(modeled(schem, 10, 2));
            assertFalse("nothing above the lane", modeled(schem, 5, 4));
            // the kept floor is only ever scaffolding: the support strip and the lava guards
            for (int c = 1; c <= 9; c++) {
                boolean scaffold = c == 1 || c == 4 || c == 5 || c == 9;
                assertEquals("sy 0 c " + c, scaffold, modeled(schem, c, 0));
                if (scaffold) {
                    ISchematic leaf = schem.getSchematic(c, 0, 0, air).schematic;
                    assertEquals(WhiteBlackSchematic.ServerRequirement.IGNORE, ((WhiteBlackSchematic) leaf).serverRequirement());
                }
            }
        }

        // without it, a dig leg keeps obsidian in its floor row
        CompositeSchematic dig = NetherHighwayBuilderBehavior.composeHighwaySchematic(PLUS_Z, false, lane(false));
        assertEquals(obsidian, desired(dig, 5, 1, obsidian));
        assertEquals(air, desired(dig, 5, 2, obsidian).getBlock().defaultBlockState());
    }

    @Test
    public void excavateTakesTheRailsOffEvenWhenAsked() {
        Settings s = lane(true);
        s.highwayRail.value = true;
        CompositeSchematic schem = NetherHighwayBuilderBehavior.composeHighwaySchematic(PLUS_Z, true, s);
        for (int sy = 0; sy <= 4; sy++) {
            assertFalse(modeled(schem, 0, sy));
            assertFalse(modeled(schem, 10, sy));
        }
        assertFalse(NetherHighwayBuilderBehavior.hasRails(s));
    }

    /** The server's crossing, moved onto this lane's columns: storage at z 99, a rail at z 100, the floor under it at z 101. */
    private static final String CROSSING = "storage -5 121 99 3 121 99;skip -5 120 100 3 120 100;skip -5 119 101 3 119 101";

    private static CompositeSchematic sliceAt(CompositeSchematic schem, CellOverrides overrides, int z) {
        return overrides.bind(schem, new BlockPos(-6, LOWEST_Y, z));
    }

    @Test
    public void skipCellsAreOutOfTheSchematicSupportStripIncluded() {
        CellOverrides overrides = CellOverrides.parse(CROSSING);
        CompositeSchematic schem = NetherHighwayBuilderBehavior.composeHighwaySchematic(PLUS_Z, false, lane(true));

        CompositeSchematic rail = sliceAt(schem, overrides, 100);
        for (int c = 1; c <= 9; c++) {
            assertFalse("the rail is left, c " + c, modeled(rail, c, 1));
            assertNull(rail.getSchematic(c, 1, 0, Blocks.OBSIDIAN.defaultBlockState()));
            assertTrue("the air over it is still dug", modeled(rail, c, 2));
            assertTrue(modeled(rail, c, 3));
        }
        assertTrue("the support strip under the rail row", modeled(rail, 4, 0));

        CompositeSchematic floor = sliceAt(schem, overrides, 101);
        for (int c = 1; c <= 9; c++) {
            assertFalse("no strip or lava guard in the highway's floor, c " + c, modeled(floor, c, 0));
            assertTrue(modeled(floor, c, 1));
        }

        CompositeSchematic clear = sliceAt(schem, overrides, 102);
        assertTrue("a slice nothing crosses keeps its strip", modeled(clear, 4, 0));
        assertTrue(modeled(clear, 5, 1));
    }

    @Test
    public void storageCellsKeepBoxesAndDigTheRest() {
        CellOverrides overrides = CellOverrides.parse(CROSSING);
        CompositeSchematic schem = NetherHighwayBuilderBehavior.composeHighwaySchematic(PLUS_Z, false, lane(true));
        CompositeSchematic storage = sliceAt(schem, overrides, 99);
        BlockState chest = Blocks.ENDER_CHEST.defaultBlockState();
        BlockState box = Blocks.LIME_SHULKER_BOX.defaultBlockState();
        BlockState plain = Blocks.SHULKER_BOX.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int c = 1; c <= 9; c++) {
            assertTrue(modeled(storage, c, 2));
            assertEquals(chest, desired(storage, c, 2, chest));
            assertEquals(box, desired(storage, c, 2, box));
            assertEquals(plain, desired(storage, c, 2, plain));
            assertEquals(air, desired(storage, c, 2, air));
            assertEquals("the support under a highway's chest is dug", air, desired(storage, c, 2, Blocks.NETHERRACK.defaultBlockState()));
            assertEquals(air, desired(storage, c, 2, Blocks.OBSIDIAN.defaultBlockState()));
            ISchematic leaf = storage.getSchematic(c, 2, 0, chest).schematic;
            assertEquals(WhiteBlackSchematic.ServerRequirement.IGNORE, ((WhiteBlackSchematic) leaf).serverRequirement());
            // the rows either side are the lane's own
            assertEquals(air, desired(storage, c, 1, chest).getBlock().defaultBlockState());
            assertEquals(air, desired(storage, c, 3, chest).getBlock().defaultBlockState());
        }
        assertTrue("the leaf lookup the builder makes still reaches the lane's own cells",
                storage.getSchematic(5, 1, 0, air).schematic instanceof WhiteBlackSchematic);
    }

    /** The overrides apply whether the leg paves or not: a paved lane leaves the highway its floor. */
    @Test
    public void overridesApplyOnAPavedLaneToo() {
        Settings paved = lane(false);
        paved.highwayLowestY.value = 118;
        paved.highwayMainY.value = 119;
        paved.highwayHeight.value = 4;
        CompositeSchematic schem = NetherHighwayBuilderBehavior.composeHighwaySchematic(PLUS_Z, true, paved);
        CellOverrides overrides = CellOverrides.parse("storage -5 121 99 3 121 99;skip -5 120 100 3 120 100;skip -5 119 101 3 119 101");
        CompositeSchematic floor = overrides.bind(schem, new BlockPos(-6, 118, 101));
        CompositeSchematic open = overrides.bind(schem, new BlockPos(-6, 118, 102));
        for (int c = 1; c <= 9; c++) {
            assertFalse("the highway lays its own floor, c " + c, modeled(floor, c, 1));
            assertTrue(modeled(open, c, 1));
            assertEquals(Blocks.OBSIDIAN.defaultBlockState(), desired(open, c, 1, Blocks.OBSIDIAN.defaultBlockState()));
        }
        CompositeSchematic storage = overrides.bind(schem, new BlockPos(-6, 118, 99));
        assertEquals(Blocks.ENDER_CHEST.defaultBlockState(), desired(storage, 5, 3, Blocks.ENDER_CHEST.defaultBlockState()));
    }

    /**
     * The section ends at z 16 here; the box is a crossing straddling it, which the server sends
     * whole. The builder gets a four-slice window across the edge, then one repeat on.
     */
    @Test
    public void aBoxAcrossTheSectionEdgeFollowsTheWindowAndItsRepeats() {
        Settings s = lane(true);
        CellOverrides overrides = CellOverrides.parse("skip -5 121 14 3 121 17");
        CompositeSchematic slice = NetherHighwayBuilderBehavior.composeHighwaySchematic(PLUS_Z, false, s);
        LookaheadWindow window = LookaheadWindow.of(slice, 0, 1, 4, 0, 9);
        assertEquals(new Vec3i(0, 0, 0), window.originShift());
        CompositeSchematic bound = overrides.bind(window.schematic(), new BlockPos(-6, LOWEST_Y, 13));
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int c = 1; c <= 9; c++) {
            assertTrue("z 13 is before the box", bound.inSchematic(c, 2, 0, air));
            for (int dz = 1; dz <= 3; dz++) {
                assertFalse("z " + (13 + dz), bound.inSchematic(c, 2, dz, air));
                assertTrue("only the one row", bound.inSchematic(c, 1, dz, air));
            }
        }

        CellOverrideSchematic next = ((CellOverrideSchematic) bound).at(new BlockPos(-6, LOWEST_Y, 17));
        assertNotSame(bound, next);
        for (int c = 1; c <= 9; c++) {
            assertFalse("z 17 is the box's last slice", next.inSchematic(c, 2, 0, air));
            for (int dz = 1; dz <= 3; dz++) {
                assertTrue("z " + (17 + dz) + " is past it", next.inSchematic(c, 2, dz, air));
            }
        }

        // the scans bind one slice at a time
        for (int z = 12; z <= 19; z++) {
            boolean in = z >= 14 && z <= 17;
            assertEquals("z " + z, !in, modeled(sliceAt(slice, overrides, z), 5, 2));
        }

        // binding a bound schematic again replaces its origin rather than stacking
        CompositeSchematic rebound = overrides.bind(bound, new BlockPos(-6, LOWEST_Y, 20));
        assertTrue(rebound.inSchematic(5, 2, 0, air));
        assertEquals(((CellOverrideSchematic) bound).inner(), ((CellOverrideSchematic) rebound).inner());
    }
}
