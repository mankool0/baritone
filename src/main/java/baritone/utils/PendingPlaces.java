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

import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Main hand uses on blocks the server hasn't acked yet, per hotbar slot. One per client level,
 * since the prediction sequences are.
 * <p>
 * The client takes a placed block off its own stack, but every inventory update the server sends
 * replaces the stack with the server's count, which is a round trip stale. So while places are in
 * flight the client's count runs high by about one per tick of round trip, and once the server has
 * run the slot dry the client still shows a few. Places sent on those go out with whatever the
 * server holds in the slot by then, and a pickup lands in the first empty slot, hotbar first.
 * <p>
 * The server sends the update for a place in the same tick as its ack and just ahead of it, so
 * once a place is acked the stack shows it. Until then, the stack's count plus the client's own
 * decrements on it, minus the places in flight, is what the server will have left.
 * <p>
 * A use dropped on its way to the server, by an anticheat say, is never acked by itself, only by
 * the ack of some later action. Waiting on it would hang a bot that has nothing else to send, so
 * one unacked for well over any round trip counts as lost.
 */
public final class PendingPlaces {

    private static final long LOST_AFTER_NANOS = TimeUnit.SECONDS.toNanos(2);

    private record Use(int sequence, int slot, long sentNanos) {}

    private final LongSupplier clock;
    private final ArrayDeque<Use> unacked = new ArrayDeque<>();
    private final int[] inFlight = new int[9];
    // the stack the client took its own decrements off; any update from the server replaces it
    private final ItemStack[] decremented = new ItemStack[9];
    private final int[] decrements = new int[9];

    public PendingPlaces() {
        this(System::nanoTime);
    }

    PendingPlaces(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Called on the main thread after a use on a block went out from this hotbar slot, whether or
     * not the client predicted it doing anything: the server may still place it.
     *
     * @param stack       the stack in the slot when the use was predicted
     * @param countBefore its count before the prediction
     */
    public void onUse(int slot, int sequence, ItemStack stack, int countBefore) {
        if (slot < 0 || slot >= inFlight.length) {
            return;
        }
        unacked.add(new Use(sequence, slot, clock.getAsLong()));
        inFlight[slot]++;
        if (decremented[slot] != stack) {
            decremented[slot] = stack;
            decrements[slot] = 0;
        }
        decrements[slot] += Math.max(0, countBefore - stack.getCount());
    }

    /** Called on the main thread with each ack, after the inventory updates sent ahead of it */
    public void onAck(int sequence) {
        while (!unacked.isEmpty() && unacked.peekFirst().sequence() <= sequence) {
            inFlight[unacked.removeFirst().slot()]--;
        }
    }

    private void dropLost() {
        long now = clock.getAsLong();
        while (!unacked.isEmpty() && now - unacked.peekFirst().sentNanos() > LOST_AFTER_NANOS) {
            inFlight[unacked.removeFirst().slot()]--;
        }
    }

    /**
     * @return uses from this hotbar slot the server hasn't acked yet
     */
    public int inFlight(int slot) {
        dropLost();
        return slot >= 0 && slot < inFlight.length ? inFlight[slot] : 0;
    }

    /**
     * @param stack what the client shows in the slot
     * @return how many of it the server holds once every use in flight from the slot has landed,
     * or fewer if some of them don't consume anything
     */
    public int countAfterInFlight(int slot, ItemStack stack) {
        if (slot < 0 || slot >= inFlight.length) {
            return stack.getCount();
        }
        dropLost();
        int standing = decremented[slot] == stack ? decrements[slot] : 0;
        return stack.getCount() + standing - inFlight[slot];
    }
}
