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
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayBlockState;
import baritone.behavior.highway.enums.HighwayState;
import baritone.behavior.highway.enums.LocationType;
import baritone.behavior.highway.enums.ShulkerType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class BuildingHighway extends State {
    public BuildingHighway(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (context.endPos() != null && !context.baritone().getBuilderProcess().isActive() && context.isHighwayEndComplete()) {
            Helper.HELPER.logDirect("Reached the end of the highway at " + context.endPos().toString() + ", stopping.");
            context.baritone().getNetherHighwayBuilderBehavior().stop();
            return;
        }

        if (!context.baritone().getBuilderProcess().isActive()) {
            if (!context.travelTowardsEnd()) {
                Helper.HELPER.logDirect("Restarting builder");
                context.transitionTo(HighwayState.Nothing);
                return;
            }
        } else {
            context.stopTravelTowardsEnd();
        }

        if (context.walkBackTimer() > 120 && context.baritone().getPathingControlManager().mostRecentCommand().isPresent()) {
            context.resetWalkBackTimer();
            if (context.getFarthestGoalDistance(context.baritone().getPathingControlManager().mostRecentCommand().get().goal) > context.settings().highwayMaxLostShulkerSearchDist.value) {
                Helper.HELPER.logDirect("We are walking way too far. Restarting");
                context.transitionTo(HighwayState.Nothing);
                return;
            }
        }

        if (context.getPickCountInventory() < context.settings().highwayPicksThreshold.value) {
            if (context.getShulkerCountInventory(context.picksToUse()) == 0) {
                // No shulker in hand, so this costs a storage trip or a pause: confirm the count
                // is real before committing to it.
                if (!context.thresholdConfirmed("Pick shulker count")) {
                    return;
                }
                if (!context.enderChestHasPickShulks()) {
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
        if (getObsidianCountInventory(context) <= context.settings().highwayObsidianThreshold.value && context.paving()) {
            // A farm with nowhere to put its drops would cycle the box forever: place, loot nothing, mine, repeat.
            if (!context.enderChestFarmCanProgress()) {
                context.baritone().getPathingBehavior().cancelEverything();
                context.pause("Obsidian is under the threshold but the inventory has no room for more, clear some slots and restart.");
                return;
            }
            if (context.getShulkerCountInventory(ShulkerType.EnderChest) == 0) {
                if (!context.thresholdConfirmed("Ender chest shulker count")) {
                    return;
                }
                if (!context.enderChestHasEnderShulks()) {
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

        // Digging: keep loose ender chests topped up for storage access (paving refills them via the obsidian farm above).
        // Cap the threshold below a full stack: a refill tops us up to ~64, and topping fits a single slot (<=64), so a
        // threshold too close to 64 could never be satisfied and would drain every shulker from storage in a loop.
        int enderChestThreshold = Math.min(context.settings().highwayEnderChestsThreshold.value, 56);
        if (!context.paving() && context.getItemCountInventory(Item.getId(Blocks.ENDER_CHEST.asItem())) < enderChestThreshold) {
            if (context.getShulkerCountInventory(ShulkerType.EnderChest) > 0) {
                // We already hold an ender chest shulker: open it and top up. No grab happened, so the stash places a fresh chest.
                if (!context.refillingEnderChests()) {
                    context.setRefillingEnderChests(true);
                    context.setEnderChestAccessLoc(null);
                }
                context.clearThresholdConfirm();
                context.transitionTo(HighwayState.EchestMiningPlaceLocPrep);
                context.baritone().getPathingBehavior().cancelEverything();
                context.resetTimer();
                return;
            }
            if (!context.thresholdConfirmed("Ender chest count")) {
                return;
            }
            if (!context.enderChestHasEnderShulks()) {
                context.baritone().getPathingBehavior().cancelEverything();
                context.pause("Low on ender chests, and none in storage to fetch.");
                return;
            }
            // Fetch a shulker (also tops picks/gapples) from storage, then loop back here to top up
            Helper.HELPER.logDirect("Low on ender chests, fetching an ender chest shulker from storage.");
            context.setRefillingEnderChests(true);
            context.transitionTo(HighwayState.LootEnderChestPlaceLocPrep);
            return;
        }

        if (context.getItemCountInventory(Item.getId(Items.ENCHANTED_GOLDEN_APPLE)) <= context.settings().highwayGapplesThreshold.value) {
            if (context.getShulkerCountInventory(ShulkerType.Gapple) == 0) {
                if (!context.thresholdConfirmed("Gapple count")) {
                    return;
                }
                if (!context.enderChestHasGappleShulks()) {
                    context.baritone().getPathingBehavior().cancelEverything();
                    context.pause("Out of gapples, refill ender chest and inventory and restart.");
                    return;
                }
                Helper.HELPER.logDirect("Out of gapples, fetching a gapple shulker from the ender chest.");
                context.setRefillingGapples(true);
                context.transitionTo(HighwayState.LootEnderChestPlaceLocPrep);
                return;
            }
            context.transitionTo(HighwayState.GappleShulkerPlaceLocPrep);
            context.baritone().getPathingBehavior().cancelEverything();
            context.resetTimer();
            return;
        }

        if (context.refillingGapples()) {
            // A gapple refill cycle got interrupted after the threshold cleared (e.g. the place/loot
            // flow bailed because we already had enough): stash the fetched shulker back, or just
            // drop the flag when there is nothing left to stash.
            if (context.settings().highwayStashGappleShulkers.value && context.getShulkerCountInventory(ShulkerType.Gapple) > 0) {
                context.transitionTo(HighwayState.EnderChestStashPlaceLocPrep);
                context.baritone().getPathingBehavior().cancelEverything();
                context.resetTimer();
                return;
            }
            context.setRefillingGapples(false);
            context.releaseEnderChestAccessLoc();
        }

        if (context.settings().highwayRefillTotems.value && context.getTotemCountInventory() <= context.settings().highwayTotemsThreshold.value) {
            if (context.getShulkerCountInventory(ShulkerType.Totem) == 0) {
                if (!context.enderChestHasTotemShulks()) {
                    // Storage is out of totem shulkers. Unlike picks or obsidian, totems aren't
                    // something the build consumes, so by default we just carry on without them
                    // and stop retrying until the next startBuild resets the flag.
                    if (context.settings().highwayPauseWhenOutOfTotems.value) {
                        if (!context.thresholdConfirmed("Totem count")) {
                            return;
                        }
                        context.baritone().getPathingBehavior().cancelEverything();
                        context.pause("Out of totems, refill ender chest and inventory and restart.");
                        return;
                    }
                } else {
                    if (!context.thresholdConfirmed("Totem count")) {
                        return;
                    }
                    Helper.HELPER.logDirect("Out of totems, fetching a totem shulker from the ender chest.");
                    context.setRefillingTotems(true);
                    context.transitionTo(HighwayState.LootEnderChestPlaceLocPrep);
                    return;
                }
            } else {
                context.transitionTo(HighwayState.TotemShulkerPlaceLocPrep);
                context.baritone().getPathingBehavior().cancelEverything();
                context.resetTimer();
                return;
            }
        }

        if (context.refillingTotems()) {
            // Same safety net as the gapple one above: the refill got interrupted after the
            // threshold cleared, so stash the fetched shulker back or just drop the flag.
            if (context.settings().highwayStashTotemShulkers.value && context.getShulkerCountInventory(ShulkerType.Totem) > 0) {
                context.transitionTo(HighwayState.EnderChestStashPlaceLocPrep);
                context.baritone().getPathingBehavior().cancelEverything();
                context.resetTimer();
                return;
            }
            context.setRefillingTotems(false);
            context.releaseEnderChestAccessLoc();
        }

        if (context.baritone().getBuilderProcess().isActive() && context.baritone().getBuilderProcess().isPaused() && context.timer() >= 360) {
            context.resetTimer();
            context.transitionTo(HighwayState.Nothing);
            return;
        }

        if (context.timer() >= 10 && context.isShulkerOnGround()) {
            Helper.HELPER.logDirect("Detected shulker on the ground, trying to collect.");
            context.transitionTo(HighwayState.ShulkerCollection);
            context.baritone().getPathingBehavior().cancelEverything();
            context.resetTimer();
            return;
        }

        if (context.checkBackTimer() >= 10) {
            context.resetCheckBackTimer();
            context.resetThroughWallDetection();
            // Time to check highway for correctness
            Vec3 direction = new Vec3(context.highwayDirection().getX(), context.highwayDirection().getY(), context.highwayDirection().getZ());

            Vec3 curPosNotOffset = new Vec3(context.playerContext().playerFeet().getX(), context.playerContext().playerFeet().getY(), context.playerContext().playerFeet().getZ());
            Vec3 curPos = new Vec3(curPosNotOffset.x + (context.highwayCheckBackDistance() * -context.highwayDirection().getX()), curPosNotOffset.y, curPosNotOffset.z + (context.highwayCheckBackDistance() * -context.highwayDirection().getZ()));
            BlockPos startCheckPos = context.getClosestPoint(new Vec3(context.originVector().x, context.originVector().y, context.originVector().z), direction, curPos, LocationType.HighwayBuild);
            BlockPos startCheckPosLiq = context.getClosestPoint(new Vec3(context.liqOriginVector().x, context.liqOriginVector().y, context.liqOriginVector().z), direction, curPos, LocationType.ShulkerEchestInteraction);

            // Along-line distance in blocks from the scan start to the player's projected position;
            // both points are on the highway line, so the larger axis delta is the step count for
            // straight and diagonal highways alike
            BlockPos feetClosestPoint = context.getClosestPoint(new Vec3(context.originVector().x, context.originVector().y, context.originVector().z), direction, curPosNotOffset, LocationType.HighwayBuild);
            int distToWantedStart = Math.max(Math.abs(feetClosestPoint.getX() - startCheckPos.getX()), Math.abs(feetClosestPoint.getZ() - startCheckPos.getZ()));

            int tempCheckBackDist = Math.min(context.highwayCheckBackDistance(), distToWantedStart);

            HighwayBlockState curState;
            if (context.baritone().getBuilderProcess().isPaused()) {
                curState = context.isHighwayCorrect(startCheckPos, startCheckPosLiq, tempCheckBackDist + 8, context.settings().highwayRenderLiquidScanArea.value); // Also checking a few blocks in front of us
            } else {
                // Through-wall filling must detect sealed pockets before their cover comes into
                // break reach, so scan a few blocks farther ahead
                int scanAhead = context.liquidThroughWalls() ? 8 : 5;
                curState = context.isHighwayCorrect(startCheckPos, startCheckPosLiq, tempCheckBackDist + scanAhead, context.settings().highwayRenderLiquidScanArea.value);
            }
            if (curState == HighwayBlockState.Liquids) {
                Helper.HELPER.logDirect("Removing liquids.");
                context.transitionTo(HighwayState.LiquidRemovalPrep);
                context.setLiquidPathingCanMine(true);
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

            int blocksCheckDist = Math.max(0, tempCheckBackDist - context.settings().highwayInvalidBlockCheckMargin.value);
            BlockPos blocksCheckPos = startCheckPos;
            BlockPos blocksCheckPosLiq = startCheckPosLiq;
            if (context.invalidBlockFixActive() && context.invalidBlockFixScanStart() != null) {
                blocksCheckPos = context.invalidBlockFixScanStart();
                blocksCheckPosLiq = context.invalidBlockFixScanStartLiq();
                blocksCheckDist = context.invalidBlockFixScanDist();
            }
            curState = context.isHighwayCorrect(blocksCheckPos, blocksCheckPosLiq, blocksCheckDist, false);
            if (curState == HighwayBlockState.Blocks) {
                if (!context.invalidBlockFixActive()) {
                    Helper.HELPER.logDirect("Fixing invalid blocks: " + String.join("; ", context.lastMismatches()));
                    context.startInvalidBlockFix(startCheckPos, startCheckPosLiq, blocksCheckDist);
                    context.transitionTo(HighwayState.Nothing);
                    context.resetTimer();
                    return;
                }
                context.noteInvalidBlockFixProgress(context.lastMismatches());
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
            int shulkerCount = context.getShulkerCountInventory(ShulkerType.Any);
            if (shulkerCount < context.startShulkerCount()) {
                // Lost a shulker somewhere :(
                if (context.maybeStartThiefHunt(HighwayState.BuildingHighway)) {
                    return; // A piglin is carrying it; get it back before resorting to the ground search
                }
                if (context.maybeMineMisplacedShulker(32, 8)) {
                    return; // Inventory desync placed the box into the highway; mine it back instead of searching
                }
                if (context.maybeLostShulkerRelog()) {
                    return;
                }
                Helper.HELPER.logDirect("We lost a shulker somewhere. Going back a maximum of " + context.settings().highwayMaxLostShulkerSearchDist.value + " blocks to look for it.");
                context.transitionTo(HighwayState.ShulkerSearchPrep);
                context.baritone().getPathingBehavior().cancelEverything();
                context.resetTimer();
                return;
            }
            // Nothing missing (or the loss was accepted); the next loss gets a fresh relog attempt
            context.clearLostShulkerRelogAttempt();
            if (shulkerCount > context.startShulkerCount()) {
                Helper.HELPER.logDirect("We picked up a shulker somewhere, updating startShulkerCount from " + context.startShulkerCount() + " to " + shulkerCount);
                context.setStartShulkerCount(shulkerCount);
                context.resetTimer();
                return;
            }
        }

        if (context.timer() >= 10 && (context.playerContext().player().isOnFire() || context.playerContext().player().getFoodData().getFoodLevel() <= 16)) {
            MobEffectInstance fireRest = context.playerContext().player().getEffect(MobEffects.FIRE_RESISTANCE);
            if (fireRest == null || fireRest.getDuration() < context.fireRestMinDuration() || context.playerContext().player().getFoodData().getFoodLevel() <= 16) {
                Helper.HELPER.logDirect("Eating a gapple.");
                context.clearSourceBlocks(); // Should fix occasional crash after eating gapples
                context.transitionTo(HighwayState.LiquidRemovalGapplePrep);
                context.baritone().getInputOverrideHandler().clearAllKeys();
                context.baritone().getPathingBehavior().cancelEverything();
                return;
            }
        }
    }

    private int getObsidianCountInventory(HighwayContext context) {
        return context.getItemCountInventory(Item.getId(Blocks.OBSIDIAN.asItem())) + context.getItemCountInventory(Item.getId(Blocks.CRYING_OBSIDIAN.asItem()));
    }
}
