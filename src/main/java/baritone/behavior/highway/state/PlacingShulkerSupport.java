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

import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.AirBlock;

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
            if (!context.inventory().canBuildSupportBlock()) {
                context.pause("Nothing under the shulker placing spot and no blocks left to build a support with. Move the bot onto solid ground or give it throwaway blocks and restart.");
                return;
            }
            supportBlockNeeded = true;
            context.shulkerPlacement().buildSupportBlock(context.placeLoc().below());
            return;
        }

        if (state == HighwayState.PlacingEnderShulkerSupport && context.farm().farmLooseChests()) {
            context.transitionTo(HighwayState.GoingToPlaceLocEnderChest);
            return;
        }
        context.transitionTo(getNextState());
    }

    private boolean shouldCancel(HighwayContext context) {
        return switch (state) {
            case PlacingEmptyShulkerSupport -> context.inventory().getShulkerSlot(baritone.behavior.highway.enums.ShulkerType.Empty) == -1;
            case PlacingLootEnderChestSupport -> context.inventory().getItemCountInventory(net.minecraft.world.item.Item.getId(net.minecraft.world.level.block.Blocks.ENDER_CHEST.asItem())) == 0;
            case PlacingPickaxeShulkerSupport -> context.inventory().getPickCountInventory() >= context.resupply().picksToHave() || context.inventory().getShulkerSlot(context.resupply().picksToUse()) == -1;
            case PlacingSupplyShulkerSupport -> context.resupply().activeSupply().count(context) >= context.resupply().activeSupply().toHave(context) || context.inventory().getShulkerSlot(context.resupply().activeSupply().shulkerType()) == -1;
            case PlacingEnderShulkerSupport -> false; // No cancellation condition for ender shulker
            default -> false;
        };
    }

    private HighwayState getPreviousState() {
        return switch (state) {
            case PlacingEmptyShulkerSupport -> HighwayState.GoingToEmptyShulkerPlaceLoc;
            case PlacingLootEnderChestSupport -> HighwayState.GoingToLootEnderChestPlaceLoc;
            case PlacingPickaxeShulkerSupport -> HighwayState.GoingToPlaceLocPickaxeShulker;
            case PlacingSupplyShulkerSupport -> HighwayState.GoingToPlaceLocSupplyShulker;
            case PlacingEnderShulkerSupport -> HighwayState.GoingToPlaceLocEnderShulker;
            default -> HighwayState.Nothing;
        };
    }

    private HighwayState getNextState() {
        return switch (state) {
            case PlacingEmptyShulkerSupport -> HighwayState.PlacingEmptyShulker;
            case PlacingLootEnderChestSupport -> HighwayState.PlacingLootEnderChest;
            case PlacingPickaxeShulkerSupport -> HighwayState.PlacingPickaxeShulker;
            case PlacingSupplyShulkerSupport -> HighwayState.PlacingSupplyShulker;
            case PlacingEnderShulkerSupport -> HighwayState.PlacingEnderShulker;
            default -> HighwayState.Nothing;
        };
    }
}