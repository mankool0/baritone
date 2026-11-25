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

import static baritone.process.elytra.GlideRecovery.Action.*;
import static org.junit.Assert.*;

/**
 * A flight moved by glide steps that the server pulls down: the setbacks down add up to a pull-down, the flight comes
 * down and stays down a while, takes off again slower, and after a clean stretch is back up to speed. Take-offs that
 * don't glide wait longer each time and then give up, as do pull-downs one after another.
 */
public class GlideRecoveryTest {

    private static final Vec3 DOWN_AND_BACK = new Vec3(-0.6, -1.4, 0.2);

    private Settings settings;
    private GlideSetbacks setbacks;
    private GlideRecovery recovery;

    @BeforeClass
    public static void bootstrap() {
        HeadlessGame.bootstrap();
    }

    @Before
    public void fresh() {
        this.settings = TestSettings.fresh();
        this.setbacks = new GlideSetbacks(this.settings);
        this.recovery = new GlideRecovery(this.settings, this.setbacks);
    }

    @Test
    public void aPullDownLandsWaitsTakesOffSlowerAndSpeedsUpAfterACleanStretch() {
        int t = 100;
        // gliding: the first two setbacks down are only setbacks
        for (int i = 0; i < 2; i++) {
            t += 15;
            this.setbacks.note(t, DOWN_AND_BACK);
            assertEquals(NONE, this.recovery.tick(t, true, false, false));
        }
        assertEquals(1.0, this.setbacks.speed(), 1e-9);
        t += 15;
        this.setbacks.note(t, DOWN_AND_BACK);
        assertEquals(SETTLE, this.recovery.tick(t, true, false, false));
        assertEquals("Pulled down by the server (3 setbacks down in 10 s): coming down on the floor below, glide speed 100% -> 80%", this.recovery.line());
        // the step goes at the slower speed from now on, and the setting holds it for whoever reads it
        assertEquals(0.8, this.setbacks.speed(), 1e-9);
        assertEquals(0.8, this.settings.elytraGlideSpeed.value, 1e-9);
        assertTrue(this.recovery.settling());

        // coming down: the server pulls on, which isn't another pull-down
        for (int i = 0; i < 5; i++) {
            t += 5;
            this.setbacks.note(t, DOWN_AND_BACK);
            assertEquals(NONE, this.recovery.tick(t, true, false, false));
        }
        assertEquals(0.8, this.setbacks.speed(), 1e-9);

        // down on the floor: stays put for a second
        t++;
        assertEquals(HOLD, this.recovery.tick(t, false, true, false));
        assertEquals("Down after the pull-down, taking off again in 1 s at 80% glide speed", this.recovery.line());
        assertTrue(this.recovery.settled());
        for (int i = 1; i < 20; i++) {
            assertEquals(HOLD, this.recovery.tick(t + i, false, true, false));
            assertNull(this.recovery.line());
        }
        t += 20;
        // then the flight again from here
        assertEquals(TAKE_OFF, this.recovery.tick(t, false, true, false));
        assertEquals("Taking off again at 80% glide speed", this.recovery.line());
        // the path comes in, the jumping starts, the glide starts
        assertEquals(NONE, this.recovery.tick(++t, false, true, false));
        assertEquals(NONE, this.recovery.tick(++t, false, true, true));
        assertEquals(NONE, this.recovery.tick(++t, false, false, true));
        assertEquals(NONE, this.recovery.tick(++t, true, false, false));
        assertFalse(this.recovery.settling());
        assertFalse(this.recovery.settled());

        // a clean stretch: back up a step, and not before
        String faster = null;
        double flown = 0;
        while (faster == null && flown < 2000) {
            faster = this.setbacks.flown(2.4);
            flown += 2.4;
        }
        assertEquals("Glide speed back up to 100% after 1000 blocks without a setback", faster);
        assertTrue(flown >= 1000);
        assertEquals(1.0, this.settings.elytraGlideSpeed.value, 1e-9);
        // at full speed there's no higher to go
        for (int i = 0; i < 500; i++) {
            assertNull(this.setbacks.flown(2.4));
        }
    }

    @Test
    public void setbacksSpreadOverMoreThanTheWindowArentAPullDown() {
        int t = 0;
        for (int i = 0; i < 6; i++) {
            t += 120; // 6 s apart, two in any 10 s
            this.setbacks.note(t, DOWN_AND_BACK);
            assertEquals(NONE, this.recovery.tick(t, true, false, false));
        }
        assertEquals(1.0, this.setbacks.speed(), 1e-9);
    }

