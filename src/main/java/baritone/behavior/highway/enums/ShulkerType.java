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

package baritone.behavior.highway.enums;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public enum ShulkerType {
    EnderChest,
    NonSilkPickaxe, // For paving
    //SilkPickaxe,
    AnyPickaxe, // For digging
    DepletedPickaxe,
    // Boxes of nothing but one item, the Supply ones
    Gapple(Items.ENCHANTED_GOLDEN_APPLE),
    Totem(Items.TOTEM_OF_UNDYING),
    GoldenCarrot(Items.GOLDEN_CARROT),
    Empty,
    Any;

    private final Item item;

    ShulkerType() {
        this(null);
    }

    ShulkerType(Item item) {
        this.item = item;
    }

    /** The one item a box of this type holds, or null for a type with a test of its own. */
    public Item item() {
        return item;
    }
}
