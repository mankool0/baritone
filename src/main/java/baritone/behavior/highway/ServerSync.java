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

package baritone.behavior.highway;

import baritone.api.utils.Helper;
import baritone.behavior.highway.enums.HighwayState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * Whether what the client shows is what the server has: container and inventory syncs, our
 * right-clicks still waiting on the server, and inventory thresholds confirmed before acting on them.
 */
public class ServerSync extends ContextComponent {
    /** containerId of the last full content sync the server sent. Written from the netty thread. */
    private volatile int syncedContainerId = NO_CONTAINER;
    /** Whether the server has sent our own inventory since we last joined. */
    private volatile boolean inventorySynced = true;
    private int ticksSinceInventoryDesync = 0;
    /** Ticks the currently open (non-inventory) container menu has been open for. */
    private int containerOpenTicks = 0;
    private int openContainerId = NO_CONTAINER;
    /** Main-thread snapshot of {@link #syncedContainerId} taken at the start of the previous tick. */
    private int syncSeenLastTick = NO_CONTAINER;
    /** Whether the open container's slots have definitely been filled in on the main thread. */
    private boolean containerContentsApplied = false;

    private long lastContainerClickTick = Long.MIN_VALUE / 4;

    private static final int NO_CONTAINER = Integer.MIN_VALUE;

    private BlockPos lastUseItemOnPos = null;
    private long lastUseItemOnTick = Long.MIN_VALUE / 4;

    private HighwayState pendingThresholdState = null;
    private String pendingThresholdKey = null;
    private long pendingThresholdTick = 0;

    /**
     * Paving lowers the obsidian count by a few blocks a tick: this tick's places, plus a lagging slot
     * echo catching up on the ones still in flight. A bigger one-tick drop is a stack the count lost
     * for a moment, like the last stack being swapped onto the hotbar.
     */
    private static final int MAX_OBSIDIAN_SPENT_PER_TICK = 16;
    private int lastObsidianCount = -1;
    private boolean obsidianDroppedSuddenly = false;

    ServerSync(HighwayContext ctx) {
        super(ctx);
    }

    @Override
    void onBuildStart() {
        resetObsidianDrop();
    }

    @Override
    void onTeardown() {
        clearThresholdConfirm();
    }

    /**
     * Latch a full container content sync. Called off the netty thread from the packet handler, so
     * the field is volatile and nothing here touches the world or the player.
     */
    public void noteContainerSync(int containerId) {
        syncedContainerId = containerId;
        if (containerId == 0) {
            inventorySynced = true;
        }
    }

    /** A fresh join/respawn invalidates the inventory we were reading counts off. */
    public void noteInventoryDesynced() {
        inventorySynced = false;
        syncedContainerId = NO_CONTAINER;
        ticksSinceInventoryDesync = 0;
    }

    /**
     * Whether inventory counts can be trusted. Straight after a login or a dimension change the
     * client's copy is empty, and acting on it would send us on a storage trip for items we are
     * actually carrying. Assumed true at startBuild (the player is already in-world by then) and
     * only cleared by a login/respawn packet.
     */
    public boolean inventorySynced() {
        if (inventorySynced) {
            return true;
        }
        // Fallback so an unusual join sequence that never resends the inventory can't wedge every
        // threshold forever. onTick already refuses to run while the inventory is fully empty.
        return ticksSinceInventoryDesync >= settings.highwayContainerSyncTimeout.value
                && !playerContext.player().getInventory().isEmpty();
    }

    /**
     * Whether the contents of the open container can be trusted - i.e. the server has sent the
     * full slot list for this exact container id, so an empty slot really is empty rather than
     * not-yet-delivered. This is the check that keeps a slow/lagging sync from being read as "this
     * shulker is empty"; the timeout below is only a wedge guard, not the normal path.
     */
    public boolean openContainerReady() {
        AbstractContainerMenu menu = playerContext.player().containerMenu;
        if (menu == playerContext.player().inventoryMenu) {
            return false;
        }
        return containerContentsApplied || containerOpenTicks >= settings.highwayContainerSyncTimeout.value;
    }

    /**
     * Container clicks are spaced out rather than fired every tick: the loot/deposit helpers move a
     * slot per call and the server (and anticheat) sees a click packet for each one.
     */
    public boolean containerClickReady() {
        return ctx.contextTick() - lastContainerClickTick >= settings.highwayContainerClickInterval.value;
    }

    public void noteContainerClick() {
        lastContainerClickTick = ctx.contextTick();
    }

    public BlockPos lastUseItemOnPos() {
        return lastUseItemOnPos;
    }

    public long lastUseItemOnTick() {
        return lastUseItemOnTick;
    }

