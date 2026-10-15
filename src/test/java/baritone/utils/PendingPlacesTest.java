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

import baritone.test.HeadlessGame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PendingPlacesTest {

    private static final int SLOT = 5;

    @BeforeClass
    public static void bootstrap() {
        HeadlessGame.bootstrap();
    }

    /**
     * Places one block per tick from a hotbar stack against a vanilla server: the server sends the
     * slot's new count and then the ack in the tick it handles a place, and the client takes the
     * count as is.
     */
    private static final class Simulation {
        private record Packet(int arrives, Runnable action) {}

        final int oneWay;
        final boolean tracked;
        final List<Packet> toClient = new ArrayList<>();
        final List<Packet> toServer = new ArrayList<>();
        long now;
        final PendingPlaces pending = new PendingPlaces(() -> now);
        ItemStack clientStack;
        int serverCount;
        int lastSentCount;
        int sequence;
        int ackedThisTick = -1;
        int placed;
        int placedEmpty;
        int refuseNext;

        Simulation(int count, int oneWay, boolean tracked) {
            this.oneWay = oneWay;
            this.tracked = tracked;
            clientStack = new ItemStack(Items.OBSIDIAN, count);
            serverCount = count;
            lastSentCount = count;
        }

        void tick(int t) {
            now = t * 50_000_000L;
            deliver(toClient, t);
            ItemStack stack = clientStack;
            boolean place = tracked ? pending.countAfterInFlight(SLOT, stack) > 0 : !stack.isEmpty();
            if (place) {
                int seq = ++sequence;
                int before = stack.getCount();
                stack.shrink(1);
                pending.onUse(SLOT, seq, stack, before);
                toServer.add(new Packet(t + oneWay, () -> serverPlace(seq)));
            }
            deliver(toServer, t);
            if (serverCount != lastSentCount) {
                int count = serverCount;
                lastSentCount = count;
                // every update is a fresh stack, which drops the client's own decrements
                toClient.add(new Packet(t + oneWay, () -> clientStack = new ItemStack(Items.OBSIDIAN, count)));
            }
            if (ackedThisTick != -1) {
                int acked = ackedThisTick;
                ackedThisTick = -1;
                toClient.add(new Packet(t + oneWay, () -> pending.onAck(acked)));
            }
        }

        void serverPlace(int seq) {
            ackedThisTick = seq;
            if (refuseNext > 0) {
                refuseNext--;
            } else if (serverCount > 0) {
                serverCount--;
                placed++;
            } else {
                placedEmpty++; // goes out with whatever got picked up into the slot meanwhile
            }
        }

        static void deliver(List<Packet> packets, int t) {
            while (!packets.isEmpty() && packets.get(0).arrives() <= t) {
                packets.remove(0).action().run();
            }
        }

        Simulation run(int ticks) {
            for (int t = 0; t < ticks; t++) {
                tick(t);
            }
            return this;
        }
    }

    @Test
    public void untrackedCountSendsPlacesPastTheEndOfTheStack() {
        Simulation sim = new Simulation(64, 3, false).run(200);
        assertEquals(64, sim.placed);
        assertTrue("expected places sent on a stale count, got " + sim.placedEmpty, sim.placedEmpty > 0);
    }

    @Test
    public void trackedCountPlacesTheWholeStackAndNothingMore() {
        for (int oneWay = 0; oneWay <= 8; oneWay++) {
            Simulation sim = new Simulation(64, oneWay, true).run(400);
            assertEquals("one way " + oneWay, 64, sim.placed);
            assertEquals("one way " + oneWay, 0, sim.placedEmpty);
            assertEquals(0, sim.pending.inFlight(SLOT));
        }
    }

    @Test
    public void refusedPlaceStillPlacesTheWholeStack() {
        Simulation sim = new Simulation(3, 2, true);
        sim.refuseNext = 1;
        sim.run(100);
        assertEquals(3, sim.placed);
        assertEquals(0, sim.placedEmpty);
    }

    @Test
    public void refusedPlaceIsGivenBackOnceAcked() {
        PendingPlaces pending = new PendingPlaces();
        ItemStack stack = new ItemStack(Items.OBSIDIAN, 5);
        stack.shrink(1);
        pending.onUse(SLOT, 1, stack, 5);
        assertEquals(4, pending.countAfterInFlight(SLOT, stack));
        // the server didn't place it, so no update comes, only the ack
        pending.onAck(1);
        assertEquals(4, stack.getCount());
        assertEquals(5, pending.countAfterInFlight(SLOT, stack));
    }

    @Test
    public void lostUseStopsHoldingTheSlot() {
        long[] now = {0};
        PendingPlaces pending = new PendingPlaces(() -> now[0]);
        ItemStack stack = new ItemStack(Items.OBSIDIAN, 2);
        stack.shrink(1);
        pending.onUse(SLOT, 1, stack, 2);
        // the place was dropped before reaching the server, and an update from it brought the
        // stack back without the client's decrement, so the slot reads as spent
        ItemStack update = new ItemStack(Items.OBSIDIAN, 1);
        assertEquals(0, pending.countAfterInFlight(SLOT, update));
        now[0] = 1_000_000_000L;
        assertEquals(1, pending.inFlight(SLOT));
        now[0] = 2_500_000_000L;
        assertEquals(0, pending.inFlight(SLOT));
        assertEquals(1, pending.countAfterInFlight(SLOT, update));
    }

    @Test
    public void updateFromServerDropsTheClientsDecrements() {
        PendingPlaces pending = new PendingPlaces();
        ItemStack stack = new ItemStack(Items.OBSIDIAN, 10);
        stack.shrink(1);
        pending.onUse(SLOT, 1, stack, 10);
        stack.shrink(1);
        pending.onUse(SLOT, 2, stack, 9);
        assertEquals(8 + 2 - 2, pending.countAfterInFlight(SLOT, stack));
        // the server has handled the first place but not the second
        ItemStack update = new ItemStack(Items.OBSIDIAN, 9);
        pending.onAck(1);
        assertEquals(9 - 1, pending.countAfterInFlight(SLOT, update));
        assertEquals(1, pending.inFlight(SLOT));
        assertEquals(0, pending.inFlight(SLOT + 1));
    }
}
