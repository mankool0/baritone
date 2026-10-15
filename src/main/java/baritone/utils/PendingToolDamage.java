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

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Damage the server takes off hotbar tools for breaks it hasn't acked yet. One per client level,
 * since the prediction sequences are.
 * <p>
 * The client never damages a tool itself: the damage only reaches it with the server's inventory
 * update, a round trip after the break. The server sends that update in the same tick as the
 * break's ack and just ahead of it, so once a break is acked its damage is on the client's stack,
 * and until then it is counted here. Unbreaking spares each point at random, so every break is
 * counted both at its expected cost and at its worst case.
 */
public final class PendingToolDamage {

    private record Break(int sequence, int slot, int worst, double expected) {}

    private final ArrayDeque<Break> unacked = new ArrayDeque<>();
    // written on the main thread only; ToolSet also reads them from pathing threads
    private final int[] worstBySlot = new int[9];
    private final double[] expectedBySlot = new double[9];

    /**
     * Called on the main thread as the client predicts a break, inside the prediction its dig
     * packet goes out under
     */
    public void onDestroyBlock(LocalPlayer player, ClientLevel level, BlockPos pos, int sequence) {
        ItemStack stack = player.getMainHandItem();
        Tool tool = stack.get(DataComponents.TOOL);
        int slot = player.getInventory().getSelectedSlot();
        // the same test Item.mineBlock makes server-side
        if (tool == null || tool.damagePerBlock() <= 0 || slot < 0 || slot >= worstBySlot.length
                || level.getBlockState(pos).getDestroySpeed(level, pos) == 0.0f) {
            return;
        }
        // vanilla Unbreaking removes each point of tool damage with chance level / (level + 1)
        double expected = (double) tool.damagePerBlock() / (unbreakingLevel(stack) + 1);
        unacked.add(new Break(sequence, slot, tool.damagePerBlock(), expected));
        worstBySlot[slot] += tool.damagePerBlock();
        expectedBySlot[slot] += expected;
    }

    /** Called on the main thread with each ack, after the inventory updates sent ahead of it */
    public void onAck(int sequence) {
        while (!unacked.isEmpty() && unacked.peekFirst().sequence() <= sequence) {
            Break acked = unacked.removeFirst();
            worstBySlot[acked.slot()] -= acked.worst();
            expectedBySlot[acked.slot()] -= acked.expected();
        }
        if (unacked.isEmpty()) {
            Arrays.fill(expectedBySlot, 0); // drop the rounding error the sums pick up
        }
    }

    /**
     * @return damage still to land on the tool in this hotbar slot if no point is spared
     */
    public int worst(int slot) {
        return slot >= 0 && slot < worstBySlot.length ? worstBySlot[slot] : 0;
    }

    /**
     * @return damage the tool in this hotbar slot can expect to still take
     */
    public double expected(int slot) {
        return slot >= 0 && slot < expectedBySlot.length ? expectedBySlot[slot] : 0;
    }

    private static int unbreakingLevel(ItemStack stack) {
        ItemEnchantments enchantments = stack.getEnchantments();
        for (Holder<Enchantment> enchant : enchantments.keySet()) {
            if (enchant.is(Enchantments.UNBREAKING)) {
                return enchantments.getLevel(enchant);
            }
        }
        return 0;
    }
}