    /** Latch a right-click we sent on a block. Called from the send hook, on the main thread. */
    public void noteUseItemOn(BlockPos pos) {
        lastUseItemOnPos = pos.immutable();
        lastUseItemOnTick = ctx.contextTick();
    }

    /**
     * Whether a right-click on {@code pos} is still waiting on the server. The server opens a
     * container once per click, so a second click sent before the first one's screen arrives opens
     * it again: our close for the first then shuts the second server-side only, and its screen
     * stays up with nothing left to ever close it.
     */
    public boolean openClickPending(BlockPos pos) {
        return lastUseItemOnPos != null && lastUseItemOnPos.equals(pos)
                && ctx.contextTick() - lastUseItemOnTick < settings.highwayContainerOpenRetryTicks.value;
    }

    void tickContainerSync() {
        if (!inventorySynced) {
            ticksSinceInventoryDesync++;
        }
        AbstractContainerMenu menu = playerContext.player().containerMenu;
        int latchedNow = syncedContainerId;
        if (menu == playerContext.player().inventoryMenu) {
            containerOpenTicks = 0;
            openContainerId = NO_CONTAINER;
            containerContentsApplied = false;
            syncSeenLastTick = latchedNow;
            return;
        }
        if (menu.containerId != openContainerId) {
            openContainerId = menu.containerId;
            containerOpenTicks = 0;
            containerContentsApplied = false;
            syncSeenLastTick = latchedNow;
            return;
        }
        containerOpenTicks++;
        // The packet handler latches from the netty thread, but the slots themselves are filled in
        // on the main thread when Minecraft drains its task queue at the top of a tick. Only accept
        // a latch that was already set when the previous tick began, so the fill can never still be
        // pending when a state reads the slots - otherwise a sync arriving mid-tick would make a
        // full container look empty, which is exactly what this check exists to prevent.
        if (syncSeenLastTick == menu.containerId) {
            containerContentsApplied = true;
        }
        syncSeenLastTick = latchedNow;
    }

    /**
     * Double-check a tripped inventory threshold before committing to something expensive (a storage
     * trip, or pausing the build). A count read off an inventory the server hasn't sent us is zero,
     * which used to be covered by standing still for 120 ticks before every such decision; the
     * inventory sync latch covers that case directly now, and the short confirm window only rides
     * out a count that flickers while items are still being moved around.
     *
     * <p>Returns false (and keeps returning false) until the same threshold has stayed tripped for
     * {@code highwayThresholdConfirmTicks}. Unlike the old gate this does not stop the builder, the
     * travel walk or the correctness scans while it waits.
     */
    public boolean thresholdConfirmed(String what) {
        if (!inventorySynced) {
            return false;
        }
        long now = ctx.contextTick();
        if (pendingThresholdState != ctx.currentState().getState() || !what.equals(pendingThresholdKey)) {
            pendingThresholdState = ctx.currentState().getState();
            pendingThresholdKey = what;
            pendingThresholdTick = now;
            Helper.HELPER.logDebug(what + " under threshold, confirming over " + settings.highwayThresholdConfirmTicks.value + " ticks.");
            return false;
        }
        if (now - pendingThresholdTick < settings.highwayThresholdConfirmTicks.value) {
            return false;
        }
        clearThresholdConfirm();
        return true;
    }

    public void clearThresholdConfirm() {
        pendingThresholdState = null;
        pendingThresholdKey = null;
    }

    /**
     * Drop a pending confirm for {@code what} once its threshold is no longer tripped, so a count
     * that dipped for a tick and recovered doesn't leave a head start for the next dip.
     */
    public void clearThresholdConfirm(String what) {
        if (what.equals(pendingThresholdKey)) {
            clearThresholdConfirm();
        }
    }

    void tickObsidianCount() {
        int count = ctx.inventory().getObsidianCountInventory();
        if (lastObsidianCount - count > MAX_OBSIDIAN_SPENT_PER_TICK && count <= settings.highwayObsidianThreshold.value) {
            obsidianDroppedSuddenly = true;
        }
        lastObsidianCount = count;
    }

    /**
     * Whether the obsidian count jumped under the threshold rather than being spent down to it. Only
     * a jump needs the threshold confirm: the builder keeps paving while a confirm runs, and that
     * spends everything under the threshold before the confirm is over.
     */
    public boolean obsidianDroppedSuddenly() {
        return obsidianDroppedSuddenly;
    }

    /** The obsidian threshold is no longer tripped, so whatever jump it was confirming has recovered */
    public void clearObsidianDrop() {
        obsidianDroppedSuddenly = false;
    }

    private void resetObsidianDrop() {
        lastObsidianCount = -1;
        obsidianDroppedSuddenly = false;
    }
}
