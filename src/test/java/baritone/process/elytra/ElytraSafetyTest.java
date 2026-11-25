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
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Glide steps that stop mid-flight on a bot with no fireworks leave the glide alone instead of flying it by firework
 * physics with none; the emergency line says which condition fired; and a tick's flicker lands nothing.
 */
public class ElytraSafetyTest {

    private Settings settings;

    @BeforeClass
    public static void bootstrap() {
        HeadlessGame.bootstrap();
    }

    @Before
    public void fresh() {
        this.settings = TestSettings.fresh();
    }

    @Test
    public void glideStepsStoppingMidFlightWithNoFireworksIsNotAnEmergency() {
        // the flight glided by steps, and now nothing moves it by steps
        assertTrue(ElytraSafety.stepsStopped(true, false, 0));
        // which is no reason given by the firework rules, since the flight doesn't take that branch
        assertEquals("there are no fireworks, and nothing moves the glide by steps", ElytraSafety.reason(true, 400, true, false, 0, this.settings));
        // with fireworks to fly on, the firework glide takes over as it always has
        assertFalse(ElytraSafety.stepsStopped(true, false, 64));
        assertNull(ElytraSafety.reason(true, 400, true, false, 64, this.settings));
        // a flight that never glided by steps is the firework flight it always was
        assertFalse(ElytraSafety.stepsStopped(false, false, 0));
        // and steps that are going are fine
        assertFalse(ElytraSafety.stepsStopped(true, true, 0));
        assertNull(ElytraSafety.reason(true, 400, true, true, 0, this.settings));
    }

    @Test
    public void theEmergencySaysWhichConditionFired() {
        assertEquals("there is no elytra in the chest slot", ElytraSafety.reason(false, 0, false, true, 0, this.settings));
        assertEquals("the elytra is down to 3 durability, under elytraMinimumDurability (5)", ElytraSafety.reason(true, 3, true, true, 0, this.settings));
        assertEquals("the elytra can't glide any more", ElytraSafety.reason(true, 5, false, true, 0, this.settings));
        assertEquals("4 fireworks left, no more than elytraMinFireworksBeforeLanding (5)", ElytraSafety.reason(true, 400, true, false, 4, this.settings));
        assertNull(ElytraSafety.reason(true, 400, true, false, 6, this.settings));
        assertTrue(("Emergency landing - " + ElytraSafety.reason(false, 0, false, true, 0, this.settings)).startsWith("Emergency landing"));
    }

    @Test
    public void aTicksFlickerLandsNothing() {
        final ElytraSafety safety = new ElytraSafety();
        final String reason = "there is no elytra in the chest slot";
        for (int flicker = 0; flicker < 5; flicker++) {
            assertNull(safety.hold(reason));
            assertNull(safety.hold(null));
        }
        for (int i = 1; i < ElytraSafety.HOLD_TICKS; i++) {
            assertNull(safety.hold(reason));
        }
        assertEquals(reason, safety.hold(reason));
        assertEquals(reason, safety.hold(reason));
        safety.reset();
        assertNull(safety.hold(reason));
    }
}