    @Test
    public void smallLevelSetbacksArentAPullDown() {
        int t = 0;
        for (int i = 0; i < 10; i++) {
            t += 5;
            this.setbacks.note(t, new Vec3(-0.6, 0.1, 0));
            assertEquals(NONE, this.recovery.tick(t, true, false, false));
        }
    }

    @Test
    public void aGlidePutBackWhereItStartedAgainAndAgainIsTurnedDown() {
        // the server putting the glide back a step's worth every 4 ticks, level
        int t = 0;
        for (int i = 0; i < 2; i++) {
            t += 4;
            this.setbacks.note(t, new Vec3(-14.3, 0.1, 0));
            assertEquals(NONE, this.recovery.tick(t, true, false, false));
        }
        t += 4;
        this.setbacks.note(t, new Vec3(-14.3, 0.1, 0));
        assertEquals(SETTLE, this.recovery.tick(t, true, false, false));
        assertEquals("Turned back by the server again and again (3 setbacks in 10 s, 0 of them down): coming down on the floor below, glide speed 100% -> 80%", this.recovery.line());
        assertEquals(0.8, this.setbacks.speed(), 1e-9);
    }

    @Test
    public void putBackInLavaAgainAndAgainItNeverGivesUpThere() {
        // the limit, and more: each time the take-off is put back down in the pool, it takes off again at once
        int t = 50;
        final int limit = this.settings.elytraPullDownLimit.value;
        for (int i = 0; i < limit + 3; i++) {
            t += 40;
            this.setbacks.note(t, new Vec3(0, -13, 0));
            assertEquals("put-down " + (i + 1), TAKE_OFF, this.recovery.tick(t + 1, false, true, false, "lava"));
            // the take-off: gliding again
            assertEquals(NONE, this.recovery.tick(t + 2, true, false, false, null));
        }
        // the same on dry ground gives up at the limit
        final GlideSetbacks drySetbacks = new GlideSetbacks(this.settings);
        final GlideRecovery onFloor = new GlideRecovery(this.settings, drySetbacks);
        GlideRecovery.Action last = NONE;
        for (int i = 0; i < limit; i++) {
            t += 400;
            drySetbacks.note(t, new Vec3(0, -6, 0));
            last = onFloor.tick(t + 1, false, true, false, null);
            if (last == GIVE_UP) {
                break;
            }
            // the wait, and gliding again
            onFloor.tick(t + 350, false, true, false, null);
            onFloor.tick(t + 360, true, false, false, null);
        }
        assertEquals(GIVE_UP, last);
    }

    @Test
    public void broughtDownIntoLavaWithTheGlideOnItLetsGoOfItThenTakesOffFromThere() {
        int t = 100;
        for (int i = 0; i < 3; i++) {
            t += 5;
            this.setbacks.note(t, DOWN_AND_BACK);
            this.recovery.tick(t, true, false, false, null);
        }
        assertTrue(this.recovery.settling());
        // into the lava, the glide still held: every step up put back
        final int in = ++t;
        for (; t < in + GlideRecovery.IN_LAVA_TICKS; t++) {
            this.setbacks.note(t, new Vec3(0, -1, 0));
            assertEquals(NONE, this.recovery.tick(t, true, false, false, "lava"));
        }
        assertEquals(LET_GO, this.recovery.tick(t, true, false, false, "lava"));
        assertEquals("In lava with the glide still on after the pull-down, and the server puts every step back: letting go of the glide for 2 s, then taking off again from there at 80% glide speed",
                this.recovery.line());
        assertTrue(this.recovery.lettingGo());
        // the server's pull is over: nothing the flight waits for down there
        assertFalse(this.recovery.settling());
        final int letGo = t;
        for (t++; t < letGo + GlideRecovery.LET_GO_TICKS; t++) {
            assertEquals(LET_GO, this.recovery.tick(t, t % 2 == 0, false, false, "lava"));
            assertNull(this.recovery.line());
        }
        assertEquals(TAKE_OFF, this.recovery.tick(t, false, false, false, "lava"));
        assertFalse(this.recovery.lettingGo());
    }

    @Test
    public void broughtDownOverDryGroundOrOnFireItDoesntLetGo() {
        for (final String heat : new String[]{null, "fire"}) {
            this.fresh();
            int t = 100;
            for (int i = 0; i < 3; i++) {
                t += 5;
                this.setbacks.note(t, DOWN_AND_BACK);
                this.recovery.tick(t, true, false, false, null);
            }
            for (int i = 0; i < 200; i++) {
                assertEquals(String.valueOf(heat), NONE, this.recovery.tick(++t, true, false, false, heat));
            }
            assertTrue(this.recovery.settling());
        }
    }

