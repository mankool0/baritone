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

import baritone.api.Settings;
import baritone.behavior.highway.enums.ShulkerType;
import net.minecraft.world.item.Item;

/**
 * What the builder keeps topped up out of shulker boxes through the Supply states: place the box,
 * open it, loot it, mine it and pick it back up. With no box carried, one is grabbed from ender
 * storage first, and in stash mode put back there once the top-up is done. Picks and ender chests
 * have cycles of their own: picks come in several kinds of shulker and swap depleted picks back
 * in, and ender chests feed the obsidian farm.
 * <p>
 * Declaration order is priority. BuildingHighway checks supplies in this order, a storage trip
 * grabs their shulkers in this order, and refill legs run one at a time, so the first supply
 * still refilling is the leg in progress.
 */
public enum Supply {
    GAPPLES(ShulkerType.Gapple, "gapple", "gapples", "Gapple count") {
        @Override
        public boolean enabled(Settings settings) {
            return true; // every highway bot carries gapples
        }

        @Override
        public int toHave(HighwayContext context) {
            return context.settings().highwayGapplesToHave.value;
        }

        @Override
        public int threshold(Settings settings) {
            return settings.highwayGapplesThreshold.value;
        }

        @Override
        public boolean stash(Settings settings) {
            return settings.highwayStashGappleShulkers.value;
        }

        @Override
        public int shulksToHave(Settings settings) {
            return settings.highwayGappleShulksToHave.value;
        }

        @Override
        public boolean pausesWhenStorageEmpty(Settings settings) {
            return true; // gapples are what keeps us alive in lava
        }
    },

    TOTEMS(ShulkerType.Totem, "totem", "totems", "Totem count") {
        @Override
        public boolean enabled(Settings settings) {
            return settings.highwayRefillTotems.value;
        }

        @Override
        public int count(HighwayContext context) {
            return context.getTotemCountInventory(); // the offhand one is the one that saves us
        }

        @Override
        public int toHave(HighwayContext context) {
            return context.totemsToHave();
        }

        @Override
        public int threshold(Settings settings) {
            return settings.highwayTotemsThreshold.value;
        }

        @Override
        public boolean stash(Settings settings) {
            return settings.highwayStashTotemShulkers.value;
        }

        @Override
        public int shulksToHave(Settings settings) {
            return settings.highwayTotemShulksToHave.value;
        }

        @Override
        public boolean pausesWhenStorageEmpty(Settings settings) {
            // Totems are a safety net, not something the build consumes
            return settings.highwayPauseWhenOutOfTotems.value;
        }
    },

    GOLDEN_CARROTS(ShulkerType.GoldenCarrot, "golden carrot", "golden carrots", "Golden carrot count") {
        @Override
        public boolean enabled(Settings settings) {
            return settings.highwayRefillGoldenCarrots.value;
        }

        @Override
        public int toHave(HighwayContext context) {
            return context.goldenCarrotsToHave();
        }

        @Override
        public int threshold(Settings settings) {
            return settings.highwayGoldenCarrotsThreshold.value;
        }

        @Override
        public boolean stash(Settings settings) {
            return settings.highwayStashGoldenCarrotShulkers.value;
        }

        @Override
        public int shulksToHave(Settings settings) {
            return settings.highwayGoldenCarrotShulksToHave.value;
        }

        @Override
        public boolean pausesWhenStorageEmpty(Settings settings) {
            return false; // gapples take hunger back once the carrots run out
        }
    };

    private final ShulkerType shulkerType;
    private final String noun;
    private final String plural;
    private final String countKey;

    Supply(ShulkerType shulkerType, String noun, String plural, String countKey) {
        this.shulkerType = shulkerType;
        this.noun = noun;
        this.plural = plural;
        this.countKey = countKey;
    }

    public ShulkerType shulkerType() {
        return shulkerType;
    }

    /** The one item its boxes hold. */
    public Item item() {
        return shulkerType.item();
    }

    /** "gapple": hive's restock reads "Looted 1 gapple shulker" and the like off the log. */
    public String noun() {
        return noun;
    }

    /** "gapples", as in "Looted 64 gapples". */
    public String plural() {
        return plural;
    }

    /** The key a low count is confirmed under before it costs a storage trip or a pause. */
    public String countKey() {
        return countKey;
    }

    /** Whether we keep this supply topped up at all. */
    public abstract boolean enabled(Settings settings);

    /** How many we carry. */
    public int count(HighwayContext context) {
        return context.getItemCountInventory(Item.getId(item()));
    }

    /** How many to loot up to. */
    public abstract int toHave(HighwayContext context);

    /** At or below this many we go get more. */
    public abstract int threshold(Settings settings);

    /** Keep this supply's shulkers in ender storage rather than carrying them. */
    public abstract boolean stash(Settings settings);

    /** Shulkers to carry when not stashing. */
    public abstract int shulksToHave(Settings settings);

    /** Pause when we run low and storage has no shulker of it left, rather than carry on. */
    public abstract boolean pausesWhenStorageEmpty(Settings settings);

    /** With one of its shulkers open, move one slot's worth into the inventory; how many were in it, 0 when none. */
    public int lootSlot(HighwayContext context) {
        return context.lootSupplyChestSlot(item());
    }

    /** Shulkers of it to come back from a storage trip with. */
    public int shulksWanted(HighwayContext context) {
        int want = enabled(context.settings()) && !stash(context.settings()) ? shulksToHave(context.settings()) : 0;
        if (context.refilling(this)) {
            want = Math.max(want, 1); // this trip has to come back with a shulker to loot from
        }
        return want;
    }
}
