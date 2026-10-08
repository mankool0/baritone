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

import baritone.test.HeadlessGame;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class EnderChestFarmForRunTest {

    @BeforeClass
    public static void bootstrap() {
        HeadlessGame.bootstrap();
    }

    @Test
    public void aFewHolesBreakOneChest() {
        assertEquals(1, HighwayContext.chestsForObsidian(5, 0));
        assertEquals(1, HighwayContext.chestsForObsidian(8, 0));
        assertEquals(2, HighwayContext.chestsForObsidian(9, 0));
    }

    @Test
    public void obsidianOnHandCounts() {
        assertEquals(3, HighwayContext.chestsForObsidian(40, 20));
        assertEquals(0, HighwayContext.chestsForObsidian(10, 10));
        assertEquals(0, HighwayContext.chestsForObsidian(3, 20));
    }

    @Test
    public void aCappedFarmKeepsEverythingButTheRunsChests() {
        assertEquals(63, HighwayContext.farmKeepForRun(8, 64, 1));
        assertEquals(56, HighwayContext.farmKeepForRun(8, 64, 8));
    }

    @Test
    public void theCapNeverLowersTheKeep() {
        assertEquals(8, HighwayContext.farmKeepForRun(8, 64, -1));
        assertEquals(8, HighwayContext.farmKeepForRun(8, 10, 5));
        assertEquals(40, HighwayContext.farmKeepForRun(40, 64, 30));
    }
}
