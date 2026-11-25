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

/**
 * When an elytra flight eats an enchanted golden apple for fire resistance, and when the eat is over. A flight the
 * server pulls down over lava goes in, and nothing else that flies it eats: without fire resistance a player lasts a
 * few seconds in lava, and the eat takes 1.6 s, so it eats while it's pulled down over lava, before it gets there,
 * and in lava or fire as a backstop. Not while enough is left ({@link baritone.api.Settings#elytraFireResistanceMinSeconds}).
 * <p>
 * An eat goes on until an apple is gone or the fire resistance is up, even once the danger has passed: it's nearly
 * done by then, and the next danger would only start it again. One that no apple went down in for
 * {@link #EAT_MAX_TICKS} has gone wrong: it lets go and tries again after {@link #AGAIN_TICKS}.
 */
public final class FireResistanceEat {

    public enum Step {
        /** Nothing to do */
        NONE,
        /** Hold the apple's use this tick */
        EAT,
        /** Let go of the eat: an apple went down, or none did for too long */
        STOP
    }

    static final int EAT_MAX_TICKS = 200;
    static final int AGAIN_TICKS = 100;

    // apples on the player as the eat began, -1 while not eating
    private int eatFrom = -1;
    private int eatSince;
    private int againTick = Integer.MIN_VALUE;
    // said that there's no apple to eat, until the flight ends
    private boolean noneSaid;
    private String line;

    /**
     * @param now                 The flight's tick
     * @param danger              Why fire resistance is wanted now, the way the line puts it ("in lava"), {@code null}
     *                            when it isn't
     * @param fireResistanceTicks How long the fire resistance on the player has left, 0 for none
     * @param minTicks            Under how long left to eat
     * @param apples              The enchanted golden apples on the player
     */
    public Step tick(int now, String danger, int fireResistanceTicks, int minTicks, int apples) {
        this.line = null;
        if (this.eatFrom >= 0) {
            if (apples < this.eatFrom || fireResistanceTicks >= minTicks) {
                this.eatFrom = -1;
                return Step.STOP;
            }
            if (now - this.eatSince >= EAT_MAX_TICKS) {
                this.eatFrom = -1;
                this.againTick = now + AGAIN_TICKS;
                this.line = "No enchanted golden apple went down in " + EAT_MAX_TICKS / 20
                        + " s of eating for fire resistance, trying again in " + AGAIN_TICKS / 20 + " s";
                return Step.STOP;
            }
            return Step.EAT;
        }
        if (danger == null || fireResistanceTicks >= minTicks || now < this.againTick) {
            return Step.NONE;
        }
        if (apples <= 0) {
            if (!this.noneSaid) {
                this.noneSaid = true;
                this.line = "No enchanted golden apple to eat for fire resistance (" + danger + ")";
            }
            return Step.NONE;
        }
        this.eatFrom = apples;
        this.eatSince = now;
        this.line = "Eating an enchanted golden apple for fire resistance (" + danger + ", "
                + (fireResistanceTicks > 0 ? fireResistanceTicks / 20 + " s of it" : "none") + " left)";
        return Step.EAT;
    }

    /**
     * @param heat               What the player is in that burns, {@code "lava"} or {@code "fire"}, {@code null} for
     *                           nothing
     * @param onFire             Whether the player burns
     * @param pulledDownOverLava Whether the server set the gliding player back down lately, over lava
     * @return Why fire resistance is wanted, the way the line puts it, {@code null} when it isn't
     */
    public static String danger(String heat, boolean onFire, boolean pulledDownOverLava) {
        return heat != null ? "in " + heat : onFire ? "on fire" : pulledDownOverLava ? "pulled down over lava" : null;
    }

    /**
     * @return Whether an eat is going on
     */
    public boolean eating() {
        return this.eatFrom >= 0;
    }

    /**
     * @return What to say about the step {@link #tick} last gave, {@code null} for nothing
     */
    public String line() {
        return this.line;
    }

    /**
     * The flight is over: nothing of its eats carries over
     */
    public void reset() {
        this.eatFrom = -1;
        this.againTick = Integer.MIN_VALUE;
        this.noneSaid = false;
        this.line = null;
    }
}
