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

import baritone.api.utils.Helper;
import baritone.behavior.highway.EnderChestFarm;
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.HighwayScanner;
import baritone.behavior.highway.State;
import baritone.behavior.highway.Supply;
import baritone.behavior.highway.enums.HighwayBlockState;
import baritone.behavior.highway.enums.HighwayState;
import baritone.behavior.highway.enums.LocationType;
import baritone.behavior.highway.enums.ShulkerType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class BuildingHighway extends State {
    public BuildingHighway(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (context.layout().endPos() != null && !context.baritone().getBuilderProcess().isActive() && context.endTracker().isHighwayEndComplete()) {
            Helper.HELPER.logDirect("Reached the end of the highway at " + context.layout().endPos().toString() + ", stopping.");
            context.baritone().getNetherHighwayBuilderBehavior().stop();
            return;
        }

        if (!context.baritone().getBuilderProcess().isActive()) {
            if (!context.endTracker().travelTowardsEnd()) {
                switch (context.endTracker().endStall()) {
                    case RESTART_BUILDER -> {
                        Helper.HELPER.logDirect("Restarting builder");
                        context.transitionTo(HighwayState.Nothing);
                        return;
                    }
                    case HANDLED -> {
                        return;
                    }
                    case WAIT -> {
                        // the checks below still run with the builder stopped
                    }
                }
            }
        } else {
            context.endTracker().stopTravelTowardsEnd();
            context.endTracker().clearEndStall();
        }

        if (context.walkBackTimer() > 120 && context.baritone().getPathingControlManager().mostRecentCommand().isPresent()) {
            context.resetWalkBackTimer();
            if (context.getFarthestGoalDistance(context.baritone().getPathingControlManager().mostRecentCommand().get().goal) > context.settings().highwayMaxLostShulkerSearchDist.value) {
                Helper.HELPER.logDirect("We are walking way too far. Restarting");
                context.transitionTo(HighwayState.Nothing);
                return;
            }
        }

        // Ahead of the refills: a box the place cycle lost can be the very thing a refill is short
        // of, and each refill holds this state on its threshold confirm for longer than the 10
        // ticks this waits, so placed after them it never ran before the storage trip went out.
        if (context.timer() >= 10) {
            int shulkerCount = context.inventory().getShulkerCountInventory(ShulkerType.Any);
            if (shulkerCount < context.resupply().startShulkerCount()) {
                // Lost a shulker somewhere :(
                if (context.threats().maybeStartThiefHunt(HighwayState.BuildingHighway)) {
                    return; // A piglin is carrying it; get it back before resorting to the ground search
                }
                if (context.containerRecovery().maybeMineMisplacedShulker(32, 8)) {
                    return; // Inventory desync placed the box into the highway; mine it back instead of searching
                }
                if (context.containerRecovery().maybeLostShulkerRelog()) {
                    return;
                }
                Helper.HELPER.logDirect("We lost a shulker somewhere. Going back a maximum of " + context.settings().highwayMaxLostShulkerSearchDist.value + " blocks to look for it.");
                context.transitionTo(HighwayState.ShulkerSearchPrep);
                context.baritone().getPathingBehavior().cancelEverything();
                context.resetTimer();
                return;
            }
            // Nothing missing (or the loss was accepted); the next loss gets a fresh relog attempt
            context.containerRecovery().clearLostShulkerRelogAttempt();
            if (shulkerCount > context.resupply().startShulkerCount()) {
                Helper.HELPER.logDirect("We picked up a shulker somewhere, updating startShulkerCount from " + context.resupply().startShulkerCount() + " to " + shulkerCount);
                context.resupply().setStartShulkerCount(shulkerCount);
                context.resetTimer();
                return;
            }
        }

        if (context.inventory().getPickCountInventory() < context.settings().highwayPicksThreshold.value) {
            if (context.inventory().getShulkerCountInventory(context.resupply().picksToUse()) == 0) {
                // No shulker in hand, so this costs a storage trip or a pause: confirm the count
                // is real before committing to it.
                if (!context.sync().thresholdConfirmed("Pick shulker count")) {
                    return;
                }
                if (!context.resupply().enderChestHasPickShulks()) {
                    context.baritone().getPathingBehavior().cancelEverything();
                    context.pause("Out of picks, refill ender chest and inventory and restart.");
                    return;
                }
                Helper.HELPER.logDirect("Shulker count is under threshold, checking ender chest");
                context.transitionTo(HighwayState.LootEnderChestPlaceLocPrep);
                return;
            }
            context.transitionTo(HighwayState.PickaxeShulkerPlaceLocPrep);
            context.baritone().getPathingBehavior().cancelEverything();
            context.resetTimer();
            return;
        }

        // TODO: Change shulker threshold from 0 to a customizable value
        int obsidian = context.inventory().getObsidianCountInventory();
        int runNeed = obsidian <= context.settings().highwayObsidianThreshold.value && context.layout().paving() ? context.farm().runObsidianNeed(obsidian) : -1;
        if (obsidian <= context.settings().highwayObsidianThreshold.value && context.layout().paving() && (runNeed < 0 || obsidian < runNeed)) {
            int runChests = runNeed < 0 ? -1 : EnderChestFarm.chestsForObsidian(runNeed, obsidian);
            boolean looseChests = context.farm().looseChestsCoverRun(runChests);
            boolean fetchShulker = !looseChests && context.inventory().getShulkerCountInventory(ShulkerType.EnderChest) == 0;
            // The count reads near zero for a tick or two while our last stack is swapped onto the
            // hotbar, so a jump under the threshold is confirmed first. One confirm covers the whole
            // decision: two in a row would keep resetting each other's key. A count spent down to the
            // threshold acts at once, or the builder paves the rest away while the confirm runs.
            if (!context.sync().inventorySynced()
                    || (context.sync().obsidianDroppedSuddenly() && !context.sync().thresholdConfirmed(fetchShulker ? "Ender chest shulker count" : "Obsidian count"))) {
                return;
            }
            // A farm with nowhere to put its drops would cycle the box forever: place, loot nothing, mine, repeat.
            if (!context.farm().enderChestFarmCanProgress()) {
                context.baritone().getPathingBehavior().cancelEverything();
                context.pause("Obsidian is under the threshold but the inventory has no room for more, clear some slots and restart.");
                return;
            }
            context.farm().setFarmRunChests(runChests);
            context.farm().setFarmLooseChests(looseChests);
            if (context.farm().farmLooseChests()) {
                Helper.HELPER.logDirect("The rest of the run needs " + runNeed + " obsidian and we have " + obsidian + ", farming " + runChests + " loose ender chests");
            } else if (fetchShulker) {
                if (!context.resupply().enderChestHasEnderShulks()) {
                    context.baritone().getPathingBehavior().cancelEverything();
                    context.pause("Out of ender chests, refill ender chest and inventory and restart.");
                    return;
                }
                Helper.HELPER.logDirect("Shulker count is under threshold, checking ender chest");
                context.transitionTo(HighwayState.LootEnderChestPlaceLocPrep);
                return;
            }
            context.transitionTo(HighwayState.EchestMiningPlaceLocPrep);
            context.baritone().getPathingBehavior().cancelEverything();
            context.resetTimer();
            return;
        }
        context.sync().clearThresholdConfirm("Obsidian count");
        context.sync().clearThresholdConfirm("Ender chest shulker count");
        context.sync().clearObsidianDrop();

        // Digging: keep loose ender chests topped up for storage access (paving refills them via the obsidian farm above).
        // Cap the threshold below a full stack: a refill tops us up to ~64, and topping fits a single slot (<=64), so a
        // threshold too close to 64 could never be satisfied and would drain every shulker from storage in a loop.
        int enderChestThreshold = Math.min(context.settings().highwayEnderChestsThreshold.value, 56);
        if (!context.layout().paving() && context.inventory().getItemCountInventory(Item.getId(Blocks.ENDER_CHEST.asItem())) < enderChestThreshold) {
            if (context.inventory().getShulkerCountInventory(ShulkerType.EnderChest) > 0) {
                topUpEnderChests(context);
                return;
            }
            if (!context.sync().thresholdConfirmed("Ender chest count")) {
                return;
            }
            if (!context.resupply().enderChestHasEnderShulks()) {
                context.baritone().getPathingBehavior().cancelEverything();
                context.pause("Low on ender chests, and none in storage to fetch.");
                return;
            }
            Helper.HELPER.logDirect("Low on ender chests, fetching an ender chest shulker from storage.");
            fetchEnderChestShulker(context);
            return;
        }

        if (farmForSupportBlocks(context)) {
            return;
        }

        for (Supply supply : Supply.values()) {
            if (supply.enabled(context.settings()) && supply.count(context) <= supply.threshold(context.settings())) {
                if (context.inventory().getShulkerCountInventory(supply.shulkerType()) > 0) {
                    context.resupply().setActiveSupply(supply);
                    context.transitionTo(HighwayState.SupplyShulkerPlaceLocPrep);
                    context.baritone().getPathingBehavior().cancelEverything();
                    context.resetTimer();
                    return;
                }
                // With storage out of its shulkers, a supply the build can do without (totems,
                // golden carrots) is just gone without, and stops costing trips until the next
                // startBuild resets the flag
                boolean stocked = context.resupply().enderChestHasShulks(supply);
                if (stocked || supply.pausesWhenStorageEmpty(context.settings())) {
                    // No shulker in hand, so this costs a storage trip or a pause: confirm the count
                    // is real before committing to it
                    if (!context.sync().thresholdConfirmed(supply.countKey())) {
                        return;
                    }
                    if (!stocked) {
                        context.baritone().getPathingBehavior().cancelEverything();
                        context.pause("Out of " + supply.plural() + ", refill ender chest and inventory and restart.");
                        return;
                    }
                    Helper.HELPER.logDirect("Low on " + supply.plural() + ", fetching a " + supply.noun() + " shulker from the ender chest.");
                    context.resupply().setRefilling(supply, true);
                    context.transitionTo(HighwayState.LootEnderChestPlaceLocPrep);
                    return;
                }
            }

            boolean stashCarried = supply.stash(context.settings()) && context.inventory().getShulkerCountInventory(supply.shulkerType()) > 0;
            if (stashCarried && !context.resupply().refilling(supply) && supply.enabled(context.settings())
                    && context.resupply().canStashCarriedShulker()
                    && context.inventory().getItemCountInventory(Item.getId(Blocks.ENDER_CHEST.asItem())) > 0) {
                // Stash mode keeps its shulkers in storage, yet we carry one with nothing to top up: a
                // stash leg went without it (deposited nothing, found nowhere to set the chest down)
                // or a restart dropped the refill flag mid-cycle. Put it back now, or it rides along
                // until it's empty. No chest is remembered with no leg latched, so this sets one down.
                Helper.HELPER.logDirect("Carrying a " + supply.noun() + " shulker that belongs in storage, putting it back.");
                context.resupply().setRefilling(supply, true); // names the shulker DepositingStashShulker puts away
            }
            if (context.resupply().refilling(supply)) {
                // A refill cycle got interrupted after the threshold cleared (e.g. the place/loot
                // flow bailed because we already had enough): stash the fetched shulker back, or just
                // drop the flag when there is nothing left to stash.
                if (stashCarried) {
                    context.transitionTo(HighwayState.EnderChestStashPlaceLocPrep);
                    context.baritone().getPathingBehavior().cancelEverything();
                    context.resetTimer();
                    return;
                }
                context.resupply().setRefilling(supply, false);
                context.resupply().releaseEnderChestAccessLoc();
            }
        }

        if (context.baritone().getBuilderProcess().isActive() && context.baritone().getBuilderProcess().isPaused() && context.timer() >= 360) {
            context.resetTimer();
            context.transitionTo(HighwayState.Nothing);
            return;
        }

        if (context.timer() >= 10 && context.inventory().isShulkerOnGround()) {
            Helper.HELPER.logDirect("Detected shulker on the ground, trying to collect.");
            context.transitionTo(HighwayState.ShulkerCollection);
            context.baritone().getPathingBehavior().cancelEverything();
            context.resetTimer();
            return;
        }

        if (context.checkBackTimer() >= 10) {
            context.resetCheckBackTimer();
            context.liquidRemoval().resetThroughWallDetection();
            // Time to check highway for correctness
            Vec3 direction = new Vec3(context.layout().highwayDirection().getX(), context.layout().highwayDirection().getY(), context.layout().highwayDirection().getZ());

            Vec3 curPosNotOffset = new Vec3(context.playerContext().playerFeet().getX(), context.playerContext().playerFeet().getY(), context.playerContext().playerFeet().getZ());
            // Back up the scan in slices, not in world blocks: the scan length below is a slice
            // count, and on an angled pattern a block of the minor axis is several slices
            BlockPos startCheckPos = context.layout().sliceAlongLine(context.layout().originVector(), curPosNotOffset, -context.layout().highwayCheckBackDistance(), LocationType.HighwayBuild);
            BlockPos startCheckPosLiq = context.layout().sliceAlongLine(context.layout().liqOriginVector(), curPosNotOffset, -context.layout().highwayCheckBackDistance(), LocationType.ShulkerEchestInteraction);

            // Slice count from the scan start to the player's projected position; both points
            // are anchors on the highway path, so this is exact for straight, diagonal and
            // pattern highways alike
            BlockPos feetClosestPoint = context.layout().getClosestPoint(new Vec3(context.layout().originVector().x, context.layout().originVector().y, context.layout().originVector().z), direction, curPosNotOffset, LocationType.HighwayBuild);
            int distToWantedStart = Math.abs(context.layout().stepsAlongHighway(startCheckPos, feetClosestPoint));

            int tempCheckBackDist = Math.min(context.layout().highwayCheckBackDistance(), distToWantedStart);

            // Also check a few blocks in front of us, farther when through-wall filling is on: it
            // must detect sealed pockets before their cover comes into break reach
            HighwayScanner.ScanFront front = context.scanner().scanAheadFront(context.baritone().getBuilderProcess().isPaused());
            HighwayBlockState curState = context.scanner().isHighwayCorrect(startCheckPos, startCheckPosLiq, tempCheckBackDist + front.slicesAhead(), front, context.settings().highwayRenderLiquidScanArea.value);
            if (curState == HighwayBlockState.Liquids) {
                Helper.HELPER.logDirect("Removing liquids.");
                context.transitionTo(HighwayState.LiquidRemovalPrep);
                context.liquidRemoval().setLiquidPathingCanMine(true);
                context.resetTimer();
                return;
            }

            if (curState == HighwayBlockState.Boat) {
                Helper.HELPER.logDirect("Found a boat, trying to remove blocks around and under it.");
                context.transitionTo(HighwayState.BoatRemoval);
                context.baritone().getPathingBehavior().cancelEverything();
                context.resetTimer();
                return;
            }

            if (curState == HighwayBlockState.Crystal) {
                Helper.HELPER.logDirect("Found an end crystal in the way at " + context.scanner().crystalLocation().toShortString() + ", breaking it from behind cover.");
                context.transitionTo(HighwayState.CrystalRemoval);
                context.baritone().getPathingBehavior().cancelEverything();
                context.resetTimer();
                return;
            }

            int blocksCheckDist = Math.max(0, tempCheckBackDist - context.settings().highwayInvalidBlockCheckMargin.value);
            BlockPos blocksCheckPos = startCheckPos;
            BlockPos blocksCheckPosLiq = startCheckPosLiq;
            if (context.invalidBlockFixActive() && context.invalidBlockFixScanStart() != null) {
                blocksCheckPos = context.invalidBlockFixScanStart();
                blocksCheckPosLiq = context.invalidBlockFixScanStartLiq();
                blocksCheckDist = context.invalidBlockFixScanDist();
            }
            curState = context.scanner().isHighwayCorrect(blocksCheckPos, blocksCheckPosLiq, blocksCheckDist, false);
            if (curState == HighwayBlockState.Blocks) {
                if (!context.invalidBlockFixActive()) {
                    Helper.HELPER.logDirect("Fixing invalid blocks: " + String.join("; ", context.scanner().lastMismatches()));
                    context.startInvalidBlockFix(startCheckPos, startCheckPosLiq, blocksCheckDist);
                    context.transitionTo(HighwayState.Nothing);
                    context.resetTimer();
                    return;
                }
                context.noteInvalidBlockFixProgress(context.scanner().lastMismatches());
                if (context.invalidBlockFixStalled()) {
                    Helper.HELPER.logDirect("Invalid block fix stalled, restarting builder.");
                    context.startInvalidBlockFix(context.invalidBlockFixScanStart(), context.invalidBlockFixScanStartLiq(), context.invalidBlockFixScanDist());
                    context.transitionTo(HighwayState.Nothing);
                    context.resetTimer();
                    return;
                }
            } else {
                context.clearInvalidBlockFix();
            }

            // No case for Air because that's what it should be
            //return;
        }

        if (context.timer() >= 10) {
            int food = context.playerContext().player().getFoodData().getFoodLevel();
            MobEffectInstance fireRest = context.playerContext().player().getEffect(MobEffects.FIRE_RESISTANCE);
            boolean burning = context.playerContext().player().isOnFire() && (fireRest == null || fireRest.getDuration() < context.eating().fireRestMinDuration());
            // Golden carrots feed us far more per eat; gapples are kept for fire, lava and low health
            boolean carrots = context.eating().canEatGoldenCarrots();
            if (burning || !carrots && food <= 16) {
                Helper.HELPER.logDirect("Eating a gapple.");
                context.liquidRemoval().clearSourceBlocks(); // Should fix occasional crash after eating gapples
                context.transitionTo(HighwayState.LiquidRemovalGapplePrep);
                context.baritone().getInputOverrideHandler().clearAllKeys();
                context.baritone().getPathingBehavior().cancelEverything();
                return;
            }
            if (carrots && food <= context.eating().goldenCarrotEatFoodThreshold()) {
                Helper.HELPER.logDirect("Eating a golden carrot.");
                context.eating().setGoldenCarrotEatReturnState(HighwayState.Nothing);
                context.transitionTo(HighwayState.GoldenCarrotEatPrep);
                context.baritone().getInputOverrideHandler().clearAllKeys();
                context.baritone().getPathingBehavior().cancelEverything();
                return;
            }
        }
    }

    /** We already hold an ender chest shulker: open it and top up. No grab happened, so the stash places a fresh chest. */
    private static void topUpEnderChests(HighwayContext context) {
        if (!context.resupply().refillingEnderChests()) {
            context.resupply().setRefillingEnderChests(true);
            context.resupply().setEnderChestAccessLoc(null);
        }
        context.farm().setFarmLooseChests(false); // a support farm cut short would turn this top-up into a farm
        context.sync().clearThresholdConfirm();
        context.transitionTo(HighwayState.EchestMiningPlaceLocPrep);
        context.baritone().getPathingBehavior().cancelEverything();
        context.resetTimer();
    }

    /** Fetch a shulker (also tops picks/gapples) from storage, then loop back here to top up */
    private static void fetchEnderChestShulker(HighwayContext context) {
        context.resupply().setRefillingEnderChests(true);
        context.farm().setFarmLooseChests(false);
        context.transitionTo(HighwayState.LootEnderChestPlaceLocPrep);
    }

    /**
     * Digging with no throwaway, netherrack or obsidian left: the support rows have nothing to be
     * built from, and the builder parks on missing materials. Break a few loose ender chests into
     * the obsidian those rows fall back to, past the ones kept for storage trips. With none to
     * spare, top them up from an ender chest shulker first. Returns whether this took the tick.
     */
    private static boolean farmForSupportBlocks(HighwayContext context) {
        int toFarm = context.settings().highwayDiggingEnderChestsToFarm.value;
        if (context.layout().paving() || toFarm <= 0 || context.inventory().canBuildSupportBlock()) {
            context.sync().clearThresholdConfirm("Support block count");
            context.farm().clearSupportFarmBlocked();
            return false;
        }
        int chests = context.inventory().getItemCountInventory(Item.getId(Blocks.ENDER_CHEST.asItem()));
        int kept = Math.max(context.settings().highwayEnderChestsToKeep.value, Math.min(context.settings().highwayEnderChestsThreshold.value, 56));
        boolean holdShulker = context.inventory().getShulkerCountInventory(ShulkerType.EnderChest) > 0;
        String blocked = null;
        if (!context.inventory().hasNonSilkPick()) {
            blocked = "no pickaxe without Silk Touch to break ender chests with";
        } else if (context.farm().obsidianRoomInventory(0) < 8) {
            blocked = "no inventory room for the obsidian [" + context.farm().obsidianRoomBreakdown() + "]";
        } else if (chests <= kept && (context.resupply().enderChestTopUpTarget() <= kept
                || !holdShulker && (chests == 0 || !context.resupply().enderChestHasEnderShulks()))) {
            // A top-up that can't lift the count past the kept chests would fetch shulker after shulker
            blocked = "all " + chests + " ender chests are kept for storage trips and there are none to top up from";
        }
        if (blocked != null) {
            if (context.farm().supportFarmBlockedLogDue()) {
                Helper.HELPER.logDirect("Out of blocks to build supports with and can't farm obsidian: " + blocked);
            }
            context.sync().clearThresholdConfirm("Support block count");
            return false;
        }
        // A pickup or a stack swapped onto the hotbar can read as none for a tick or two
        if (!context.sync().thresholdConfirmed("Support block count")) {
            return true;
        }
        if (chests <= kept) {
            if (holdShulker) {
                Helper.HELPER.logDirect("Out of blocks to build supports with and no ender chests to spare, topping up from our ender chest shulker first.");
                topUpEnderChests(context);
            } else {
                Helper.HELPER.logDirect("Out of blocks to build supports with and no ender chests to spare, fetching an ender chest shulker from storage.");
                fetchEnderChestShulker(context);
            }
            return true;
        }
        toFarm = Math.min(toFarm, chests - kept);
        Helper.HELPER.logDirect("Out of blocks to build supports with, farming " + toFarm + " ender chests into obsidian.");
        context.farm().setFarmRunChests(toFarm);
        context.farm().setFarmLooseChests(true);
        context.transitionTo(HighwayState.EchestMiningPlaceLocPrep);
        context.baritone().getPathingBehavior().cancelEverything();
        context.resetTimer();
        return true;
    }
}
