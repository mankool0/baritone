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

package baritone.behavior;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.utils.Helper;
import baritone.utils.PendingToolDamage;
import baritone.utils.ToolSet;
import baritone.utils.accessor.IClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.effects.EnchantmentAttributeEffect;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Random;
import java.util.function.Predicate;

public final class InventoryBehavior extends Behavior implements Helper {

    public static final int SWORD_SLOT = 0;
    public static final int PICKAXE_SLOT = 1;
    public static final int SHOVEL_SLOT = 2;
    public static final int AXE_SLOT = 3;
    public static final int SPARE_PICKAXE_SLOT = 4;
    private static final int[] PICKAXE_SLOTS = {PICKAXE_SLOT, SPARE_PICKAXE_SLOT};

    int ticksSinceLastInventoryMove;
    private final int[] ticksSinceHotbarMove = new int[9];
    int[] lastTickRequestedMove; // not everything asks every tick, so remember the request while coming to a halt

    public InventoryBehavior(Baritone baritone) {
        super(baritone);
    }

    /**
     * @return ticks since the last hotbar swap was sent. Stops advancing while a container is
     * open, and is meaningless when {@code allowInventory} is off since no moves are made then.
     */
    public int ticksSinceLastInventoryMove() {
        return ticksSinceLastInventoryMove;
    }

    /**
     * @return ticks since a swap last changed this hotbar slot, counted like
     * {@link #ticksSinceLastInventoryMove()}
     */
    public int ticksSinceHotbarMove(int slot) {
        return ticksSinceHotbarMove[slot];
    }

    @Override
    public void onTick(TickEvent event) {
        if (!Baritone.settings().allowInventory.value) {
            return;
        }
        if (event.getType() == TickEvent.Type.OUT) {
            return;
        }
        if (ctx.player().containerMenu != ctx.player().inventoryMenu) {
            // we have a crafting table or a chest or something open
            return;
        }
        ticksSinceLastInventoryMove++;
        for (int i = 0; i < ticksSinceHotbarMove.length; i++) {
            ticksSinceHotbarMove[i]++;
        }
        boolean pathing = baritone.getPathingBehavior().isPathing();
        if (pathing && firstValidThrowaway() >= 9) { // aka there are none on the hotbar, but there are some in main inventory
            requestSwapWithHotBar(firstValidThrowaway(), 8);
        }
        if (Baritone.settings().keepToolsOnHotbar.value) {
            // not gated on pathing: walk-creep and the printer don't path, and switchToBestToolFor only looks at the hotbar
            keepToolAt(bestSwordSlot(), SWORD_SLOT);
            keepPickaxes();
            keepToolAt(bestToolAgainst(Blocks.DIRT, ItemTags.SHOVELS), SHOVEL_SLOT);
            keepToolAt(bestToolAgainst(Blocks.OAK_LOG, ItemTags.AXES), AXE_SLOT);
        } else if (pathing) {
            int pick = bestToolAgainst(Blocks.STONE, ItemTags.PICKAXES);
            if (pick >= 9) {
                requestSwapWithHotBar(pick, 0);
            }
        }
        if (lastTickRequestedMove != null) {
            logDebug("Remembering to move " + lastTickRequestedMove[0] + " " + lastTickRequestedMove[1] + " from a previous tick");
            requestSwapWithHotBar(lastTickRequestedMove[0], lastTickRequestedMove[1]);
        }
    }

    private void keepToolAt(int bestSlot, int hotbarSlot) {
        if (bestSlot >= 9) {
            requestSwapWithHotBar(bestSlot, hotbarSlot);
        }
    }

