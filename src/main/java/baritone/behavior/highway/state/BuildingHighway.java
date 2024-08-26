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
import baritone.api.utils.VecUtils;
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
            if (!context.baritone().getBuilderProcess().isActive()) {
                Helper.HELPER.logDirect("Restarting builder");
                context.transitionTo(HighwayState.Nothing);
                return;
            }

            if (context.walkBackTimer() > 120 && context.baritone().getPathingControlManager().mostRecentCommand().isPresent()) {
                context.resetWalkBackTimer();
                if (context.getFarthestGoalDistance(context.baritone().getPathingControlManager().mostRecentCommand().get().goal) > context.settings().highwayMaxLostShulkerSearchDist.value) {
                    Helper.HELPER.logDirect("We are walking way too far. Restarting");
                    context.transitionTo(HighwayState.Nothing);
                    return;
                }
            }

            // TODO: Change shulker threshold from 0 to a customizable value
            if (context.getItemCountInventory(Item.getId(Blocks.OBSIDIAN.asItem())) <= context.settings().highwayObsidianThreshold.value && context.paving()) {
                if (context.getShulkerCountInventory(ShulkerType.EnderChest) == 0) {
                    if (context.repeatCheck()) {
                        if (!context.enderChestHasEnderShulks()) {
                            Helper.HELPER.logDirect("Out of ender chests, refill ender chest and inventory and restart.");
                            context.baritone().getPathingBehavior().cancelEverything();
                            context.setPaused(true);
                            return;
                        }
                        Helper.HELPER.logDirect("Shulker count is under threshold, checking ender chest");
                        context.transitionTo(HighwayState.LootEnderChestPlaceLocPrep);
                    } else {
                        Helper.HELPER.logDirect("Shulker count is under threshold. Player may still be loading. Waiting 120 ticks");
                        context.resetTimer();
                        context.setRepeatCheck(true);
                    }
                    return;
                }
                context.transitionTo(HighwayState.EchestMiningPlaceLocPrep);
                context.baritone().getPathingBehavior().cancelEverything();
                context.resetTimer();
                return;
            }

            if (context.getPickCountInventory() <= context.settings().highwayPicksThreshold.value) {
                if (context.getShulkerCountInventory(context.picksToUse()) == 0) {
                    if (context.repeatCheck()) {
                        if (!context.enderChestHasPickShulks()) {
                            Helper.HELPER.logDirect("Out of picks, refill ender chest and inventory and restart.");
                            context.baritone().getPathingBehavior().cancelEverything();
                            context.setPaused(true);
                            return;
                        }
                        Helper.HELPER.logDirect("Shulker count is under threshold, checking ender chest");
                        context.transitionTo(HighwayState.LootEnderChestPlaceLocPrep);
                    } else {
                        Helper.HELPER.logDirect("Shulker count is under threshold. Player may still be loading. Waiting 120 ticks");
                        context.resetTimer();
                        context.setRepeatCheck(true);
                    }
                    return;
                }
                context.transitionTo(HighwayState.PickaxeShulkerPlaceLocPrep);
                context.baritone().getPathingBehavior().cancelEverything();
                context.resetTimer();
                return;
            }

            if (context.getItemCountInventory(Item.getId(Items.ENCHANTED_GOLDEN_APPLE)) <= context.settings().highwayGapplesThreshold.value) {
                if (context.getShulkerCountInventory(ShulkerType.Gapple) == 0) {
                    Helper.HELPER.logDirect("No more gapples, pausing");
                    context.baritone().getPathingBehavior().cancelEverything();
                    context.setPaused(true);
                    return;
                }
                context.transitionTo(HighwayState.GappleShulkerPlaceLocPrep);
                context.baritone().getPathingBehavior().cancelEverything();
                context.resetTimer();
                return;
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
                // Time to check highway for correctness
                Vec3 direction = new Vec3(context.highwayDirection().getX(), context.highwayDirection().getY(), context.highwayDirection().getZ());

                Vec3 curPosNotOffset = new Vec3(context.playerContext().playerFeet().getX(), context.playerContext().playerFeet().getY(), context.playerContext().playerFeet().getZ());
                // Fix player feet location for diags so we don't check too far ahead
                // +X,+Z and -X,-Z
                if (((context.highwayDirection().getX() == 1 && context.highwayDirection().getZ() == 1) || (context.highwayDirection().getX() == -1 && context.highwayDirection().getZ() == -1))) {
                    curPosNotOffset = new Vec3(curPosNotOffset.x, curPosNotOffset.y, curPosNotOffset.x - 4);
                } else if ((context.highwayDirection().getX() == 1 && context.highwayDirection().getZ() == -1) || (context.highwayDirection().getX() == -1 && context.highwayDirection().getZ() == 1)) {
                    curPosNotOffset = new Vec3(-curPosNotOffset.z - 5, curPosNotOffset.y, curPosNotOffset.z);
                }

                Vec3 curPos = new Vec3(curPosNotOffset.x + (context.highwayCheckBackDistance() * -context.highwayDirection().getX()), curPosNotOffset.y, curPosNotOffset.z + (context.highwayCheckBackDistance() * -context.highwayDirection().getZ()));
                BlockPos startCheckPos = context.getClosestPoint(new Vec3(context.originVector().x, context.originVector().y, context.originVector().z), direction, curPos, LocationType.HighwayBuild);
                BlockPos startCheckPosLiq = context.getClosestPoint(new Vec3(context.liqOriginVector().x, context.liqOriginVector().y, context.liqOriginVector().z), new Vec3(context.highwayDirection().getX(), context.highwayDirection().getY(), context.highwayDirection().getZ()), curPos, LocationType.ShulkerEchestInteraction);


                BlockPos feetClosestPoint = context.getClosestPoint(new Vec3(context.originVector().x, context.originVector().y, context.originVector().z), direction, curPosNotOffset, LocationType.HighwayBuild);
                double distToWantedStart;
                if ((context.highwayDirection().getX() == 1 && context.highwayDirection().getZ() == 1) || (context.highwayDirection().getX() == -1 && context.highwayDirection().getZ() == -1)) {
                    distToWantedStart = Math.abs(Math.abs(context.playerContext().playerFeet().getX()) - Math.abs(startCheckPos.getX()));
                } else if ((context.highwayDirection().getX() == 1 && context.highwayDirection().getZ() == -1) || (context.highwayDirection().getX() == -1 && context.highwayDirection().getZ() == 1)) {
                    distToWantedStart = Math.abs(Math.abs(context.playerContext().playerFeet().getZ()) - Math.abs(startCheckPos.getZ()));
                } else {
                    distToWantedStart = VecUtils.distanceToCenter(feetClosestPoint, startCheckPos.getX(), startCheckPos.getY(), startCheckPos.getZ());
                }

                int tempCheckBackDist = context.highwayCheckBackDistance();
                if (distToWantedStart < tempCheckBackDist) {
                    tempCheckBackDist = (int) distToWantedStart;
                }

                HighwayBlockState curState;
                if (context.baritone().getBuilderProcess().isPaused()) {
                    curState = context.isHighwayCorrect(startCheckPos, startCheckPosLiq, tempCheckBackDist + 8, context.settings().highwayRenderLiquidScanArea.value); // Also checking a few blocks in front of us
                } else {
                    curState = context.isHighwayCorrect(startCheckPos, startCheckPosLiq, tempCheckBackDist + 5, context.settings().highwayRenderLiquidScanArea.value);
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

                curState = context.isHighwayCorrect(startCheckPos, startCheckPosLiq, tempCheckBackDist, false); // Don't check front for blocks as we are probably just mining
                if (curState == HighwayBlockState.Blocks) {
                    Helper.HELPER.logDirect("Fixing invalid blocks.");
                    context.transitionTo(HighwayState.Nothing);
                    context.resetTimer();
                    return;
                }

                // No case for Air because that's what it should be
                //return;
            }

            if (context.timer() >= 10 && context.getShulkerCountInventory(ShulkerType.Any) < context.startShulkerCount()) {
                // Lost a shulker somewhere :(
                Helper.HELPER.logDirect("We lost a shulker somewhere. Going back a maximum of " + context.settings().highwayMaxLostShulkerSearchDist.value + " blocks to look for it.");
                context.transitionTo(HighwayState.ShulkerSearchPrep);
                context.baritone().getPathingBehavior().cancelEverything();
                context.resetTimer();
                return;
            }

            if (context.timer() >= 10 && context.getShulkerCountInventory(ShulkerType.Any) > context.startShulkerCount()) {
                Helper.HELPER.logDirect("We picked up a shulker somewhere, updating startShulkerCount from " + context.startShulkerCount() + " to " + context.getShulkerCountInventory(ShulkerType.Any));
                context.setStartShulkerCount(context.getShulkerCountInventory(ShulkerType.Any));
                context.resetTimer();
                return;
            }

            if (context.timer() >= 10 && (context.playerContext().player().isOnFire() || context.playerContext().player().getFoodData().getFoodLevel() <= 16)) {
                MobEffectInstance fireRest = context.playerContext().player().getEffect(MobEffects.FIRE_RESISTANCE);
                if (fireRest == null || fireRest.getDuration() < context.settings().highwayFireRestMinDuration.value || context.playerContext().player().getFoodData().getFoodLevel() <= 16) {
                    Helper.HELPER.logDirect("Eating a gapple.");
                    context.clearSourceBlocks(); // Should fix occasional crash after eating gapples
                    context.transitionTo(HighwayState.LiquidRemovalGapplePrep);
                    context.baritone().getInputOverrideHandler().clearAllKeys();
                    context.baritone().getPathingBehavior().cancelEverything();
                    return;
                }
            }
    }
}
