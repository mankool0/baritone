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


import baritone.behavior.highway.enums.HighwayState;
import baritone.behavior.highway.state.*;
import net.minecraft.world.entity.monster.Shulker;

public class StateFactory {
    public static State getState(HighwayState highwayState) {
        switch (highwayState) {
            case Nothing -> {
                return new Nothing(highwayState);
            }
            case BuildingHighway -> {
                return new BuildingHighway(highwayState);
            }
            case FloatingFixPrep -> {
                return new FloatingFixPrep(highwayState);
            }
            case FloatingFix -> {
                return new FloatingFix(highwayState);
            }
            case LiquidRemovalPrep -> {
                return new LiquidRemovalPrep(highwayState);
            }
            case LiquidRemovalPrepWait -> {
                return new LiquidRemovalPrepWait(highwayState);
            }
            case LiquidRemovalPathingBack -> {
                return new LiquidRemovalPathingBack(highwayState);
            }
            case LiquidRemovalGapplePrep -> {
                return new LiquidRemovalGapplePrep(highwayState);
            }
            case LiquidRemovalGapplePreEat -> {
                return new LiquidRemovalGapplePreEat(highwayState);
            }
            case LiquidRemovalGappleEat -> {
                return new LiquidRemovalGappleEat(highwayState);
            }
            case LiquidRemovalPathing -> {
                return new LiquidRemovalPathing(highwayState);
            }
            case PickaxeShulkerPlaceLocPrep -> {
                return new PickaxeShulkerPlaceLocPrep(highwayState);
            }
            case GoingToPlaceLocPickaxeShulker -> {
                return new GoingToPlaceLocPickaxeShulker(highwayState);
            }
            case PlacingPickaxeShulker -> {
                return new PlacingPickaxeShulker(highwayState);
            }
            case OpeningPickaxeShulker -> {
                return new OpeningPickaxeShulker(highwayState);
            }
            case LootingPickaxeShulker -> {
                return new LootingPickaxeShulker(highwayState);
            }
            case MiningPickaxeShulker -> {
                return new MiningPickaxeShulker(highwayState);
            }
            case CollectingPickaxeShulker -> {
                return new CollectingPickaxeShulker(highwayState);
            }
            case InventoryCleaningPickaxeShulker -> {
                return new InventoryCleaningPickaxeShulker(highwayState);
            }
            case GappleShulkerPlaceLocPrep -> {
                return new GappleShulkerPlaceLocPrep(highwayState);
            }
            case GoingToPlaceLocGappleShulker -> {
                return new GoingToPlaceLocGappleShulker(highwayState);
            }
            case PlacingGappleShulker -> {
                return new PlacingGappleShulker(highwayState);
            }
            case OpeningGappleShulker -> {
                return new OpeningGappleShulker(highwayState);
            }
            case LootingGappleShulker -> {
                return new LootingGappleShulker(highwayState);
            }
            case MiningGappleShulker -> {
                return new MiningGappleShulker(highwayState);
            }
            case CollectingGappleShulker -> {
                return new CollectingGappleShulker(highwayState);
            }
            case InventoryCleaningGappleShulker -> {
                return new InventoryCleaningGappleShulker(highwayState);
            }
            case ShulkerCollection -> {
                return new ShulkerCollection(highwayState);
            }
            case InventoryCleaningShulkerCollection -> {
                return new InventoryCleaningShulkerCollection(highwayState);
            }
            case ShulkerSearchPrep -> {
                return new ShulkerSearchPrep(highwayState);
            }
            case ShulkerSearchPathing -> {
                return new ShulkerSearchPathing(highwayState);
            }
            case LootEnderChestPlaceLocPrep -> {
                return new LootEnderChestPlaceLocPrep(highwayState);
            }
            case GoingToLootEnderChestPlaceLoc -> {
                return new GoingToLootEnderChestPlaceLoc(highwayState);
            }
            case PlacingLootEnderChestSupport -> {
                return new PlacingLootEnderChestSupport(highwayState);
            }
            case PlacingLootEnderChest -> {
                return new PlacingLootEnderChest(highwayState);
            }
            case OpeningLootEnderChest -> {
                return new OpeningLootEnderChest(highwayState);
            }
            case LootingLootEnderChestPicks -> {
                return new LootingLootEnderChestPicks(highwayState);
            }
            case LootingLootEnderChestEnderChests -> {
                return new LootingLootEnderChestEnderChests(highwayState);
            }
            case LootingLootEnderChestGapples -> {
                return new LootingLootEnderChestGapples(highwayState);
            }
            case EchestMiningPlaceLocPrep -> {
                return new EchestMiningPlaceLocPrep(highwayState);
            }
            case GoingToPlaceLocEnderShulker -> {
                return new GoingToPlaceLocEnderShulker(highwayState);
            }
            case PlacingEnderShulker -> {
                return new PlacingEnderShulker(highwayState);
            }
            case OpeningEnderShulker -> {
                return new OpeningEnderShulker(highwayState);
            }
            case LootingEnderShulker -> {
                return new LootingEnderShulker(highwayState);
            }
            case MiningEnderShulker -> {
                return new MiningEnderShulker(highwayState);
            }
            case CollectingEnderShulker -> {
                return new CollectingEnderShulker(highwayState);
            }
            case InventoryCleaningEnderShulker -> {
                return new InventoryCleaningEnderShulker(highwayState);
            }
            case GoingToPlaceLocEnderChest -> {
                return new GoingToPlaceLocEnderChest(highwayState);
            }
            case FarmingEnderChestPrepEchest -> {
                return new FarmingEnderChestPrepEchest(highwayState);
            }
            case FarmingEnderChestPrepPick -> {
                return new FarmingEnderChestPrepPick(highwayState);
            }
            case FarmingEnderChest -> {
                return new FarmingEnderChest(highwayState);
            }
            case FarmingEnderChestSwapBack -> {
                return new FarmingEnderChestSwapBack(highwayState);
            }
            case FarmingEnderChestClear -> {
                return new FarmingEnderChestClear(highwayState);
            }
            case CollectingObsidian -> {
                return new CollectingObsidian(highwayState);
            }
            case InventoryCleaningObsidian -> {
                return new InventoryCleaningObsidian(highwayState);
            }
            case EmptyShulkerPlaceLocPrep -> {
                return new EmptyShulkerPlaceLocPrep(highwayState);
            }
            case GoingToEmptyShulkerPlaceLoc -> {
                return new GoingToEmptyShulkerPlaceLoc(highwayState);
            }
            case PlacingEmptyShulkerSupport -> {
                return new PlacingEmptyShulkerSupport(highwayState);
            }
            case PlacingEmptyShulker -> {
                return new PlacingEmptyShulker(highwayState);
            }
            case BoatRemoval -> {
                return new BoatRemoval(highwayState);
            }
        }
        return null;
    }
}
