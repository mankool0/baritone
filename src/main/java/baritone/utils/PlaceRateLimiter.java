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

package baritone.utils;

import baritone.api.event.events.PacketEvent;
import baritone.api.event.events.WorldEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Keeps item uses under a Paper server's spam limit on them. Uses on a block and uses in the air
 * share one counter: 9 get through per window, which starts at the first one after the last window
 * ran out, and the rest are dropped without a reply. The 9 is fixed, the window's length is server
 * config (300 ms by default), and it's timed by when packets arrive, so network jitter bunches up
 * uses that went out spread.
 * <p>
 * So this lets at most 9 out in any window of its own, trailing and timed by when they're sent.
 * It starts at the default length, lengthens it whenever the server drops a use and shortens it
 * back while none are. At more than one place a tick that settles a little under 9 per window,
 * since the ticks fall on 50 ms steps and drift.
 * <p>
 * A server can also hold placing to one a tick, by turning down the places after the first in
 * their tick, or by dropping uses however long the window gets. Either way placing more than one a
 * tick only loses blocks, so after a few of those it gives up on it until {@link #retryMultiPlace}.
 * A place turned down now and then, for reach say, is no sign of that, so turned down second
 * places only count once they're a good share of the ones sent.
 * <p>
 * An anticheat can also take placing more than one a tick badly without refusing the places, by
 * setting the player back while it does. Every place sent until the set-back is answered is turned
 * down too, so after a few set-backs close together while placing more than one a tick this places
 * one a tick for a few minutes, then tries again.
 */
public final class PlaceRateLimiter implements AbstractGameEventListener {

    static final int SERVER_LIMIT = 9;
    private static final long MIN_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(300);
    // 9 per 450 ms is a place a tick; a server that strict drops those too
    private static final long MAX_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(450);
    private static final long STEP_UP_NANOS = TimeUnit.MILLISECONDS.toNanos(15);
    private static final long STEP_DOWN_NANOS = TimeUnit.MILLISECONDS.toNanos(5);
    private static final long RECOVER_AFTER_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final int GIVE_UP_AFTER = 3;
    private static final long GIVE_UP_WITHIN_NANOS = TimeUnit.SECONDS.toNanos(60);
    // the share of second places sent that being turned down gives up at
    private static final int GIVE_UP_REFUSED_ONE_IN = 4;
    private static final int BACK_OFF_AFTER_SETBACKS = 3;
    private static final long BACK_OFF_SETBACKS_WITHIN_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final long BACK_OFF_NANOS = TimeUnit.MINUTES.toNanos(3);
    // a set-back counts against placing more than one a tick if a second place went out this shortly before
    private static final long SECOND_PLACE_LATELY_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final LongSupplier clock;
    private final ArrayDeque<Long> sent = new ArrayDeque<>();
    private long windowNanos = MIN_WINDOW_NANOS;
    private long lastStepUp;
    private long lastDrop;
    private long lastStepDown;
    // when places after the first in their tick went out, and when ones were turned down
    private final ArrayDeque<Long> secondPlaces = new ArrayDeque<>();
    private final ArrayDeque<Long> refusedSecondPlaces = new ArrayDeque<>();
    // when uses were dropped at the longest window
    private final ArrayDeque<Long> droppedAtLongest = new ArrayDeque<>();
    private boolean multiPlaceGivenUp;
    // set-backs soon after second places, and until when placing is held to one a tick for them
    private final ArrayDeque<Long> setbacks = new ArrayDeque<>();
    private long lastSecondPlace;
    private long backOffUntil;
    private int backOffsStarted;

    public PlaceRateLimiter() {
        this(System::nanoTime);
    }

    PlaceRateLimiter(LongSupplier clock) {
        this.clock = clock;
        long now = clock.getAsLong();
        lastStepUp = now - RECOVER_AFTER_NANOS;
        lastDrop = now - RECOVER_AFTER_NANOS;
        lastStepDown = now;
        lastSecondPlace = now - SECOND_PLACE_LATELY_NANOS - 1;
        backOffUntil = now;
    }

    @Override
    public void onSendPacket(PacketEvent event) {
        if (event.getState() == EventState.PRE
                && (event.getPacket() instanceof ServerboundUseItemOnPacket || event.getPacket() instanceof ServerboundUseItemPacket)) {
            sent.add(clock.getAsLong());
        } else if (event.getState() == EventState.PRE && event.getPacket() instanceof ServerboundAcceptTeleportationPacket) {
            noteSetback(); // sent as the client applies a teleport, on the main thread
        }
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        sent.clear(); // a new connection starts the server's count afresh; the window it learned stays
    }

    /**
     * @return how many places may go out now
     */
    public int placesAllowed() {
        long now = clock.getAsLong();
        while (!sent.isEmpty() && now - sent.peekFirst() >= windowNanos) {
            sent.removeFirst();
        }
        if (windowNanos > MIN_WINDOW_NANOS && now - lastDrop >= RECOVER_AFTER_NANOS && now - lastStepDown >= RECOVER_AFTER_NANOS) {
            windowNanos = Math.max(MIN_WINDOW_NANOS, windowNanos - STEP_DOWN_NANOS);
            lastStepDown = now;
        }
        return Math.max(0, SERVER_LIMIT - sent.size());
    }

    /**
     * Learns from the uses the server didn't handle since this was last called, while placing
     * more than one a tick.
     */
    public void noteUnhandled(PendingPlaces.Unanswered unhandled) {
        long now = clock.getAsLong();
        addTimes(refusedSecondPlaces, now, unhandled.refusedLater());
        if (unhandled.dropped() > 0 && windowNanos >= MAX_WINDOW_NANOS) {
            addTimes(droppedAtLongest, now, unhandled.dropped()); // as slow as a place a tick and still dropped
        }
        prune(now);
        int refused = refusedSecondPlaces.size();
        if (refused >= GIVE_UP_AFTER && refused * GIVE_UP_REFUSED_ONE_IN >= secondPlaces.size() || droppedAtLongest.size() >= GIVE_UP_AFTER) {
            multiPlaceGivenUp = true;
        }
        if (unhandled.dropped() <= 0) {
            return;
        }
        // what one burst loses shows up together, so step once a window
        if (now - lastStepUp >= windowNanos) {
            windowNanos = Math.min(MAX_WINDOW_NANOS, windowNanos + STEP_UP_NANOS);
            lastStepUp = now;
        }
        lastDrop = now;
    }

    /** Called for every place sent after the first in its tick */
    public void noteSecondPlace() {
        long now = clock.getAsLong();
        secondPlaces.add(now);
        lastSecondPlace = now;
    }

    /** Called for every position the server set, which while building is an anticheat set-back */
    void noteSetback() {
        long now = clock.getAsLong();
        if (now - lastSecondPlace > SECOND_PLACE_LATELY_NANOS) {
            return;
        }
        setbacks.add(now);
        while (now - setbacks.peekFirst() > BACK_OFF_SETBACKS_WITHIN_NANOS) {
            setbacks.removeFirst();
        }
        if (setbacks.size() >= BACK_OFF_AFTER_SETBACKS) {
            setbacks.clear();
            backOffUntil = now + BACK_OFF_NANOS;
            backOffsStarted++;
        }
    }

    /**
     * @return how many times placing went to one a tick for set-backs since this was last called
     */
    public int takeBackOffsStarted() {
        int started = backOffsStarted;
        backOffsStarted = 0;
        return started;
    }

    /**
     * @return how many more seconds placing stays at one a tick after set-backs, 0 if it isn't
     */
    public int backOffSecondsLeft() {
        long left = backOffUntil - clock.getAsLong();
        return left <= 0 ? 0 : (int) Math.max(1, TimeUnit.NANOSECONDS.toSeconds(left));
    }

    /**
     * @return whether the server held placing to one a tick, so placing more is pointless
     */
    public boolean multiPlaceGivenUp() {
        return multiPlaceGivenUp;
    }

    /** Tries placing more than one a tick again, say after printerMultiPlace was changed */
    public void retryMultiPlace() {
        multiPlaceGivenUp = false;
        secondPlaces.clear();
        refusedSecondPlaces.clear();
        droppedAtLongest.clear();
        setbacks.clear();
        backOffUntil = clock.getAsLong();
    }

    public int windowMillis() {
        return (int) TimeUnit.NANOSECONDS.toMillis(windowNanos);
    }

    private static void addTimes(ArrayDeque<Long> times, long now, int count) {
        for (int i = 0; i < count; i++) {
            times.add(now);
        }
    }

    private void prune(long now) {
        for (ArrayDeque<Long> times : List.of(secondPlaces, refusedSecondPlaces, droppedAtLongest)) {
            while (!times.isEmpty() && now - times.peekFirst() > GIVE_UP_WITHIN_NANOS) {
                times.removeFirst();
            }
        }
    }
}
