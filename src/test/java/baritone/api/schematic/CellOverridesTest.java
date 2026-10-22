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

package baritone.api.schematic;

import baritone.api.schematic.CellOverrides.Kind;
import baritone.test.HeadlessGame;
import net.minecraft.core.Vec3i;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * highwayCellOverrides as the server writes it ({@code junction::cell_overrides}): the case below
 * is its own test's answer for a lane crossing a stock highway.
 */
public class CellOverridesTest {

    private static final String CROSSING = "storage -4 121 99 4 121 99;skip -4 120 100 4 120 100;"
            + "skip -4 119 101 4 119 101;skip -4 119 102 4 119 102;"
            + "skip -4 119 103 4 119 103;skip -4 119 104 4 119 104;"
            + "skip -4 120 105 4 120 105;storage -4 121 106 4 121 106";

    @BeforeClass
    public static void bootstrap() {
        HeadlessGame.bootstrap();
    }

    @Test
    public void theServersCrossingParses() {
        CellOverrides o = CellOverrides.parse(CROSSING);
        assertEquals(8, o.size());
        for (int x = -4; x <= 4; x++) {
            assertEquals(Kind.STORAGE, o.kindAt(x, 121, 99));
            assertEquals("the rail", Kind.SKIP, o.kindAt(x, 120, 100));
            assertEquals("the floor under the lane", Kind.SKIP, o.kindAt(x, 119, 102));
            assertEquals(Kind.STORAGE, o.kindAt(x, 121, 106));
            assertNull("the deck is the lane's", o.kindAt(x, 120, 102));
            assertNull(o.kindAt(x, 122, 100));
        }
        assertNull("past the box", o.kindAt(5, 120, 100));
        assertNull(o.kindAt(-5, 120, 100));
        assertNull(o.kindAt(0, 121, 98));
    }

    @Test
    public void anEmptyValueIsNoOverride() {
        assertTrue(CellOverrides.parse("").isEmpty());
        assertTrue(CellOverrides.parse("  ").isEmpty());
        assertTrue(CellOverrides.parse(null).isEmpty());
        assertNull(CellOverrides.parse("").kindAt(0, 0, 0));
        CompositeSchematic schematic = new CompositeSchematic(1, 1, 1);
        assertSame("nothing to override, nothing wrapped", schematic, CellOverrides.parse("").bind(schematic, new Vec3i(0, 0, 0)));
    }

    @Test
    public void whitespaceAroundEntriesIsFine() {
        CellOverrides o = CellOverrides.parse(" skip 0 0 0 1 1 1 ;  storage  5 5 5 5 5 5");
        assertEquals(Kind.SKIP, o.kindAt(1, 1, 1));
        assertEquals(Kind.STORAGE, o.kindAt(5, 5, 5));
    }

    @Test
    public void malformedValuesAreRefused() {
        String[] bad = {
                "skip 1 2 3 4 5",               // a coordinate short
                "skip 1 2 3 4 5 6 7",           // one too many
                "keep 1 2 3 4 5 6",             // no such kind
                "SKIP 1 2 3 4 5 6",             // kinds are lower case
                "skip 1 2 3 4 5 x",
                "skip 1.5 2 3 4 5 6",
                "skip 4 5 6 1 2 3",             // high corner first
                "skip 1 2 3 1 1 3",
                "skip 1 2 3 4 5 6;",            // an empty entry
                "skip 1 2 3 4 5 6;;skip 1 2 3 4 5 6",
                "skip 1,2,3,4,5,6",
                "skip 1 2 3 4 5 6,storage 1 2 3 4 5 6",
        };
        for (String value : bad) {
            try {
                CellOverrides.parse(value);
                fail("accepted \"" + value + "\"");
            } catch (IllegalArgumentException e) {
                assertTrue(value + ": " + e.getMessage(), e.getMessage().startsWith("entry "));
            }
        }
    }

    @Test
    public void skipWinsWhereBoxesOverlap() {
        CellOverrides o = CellOverrides.parse("storage 0 0 0 9 0 9;skip 3 0 3 4 0 4;storage 3 0 3 3 0 3");
        assertEquals(Kind.SKIP, o.kindAt(3, 0, 3));
        assertEquals(Kind.SKIP, o.kindAt(4, 0, 4));
        assertEquals(Kind.STORAGE, o.kindAt(5, 0, 5));
    }

    @Test
    public void boxesAcrossChunkBordersAndHugeOnes() {
        CellOverrides o = CellOverrides.parse("skip 14 120 -2 17 120 1;storage -100000 64 -100000 100000 64 100000");
        for (int x = 14; x <= 17; x++) {
            for (int z = -2; z <= 1; z++) {
                assertEquals(x + " " + z, Kind.SKIP, o.kindAt(x, 120, z));
            }
        }
        assertNull(o.kindAt(18, 120, 0));
        assertNull(o.kindAt(13, 120, 0));
        assertNull(o.kindAt(15, 120, 2));
        assertEquals("the big box is still looked at", Kind.STORAGE, o.kindAt(50000, 64, -70000));
        assertEquals(Kind.STORAGE, o.kindAt(15, 64, 0));
        assertFalse(o.isEmpty());
    }
}
