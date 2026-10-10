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

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * What the builder may throw out ahead of its throwaway blocks when it needs room. Only items the
 * user named as junk (or scrap gear) qualify, never "whatever we don't recognise": a builder run
 * without a controller that knows the whole inventory can't tell a user's fireworks from rubbish.
 * Ids are read off the registry rather than item tags so this works without loaded tags.
 */
public final class HighwayJunk {

    /** Scrap kinds, by item id suffix: armor plus the tool classes that drop off nether mobs. */
    private static final Set<String> SCRAP_KINDS = Set.of("helmet", "chestplate", "leggings", "boots", "sword", "axe", "shovel");

    /** The scrap kinds keepToolsOnHotbar ranks a best of; pickaxes never get here. */
    private static final Set<String> KEPT_TOOL_KINDS = Set.of("sword", "axe", "shovel");

    private static final Set<Item> NEVER_JUNK = Set.of(
            Blocks.ENDER_CHEST.asItem(),
            Blocks.OBSIDIAN.asItem(),
            Blocks.CRYING_OBSIDIAN.asItem(),
            Items.ENCHANTED_GOLDEN_APPLE,
            Items.TOTEM_OF_UNDYING,
            Items.ENDER_PEARL,
            Items.ELYTRA
    );

    private HighwayJunk() {}

    private static String idPath(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }

    /** Armor, a sword, an axe or a shovel made of one of the materials ("golden" for golden_sword). */
    public static boolean isScrap(Item item, List<String> materials) {
        String path = idPath(item);
        for (String material : materials) {
            if (path.startsWith(material + "_") && SCRAP_KINDS.contains(path.substring(material.length() + 1))) {
                return true;
            }
        }
        return false;
    }

    /**
     * What the builder runs on or the user plainly owns: no junk list can make these junk, so a
     * user who lists obsidian can't break paving. Worn armor and the offhand sit outside slots
     * 0-35, which is all any room-making picker reads.
     */
    public static boolean neverJunk(ItemStack stack) {
        Item item = stack.getItem();
        return stack.has(DataComponents.CUSTOM_NAME)
                || NEVER_JUNK.contains(item)
                || Block.byItem(item) instanceof ShulkerBoxBlock
                || idPath(item).endsWith("pickaxe");
    }

    /** On the junk list or scrap, and not something the builder needs. */
    public static boolean isJunk(ItemStack stack, List<Item> junkItems, List<String> scrapMaterials) {
        if (stack.isEmpty() || neverJunk(stack)) {
            return false;
        }
        return junkItems.contains(stack.getItem()) || isScrap(stack.getItem(), scrapMaterials);
    }

    /**
     * Junk the builder may throw from this inventory slot. Slot 8 is the throwaway slot, and the
     * class-best sword, axe or shovel is the user's tool whatever it's made of; {@code keptTool}
     * is only asked about stacks that could be one.
     */
    public static boolean isJunkSlot(int slot, ItemStack stack, IntPredicate keptTool, List<Item> junkItems, List<String> scrapMaterials) {
        if (slot == 8 || !isJunk(stack, junkItems, scrapMaterials)) {
            return false;
        }
        String path = idPath(stack.getItem());
        boolean couldBeKept = KEPT_TOOL_KINDS.stream().anyMatch(kind -> path.endsWith("_" + kind));
        return !(couldBeKept && keptTool.test(slot));
    }
}
