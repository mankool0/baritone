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
import baritone.api.utils.Rotation;
import baritone.behavior.highway.enums.HighwayState;
import baritone.behavior.highway.enums.ShulkerType;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.storage.TagValueInput;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * What we carry: finding, counting and hotbarring items, telling shulker boxes apart by their
 * contents, the offhand and the cursor, and which slots are junk to throw out for room.
 */
public class HighwayInventory extends ContextComponent {
    private final ArrayList<Integer> usefulSlots = new ArrayList<>(Arrays.asList(0, 1, 2, 7)); // Don't try to put into these slots

    private int preMineShulkerCount = -1; // inventory shulker count snapshotted before a refill box is mined

    private boolean cursorStackNonEmpty = false;

    // States where the offhand is deliberately occupied (echest farming keeps an ender chest there
    // between PrepEchest and SwapBack) - a totem swap would fight the state's own offhand swaps and
    // loop forever. Every state NOT listed here keeps a totem equipped, so new states default to protected.
    private static final EnumSet<HighwayState> OFFHAND_OCCUPIED_STATES = EnumSet.of(
            HighwayState.FarmingEnderChestPrepEchest,
            HighwayState.FarmingEnderChestPrepPick,
            HighwayState.FarmingEnderChest,
            HighwayState.FarmingEnderChestSwapBack,
            HighwayState.InQueue
    );

    // States that open, or work inside, one of our own containers. Every state NOT listed here
    // closes any container it finds open, so a new state that opens one has to be added. InQueue is
    // here because queue servers can open menus of their own.
    private static final EnumSet<HighwayState> CONTAINER_STATES = EnumSet.of(
            HighwayState.OpeningPickaxeShulker,
            HighwayState.LootingPickaxeShulker,
            HighwayState.OpeningSupplyShulker,
            HighwayState.LootingSupplyShulker,
            HighwayState.OpeningLootEnderChest,
            HighwayState.DepositingLootEnderChestDepletedShulkers,
            HighwayState.LootingLootEnderChestPicks,
            HighwayState.LootingLootEnderChestEnderChests,
            HighwayState.LootingLootEnderChestSupplies,
            HighwayState.DepositingLootEnderChestDepletedShulkersFinal,
            HighwayState.OpeningEnderShulker,
            HighwayState.LootingEnderShulker,
            HighwayState.DepositingStashShulker,
            HighwayState.InQueue
    );

    /** Ticks a throw waits for the server to take {@link #aimToThrow}'s aim before going out anyway. */
    private static final int THROW_AIM_TIMEOUT_TICKS = 20;

    /** Ticks a slot thrown out by {@link #tossForRoom} stays off the pickup pickers: a round trip with room to spare. */
    private static final int TOSS_SETTLE_TICKS = 20;

    // Context tick each slot was last thrown out at by tossForRoom
    private final long[] tossedAt = new long[36];

    HighwayInventory(HighwayContext ctx) {
        super(ctx);
        Arrays.fill(tossedAt, Long.MIN_VALUE);
    }

    public int getObsidianCountInventory() {
        return getItemCountInventory(Item.getId(Blocks.OBSIDIAN.asItem())) + getItemCountInventory(Item.getId(Blocks.CRYING_OBSIDIAN.asItem()));
    }

    public int getGoldenCarrotCountInventory() {
        return getItemCountInventory(Item.getId(Items.GOLDEN_CARROT));
    }

    public int preMineShulkerCount() {
        return preMineShulkerCount;
    }

    public void setPreMineShulkerCount(int count) {
        this.preMineShulkerCount = count;
    }

    // Whether the box mined by the last Mining*Shulker state has reached the inventory.
    public boolean minedShulkerLanded() {
        return preMineShulkerCount < 0 || getShulkerCountInventory(ShulkerType.Any) > preMineShulkerCount;
    }

    /**
     * True while we still carry something the support block under a placing spot could be built out
     * of: any acceptableThrowawayItems block, the netherrack that schematic asks for by default, or
     * the obsidian it falls back to. Out of all three, a spot with nothing under it can never be
     * made placeable, and sending the builder at it only parks it on missing materials.
     */
    public boolean canBuildSupportBlock() {
        return getAcceptableThrowawaySlot() != -1
                || getItemCountInventory(Item.getId(Blocks.NETHERRACK.asItem())) > 0
                || getItemCountInventory(Item.getId(Blocks.OBSIDIAN.asItem())) > 0;
    }

