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
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * The server's setbacks over an elytra flight: how many came lately and how far down, whether they add up to the
 * server pulling the player down, and the glide speed ({@link Settings#elytraGlideSpeed}) that goes down a step for
 * each pull-down and back up a step after each clean stretch. Ticks are the flight's own count, so a window is the
 * same length however long the game takes over a tick.
 */
public final class GlideSetbacks {

    /**
     * A setback: when, and where the server put the player from where it was
     */
    private static final class Setback {
        final int tick;
        final Vec3 moved;

        Setback(int tick, Vec3 moved) {
            this.tick = tick;
            this.moved = moved;
        }
    }

    // at most one chat line a second about setbacks; a later line counts the ones in between
    private static final int LINE_EVERY_TICKS = 20;
    // a setback that moves the player less than this up or down is a level one, and less than this all told puts it
    // back where it was
    private static final double LEVEL = 0.05;
    // a setback that puts the player back this far, whichever way, turns the glide down
    static final double TURNED_BACK = 1.0;

    private final Settings settings;
    private final ArrayDeque<Setback> recent = new ArrayDeque<>();
    // setbacks up to this tick count for the line, not towards a pull-down
    private int spentTick = Integer.MIN_VALUE;
    // far enough back that the first setback is said, and not so far that the difference overflows
    private int lastLineTick = Integer.MIN_VALUE / 2;
    // the last line about setbacks that put the player where it already was, said once a window while they go on
    private int lastInPlaceLineTick = Integer.MIN_VALUE / 2;
    // horizontal blocks flown since the last setback
    private double clean;
    // pull-downs since the last clean stretch
    private int pullDowns;

    public GlideSetbacks(Settings settings) {
        this.settings = settings;
    }

    /**
     * Notes a setback at {@code tick} that moved the player by {@code moved}
     *
     * @return The chat line to say about it, {@code null} when one was said less than a second ago
     */
    public String note(int tick, Vec3 moved) {
        this.forget(tick);
        this.recent.addLast(new Setback(tick, moved));
        this.clean = 0;
        final boolean inPlace = inPlace(moved);
        if (tick - this.lastLineTick < LINE_EVERY_TICKS || (inPlace && tick - this.lastInPlaceLineTick < this.windowSeconds() * 20)) {
            return null;
        }
        this.lastLineTick = tick;
        if (inPlace) {
            // the server turning down every move the glide makes from where it holds still: five a second for as long
            // as it holds, and a line each second would say nothing the first didn't
            this.lastInPlaceLineTick = tick;
            return "Set back to where it already was, " + this.recent.size() + " in the last " + this.windowSeconds() + " s";
        }
        final String way;
        if (moved.y <= -LEVEL) {
            way = format(-moved.y) + " down";
        } else if (moved.y >= LEVEL) {
            way = format(moved.y) + " up";
        } else {
            way = "level";
        }
        return "Set back " + format(moved.length()) + " blocks (" + way + "), " + this.recent.size()
                + " in the last " + this.windowSeconds() + " s";
    }

    /**
     * @return How many setbacks within the window up to {@code tick} came after {@code since}, whatever they did
     */
    public int since(int tick, int since) {
        this.forget(tick);
        int count = 0;
        for (Setback setback : this.recent) {
            if (setback.tick > since) {
                count++;
            }
        }
        return count;
    }

    /**
     * @return How many setbacks within the window up to {@code tick}, and after {@code since}, put the player back
     * where it already was
     */
    public int heldInPlace(int tick, int since) {
        this.forget(tick);
        int count = 0;
        for (Setback setback : this.recent) {
            if (setback.tick > since && inPlace(setback.moved)) {
                count++;
            }
        }
        return count;
    }

    /**
     * @return Whether a setback that moved the player by {@code moved} put it back where it already was
     */
    public static boolean inPlace(final Vec3 moved) {
        return moved.lengthSqr() < LEVEL * LEVEL;
    }

    /**
     * @return How many setbacks within the window up to {@code tick} each moved the player at least
     * {@link Settings#elytraPullDownDrop} down
     */
    public int downward(int tick) {
        this.forget(tick);
        final double drop = this.settings.elytraPullDownDrop.value;
        int count = 0;
        for (Setback setback : this.recent) {
            if (setback.tick > this.spentTick && -setback.moved.y >= drop) {
                count++;
            }
        }
        return count;
    }

    /**
     * @return How many setbacks within the window up to {@code tick} each moved the player at least
     * {@link Settings#elytraPullDownDrop} down, or {@link #TURNED_BACK} or more any way: the server turning the glide
     * down as surely as a pull-down. Counting drops alone, a glide put back where it started every few ticks, a step's
     * worth each time, counts none of them and flies at the same few blocks indefinitely
     */
    public int turnedDown(int tick) {
        this.forget(tick);
        final double drop = this.settings.elytraPullDownDrop.value;
        int count = 0;
        for (Setback setback : this.recent) {
            if (setback.tick > this.spentTick && (-setback.moved.y >= drop || setback.moved.lengthSqr() >= TURNED_BACK * TURNED_BACK)) {
                count++;
            }
        }
        return count;
    }

    /**
     * @return Whether the setbacks in the window up to {@code tick} mean the server is pulling the player down, or
     * turning the glide down some other way ({@link #turnedDown})
     */
    public boolean pullingDown(int tick) {
        return this.turnedDown(tick) >= Math.max(1, this.settings.elytraPullDownSetbacks.value);
    }

    /**
     * @return Whether a setback within {@code ticks} before {@code tick} moved the player down
     */
    public boolean droppedWithin(int tick, int ticks) {
        final double drop = this.settings.elytraPullDownDrop.value;
        for (Setback setback : this.recent) {
            if (setback.tick > this.spentTick && tick - setback.tick <= ticks && -setback.moved.y >= drop) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return Whether a setback within the window before {@code tick} moved the player down, spent on a pull-down or
     * not: the server is pulling at it
     */
    public boolean draggedDownLately(int tick) {
        this.forget(tick);
        final double drop = this.settings.elytraPullDownDrop.value;
        for (Setback setback : this.recent) {
            if (-setback.moved.y >= drop) {
                return true;
            }
        }
        return false;
    }

    /**
     * A pull-down: the glide speed goes down a step, and the setbacks that made it are spent
     *
     * @return The pull-downs since the last clean stretch, this one included
     */
    public int pulledDown() {
        this.pullDowns++;
        this.spend();
        this.clean = 0;
        this.setSpeed(this.speed() * this.slowdown());
        return this.pullDowns;
    }

    /**
     * The setbacks so far are spent: the ones while coming down after a pull-down, or while taking off, were the
     * server finishing what it started, not the start of another pull-down
     */
    public void spend() {
        if (!this.recent.isEmpty()) {
            this.spentTick = Math.max(this.spentTick, this.recent.peekLast().tick);
        }
    }

    /**
     * Counts {@code blocks} flown across since the last call, none of it a setback
     *
     * @return The chat line to say when that made a clean stretch and the speed went up a step, else {@code null}
     */
    public String flown(double blocks) {
        if (!(blocks > 0)) {
            return null;
        }
        this.clean += blocks;
        final int stretch = Math.max(1, this.settings.elytraGlideSpeedRecoveryBlocks.value);
        if (this.clean < stretch) {
            return null;
        }
        this.clean = 0;
        this.pullDowns = 0;
        final double before = this.speed();
        if (before >= 1) {
            return null;
        }
        this.setSpeed(before / this.slowdown());
        return "Glide speed back up to " + this.speedPercent() + " after " + stretch + " blocks without a setback";
    }

    /**
     * @return The share of the most a step moves across to fly at, from {@link Settings#elytraGlideSpeedMin} to 1
     */
    public double speed() {
        return clampSpeed(this.settings.elytraGlideSpeed.value, this.settings.elytraGlideSpeedMin.value);
    }

    public String speedPercent() {
        return Math.round(this.speed() * 100) + "%";
    }

    public int pullDowns() {
        return this.pullDowns;
    }

    public int windowSeconds() {
        return Math.max(1, this.settings.elytraSetbackWindowSeconds.value);
    }

    private void setSpeed(double speed) {
        // to a thousandth: a step down and back up again comes back to where it was, not a hair off it
        this.settings.elytraGlideSpeed.value = Math.round(clampSpeed(speed, this.settings.elytraGlideSpeedMin.value) * 1000) / 1000.0;
    }

    private double slowdown() {
        final double slowdown = this.settings.elytraPullDownSlowdown.value;
        // a slowdown of 1 or more would never slow anything, and of 0 or less would stop the flight dead
        return slowdown > 0 && slowdown < 1 ? slowdown : 0.8;
    }

    static double clampSpeed(double speed, double min) {
        final double floor = Math.min(1, Math.max(0.01, min));
        if (!(speed >= floor)) { // NaN as well
            return floor;
        }
        return Math.min(1, speed);
    }

    private void forget(int tick) {
        final int window = this.windowSeconds() * 20;
        while (!this.recent.isEmpty() && tick - this.recent.peekFirst().tick > window) {
            this.recent.removeFirst();
        }
    }

    private static String format(double blocks) {
        return String.format(Locale.ROOT, "%.1f", blocks);
    }
}
