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

/**
 * What a flight moved by glide steps does about the server pulling it down and about take-offs that don't glide. Once
 * the server pulls a gliding player down it keeps on until the player is down, so the flight stops fighting it: it
 * comes down on the floor below, waits, plans again from there and takes off slower ({@link GlideSetbacks}). A
 * take-off that hasn't got the glide going after {@link #TAKE_OFF_TICKS} waits longer each time, and after
 * {@link Settings#elytraTakeOffTries} of them, or {@link Settings#elytraPullDownLimit} pull-downs with no clean stretch
 * between, the flight gives up. In lava or fire it never waits: a touchdown there takes off again at once, and so does
 * the next take-off after one that didn't glide. Brought down into lava with the glide still on, it lets go of the
 * glide for a moment and takes off from there ({@link Action#LET_GO}): held there, every step up is put back.
 * <p>
 * The flight asks {@link #tick} what to do once a tick and does it; {@link #line()} is what to say about it then.
 */
public final class GlideRecovery {

    public enum Action {
        /** Carry on */
        NONE,
        /** The server is pulling the player down: come down on the floor below, then {@link #noFloor()} if none */
        SETTLE,
        /** Down after a pull-down, or after a take-off that didn't glide: stay put */
        HOLD,
        /** Plan the flight again from here and take off */
        TAKE_OFF,
        /**
         * Let go of the glide, no steps and nothing held, before a take-off from here: in lava after a pull-down with
         * the glide still on, the server puts every step back where it has the player, five times a second, for as long
         * as the glide is held there. A flight started afresh from there gets out at once.
         */
        LET_GO,
        /** Stop the flight */
        GIVE_UP
    }

    private enum Phase {
        FLYING,
        SETTLING,
        LETTING_GO,
        SETTLED,
        TAKING_OFF,
        GAVE_UP
    }

    // jumping this long without the glide starting is a take-off that didn't
    static final int TAKE_OFF_TICKS = 60;
    private static final int FIRST_WAIT_TICKS = 20;
    private static final int LONGEST_WAIT_TICKS = 200;
    // a player found on the ground this soon after a setback down was put there by it: a pull-down that's over
    private static final int PUT_DOWN_TICKS = 40;
    // coming down after a pull-down takes no longer than this, or the landing has gone wrong and the flight goes on
    private static final int SETTLE_TICKS = 600;
    // in lava this long with the glide on while being brought down, it lets go of the glide, for this long
    static final int IN_LAVA_TICKS = 20;
    static final int LET_GO_TICKS = 40;

    private final Settings settings;
    private final GlideSetbacks setbacks;
    private Phase phase = Phase.FLYING;
    private int phaseTick;
    // when it went into lava, -1 when not in it
    private int inLavaSince = -1;
    private int resumeTick;
    private int jumpingSince = -1;
    private int takeOffFails;
    private String line;

    public GlideRecovery(Settings settings, GlideSetbacks setbacks) {
        this.settings = settings;
        this.setbacks = setbacks;
    }

    /**
     * @param now        The flight's tick
     * @param fallFlying Whether the player is gliding
     * @param onGround   Whether the player stands on something
     * @param jumping    Whether the flight is trying to take off, its path in
     */
    public Action tick(int now, boolean fallFlying, boolean onGround, boolean jumping) {
        return this.tick(now, fallFlying, onGround, jumping, null);
    }

    /**
     * @param heat What the player is in that burns, {@code "lava"} or {@code "fire"}, {@code null} when nothing: there
     *             it doesn't wait for anything
     */
    public Action tick(int now, boolean fallFlying, boolean onGround, boolean jumping, String heat) {
        this.line = null;
        if (!"lava".equals(heat)) {
            this.inLavaSince = -1;
        } else if (this.inLavaSince < 0) {
            this.inLavaSince = now;
        }
        final Action action = this.tick0(now, fallFlying, onGround, jumping, heat);
        if (action == Action.GIVE_UP) {
            // said once: the flight lands or ends from here, and nothing here changes that
            this.phase = Phase.GAVE_UP;
        }
        return action;
    }