    /**
     * Pickaxes live on slots 1 and 4. The one in use stays where it is until it's spent, and once
     * the hotbar ones run low the next one from the main inventory comes to whichever of the two
     * doesn't hold a working one, so ToolSet goes on to it without a swap in between.
     */
    private void keepPickaxes() {
        int threshold = Baritone.settings().sparePickaxeThreshold.value;
        if (threshold <= 0) {
            keepToolAt(bestToolAgainst(Blocks.STONE, ItemTags.PICKAXES), PICKAXE_SLOT);
            return;
        }
        int next = nextPickaxe();
        if (next == -1) {
            return;
        }
        // a better pickaxe than any on the hotbar leaves nothing working there, so it comes over at once
        double minSpeed = pickaxeSpeed(next);
        PendingToolDamage pending = ((IClientLevel) ctx.world()).getPendingToolDamage();
        double lifeLeft = 0;
        for (int i = 0; i < 9; i++) {
            if (holdsWorkingPickaxe(i, minSpeed)) {
                double left = ToolSet.lifeLeft(ctx.player().getInventory().getItem(i), pending, i)
                        - (Baritone.settings().itemSaver.value ? Baritone.settings().itemSaverThreshold.value : 0);
                lifeLeft += Math.max(0, left);
            }
        }
        if (lifeLeft > threshold) {
            return;
        }
        for (int slot : PICKAXE_SLOTS) {
            if (!holdsWorkingPickaxe(slot, minSpeed) && !hotbarSlotBusy(slot)) {
                requestSwapWithHotBar(next, slot);
                return;
            }
        }
    }

    /**
     * @return the main inventory slot of the best pickaxe off the hotbar, of equally good ones the
     * one with the most left so it outlasts the one in use, or -1 if there is none
     */
    private int nextPickaxe() {
        NonNullList<ItemStack> invy = ctx.player().getInventory().getNonEquipmentItems();
        int bestInd = -1;
        double bestSpeed = -1;
        int bestLeft = -1;
        for (int i = 9; i < invy.size(); i++) {
            ItemStack stack = invy.get(i);
            if (!stack.is(ItemTags.PICKAXES) || itemSaverRetired(stack)) {
                continue;
            }
            double speed = pickaxeSpeed(i);
            int left = stack.getMaxDamage() - stack.getDamageValue();
            if (speed > bestSpeed || (speed == bestSpeed && left > bestLeft)) {
                bestInd = i;
                bestSpeed = speed;
                bestLeft = left;
            }
        }
        return bestInd;
    }

    private double nextPickaxeSpeed() {
        int next = nextPickaxe();
        return next == -1 ? -1 : pickaxeSpeed(next);
    }

    /**
     * @param minSpeed the speed of the best pickaxe off the hotbar, -1 if there is none
     * @return whether the slot holds a pickaxe item saver hasn't retired that nothing off the
     * hotbar beats
     */
    private boolean holdsWorkingPickaxe(int slot, double minSpeed) {
        ItemStack stack = ctx.player().getInventory().getNonEquipmentItems().get(slot);
        return stack.is(ItemTags.PICKAXES) && !itemSaverRetired(stack) && pickaxeSpeed(slot) >= minSpeed;
    }

    private double pickaxeSpeed(int slot) {
        return ToolSet.calculateSpeedVsBlock(ctx.player().getInventory().getNonEquipmentItems().get(slot), Blocks.STONE.defaultBlockState());
    }

    /**
     * Whether swapping this hotbar slot now would go wrong. A dig with its tool or a place from it
     * that the server hasn't acked yet still sends the tool's durability or the stack's count back,
     * and that overwrites the swap client-side until the server resends the whole inventory a round
     * trip later. And swapping out an item in use, like food being eaten, stops the use.
     */
    private boolean hotbarSlotBusy(int slot) {
        if (slot < 0 || slot >= 9) {
            return false;
        }
        LocalPlayer player = ctx.player();
        if (player.isUsingItem() && player.getUsedItemHand() == InteractionHand.MAIN_HAND
                && player.getInventory().getSelectedSlot() == slot) {
            return true;
        }
        IClientLevel level = (IClientLevel) ctx.world();
        return level.getPendingToolDamage().worst(slot) > 0 || level.getPendingPlaces().inFlight(slot) > 0;
    }

    /**
     * Whether the server still holds some of this hotbar stack once the places already sent from it
     * land. The client's count runs high while places are in flight, and a place sent on it after
     * the server has run the slot dry goes out with whatever got picked up into the slot since.
     */
    public boolean hotbarStackLasts(int slot, ItemStack stack) {
        return ((IClientLevel) ctx.world()).getPendingPlaces().countAfterInFlight(slot, stack) > 0;
    }

    private static boolean itemSaverRetired(ItemStack stack) {
        return Baritone.settings().itemSaver.value && stack.getMaxDamage() > 1
                && stack.getDamageValue() + Baritone.settings().itemSaverThreshold.value >= stack.getMaxDamage();
    }