    @Test
    public void putDownOnTheGroundBySetbacksIsAPullDownThatsOver() {
        int t = 50;
        this.setbacks.note(t, new Vec3(0, -6, 0));
        // the server stopped the glide with that, and the player is on the ground
        assertEquals(HOLD, this.recovery.tick(t + 1, false, true, false));
        assertEquals("Down after the pull-down, taking off again in 1 s at 80% glide speed", this.recovery.line());
        assertEquals(0.8, this.setbacks.speed(), 1e-9);
    }

    @Test
    public void takeOffsThatDontGlideWaitLongerEachTimeThenGiveUp() {
        int t = 0;
        int lastWait = 0;
        for (int fail = 1; fail <= 5; fail++) {
            // jumping, no glide
            GlideRecovery.Action action = NONE;
            int jumped = 0;
            while (action == NONE) {
                action = this.recovery.tick(++t, false, true, true);
                jumped++;
                assertTrue("jumped for " + jumped + " ticks", jumped <= GlideRecovery.TAKE_OFF_TICKS + 2);
            }
            if (fail == 5) {
                assertEquals(GIVE_UP, action);
                assertEquals("Gave up the flight: the glide didn't start in 5 take-offs", this.recovery.line());
                break;
            }
            assertEquals(HOLD, action);
            assertEquals("The glide didn't start in 3 s of jumping (take-off " + fail + " of 5), trying again in "
                    + GlideRecovery.waitTicks(fail) / 20 + " s", this.recovery.line());
            int waited = 0;
            while ((action = this.recovery.tick(++t, false, true, false)) == HOLD) {
                waited++;
            }
            assertEquals(TAKE_OFF, action);
            assertTrue("waited " + waited + " after " + lastWait, waited >= lastWait);
            lastWait = waited;
        }
        // said once: nothing after
        for (int i = 0; i < 100; i++) {
            assertEquals(NONE, this.recovery.tick(++t, false, true, true));
            assertNull(this.recovery.line());
        }
    }

    @Test
    public void aTakeOffWaitingForItsPathIsntTimed() {
        int t = 0;
        for (int i = 0; i < 400; i++) {
            assertEquals(NONE, this.recovery.tick(++t, false, true, false));
        }
    }

    @Test
    public void pullDownsOneAfterAnotherGiveUp() {
        int t = 0;
        for (int pullDown = 1; pullDown <= 5; pullDown++) {
            GlideRecovery.Action action = NONE;
            for (int i = 0; i < 3; i++) {
                t += 10;
                this.setbacks.note(t, DOWN_AND_BACK);
                action = this.recovery.tick(t, true, false, false);
            }
            if (pullDown == 5) {
                assertEquals(GIVE_UP, action);
                assertEquals("Gave up the flight: pulled down 5 times without 1000 blocks clear of setbacks between", this.recovery.line());
                break;
            }
            assertEquals(SETTLE, action);
            // there's no floor below: on, slower
            this.recovery.noFloor();
        }
        assertEquals(GlideSetbacks.clampSpeed(Math.pow(0.8, 5), 0.25), this.setbacks.speed(), 1e-3);
        t += 10;
        this.setbacks.note(t, DOWN_AND_BACK);
        assertEquals(NONE, this.recovery.tick(t, true, false, false));
        assertNull(this.recovery.line());
    }

    @Test
    public void aCleanStretchBetweenPullDownsStartsTheCountAgain() {
        int t = 0;
        for (int pullDown = 1; pullDown <= 8; pullDown++) {
            GlideRecovery.Action action = NONE;
            for (int i = 0; i < 3; i++) {
                t += 10;
                this.setbacks.note(t, DOWN_AND_BACK);
                action = this.recovery.tick(t, true, false, false);
            }
            assertEquals(SETTLE, action);
            this.recovery.noFloor();
            this.setbacks.flown(this.settings.elytraGlideSpeedRecoveryBlocks.value);
        }
    }

    /**
     * Gliding, three setbacks down: the pull-down
     */
    private int pullDown(int t) {
        GlideRecovery.Action action = NONE;
        for (int i = 0; i < 3; i++) {
            t += 15;
            this.setbacks.note(t, DOWN_AND_BACK);
            action = this.recovery.tick(t, true, false, false);
        }
        assertEquals(SETTLE, action);
        assertTrue(this.recovery.settling());
        return t;
    }