    public int getLargestItemSlot(int itemId) {
        int largestSlot = -1;
        int largestCount = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = playerContext.player().getInventory().getNonEquipmentItems().get(i);
            if (Item.getId(stack.getItem()) == itemId && stack.getCount() > largestCount) {
                largestSlot = i;
                largestCount = stack.getCount();
            }
        }

        return largestSlot;
    }

    public int getItemSlot(int itemId) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = playerContext.player().getInventory().getNonEquipmentItems().get(i);
            if (Item.getId(stack.getItem()) == itemId) {
                return i;
            }
        }

        return -1;
    }

    public int getItemSlotHotbar(int itemId) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = playerContext.player().getInventory().getNonEquipmentItems().get(i);
            if (Item.getId(stack.getItem()) == itemId) {
                return i;
            }
        }

        return -1;
    }

    public int getItemSlotNoHotbar(int itemId) {
        for (int i = 9; i < 36; i++) {
            ItemStack stack = playerContext.player().getInventory().getNonEquipmentItems().get(i);
            if (Item.getId(stack.getItem()) == itemId) {
                return i;
            }
        }

        return -1;
    }

    public int putItemHotbar(int itemId) {
        int itemSlot = getItemSlot(itemId);
        if (itemSlot >= 9) {
            baritone.getInventoryBehavior().attemptToPutOnHotbar(itemSlot, usefulSlots::contains);
            itemSlot = getItemSlot(itemId);
        }

        return itemSlot;
    }

    public int putPickaxeHotbar() {
        return putPickaxeHotbar(false);
    }

    public int putPickaxeHotbar(boolean avoidSilkTouch) {
        int itemSlot = getPickaxeSlot(avoidSilkTouch);
        if (itemSlot >= 9) {
            baritone.getInventoryBehavior().attemptToPutOnHotbar(itemSlot, usefulSlots::contains);
            itemSlot = getPickaxeSlot(avoidSilkTouch);
        }

        return itemSlot;
    }

    /** Whether we carry a pickaxe that breaks an ender chest into obsidian rather than dropping the chest */
    public boolean hasNonSilkPick() {
        return getPickaxeSlot(true) != -1;
    }

    private int getPickaxeSlot(boolean avoidSilkTouch) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = playerContext.player().getInventory().getNonEquipmentItems().get(i);
            if (isUsablePick(stack)) {
                if (avoidSilkTouch && hasSilkTouch(stack)) {
                    continue;
                }

                return i;
            }
        }

        return -1;
    }

    public int putBestSwordHotbar() {
        int itemSlot = baritone.getInventoryBehavior().bestSwordSlot();
        if (itemSlot == -1) return -1;
        if (itemSlot >= 9) {
            baritone.getInventoryBehavior().attemptToPutOnHotbar(itemSlot, usefulSlots::contains);
            itemSlot = baritone.getInventoryBehavior().bestSwordSlot();
        }
        return itemSlot;
    }

    public void swapOffhand(int slot) {
        playerContext.playerController().windowClick(0, 45, 0, ClickType.PICKUP, playerContext.player());
        playerContext.playerController().windowClick(0, slot < 9 ? slot + 36 : slot, 0, ClickType.PICKUP, playerContext.player());
        playerContext.playerController().windowClick(0, 45, 0, ClickType.PICKUP, playerContext.player());
    }

    /**
     * Close a container left open outside the states that use one. A box that got clicked twice
     * opens twice, and our close for the first shuts the second server-side only (see
     * {@link ServerSync#openClickPending}), so the server never closes its screen. Left alone, it blocks the
     * printer and holds the Mining states until the stuck check, while the mined box sits on the
     * ground despawning. The client-side close is immediate, so the state machine can run on this tick.
     */
    public void closeStrayContainer() {
        if (!playerContext.player().hasContainerOpen() || CONTAINER_STATES.contains(ctx.currentState().getState())) {
            return;
        }
        Helper.HELPER.logDirect("Closing a container left open in " + ctx.currentState().getState() + ".");
        playerContext.player().closeContainer();
    }

    public boolean autoTotem() {
        if (!settings.highwayAutoTotem.value
                || playerContext.player().getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING
                || OFFHAND_OCCUPIED_STATES.contains(ctx.currentState().getState())) {
            return false;
        }
        // swapOffhand clicks the player inventory: defer while a container is open, the cursor is
        // carrying a stack (clearCursorItem handles that), or an item (gapple) is mid-use.
        if (playerContext.player().hasContainerOpen()
                || !playerContext.player().containerMenu.getCarried().isEmpty()
                || playerContext.player().isUsingItem()) {
            return false;
        }
        int totemSlot = getItemSlot(Item.getId(Items.TOTEM_OF_UNDYING));
        if (totemSlot != -1) {
            swapOffhand(totemSlot);
            ctx.resetTimer();
            return true;
        }
        return false;
    }

    /**
     * Swap the offhand ender chest stack into the inventory, merging onto a partial loose stack
     * first so the chests never fragment into an extra slot; falls back to an empty slot. A merge
     * moves at most (64 - partial) and leaves the remainder in the offhand for a follow-up call.
     * Returns false when the offhand holds no ender chests or the inventory has no room.
     */
    public boolean stashOffhandEnderChests() {
        Item offhandItem = playerContext.player().getOffhandItem().getItem();
        if (!(offhandItem instanceof BlockItem) || !(((BlockItem) offhandItem).getBlock() instanceof EnderChestBlock)) {
            return false;
        }
        int targetSlot = getMergeableEnderChestSlot();
        if (targetSlot == -1) {
            targetSlot = getItemSlot(Item.getId(Items.AIR));
        }
        if (targetSlot == -1) {
            return false;
        }
        swapOffhand(targetSlot);
        return true;
    }

    /**
     * Ender chests belong in the offhand only between PrepEchest and SwapBack; anywhere else they
     * are leftovers of an interrupted farm session, invisible to every inventory count (all scan
     * slots 0-35 only).
     */
    public boolean rescueOffhandEnderChests() {
        Item offhandItem = playerContext.player().getOffhandItem().getItem();
        if (!(offhandItem instanceof BlockItem) || !(((BlockItem) offhandItem).getBlock() instanceof EnderChestBlock)
                || OFFHAND_OCCUPIED_STATES.contains(ctx.currentState().getState())) {
            return false;
        }
        // Same interaction guards as autoTotem: swapOffhand clicks the player inventory
        if (playerContext.player().hasContainerOpen()
                || !playerContext.player().containerMenu.getCarried().isEmpty()
                || playerContext.player().isUsingItem()) {
            return false;
        }
        if (stashOffhandEnderChests()) {
            ctx.resetTimer();
            return true;
        }
        // No partial chest stack and no empty slot: toss a throwaway stack so the next tick's
        // stash has somewhere to land. Chests always outrank netherrack.
        int throwawaySlot = getThrowawaySlotToToss();
        if (throwawaySlot == -1) {
            return false;
        }
        AbstractContainerMenu menu = playerContext.player().containerMenu;
        playerContext.playerController().windowClick(menu.containerId, invSlotToMenuSlot(throwawaySlot), 0, ClickType.PICKUP, playerContext.player());
        playerContext.playerController().windowClick(menu.containerId, -999, 0, ClickType.PICKUP, playerContext.player());
        ctx.resetTimer();
        return true;
    }

    public boolean clearCursorItem() {
        AbstractContainerMenu curContainer = playerContext.player().containerMenu;
        if (!curContainer.getCarried().isEmpty()) {
            if (cursorStackNonEmpty && ctx.timer() >= 20) {
                // We have some item on our cursor for 20 ticks, try to place it somewhere
                ctx.resetTimer();


                int emptySlot = getItemSlot(Item.getId(Items.AIR));
                if (emptySlot != -1) {
                    Helper.HELPER.logDirect("Had " + curContainer.getCarried().getDisplayName() + " on our cursor. Trying to place into slot " + emptySlot);

                    playerContext.playerController().windowClick(curContainer.containerId, invSlotToMenuSlot(emptySlot), 0, ClickType.PICKUP, playerContext.player());
                    cursorStackNonEmpty = false;
                    return true;
                } else {
                    // Junk by the list only: a scrap tool on the cursor may be the user's only
                    // shovel, and the kept-tool ranking only sees the inventory slots
                    boolean listedJunk = settings.highwayThrowJunk.value
                            && HighwayJunk.isJunk(curContainer.getCarried(), settings.highwayJunkItems.value, List.of());
                    if (isAcceptableThrowawayItem(curContainer.getCarried().getItem()) || listedJunk) {
                        // Netherrack or junk on our cursor, we can just throw it out
                        playerContext.playerController().windowClick(curContainer.containerId, -999, 0, ClickType.PICKUP, playerContext.player());
                        cursorStackNonEmpty = false;
                        return true;
                    } else {
                        // We don't have throwaway items on our cursor, might be important so swap with throwaway items and throw away the throwaway
                        int throwawaySlot = getThrowawaySlotToToss();
                        if (throwawaySlot != -1) {
                            playerContext.playerController().windowClick(curContainer.containerId, invSlotToMenuSlot(throwawaySlot), 0, ClickType.PICKUP, playerContext.player());
                            playerContext.playerController().windowClick(curContainer.containerId, -999, 0, ClickType.PICKUP, playerContext.player());
                            cursorStackNonEmpty = false;
                            return true;
                        }
                    }
                }
            } else if (!cursorStackNonEmpty) {
                cursorStackNonEmpty = true;
                ctx.resetTimer();
                return true;
            }
            return true;
        }
        return false;
    }

    public int invSlotToMenuSlot(int invSlot) {
        AbstractContainerMenu menu = playerContext.player().containerMenu;
        if (menu == playerContext.player().inventoryMenu) {
            // Inventory menu: main slots map directly, hotbar sits after them at 36-44
            return invSlot < 9 ? invSlot + 36 : invSlot;
        }
        // Container menus: container slots first, then main inventory, then hotbar
        int containerSlots = menu.slots.size() - 36;
        return invSlot < 9 ? invSlot + containerSlots + 27 : invSlot + containerSlots - 9;
    }

    private NonNullList<ItemStack> getShulkerContents(ItemStack shulker) {
        NonNullList<ItemStack> contents = NonNullList.withSize(27, ItemStack.EMPTY);

        if (shulker.has(DataComponents.CONTAINER)) {
            ItemContainerContents container = shulker.get(DataComponents.CONTAINER);
            container.copyInto(contents);
        }
        else if (shulker.has(DataComponents.BLOCK_ENTITY_DATA)) {
            TypedEntityData<BlockEntityType<?>> data = shulker.get(DataComponents.BLOCK_ENTITY_DATA);
            if (data != null && data.contains("Items")) {
                ContainerHelper.loadAllItems(TagValueInput.create(ProblemReporter.DISCARDING, playerContext.player().registryAccess(), data.copyTagWithoutId()), contents);
            }
        }

        return contents;
    }

    int isPickaxeShulker(ItemStack shulker) {
        NonNullList<ItemStack> contents = getShulkerContents(shulker);

        int pickaxeCount = 0;
        for (ItemStack curStack : contents) {
            if (curStack.is(ItemTags.PICKAXES)) {
                if (!isUsablePick(curStack)) {
                    continue;
                }
                pickaxeCount++;
            } else if (!(curStack.getItem() instanceof AirItem)) {
                if (!settings.highwayAllowMixedShulks.value || !(curStack.getItem() instanceof BlockItem) || !(((BlockItem)curStack.getItem()).getBlock() instanceof EnderChestBlock)) {
                    return 0; // Found a non pickaxe and non-air item
                }
            }
        }

        return pickaxeCount;
    }

    private boolean hasSilkTouch(ItemStack itemStack) {
        ItemEnchantments enchantments = itemStack.getEnchantments();
        for (Holder<Enchantment> enchant : enchantments.keySet()) {
            if (enchant.is(Enchantments.SILK_TOUCH) && enchantments.getLevel(enchant) > 0) {
                return true;
            }
        }
        return false;
    }

    int isNonSilkPickShulker(ItemStack shulker) {
        NonNullList<ItemStack> contents = getShulkerContents(shulker);

        int pickaxeCount = 0;
        for (ItemStack curStack : contents) {
            if (curStack.is(ItemTags.PICKAXES)) {
                if (!isUsablePick(curStack)) {
                    continue;
                }
                pickaxeCount++;


                if (hasSilkTouch(curStack)) {
                    // Pickaxe is enchanted with silk touch
                    return 0;
                }
            } else if (!(curStack.getItem() instanceof AirItem)) {
                if (!settings.highwayAllowMixedShulks.value || !(curStack.getItem() instanceof BlockItem) || !(((BlockItem)curStack.getItem()).getBlock() instanceof EnderChestBlock)) {
                    return 0; // Found a non pickaxe and non-air item
                }
            }
        }

        return pickaxeCount;
    }

    private boolean isDepletedPickShulker(ItemStack shulker) {
        if (!settings.itemSaver.value) {
            return false;
        }
        NonNullList<ItemStack> contents = getShulkerContents(shulker);

        int depletedCount = 0;
        for (ItemStack curStack : contents) {
            if (curStack.getItem() instanceof AirItem) {
                continue;
            }
            if (curStack.is(ItemTags.PICKAXES) && (curStack.getDamageValue() + settings.itemSaverThreshold.value) >= curStack.getMaxDamage() && curStack.getMaxDamage() > 1) {
                depletedCount++;
            } else {
                return false; // Found a usable pick or some other item
            }
        }

        return depletedCount > 0;
    }

    int isEnderChestShulker(ItemStack shulker) {
        NonNullList<ItemStack> contents = getShulkerContents(shulker);

        int enderChestCount = 0;
        for (ItemStack curStack : contents) {
            if (Item.getId(curStack.getItem()) != Item.getId(Items.AIR) && Item.getId(curStack.getItem()) != Item.getId(Blocks.ENDER_CHEST.asItem())) {
                if (!settings.highwayAllowMixedShulks.value || !curStack.is(ItemTags.PICKAXES)) {
                    return 0;
                }
            }

            if (Item.getId(curStack.getItem()) == Item.getId(Blocks.ENDER_CHEST.asItem())) {
                enderChestCount++;
            }
        }
        return enderChestCount;
    }

    /** How many of the item a box holding nothing else has, 0 for any other box. */
    int singleItemShulkerCount(ItemStack shulker, Item item) {
        NonNullList<ItemStack> contents = getShulkerContents(shulker);

        int count = 0;
        for (ItemStack curStack : contents) {
            if (!curStack.isEmpty() && !curStack.is(item)) {
                return 0;
            }
            count += curStack.getCount();
        }
        return count;
    }

    boolean isEmptyShulker(ItemStack shulker) {
        NonNullList<ItemStack> contents = getShulkerContents(shulker);

        for (ItemStack curStack : contents) {
            if (!(curStack.getItem() instanceof AirItem)) {
                // Not air so shulker contains something
                return false;
            }
        }

        // Didn't find anything in the shulker, so it's empty
        return true;
    }

    public int getShulkerSlot(ShulkerType shulkerType) {
        int bestSlot = -1;
        int bestSlotCount = Integer.MAX_VALUE;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = playerContext.player().getInventory().getNonEquipmentItems().get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                continue;
            }
            if (HighwayContext.shulkerItemList.contains(stack.getItem())) {
                switch (shulkerType) {
                    case AnyPickaxe: {
                        int count = isPickaxeShulker(stack);
                        if (count > 0 && count < bestSlotCount) {
                            bestSlot = i;
                            bestSlotCount = count;
                        }
                        break;
                    }

                    case NonSilkPickaxe: {
                        int count = isNonSilkPickShulker(stack);
                        if (count > 0 && count < bestSlotCount) {
                            bestSlot = i;
                            bestSlotCount = count;
                        }
                        break;
                    }

                    case EnderChest: {
                        int count = isEnderChestShulker(stack);
                        if (count > 0 && count < bestSlotCount) {
                            bestSlot = i;
                            bestSlotCount = count;
                        }
                        break;
                    }

                    case Empty: {
                        if (isEmptyShulker(stack)) {
                            return i;
                        }
                        break;
                    }

                    case DepletedPickaxe: {
                        if (isDepletedPickShulker(stack)) {
                            return i;
                        }
                        break;
                    }

                    default: {
                        // the supply boxes, one item each; the emptiest goes first
                        int count = shulkerType.item() == null ? 0 : singleItemShulkerCount(stack, shulkerType.item());
                        if (count > 0 && count < bestSlotCount) {
                            bestSlot = i;
                            bestSlotCount = count;
                        }
                        break;
                    }
                }
            }
        }

        return bestSlot;
    }

    public int getItemCountInventory(int itemId) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = playerContext.player().getInventory().getNonEquipmentItems().get(i);
            if (Item.getId(stack.getItem()) == itemId) {
                if (itemId == 0) {
                    // We're counting air slots
                    count++;
                } else {
                    count += stack.getCount();
                }
            }
        }

        return count;
    }

    /**
     * A pick the builder runs on: diamond or netherite, and not retired by item saver. A stray
     * wooden pick picked up off the road must not count, or it holds the count at the threshold
     * and the builder digs on with it instead of refilling from a pick shulker.
     */
    boolean isUsablePick(ItemStack stack) {
        if (!HighwayContext.validPicksList.contains(stack.getItem())) {
            return false;
        }
        return !settings.itemSaver.value || (stack.getDamageValue() + settings.itemSaverThreshold.value) < stack.getMaxDamage() || stack.getMaxDamage() <= 1;
    }

    public int getPickCountInventory() {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            if (isUsablePick(playerContext.player().getInventory().getNonEquipmentItems().get(i))) {
                count++;
            }
        }

        return count;
    }

    /**
     * Totems we can actually pop, so the offhand one counts: it's the one that saves us, and it's
     * invisible to {@link #getItemCountInventory} (which only scans slots 0-35).
     */
    public int getTotemCountInventory() {
        int count = getItemCountInventory(Item.getId(Items.TOTEM_OF_UNDYING));
        ItemStack offhand = playerContext.player().getOffhandItem();
        if (offhand.is(Items.TOTEM_OF_UNDYING)) {
            count += offhand.getCount();
        }

        return count;
    }

    int putShulkerHotbar(ShulkerType shulkerType) {
        int shulkerSlot = getShulkerSlot(shulkerType);
        if (shulkerSlot >= 9) {
            baritone.getInventoryBehavior().attemptToPutOnHotbar(shulkerSlot, usefulSlots::contains);
            shulkerSlot = getShulkerSlot(shulkerType);
        }

        return shulkerSlot;
    }

    /**
     * Inventory slot of a loose ender chest stack that still has room to merge into (count < 64),
     * preferring the smallest so we top a partial stack rather than starting a new one. -1 if none.
     */
    int getMergeableEnderChestSlot() {
        int best = -1;
        int bestCount = 64;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = playerContext.player().getInventory().getNonEquipmentItems().get(i);
            if (stack.getItem() instanceof BlockItem && ((BlockItem) stack.getItem()).getBlock() instanceof EnderChestBlock && stack.getCount() < 64) {
                if (stack.getCount() < bestCount) {
                    best = i;
                    bestCount = stack.getCount();
                }
            }
        }
        return best;
    }

    public int getShulkerCountInventory(ShulkerType shulkerType) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = playerContext.player().getInventory().getNonEquipmentItems().get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                continue;
            }
            if (HighwayContext.shulkerItemList.contains(stack.getItem())) {
                switch (shulkerType) {
                    case AnyPickaxe:
                        if (isPickaxeShulker(stack) > 0) {
                            count++;
                        }
                        break;

                    case Empty:
                        if (isEmptyShulker(stack)) {
                            count++;
                        }
                        break;

                    case NonSilkPickaxe:
                        if (isNonSilkPickShulker(stack) > 0) {
                            count++;
                        }
                        break;

                    case EnderChest:
                        if (isEnderChestShulker(stack) > 0) {
                            count++;
                        }
                        break;

                    case DepletedPickaxe:
                        if (isDepletedPickShulker(stack)) {
                            count++;
                        }
                        break;

                    case Any:
                        count++;
                        break;

                    default:
                        if (shulkerType.item() != null && singleItemShulkerCount(stack, shulkerType.item()) > 0) {
                            count++;
                        }
                }
            }
        }

        return count;
    }

    public boolean isShulkerOnGround() {
        for (Entity entity : playerContext.entities()) {
            if (entity instanceof ItemEntity) {
                if (HighwayContext.shulkerItemList.contains(((ItemEntity) entity).getItem().getItem())) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isAcceptableThrowawayItem(Item item) {
        return settings.acceptableThrowawayItems.value.contains(item);
    }

    public int getAcceptableThrowawaySlot() {
        for (Item throwawayItem : settings.acceptableThrowawayItems.value) {
            int slot = getItemSlot(Item.getId(throwawayItem));
            if (slot != -1) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Junk slots ({@link HighwayJunk}), shared by the pickers and the room counts: a slot a picker
     * would throw that the counts miss stops the farm short with junk still aboard, and a counted
     * slot no picker throws leaves farmed obsidian on the ground.
     */
    IntPredicate junkSlots() {
        if (!settings.highwayThrowJunk.value) {
            return slot -> false;
        }
        List<ItemStack> inventory = playerContext.player().getInventory().getNonEquipmentItems();
        IntPredicate keptTool = baritone.getInventoryBehavior()::holdsKeptTool;
        return slot -> HighwayJunk.isJunkSlot(slot, inventory.get(slot), keptTool,
                settings.highwayJunkItems.value, settings.highwayScrapMaterials.value);
    }

    public int getThrowawaySlotToToss() {
        return throwawaySlotToToss(playerContext.player().getInventory().getNonEquipmentItems(), junkSlots(),
                settings.acceptableThrowawayItems.value, layout().paving());
    }

    /** {@link #getThrowawaySlotToToss()} for a state making room to pick drops up, skipping {@link #tossSettling} slots. */
    public int throwawaySlotForPickup() {
        return throwawaySlotToToss(playerContext.player().getInventory().getNonEquipmentItems(), junkSlots(),
                settings.acceptableThrowawayItems.value, layout().paving(), this::tossSettling);
    }

    static int throwawaySlotToToss(List<ItemStack> inventory, IntPredicate junkSlot, List<Item> throwaway, boolean paving) {
        return throwawaySlotToToss(inventory, junkSlot, throwaway, paving, slot -> false);
    }

    /**
     * Junk first, then acceptableThrowawayItems blocks in the setting's order; never slot 8 or a
     * {@code skip} slot. Netherrack goes after the junk because lava removal, support blocks and
     * pillaring still use it.
     */
    static int throwawaySlotToToss(List<ItemStack> inventory, IntPredicate junkSlot, List<Item> throwaway, boolean paving, IntPredicate skip) {
        // in slot order, so the hotbar goes first: that's where pickups land
        for (int i = 0; i < 36; i++) {
            if (!skip.test(i) && junkSlot.test(i)) {
                return i;
            }
        }
        for (Item throwawayItem : throwaway) {
            // Paving's product is never a throwaway, whatever the list says; obsidianRoomInventory
            // assumes as much.
            if (paving && (throwawayItem == Blocks.OBSIDIAN.asItem() || throwawayItem == Blocks.CRYING_OBSIDIAN.asItem())) {
                continue;
            }
            for (int i = 0; i < 36; i++) {
                if (i != 8 && !skip.test(i) && inventory.get(i).is(throwawayItem)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Room for a shulker: junk, then a throwaway, else the smallest obsidian stack, else the
     * smallest crying obsidian stack. A paver digging out an obsidian-filled road refills every
     * slot with obsidian, which is never a throwaway while paving.
     */
    public int roomSlotToToss() {
        return roomSlotToToss(playerContext.player().getInventory().getNonEquipmentItems(), junkSlots(),
                settings.acceptableThrowawayItems.value, layout().paving());
    }

    /** {@link #roomSlotToToss()} for a state making room to pick a shulker up, skipping {@link #tossSettling} slots. */
    public int roomSlotForPickup() {
        return roomSlotToToss(playerContext.player().getInventory().getNonEquipmentItems(), junkSlots(),
                settings.acceptableThrowawayItems.value, layout().paving(), this::tossSettling);
    }

    static int roomSlotToToss(List<ItemStack> inventory, IntPredicate junkSlot, List<Item> throwaway, boolean paving) {
        return roomSlotToToss(inventory, junkSlot, throwaway, paving, slot -> false);
    }

    static int roomSlotToToss(List<ItemStack> inventory, IntPredicate junkSlot, List<Item> throwaway, boolean paving, IntPredicate skip) {
        int slot = throwawaySlotToToss(inventory, junkSlot, throwaway, paving, skip);
        if (slot != -1) {
            return slot;
        }
        for (Item item : List.of(Blocks.OBSIDIAN.asItem(), Blocks.CRYING_OBSIDIAN.asItem())) {
            int smallest = Integer.MAX_VALUE;
            for (int i = 0; i < 36; i++) {
                ItemStack stack = inventory.get(i);
                if (i != 8 && !skip.test(i) && stack.is(item) && stack.getCount() < smallest) {
                    smallest = stack.getCount();
                    slot = i;
                }
            }
            if (slot != -1) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Throw a player inventory slot out to make room for a pickup, once {@link #aimToThrow} says
     * so. The slot is then off the pickup pickers until it settles.
     */
    public void tossForRoom(int slot) {
        int containerId = playerContext.player().inventoryMenu.containerId;
        playerContext.playerController().windowClick(containerId, slot < 9 ? slot + 36 : slot, 0, ClickType.PICKUP, playerContext.player());
        playerContext.playerController().windowClick(containerId, -999, 0, ClickType.PICKUP, playerContext.player());
        tossedAt[slot] = ctx.contextTick();
    }

    /**
     * Whether {@link #tossForRoom} emptied this slot too recently to trust what we see in it. The
     * server puts whatever we touch next into the slot we emptied, and a slot update it sent before
     * our throw can arrive after it and show the old stack again. Throwing the slot a second time
     * then throws what the server put there, the shulker box we came to pick up included.
     */
    private boolean tossSettling(int slot) {
        return tossedAt[slot] > ctx.contextTick() - TOSS_SETTLE_TICKS;
    }

    /**
     * Faces back along the highway and a little up; true once a stack thrown now flies that way.
     * A thrown stack leaves along the rotation the server last received, and our click goes out
     * before this tick's movement packet, so the aim has to be sent a tick ahead of the throw.
     * Behind us is road the build has already passed, and the lift carries the stack over a box
     * placed beside us. The old fixed yaw of 45 threw straight down the road on one diagonal, where
     * the bot walked back over its junk and picked it up again. A path still walking us to the
     * pickup is stopped: its look would fight ours, and Grim drops clicks sent with movement keys
     * held.
     *
     * @param ticksWaited ticks the throw has been waiting; past the timeout it goes out unaimed, so
     *                    a look something else keeps overriding can't wedge the room-making
     */
    public boolean aimToThrow(int ticksWaited) {
        // the input packet that releases the keys goes out at the end of this tick, so not now
        boolean stopping = baritone.getPathingBehavior().isPathing();
        if (stopping) {
            baritone.getPathingBehavior().cancelEverything();
        }
        Rotation aim = new Rotation(layout().highwayDirectionYaw() + 180, -30);
        baritone.getLookBehavior().updateTarget(aim, true);
        Rotation sent = baritone.getLookBehavior().getServerRotation().orElse(null);
        if (!stopping && sent != null && Math.abs(Mth.wrapDegrees(sent.getYaw() - aim.getYaw())) < 10 && Math.abs(sent.getPitch() - aim.getPitch()) < 10) {
            return true;
        }
        return ticksWaited >= THROW_AIM_TIMEOUT_TICKS;
    }

    public int getAcceptableThrowawaySlotNoHotbar() {
        for (Item throwawayItem : settings.acceptableThrowawayItems.value) {
            int slot = getItemSlotNoHotbar(Item.getId(throwawayItem));
            if (slot != -1) {
                return slot;
            }
        }
        return -1;
    }

    /** Puts the first acceptableThrowawayItems block we carry onto the hotbar; returns its slot or -1. */
    public int putAcceptableThrowawayHotbar() {
        for (Item throwawayItem : settings.acceptableThrowawayItems.value) {
            int slot = getItemSlotHotbar(Item.getId(throwawayItem));
            if (slot != -1) {
                return slot;
            }
        }
        for (Item throwawayItem : settings.acceptableThrowawayItems.value) {
            int slot = putItemHotbar(Item.getId(throwawayItem));
            if (slot != -1) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Puts a block to fill lava or build a support with onto the hotbar: the first
     * acceptableThrowawayItems block we carry, else netherrack, else obsidian - the same three
     * {@link #canBuildSupportBlock} counts. Returns its slot, or -1 when we carry none of them.
     */
    public int putFillBlockHotbar() {
        int slot = putAcceptableThrowawayHotbar();
        if (slot != -1) {
            return slot;
        }
        for (Item item : List.of(Blocks.NETHERRACK.asItem(), Blocks.OBSIDIAN.asItem())) {
            if (getItemCountInventory(Item.getId(item)) > 0) {
                return putItemHotbar(Item.getId(item));
            }
        }
        return -1;
    }

    /**
     * True when obsidian is the only block we could place: no acceptableThrowawayItems block and no
     * netherrack aboard, but obsidian. It is the paving material and slow to dig back out, so
     * whatever has a choice takes the route that places the least of it.
     */
    public boolean onlyObsidianToPlace() {
        return getAcceptableThrowawaySlot() == -1
                && getItemCountInventory(Item.getId(Blocks.NETHERRACK.asItem())) == 0
                && getItemCountInventory(Item.getId(Blocks.OBSIDIAN.asItem())) > 0;
    }
}