    private boolean reservedForTool(int slot) {
        if (!Baritone.settings().keepToolsOnHotbar.value) {
            return false;
        }
        if (holdsKeptTool(slot)) {
            // a kept tool outside its home: evicting it has onTick swap it straight back
            return true;
        }
        switch (slot) {
            case SWORD_SLOT:
                return bestSwordSlot() != -1;
            case PICKAXE_SLOT:
                return bestToolAgainst(Blocks.STONE, ItemTags.PICKAXES) != -1;
            case SHOVEL_SLOT:
                return bestToolAgainst(Blocks.DIRT, ItemTags.SHOVELS) != -1;
            case AXE_SLOT:
                return bestToolAgainst(Blocks.OAK_LOG, ItemTags.AXES) != -1;
            case SPARE_PICKAXE_SLOT:
                // only while it holds one, otherwise it's free for anything else
                return Baritone.settings().sparePickaxeThreshold.value > 0 && holdsWorkingPickaxe(slot, nextPickaxeSpeed());
            default:
                return false;
        }
    }

    /**
     * Whether the slot holds the class-best sword, pickaxe, shovel or axe, whether or not
     * keepToolsOnHotbar is on: the highway never throws one out as scrap.
     */
    public boolean holdsKeptTool(int slot) {
        if (ctx.player().getInventory().getNonEquipmentItems().get(slot).isEmpty()) {
            return false;
        }
        return slot == bestSwordSlot()
                || slot == bestToolAgainst(Blocks.STONE, ItemTags.PICKAXES)
                || slot == bestToolAgainst(Blocks.DIRT, ItemTags.SHOVELS)
                || slot == bestToolAgainst(Blocks.OAK_LOG, ItemTags.AXES);
    }

    // Ordered from highest to lowest priority
    private static final List<Item> SWORD_PRIORITY = List.of(
            Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD,
            Items.COPPER_SWORD, Items.STONE_SWORD, Items.GOLDEN_SWORD, Items.WOODEN_SWORD
    );

    /**
     * Slot 0-35 of the best sword in the inventory, ranked by material with attack enchantments
     * breaking ties, or -1 if there is none.
     */
    public int bestSwordSlot() {
        NonNullList<ItemStack> invy = ctx.player().getInventory().getNonEquipmentItems();
        for (Item swordType : SWORD_PRIORITY) {
            int bestSlot = -1;
            double bestEnchantBonus = -1;
            for (int i = 0; i < invy.size(); i++) {
                ItemStack stack = invy.get(i);
                if (Item.getId(stack.getItem()) != Item.getId(swordType)) continue;
                double bonus = swordAttackEnchantBonus(stack);
                if (bonus > bestEnchantBonus) {
                    bestEnchantBonus = bonus;
                    bestSlot = i;
                }
            }
            if (bestSlot != -1) return bestSlot;
        }
        return -1;
    }

    private double swordAttackEnchantBonus(ItemStack stack) {
        double bonus = 0;
        ItemEnchantments enchantments = stack.getEnchantments();
        for (Holder<Enchantment> enchant : enchantments.keySet()) {
            for (EnchantmentAttributeEffect e : enchant.value().getEffects(EnchantmentEffectComponents.ATTRIBUTES)) {
                if (e.attribute().is(Attributes.ATTACK_DAMAGE.unwrapKey().get())) {
                    bonus += e.amount().calculate(enchantments.getLevel(enchant));
                }
            }
        }
        return bonus;
    }

    /**
     * @param homes hotbar slots this item was in, refilled first even if a throwaway block has
     *              landed there since
     */
    public boolean attemptToPutOnHotbar(int inMainInvy, List<Integer> homes, Predicate<Integer> disallowedHotbar) {
        List<Integer> refillable = refillableHomes(homes, disallowedHotbar);
        if (refillable == null) {
            return false;
        }
        if (!refillable.isEmpty()) {
            return requestSwapWithHotBar(inMainInvy, refillable.get(0));
        }
        return attemptToPutOnHotbar(inMainInvy, disallowedHotbar);
    }