    private Action tick0(int now, boolean fallFlying, boolean onGround, boolean jumping, String heat) {
        switch (this.phase) {
            case GAVE_UP:
                return Action.NONE;
            case FLYING:
                if (fallFlying) {
                    return this.setbacks.pullingDown(now) ? this.pullDown(now, heat) : Action.NONE;
                }
                if (onGround && this.setbacks.droppedWithin(now, PUT_DOWN_TICKS)) {
                    // the server had it down before the setbacks added up to a pull-down
                    final Action giveUp = this.countPullDown(now, "put down by the server", heat);
                    return giveUp != null ? giveUp : this.settled(now, heat);
                }
                if (jumping) {
                    this.phase = Phase.TAKING_OFF;
                    this.jumpingSince = now;
                }
                return Action.NONE;
            case SETTLING:
                // the glide over in lava is down too, on the bottom or not
                if (!fallFlying && (onGround || heat != null)) {
                    return this.settled(now, heat);
                }
                if (this.inLavaSince >= 0 && now - this.inLavaSince >= IN_LAVA_TICKS) {
                    // brought down into it with the glide on: there's no floor to come down on in lava, and the glide
                    // the client holds never ends there. every step up is put back, and waiting out the settle only
                    // gets it pulled down again
                    this.phase = Phase.LETTING_GO;
                    this.phaseTick = now;
                    this.line = "In lava with the glide still on after the pull-down, and the server puts every step back: letting go of the glide for "
                            + LET_GO_TICKS / 20 + " s, then taking off again from there at " + this.setbacks.speedPercent() + " glide speed";
                    return Action.LET_GO;
                }
                if (now - this.phaseTick > SETTLE_TICKS) {
                    this.phase = Phase.FLYING;
                }
                return Action.NONE;
            case LETTING_GO:
                if (now - this.phaseTick < LET_GO_TICKS) {
                    return Action.LET_GO;
                }
                return this.takeOffAgain();
            case SETTLED:
                if (now < this.resumeTick && heat == null) {
                    return Action.HOLD;
                }
                this.line = (now < this.resumeTick ? "In " + heat + ", taking off again now" : "Taking off again")
                        + " at " + this.setbacks.speedPercent() + " glide speed";
                return this.takeOffAgain();
            default: // TAKING_OFF
                if (fallFlying) {
                    // gliding: what the server does from here counts afresh
                    this.phase = Phase.FLYING;
                    this.takeOffFails = 0;
                    this.setbacks.spend();
                    return Action.NONE;
                }
                if (!jumping) {
                    // the path isn't in yet: the clock starts with the jumping
                    this.jumpingSince = -1;
                    return Action.NONE;
                }
                if (this.jumpingSince < 0) {
                    this.jumpingSince = now;
                }
                if (now - this.jumpingSince < TAKE_OFF_TICKS) {
                    return Action.NONE;
                }
                this.takeOffFails++;
                final int tries = Math.max(1, this.settings.elytraTakeOffTries.value);
                if (this.takeOffFails >= tries) {
                    this.line = "Gave up the flight: the glide didn't start in " + tries + " take-offs";
                    return Action.GIVE_UP;
                }
                if (heat != null) {
                    this.line = "The glide didn't start in " + TAKE_OFF_TICKS / 20 + " s of jumping in " + heat + " (take-off "
                            + this.takeOffFails + " of " + tries + "), trying again at once";
                    return this.takeOffAgain();
                }
                final int wait = waitTicks(this.takeOffFails);
                this.phase = Phase.SETTLED;
                this.resumeTick = now + wait;
                this.line = "The glide didn't start in " + TAKE_OFF_TICKS / 20 + " s of jumping (take-off " + this.takeOffFails
                        + " of " + tries + "), trying again in " + wait / 20 + " s";
                return Action.HOLD;
        }
    }

    /**
     * There's no floor to come down on: the flight carries on, slower
     */
    public void noFloor() {
        if (this.phase == Phase.SETTLING) {
            this.phase = Phase.FLYING;
        }
    }

    /**
     * @return Whether the flight is coming down after a pull-down
     */
    public boolean settling() {
        return this.phase == Phase.SETTLING;
    }

    /**
     * @return Whether the flight has let go of the glide for a moment before it takes off again ({@link Action#LET_GO})
     */
    public boolean lettingGo() {
        return this.phase == Phase.LETTING_GO;
    }

    /**
     * @return Whether the flight is down after a pull-down or a take-off that didn't glide, waiting to take off
     */
    public boolean settled() {
        return this.phase == Phase.SETTLED;
    }

    /**
     * @return What to say about the action {@link #tick} last gave, {@code null} for nothing
     */
    public String line() {
        return this.line;
    }

    private Action pullDown(int now, String heat) {
        final int downward = this.setbacks.downward(now);
        final int turnedDown = this.setbacks.turnedDown(now);
        final String before = this.setbacks.speedPercent();
        final Action giveUp = this.countPullDown(now, "pulled down", heat);
        if (giveUp != null) {
            return giveUp;
        }
        this.phase = Phase.SETTLING;
        this.phaseTick = now;
        this.line = (downward >= turnedDown
                ? "Pulled down by the server (" + downward + " setbacks down in " + this.setbacks.windowSeconds() + " s)"
                : "Turned back by the server again and again (" + turnedDown + " setbacks in " + this.setbacks.windowSeconds()
                + " s, " + downward + " of them down)")
                + ": coming down on the floor below, glide speed " + before + " -> " + this.setbacks.speedPercent();
        return Action.SETTLE;
    }

    /**
     * @return {@link Action#GIVE_UP} when this pull-down is one too many, else {@code null}
     */
    private Action countPullDown(int now, String how, String heat) {
        final int pullDowns = this.setbacks.pulledDown();
        final int limit = Math.max(1, this.settings.elytraPullDownLimit.value);
        // in lava or fire it doesn't give up: that would end the flight in it. it takes off again at once instead,
        // slower each time
        if (pullDowns >= limit && heat == null) {
            this.line = "Gave up the flight: " + how + " " + pullDowns + " times without "
                    + this.settings.elytraGlideSpeedRecoveryBlocks.value + " blocks clear of setbacks between";
            return Action.GIVE_UP;
        }
        return null;
    }

    private Action settled(int now, String heat) {
        if (heat != null) {
            this.line = "Down in " + heat + " after the pull-down, taking off again at once at " + this.setbacks.speedPercent() + " glide speed";
            return this.takeOffAgain();
        }
        final int wait = waitTicks(this.setbacks.pullDowns());
        this.setbacks.spend();
        this.phase = Phase.SETTLED;
        this.resumeTick = now + wait;
        this.line = "Down after the pull-down, taking off again in " + wait / 20 + " s at " + this.setbacks.speedPercent() + " glide speed";
        return Action.HOLD;
    }

    /**
     * The flight again from here, and the take-off timed afresh
     */
    private Action takeOffAgain() {
        this.phase = Phase.TAKING_OFF;
        this.jumpingSince = -1;
        this.setbacks.spend();
        return Action.TAKE_OFF;
    }

    /**
     * 1 s, then twice as long each time, at most 10 s
     */
    static int waitTicks(int times) {
        final int doublings = Math.max(0, Math.min(times - 1, 4));
        return Math.min(LONGEST_WAIT_TICKS, FIRST_WAIT_TICKS << doublings);
    }
}
