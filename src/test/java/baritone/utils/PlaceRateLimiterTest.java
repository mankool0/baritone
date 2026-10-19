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
import baritone.api.event.events.type.EventState;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaceRateLimiterTest {

    private static final long MS = 1_000_000L;

    private final long[] now = {0};
    private final PlaceRateLimiter limiter = new PlaceRateLimiter(() -> now[0]);

    private void dropped(int count) {
        limiter.noteUnhandled(new PendingPlaces.Unanswered(count, 0, 0));
    }

    private void setBackAfterASecondPlace() {
        limiter.noteSecondPlace();
        now[0] += 200 * MS;
        limiter.onSendPacket(new PacketEvent(null, EventState.PRE, new ServerboundAcceptTeleportationPacket(1)));
    }

    private void send(int count) {
        for (int i = 0; i < count; i++) {
            limiter.onSendPacket(new PacketEvent(null, EventState.PRE, new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 0, 0, 0)));
        }
    }

    @Test
    public void holdsTheTenthUntilTheFirstIsAWindowOld() {
        send(5);
        now[0] = 100 * MS;
        assertEquals(4, limiter.placesAllowed());
        send(4);
        now[0] = 299 * MS;
        assertEquals(0, limiter.placesAllowed());
        now[0] = 300 * MS;
        assertEquals(5, limiter.placesAllowed());
    }

    @Test
    public void dropsLengthenTheWindowOnceAWindow() {
        dropped(3);
        assertEquals(315, limiter.windowMillis());
        now[0] = 200 * MS;
        dropped(1); // the same burst still showing up
        assertEquals(315, limiter.windowMillis());
        now[0] = 400 * MS;
        dropped(1);
        assertEquals(330, limiter.windowMillis());
        assertFalse(limiter.multiPlaceGivenUp());
    }

    @Test
    public void refusalsAndRefusedSecondPlacesAreTreatedApart() {
        // turned down on their own, but as the first in their tick: not about placing more
        limiter.noteUnhandled(new PendingPlaces.Unanswered(0, 5, 0));
        assertEquals(300, limiter.windowMillis());
        assertFalse(limiter.multiPlaceGivenUp());
        limiter.noteUnhandled(new PendingPlaces.Unanswered(0, 2, 2));
        assertFalse(limiter.multiPlaceGivenUp());
        now[0] = 1_000 * MS;
        limiter.noteUnhandled(new PendingPlaces.Unanswered(0, 1, 1));
        assertTrue(limiter.multiPlaceGivenUp());
        assertEquals(300, limiter.windowMillis());
        limiter.retryMultiPlace();
        assertFalse(limiter.multiPlaceGivenUp());
    }

    @Test
    public void aFewRefusedAmongManySecondPlacesAreForgiven() {
        for (int i = 0; i < 100; i++) {
            limiter.noteSecondPlace();
        }
        limiter.noteUnhandled(new PendingPlaces.Unanswered(0, 3, 3));
        assertFalse(limiter.multiPlaceGivenUp());
        limiter.noteUnhandled(new PendingPlaces.Unanswered(0, 22, 22));
        assertTrue(limiter.multiPlaceGivenUp());
    }

    @Test
    public void refusedSecondPlacesFarApartAreForgiven() {
        limiter.noteUnhandled(new PendingPlaces.Unanswered(0, 2, 2));
        now[0] = 61_000 * MS;
        limiter.noteUnhandled(new PendingPlaces.Unanswered(0, 1, 1));
        assertFalse(limiter.multiPlaceGivenUp());
    }

    @Test
    public void givesUpWhenDropsGoOnAtTheLongestWindow() {
        for (int i = 0; i < 10; i++) {
            now[0] += 500 * MS;
            dropped(1);
        }
        assertEquals(450, limiter.windowMillis());
        assertFalse(limiter.multiPlaceGivenUp());
        for (int i = 0; i < 3; i++) {
            now[0] += 500 * MS;
            dropped(1);
        }
        assertTrue(limiter.multiPlaceGivenUp());
    }

    @Test
    public void setbacksCloseTogetherHoldPlacingToOneATickForAWhile() {
        setBackAfterASecondPlace();
        now[0] += 10_000 * MS;
        setBackAfterASecondPlace();
        assertEquals(0, limiter.backOffSecondsLeft());
        now[0] += 10_000 * MS;
        setBackAfterASecondPlace();
        assertEquals(180, limiter.backOffSecondsLeft());
        assertEquals(1, limiter.takeBackOffsStarted());
        assertEquals(0, limiter.takeBackOffsStarted());
        now[0] += 179_000 * MS;
        assertEquals(1, limiter.backOffSecondsLeft());
        now[0] += 1_000 * MS;
        assertEquals(0, limiter.backOffSecondsLeft());
        assertFalse(limiter.multiPlaceGivenUp());
    }

    @Test
    public void setbacksFarApartOrWithoutSecondPlacesAreForgiven() {
        for (int i = 0; i < 5; i++) {
            setBackAfterASecondPlace();
            now[0] += 20_000 * MS;
        }
        assertEquals(0, limiter.backOffSecondsLeft());
        limiter.noteSecondPlace();
        now[0] += 1_001 * MS;
        for (int i = 0; i < 5; i++) {
            limiter.onSendPacket(new PacketEvent(null, EventState.PRE, new ServerboundAcceptTeleportationPacket(1)));
        }
        assertEquals(0, limiter.backOffSecondsLeft());
    }

    @Test
    public void changingMultiPlaceEndsTheSetbackHold() {
        for (int i = 0; i < 3; i++) {
            setBackAfterASecondPlace();
        }
        assertTrue(limiter.backOffSecondsLeft() > 0);
        limiter.retryMultiPlace();
        assertEquals(0, limiter.backOffSecondsLeft());
    }

    @Test
    public void shortensBackWhileNoneAreDropped() {
        dropped(1);
        now[0] = 400 * MS;
        dropped(1);
        assertEquals(330, limiter.windowMillis());
        now[0] = 5_399 * MS;
        limiter.placesAllowed();
        assertEquals(330, limiter.windowMillis());
        now[0] = 5_400 * MS;
        limiter.placesAllowed();
        assertEquals(325, limiter.windowMillis());
        now[0] = 9_000 * MS;
        limiter.placesAllowed();
        assertEquals(325, limiter.windowMillis());
        now[0] = 10_400 * MS;
        limiter.placesAllowed();
        assertEquals(320, limiter.windowMillis());
        // never below the server's default
        now[0] = 1_000_000 * MS;
        for (int i = 0; i < 20; i++) {
            now[0] += 5_000 * MS;
            limiter.placesAllowed();
        }
        assertEquals(300, limiter.windowMillis());
    }
}