    public boolean attemptToPutOnHotbar(int inMainInvy, Predicate<Integer> disallowedHotbar) {
        OptionalInt destination = getTempHotbarSlot(disallowedHotbar);
        if (destination.isPresent()) {
            if (!requestSwapWithHotBar(inMainInvy, destination.getAsInt())) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param homes hotbar slots these items were in, refilled first even if a throwaway block has
     *              landed there since
     */
    public boolean attemptToFillHotbarFrom(List<Integer> inMainInvy, List<Integer> homes, Predicate<Integer> disallowedHotbar) {
        if (inMainInvy.isEmpty()) {
            return true;
        }
        List<Integer> targets = fillTargets(homes, true, disallowedHotbar);
        if (targets == null) {
            return false;
        }
        if (targets.isEmpty()) {
            return attemptToPutOnHotbar(inMainInvy.get(0), disallowedHotbar);
        }
        if (!inventoryMoveAllowedNow()) {
            return false;
        }
        fillSlots(inMainInvy, targets);
        return true;
    }

    /**
     * Where a restock goes while the hotbar still holds some: like {@link #attemptToFillHotbarFrom}
     * but only empty slots and homes a pickup put a throwaway block in, never evicting anything else
     * for a temporary slot.
     *
     * @param fillEmpty also fill empty slots that weren't this item's homes, otherwise just one home
     * @return null while a home is busy
     */
    public List<Integer> topUpTargets(List<Integer> homes, boolean fillEmpty, Predicate<Integer> disallowedHotbar) {
        List<Integer> targets = fillTargets(homes, fillEmpty, disallowedHotbar);
        return targets != null && !fillEmpty && targets.size() > 1 ? targets.subList(0, 1) : targets;
    }

    /**
     * The hotbar slot an early restock swaps its stack into when none is free: the one holding what
     * is least likely to be wanted on the hotbar soon. Nothing is lost, the swap puts it where the
     * restocked stack came from, and the highway moves it back up when a state needs it.
     *
     * @return -1 if every slot holds something to keep
     */
    public int topUpEvictionSlot(Predicate<Integer> disallowedHotbar) {
        int best = -1;
        int bestRank = Integer.MAX_VALUE;
        for (int i = 1; i < 8; i++) { // 0 and 8 stay reserved for the sword/pickaxe and throwaway
            if (reservedForTool(i) || disallowedHotbar.test(i) || hotbarSlotBusy(i)) {
                continue;
            }
            int rank = topUpEvictionRank(ctx.player().getInventory().getNonEquipmentItems().get(i));
            if (rank >= 0 && rank < bestRank) {
                best = i;
                bestRank = rank;
            }
        }
        return best;
    }

    /**
     * Lower goes first, -1 stays: what's eaten or drunk, since auto-eat and the highway's emergency
     * eat expect it on the hotbar, and totems.
     */
    private static int topUpEvictionRank(ItemStack stack) {
        if (stack.has(DataComponents.CONSUMABLE) || stack.has(DataComponents.DEATH_PROTECTION)) {
            return -1;
        }
        if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock) {
            return 1; // only wanted once the ender chests run out
        }
        if (stack.is(Items.ENDER_CHEST)) {
            return 2; // wanted at every farm, which puts them back itself
        }
        if (stack.isDamageableItem()) {
            return 3; // a tool that isn't kept, like the farm's pickaxe
        }
        return 0;
    }

    /**
     * Moves the stacks into the slots {@link #topUpTargets} gave, if inventory moves are allowed this tick.
     *
     * @return whether anything was moved
     */
    public boolean topUpHotbar(List<Integer> inMainInvy, List<Integer> targets) {
        if (inMainInvy.isEmpty() || targets.isEmpty() || !inventoryMoveAllowedNow()) {
            return false;
        }
        fillSlots(inMainInvy, targets);
        return true;
    }

    /**
     * The refillable homes, then with fillEmpty the other empty slots, or null while a home is busy
     */
    private List<Integer> fillTargets(List<Integer> homes, boolean fillEmpty, Predicate<Integer> disallowedHotbar) {
        List<Integer> targets = refillableHomes(homes, disallowedHotbar);
        if (targets == null || !fillEmpty) {
            return targets;
        }
        for (int i = 1; i < 8; i++) { // 0 and 8 stay reserved for the sword/pickaxe and throwaway
            if (!targets.contains(i) && ctx.player().getInventory().getNonEquipmentItems().get(i).isEmpty() && !reservedForTool(i) && !disallowedHotbar.test(i) && !hotbarSlotBusy(i)) {
                targets.add(i);
            }
        }
        return targets;
    }

    private void fillSlots(List<Integer> inMainInvy, List<Integer> targets) {
        int moves = Math.min(targets.size(), inMainInvy.size());
        for (int m = 0; m < moves; m++) {
            clickSwap(inMainInvy.get(m), targets.get(m));
        }
        ticksSinceLastInventoryMove = 0;
        lastTickRequestedMove = null;
    }

    /**
     * The homes that can be refilled, in order: empty, or holding a throwaway block, which is what a
     * pickup puts in a slot the moment it runs dry. Null while one is busy, since what it holds isn't
     * settled yet, and moving on to another slot is how an item ends up wandering along the hotbar.
     */
    private List<Integer> refillableHomes(List<Integer> homes, Predicate<Integer> disallowedHotbar) {
        List<Integer> refillable = new ArrayList<>();
        for (int slot : homes) {
            if (slot < 1 || slot >= 8 || reservedForTool(slot) || disallowedHotbar.test(slot)) {
                continue;
            }
            if (hotbarSlotBusy(slot)) {
                logDebug("Inventory move requested but waiting for hotbar slot " + slot + " to be done with");
                return null;
            }
            ItemStack stack = ctx.player().getInventory().getNonEquipmentItems().get(slot);
            if (stack.isEmpty() || Baritone.settings().acceptableThrowawayItems.value.contains(stack.getItem())) {
                refillable.add(slot);
            }
        }
        return refillable;
    }

    public OptionalInt getTempHotbarSlot(Predicate<Integer> disallowedHotbar) {
        // 0 and 8 are the sword/pickaxe and throwaway, and occupied tool homes and kept tools are off-limits too
        ArrayList<Integer> candidates = new ArrayList<>();
        for (int i = 1; i < 8; i++) {
            if (ctx.player().getInventory().getNonEquipmentItems().get(i).isEmpty() && !reservedForTool(i) && !disallowedHotbar.test(i)) {
                candidates.add(i);
            }
        }
        if (candidates.isEmpty()) {
            for (int i = 1; i < 8; i++) {
                if (!reservedForTool(i) && !disallowedHotbar.test(i)) {
                    candidates.add(i);
                }
            }
        }
        if (candidates.isEmpty()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(candidates.get(new Random().nextInt(candidates.size())));
    }

    private boolean requestSwapWithHotBar(int inInventory, int inHotbar) {
        lastTickRequestedMove = new int[]{inInventory, inHotbar};
        // ahead of the movement gates, so waiting this out doesn't pause anything
        if (hotbarSlotBusy(inHotbar) || hotbarSlotBusy(inInventory)) {
            logDebug("Inventory move requested but waiting for hotbar slot to be done with");
            return false;
        }
        if (!inventoryMoveAllowedNow()) {
            return false;
        }
        clickSwap(inInventory, inHotbar);
        ticksSinceLastInventoryMove = 0;
        lastTickRequestedMove = null;
        return true;
    }

    private boolean inventoryMoveAllowedNow() {
        if (ticksSinceLastInventoryMove < Baritone.settings().ticksBetweenInventoryMoves.value) {
            logDebug("Inventory move requested but delaying " + ticksSinceLastInventoryMove + " " + Baritone.settings().ticksBetweenInventoryMoves.value);
            return false;
        }
        if (Baritone.settings().inventoryMoveOnlyIfStationary.value && !baritone.getInventoryPauserProcess().stationaryForInventoryMove()) {
            logDebug("Inventory move requested but delaying until stationary");
            return false;
        }
        if (Baritone.settings().inventoryMoveOnlyIfCalm.value && !baritone.getInventoryPauserProcess().calmForInventoryMove()) {
            logDebug("Inventory move requested but waiting for a tick without sprint/input");
            return false;
        }
        return true;
    }

    private void clickSwap(int inInventory, int inHotbar) {
        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, inInventory < 9 ? inInventory + 36 : inInventory, inHotbar, ClickType.SWAP, ctx.player());
        ticksSinceHotbarMove[inHotbar] = 0;
        if (inInventory < 9) {
            ticksSinceHotbarMove[inInventory] = 0;
        }
    }

    private int firstValidThrowaway() { // TODO offhand idk
        NonNullList<ItemStack> invy = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < invy.size(); i++) {
            if (Baritone.settings().acceptableThrowawayItems.value.contains(invy.get(i).getItem())) {
                return i;
            }
        }
        return -1;
    }

