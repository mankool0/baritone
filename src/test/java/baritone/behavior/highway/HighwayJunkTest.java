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

import baritone.api.TestSettings;
import baritone.test.HeadlessGame;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HighwayJunkTest {

    private static List<Item> junkItems;
    private static List<String> scrapMaterials;
    private static List<Item> throwaway;

    @BeforeClass
    public static void bootstrap() {
        HeadlessGame.bootstrap();
        junkItems = TestSettings.fresh().highwayJunkItems.value;
        scrapMaterials = TestSettings.fresh().highwayScrapMaterials.value;
        throwaway = TestSettings.fresh().acceptableThrowawayItems.value;
    }

    private static List<ItemStack> inventory() {
        List<ItemStack> inventory = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            inventory.add(ItemStack.EMPTY);
        }
        return inventory;
    }

    private static IntPredicate junkSlots(List<ItemStack> inventory, IntPredicate keptTool) {
        return slot -> HighwayJunk.isJunkSlot(slot, inventory.get(slot), keptTool, junkItems, scrapMaterials);
    }

    private static IntPredicate junkSlots(List<ItemStack> inventory) {
        return junkSlots(inventory, slot -> false);
    }

    private static ItemStack named(Item item) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("mine"));
        return stack;
    }

    @Test
    public void scrapIsAMaterialPlusAKind() {
        for (Item item : List.of(Items.GOLDEN_SWORD, Items.IRON_AXE, Items.STONE_SHOVEL, Items.WOODEN_SWORD,
                Items.CHAINMAIL_HELMET, Items.LEATHER_BOOTS, Items.GOLDEN_CHESTPLATE, Items.IRON_LEGGINGS, Items.COPPER_SWORD)) {
            assertTrue(item.toString(), HighwayJunk.isScrap(item, scrapMaterials));
        }
        for (Item item : List.of(Items.DIAMOND_SWORD, Items.NETHERITE_AXE, Items.IRON_PICKAXE, Items.IRON_INGOT,
                Items.LEATHER, Items.LEATHER_HORSE_ARMOR, Items.GOLDEN_APPLE, Items.STONE)) {
            assertFalse(item.toString(), HighwayJunk.isScrap(item, scrapMaterials));
        }
    }

    @Test
    public void scrapMaterialsComeFromTheSetting() {
        assertFalse(HighwayJunk.isScrap(Items.GOLDEN_SWORD, List.of("iron")));
        assertTrue(HighwayJunk.isScrap(Items.IRON_SWORD, List.of("iron")));
        assertTrue(HighwayJunk.isScrap(Items.DIAMOND_SWORD, List.of("diamond")));
    }

    @Test
    public void whatTheBuilderNeedsBeatsAHostileJunkList() {
        List<Item> hostile = List.of(Items.OBSIDIAN, Items.CRYING_OBSIDIAN, Items.ENDER_CHEST, Items.SHULKER_BOX,
                Items.RED_SHULKER_BOX, Items.ENCHANTED_GOLDEN_APPLE, Items.TOTEM_OF_UNDYING, Items.ENDER_PEARL,
                Items.ELYTRA, Items.IRON_PICKAXE, Items.NETHERITE_PICKAXE, Items.GOLDEN_PICKAXE);
        for (Item item : hostile) {
            assertFalse(item.toString(), HighwayJunk.isJunk(new ItemStack(item), hostile, List.of("golden", "iron", "netherite")));
        }
    }

    @Test
    public void aCustomNameKeepsAnything() {
        assertTrue(HighwayJunk.isJunk(new ItemStack(Items.GOLD_NUGGET), junkItems, scrapMaterials));
        assertFalse(HighwayJunk.isJunk(named(Items.GOLD_NUGGET), junkItems, scrapMaterials));
        assertFalse(HighwayJunk.isJunk(named(Items.GOLDEN_SWORD), junkItems, scrapMaterials));
    }

    @Test
    public void unlistedItemsAreKept() {
        for (Item item : List.of(Items.FIREWORK_ROCKET, Items.POTION, Items.RED_BED, Items.ANCIENT_DEBRIS,
                Items.NETHERITE_SCRAP, Items.WITHER_SKELETON_SKULL, Items.GOLD_INGOT, Items.BLAZE_ROD, Items.GHAST_TEAR,
                Items.NETHERRACK, Items.DIAMOND_SWORD)) {
            assertFalse(item.toString(), HighwayJunk.isJunk(new ItemStack(item), junkItems, scrapMaterials));
        }
    }

    @Test
    public void theKeptToolIsNotScrap() {
        List<ItemStack> inventory = inventory();
        inventory.set(2, new ItemStack(Items.IRON_SHOVEL));
        inventory.set(12, new ItemStack(Items.IRON_SHOVEL));
        IntPredicate junk = junkSlots(inventory, slot -> slot == 2);
        assertFalse(junk.test(2));
        assertTrue(junk.test(12));
    }

    @Test
    public void onlyToolsAskTheKeptToolRanking() {
        List<ItemStack> inventory = inventory();
        inventory.set(4, new ItemStack(Items.GOLD_NUGGET));
        inventory.set(5, new ItemStack(Items.GOLDEN_HELMET));
        IntPredicate junk = junkSlots(inventory, slot -> {
            fail("asked about slot " + slot);
            return true;
        });
        assertTrue(junk.test(4));
        assertTrue(junk.test(5));
    }

    @Test
    public void slot8IsNeverJunk() {
        List<ItemStack> inventory = inventory();
        inventory.set(8, new ItemStack(Items.ROTTEN_FLESH));
        assertFalse(junkSlots(inventory).test(8));
        assertEquals(-1, HighwayContext.throwawaySlotToToss(inventory, junkSlots(inventory), throwaway, true));
    }

    @Test
    public void junkGoesBeforeNetherrack() {
        List<ItemStack> inventory = inventory();
        inventory.set(0, new ItemStack(Items.NETHERRACK, 64));
        inventory.set(20, new ItemStack(Items.QUARTZ, 3));
        assertEquals(20, HighwayContext.throwawaySlotToToss(inventory, junkSlots(inventory), throwaway, true));
        assertEquals(20, HighwayContext.roomSlotToToss(inventory, junkSlots(inventory), throwaway, true));
    }

    @Test
    public void hotbarJunkGoesFirst() {
        List<ItemStack> inventory = inventory();
        inventory.set(20, new ItemStack(Items.GOLD_NUGGET));
        inventory.set(3, new ItemStack(Items.ROTTEN_FLESH));
        assertEquals(3, HighwayContext.throwawaySlotToToss(inventory, junkSlots(inventory), throwaway, true));
    }

    @Test
    public void withoutJunkTheOldOrderHolds() {
        List<ItemStack> inventory = inventory();
        inventory.set(1, new ItemStack(Items.OBSIDIAN, 64));
        inventory.set(2, new ItemStack(Items.OBSIDIAN, 10));
        inventory.set(20, new ItemStack(Items.GOLD_NUGGET));
        inventory.set(30, new ItemStack(Items.NETHERRACK, 64));
        IntPredicate off = slot -> false;
        assertEquals(30, HighwayContext.throwawaySlotToToss(inventory, off, throwaway, true));
        assertEquals(30, HighwayContext.roomSlotToToss(inventory, off, throwaway, true));
        inventory.set(30, ItemStack.EMPTY);
        // paving never throws obsidian as a throwaway, but a shulker still gets the smallest stack
        assertEquals(-1, HighwayContext.throwawaySlotToToss(inventory, off, throwaway, true));
        assertEquals(2, HighwayContext.roomSlotToToss(inventory, off, throwaway, true));
    }

    @Test
    public void obsidianRoomCountsExactlyWhatThePickersThrow() {
        List<ItemStack> inventory = inventory();
        inventory.set(0, new ItemStack(Items.NETHERITE_SWORD));
        inventory.set(2, new ItemStack(Items.IRON_SHOVEL)); // kept
        inventory.set(3, new ItemStack(Items.GOLDEN_SWORD));
        inventory.set(4, new ItemStack(Items.GOLD_NUGGET, 7));
        inventory.set(5, named(Items.GOLD_NUGGET));
        inventory.set(8, new ItemStack(Items.ROTTEN_FLESH)); // slot 8
        inventory.set(9, new ItemStack(Items.NETHERRACK, 64));
        inventory.set(10, new ItemStack(Items.CRYING_OBSIDIAN, 5));
        inventory.set(11, new ItemStack(Items.OBSIDIAN, 60));
        inventory.set(12, new ItemStack(Items.ENDER_CHEST, 64));
        inventory.set(13, new ItemStack(Items.SHULKER_BOX));
        inventory.set(14, new ItemStack(Items.FIREWORK_ROCKET, 64));
        inventory.set(15, new ItemStack(Items.BOW));
        int empty = 36 - 13;
        IntPredicate keptTool = slot -> slot == 2;
        IntPredicate junk = junkSlots(inventory, keptTool);

        int room = HighwayContext.obsidianRoom(inventory, junk, throwaway, 0);

        int thrown = 0;
        for (int slot; (slot = HighwayContext.throwawaySlotToToss(inventory, junk, throwaway, true)) != -1; ) {
            inventory.set(slot, ItemStack.EMPTY);
            thrown++;
        }
        // golden sword, the nuggets, the bow, the netherrack
        assertEquals(4, thrown);
        assertEquals(4 + 64 * (empty + thrown), room);
    }

    @Test
    public void listedJunkOnlyWithoutScrapMaterials() {
        // the cursor check: no kept-tool ranking reaches the cursor, so scrap there is kept
        assertFalse(HighwayJunk.isJunk(new ItemStack(Items.IRON_SHOVEL), junkItems, List.of()));
        assertTrue(HighwayJunk.isJunk(new ItemStack(Items.BOW), junkItems, List.of()));
    }
}
