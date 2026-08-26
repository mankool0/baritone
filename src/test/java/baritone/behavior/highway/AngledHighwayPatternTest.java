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
import baritone.api.schematic.ISchematic;
import baritone.test.HeadlessGame;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The geometry an angled ({@code highwayPattern}) build produces, checked slice by slice against
 * every other slice it overlaps.
 *
 * <p>Unlike a straight or 45-degree build, consecutive slices of an angled one can share a
 * driving-axis coordinate: wherever the pattern jogs, two (or more) cross-sections sit at the same
 * place on that axis, offset by one on the cross axis. Their cells overlap, and whatever the
 * builder places for one slice the correctness scan then reads back for the other. A cell that one
 * slice wants clear and another wants filled is a build that never finishes: the scan reports it,
 * the invalid-block fix rebuilds it, and the next scan reports it again. So the invariant is that
 * for every cell, some block state satisfies every slice covering it at once - which for the rail
 * columns is only true because {@link HighwayPattern#ownsLowRail} hands each one to a single slice.
 */
public class AngledHighwayPatternTest {

    /**
     * Every state the highway ever asks for; a cell no candidate satisfies is unbuildable. Built on
     * demand: touching {@link Blocks} before {@link HeadlessGame#bootstrap()} kills the registries.
     */
    private static List<BlockState> candidates() {
        return Arrays.asList(
                Blocks.AIR.defaultBlockState(),
                Blocks.OBSIDIAN.defaultBlockState(),
                Blocks.NETHERRACK.defaultBlockState());
    }

    private static final int FIRST_SLICE = -24;
    private static final int LAST_SLICE = 48;

    @BeforeClass
    public static void bootstrap() {
        HeadlessGame.bootstrap();
    }

    @Test
    public void everyPatternBuildsWithoutSelfContradiction() {
        for (String pattern : new String[]{"XXZXXZXXXZ", "XXZ", "XZZ", "XXXXZ", "ZZZXZ", "ZXX"}) {
            for (int[] dir : new int[][]{{1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
                for (boolean pave : new boolean[]{true, false}) {
                    for (boolean[] rails : new boolean[][]{{true, true}, {true, false}, {false, true}, {false, false}}) {
                        check(pattern, dir[0], dir[1], pave, true, rails[0], rails[1]);
                    }
                    check(pattern, dir[0], dir[1], pave, false, false, false); // rails off entirely
                }
            }
        }
    }

    /** With rails on, every driving-axis column carries exactly one rail per enabled side. */
    @Test
    public void railsRunUnbrokenAlongsideTheRoad() {
        for (String pattern : new String[]{"XXZXXZXXXZ", "XZZ", "ZXX"}) {
            for (int[] dir : new int[][]{{1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
                for (boolean[] rails : new boolean[][]{{true, true}, {true, false}, {false, true}}) {
                    check(pattern, dir[0], dir[1], true, true, rails[0], rails[1]);
                }
            }
        }
    }

    private void check(String pattern, int dirX, int dirZ, boolean pave, boolean rails, boolean railLow, boolean railHigh) {
        String what = pattern + " " + dirX + "/" + dirZ + (pave ? " pave" : " dig")
                + " rails=" + (rails ? (railLow ? "low" : "") + (railHigh ? "high" : "") + (!railLow && !railHigh ? "clear" : "") : "off");
        Settings settings = TestSettings.fresh();
        settings.highwayWidth.value = 4;
        settings.highwayRail.value = rails;
        settings.highwayRailLow.value = railLow;
        settings.highwayRailHigh.value = railHigh;

        Vec3i direction = new Vec3i(dirX, 0, dirZ);
        HighwayPattern hp = HighwayPattern.parse(pattern, direction);
        assertTrue(what + ": expected a custom pattern", hp.isCustom());
        CompositeSchematic[] variants = NetherHighwayBuilderBehavior.composePatternSchematics(
                direction, hp.majorIsX(), !hp.majorIsX(), pave, settings);
        for (CompositeSchematic variant : variants) {
            assertEquals(what + ": variants must agree on width", variants[3].widthX(), variant.widthX());
            assertEquals(what + ": variants must agree on height", variants[3].heightY(), variant.heightY());
            assertEquals(what + ": variants must agree on length", variants[3].lengthZ(), variant.lengthZ());
        }

        // cell -> the states still acceptable to every slice that covers it
        Map<Cell, Set<BlockState>> allowed = new HashMap<>();
        Map<Integer, TreeSet<Integer>> floorByColumn = new HashMap<>();
        for (int t = FIRST_SLICE; t <= LAST_SLICE; t++) {
            CompositeSchematic slice = variants[(hp.ownsLowRail(t) ? 1 : 0) | (hp.ownsHighRail(t) ? 2 : 0)];
            int ax = hp.worldOffsetX(t);
            int az = hp.worldOffsetZ(t);
            for (int y = 0; y < slice.heightY(); y++) {
                for (int z = 0; z < slice.lengthZ(); z++) {
                    for (int x = 0; x < slice.widthX(); x++) {
                        if (!slice.inSchematic(x, y, z, null)) {
                            continue;
                        }
                        ISchematic sub = slice.getSchematic(x, y, z, null).schematic;
                        Cell cell = new Cell(ax + x, y, az + z);
                        Set<BlockState> states = allowed.computeIfAbsent(cell, c -> new LinkedHashSet<>(candidates()));
                        int localX = x;
                        int localY = y;
                        int localZ = z;
                        states.removeIf(candidate -> !accepts(sub, localX, localY, localZ, candidate));
                        assertFalse(what + ": no state satisfies every slice at " + cell, states.isEmpty());
                    }
                }
            }
            // the paved deck of this slice, in cross-axis coordinates, for the rail assertions
            if (pave) {
                for (int c = 1; c <= settings.highwayWidth.value; c++) {
                    floorByColumn.computeIfAbsent(hp.majorIsX() ? ax : az, k -> new TreeSet<>())
                            .add((hp.majorIsX() ? az : ax) + c);
                }
            }
        }

        if (!pave || !rails || (!railLow && !railHigh)) {
            return;
        }
        // One rail per side per driving-axis column, immediately outside the deck. Columns at the
        // ends of the scanned range see only part of their slices, so skip them.
        int firstColumn = hp.majorOffset(FIRST_SLICE + hp.periodSlices());
        int lastColumn = hp.majorOffset(LAST_SLICE - hp.periodSlices());
        for (Map.Entry<Integer, TreeSet<Integer>> entry : floorByColumn.entrySet()) {
            int columnMajor = (hp.majorIsX() ? entry.getKey() : entry.getKey()) * hp.majorSign();
            if (columnMajor < firstColumn || columnMajor > lastColumn) {
                continue;
            }
            TreeSet<Integer> deck = entry.getValue();
            assertEquals("deck is contiguous at " + what + " column " + entry.getKey(),
                    deck.last() - deck.first() + 1, deck.size());
            List<Integer> railsHere = new ArrayList<>();
            for (Map.Entry<Cell, Set<BlockState>> cellEntry : allowed.entrySet()) {
                Cell cell = cellEntry.getKey();
                if (cell.y != 2 || (hp.majorIsX() ? cell.x : cell.z) != entry.getKey()) {
                    continue;
                }
                if (cellEntry.getValue().size() == 1 && cellEntry.getValue().contains(Blocks.OBSIDIAN.defaultBlockState())) {
                    railsHere.add(hp.majorIsX() ? cell.z : cell.x);
                }
            }
            Collections.sort(railsHere);
            List<Integer> expected = new ArrayList<>();
            if (railLow) {
                expected.add(deck.first() - 1);
            }
            if (railHigh) {
                expected.add(deck.last() + 1);
            }
            Collections.sort(expected);
            assertEquals(what + ": rails beside column " + entry.getKey(), expected, railsHere);
        }
    }

    private static boolean accepts(ISchematic sub, int x, int y, int z, BlockState candidate) {
        try {
            return sub.desiredState(x, y, z, candidate, Collections.emptyList()).equals(candidate);
        } catch (RuntimeException | LinkageError e) {
            // the rejection path of a scaffolding cell reaches for the player's hotbar (and through
            // it for a running game); there is no player here, and reaching for one at all means the
            // candidate was rejected
            return false;
        }
    }

    private record Cell(int x, int y, int z) {
        @Override
        public String toString() {
            return "(" + x + ", " + y + ", " + z + ")";
        }
    }
}
