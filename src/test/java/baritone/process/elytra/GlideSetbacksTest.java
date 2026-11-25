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

package baritone.process.elytra;

import baritone.api.Settings;
import baritone.api.TestSettings;
import baritone.test.HeadlessGame;
import net.minecraft.world.phys.Vec3;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The setback lines (at most one a second, each counting the ones in the window), what counts towards a pull-down,
 * and the glide speed's steps and bounds.
 */
public class GlideSetbacksTest {

    private Settings settings;
    private GlideSetbacks setbacks;

    @BeforeClass
    public static void bootstrap() {
        HeadlessGame.bootstrap();
    }

    @Before
    public void fresh() {
        this.settings = TestSettings.fresh();
        this.setbacks = new GlideSetbacks(this.settings);
    }

    @Test
    public void aLineASecondCountingTheSetbacksInTheWindow() {
        assertEquals("Set back 2.0 blocks (2.0 down), 1 in the last 10 s", this.setbacks.note(0, new Vec3(0, -2, 0)));
        assertNull(this.setbacks.note(5, new Vec3(0, -1, 0)));
        assertNull(this.setbacks.note(19, new Vec3(0, -1, 0)));
        assertEquals("Set back 3.0 blocks (level), 4 in the last 10 s", this.setbacks.note(20, new Vec3(3, 0, 0)));
        assertEquals("Set back 0.5 blocks (0.5 up), 5 in the last 10 s", this.setbacks.note(40, new Vec3(0, 0.5, 0)));
        // the first ones are out of the window by now
        assertEquals("Set back 1.0 blocks (1.0 down), 2 in the last 10 s", this.setbacks.note(230, new Vec3(0, -1, 0)));
    }

    @Test
    public void setbacksToWhereItAlreadyWasSayALineAWindow() {
        // the server turning down a glide held still, every 4 ticks
        assertEquals("Set back to where it already was, 1 in the last 10 s", this.setbacks.note(0, Vec3.ZERO));
        for (int t = 4; t < 200; t += 4) {
            assertNull(this.setbacks.note(t, new Vec3(0.01, 0, 0)));
        }
        assertEquals("Set back to where it already was, 51 in the last 10 s", this.setbacks.note(200, Vec3.ZERO));
        // one that moves it is said as before, a second on from the last line
        assertEquals("Set back 2.0 blocks (2.0 down), 47 in the last 10 s", this.setbacks.note(220, new Vec3(0, -2, 0)));
        assertTrue(GlideSetbacks.inPlace(new Vec3(0.03, -0.03, 0)));
        assertFalse(GlideSetbacks.inPlace(new Vec3(0, -0.06, 0)));
        // and none of them count towards a pull-down
        assertEquals(1, this.setbacks.downward(220));
    }

    @Test
    public void onlySetbacksFarEnoughDownCount() {
        this.setbacks.note(0, new Vec3(0, -0.4, 0));
        this.setbacks.note(1, new Vec3(-3, 0, 0));
        this.setbacks.note(2, new Vec3(0, 2, 0));
        assertEquals(0, this.setbacks.downward(3));
        this.setbacks.note(3, new Vec3(0, -0.5, 0));
        assertEquals(1, this.setbacks.downward(4));
        this.settings.elytraPullDownDrop.value = 0.3;
        assertEquals(2, this.setbacks.downward(4));
    }

    @Test
    public void spentSetbacksStillCountForTheLineButNotTowardsAPullDown() {
        for (int t = 0; t < 3; t++) {
            this.setbacks.note(t * 20, new Vec3(0, -1, 0));
        }
        assertTrue(this.setbacks.pullingDown(41));
        this.setbacks.spend();
        assertFalse(this.setbacks.pullingDown(41));
        assertEquals(0, this.setbacks.downward(41));
        assertEquals("Set back 1.0 blocks (1.0 down), 4 in the last 10 s", this.setbacks.note(60, new Vec3(0, -1, 0)));
        assertEquals(1, this.setbacks.downward(61));
    }

    @Test
    public void theSpeedStepsDownNoLowerThanTheMinimumAndBackUpNoHigherThanFull() {
        for (int i = 0; i < 20; i++) {
            this.setbacks.pulledDown();
        }
        assertEquals(0.25, this.setbacks.speed(), 1e-9);
        assertEquals("25%", this.setbacks.speedPercent());
        int steps = 0;
        while (this.setbacks.flown(this.settings.elytraGlideSpeedRecoveryBlocks.value) != null) {
            steps++;
        }
        assertEquals(1.0, this.setbacks.speed(), 1e-9);
        // 0.25 / 0.8^7 is over 1
        assertEquals(7, steps);
    }

    @Test
    public void aStepDownAndBackUpComesBackExactly() {
        this.setbacks.pulledDown();
        this.setbacks.pulledDown();
        assertEquals(0.64, this.settings.elytraGlideSpeed.value, 0);
        this.setbacks.flown(1000);
        assertEquals(0.8, this.settings.elytraGlideSpeed.value, 0);
        this.setbacks.flown(1000);
        assertEquals(1.0, this.settings.elytraGlideSpeed.value, 0);
    }

    @Test
    public void aSpeedSetFromOutsideIsWhatTheFlightStartsAt() {
        // a script carrying the speed it learned to the next run
        this.settings.elytraGlideSpeed.value = 0.5;
        assertEquals(0.5, this.setbacks.speed(), 1e-9);
        this.setbacks.pulledDown();
        assertEquals(0.4, this.settings.elytraGlideSpeed.value, 1e-9);
        // nonsense is held to the bounds
        this.settings.elytraGlideSpeed.value = 7.0;
        assertEquals(1.0, this.setbacks.speed(), 1e-9);
        this.settings.elytraGlideSpeed.value = -1.0;
        assertEquals(0.25, this.setbacks.speed(), 1e-9);
        this.settings.elytraGlideSpeed.value = Double.NaN;
        assertEquals(0.25, this.setbacks.speed(), 1e-9);
    }

    @Test
    public void aSetbackStartsTheCleanStretchAgain() {
        this.setbacks.pulledDown();
        assertNull(this.setbacks.flown(900));
        this.setbacks.note(10, new Vec3(-1, 0, 0));
        assertNull(this.setbacks.flown(900));
        assertNotNull(this.setbacks.flown(100));
    }
}
