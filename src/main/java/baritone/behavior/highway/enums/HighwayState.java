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

package baritone.behavior.highway.enums;

public enum HighwayState {
    Nothing,

    BuildingHighway,

    FloatingFixPrep,
    FloatingFix,

    LiquidRemovalPrep,
    LiquidRemovalPrepWait,
    LiquidRemovalPathingBack,
    LiquidRemovalGapplePrep,
    LiquidRemovalGapplePreEat,
    LiquidRemovalGappleEat,
    LiquidRemovalPathing,
    //LiquidRemovalPlacing,

    PickaxeShulkerPlaceLocPrep,
    GoingToPlaceLocPickaxeShulker,
    PlacingPickaxeShulkerSupport,
    PlacingPickaxeShulker,
    OpeningPickaxeShulker,
    LootingPickaxeShulker,
    MiningPickaxeShulker,
    CollectingPickaxeShulker,
    InventoryCleaningPickaxeShulker,

    GappleShulkerPlaceLocPrep,
    GoingToPlaceLocGappleShulker,
    PlacingGappleShulkerSupport,
    PlacingGappleShulker,
    OpeningGappleShulker,
    LootingGappleShulker,
    MiningGappleShulker,
    CollectingGappleShulker,
    InventoryCleaningGappleShulker,

    ShulkerCollection,
    InventoryCleaningShulkerCollection,

    ShulkerSearchPrep,
    ShulkerSearchPathing,

    LootEnderChestPlaceLocPrep,
    GoingToLootEnderChestPlaceLoc,
    PlacingLootEnderChestSupport,
    PlacingLootEnderChest,
    OpeningLootEnderChest,
    DepositingLootEnderChestDepletedShulkers,
    LootingLootEnderChestPicks,
    LootingLootEnderChestEnderChests,
    LootingLootEnderChestGapples,
    DepositingLootEnderChestDepletedShulkersFinal,

    EchestMiningPlaceLocPrep,
    GoingToPlaceLocEnderShulker,
    PlacingEnderShulkerSupport,
    PlacingEnderShulker,
    OpeningEnderShulker,
    LootingEnderShulker,
    MiningEnderShulker,
    CollectingEnderShulker,
    InventoryCleaningEnderShulker,
    EnderChestStashPlaceLocPrep,
    DepositingStashShulker,
    GoingToPlaceLocEnderChest,
    FarmingEnderChestPrepEchest,
    FarmingEnderChestPrepPick,
    FarmingEnderChest,
    FarmingEnderChestSwapBack,
    FarmingEnderChestClear,
    CollectingObsidian,
    InventoryCleaningObsidian,

    EmptyShulkerPlaceLocPrep,
    GoingToEmptyShulkerPlaceLoc,
    PlacingEmptyShulkerSupport,
    PlacingEmptyShulker,

    BoatRemoval,

    EmergencyGapplePrep,
    EmergencyGapplePreEat,
    EmergencyGappleEat,

    MobCombat,
    MobCombatReturn,

    FallRecovery,

    PortalEscape,

    InQueue,
}