    private int bestToolAgainst(Block against, TagKey<Item> kind) {
        NonNullList<ItemStack> invy = ctx.player().getInventory().getNonEquipmentItems();
        int bestInd = -1;
        double bestSpeed = -1;
        for (int i = 0; i < invy.size(); i++) {
            ItemStack stack = invy.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (itemSaverRetired(stack)) {
                continue;
            }
            if (stack.is(kind)) {
                double speed = ToolSet.calculateSpeedVsBlock(stack, against.defaultBlockState()); // takes into account enchants
                if (speed > bestSpeed) {
                    bestSpeed = speed;
                    bestInd = i;
                }
            }
        }
        return bestInd;
    }

    public boolean hasGenericThrowaway() {
        for (Item item : Baritone.settings().acceptableThrowawayItems.value) {
            if (throwaway(false, stack -> item.equals(stack.getItem()))) {
                return true;
            }
        }
        return false;
    }

    public boolean selectThrowawayForLocation(boolean select, int x, int y, int z) {
        BlockState maybe = baritone.getBuilderProcess().placeAt(x, y, z, baritone.bsi.get0(x, y, z));
        if (maybe != null && throwaway(select, stack -> stack.getItem() instanceof BlockItem && maybe.equals(((BlockItem) stack.getItem()).getBlock().getStateForPlacement(new BlockPlaceContext(new UseOnContext(ctx.world(), ctx.player(), InteractionHand.MAIN_HAND, stack, new BlockHitResult(new Vec3(ctx.player().position().x, ctx.player().position().y, ctx.player().position().z), Direction.UP, ctx.playerFeet(), false)) {}))))) {
            return true; // gotem
        }
        if (maybe != null && throwaway(select, stack -> stack.getItem() instanceof BlockItem && ((BlockItem) stack.getItem()).getBlock().equals(maybe.getBlock()))) {
            return true;
        }
        for (Item item : Baritone.settings().acceptableThrowawayItems.value) {
            if (throwaway(select, stack -> item.equals(stack.getItem()))) {
                return true;
            }
        }
        return false;
    }

