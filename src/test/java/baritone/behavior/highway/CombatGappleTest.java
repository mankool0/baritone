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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A fight's gapple: eaten on the first hit that finds no absorption left, not at the emergency
 * threshold, where a piglin pack's hits during the eat took bots under an auto-log's 12 first.
 */
public class CombatGappleTest {

    @BeforeClass
    public static void bootstrap() {
        HeadlessGame.bootstrap();
    }

    @Test
    public void aGappleJustDownWaitsForItsAbsorption() {
        // The effect lands with the gapple leaving our inventory; the amount follows a tracker update later
        assertFalse(Eating.combatGappleDue(20f, 18f, Eating.absorptionLeft(0f, true), true));
    }

    @Test
    public void absorptionRunOutEats() {
        assertTrue(Eating.combatGappleDue(20f, 18f, Eating.absorptionLeft(0f, false), true));
    }

    @Test
    public void offAtZero() {
        assertFalse(Eating.combatGappleDue(0f, 5f, false, true));
    }
}
