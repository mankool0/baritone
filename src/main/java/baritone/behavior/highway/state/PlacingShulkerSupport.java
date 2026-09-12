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

package baritone.behavior.highway.state;

import baritone.api.schematic.WhiteBlackSchematic;
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Blocks;

public class PlacingShulkerSupport extends State {
    private boolean supportBlockNeeded = false;

    public PlacingShulkerSupport(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (!context.baritone().getBuilderProcess().isPaused() && context.baritone().getBuilderProcess().isActive()) {
            return; // Wait for build to complete
        }

        // If build just completed and support was needed, go back to placement location
        if (supportBlockNeeded) {
            context.transitionTo(getPreviousState());
            return;
        }

        if (shouldCancel(context)) {
            context.transitionTo(HighwayState.Nothing);
            return;
        }

        context.baritone().getPathingBehavior().cancelEverything();
        context.settings().buildRepeat.value = new Vec3i(0, 0, 0);
        if (context.playerContext().world().getBlockState(context.placeLoc().below()).getBlock() instanceof AirBlock) {
            if (!context.canBuildSupportBlock()) {
                context.pause("Nothing under the shulker placing spot and no blocks left to build a support with. Move the bot onto solid ground or give it throwaway blocks and restart.");
                return;
            }
            supportBlockNeeded = true;
            WhiteBlackSchematic supportSchem = new WhiteBlackSchematic(1, 1, 1, context.blackListBlocks(), Blocks.NETHERRACK.defaultBlockState(), false, false, true);
            supportSchem.setThrowawayFallback(Blocks.OBSIDIAN.defaultBlockState());
            context.baritone().getBuilderProcess().build("supportBlock", supportSchem, context.placeLoc().below());
            return;
        }

        context.transitionTo(getNextState());
    }

    private boolean shouldCancel(HighwayContext context) {
        return switch (state) {
            case PlacingEmptyShulkerSupport -> context.getShulkerSlot(baritone.behavior.highway.enums.ShulkerType.Empty) == -1;
            case PlacingLootEnderChestSupport -> context.getItemCountInventory(net.minecraft.world.item.Item.getId(net.minecraft.world.level.block.Blocks.ENDER_CHEST.asItem())) == 0;
            case PlacingPickaxeShulkerSupport -> context.getPickCountInventory() >= context.picksToHave() || context.getShulkerSlot(context.picksToUse()) == -1;
            case PlacingGappleShulkerSupport -> context.getItemCountInventory(net.minecraft.world.item.Item.getId(net.minecraft.world.item.Items.ENCHANTED_GOLDEN_APPLE)) >= context.settings().highwayGapplesToHave.value || context.getShulkerSlot(baritone.behavior.highway.enums.ShulkerType.Gapple) == -1;
            case PlacingTotemShulkerSupport -> context.getTotemCountInventory() >= context.totemsToHave() || context.getShulkerSlot(baritone.behavior.highway.enums.ShulkerType.Totem) == -1;
            case PlacingEnderShulkerSupport -> false; // No cancellation condition for ender shulker
            default -> false;
        };
    }

    private HighwayState getPreviousState() {
        return switch (state) {
            case PlacingEmptyShulkerSupport -> HighwayState.GoingToEmptyShulkerPlaceLoc;
            case PlacingLootEnderChestSupport -> HighwayState.GoingToLootEnderChestPlaceLoc;
            case PlacingPickaxeShulkerSupport -> HighwayState.GoingToPlaceLocPickaxeShulker;
            case PlacingGappleShulkerSupport -> HighwayState.GoingToPlaceLocGappleShulker;
            case PlacingTotemShulkerSupport -> HighwayState.GoingToPlaceLocTotemShulker;
            case PlacingEnderShulkerSupport -> HighwayState.GoingToPlaceLocEnderShulker;
            default -> HighwayState.Nothing;
        };
    }

    private HighwayState getNextState() {
        return switch (state) {
            case PlacingEmptyShulkerSupport -> HighwayState.PlacingEmptyShulker;
            case PlacingLootEnderChestSupport -> HighwayState.PlacingLootEnderChest;
            case PlacingPickaxeShulkerSupport -> HighwayState.PlacingPickaxeShulker;
            case PlacingGappleShulkerSupport -> HighwayState.PlacingGappleShulker;
            case PlacingTotemShulkerSupport -> HighwayState.PlacingTotemShulker;
            case PlacingEnderShulkerSupport -> HighwayState.PlacingEnderShulker;
            default -> HighwayState.Nothing;
        };
    }
}