    @Test
    public void aTouchdownInLavaAfterAPullDownTakesOffAtOnce() {
        int t = this.pullDown(100);
        // no floor near but lava: pulled on into it, gliding through it, then down on its bottom
        t += 5;
        this.setbacks.note(t, DOWN_AND_BACK);
        assertEquals(NONE, this.recovery.tick(t, true, false, false, "lava"));
        t++;
        assertEquals(TAKE_OFF, this.recovery.tick(t, false, true, false, "lava"));
        assertEquals("Down in lava after the pull-down, taking off again at once at 80% glide speed", this.recovery.line());
        assertFalse(this.recovery.settled());
        assertFalse(this.recovery.settling());
        // the path comes in, the jumping starts and the glide with it: no wait anywhere
        assertEquals(NONE, this.recovery.tick(++t, false, true, false, "lava"));
        assertEquals(NONE, this.recovery.tick(++t, false, true, true, "lava"));
        assertEquals(NONE, this.recovery.tick(++t, true, false, false, "lava"));
        // the setbacks that brought it down are spent: one more isn't another pull-down
        t += 5;
        this.setbacks.note(t, DOWN_AND_BACK);
        assertEquals(NONE, this.recovery.tick(t, true, false, false, "lava"));
        assertEquals(1, this.setbacks.pullDowns());
    }

    @Test
    public void aGlideOverInLavaIsDownEvenOffTheBottom() {
        int t = this.pullDown(100);
        t += 5;
        assertEquals(TAKE_OFF, this.recovery.tick(t, false, false, false, "lava"));
        assertEquals("Down in lava after the pull-down, taking off again at once at 80% glide speed", this.recovery.line());
    }

    @Test
    public void putDownIntoLavaBeforeThePullDownAddsUpItTakesOffAtOnce() {
        int t = 100;
        t += 15;
        this.setbacks.note(t, DOWN_AND_BACK);
        assertEquals(NONE, this.recovery.tick(t, true, false, false, null));
        t += 2;
        assertEquals(TAKE_OFF, this.recovery.tick(t, false, true, false, "lava"));
        assertEquals("Down in lava after the pull-down, taking off again at once at 80% glide speed", this.recovery.line());
    }

    @Test
    public void aWaitOnTheFloorEndsAsSoonAsItsInFire() {
        int t = this.pullDown(100);
        t++;
        assertEquals(HOLD, this.recovery.tick(t, false, true, false));
        assertEquals(HOLD, this.recovery.tick(t + 3, false, true, false));
        assertEquals(TAKE_OFF, this.recovery.tick(t + 5, false, true, false, "fire"));
        assertEquals("In fire, taking off again now at 80% glide speed", this.recovery.line());
    }

    @Test
    public void takeOffsInLavaThatDontGlideTryAgainAtOnceThenGiveUp() {
        this.settings.elytraTakeOffTries.value = 3;
        final java.util.List<Integer> takeOffs = new java.util.ArrayList<>();
        final java.util.List<String> lines = new java.util.ArrayList<>();
        String gaveUp = null;
        for (int t = 0; t < 2000 && gaveUp == null; t++) {
            // in lava, the path in, jumping all along, and the glide never starts
            final GlideRecovery.Action action = this.recovery.tick(t, false, false, true, "lava");
            assertNotEquals("never waits in lava", HOLD, action);
            if (action == TAKE_OFF) {
                takeOffs.add(t);
                lines.add(this.recovery.line());
            } else if (action == GIVE_UP) {
                gaveUp = this.recovery.line();
            } else {
                assertEquals(NONE, action);
            }
        }
        assertEquals("Gave up the flight: the glide didn't start in 3 take-offs", gaveUp);
        assertEquals(2, takeOffs.size());
        assertEquals("The glide didn't start in 3 s of jumping in lava (take-off 1 of 3), trying again at once", lines.get(0));
        assertEquals("The glide didn't start in 3 s of jumping in lava (take-off 2 of 3), trying again at once", lines.get(1));
        // each try jumps its full 3 s, and the next starts the tick after
        assertEquals(GlideRecovery.TAKE_OFF_TICKS, (int) takeOffs.get(0));
        assertEquals(GlideRecovery.TAKE_OFF_TICKS + 1, takeOffs.get(1) - takeOffs.get(0));
    }
}
