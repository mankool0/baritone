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

import baritone.behavior.highway.enums.ShulkerType;
import net.minecraft.core.BlockPos;

import java.util.EnumSet;

/**
 * Bookkeeping for storage trips: what is being refilled, what storage has run out of, and the
 * ender chest a trip goes through.
 */
public class Resupply extends ContextComponent {
    // How long a carried supply shulker waits for another stash trip after one found nowhere to set the chest down
    private static final int STASH_RETRY_TICKS = 1200;

    private int picksToHave = 5;
    private boolean enderChestHasPickShulks = true;
    private boolean enderChestHasEnderShulks = true;
    private boolean refillingEnderChests = false;
    // Supplies a storage trip is refilling, and those storage ran out of shulkers of (until the next startBuild)
    private final EnumSet<Supply> refillingSupplies = EnumSet.noneOf(Supply.class);
    private final EnumSet<Supply> storageOutOfSupplies = EnumSet.noneOf(Supply.class);
    // The supply the Supply states are topping up
    private Supply activeSupply = Supply.GAPPLES;
    private boolean stashingShulker = false;
    // The ender chest had no free slot for a stash (until the next startBuild)
    private boolean stashStorageFull = false;
    private long stashRetryTick = 0;
    private BlockPos enderChestAccessLoc = null;
    private ShulkerType picksToUse;

    private int startShulkerCount = 0;

    Resupply(HighwayContext ctx) {
        super(ctx);
    }

    @Override
    void onBuildStart() {
        if (!layout().paving()) {
            // Only digging so any pickaxe works
            picksToUse = ShulkerType.AnyPickaxe;
            picksToHave = settings.highwayPicksToHaveDigging.value;
        } else {
            // If paving then we mine echests and need non-silk picks
            picksToUse = ShulkerType.NonSilkPickaxe;
            picksToHave = settings.highwayPicksToHavePaving.value;
        }
        enderChestHasPickShulks = true;
        enderChestHasEnderShulks = true;
        refillingEnderChests = false;
        resetSupplies();
        stashingShulker = false;
        stashStorageFull = false;
        stashRetryTick = 0;
        enderChestAccessLoc = null;
        startShulkerCount = ctx.inventory().getShulkerCountInventory(ShulkerType.Any);
    }

    public ShulkerType picksToUse() {
        return picksToUse;
    }

    public int picksToHave() {
        return picksToHave;
    }

    public int totemsToHave() {
        return Math.max(settings.highwayTotemsToHave.value, settings.highwayTotemsThreshold.value + 1);
    }

    public int goldenCarrotsToHave() {
        return Math.max(settings.highwayGoldenCarrotsToHave.value, settings.highwayGoldenCarrotsThreshold.value + 1);
    }

    public boolean enderChestHasPickShulks() {
        return enderChestHasPickShulks;
    }

    public void setEnderChestHasPickShulks(boolean enderChestHasPickShulks) {
        this.enderChestHasPickShulks = enderChestHasPickShulks;
    }

    public boolean enderChestHasEnderShulks() {
        return enderChestHasEnderShulks;
    }

    public void setEnderChestHasEnderShulks(boolean enderChestHasEnderShulks) {
        this.enderChestHasEnderShulks = enderChestHasEnderShulks;
    }

    public boolean enderChestHasShulks(Supply supply) {
        return !storageOutOfSupplies.contains(supply);
    }

    public void setEnderChestHasShulks(Supply supply, boolean has) {
        if (has) {
            storageOutOfSupplies.remove(supply);
        } else {
            storageOutOfSupplies.add(supply);
        }
    }

    public boolean refillingEnderChests() {
        return refillingEnderChests;
    }

    public void setRefillingEnderChests(boolean refillingEnderChests) {
        this.refillingEnderChests = refillingEnderChests;
    }

    public boolean refilling(Supply supply) {
        return refillingSupplies.contains(supply);
    }

    public void setRefilling(Supply supply, boolean refilling) {
        if (refilling) {
            refillingSupplies.add(supply);
        } else {
            refillingSupplies.remove(supply);
        }
    }

    /** The refill leg in progress: legs run one at a time, in priority order. Null when none is. */
    public Supply firstRefillingSupply() {
        return refillingSupplies.isEmpty() ? null : refillingSupplies.iterator().next();
    }

    /** For a new build: nothing being refilled, and storage assumed to hold every supply again. */
    private void resetSupplies() {
        refillingSupplies.clear();
        storageOutOfSupplies.clear();
    }

    public Supply activeSupply() {
        return activeSupply;
    }

    public void setActiveSupply(Supply supply) {
        this.activeSupply = supply;
    }

    public boolean stashingShulker() {
        return stashingShulker;
    }

    public void setStashingShulker(boolean stashingShulker) {
        this.stashingShulker = stashingShulker;
    }

    /**
     * Whether a supply shulker we carry may go back into storage on a trip of its own: not once the
     * ender chest had no free slot for one (only a restock between builds makes room), and not for
     * a while after a stash found nowhere to set the chest down.
     */
    public boolean canStashCarriedShulker() {
        return !stashStorageFull && ctx.contextTick() >= stashRetryTick;
    }

    public void noteStashStorageFull() {
        stashStorageFull = true;
    }

    /** Give up on the stash leg in progress: the shulker rides along until a later stash trip. */
    public void abandonStash() {
        stashingShulker = false;
        refillingEnderChests = false;
        refillingSupplies.clear();
        releaseEnderChestAccessLoc();
        stashRetryTick = ctx.contextTick() + STASH_RETRY_TICKS;
    }

    public BlockPos enderChestAccessLoc() {
        return enderChestAccessLoc;
    }

    /**
     * Forget the remembered storage access chest, but only once no refill leg is still latched:
     * trips are chained (an ender chest grab can queue supply top-ups behind it), and
     * whichever leg finishes first would otherwise strand the later ones into placing a fresh chest
     * a few blocks from the perfectly good one this trip already put down.
     */
    public void releaseEnderChestAccessLoc() {
        if (!refillingEnderChests && refillingSupplies.isEmpty()) {
            enderChestAccessLoc = null;
        }
    }

    /** A chest we gave up on can't stay the one a later refill or stash leg walks back to reuse. */
    void forgetEnderChestAccessLoc(BlockPos spot) {
        if (spot.equals(enderChestAccessLoc)) {
            enderChestAccessLoc = null;
        }
    }

    public void setEnderChestAccessLoc(BlockPos enderChestAccessLoc) {
        this.enderChestAccessLoc = enderChestAccessLoc == null
                ? null
                : new BlockPos(enderChestAccessLoc.getX(), enderChestAccessLoc.getY(), enderChestAccessLoc.getZ());
    }

    public int startShulkerCount() {
        return startShulkerCount;
    }

    public void setStartShulkerCount(int startShulkerCount) {
        this.startShulkerCount = startShulkerCount;
    }
}
