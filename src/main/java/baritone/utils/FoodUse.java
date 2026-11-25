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

import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import baritone.utils.accessor.ILivingEntity;
import baritone.utils.accessor.IMinecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Holding use to eat until the server confirms it, for whatever eats: the highway's eats and the elytra flight's.
 */
public final class FoodUse {

    // Ticks a predicted eat may go without the server confirming it before we press use again
    public static final int EAT_CONFIRM_TICKS = 20;

    private FoodUse() {}

    /**
     * Eats {@code food} for this tick. Call it with no container open; a screen is fine.
     * Vanilla presses a held use key only from handleKeybinds(), which never runs while a screen is
     * open (an unfocused window opens the pause menu), so we press it ourselves. That sets
     * rightClickDelay, so handleKeybinds() won't start a second use this tick. The held key keeps
     * it from releasing ours.
     * The client predicts a use as soon as it sends one, and only the server's using-item flag
     * ends it again. A use the server dropped stays predicted, and nothing sends another. Once a use
     * goes unconfirmed for EAT_CONFIRM_TICKS, release it and leave the key up this tick, so the
     * next tick starts a fresh one.
     * Another mod's auto-switch (a kill aura's) can take the food out of hand between presses, and
     * the press would use whatever it put there instead. The food goes back in hand right before
     * each press, so the slot change goes out with the use.
     *
     * @return whether this tick's press had to put the food back in hand
     */
    public static boolean holdUseToEat(IPlayerContext ctx, Item food) {
        LocalPlayer player = ctx.player();
        boolean putBack = false;
        if (player.isUsingItem()) {
            if (player.getTicksUsingItem() > EAT_CONFIRM_TICKS && !((ILivingEntity) player).isServerUsingItem()) {
                Helper.HELPER.logDiagnostic("Server never confirmed the eat after " + player.getTicksUsingItem() + " ticks, pressing use again.");
                releaseUseToEat(ctx);
                return false;
            }
        } else if (clientLoaded(ctx) && ((IMinecraft) ctx.minecraft()).getRightClickDelay() == 0) {
            // The server drops uses until the client has loaded, and the loading screen no longer stops us pressing
            if (!player.getMainHandItem().is(food)) {
                int slot = hotbarSlotOf(ctx, food);
                if (slot != -1) {
                    Helper.HELPER.logDiagnostic("Something switched the hotbar off the " + food.getName().getString() + " (slot " + player.getInventory().getSelectedSlot() + " instead of " + slot + "), selecting it again before pressing use.");
                    player.getInventory().setSelectedSlot(slot);
                    putBack = true;
                }
            }
            ((IMinecraft) ctx.minecraft()).callStartUseItem();
        }
        ctx.minecraft().options.keyUse.setDown(true);
        return putBack;
    }

    /** The server drops uses until the client has loaded, so an eat can't start before then. */
    public static boolean clientLoaded(IPlayerContext ctx) {
        return ctx.player().connection.hasClientLoaded();
    }

    /** Lets go of an eat. Releasing the key alone does that only through handleKeybinds(), which a screen stops. */
    public static void releaseUseToEat(IPlayerContext ctx) {
        ctx.minecraft().options.keyUse.setDown(false);
        LocalPlayer player = ctx.player();
        if (player != null && player.isUsingItem()) {
            ctx.minecraft().gameMode.releaseUsingItem(player);
        }
    }

    /**
     * @return The first hotbar slot holding {@code item}, -1 for none
     */
    public static int hotbarSlotOf(IPlayerContext ctx, Item item) {
        for (int i = 0; i < 9; i++) {
            if (ctx.player().getInventory().getNonEquipmentItems().get(i).is(item)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * @return The first slot of the main inventory, hotbar first, holding {@code item}, -1 for none
     */
    public static int slotOf(IPlayerContext ctx, Item item) {
        for (int i = 0; i < 36; i++) {
            if (ctx.player().getInventory().getNonEquipmentItems().get(i).is(item)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * @return How many of {@code item} the main inventory holds
     */
    public static int count(IPlayerContext ctx, Item item) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = ctx.player().getInventory().getNonEquipmentItems().get(i);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }
}