    public boolean throwaway(boolean select, Predicate<? super ItemStack> desired) {
        return throwaway(select, desired, Baritone.settings().allowInventory.value);
    }

    public boolean throwaway(boolean select, Predicate<? super ItemStack> desired, boolean allowInventory) {
        LocalPlayer p = ctx.player();
        NonNullList<ItemStack> inv = p.getInventory().getNonEquipmentItems();
        for (int i = 0; i < 9; i++) {
            ItemStack item = inv.get(i);
            // this usage of settings() is okay because it's only called once during pathing
            // (while creating the CalculationContext at the very beginning)
            // and then it's called during execution
            // since this function is never called during cost calculation, we don't need to migrate
            // acceptableThrowawayItems to the CalculationContext
            if (desired.test(item) && hotbarStackLasts(i, item)) {
                if (select) {
                    p.getInventory().setSelectedSlot(i);
                }
                return true;
            }
        }
        if (desired.test(p.getItemBySlot(EquipmentSlot.OFFHAND))) {
            // main hand takes precedence over off hand
            // that means that if we have block A selected in main hand and block B in off hand, right clicking places block B
            // we've already checked above ^ and the main hand can't possible have an acceptablethrowawayitem
            // so we need to select in the main hand something that doesn't right click
            // so not a shovel, not a hoe, not a block, etc
            for (int i = 0; i < 9; i++) {
                ItemStack item = inv.get(i);
                if (item.isEmpty() || item.getItem().components().has(DataComponents.TOOL)) {
                    if (select) {
                        p.getInventory().setSelectedSlot(i);
                    }
                    return true;
                }
            }
        }

        if (allowInventory) {
            for (int i = 9; i < 36; i++) {
                if (desired.test(inv.get(i))) {
                    if (select) {
                        int dest = reservedForTool(7) ? getTempHotbarSlot(slot -> false).orElse(-1) : 7;
                        // select only once the swap went through: until then dest holds something
                        // else, and if that's a block the movement places it instead
                        if (dest != -1 && requestSwapWithHotBar(i, dest)) {
                            p.getInventory().setSelectedSlot(dest);
                        }
                    }
                    return true;
                }
            }
        }

        return false;
    }
}
