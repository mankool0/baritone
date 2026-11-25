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

import static baritone.process.elytra.FireResistanceEat.Step.*;
import static org.junit.Assert.*;

/**
 * A flight pulled down over lava eats an enchanted golden apple for fire resistance at the first setback down, before
 * the pull-down has added up and well before it's in the lava; in lava it doesn't wait to take off. In lava or fire it
 * eats as a backstop, not while enough is left, says once when there's no apple, and lets go of an eat no apple went
 * down in.
 */
public class FireResistanceEatTest {

    private static final Vec3 DOWN_AND_BACK = new Vec3(-0.6, -1.4, 0.2);
    // the default, elytraFireResistanceMinSeconds
    private static final int MIN = 60 * 20;
    private static final int APPLE = 6000;

    private Settings settings;
    private GlideSetbacks setbacks;
    private GlideRecovery recovery;
    private FireResistanceEat eat;

    @BeforeClass
    public static void bootstrap() {
        HeadlessGame.bootstrap();
    }

    @Before
    public void fresh() {
        this.settings = TestSettings.fresh();
        this.setbacks = new GlideSetbacks(this.settings);
        this.recovery = new GlideRecovery(this.settings, this.setbacks);
        this.eat = new FireResistanceEat();
    }

    /**
     * Why fire resistance is wanted at {@code t}, as the flight works it out: gliding over lava, out of it
     */
    private String overLava(int t) {
        return FireResistanceEat.danger(null, false, this.setbacks.draggedDownLately(t));
    }

    @Test
    public void pulledDownOverLavaItEatsBeforeItGoesInAndTakesOffFromItAtOnce() {
        int t = 100;
        int apples = 4;
        int fireResistance = 0;
        // gliding over lava with no setbacks: nothing to eat for
        assertEquals(NONE, this.eat.tick(t, this.overLava(t), fireResistance, MIN, apples));

        // the first setback down: the eat starts, two setbacks before the pull-down
        t += 15;
        this.setbacks.note(t, DOWN_AND_BACK);
        assertEquals(GlideRecovery.Action.NONE, this.recovery.tick(t, true, false, false));
        assertEquals(EAT, this.eat.tick(t, this.overLava(t), fireResistance, MIN, apples));
        assertEquals("Eating an enchanted golden apple for fire resistance (pulled down over lava, none left)", this.eat.line());
        final int eatStart = t;

        // the 32 ticks the apple takes, while the server pulls on and the pull-down comes
        boolean settled = false;
        for (int i = 1; i <= 32; i++) {
            t++;
            if (i % 15 == 0) {
                this.setbacks.note(t, DOWN_AND_BACK);
            }
            final GlideRecovery.Action action = this.recovery.tick(t, true, false, false);
            if (action == GlideRecovery.Action.SETTLE) {
                settled = true;
                // no floor near, only lava: flying on while it looks further
            }
            assertEquals(EAT, this.eat.tick(t, this.overLava(t), fireResistance, MIN, apples));
            assertNull(this.eat.line());
        }
        assertTrue(settled);
        assertTrue(this.recovery.settling());

        // it went down: fire resistance before the lava
        apples--;
        fireResistance = APPLE;
        t++;
        assertEquals(STOP, this.eat.tick(t, this.overLava(t), fireResistance, MIN, apples));
        assertFalse(this.eat.eating());
        assertTrue(t - eatStart < 40);

        // pulled on into the lava and onto its bottom: up again at once, and no second apple
        t += 20;
        fireResistance -= 20;
        assertEquals(NONE, this.eat.tick(t, FireResistanceEat.danger("lava", true, false), fireResistance, MIN, apples));
        assertEquals(GlideRecovery.Action.TAKE_OFF, this.recovery.tick(t, false, true, false, "lava"));
        assertEquals(3, apples);
    }

    @Test
    public void inLavaOrFireItEatsAsABackstopNotWhileEnoughIsLeft() {
        assertEquals(NONE, this.eat.tick(0, FireResistanceEat.danger("lava", true, false), MIN, MIN, 3));
        assertEquals(NONE, this.eat.tick(1, null, 0, MIN, 3));
        assertEquals(EAT, this.eat.tick(2, FireResistanceEat.danger("lava", true, false), MIN - 1, MIN, 3));
        assertEquals("Eating an enchanted golden apple for fire resistance (in lava, 59 s of it left)", this.eat.line());

        final FireResistanceEat onFire = new FireResistanceEat();
        assertEquals(EAT, onFire.tick(0, FireResistanceEat.danger(null, true, false), 0, MIN, 1));
        assertEquals("Eating an enchanted golden apple for fire resistance (on fire, none left)", onFire.line());
        assertEquals("in fire", FireResistanceEat.danger("fire", false, true));
    }

    @Test
    public void anEatGoesOnPastTheDangerUntilTheAppleIsGone() {
        assertEquals(EAT, this.eat.tick(0, "in lava", 0, MIN, 2));
        // out of the lava a few ticks in: it's nearly done, and the next danger would only start it again
        for (int t = 1; t < 30; t++) {
            assertEquals(EAT, this.eat.tick(t, null, 0, MIN, 2));
        }
        // the effect came in before the apple left the inventory
        assertEquals(STOP, this.eat.tick(30, null, APPLE, MIN, 2));
    }

    @Test
    public void withNoAppleItSaysSoOnceAFlight() {
        assertEquals(NONE, this.eat.tick(0, "in lava", 0, MIN, 0));
        assertEquals("No enchanted golden apple to eat for fire resistance (in lava)", this.eat.line());
        for (int t = 1; t < 100; t++) {
            assertEquals(NONE, this.eat.tick(t, t % 2 == 0 ? "in lava" : null, 0, MIN, 0));
            assertNull(this.eat.line());
        }
        // one picked up: eaten
        assertEquals(EAT, this.eat.tick(100, "in lava", 0, MIN, 1));
        this.eat.reset();
        assertEquals(NONE, this.eat.tick(200, "in lava", 0, MIN, 0));
        assertNotNull(this.eat.line());
    }

    @Test
    public void anEatNoAppleWentDownInIsLetGoOfAndTriedAgainLater() {
        assertEquals(EAT, this.eat.tick(0, "in lava", 0, MIN, 2));
        int t = 1;
        while (t < FireResistanceEat.EAT_MAX_TICKS) {
            assertEquals(EAT, this.eat.tick(t++, "in lava", 0, MIN, 2));
        }
        assertEquals(STOP, this.eat.tick(t, "in lava", 0, MIN, 2));
        assertEquals("No enchanted golden apple went down in 10 s of eating for fire resistance, trying again in 5 s", this.eat.line());
        final int stopped = t;
        while (t < stopped + FireResistanceEat.AGAIN_TICKS) {
            assertEquals(NONE, this.eat.tick(t++, "in lava", 0, MIN, 2));
        }
        assertEquals(EAT, this.eat.tick(t, "in lava", 0, MIN, 2));
    }
}
