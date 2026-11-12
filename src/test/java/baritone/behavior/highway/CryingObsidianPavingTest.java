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
import baritone.api.schematic.CompositeSchematic;
import baritone.test.HeadlessGame;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Which block a paved cell asks for, given what we carry: crying obsidian is spent before
 * obsidian, and cave air is filled like air. The lane runs +Z, five wide with both rails: floor
 * columns 1-5 at y 1, rails at columns 0 and 6, y 2.
 */
public class CryingObsidianPavingTest {

    private static final Vec3i PLUS_Z = new Vec3i(0, 0, 1);

    // set after the bootstrap: touching Blocks from a static initializer runs before it
    private static BlockState AIR;
    private static BlockState CAVE_AIR;
    private static BlockState OBSIDIAN;
    private static BlockState CRYING;
    private static BlockState NETHERRACK;

    @BeforeClass
    public static void bootstrap() {
        HeadlessGame.bootstrap();
        AIR = Blocks.AIR.defaultBlockState();
        CAVE_AIR = Blocks.CAVE_AIR.defaultBlockState();
        OBSIDIAN = Blocks.OBSIDIAN.defaultBlockState();
        CRYING = Blocks.CRYING_OBSIDIAN.defaultBlockState();
        NETHERRACK = Blocks.NETHERRACK.defaultBlockState();
    }

    private static CompositeSchematic paved() {
        Settings s = TestSettings.fresh();
        s.highwayWidth.value = 5;
        s.highwayHeight.value = 3;
        s.highwayRail.value = true;
        s.highwayRailLow.value = true;
        s.highwayRailHigh.value = true;
        s.highwayExcavate.value = false;
        return NetherHighwayBuilderBehavior.composeHighwaySchematic(PLUS_Z, true, s);
    }

    /** A 36-slot approxPlaceable: air everywhere but the given slots. */
    private static List<BlockState> carrying(Object... slotThenState) {
        List<BlockState> placeable = new ArrayList<>(Collections.nCopies(36, AIR));
        for (int i = 0; i < slotThenState.length; i += 2) {
            placeable.set((Integer) slotThenState[i], (BlockState) slotThenState[i + 1]);
        }
        return placeable;
    }

    private static final int[][] PAVED_CELLS = {{1, 1}, {3, 1}, {5, 1}, {0, 2}, {6, 2}};

    @Test
    public void cryingObsidianIsPlacedFirstWhereverItIsCarried() {
        CompositeSchematic schem = paved();
        List<BlockState> hotbarBoth = carrying(5, OBSIDIAN, 6, CRYING);
        List<BlockState> cryingInInventory = carrying(5, OBSIDIAN, 6, OBSIDIAN, 20, CRYING);
        for (int[] cell : PAVED_CELLS) {
            String at = "c " + cell[0] + " y " + cell[1];
            for (BlockState empty : new BlockState[]{AIR, CAVE_AIR}) {
                assertEquals(at, CRYING, schem.desiredState(cell[0], cell[1], 0, empty, hotbarBoth));
                assertEquals(at, CRYING, schem.desiredState(cell[0], cell[1], 0, empty, cryingInInventory));
            }
        }
    }

    @Test
    public void obsidianOnceTheCryingObsidianIsGone() {
        CompositeSchematic schem = paved();
        List<BlockState> obsidianOnly = carrying(5, OBSIDIAN, 30, OBSIDIAN);
        for (int[] cell : PAVED_CELLS) {
            for (BlockState empty : new BlockState[]{AIR, CAVE_AIR}) {
                assertEquals(OBSIDIAN, schem.desiredState(cell[0], cell[1], 0, empty, obsidianOnly));
            }
        }
    }

    @Test
    public void onlyCryingObsidianStillFillsCaveAir() {
        CompositeSchematic schem = paved();
        // cave air asked for obsidian outright, parking a bot carrying none on missing materials
        assertEquals(CRYING, schem.desiredState(3, 1, 0, CAVE_AIR, carrying(12, CRYING)));
        assertEquals(CRYING, schem.desiredState(0, 2, 0, CAVE_AIR, carrying(12, CRYING)));
    }

    @Test
    public void carryingNeitherAsksForObsidian() {
        CompositeSchematic schem = paved();
        // the scanner and the missing-materials report pass no placeables; they should say obsidian
        for (int[] cell : PAVED_CELLS) {
            assertEquals(OBSIDIAN, schem.desiredState(cell[0], cell[1], 0, AIR, Collections.emptyList()));
            assertEquals(OBSIDIAN, schem.desiredState(cell[0], cell[1], 0, CAVE_AIR, carrying(4, NETHERRACK)));
        }
    }

    @Test
    public void eitherKindLaidDownStaysAndOtherBlocksAreBrokenForObsidian() {
        CompositeSchematic schem = paved();
        List<BlockState> both = carrying(5, OBSIDIAN, 6, CRYING);
        for (int[] cell : PAVED_CELLS) {
            assertEquals(OBSIDIAN, schem.desiredState(cell[0], cell[1], 0, OBSIDIAN, both));
            assertEquals(CRYING, schem.desiredState(cell[0], cell[1], 0, CRYING, both));
            assertEquals(OBSIDIAN, schem.desiredState(cell[0], cell[1], 0, NETHERRACK, both));
        }
    }
}
