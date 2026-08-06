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
import baritone.utils.ToolSet;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.effects.EnchantmentAttributeEffect;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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

    int ticksSinceLastInventoryMove;
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
        if (firstValidThrowaway() >= 9) { // aka there are none on the hotbar, but there are some in main inventory
            requestSwapWithHotBar(firstValidThrowaway(), 8);
        }
        if (Baritone.settings().keepToolsOnHotbar.value) {
            keepToolAt(bestSwordSlot(), SWORD_SLOT);
            keepToolAt(bestToolAgainst(Blocks.STONE, PickaxeItem.class), PICKAXE_SLOT);
            keepToolAt(bestToolAgainst(Blocks.DIRT, ShovelItem.class), SHOVEL_SLOT);
            keepToolAt(bestToolAgainst(Blocks.OAK_LOG, AxeItem.class), AXE_SLOT);
        } else {
            int pick = bestToolAgainst(Blocks.STONE, PickaxeItem.class);
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

    private boolean reservedForTool(int slot) {
        if (!Baritone.settings().keepToolsOnHotbar.value) {
            return false;
        }
        switch (slot) {
            case SWORD_SLOT:
                return bestSwordSlot() != -1;
            case PICKAXE_SLOT:
                return bestToolAgainst(Blocks.STONE, PickaxeItem.class) != -1;
            case SHOVEL_SLOT:
                return bestToolAgainst(Blocks.DIRT, ShovelItem.class) != -1;
            case AXE_SLOT:
                return bestToolAgainst(Blocks.OAK_LOG, AxeItem.class) != -1;
            default:
                return false;
        }
    }

    // Ordered from highest to lowest priority
    private static final List<Item> SWORD_PRIORITY = List.of(
            Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD,
            Items.STONE_SWORD, Items.GOLDEN_SWORD, Items.WOODEN_SWORD
    );

    /**
     * Slot 0-35 of the best sword in the inventory, ranked by material with attack enchantments
     * breaking ties, or -1 if there is none.
     */
    public int bestSwordSlot() {
        NonNullList<ItemStack> invy = ctx.player().getInventory().items;
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

    public boolean attemptToPutOnHotbar(int inMainInvy, Predicate<Integer> disallowedHotbar) {
        OptionalInt destination = getTempHotbarSlot(disallowedHotbar);
        if (destination.isPresent()) {
            if (!requestSwapWithHotBar(inMainInvy, destination.getAsInt())) {
                return false;
            }
        }
        return true;
    }

    public boolean attemptToFillHotbarFrom(List<Integer> inMainInvy, Predicate<Integer> disallowedHotbar) {
        if (inMainInvy.isEmpty()) {
            return true;
        }
        List<Integer> empties = new ArrayList<>();
        for (int i = 1; i < 8; i++) { // 0 and 8 stay reserved for the sword/pickaxe and throwaway
            if (ctx.player().getInventory().items.get(i).isEmpty() && !reservedForTool(i) && !disallowedHotbar.test(i)) {
                empties.add(i);
            }
        }
        if (empties.isEmpty()) {
            return attemptToPutOnHotbar(inMainInvy.get(0), disallowedHotbar);
        }
        if (!inventoryMoveAllowedNow()) {
            return false;
        }
        int moves = Math.min(empties.size(), inMainInvy.size());
        for (int m = 0; m < moves; m++) {
            clickSwap(inMainInvy.get(m), empties.get(m));
        }
        ticksSinceLastInventoryMove = 0;
        lastTickRequestedMove = null;
        return true;
    }

    public OptionalInt getTempHotbarSlot(Predicate<Integer> disallowedHotbar) {
        // 0 and 8 are the sword/pickaxe and throwaway, and occupied tool homes are off-limits too
        ArrayList<Integer> candidates = new ArrayList<>();
        for (int i = 1; i < 8; i++) {
            if (ctx.player().getInventory().items.get(i).isEmpty() && !reservedForTool(i) && !disallowedHotbar.test(i)) {
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
    }

    private int firstValidThrowaway() { // TODO offhand idk
        NonNullList<ItemStack> invy = ctx.player().getInventory().items;
        for (int i = 0; i < invy.size(); i++) {
            if (Baritone.settings().acceptableThrowawayItems.value.contains(invy.get(i).getItem())) {
                return i;
            }
        }
        return -1;
    }

    private int bestToolAgainst(Block against, Class<? extends DiggerItem> cla$$) {
        NonNullList<ItemStack> invy = ctx.player().getInventory().items;
        int bestInd = -1;
        double bestSpeed = -1;
        for (int i = 0; i < invy.size(); i++) {
            ItemStack stack = invy.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (Baritone.settings().itemSaver.value && (stack.getDamageValue() + Baritone.settings().itemSaverThreshold.value) >= stack.getMaxDamage() && stack.getMaxDamage() > 1) {
                continue;
            }
            if (cla$$.isInstance(stack.getItem())) {
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
        NonNullList<ItemStack> inv = p.getInventory().items;
        for (int i = 0; i < 9; i++) {
            ItemStack item = inv.get(i);
            // this usage of settings() is okay because it's only called once during pathing
            // (while creating the CalculationContext at the very beginning)
            // and then it's called during execution
            // since this function is never called during cost calculation, we don't need to migrate
            // acceptableThrowawayItems to the CalculationContext
            if (desired.test(item)) {
                if (select) {
                    p.getInventory().selected = i;
                }
                return true;
            }
        }
        if (desired.test(p.getInventory().offhand.get(0))) {
            // main hand takes precedence over off hand
            // that means that if we have block A selected in main hand and block B in off hand, right clicking places block B
            // we've already checked above ^ and the main hand can't possible have an acceptablethrowawayitem
            // so we need to select in the main hand something that doesn't right click
            // so not a shovel, not a hoe, not a block, etc
            for (int i = 0; i < 9; i++) {
                ItemStack item = inv.get(i);
                if (item.isEmpty() || item.getItem() instanceof PickaxeItem) {
                    if (select) {
                        p.getInventory().selected = i;
                    }
                    return true;
                }
            }
        }

        if (allowInventory) {
            for (int i = 9; i < 36; i++) {
                if (desired.test(inv.get(i))) {
                    if (select) {
                        requestSwapWithHotBar(i, 7);
                        p.getInventory().selected = 7;
                    }
                    return true;
                }
            }
        }

        return false;
    }
}
