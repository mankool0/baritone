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

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.pathing.goals.*;
import baritone.api.schematic.CompositeSchematic;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.WhiteBlackSchematic;
import baritone.api.utils.*;
import baritone.api.utils.Rotation;
import baritone.behavior.highway.enums.HighwayBlockState;
import baritone.behavior.highway.enums.HighwayState;
import baritone.behavior.highway.enums.ShulkerType;
import baritone.behavior.highway.enums.LocationType;
import baritone.pathing.movement.MovementHelper;
import baritone.process.BuilderProcess;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class HighwayContext {
    public static final List<Block> blackList = Arrays.asList(Blocks.ENDER_CHEST, Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.CRAFTING_TABLE, Blocks.ANVIL, Blocks.BREWING_STAND, Blocks.HOPPER,
            Blocks.DROPPER, Blocks.DISPENSER, Blocks.OAK_TRAPDOOR, Blocks.SPRUCE_TRAPDOOR, Blocks.BIRCH_TRAPDOOR, Blocks.JUNGLE_TRAPDOOR, Blocks.ACACIA_TRAPDOOR, Blocks.DARK_OAK_TRAPDOOR, Blocks.MANGROVE_TRAPDOOR, Blocks.ENCHANTING_TABLE);
    public static final List<Block> shulkerBlockList = Arrays.asList(Blocks.WHITE_SHULKER_BOX, Blocks.ORANGE_SHULKER_BOX, Blocks.MAGENTA_SHULKER_BOX, Blocks.LIGHT_BLUE_SHULKER_BOX,
            Blocks.YELLOW_SHULKER_BOX, Blocks.LIME_SHULKER_BOX, Blocks.PINK_SHULKER_BOX, Blocks.GRAY_SHULKER_BOX, Blocks.LIGHT_GRAY_SHULKER_BOX, Blocks.CYAN_SHULKER_BOX, Blocks.PURPLE_SHULKER_BOX,
            Blocks.BLUE_SHULKER_BOX, Blocks.BROWN_SHULKER_BOX, Blocks.GREEN_SHULKER_BOX, Blocks.RED_SHULKER_BOX, Blocks.BLACK_SHULKER_BOX, Blocks.SHULKER_BOX);
    public static final List<ItemLike> shulkerItemList = Arrays.asList(Items.WHITE_SHULKER_BOX, Items.ORANGE_SHULKER_BOX, Items.MAGENTA_SHULKER_BOX, Items.LIGHT_BLUE_SHULKER_BOX,
            Items.YELLOW_SHULKER_BOX, Items.LIME_SHULKER_BOX, Items.PINK_SHULKER_BOX, Items.GRAY_SHULKER_BOX, Items.LIGHT_GRAY_SHULKER_BOX, Items.CYAN_SHULKER_BOX, Items.PURPLE_SHULKER_BOX,
            Items.BLUE_SHULKER_BOX, Items.BROWN_SHULKER_BOX, Items.GREEN_SHULKER_BOX, Items.RED_SHULKER_BOX, Items.BLACK_SHULKER_BOX, Items.SHULKER_BOX);
    public static final List<ItemLike> validPicksList = Arrays.asList(Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE);
    private final Baritone baritone;
    private final IPlayerContext playerContext;
    private final ArrayList<Integer> usefulSlots = new ArrayList<>(Arrays.asList(0, 1, 2, 7)); // Don't try to put into these slots

    public ArrayList<BlockPos> sourceBlocks() {
        return sourceBlocks;
    }

    private final ArrayList<BlockPos> sourceBlocks = new ArrayList<>();
    private final int highwayCheckBackDistance = 32;
    private final List<BlockState> approxPlaceable = new ArrayList<>() {};

    public Lock renderLockLiquid() {
        return renderLockLiquid;
    }

    public Lock renderLockBuilding() {
        return renderLockBuilding;
    }

    private final Lock renderLockLiquid = new ReentrantLock();
    private final Lock renderLockBuilding = new ReentrantLock();

    public ArrayList<BlockPos> renderBlocksLiquid() {
        return renderBlocksLiquid;
    }

    public HashMap<BlockPos, Color> renderBlocksBuilding() {
        return renderBlocksBuilding;
    }

    private final ArrayList<BlockPos> renderBlocksLiquid = new ArrayList<>();
    private final HashMap<BlockPos, Color> renderBlocksBuilding = new HashMap<>();

    public Rotation floatingFixReachablesRemoveFirst() {
        if (!floatingFixReachables.isEmpty()) {
            return floatingFixReachables.removeFirst();
        }
        return null;
    }

    private final ArrayList<Rotation> floatingFixReachables = new ArrayList<>();
    private final List<BlockState> blackListBlocks = Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.LAVA.defaultBlockState(), Blocks.FIRE.defaultBlockState(), Blocks.BROWN_MUSHROOM.defaultBlockState(), Blocks.RED_MUSHROOM.defaultBlockState(), Blocks.MAGMA_BLOCK.defaultBlockState(), Blocks.SOUL_SAND.defaultBlockState(), Blocks.SOUL_SOIL.defaultBlockState());
    private Settings settings = BaritoneAPI.getSettings();
    private State currentState;
    private CompositeSchematic schematic;
    private WhiteBlackSchematic liqCheckSchem;
    private BetterBlockPos originBuild;
    private BetterBlockPos firstStartingPos;
    private Vec3 originVector = new Vec3(0, 0, 0);
    private Vec3 liqOriginVector = new Vec3(0, 0, 0);
    private Vec3 backPathOriginVector = new Vec3(0, 0, 0);
    private Vec3 eChestEmptyShulkOriginVector = new Vec3(0, 0, 0);
    private Vec3i highwayDirection = new Vec3i(1, 0, -1);
    private boolean paving = false;
    private boolean paused = false;
    private boolean cursorStackNonEmpty = false;

    public BlockPos placeLoc() {
        return placeLoc;
    }

    public void setPlaceLoc(BlockPos placeLoc) {
        this.placeLoc = placeLoc;
    }

    private BlockPos placeLoc;
    private int picksToHave = 5;
    private boolean enderChestHasPickShulks = true;
    private boolean enderChestHasEnderShulks = true;
    private boolean repeatCheck = false;
    private ShulkerType picksToUse;
    private BetterBlockPos cachedPlayerFeet = null;
    private int startShulkerCount = 0;

    public void clearSourceBlocks() {
        sourceBlocks.clear();
    }

    public void clearFloatingFixReachables() {
        floatingFixReachables.clear();
    }

    public void addFloatingFixReachables(Rotation rotation) {
        floatingFixReachables.add(rotation);
    }

    public boolean liquidPathingCanMine() {
        return liquidPathingCanMine;
    }

    public void setLiquidPathingCanMine(boolean liquidPathingCanMine) {
        this.liquidPathingCanMine = liquidPathingCanMine;
    }

    private boolean liquidPathingCanMine = true;
    private int timer = 0;

    public int walkBackTimer() {
        return walkBackTimer;
    }

    private int walkBackTimer = 0;
    private int checkBackTimer = 0;
    private int stuckTimer = 0;
    private float cachedHealth = 0.0f;

    public boolean instantMineActivated() {
        return instantMineActivated;
    }

    public void setInstantMineActivated(boolean instantMineActivated) {
        this.instantMineActivated = instantMineActivated;
    }

    public boolean instantMinePlace() {
        return instantMinePlace;
    }

    public void setInstantMinePlace(boolean instantMinePlace) {
        this.instantMinePlace = instantMinePlace;
    }

    private boolean instantMineActivated = false;
    private boolean instantMinePacketCancel = false;

    public BlockPos instantMineLastBlock() {
        return instantMineLastBlock;
    }

    public void setInstantMineLastBlock(BlockPos instantMineLastBlock) {
        this.instantMineLastBlock = instantMineLastBlock;
    }

    private BlockPos instantMineLastBlock;

    public Direction instantMineDirection() {
        return instantMineDirection;
    }

    public void setInstantMineDirection(Direction instantMineDirection) {
        this.instantMineDirection = instantMineDirection;
    }

    private Direction instantMineDirection;

    public Item instantMineOriginalOffhandItem() {
        return instantMineOriginalOffhandItem;
    }

    public void setInstantMineOriginalOffhandItem(Item instantMineOriginalOffhandItem) {
        this.instantMineOriginalOffhandItem = instantMineOriginalOffhandItem;
    }

    private Item instantMineOriginalOffhandItem;
    private boolean instantMinePlace = true;

    public void setBoatLocation(BlockPos boatLocation) {
        this.boatLocation = boatLocation;
    }

    private BlockPos boatLocation = null;

    public boolean boatHasPassenger() {
        return boatHasPassenger;
    }

    public void setBoatHasPassenger(boolean boatHasPassenger) {
        this.boatHasPassenger = boatHasPassenger;
    }

    private boolean boatHasPassenger = false;
    public HighwayContext(Baritone baritone) {
        this.baritone = baritone;
        this.playerContext = baritone.getPlayerContext();
        currentState = StateFactory.getState(HighwayState.Nothing);
    }

    public Settings settings() {
        return settings;
    }

    public Baritone baritone() {
        return baritone;
    }

    public IPlayerContext playerContext() {
        return playerContext;
    }

    public BetterBlockPos originBuild() {
        return originBuild;
    }

    public Vec3 originVector() {
        return originVector;
    }

    public Vec3i highwayDirection() {
        return highwayDirection;
    }

    public State currentState() {
        return currentState;
    }

    public CompositeSchematic schematic() {
        return schematic;
    }

    public void setSchematic(CompositeSchematic schematic) {
        this.schematic = schematic;
    }

    public void setOriginBuild(BetterBlockPos originBuild) {
        this.originBuild = originBuild;
    }

    public BetterBlockPos firstStartingPos() {
        return firstStartingPos;
    }

    public void setFirstStartingPos(BetterBlockPos firstStartingPos) {
        this.firstStartingPos = firstStartingPos;
    }

    public void setOriginVector(Vec3 originVector) {
        this.originVector = originVector;
    }

    public Vec3 liqOriginVector() {
        return liqOriginVector;
    }

    public void setLiqOriginVector(Vec3 liqOriginVector) {
        this.liqOriginVector = liqOriginVector;
    }

    public Vec3 backPathOriginVector() {
        return backPathOriginVector;
    }

    public void setBackPathOriginVector(Vec3 backPathOriginVector) {
        this.backPathOriginVector = backPathOriginVector;
    }

    public Vec3 eChestEmptyShulkOriginVector() {
        return eChestEmptyShulkOriginVector;
    }

    public void seteChestEmptyShulkOriginVector(Vec3 eChestEmptyShulkOriginVector) {
        this.eChestEmptyShulkOriginVector = eChestEmptyShulkOriginVector;
    }

    public WhiteBlackSchematic liqCheckSchem() {
        return liqCheckSchem;
    }

    public void setLiqCheckSchem(WhiteBlackSchematic liqCheckSchem) {
        this.liqCheckSchem = liqCheckSchem;
    }

    public void setHighwayDirection(Vec3i highwayDirection) {
        this.highwayDirection = highwayDirection;
    }

    public void setPaving(boolean paving) {
        this.paving = paving;
    }

    public boolean paving() {
        return paving;
    }

    public ShulkerType picksToUse() {
        return picksToUse;
    }

    public void setPicksToUse(ShulkerType picksToUse) {
        this.picksToUse = picksToUse;
    }

    public int highwayCheckBackDistance() {
        return highwayCheckBackDistance;
    }

    public float cachedHealth() {
        return cachedHealth;
    }

    public void setCachedHealth(float cachedHealth) {
        this.cachedHealth = cachedHealth;
    }

    public void transitionTo(HighwayState nextState) {
        currentState = StateFactory.getState(nextState);
    }

    public void handle() {
        currentState.handle(this);
    }

    public void incrementTimers() {
        timer++;
        walkBackTimer++;
        checkBackTimer++;
        stuckTimer++;
    }

    public void resetTimer() {
        timer = 0;
    }

    public void resetWalkBackTimer() {
        walkBackTimer = 0;
    }

    public void resetCheckBackTimer() {
        checkBackTimer = 0;
    }

    public void resetStuckTimer() {
        stuckTimer = 0;
    }

    public int picksToHave() {
        return picksToHave;
    }

    public void setPicksToHave(int picksToHave) {
        this.picksToHave = picksToHave;
    }

    public List<BlockState> blackListBlocks() {
        return blackListBlocks;
    }

    public boolean enderChestHasPickShulks() {
        return enderChestHasPickShulks;
    }

    public void setEnderChestHasPickShulks(boolean enderChestHasPickShulks) {
        this.enderChestHasPickShulks = enderChestHasPickShulks;
    }

    public boolean enderChestHasEnderShulks() {
        return enderChestHasEnderShulks;
    }

    public void setEnderChestHasEnderShulks(boolean enderChestHasEnderShulks) {
        this.enderChestHasEnderShulks = enderChestHasEnderShulks;
    }

    public boolean repeatCheck() {
        return repeatCheck;
    }

    public void setRepeatCheck(boolean repeatCheck) {
        this.repeatCheck = repeatCheck;
    }

    public int startShulkerCount() {
        return startShulkerCount;
    }

    public void setStartShulkerCount(int startShulkerCount) {
        this.startShulkerCount = startShulkerCount;
    }

    public boolean paused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public BetterBlockPos getClosestPoint(Vec3 origin, Vec3 direction, Vec3 point, LocationType locType) {
        // https://stackoverflow.com/a/51906100

        int yLevel = switch (locType) {
            case HighwayBuild -> settings.highwayLowestY.value;
            case ShulkerEchestInteraction -> paving ? settings.highwayMainY.value + 1 : settings.highwayMainY.value;
            case SideStorage -> settings.highwayEmptyShulkEchestY.value;
        };

        // Never allow points behind the original starting location
        if (firstStartingPos != null &&
                ((direction.z == -1 && point.z > firstStartingPos.z) || // NW, N, NE
                        (direction.z == 1 && point.z < firstStartingPos.z) || // SE, S, SW
                        (direction.x == -1 && direction.z == 0 && point.x > firstStartingPos.x) || // W
                        (direction.x == 1 && direction.z == 0 && point.x < firstStartingPos.x))) { // E
            point = new Vec3(firstStartingPos.getX(), firstStartingPos.getY(), firstStartingPos.getZ());
        }

        direction = direction.normalize();
        Vec3 lhs = point.subtract(origin);
        double dotP = lhs.dot(direction);
        Vec3 closest = origin.add(direction.scale(dotP));
        return new BetterBlockPos(Math.round(closest.x), yLevel, Math.round(closest.z));
    }

    public int getLargestItemSlot(int itemId) {
        int largestSlot = -1;
        int largestCount = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = playerContext.player().getInventory().items.get(i);
            if (Item.getId(stack.getItem()) == itemId && stack.getCount() > largestCount) {
                largestSlot = i;
                largestCount = stack.getCount();
            }
        }

        return largestSlot;
    }

    public int getItemSlot(int itemId) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = playerContext.player().getInventory().items.get(i);
            if (Item.getId(stack.getItem()) == itemId) {
                return i;
            }
        }

        return -1;
    }

    public int getItemSlotNoHotbar(int itemId) {
        for (int i = 9; i < 36; i++) {
            ItemStack stack = playerContext.player().getInventory().items.get(i);
            if (Item.getId(stack.getItem()) == itemId) {
                return i;
            }
        }

        return -1;
    }

    public int putItemHotbar(int itemId) {
        int itemSlot = getItemSlot(itemId);
        if (itemSlot >= 9) {
            baritone.getInventoryBehavior().attemptToPutOnHotbar(itemSlot, usefulSlots::contains);
            itemSlot = getItemSlot(itemId);
        }

        return itemSlot;
    }

    public int putPickaxeHotbar() {
        int itemSlot = getPickaxeSlot();
        if (itemSlot >= 9) {
            baritone.getInventoryBehavior().attemptToPutOnHotbar(itemSlot, usefulSlots::contains);
            itemSlot = getPickaxeSlot();
        }

        return itemSlot;
    }

    private int getPickaxeSlot() {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = playerContext.player().getInventory().items.get(i);
            if (stack.getItem() instanceof PickaxeItem) {
                if (settings.itemSaver.value && (stack.getDamageValue() + settings.itemSaverThreshold.value) >= stack.getMaxDamage() && stack.getMaxDamage() > 1) {
                    continue;
                }
                return i;
            }
        }

        return -1;
    }

    public void swapOffhand(int slot) {
        playerContext.playerController().windowClick(0, 45, 0, ClickType.PICKUP, playerContext.player());
        playerContext.playerController().windowClick(0, slot < 9 ? slot + 36 : slot, 0, ClickType.PICKUP, playerContext.player());
        playerContext.playerController().windowClick(0, 45, 0, ClickType.PICKUP, playerContext.player());
    }

    public boolean autoTotem() {
        if (settings.highwayAutoTotem.value && currentState.getState() == HighwayState.BuildingHighway && playerContext.player().getOffhandItem().getItem() != Items.TOTEM_OF_UNDYING) {
            int totemSlot = getItemSlot(Item.getId(Items.TOTEM_OF_UNDYING));
            if (totemSlot != -1) {
                swapOffhand(totemSlot);
                timer = 0;
                return true;
            }
        }
        return false;
    }

    public boolean clearCursorItem() {
        if (!playerContext.player().containerMenu.getCarried().isEmpty() && playerContext.player().containerMenu == playerContext.player().inventoryMenu) {
            if (cursorStackNonEmpty && timer >= 20) {
                // We have some item on our cursor for 20 ticks, try to place it somewhere
                timer = 0;


                int emptySlot = getItemSlot(Item.getId(Items.AIR));
                if (emptySlot != -1) {
                    Helper.HELPER.logDirect("Had " + playerContext.player().containerMenu.getCarried().getDisplayName() + " on our cursor. Trying to place into slot " + emptySlot);

                    if (emptySlot <= 8) {
                        // Fix slot id if it's a hotbar slot
                        emptySlot += 36;
                    }
                    playerContext.playerController().windowClick(playerContext.player().inventoryMenu.containerId, emptySlot, 0, ClickType.PICKUP, playerContext.player());
                    cursorStackNonEmpty = false;
                    return true;
                } else {
                    if (Item.getId(playerContext.player().containerMenu.getCarried().getItem()) == Item.getId(Blocks.NETHERRACK.asItem())) {
                        // Netherrack on our cursor, we can just throw it out
                        playerContext.playerController().windowClick(playerContext.player().inventoryMenu.containerId, -999, 0, ClickType.PICKUP, playerContext.player());
                        cursorStackNonEmpty = false;
                        return true;
                    } else {
                        // We don't have netherrack on our cursor, might be important so swap with netherrack and throw away the netherrack
                        int netherRackSlot = getItemSlot(Item.getId(Blocks.NETHERRACK.asItem()));
                        if (netherRackSlot == 8) {
                            netherRackSlot = getItemSlotNoHotbar(Item.getId(Blocks.NETHERRACK.asItem()));
                        }
                        if (netherRackSlot != -1) {
                            if (netherRackSlot <= 8) {
                                // Fix slot id if it's a hotbar slot
                                netherRackSlot += 36;
                            }
                            playerContext.playerController().windowClick(playerContext.player().inventoryMenu.containerId, netherRackSlot, 0, ClickType.PICKUP, playerContext.player());
                            playerContext.playerController().windowClick(playerContext.player().inventoryMenu.containerId, -999, 0, ClickType.PICKUP, playerContext.player());
                            cursorStackNonEmpty = false;
                            return true;
                        }
                    }
                }
            } else if (!cursorStackNonEmpty) {
                cursorStackNonEmpty = true;
                timer = 0;
                return true;
            }
            return true;
        }
        return false;
    }

    public boolean stuckCheck() {
        if (stuckTimer >= settings.highwayStuckCheckTicks.value) {
            if (playerContext.player().hasContainerOpen()) {
                playerContext.player().closeContainer(); // Close chest gui so we can actually build
                stuckTimer = 0;
                return true;
            }

            if (cachedPlayerFeet == null) {
                cachedPlayerFeet = new BetterBlockPos(playerContext.playerFeet());
                stuckTimer = 0;
                return true;
            }

            if (VecUtils.distanceToCenter(cachedPlayerFeet, playerContext.playerFeet().x, playerContext.playerFeet().y, playerContext.playerFeet().z) < settings.highwayStuckDistance.value) {
                // Check for floating case
                if (playerContext.world().getBlockState(playerContext.playerFeet().below()).getBlock() instanceof AirBlock) {
                    Helper.HELPER.logDirect("Haven't moved in " + settings.highwayStuckCheckTicks.value + " ticks and are floating. Trying to force clear blocks around us");
                    timer = 0;
                    stuckTimer = 0;
                    transitionTo(HighwayState.FloatingFixPrep);
                    baritone.getInputOverrideHandler().clearAllKeys();
                    baritone.getPathingBehavior().cancelEverything();
                    return true;
                }

                Helper.HELPER.logDirect("We haven't moved in " + settings.highwayStuckCheckTicks.value + " ticks. Restarting builder");
                timer = 0;
                stuckTimer = 0;
                if (currentState.getState() == HighwayState.BuildingHighway) {
                    transitionTo(HighwayState.Nothing);
                }
                playerContext.player().connection.getConnection().disconnect(Component.literal("Haven't moved in " + settings.highwayStuckCheckTicks.value + " ticks. Reconnect"));
                return true;
            }

            if (!cachedPlayerFeet.equals(playerContext.playerFeet())) {
                cachedPlayerFeet = new BetterBlockPos(playerContext.playerFeet());
                stuckTimer = 0;
                return true;
            }
        }
        return false;
    }

    public boolean healthCheck() {
        if (settings.highwayDcOnHealthLoss.value && playerContext.player().getHealth() < cachedHealth &&
                currentState.getState() != HighwayState.LiquidRemovalGapplePrep && currentState.getState() != HighwayState.LiquidRemovalGapplePreEat && currentState.getState() != HighwayState.LiquidRemovalGappleEat) {
            Component dcMsg = Component.literal("Lost " + (cachedHealth - playerContext.player().getHealth()) + " health. Reconnect");
            Helper.HELPER.logDirect(dcMsg);
            playerContext.player().connection.getConnection().disconnect(dcMsg);
            cachedHealth = playerContext.player().getHealth();
            return true;
        }
        cachedHealth = playerContext.player().getHealth(); // Get new HP value
        return false;
    }

    /* Find the farthest distance baritone will travel with the current goal */
    public double getFarthestGoalDistance(Goal goal) {
        double farthest = 0.0d;
        List<Goal> goalList = new ArrayList<>();
        if (goal instanceof BuilderProcess.JankyGoalComposite) {
            Goal primary = ((BuilderProcess.JankyGoalComposite) goal).getPrimary();
            Goal fallback = ((BuilderProcess.JankyGoalComposite) goal).getFallback();
            if (primary instanceof GoalComposite) {
                goalList.addAll(getCompositeGoals((GoalComposite) primary));
            } else {
                goalList.add(primary);
            }
            if (fallback instanceof GoalComposite) {
                goalList.addAll(getCompositeGoals((GoalComposite) fallback));
            } else {
                goalList.add(fallback);
            }
        } else if (goal instanceof GoalComposite) {
            goalList.addAll(getCompositeGoals((GoalComposite) goal));
        }

        for (Goal curGoal: goalList) {
            BlockPos blockPos;
            Vec3 vecPos;
            double dist;
            if (curGoal instanceof GoalGetToBlock) {
                blockPos = ((GoalGetToBlock) curGoal).getGoalPos();
            } else if (curGoal instanceof GoalBlock) {
                blockPos = ((GoalBlock) curGoal).getGoalPos();
            } else if (curGoal instanceof GoalTwoBlocks) {
                blockPos = ((GoalTwoBlocks) curGoal).getGoalPos();
            } else if (curGoal instanceof GoalXZ) {
                blockPos = new BlockPos(((GoalXZ) curGoal).getX(), (int) playerContext.player().position().y, ((GoalXZ) curGoal).getZ());
            }
            else {
                continue;
            }

            vecPos = new Vec3(blockPos.getX(), blockPos.getY(), blockPos.getZ());
            dist = vecPos.distanceToSqr(playerContext.player().position());
            if (dist > farthest) {
                farthest = dist;
            }
        }

        return Math.sqrt(farthest);
    }

    private List<Goal> getCompositeGoals(GoalComposite goal) {
        List<Goal> goalList = new ArrayList<>();
        for (Goal curGoal: goal.goals()) {
            if (curGoal instanceof BuilderProcess.JankyGoalComposite) {
                goalList.add(((BuilderProcess.JankyGoalComposite) curGoal).getPrimary());
                goalList.add(((BuilderProcess.JankyGoalComposite) curGoal).getFallback());
            } else {
                goalList.add(curGoal);
            }
        }

        return goalList;
    }



    private boolean checkForNeighbours(BlockPos blockPos)
    {
        // check if we don't have a block adjacent to blockpos
        if (!hasNeighbour(blockPos))
        {
            // find air adjacent to blockpos that does have a block adjacent to it, let's fill this first as to form a bridge between the player and the original blockpos. necessary if the player is
            // going diagonal.
            for (Direction side : Direction.values())
            {
                BlockPos neighbour = blockPos.offset(side.getNormal());
                if (hasNeighbour(neighbour))
                {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private boolean hasNeighbour(BlockPos blockPos)
    {
        for (Direction side : Direction.values())
        {
            BlockPos neighbour = blockPos.offset(side.getNormal());
            if (!playerContext.world().getBlockState(neighbour).canBeReplaced())
            {
                return true;
            }
        }
        return false;
    }

    private NonNullList<ItemStack> getShulkerContents(ItemStack shulker) {
        NonNullList<ItemStack> contents = NonNullList.withSize(27, ItemStack.EMPTY);

        if (shulker.has(DataComponents.CONTAINER)) {
            ItemContainerContents container = shulker.get(DataComponents.CONTAINER);
            container.copyInto(contents);
        }
        else if (shulker.has(DataComponents.BLOCK_ENTITY_DATA)) {
            CustomData data = shulker.get(DataComponents.BLOCK_ENTITY_DATA);
            if (data != null && data.contains("Items")) {
                CompoundTag compoundTag = data.copyTag();
                if (compoundTag.contains("Items")) {
                    ContainerHelper.loadAllItems(compoundTag, contents, playerContext.player().registryAccess());
                }
            }
        }

        return contents;
    }

    private int isPickaxeShulker(ItemStack shulker) {
        NonNullList<ItemStack> contents = getShulkerContents(shulker);

        int pickaxeCount = 0;
        for (ItemStack curStack : contents) {
            if (curStack.getItem() instanceof PickaxeItem) {
                if (settings.itemSaver.value && (curStack.getDamageValue() + settings.itemSaverThreshold.value) >= curStack.getMaxDamage() && curStack.getMaxDamage() > 1) {
                    continue;
                }
                pickaxeCount++;
            } else if (!(curStack.getItem() instanceof AirItem)) {
                if (!settings.highwayAllowMixedShulks.value || !(curStack.getItem() instanceof BlockItem) || !(((BlockItem)curStack.getItem()).getBlock() instanceof EnderChestBlock)) {
                    return 0; // Found a non pickaxe and non-air item
                }
            }
        }

        return pickaxeCount;
    }

    private int isNonSilkPickShulker(ItemStack shulker) {
        NonNullList<ItemStack> contents = getShulkerContents(shulker);

        int pickaxeCount = 0;
        for (ItemStack curStack : contents) {
            if (curStack.getItem() instanceof PickaxeItem) {
                if (settings.itemSaver.value && (curStack.getDamageValue() + settings.itemSaverThreshold.value) >= curStack.getMaxDamage() && curStack.getMaxDamage() > 1) {
                    continue;
                }
                pickaxeCount++;


                ItemEnchantments enchantments = curStack.getEnchantments();
                for (Holder<Enchantment> enchant : enchantments.keySet()) {
                    if (enchant.is(Enchantments.SILK_TOUCH) && enchantments.getLevel(enchant) > 0) {
                        // Pickaxe is enchanted with silk touch
                        return 0;
                    }
                }
            } else if (!(curStack.getItem() instanceof AirItem)) {
                if (!settings.highwayAllowMixedShulks.value || !(curStack.getItem() instanceof BlockItem) || !(((BlockItem)curStack.getItem()).getBlock() instanceof EnderChestBlock)) {
                    return 0; // Found a non pickaxe and non-air item
                }
            }
        }

        return pickaxeCount;
    }

    private int isEnderChestShulker(ItemStack shulker) {
        NonNullList<ItemStack> contents = getShulkerContents(shulker);

        int enderChestCount = 0;
        for (ItemStack curStack : contents) {
            if (Item.getId(curStack.getItem()) != Item.getId(Items.AIR) && Item.getId(curStack.getItem()) != Item.getId(Blocks.ENDER_CHEST.asItem())) {
                if (!settings.highwayAllowMixedShulks.value || !(curStack.getItem() instanceof PickaxeItem)) {
                    return 0;
                }
            }

            if (Item.getId(curStack.getItem()) == Item.getId(Blocks.ENDER_CHEST.asItem())) {
                enderChestCount++;
            }
        }
        return enderChestCount;
    }

    private int isGappleShulker(ItemStack shulker) {
        NonNullList<ItemStack> contents = getShulkerContents(shulker);

        int gappleCount = 0;
        for (ItemStack curStack : contents) {
            if (Item.getId(curStack.getItem()) != Item.getId(Items.AIR) && Item.getId(curStack.getItem()) != Item.getId(Items.ENCHANTED_GOLDEN_APPLE)) {
                return 0;
            }

            if (Item.getId(curStack.getItem()) == Item.getId(Items.ENCHANTED_GOLDEN_APPLE)) {
                gappleCount++;
            }
        }
        return gappleCount;
    }

    private boolean isEmptyShulker(ItemStack shulker) {
        NonNullList<ItemStack> contents = getShulkerContents(shulker);

        for (ItemStack curStack : contents) {
            if (!(curStack.getItem() instanceof AirItem)) {
                // Not air so shulker contains something
                return false;
            }
        }

        // Didn't find anything in the shulker, so it's empty
        return true;
    }

    public int getShulkerSlot(ShulkerType shulkerType) {
        int bestSlot = -1;
        int bestSlotCount = Integer.MAX_VALUE;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = playerContext.player().getInventory().items.get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                continue;
            }
            if (shulkerItemList.contains(stack.getItem())) {
                switch (shulkerType) {
                    case AnyPickaxe: {
                        int count = isPickaxeShulker(stack);
                        if (count > 0 && count < bestSlotCount) {
                            bestSlot = i;
                            bestSlotCount = count;
                        }
                        break;
                    }

                    case Gapple: {
                        int count = isGappleShulker(stack);
                        if (count > 0 && count < bestSlotCount) {
                            bestSlot = i;
                            bestSlotCount = count;
                        }
                        break;
                    }

                    case NonSilkPickaxe: {
                        int count = isNonSilkPickShulker(stack);
                        if (count > 0 && count < bestSlotCount) {
                            bestSlot = i;
                            bestSlotCount = count;
                        }
                        break;
                    }

                    case EnderChest: {
                        int count = isEnderChestShulker(stack);
                        if (count > 0 && count < bestSlotCount) {
                            bestSlot = i;
                            bestSlotCount = count;
                        }
                        break;
                    }

                    case Empty: {
                        if (isEmptyShulker(stack)) {
                            return i;
                        }
                        break;
                    }
                }
            }
        }

        return bestSlot;
    }

    public int getItemCountInventory(int itemId) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = playerContext.player().getInventory().items.get(i);
            if (Item.getId(stack.getItem()) == itemId) {
                if (itemId == 0) {
                    // We're counting air slots
                    count++;
                } else {
                    count += stack.getCount();
                }
            }
        }

        return count;
    }

    public int getPickCountInventory() {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = playerContext.player().getInventory().items.get(i);
            if (stack.getItem() instanceof PickaxeItem) {
                if (settings.itemSaver.value && (stack.getDamageValue() + settings.itemSaverThreshold.value) >= stack.getMaxDamage() && stack.getMaxDamage() > 1) {
                    continue;
                }
                count++;
            }
        }

        return count;
    }

    private int putShulkerHotbar(ShulkerType shulkerType) {
        int shulkerSlot = getShulkerSlot(shulkerType);
        if (shulkerSlot >= 9) {
            baritone.getInventoryBehavior().attemptToPutOnHotbar(shulkerSlot, usefulSlots::contains);
            shulkerSlot = getShulkerSlot(shulkerType);
        }

        return shulkerSlot;
    }

    public HighwayState placeShulkerBox(Rotation shulkerReachable, Rotation underShulkerReachable, BlockPos shulkerPlaceLoc, HighwayState prevHighwayState, HighwayState currentHighwayState, HighwayState nextHighwayState, ShulkerType shulkerType) {
        if (shulkerReachable != null) {
            Block shulkerLocBlock = playerContext.world().getBlockState(shulkerPlaceLoc).getBlock();
            baritone.getLookBehavior().updateTarget(shulkerReachable, true); // Look at shulker spot

            if (shulkerLocBlock instanceof ShulkerBoxBlock) {
                Helper.HELPER.logDirect("Shulker has been placed successfully");
                baritone.getInputOverrideHandler().clearAllKeys();
                return nextHighwayState;
            }
            else if (!(shulkerLocBlock instanceof SnowLayerBlock)) {
                Helper.HELPER.logDirect("Something went wrong at " + currentHighwayState + ". Have " + shulkerLocBlock + " instead of shulker");
                return prevHighwayState; // Go back a state
            }
        }
        else if (underShulkerReachable != null) {
            baritone.getLookBehavior().updateTarget(underShulkerReachable, true);
        }

        if (underShulkerReachable == null) {
            return prevHighwayState; // Something is wrong with where we are trying to place, go back
        }

        if (shulkerPlaceLoc.below().equals(playerContext.getSelectedBlock().orElse(null))) {
            int shulkerSlot = putShulkerHotbar(shulkerType);
            if (shulkerSlot == -1) {
                Helper.HELPER.logDirect("Error getting shulker slot");
                return currentHighwayState;
            }
            if (shulkerSlot >= 9) {
                Helper.HELPER.logDirect("Couldn't put shulker to hotbar, waiting");
                return currentHighwayState;
            }
            ItemStack stack = playerContext.player().getInventory().items.get(shulkerSlot);
            if (shulkerItemList.contains(stack.getItem())) {
                playerContext.player().getInventory().selected = shulkerSlot;
            }

            //baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            double lastX = playerContext.getPlayerEntity().getXLast();
            double lastY = playerContext.getPlayerEntity().getYLast();
            double lastZ = playerContext.getPlayerEntity().getZLast();
            final Vec3 pos = new Vec3(lastX + (playerContext.player().getX() - lastX) * playerContext.minecraft().getTimer().getGameTimeDeltaPartialTick(true),
                    lastY + (playerContext.player().getY() - lastY) * playerContext.minecraft().getTimer().getGameTimeDeltaPartialTick(true),
                    lastZ + (playerContext.player().getZ() - lastZ) * playerContext.minecraft().getTimer().getGameTimeDeltaPartialTick(true));
            BetterBlockPos originPos = new BetterBlockPos(pos.x, pos.y+0.5f, pos.z);
            double l_Offset = pos.y - originPos.getY();
            PlaceResult l_Place = place(shulkerPlaceLoc, 5.0f, true, l_Offset == -0.5f, InteractionHand.MAIN_HAND);
        }

        return currentHighwayState;
    }

    private int getDepletedPickSlot() {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = playerContext.player().getInventory().items.get(i);
            if (stack.getItem() instanceof PickaxeItem && (stack.getDamageValue() + settings.itemSaverThreshold.value) >= stack.getMaxDamage() && stack.getMaxDamage() > 1) {
                return i;
            }
        }

        return -1;
    }

    public int lootPickaxeChestSlot() {
        AbstractContainerMenu curContainer = playerContext.player().containerMenu;
        for (int i = 0; i < 27; i++) {
            if (curContainer.getSlot(i).getItem().getItem() instanceof PickaxeItem) {
                // Don't loot depleted picks if we're using item saver mode
                if (settings.itemSaver.value && (curContainer.getSlot(i).getItem().getDamageValue() + settings.itemSaverThreshold.value) >= curContainer.getSlot(i).getItem().getMaxDamage() && curContainer.getSlot(i).getItem().getMaxDamage() > 1) {
                    continue;
                }
                int swapSlot = settings.itemSaver.value ? getDepletedPickSlot() : -1;

                // No depleted picks
                if (swapSlot == -1) {
                    int airSlot = getItemSlot(Item.getId(Items.AIR));
                    if (airSlot != -1) {
                        playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.QUICK_MOVE, playerContext.player());
                        return 1;
                    }

                    swapSlot = getItemSlot(Item.getId(Blocks.NETHERRACK.asItem()));
                    if (swapSlot == 8) {
                        swapSlot = getItemSlotNoHotbar(Item.getId(Blocks.NETHERRACK.asItem()));
                    }
                    if (swapSlot == -1) {
                        // Also didn't find any netherrack
                        return 0;
                    }
                    playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, playerContext.player());
                    playerContext.playerController().windowClick(curContainer.containerId, swapSlot < 9 ? swapSlot + 54 : swapSlot + 18, 0, ClickType.PICKUP, playerContext.player()); // Have to convert slot id to single chest slot id
                    playerContext.playerController().windowClick(curContainer.containerId, -999, 0, ClickType.PICKUP, playerContext.player()); // Throw away netherrack
                } else {
                    // Swap new pickaxe with depleted
                    playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, playerContext.player());
                    playerContext.playerController().windowClick(curContainer.containerId, swapSlot < 9 ? swapSlot + 54 : swapSlot + 18, 0, ClickType.PICKUP, playerContext.player()); // Have to convert slot id to single chest slot id
                    playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, playerContext.player()); // Put depleted pick in looted slot
                }
                return 1;
            }
        }

        return 0;
    }

    public int lootGappleChestSlot() {
        int count = 0;
        AbstractContainerMenu curContainer = playerContext.player().containerMenu;
        for (int i = 0; i < 27; i++) {
            if (curContainer.getSlot(i).getItem().is(Items.ENCHANTED_GOLDEN_APPLE)) {
                count += curContainer.getSlot(i).getItem().getCount();

                if (getItemCountInventory(Item.getId(Items.ENCHANTED_GOLDEN_APPLE)) == 0 && getItemSlot(Item.getId(Items.AIR)) == -1) {
                    // For some reason we have no gapples and no air slots so we have to throw out some netherrack
                    int netherRackSlot = getItemSlot(Item.getId(Blocks.NETHERRACK.asItem()));
                    if (netherRackSlot == 8) {
                        netherRackSlot = getItemSlotNoHotbar(Item.getId(Blocks.NETHERRACK.asItem()));
                    }
                    if (netherRackSlot == -1) {
                        return 0;
                    }
                    playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, playerContext.player());
                    playerContext.playerController().windowClick(curContainer.containerId, netherRackSlot < 9 ? netherRackSlot + 54 : netherRackSlot + 18, 0, ClickType.PICKUP, playerContext.player()); // Have to convert slot id to single chest slot id
                    playerContext.playerController().windowClick(curContainer.containerId, -999, 0, ClickType.PICKUP, playerContext.player());
                } else {
                    // Gapples exist already or there's an air slot so we can just do a quick move
                    playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.QUICK_MOVE, playerContext.player());
                }

                return count;
            }
        }

        return count;
    }

    public int lootEnderChestSlot() {
        int count = 0;
        AbstractContainerMenu curContainer = playerContext.player().containerMenu;
        for (int i = 0; i < 27; i++) {
            if (curContainer.getSlot(i).getItem().getItem() instanceof BlockItem &&
                    ((BlockItem) curContainer.getSlot(i).getItem().getItem()).getBlock() instanceof EnderChestBlock) {
                count += curContainer.getSlot(i).getItem().getCount();

                if (getItemSlot(Item.getId(Items.AIR)) == -1) {
                    // For some reason we have no air slots so we have to throw out some netherrack
                    int netherRackSlot = getItemSlot(Item.getId(Blocks.NETHERRACK.asItem()));
                    if (netherRackSlot == 8) {
                        netherRackSlot = getItemSlotNoHotbar(Item.getId(Blocks.NETHERRACK.asItem()));
                    }
                    if (netherRackSlot == -1) {
                        return 0;
                    }
                    playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, playerContext.player());
                    playerContext.playerController().windowClick(curContainer.containerId, netherRackSlot < 9 ? netherRackSlot + 54 : netherRackSlot + 18, 0, ClickType.PICKUP, playerContext.player()); // Have to convert slot id to single chest slot id
                    playerContext.playerController().windowClick(curContainer.containerId, -999, 0, ClickType.PICKUP, playerContext.player());
                } else {
                    // There's an air slot so we can just do a quick move
                    playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.QUICK_MOVE, playerContext.player());
                }

                return count;
            }
        }

        return count;
    }

    public int lootShulkerChestSlot(ShulkerType shulkerType) {
        AbstractContainerMenu curContainer = playerContext.player().containerMenu;
        for (int i = 0; i < 27; i++) {
            ItemStack stack = curContainer.getSlot(i).getItem();
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                continue;
            }
            if (shulkerItemList.contains(stack.getItem())) {
                boolean doLoot = false;
                switch (shulkerType) {
                    case EnderChest:
                        if (isEnderChestShulker(stack) > 0) {
                            doLoot = true;
                        }
                        break;

                    case Empty:
                        if (isEmptyShulker(stack)) {
                            doLoot = true;
                        }
                        break;

                    case NonSilkPickaxe:
                        if (isNonSilkPickShulker(stack) > 0) {
                            doLoot = true;
                        }
                        break;

                    case Gapple:
                        if (isGappleShulker(stack) > 0) {
                            doLoot = true;
                        }
                        break;

                    case AnyPickaxe:
                        if (isPickaxeShulker(stack) > 0) {
                            doLoot = true;
                        }
                }
                if (doLoot) {
                    if (getItemSlot(Item.getId(Items.AIR)) == -1) {
                        // For some reason we have no air slots so we have to throw out some netherrack
                        int netherRackSlot = getItemSlot(Item.getId(Blocks.NETHERRACK.asItem()));
                        if (netherRackSlot == 8) {
                            netherRackSlot = getItemSlotNoHotbar(Item.getId(Blocks.NETHERRACK.asItem()));
                        }
                        if (netherRackSlot == -1) {
                            return 0;
                        }
                        playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, playerContext.player());
                        playerContext.playerController().windowClick(curContainer.containerId, netherRackSlot < 9 ? netherRackSlot + 54 : netherRackSlot + 18, 0, ClickType.PICKUP, playerContext.player()); // Have to convert slot id to single chest slot id
                        playerContext.playerController().windowClick(curContainer.containerId, -999, 0, ClickType.PICKUP, playerContext.player());
                    } else {
                        // There's an air slot so we can just do a quick move
                        playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.QUICK_MOVE, playerContext.player());
                    }
                    return 1;
                }
            }
        }

        return 0;
    }

    public int getShulkerCountInventory(ShulkerType shulkerType) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = playerContext.player().getInventory().items.get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                continue;
            }
            if (shulkerItemList.contains(stack.getItem())) {
                switch (shulkerType) {
                    case AnyPickaxe:
                        if (isPickaxeShulker(stack) > 0) {
                            count++;
                        }
                        break;

                    case Gapple:
                        if (isGappleShulker(stack) > 0) {
                            count++;
                        }
                        break;

                    case Empty:
                        if (isEmptyShulker(stack)) {
                            count++;
                        }
                        break;

                    case NonSilkPickaxe:
                        if (isNonSilkPickShulker(stack) > 0) {
                            count++;
                        }
                        break;

                    case EnderChest:
                        if (isEnderChestShulker(stack) > 0) {
                            count++;
                        }
                        break;

                    case Any:
                        count++;
                        break;
                }
            }
        }

        return count;
    }

    public boolean isShulkerOnGround() {
        for (Entity entity : playerContext.entities()) {
            if (entity instanceof ItemEntity) {
                if (shulkerItemList.contains(((ItemEntity) entity).getItem().getItem())) {
                    return true;
                }
            }
        }

        return false;
    }

    public BlockPos closestAirBlockWithSideBlock(BlockPos pos, int radius, boolean allowUp) {
        ArrayList<BlockPos> surroundingBlocks = new ArrayList<>();
        int yRadius = radius;
        if (!allowUp) {
            yRadius = 0;
        }
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= yRadius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos curPos = pos.offset(x, y, z);
                    if (getIssueType(curPos) != HighwayBlockState.Blocks &&
                            (getIssueType(curPos.north()) == HighwayBlockState.Blocks ||
                                    getIssueType(curPos.east()) == HighwayBlockState.Blocks ||
                                    getIssueType(curPos.south()) == HighwayBlockState.Blocks ||
                                    getIssueType(curPos.west()) == HighwayBlockState.Blocks ||
                                    getIssueType(curPos.below()) == HighwayBlockState.Blocks)) {
                        surroundingBlocks.add(curPos);
                    }
                }
            }
        }

        BlockPos closestBlock = null;
        double closestDistance = Double.MAX_VALUE;
        for (BlockPos curBlock : surroundingBlocks) {
            if (VecUtils.distanceToCenter(curBlock, pos.getX(), pos.getY(), pos.getZ()) < closestDistance) {
                closestDistance = VecUtils.distanceToCenter(curBlock, pos.getX(), pos.getY(), pos.getZ());
                closestBlock = curBlock;
            }
        }

        return closestBlock;
    }

    public int getHighwayLengthFront() {
        // TODO: Clean this up
        Vec3 direction = new Vec3(highwayDirection.getX(), highwayDirection.getY(), highwayDirection.getZ());
        Vec3 curPosNotOffset = new Vec3(playerContext.playerFeet().getX(), playerContext.playerFeet().getY(), playerContext.playerFeet().getZ());
        // Fix player feet location for diags so we don't check too far ahead
        // +X,+Z and -X,-Z
        if (((highwayDirection.getX() == 1 && highwayDirection.getZ() == 1) || (highwayDirection.getX() == -1 && highwayDirection.getZ() == -1))) {
            curPosNotOffset = new Vec3(curPosNotOffset.x, curPosNotOffset.y, curPosNotOffset.x - 4);
        } else if ((highwayDirection.getX() == 1 && highwayDirection.getZ() == -1) || (highwayDirection.getX() == -1 && highwayDirection.getZ() == 1)) {
            curPosNotOffset = new Vec3(-curPosNotOffset.z - 5, curPosNotOffset.y, curPosNotOffset.z);
        }

        Vec3 curPosPlayer = new Vec3(curPosNotOffset.x, curPosNotOffset.y, curPosNotOffset.z);
        BlockPos startCheckPos = getClosestPoint(new Vec3(originVector.x, originVector.y, originVector.z), direction, curPosPlayer, LocationType.HighwayBuild);


        for (int i = 0; i < 10; i++) {
            BlockPos curPos = startCheckPos.offset(i * highwayDirection.getX(), 0, i * highwayDirection.getZ());
            for (int y = 0; y < schematic.heightY(); y++) {
                for (int z = 0; z < schematic.lengthZ(); z++) {
                    for (int x = 0; x < schematic.widthX(); x++) {
                        int blockX = x + curPos.getX();
                        int blockY = y + curPos.getY();
                        int blockZ = z + curPos.getZ();
                        BlockState current = playerContext.world().getBlockState(new BlockPos(blockX, blockY, blockZ));

                        if (!schematic.inSchematic(x, y, z, current)) {
                            continue;
                        }
                        if (baritone.bsi.worldContainsLoadedChunk(blockX, blockZ)) { // check if its in render distance, not if its in cache
                            // we can directly observe this block, it is in render distance

                            ISchematic ourSchem = schematic.getSchematic(x, y, z, current).schematic;
                            if (ourSchem instanceof WhiteBlackSchematic && ((WhiteBlackSchematic) ourSchem).isValidIfUnder() &&
                                    MovementHelper.isBlockNormalCube(playerContext.world().getBlockState(new BlockPos(blockX, blockY + 1, blockZ)))) {
                                continue;
                            }

                            if (!schematic.desiredState(x, y, z, current, this.approxPlaceable).equals(current)) {
                                return i;
                            }

                        }

                    }
                }
            }
        }

        return 0;
    }


    public HighwayBlockState isHighwayCorrect(BlockPos startPos, BlockPos startPosLiq, int distanceToCheck, boolean renderLiquidScan) {
        // startPos needs to be in center of highway
        renderLockBuilding.lock();
        renderBlocksBuilding.clear();
        boolean foundBlocks = false;
        for (int i = 1; i < distanceToCheck; i++) {
            BlockPos curPos = startPos.offset(i * highwayDirection.getX(), 0, i * highwayDirection.getZ());
            for (int y = 0; y < schematic.heightY(); y++) {
                for (int z = 0; z < schematic.lengthZ(); z++) {
                    for (int x = 0; x < schematic.widthX(); x++) {
                        int blockX = x + curPos.getX();
                        int blockY = y + curPos.getY();
                        int blockZ = z + curPos.getZ();
                        BlockState current = playerContext.world().getBlockState(new BlockPos(blockX, blockY, blockZ));

                        if (!schematic.inSchematic(x, y, z, current)) {
                            continue;
                        }
                        if (i == 1) {
                            BlockState desiredState = schematic.desiredState(x, y, z, current, this.approxPlaceable);
                            if (desiredState.getBlock() instanceof AirBlock) {
                                renderBlocksBuilding.put(new BlockPos(blockX, blockY, blockZ), Color.CYAN);
                            } else if (desiredState.getBlock().equals(Blocks.NETHERRACK)) {
                                renderBlocksBuilding.put(new BlockPos(blockX, blockY, blockZ), Color.RED);
                            } else if (desiredState.getBlock().equals(Blocks.OBSIDIAN) || desiredState.getBlock().equals(Blocks.CRYING_OBSIDIAN)) {
                                renderBlocksBuilding.put(new BlockPos(blockX, blockY, blockZ), Color.BLACK);
                            } else {
                                renderBlocksBuilding.put(new BlockPos(blockX, blockY, blockZ), Color.GREEN);
                            }
                        }
                        if (baritone.bsi.worldContainsLoadedChunk(blockX, blockZ)) { // check if its in render distance, not if its in cache
                            // we can directly observe this block, it is in render distance

                            ISchematic ourSchem = schematic.getSchematic(x, y, z, current).schematic;
                            if (ourSchem instanceof WhiteBlackSchematic && ((WhiteBlackSchematic) ourSchem).isValidIfUnder() &&
                                    MovementHelper.isBlockNormalCube(playerContext.world().getBlockState(new BlockPos(blockX, blockY + 1, blockZ)))) {
                                continue;
                            }

                            if (!schematic.desiredState(x, y, z, current, this.approxPlaceable).equals(current)) {
                                if (!baritone.getBuilderProcess().checkNoEntityCollision(new AABB(new BlockPos(blockX, blockY, blockZ)), playerContext.player())) {
                                    List<Entity> entityList = playerContext.world().getEntities(null, new AABB(new BlockPos(blockX, blockY, blockZ)));
                                    for (Entity entity : entityList) {
                                        if (entity instanceof Boat || entity.isVehicle()) {
                                            // can't do boats lol
                                            boatHasPassenger = entity.isVehicle();
                                            boatLocation = new BlockPos(blockX, blockY, blockZ);
                                            return HighwayBlockState.Boat;
                                        }
                                    }
                                }

                                // Never should be liquids
                                if (current.getBlock() instanceof LiquidBlock) {
                                    renderLockBuilding.unlock();
                                    return HighwayBlockState.Liquids;
                                } else {
                                    foundBlocks = true;
                                }
                            }


                            //if (!schematic.desiredState(x, y, z, current, this.approxPlaceable).equals(current)) {
                            // Found incorrect block
                            //   foundBlocks = true;//current.getBlock() != Blocks.OBSIDIAN || blockY != 119;
                            //}
                        }

                    }
                }
            }
        }
        renderLockBuilding.unlock();

        if (findFirstLiquidGround(startPosLiq, distanceToCheck, renderLiquidScan) != null) {
            return HighwayBlockState.Liquids;
        }

        if (foundBlocks) {
            return HighwayBlockState.Blocks;
        }
        return HighwayBlockState.Air;
    }

    public BlockPos findFirstLiquidGround(BlockPos startPos, int distanceToCheck, boolean renderCheckedBlocks) {
        if (renderCheckedBlocks) {
            renderLockLiquid.lock();
            renderBlocksLiquid.clear();
        }
        //Liquid Checking all around
        for (int i = 1; i < distanceToCheck; i++) {
            BlockPos curPos = startPos.offset(i * highwayDirection.getX(), 0, i * highwayDirection.getZ());
            for (int y = 0; y < liqCheckSchem.heightY(); y++) {
                for (int z = 0; z < liqCheckSchem.lengthZ(); z++) {
                    for (int x = 0; x < liqCheckSchem.widthX(); x++) {
                        int blockX = x + curPos.getX();
                        int blockY = y + curPos.getY();
                        int blockZ = z + curPos.getZ();
                        BlockState current = playerContext.world().getBlockState(new BlockPos(blockX, blockY, blockZ));
                        if (!liqCheckSchem.inSchematic(x, y, z, current)) {
                            continue;
                        }
                        if (renderCheckedBlocks) {
                            if (i == 1 && !renderBlocksBuilding.containsKey(new BlockPos(blockX, blockY, blockZ))) {
                                renderBlocksLiquid.add(new BlockPos(blockX, blockY, blockZ));
                            }
                        }
                        if (baritone.bsi.worldContainsLoadedChunk(blockX, blockZ)) { // check if its in render distance, not if its in cache
                            // Never should be liquids
                            if (current.getBlock() instanceof LiquidBlock) {
                                if (renderCheckedBlocks) {
                                    renderLockLiquid.unlock();
                                }
                                return new BlockPos(blockX, blockY, blockZ);
                            }
                        }

                    }
                }
            }
        }
        if (renderCheckedBlocks) {
            renderLockLiquid.unlock();
        }
        return null;
    }

    public HighwayBlockState getIssueType(BlockPos blockPos) {
        BlockState state = playerContext.world().getBlockState(blockPos);
        if (state.getBlock() instanceof AirBlock) {
            return HighwayBlockState.Air;
        }
        if (state.getBlock() instanceof LiquidBlock) {
            return HighwayBlockState.Liquids;
        }

        return HighwayBlockState.Blocks; // Not air and not liquids so it's some block
    }

    public void findSourceLiquid(int x, int y, int z, ArrayList<BlockPos> checked, ArrayList<BlockPos> sourceBlocks, ArrayList<BlockPos> flowingBlocks) {
        BlockState curBlockState = playerContext.world().getBlockState(new BlockPos(x, y, z));

        if (!(curBlockState.getBlock() instanceof LiquidBlock)) {
            return;
        }

        if (curBlockState.getBlock() instanceof LiquidBlock) {
            if (curBlockState.getValue(LiquidBlock.LEVEL) == 0) {
                sourceBlocks.add(new BlockPos(x, y, z));
            } else {
                flowingBlocks.add(new BlockPos(x, y, z));
            }
        }

        if (!checked.contains(new BlockPos(x + 1, y, z))) {
            checked.add(new BlockPos(x + 1, y, z));
            findSourceLiquid(x + 1, y, z, checked, sourceBlocks, flowingBlocks);
        }

        if (!checked.contains(new BlockPos(x - 1, y, z))) {
            checked.add(new BlockPos(x - 1, y, z));
            findSourceLiquid(x - 1, y, z, checked, sourceBlocks, flowingBlocks);
        }

        if (!checked.contains(new BlockPos(x, y, z + 1))) {
            checked.add(new BlockPos(x, y, z + 1));
            findSourceLiquid(x, y, z + 1, checked, sourceBlocks, flowingBlocks);
        }

        if (!checked.contains(new BlockPos(x, y, z - 1))) {
            checked.add(new BlockPos(x, y, z - 1));
            findSourceLiquid(x, y, z - 1, checked, sourceBlocks, flowingBlocks);
        }

        if (!checked.contains(new BlockPos(x, y + 1, z))) {
            checked.add(new BlockPos(x, y + 1, z));
            findSourceLiquid(x, y + 1, z, checked, sourceBlocks, flowingBlocks);
        }
    }

    public int checkBackTimer() {
        return checkBackTimer;
    }

    public BlockPos boatLocation() {
        return boatLocation;
    }

    public enum PlaceResult
    {
        NotReplaceable,
        Neighbors,
        CantPlace,
        Placed
    }

    public PlaceResult place(BlockPos pos, float p_Distance, boolean p_Rotate, boolean p_UseSlabRule, InteractionHand hand) {
        return place(pos, p_Distance, p_Rotate, p_UseSlabRule, false, hand);
    }

    private PlaceResult place(BlockPos pos, float p_Distance, boolean p_Rotate, boolean p_UseSlabRule, boolean packetSwing, InteractionHand hand) {
        BlockState l_State = playerContext.world().getBlockState(pos);

        boolean l_Replaceable = l_State.canBeReplaced();

        boolean l_IsSlabAtBlock = l_State.getBlock() instanceof SlabBlock;

        if (!l_Replaceable && !l_IsSlabAtBlock)
            return PlaceResult.NotReplaceable;
        if (!checkForNeighbours(pos))
            return PlaceResult.Neighbors;

        if (!l_IsSlabAtBlock)
        {
            ValidResult l_Result = valid(pos);

            if (l_Result != ValidResult.Ok && !l_Replaceable)
                return PlaceResult.CantPlace;
        }

        if (p_UseSlabRule)
        {
            if (l_IsSlabAtBlock && !MovementHelper.isBlockNormalCube(l_State))
                return PlaceResult.CantPlace;
        }

        final Vec3 eyesPos = new Vec3(playerContext.player().getX(), playerContext.player().getEyeY(), playerContext.player().getZ());

        for (final Direction side : Direction.values())
        {
            final BlockPos neighbor = pos.offset(side.getNormal());
            final Direction side2 = side.getOpposite();

            if (!playerContext.world().getBlockState(neighbor).getFluidState().isEmpty())
                continue;

            VoxelShape collisionShape = playerContext.world().getBlockState(neighbor).getCollisionShape(playerContext.world(), neighbor);
            boolean hasCollision = collisionShape != Shapes.empty();
            if (hasCollision)
            {
                final Vec3 hitVec = new Vec3(neighbor.getX(), neighbor.getY(), neighbor.getZ()).add(0.5, 0.5, 0.5).add(new Vec3(side2.getStepX(), side2.getStepY(), side2.getStepZ()).scale(0.5));
                if (eyesPos.distanceTo(hitVec) <= p_Distance)
                {
                    final Block neighborBlock = playerContext.world().getBlockState(neighbor).getBlock();

                    BlockHitResult blockHitResult = new BlockHitResult(hitVec, side2, neighbor, false);
                    final boolean activated = playerContext.world().getBlockState(neighbor).useWithoutItem(playerContext.world(), playerContext.player(), blockHitResult) == InteractionResult.SUCCESS;

                    if (blackList.contains(neighborBlock) || shulkerBlockList.contains(neighborBlock) || activated)
                    {
                        playerContext.player().connection.send(new ServerboundPlayerCommandPacket(playerContext.player(), ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY));
                    }
                    if (p_Rotate)
                    {
                        faceVectorPacketInstant(hitVec);
                    }
                    InteractionResult l_Result2 = playerContext.playerController().processRightClickBlock(playerContext.player(), playerContext.world(), hand, blockHitResult);

                    if (l_Result2 == InteractionResult.SUCCESS)
                    {
                        if (packetSwing)
                            playerContext.player().connection.send(new ServerboundSwingPacket(hand));
                        else
                            playerContext.player().swing(hand);
                        if (activated)
                        {
                            playerContext.player().connection.send(new ServerboundPlayerCommandPacket(playerContext.player(), ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
                        }
                        return PlaceResult.Placed;
                    }
                }
            }
        }
        return PlaceResult.CantPlace;
    }

    private enum ValidResult
    {
        NoEntityCollision,
        AlreadyBlockThere,
        NoNeighbors,
        Ok,
    }

    private ValidResult valid(BlockPos pos) {
        // There are no entities to block placement,
        if (!baritone.getBuilderProcess().checkNoEntityCollision(new AABB(pos), null))
            return ValidResult.NoEntityCollision;

        if (!checkForNeighbours(pos))
            return ValidResult.NoNeighbors;

        BlockState l_State = playerContext.world().getBlockState(pos);

        if (l_State.getBlock() instanceof AirBlock)
        {
            final BlockPos[] l_Blocks =
                    { pos.north(), pos.south(), pos.east(), pos.west(), pos.above(), pos.below() };

            for (BlockPos l_Pos : l_Blocks)
            {
                BlockState l_State2 = playerContext.world().getBlockState(l_Pos);

                if (l_State2.getBlock() instanceof AirBlock)
                    continue;

                for (final Direction side : Direction.values())
                {
                    final BlockPos neighbor = pos.offset(side.getNormal());

                    boolean l_IsWater = playerContext.world().getBlockState(neighbor).getBlock() == Blocks.WATER;

                    // TODO: make sure this works
                    VoxelShape collisionShape = playerContext.world().getBlockState(neighbor).getCollisionShape(playerContext.world(), neighbor);
                    boolean hasCollision = collisionShape != Shapes.empty();
                    if (hasCollision)
                    {
                        return ValidResult.Ok;
                    }
                }
            }

            return ValidResult.NoNeighbors;
        }

        return ValidResult.AlreadyBlockThere;
    }

    private float[] getLegitRotations(Vec3 vec) {
        Vec3 eyesPos = getEyesPos();

        double diffX = vec.x - eyesPos.x;
        double diffY = vec.y - eyesPos.y;
        double diffZ = vec.z - eyesPos.z;

        double diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);

        float yaw = (float) Math.toDegrees(Math.atan2(diffZ, diffX)) - 90F;
        float pitch = (float) -Math.toDegrees(Math.atan2(diffY, diffXZ));

        return new float[]
                { playerContext.player().getYRot() + Mth.wrapDegrees(yaw - playerContext.player().getYRot()),
                        playerContext.player().getXRot() + Mth.wrapDegrees(pitch - playerContext.player().getXRot()) };
    }

    private Vec3 getEyesPos() {
        return new Vec3(playerContext.player().getX(), playerContext.player().getEyeY(), playerContext.player().getZ());
    }

    private void faceVectorPacketInstant(Vec3 vec) {
        float[] rotations = getLegitRotations(vec);

        playerContext.player().connection.send(new ServerboundMovePlayerPacket.Rot(rotations[0], rotations[1], playerContext.player().onGround()));
    }

    public void setTarget(BlockPos pos) {
        instantMinePacketCancel = false;
        playerContext.player().connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                pos, Direction.DOWN));
        instantMinePacketCancel = true;
        playerContext.player().connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                pos, Direction.DOWN));
        instantMineDirection = Direction.DOWN;
        instantMineLastBlock = pos;
    }

    public int timer() {
        return timer;
    }
}
