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
import baritone.api.utils.input.Input;
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
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Shulker;
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
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    public AABB renderAreaLiquid() {
        return renderAreaLiquid;
    }

    public HashMap<BlockPos, Color> renderBlocksBuilding() {
        return renderBlocksBuilding;
    }

    private AABB renderAreaLiquid = null;
    private final HashMap<BlockPos, Color> renderBlocksBuilding = new HashMap<>();

    public List<String> lastMismatches() {
        return lastMismatches;
    }

    private final ArrayList<String> lastMismatches = new ArrayList<>();

    public Rotation floatingFixReachablesRemoveFirst() {
        if (!floatingFixReachables.isEmpty()) {
            return floatingFixReachables.removeFirst();
        }
        return null;
    }

    private final ArrayList<Rotation> floatingFixReachables = new ArrayList<>();
    static final List<BlockState> blackListBlocks = Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.LAVA.defaultBlockState(), Blocks.FIRE.defaultBlockState(), Blocks.BROWN_MUSHROOM.defaultBlockState(), Blocks.RED_MUSHROOM.defaultBlockState(), Blocks.MAGMA_BLOCK.defaultBlockState(), Blocks.SOUL_SAND.defaultBlockState(), Blocks.SOUL_SOIL.defaultBlockState());
    private Settings settings = BaritoneAPI.getSettings();
    private State currentState;
    private HighwayState previousState = HighwayState.Nothing;
    private HighwayState emergencyEatReturnState = HighwayState.Nothing;
    private Entity currentMobTarget = null;
    private BetterBlockPos combatReturnPos = null;
    private Entity thiefTarget = null;
    private HighwayState thiefHuntReturnState = null;
    private BetterBlockPos thiefHuntStartPos = null;
    private int thiefHuntFailCooldown = 0;
    private java.util.UUID thiefHuntIgnoredThief = null; // the one thief a failed hunt gave up on; only it is ignored while the cooldown runs
    private double thiefHuntBestDist = Double.MAX_VALUE; // closest we've ever been to the current hunt target
    private int preMineShulkerCount = -1; // inventory shulker count snapshotted before a refill box is mined
    private BetterBlockPos recoveryTarget = null;
    private int recoveryExtraBack = 0;
    private boolean recoveryHoldingJump = false; // here, not on FallRecovery: transitionTo throws the state object away, and a latched jump key would outlive it
    private Boolean recoverySwimThroughLavaSaved = null; // non-null while we've overridden allowSwimThroughLava for recovery
    private boolean invalidBlockFixActive = false;
    private int invalidBlockFixNoPathTicks = 0;
    private BlockPos invalidBlockFixScanStart = null; // stretch the running repair was dispatched for, in build-line coords
    private BlockPos invalidBlockFixScanStartLiq = null;
    private int invalidBlockFixScanDist = 0;
    private final ArrayList<String> invalidBlockFixLastMismatches = new ArrayList<>(); // mismatch snapshot of the last check, to spot progress
    private static final int TRAVEL_STALL_TICKS = 200; // a walk that hasn't closed any distance in this long isn't going to
    private static final int SHULKER_PLACE_ANCHOR_BACK = 7; // where a box goes when the lane behind us is fine
    private static final int SHULKER_PLACE_MAX_BACK = 31; // how far back the scan walks looking for one that isn't
    private static final int SHULKER_PLACE_MAX_AHEAD = 16; // last resort, for a start with nothing usable behind it
    private static final int SHULKER_OPEN_RETRIES = 3; // plain re-places to spend before treating an open as stuck
    private static final int SHULKER_OPEN_UNBLOCK_TRIES = 3; // digs at whatever the lid is pushing into, after those
    private static final int SHULKER_SPOT_MEMORY = 64; // how many hopeless spots to remember before starting over
    private BlockPos shulkerOpenFailSpot = null; // spot the open failures below are counted against
    private int shulkerOpenFailTries = 0;
    private final Set<BlockPos> badShulkerSpots = new HashSet<>();
    private static final int SHULKER_SIGHT_DIGS = 6; // a reach-length of blocks is the most that can be in the way
    private static final int SHULKER_SIGHT_WALKS = 3; // rounds of stepping back onto the standing spot
    private static final int SHULKER_SIGHT_DIG_SPINUP = 5; // ticks a dispatched clearArea takes to read as an active builder
    private static final int SHULKER_STAND_CLEAR_TRIES = 3; // walks off the spot to try before giving it up
    private static final int SHULKER_PLACE_REFUSALS = 40; // refused clicks to sit through before the spot is the problem
    private BlockPos placeRefusedSpot = null;
    private int placeRefusedTries = 0;
    private BlockPos standClearSpot = null; // spot the step-off-it attempts below are counted against
    private int standClearTries = 0;
    private BlockPos shulkerSightFailSpot = null; // spot the sight failures below are counted against
    private int shulkerSightDigs = 0;
    private int shulkerSightWalks = 0;
    private long shulkerSightLastDigTick = Long.MIN_VALUE / 2;
    private boolean travellingToEnd = false; // walking up an already-built stretch because the builder found nothing to do
    private Goal travelGoal = null; // the goal object we handed the custom goal process, so we only ever cancel our own walk
    private BetterBlockPos travelTarget = null;
    private int travelBestSteps = Integer.MAX_VALUE; // closest we've come to travelTarget, in slices
    private int travelNoProgressTicks = 0;
    private int travelStallCooldown = 0; // ticks to leave the walk alone after one failed to make progress
    private CompositeSchematic schematic;
    private WhiteBlackSchematic liqCheckSchem;
    private BetterBlockPos originBuild;
    private BetterBlockPos firstStartingPos;
    private BetterBlockPos endPos; // on the highway line at HighwayBuild Y; null = build forever
    private Vec3 originVector = new Vec3(0, 0, 0);
    private Vec3 liqOriginVector = new Vec3(0, 0, 0);
    private Vec3 backPathOriginVector = new Vec3(0, 0, 0);
    private Vec3 eChestEmptyShulkOriginVector = new Vec3(0, 0, 0);
    private Vec3i highwayDirection = new Vec3i(1, 0, -1);
    private boolean paving = false;
    private boolean paused = false;
    private String pauseReason = null;
    private boolean cursorStackNonEmpty = false;

    public BlockPos placeLoc() {
        return placeLoc;
    }

    public void setPlaceLoc(BlockPos placeLoc) {
        this.placeLoc = new BlockPos(placeLoc.getX(), placeLoc.getY(), placeLoc.getZ());
    }

    private BlockPos placeLoc;
    private int picksToHave = 5;
    private boolean enderChestHasPickShulks = true;
    private boolean enderChestHasEnderShulks = true;
    private boolean enderChestHasGappleShulks = true;
    private boolean enderChestHasTotemShulks = true;
    private boolean refillingEnderChests = false;
    private boolean refillingGapples = false;
    private boolean refillingTotems = false;
    private boolean stashingShulker = false;
    private BlockPos enderChestAccessLoc = null;
    private ShulkerType picksToUse;
    private BetterBlockPos cachedPlayerFeet = null;
    private int startShulkerCount = 0;
    private boolean lostShulkerRelogAttempted = false;

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

    private int throughWallPlaceStreak = 0;
    private BlockPos throughWallVerifyPos = null;
    private int throughWallVerifyAge = 0;
    private boolean throughWallsSuppressed = false;

    public boolean liquidThroughWalls() {
        return settings.highwayLiquidRemovalThroughWalls.value && !throughWallsSuppressed;
    }

    public int throughWallPlaceStreak() {
        return throughWallPlaceStreak;
    }

    public void noteThroughWallPlace(BlockPos target) {
        throughWallPlaceStreak++;
        if (throughWallVerifyPos == null) {
            throughWallVerifyPos = target;
            throughWallVerifyAge = 0;
        }
    }

    public void tickThroughWallVerify() {
        if (throughWallVerifyPos != null && ++throughWallVerifyAge >= 10) {
            if (getIssueType(throughWallVerifyPos) == HighwayBlockState.Blocks) {
                throughWallPlaceStreak = 0;
            }
            throughWallVerifyPos = null;
        }
    }

    public void suppressThroughWalls() {
        throughWallsSuppressed = true;
        throughWallPlaceStreak = 0;
        throughWallVerifyPos = null;
    }

    public void resetThroughWallDetection() {
        throughWallsSuppressed = false;
        throughWallPlaceStreak = 0;
        throughWallVerifyPos = null;
    }

    private int timer = 0;

    // --- Server round-trip tracking -------------------------------------------------------------

    /** containerId of the last full content sync the server sent. Written from the netty thread. */
    private volatile int syncedContainerId = NO_CONTAINER;
    /** Whether the server has sent our own inventory since we last joined. */
    private volatile boolean inventorySynced = true;
    private int ticksSinceInventoryDesync = 0;
    /** Ticks the currently open (non-inventory) container menu has been open for. */
    private int containerOpenTicks = 0;
    private int openContainerId = NO_CONTAINER;
    /** Main-thread snapshot of {@link #syncedContainerId} taken at the start of the previous tick. */
    private int syncSeenLastTick = NO_CONTAINER;
    /** Whether the open container's slots have definitely been filled in on the main thread. */
    private boolean containerContentsApplied = false;
    /** Our own tick counter: a respawn hands us a fresh player whose tickCount restarts at zero. */
    private long contextTick = 0;
    private long lastContainerClickTick = Long.MIN_VALUE / 4;

    private static final int NO_CONTAINER = Integer.MIN_VALUE;

    /**
     * Latch a full container content sync. Called off the netty thread from the packet handler, so
     * the field is volatile and nothing here touches the world or the player.
     */
    public void noteContainerSync(int containerId) {
        syncedContainerId = containerId;
        if (containerId == 0) {
            inventorySynced = true;
        }
    }

    /** A fresh join/respawn invalidates the inventory we were reading counts off. */
    public void noteInventoryDesynced() {
        inventorySynced = false;
        syncedContainerId = NO_CONTAINER;
        ticksSinceInventoryDesync = 0;
    }

    /**
     * Whether inventory counts can be trusted. Straight after a login or a dimension change the
     * client's copy is empty, and acting on it would send us on a storage trip for items we are
     * actually carrying. Assumed true at startBuild (the player is already in-world by then) and
     * only cleared by a login/respawn packet.
     */
    public boolean inventorySynced() {
        if (inventorySynced) {
            return true;
        }
        // Fallback so an unusual join sequence that never resends the inventory can't wedge every
        // threshold forever. onTick already refuses to run while the inventory is fully empty.
        return ticksSinceInventoryDesync >= settings.highwayContainerSyncTimeout.value
                && !playerContext.player().getInventory().isEmpty();
    }

    /**
     * Whether the contents of the open container can be trusted - i.e. the server has sent the
     * full slot list for this exact container id, so an empty slot really is empty rather than
     * not-yet-delivered. This is the check that keeps a slow/lagging sync from being read as "this
     * shulker is empty"; the timeout below is only a wedge guard, not the normal path.
     */
    public boolean openContainerReady() {
        AbstractContainerMenu menu = playerContext.player().containerMenu;
        if (menu == playerContext.player().inventoryMenu) {
            return false;
        }
        return containerContentsApplied || containerOpenTicks >= settings.highwayContainerSyncTimeout.value;
    }

    /**
     * Container clicks are spaced out rather than fired every tick: the loot/deposit helpers move a
     * slot per call and the server (and anticheat) sees a click packet for each one.
     */
    public boolean containerClickReady() {
        return contextTick - lastContainerClickTick >= settings.highwayContainerClickInterval.value;
    }

    public void noteContainerClick() {
        lastContainerClickTick = contextTick;
    }

    private long lastObsidianStuckLogTick = Long.MIN_VALUE / 2;

    /** Rate limit for the "obsidian doesn't fit" log: states are re-created on every transition, so the throttle lives here. */
    public boolean obsidianStuckLogDue() {
        if (contextTick - lastObsidianStuckLogTick < 200) {
            return false;
        }
        lastObsidianStuckLogTick = contextTick;
        return true;
    }

    private void tickContainerSync() {
        contextTick++;
        if (!inventorySynced) {
            ticksSinceInventoryDesync++;
        }
        AbstractContainerMenu menu = playerContext.player().containerMenu;
        int latchedNow = syncedContainerId;
        if (menu == playerContext.player().inventoryMenu) {
            containerOpenTicks = 0;
            openContainerId = NO_CONTAINER;
            containerContentsApplied = false;
            syncSeenLastTick = latchedNow;
            return;
        }
        if (menu.containerId != openContainerId) {
            openContainerId = menu.containerId;
            containerOpenTicks = 0;
            containerContentsApplied = false;
            syncSeenLastTick = latchedNow;
            return;
        }
        containerOpenTicks++;
        // The packet handler latches from the netty thread, but the slots themselves are filled in
        // on the main thread when Minecraft drains its task queue at the top of a tick. Only accept
        // a latch that was already set when the previous tick began, so the fill can never still be
        // pending when a state reads the slots - otherwise a sync arriving mid-tick would make a
        // full container look empty, which is exactly what this check exists to prevent.
        if (syncSeenLastTick == menu.containerId) {
            containerContentsApplied = true;
        }
        syncSeenLastTick = latchedNow;
    }

    // --- Inventory threshold confirmation ---------------------------------------------------------

    private HighwayState pendingThresholdState = null;
    private String pendingThresholdKey = null;
    private long pendingThresholdTick = 0;

    /**
     * Double-check a tripped inventory threshold before committing to something expensive (a storage
     * trip, or pausing the build). A count read off an inventory the server hasn't sent us is zero,
     * which used to be covered by standing still for 120 ticks before every such decision; the
     * inventory sync latch covers that case directly now, and the short confirm window only rides
     * out a count that flickers while items are still being moved around.
     *
     * <p>Returns false (and keeps returning false) until the same threshold has stayed tripped for
     * {@code highwayThresholdConfirmTicks}. Unlike the old gate this does not stop the builder, the
     * travel walk or the correctness scans while it waits.
     */
    public boolean thresholdConfirmed(String what) {
        if (!inventorySynced) {
            return false;
        }
        long now = contextTick;
        if (pendingThresholdState != currentState.getState() || !what.equals(pendingThresholdKey)) {
            pendingThresholdState = currentState.getState();
            pendingThresholdKey = what;
            pendingThresholdTick = now;
            Helper.HELPER.logDebug(what + " under threshold, confirming over " + settings.highwayThresholdConfirmTicks.value + " ticks.");
            return false;
        }
        if (now - pendingThresholdTick < settings.highwayThresholdConfirmTicks.value) {
            return false;
        }
        clearThresholdConfirm();
        return true;
    }

    public void clearThresholdConfirm() {
        pendingThresholdState = null;
        pendingThresholdKey = null;
    }

    public int walkBackTimer() {
        return walkBackTimer;
    }

    private int walkBackTimer = 0;
    private int checkBackTimer = 0;
    private int stuckTimer = 0;
    private float cachedHealth = 0.0f;
    private float cachedAbsorption = 0.0f;
    private boolean walkCentering = false;

    public boolean instantMineCalibrated() {
        return instantMineCalibrated;
    }

    public void setInstantMineCalibrated(boolean instantMineCalibrated) {
        this.instantMineCalibrated = instantMineCalibrated;
        if (!instantMineCalibrated) {
            this.instantMineCalibrationHitting = false;
        }
    }

    private boolean instantMineCalibrated = false;
    // Whether we were mid-break last tick during the legit calibration break. The client-side
    // isDestroying flag must be saved/restored each tick since we never actually hold the attack key.
    private boolean instantMineCalibrationHitting = false;

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

    public int lastEchestPlaceTick() {
        return lastEchestPlaceTick;
    }

    public void setLastEchestPlaceTick(int lastEchestPlaceTick) {
        this.lastEchestPlaceTick = lastEchestPlaceTick;
    }

    private int lastEchestPlaceTick = Integer.MIN_VALUE;

    public boolean echestPlacedServerSide() {
        return echestPlacedServerSide;
    }

    public void setEchestPlacedServerSide(boolean echestPlacedServerSide) {
        this.echestPlacedServerSide = echestPlacedServerSide;
    }

    private boolean echestPlacedServerSide = false;

    public boolean farmPlaceLocResynced() {
        return farmPlaceLocResynced;
    }

    public void setFarmPlaceLocResynced(boolean farmPlaceLocResynced) {
        this.farmPlaceLocResynced = farmPlaceLocResynced;
    }

    private boolean farmPlaceLocResynced = false;

    public Item instantMineOriginalOffhandItem() {
        return instantMineOriginalOffhandItem;
    }

    public void setInstantMineOriginalOffhandItem(Item instantMineOriginalOffhandItem) {
        this.instantMineOriginalOffhandItem = instantMineOriginalOffhandItem;
    }

    private Item instantMineOriginalOffhandItem;

    /**
     * Loose ender chest count the running farm session stops at: the configured keep, or more when
     * the obsidian would not fit (see enderChestFarmKeep). Set on farm entry; never below the setting.
     */
    public int farmEnderChestsToKeep() {
        return Math.max(settings.highwayEnderChestsToKeep.value, farmEnderChestsToKeep);
    }

    public void setFarmEnderChestsToKeep(int farmEnderChestsToKeep) {
        this.farmEnderChestsToKeep = farmEnderChestsToKeep;
    }

    private int farmEnderChestsToKeep = -1;

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
    private boolean inQueue = false;
    private HighwayState stateBeforeQueue = HighwayState.Nothing;
    
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

    public BetterBlockPos endPos() {
        return endPos;
    }

    public void setEndPos(BetterBlockPos endPos) {
        this.endPos = endPos;
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
    
    public float cachedAbsorption() {
        return cachedAbsorption;
    }
    
    public void setCachedAbsorption(float cachedAbsorption) {
        this.cachedAbsorption = cachedAbsorption;
    }

    // All states of the two gapple-eat flows; used to release eat-side effects when any code
    // path (mob combat, floating fix, stuck handling, ...) yanks the state machine out of them.
    private static final EnumSet<HighwayState> GAPPLE_EAT_STATES = EnumSet.of(
            HighwayState.EmergencyGapplePrep, HighwayState.EmergencyGapplePreEat, HighwayState.EmergencyGappleEat,
            HighwayState.LiquidRemovalGapplePrep, HighwayState.LiquidRemovalGapplePreEat, HighwayState.LiquidRemovalGappleEat
    );

    public void transitionTo(HighwayState nextState) {
        Helper.HELPER.logDebug(currentState + " -> " + nextState);
        // The travel walk belongs to BuildingHighway; never let it outlive the state that drives it,
        // or the next state's pathing runs with our goal still set and we cancel a walk (or a builder)
        // that isn't ours several states later.
        if (currentState != null && currentState.getState() == HighwayState.BuildingHighway && nextState != HighwayState.BuildingHighway) {
            stopTravelTowardsEnd();
        }
        // Leaving the eat flow by any path: release the use key and drop the hitResult
        // suppression so they can't leak into (and act during) other states.
        if (currentState != null && GAPPLE_EAT_STATES.contains(currentState.getState())
                && !GAPPLE_EAT_STATES.contains(nextState)) {
            NetherHighwayBuilderBehavior.suppressHitResult = false;
            if (playerContext.minecraft() != null) {
                playerContext.minecraft().options.keyUse.setDown(false);
            }
        }
        if (currentState != null && currentState.getState() != nextState) {
            currentState.onExit(this);
        }
        currentState = StateFactory.getState(nextState);
    }

    public float gappleEatHealthThreshold() {
        return Math.min(settings.highwayGappleEatHealthThreshold.value, 20f);
    }

    public int gappleEatFoodThreshold() {
        return Math.min(settings.highwayGappleEatFoodThreshold.value, 19);
    }

    public int fireRestMinDuration() {
        return Math.min(settings.highwayFireRestMinDuration.value, 5900);
    }

    public HighwayState previousState() {
        return previousState;
    }

    public void setPreviousState(HighwayState state) {
        this.previousState = state;
    }

    public HighwayState emergencyEatReturnState() {
        return emergencyEatReturnState;
    }

    public void setEmergencyEatReturnState(HighwayState state) {
        this.emergencyEatReturnState = state;
    }

    public Entity currentMobTarget() {
        return currentMobTarget;
    }

    public void setCurrentMobTarget(Entity entity) {
        this.currentMobTarget = entity;
    }

    public Entity thiefTarget() {
        return thiefTarget;
    }

    public void setThiefTarget(Entity entity) {
        this.thiefTarget = entity;
    }

    public HighwayState thiefHuntReturnState() {
        return thiefHuntReturnState;
    }

    public BetterBlockPos thiefHuntStartPos() {
        return thiefHuntStartPos;
    }

    public void ignoreThief(Entity thief, int ticks) {
        this.thiefHuntIgnoredThief = thief.getUUID();
        this.thiefHuntFailCooldown = ticks;
    }

    public double thiefHuntBestDist() {
        return thiefHuntBestDist;
    }

    public void setThiefHuntBestDist(double dist) {
        this.thiefHuntBestDist = dist;
    }

    public int preMineShulkerCount() {
        return preMineShulkerCount;
    }

    public void setPreMineShulkerCount(int count) {
        this.preMineShulkerCount = count;
    }

    // Whether the box mined by the last Mining*Shulker state has reached the inventory.
    public boolean minedShulkerLanded() {
        return preMineShulkerCount < 0 || getShulkerCountInventory(ShulkerType.Any) > preMineShulkerCount;
    }

    public void resetThiefHunt() {
        thiefTarget = null;
        thiefHuntReturnState = null;
        thiefHuntStartPos = null;
        thiefHuntFailCooldown = 0;
        thiefHuntIgnoredThief = null;
        thiefHuntBestDist = Double.MAX_VALUE;
        preMineShulkerCount = -1;
    }

    public BetterBlockPos combatReturnPos() {
        return combatReturnPos;
    }

    public void setCombatReturnPos(BetterBlockPos pos) {
        this.combatReturnPos = pos;
    }

    public BetterBlockPos recoveryTarget() {
        return recoveryTarget;
    }

    public void setRecoveryTarget(BetterBlockPos pos) {
        this.recoveryTarget = pos;
    }

    /**
     * Temporarily treat lava as a swimmable fluid so a fall recovery can swim up and out of a lava lake
     * (we have fire resistance during recovery). Saves the user's original {@code allowSwimThroughLava}
     * value so it can be restored afterwards.
     */
    public void enableLavaSwimmingForRecovery() {
        if (recoverySwimThroughLavaSaved == null) {
            recoverySwimThroughLavaSaved = settings.allowSwimThroughLava.value;
            settings.allowSwimThroughLava.value = true;
        }
    }

    public void restoreLavaSwimming() {
        if (recoverySwimThroughLavaSaved != null) {
            settings.allowSwimThroughLava.value = recoverySwimThroughLavaSaved;
            recoverySwimThroughLavaSaved = null;
        }
    }

    /** Clears all fall-recovery state, releases the keys we may have held, and restores overridden settings. */
    public void resetRecovery() {
        restoreLavaSwimming();
        recoveryTarget = null;
        recoveryExtraBack = 0;
        recoveryHoldingJump = false;
        NetherHighwayBuilderBehavior.suppressHitResult = false;
        if (playerContext.minecraft() != null) {
            playerContext.minecraft().options.keyJump.setDown(false);
            playerContext.minecraft().options.keyUse.setDown(false);
        }
    }

    /** Hold (or release) the jump key that floats us at the lava surface during recovery. */
    public void setRecoveryHoldingJump(boolean holding) {
        if (recoveryHoldingJump == holding) {
            return;
        }
        recoveryHoldingJump = holding;
        if (playerContext.minecraft() != null) {
            playerContext.minecraft().options.keyJump.setDown(holding);
        }
    }

    /** Search a bit further back next time we look for a spot to return to. */
    public void growRecoveryBack() {
        int step = Math.max(1, settings.highwayRecoveryBackDistance.value);
        recoveryExtraBack = Math.min(recoveryExtraBack + step, settings.highwayRecoveryMaxSearch.value);
    }

    /**
     * Find a standable, already-built spot on the highway behind the player to path back to after a fall.
     * Starts {@code highwayRecoveryBackDistance} (+ any grown offset) blocks back and searches further
     * until a column with solid footing and clear feet/head is found, up to {@code highwayRecoveryMaxSearch}.
     *
     * @return the feet position to stand at, or null if nothing suitable was found
     */
    public BetterBlockPos computeRecoveryTarget() {
        int dirX = highwayDirection.getX();
        int dirZ = highwayDirection.getZ();
        // perpendicular to the highway in the XZ plane
        int perpX = dirZ;
        int perpZ = -dirX;
        int floorY = settings.highwayLowestY.value + (paving ? 1 : 0);
        BetterBlockPos feet = playerContext.playerFeet();
        int maxLateral = settings.highwayWidth.value + 2;
        int start = Math.max(1, settings.highwayRecoveryBackDistance.value) + recoveryExtraBack;
        int max = Math.max(start, settings.highwayRecoveryMaxSearch.value);
        for (int d = start; d <= max; d++) {
            int baseX = feet.x - dirX * d;
            int baseZ = feet.z - dirZ * d;
            // scan laterally outward from our column to snap onto a built part of the highway
            for (int w = 0; w <= maxLateral; w++) {
                for (int sign = (w == 0 ? 1 : -1); sign <= 1; sign += 2) {
                    int fx = baseX + perpX * w * sign;
                    int fz = baseZ + perpZ * w * sign;
                    if (MovementHelper.canWalkOn(baritone.bsi, fx, floorY, fz)
                            && MovementHelper.canWalkThrough(baritone.bsi, fx, floorY + 1, fz)
                            && MovementHelper.canWalkThrough(baritone.bsi, fx, floorY + 2, fz)) {
                        return new BetterBlockPos(fx, floorY + 1, fz);
                    }
                }
            }
        }
        return null;
    }

    public int highwayFeetY() {
        return settings.highwayMainY.value + (paving ? 1 : 0);
    }

    public int highwayFloorY() {
        return highwayFeetY() - 1;
    }

    /**
     * Lifts a point on the walking/placement lane onto the pavement when the road under it is
     * already obsidian. A digging-only run aims at highwayMainY because that's the floor it digs
     * down to, but a dig that starts on (or runs through) an already-paved stretch finds obsidian
     * sitting there: the shulker, the ender chest and our own feet all belong one block up, exactly
     * where a paving run puts them. Without this the bot mines the road out from under itself and
     * then keeps failing to place into the block it just tried to stand in.
     */
    public BetterBlockPos liftOntoPavement(BetterBlockPos lanePos) {
        if (paving) {
            return lanePos; // already one above the obsidian the printer lays
        }
        Block at = baritone.bsi.get0(lanePos.x, lanePos.y, lanePos.z).getBlock();
        return (at == Blocks.OBSIDIAN || at == Blocks.CRYING_OBSIDIAN) ? lanePos.above() : lanePos;
    }

    /**
     * True if the paved road is directly over our head. This is what separates being underneath the
     * highway from standing in ground the printer simply hasn't mined yet: raw netherrack overhead
     * is not a road, it's the next thing to dig, and it must never trigger a recovery.
     */
    public boolean roadOverhead() {
        BetterBlockPos feet = playerContext.playerFeet();
        Block above = baritone.bsi.get0(feet.x, highwayFloorY(), feet.z).getBlock();
        return above == Blocks.OBSIDIAN || above == Blocks.CRYING_OBSIDIAN;
    }

    /**
     * True if the highway's walking space is open above our column, i.e. this stretch is already dug
     * out. That's the boat pit case: we're below the highway where the floor has been cleared away,
     * so restarting the printer would pave it shut over our head.
     */
    public boolean highwayWalkSpaceOpen() {
        BetterBlockPos feet = playerContext.playerFeet();
        return passableForRecovery(feet.x, highwayFeetY(), feet.z)
                && passableForRecovery(feet.x, highwayFeetY() + 1, feet.z);
    }

    /** True if we're stuck below the road with it sealing us in, so we have to walk back on top of it. */
    public boolean sealedUnderHighway() {
        return underRoadChecksApply()
                && playerContext.playerFeet().y < highwayFloorY()
                && roadOverhead();
    }

    /**
     * True if we're below the highway somewhere the printer could pave the floor in on top of us:
     * either the road is already over our head, or we're in a pit under a dug out stretch that is
     * still waiting to be paved.
     */
    public boolean belowBuiltHighway() {
        return underRoadChecksApply()
                && playerContext.playerFeet().y < highwayFeetY()
                && (roadOverhead() || highwayWalkSpaceOpen());
    }

    /** A digging build lays no floor, so there is nothing that could ever pave us in. */
    private boolean underRoadChecksApply() {
        return paving && settings.highwayUnderRoadRecovery.value;
    }

    /** Air, liquid, or something the printer replaces - nothing that could box us in. */
    private boolean passableForRecovery(int x, int y, int z) {
        BlockState state = baritone.bsi.get0(x, y, z);
        return state.getBlock() instanceof AirBlock || state.getBlock() instanceof LiquidBlock
                || MovementHelper.isReplaceable(x, y, z, state, baritone.bsi);
    }

    public boolean invalidBlockFixActive() {
        return invalidBlockFixActive;
    }

    public BlockPos invalidBlockFixScanStart() {
        return invalidBlockFixScanStart;
    }

    public BlockPos invalidBlockFixScanStartLiq() {
        return invalidBlockFixScanStartLiq;
    }

    public int invalidBlockFixScanDist() {
        return invalidBlockFixScanDist;
    }

    public void startInvalidBlockFix(BlockPos scanStart, BlockPos scanStartLiq, int scanDist) {
        invalidBlockFixActive = true;
        invalidBlockFixNoPathTicks = 0;
        invalidBlockFixScanStart = scanStart;
        invalidBlockFixScanStartLiq = scanStartLiq;
        invalidBlockFixScanDist = scanDist;
        invalidBlockFixLastMismatches.clear();
        invalidBlockFixLastMismatches.addAll(lastMismatches);
    }

    public void noteInvalidBlockFixProgress(List<String> mismatches) {
        if (invalidBlockFixLastMismatches.equals(mismatches)) {
            return;
        }
        invalidBlockFixLastMismatches.clear();
        invalidBlockFixLastMismatches.addAll(mismatches);
        invalidBlockFixNoPathTicks = 0;
    }

    public void clearInvalidBlockFix() {
        invalidBlockFixActive = false;
        invalidBlockFixNoPathTicks = 0;
        invalidBlockFixScanStart = null;
        invalidBlockFixScanStartLiq = null;
        invalidBlockFixScanDist = 0;
        invalidBlockFixLastMismatches.clear();
    }

    /** True if the dispatched invalid-block fix has gone too long without an active path (it's stuck). */
    public boolean invalidBlockFixStalled() {
        return invalidBlockFixNoPathTicks > settings.highwayInvalidBlockFixTimeout.value;
    }

    private boolean isPlayerWearingGoldArmor(net.minecraft.world.entity.player.Player player) {
        return player.getInventory().armor.stream().anyMatch(stack ->
                stack.is(net.minecraft.world.item.Items.GOLDEN_HELMET)
                || stack.is(net.minecraft.world.item.Items.GOLDEN_CHESTPLATE)
                || stack.is(net.minecraft.world.item.Items.GOLDEN_LEGGINGS)
                || stack.is(net.minecraft.world.item.Items.GOLDEN_BOOTS));
    }

    private boolean isEntityInHighwayCorridor(Entity entity) {
        if (schematic == null) return false;
        Vec3 dir = new Vec3(highwayDirection.getX(), highwayDirection.getY(), highwayDirection.getZ());
        if (dir.lengthSqr() == 0) return true;
        BetterBlockPos closest = getClosestPoint(originVector, dir, entity.position(), LocationType.HighwayBuild);
        BlockPos ep = entity.blockPosition();
        // widthX/lengthZ == 1 means that's the along-highway axis; getClosestPoint rounds to the nearest block
        // so we skip the size-1 dimension to avoid off-by-one failures at block boundaries
        boolean xOk = schematic.widthX() == 1 || (ep.getX() >= closest.getX() && ep.getX() < closest.getX() + schematic.widthX());
        boolean zOk = schematic.lengthZ() == 1 || (ep.getZ() >= closest.getZ() && ep.getZ() < closest.getZ() + schematic.lengthZ());
        boolean yOk = ep.getY() >= closest.getY() && ep.getY() < closest.getY() + schematic.heightY();
        return xOk && yOk && zOk;
    }

    public java.util.Optional<Entity> findMobTargetingPlayer() {
        net.minecraft.world.entity.player.Player player = playerContext.player();
        boolean wearingGold = isPlayerWearingGoldArmor(player);

        // Collect ghasts that own a nearby fireball
        java.util.Set<Entity> ghastsThreatening = playerContext.entitiesStream()
                .filter(e -> e instanceof net.minecraft.world.entity.projectile.LargeFireball && e.isAlive())
                .filter(e -> e.distanceToSqr(player) <= 8.0 * 8.0)
                .map(e -> ((net.minecraft.world.entity.projectile.LargeFireball) e).getOwner())
                .filter(owner -> owner instanceof net.minecraft.world.entity.monster.Ghast)
                .collect(java.util.stream.Collectors.toSet());

        // Sticky ghast: if we're already tracking a ghast, keep it as a threat while it's alive and within 40 blocks
        if (currentMobTarget instanceof net.minecraft.world.entity.monster.Ghast
                && currentMobTarget.isAlive()
                && currentMobTarget.distanceToSqr(player) <= 40.0 * 40.0) {
            ghastsThreatening.add(currentMobTarget);
        }

        net.minecraft.world.damagesource.DamageSource lastDamage = player.getLastDamageSource();
        final Entity lastAttacker = lastDamage != null ? lastDamage.getEntity() : null;

        double aggroRangeSq = settings.highwayMobAggroRange.value * settings.highwayMobAggroRange.value;
        double magmaRangeSq = settings.highwayMagmaCubeAggroRange.value * settings.highwayMagmaCubeAggroRange.value;
        double maxRangeSq = settings.highwayMobMaxAggroRange.value * settings.highwayMobMaxAggroRange.value;

        return playerContext.entitiesStream()
                .filter(e -> e instanceof net.minecraft.world.entity.LivingEntity && e.isAlive())
                .filter(e -> !(e instanceof net.minecraft.world.entity.player.Player))
                .filter(e -> e == lastAttacker
                        // isAggressive is the synced anger flag: set for piglins (even when we wear gold armor),
                        // brutes, zombified piglins, skeletons/wither skeletons, zoglins and endermen
                        || (e instanceof net.minecraft.world.entity.monster.Enemy && e instanceof net.minecraft.world.entity.Mob
                            && ((net.minecraft.world.entity.Mob) e).isAggressive()
                            && e.distanceToSqr(player) <= aggroRangeSq)
                        // hoglins never set the aggressive flag and zoglins attack on sight; both charge from up to 16 blocks
                        || ((e instanceof net.minecraft.world.entity.monster.Zoglin || e instanceof net.minecraft.world.entity.monster.hoglin.Hoglin) && e.distanceToSqr(player) <= aggroRangeSq)
                        || (e instanceof net.minecraft.world.entity.monster.piglin.Piglin && !wearingGold
                            && ((net.minecraft.world.entity.monster.piglin.Piglin) e).isAdult()
                            && e.distanceToSqr(player) <= aggroRangeSq)
                        || (e instanceof net.minecraft.world.entity.monster.piglin.PiglinBrute && e.distanceToSqr(player) <= aggroRangeSq)
                        || (e instanceof net.minecraft.world.entity.monster.MagmaCube
                            && e.distanceToSqr(player) <= magmaRangeSq)
                        // a lit blaze is in attack mode (blazes override isOnFire to their synced charged flag)
                        || (e instanceof net.minecraft.world.entity.monster.Blaze && e.isOnFire() && e.distanceToSqr(player) <= aggroRangeSq)
                        || (e instanceof net.minecraft.world.entity.monster.EnderMan
                            && ((net.minecraft.world.entity.monster.EnderMan) e).isCreepy()
                            && e.distanceToSqr(player) <= aggroRangeSq)
                        || ghastsThreatening.contains(e))
                .filter(e -> !(e instanceof net.minecraft.world.entity.monster.Ghast) || ghastsThreatening.contains(e))
                .filter(e -> e.distanceToSqr(player) <= maxRangeSq || e instanceof net.minecraft.world.entity.monster.Ghast || e == lastAttacker)
                // corridor only gates pre-emptive proximity targets; angry mobs and whatever hit us get engaged anywhere
                .filter(e -> e == lastAttacker
                        || (e instanceof net.minecraft.world.entity.Mob && ((net.minecraft.world.entity.Mob) e).isAggressive())
                        || ghastsThreatening.contains(e)
                        || isEntityInHighwayCorridor(e))
                .min(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(player)));
    }

    // Only an empty main hand can accept a shulker box (Mob.canReplaceCurrentItem; adults always
    // spawn holding a crossbow or golden sword and a box never beats either). A baby already holding
    // junk can still swap it for a custom-named box via canReplaceEqualItem, so every baby counts.
    public boolean isPotentialShulkerThief(Entity entity) {
        if (!(entity instanceof net.minecraft.world.entity.monster.piglin.Piglin piglin) || !entity.isAlive()) {
            return false;
        }
        return piglin.isBaby() || piglin.getMainHandItem().isEmpty();
    }

    public boolean shulkerThiefNear(BlockPos pos, double range) {
        if (range <= 0) {
            return false;
        }
        double rangeSq = range * range;
        return playerContext.entitiesStream()
                .filter(this::isPotentialShulkerThief)
                .anyMatch(e -> e.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= rangeSq);
    }

    public Optional<Entity> findShulkerThief() {
        return playerContext.entitiesStream()
                .filter(e -> e instanceof net.minecraft.world.entity.monster.piglin.Piglin && e.isAlive())
                .filter(e -> shulkerItemList.contains(((net.minecraft.world.entity.monster.piglin.Piglin) e).getMainHandItem().getItem()))
                .filter(e -> thiefHuntFailCooldown <= 0 || !e.getUUID().equals(thiefHuntIgnoredThief))
                .min(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(playerContext.player())));
    }

    public boolean maybeStartThiefHunt(HighwayState returnState) {
        if (!settings.highwayRecoverStolenShulkers.value) {
            return false;
        }
        Optional<Entity> thief = findShulkerThief();
        if (thief.isEmpty()) {
            return false;
        }
        Helper.HELPER.logDirect("A piglin is carrying a shulker box, hunting it to get the box back.");
        thiefTarget = thief.get();
        thiefHuntReturnState = returnState;
        thiefHuntStartPos = playerContext.playerFeet();
        thiefHuntBestDist = Double.MAX_VALUE;
        baritone.getPathingBehavior().cancelEverything();
        transitionTo(HighwayState.ShulkerThiefHunt);
        resetTimer();
        return true;
    }

    public BlockPos findMisplacedShulkerBox(int distBack, int distAhead) {
        if (schematic == null) {
            return null;
        }
        Vec3 direction = new Vec3(highwayDirection.getX(), highwayDirection.getY(), highwayDirection.getZ());
        Vec3 backPos = new Vec3(playerContext.playerFeet().getX() + (distBack * -highwayDirection.getX()),
                playerContext.playerFeet().getY(),
                playerContext.playerFeet().getZ() + (distBack * -highwayDirection.getZ()));
        BlockPos startPos = getClosestPoint(new Vec3(originVector.x, originVector.y, originVector.z), direction, backPos, LocationType.HighwayBuild);
        for (int i = 0; i < distBack + distAhead; i++) {
            BlockPos curPos = startPos.offset(i * highwayDirection.getX(), 0, i * highwayDirection.getZ());
            for (int y = 0; y < schematic.heightY(); y++) {
                for (int z = 0; z < schematic.lengthZ(); z++) {
                    for (int x = 0; x < schematic.widthX(); x++) {
                        int blockX = x + curPos.getX();
                        int blockY = y + curPos.getY();
                        int blockZ = z + curPos.getZ();
                        if (!baritone.bsi.worldContainsLoadedChunk(blockX, blockZ)) {
                            continue;
                        }
                        if (shulkerBlockList.contains(playerContext.world().getBlockState(new BlockPos(blockX, blockY, blockZ)).getBlock())) {
                            return new BlockPos(blockX, blockY, blockZ);
                        }
                    }
                }
            }
        }
        return null;
    }

    public boolean maybeMineMisplacedShulker(int distBack, int distAhead) {
        BlockPos misplaced = findMisplacedShulkerBox(distBack, distAhead);
        if (misplaced == null) {
            return false;
        }
        Helper.HELPER.logDirect("The missing shulker box got placed into the highway at (" + misplaced.toShortString() + "), mining it back.");
        setPlaceLoc(misplaced);
        baritone.getPathingBehavior().cancelEverything();
        transitionTo(HighwayState.MiningMisplacedShulker);
        resetTimer();
        return true;
    }

    public boolean maybeLostShulkerRelog() {
        if (!settings.highwayLostShulkerRelog.value || lostShulkerRelogAttempted) {
            return false;
        }
        lostShulkerRelogAttempted = true;
        Component dcMsg = Component.literal("Lost a shulker box that isn't visible anywhere nearby. Reconnect to resync");
        Helper.HELPER.logDirect(dcMsg);
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getPathingBehavior().cancelEverything();
        transitionTo(HighwayState.Nothing);
        playerContext.player().connection.getConnection().disconnect(dcMsg);
        return true;
    }

    public void clearLostShulkerRelogAttempt() {
        lostShulkerRelogAttempted = false;
    }

    public void attackEntity(Entity target) {
        Rotation aim = RotationUtils.calcRotationFromVec3d(playerContext.playerHead(),
                target.getBoundingBox().getCenter(), playerContext.playerRotations());
        baritone.getLookBehavior().updateTarget(aim, true);

        if (playerContext.player().getAttackStrengthScale(0.5f) < 1.0f) {
            return;
        }
        Optional<Rotation> serverRotation = baritone.getLookBehavior().getServerRotation();
        if (serverRotation.isEmpty()) {
            return;
        }
        Vec3 eyes = playerContext.playerHead();
        Vec3 dir = RotationUtils.calcLookDirectionFromRotation(serverRotation.get());
        if (target.getBoundingBox().clip(eyes, eyes.add(dir.scale(3.0))).isEmpty()) {
            return;
        }
        playerContext.minecraft().gameMode.attack(playerContext.player(), target);
        playerContext.player().swing(InteractionHand.MAIN_HAND);
    }

    /**
     * The interaction-lane spot {@code back} blocks behind us (negative is ahead). The projection is
     * taken at our own position and stepped from there, because {@link #getClosestPoint} clamps
     * every point to the build start: projecting an already shifted position would snap the whole
     * scan onto the first slice and never reach the pavement laid before this build began.
     */
    private BetterBlockPos shulkerPlaceLocAt(int back) {
        Vec3 origin = new Vec3(backPathOriginVector.x, backPathOriginVector.y, backPathOriginVector.z);
        Vec3 direction = new Vec3(highwayDirection.getX(), highwayDirection.getY(), highwayDirection.getZ());
        Vec3 feet = new Vec3(playerContext.playerFeet().getX(), playerContext.playerFeet().getY(), playerContext.playerFeet().getZ());
        BetterBlockPos lane = getClosestPoint(origin, direction, feet, LocationType.ShulkerEchestInteraction);
        return liftOntoPavement(new BetterBlockPos(lane.offset(back * -highwayDirection.getX(), 0, back * -highwayDirection.getZ())));
    }

    /**
     * Where to set a shulker box (or the obsidian farm's ender chest) down: anchored
     * {@value #SHULKER_PLACE_ANCHOR_BACK} blocks back, walked further back along the lane when that
     * spot can't hold a box or piglins are standing by it, and only then tried ahead of us. Starting
     * a build at the end of an already paved stretch used to hand back the anchor no matter what,
     * which over the drop past the pavement is thin air: with no block left to prop the box up with,
     * placing there can never succeed and the place/support states just ping-pong forever.
     */
    public BetterBlockPos shulkerPlaceLocClearOfThieves() {
        double radius = settings.highwayShulkerTheftGuardRadius.value;
        BetterBlockPos anchor = shulkerPlaceLocAt(SHULKER_PLACE_ANCHOR_BACK);
        boolean anchorUsable = isShulkerPlaceSpotUsable(anchor);
        BetterBlockPos thiefDirty = null;
        for (int back : shulkerPlaceScanOffsets()) {
            BetterBlockPos candidate = back == SHULKER_PLACE_ANCHOR_BACK ? anchor : shulkerPlaceLocAt(back);
            if (!isShulkerPlaceSpotUsable(candidate)) {
                continue;
            }
            if (shulkerThiefNear(candidate, radius)) {
                if (thiefDirty == null) {
                    thiefDirty = candidate;
                }
                continue;
            }
            if (back != SHULKER_PLACE_ANCHOR_BACK) {
                String where = back >= 0 ? (back - SHULKER_PLACE_ANCHOR_BACK) + " blocks further back" : -back + " blocks ahead";
                Helper.HELPER.logDirect(anchorUsable
                        ? "Piglins that could steal the shulker are near the placing location, placing it " + where + "."
                        : "Nothing to set the shulker on at the placing location, placing it " + where + ".");
            }
            return candidate;
        }
        // every usable candidate is dirty; the mining guard and the thief hunt cover the rest
        return thiefDirty != null ? thiefDirty : anchor;
    }

    /** Lane offsets to try, in order: back onto what's already paved, then closer in, then ahead of us. */
    private int[] shulkerPlaceScanOffsets() {
        int[] offsets = new int[SHULKER_PLACE_MAX_BACK + SHULKER_PLACE_MAX_AHEAD + 1];
        int i = 0;
        for (int back = SHULKER_PLACE_ANCHOR_BACK; back <= SHULKER_PLACE_MAX_BACK; back++) {
            offsets[i++] = back;
        }
        for (int back = SHULKER_PLACE_ANCHOR_BACK - 1; back >= -SHULKER_PLACE_MAX_AHEAD; back--) {
            offsets[i++] = back;
        }
        return offsets;
    }

    /**
     * A lane spot we can actually place into and reach: lava-free, with something solid under the
     * box - or, while we still carry a block to build one with, a face a support block can go on -
     * and a floor under the spots the go-to states stand on (two back for the ender chest flow, one
     * ahead for the shulker ones). A spot hanging over the end of the pavement has none of that.
     */
    public boolean isShulkerPlaceSpotUsable(BlockPos placeLoc) {
        if (!isSideStorageSpotSafe(placeLoc)) {
            return false;
        }

        if (isShulkerSpotKnownBad(placeLoc) || !shulkerColumnWorkable(placeLoc)) {
            return false;
        }
        if (!hasFloorUnder(placeLoc) && !(canBuildSupportBlock() && hasSturdyNeighbor(placeLoc.below()))) {
            return false;
        }
        for (int step = -2; step <= 1; step++) {
            if (step == 0) {
                continue;
            }
            if (!hasFloorUnder(placeLoc.offset(step * highwayDirection.getX(), 0, step * highwayDirection.getZ()))) {
                return false;
            }
        }
        return true;
    }

    private boolean hasFloorUnder(BlockPos pos) {
        return MovementHelper.canWalkOn(baritone.bsi, pos.getX(), pos.getY() - 1, pos.getZ());
    }

    /**
     * True while we still carry something the support block under a placing spot could be built out
     * of: any acceptableThrowawayItems block, the netherrack that schematic asks for by default, or
     * the obsidian it falls back to. Out of all three, a spot with nothing under it can never be
     * made placeable, and sending the builder at it only parks it on missing materials.
     */
    public boolean canBuildSupportBlock() {
        return getAcceptableThrowawaySlot() != -1
                || getItemCountInventory(Item.getId(Blocks.NETHERRACK.asItem())) > 0
                || getItemCountInventory(Item.getId(Blocks.OBSIDIAN.asItem())) > 0;
    }

    public void handle() {
        HighwayState currentStateEnum = currentState.getState();
        boolean inEmergencyEat = currentStateEnum == HighwayState.EmergencyGapplePrep || currentStateEnum == HighwayState.EmergencyGapplePreEat || currentStateEnum == HighwayState.EmergencyGappleEat;
        boolean inCombat = currentStateEnum == HighwayState.MobCombat || currentStateEnum == HighwayState.MobCombatReturn;
        boolean inRecovery = currentStateEnum == HighwayState.FallRecovery;
        boolean inPortalEscape = currentStateEnum == HighwayState.PortalEscape;

        if (inCombat) {
            int swordSlot = putBestSwordHotbar();
            if (swordSlot != -1) {
                playerContext.player().getInventory().selected = swordSlot;
            }
        }

        if (!inEmergencyEat && !inCombat && !inRecovery && !inPortalEscape && currentStateEnum != HighwayState.Nothing) {
            java.util.Optional<Entity> mob = findMobTargetingPlayer();
            if (mob.isPresent()) {
                setPreviousState(currentStateEnum);
                setCurrentMobTarget(mob.get());
                // A preempted thief hunt resumes at the thief, not at where combat started
                setCombatReturnPos(currentStateEnum == HighwayState.ShulkerThiefHunt ? null : playerContext.playerFeet());
                transitionTo(HighwayState.MobCombat);
                return;
            }
        }
        boolean inLiquidEat = currentStateEnum == HighwayState.LiquidRemovalGapplePrep || currentStateEnum == HighwayState.LiquidRemovalGapplePreEat || currentStateEnum == HighwayState.LiquidRemovalGappleEat;
        if (!inEmergencyEat && !inLiquidEat && !inRecovery && !inPortalEscape && (!inCombat || settings.highwayEmergencyEatDuringCombat.value)) {
            if (currentStateEnum != HighwayState.Nothing) {
                float healthThreshold = gappleEatHealthThreshold();
                int foodThreshold = gappleEatFoodThreshold();
                boolean healthTrigger = healthThreshold > 0 && playerContext.player().getHealth() < healthThreshold;
                boolean foodTrigger = foodThreshold > 0 && playerContext.player().getFoodData().getFoodLevel() <= foodThreshold;
                if (healthTrigger || foodTrigger) {
                    Helper.HELPER.logDirect("Emergency gapple eat triggered (health=" + playerContext.player().getHealth() + ", food=" + playerContext.player().getFoodData().getFoodLevel() + ").");
                    setEmergencyEatReturnState(currentStateEnum);
                    baritone.getInputOverrideHandler().clearAllKeys();
                    baritone.getPathingBehavior().cancelEverything();
                    transitionTo(HighwayState.EmergencyGapplePrep);
                    return;
                }
            }
        }

        // Fell off the highway, or got paved over while below it: recover by pathing back to a
        // built spot behind us
        boolean fellFar = playerContext.playerFeet().y <= highwayFeetY() - settings.highwayFallDetectThreshold.value;
        if (settings.highwayFallRecovery.value && currentStateEnum == HighwayState.BuildingHighway
                && (fellFar || sealedUnderHighway())) {
            Helper.HELPER.logDirect((fellFar ? "Fell off the highway" : "Trapped underneath the highway")
                    + " (y=" + playerContext.playerFeet().y + "). Recovering.");
            setPreviousState(currentStateEnum);
            resetRecovery();
            baritone.getInputOverrideHandler().clearAllKeys();
            baritone.getPathingBehavior().cancelEverything();
            transitionTo(HighwayState.FallRecovery);
            return;
        }

        for (int i = 0; ; i++) {
            State handled = currentState;
            if (i == 0) {
                noteStateTick(handled.getState()); // chained instant states are free, so charge one tick
            }
            handled.handle(this);
            handled.noteHandled();
            if (i >= CHAIN_LIMIT || currentState == handled || !INSTANT_STATES.contains(currentState.getState())) {
                break;
            }
        }
    }

    private static final int CHAIN_LIMIT = 4;

    /**
     * States safe to run in the same tick they were entered: each one reads the world, picks a
     * position or a schematic, and transitions. None of them clicks, places, mines, paths or waits
     * on a reply, so nothing is gained by spreading them over separate ticks.
     */
    private static final EnumSet<HighwayState> INSTANT_STATES = EnumSet.of(
            HighwayState.Nothing,
            HighwayState.FloatingFixPrep,
            HighwayState.PickaxeShulkerPlaceLocPrep,
            HighwayState.GappleShulkerPlaceLocPrep,
            HighwayState.TotemShulkerPlaceLocPrep,
            HighwayState.EchestMiningPlaceLocPrep,
            HighwayState.EmptyShulkerPlaceLocPrep,
            HighwayState.EnderChestStashPlaceLocPrep,
            HighwayState.LootEnderChestPlaceLocPrep,
            HighwayState.ShulkerSearchPrep
    );

    // --- Per-state dwell instrumentation ----------------------------------------------------------

    private final EnumMap<HighwayState, int[]> stateDwell = new EnumMap<>(HighwayState.class);
    private HighwayState lastDwellState = null;
    private int dwellTotalTicks = 0;

    private void noteStateTick(HighwayState state) {
        int[] entry = stateDwell.computeIfAbsent(state, k -> new int[2]);
        entry[0]++;
        if (state != lastDwellState) {
            entry[1]++;
            lastDwellState = state;
        }
        dwellTotalTicks++;
    }

    public void resetStateDwell() {
        stateDwell.clear();
        lastDwellState = null;
        dwellTotalTicks = 0;
    }

    /**
     * Ticks spent in each state since the build started, worst first. This is the measurement the
     * wait tuning is meant to be judged on: a state whose share is far above the work it actually
     * does is sitting on a timer it shouldn't be.
     */
    public List<String> stateDwellDiagnostic(int limit) {
        List<String> out = new ArrayList<>();
        if (dwellTotalTicks == 0) {
            out.add("No state time recorded yet.");
            return out;
        }
        out.add(String.format("State time over %d ticks (%.1fs), worst first:", dwellTotalTicks, dwellTotalTicks / 20.0));
        stateDwell.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
                .limit(limit)
                .forEach(e -> out.add(String.format("  %-38s %6d ticks (%5.1fs, %4.1f%%) over %d visit(s), avg %.1ft",
                        e.getKey(), e.getValue()[0], e.getValue()[0] / 20.0,
                        100.0 * e.getValue()[0] / dwellTotalTicks, e.getValue()[1],
                        (double) e.getValue()[0] / e.getValue()[1])));
        return out;
    }

    public void incrementTimers() {
        timer++;
        walkBackTimer++;
        checkBackTimer++;
        stuckTimer++;
        tickContainerSync();
        if (thiefHuntFailCooldown > 0) {
            thiefHuntFailCooldown--;
            if (thiefHuntFailCooldown == 0) {
                thiefHuntIgnoredThief = null;
            }
        }
        if (travelStallCooldown > 0) {
            travelStallCooldown--;
        }

        if (invalidBlockFixActive) {
            if (baritone.getPathingBehavior().isPathing()) {
                invalidBlockFixNoPathTicks = 0;
            } else {
                invalidBlockFixNoPathTicks++;
            }
        }
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

    public int totemsToHave() {
        return Math.max(settings.highwayTotemsToHave.value, settings.highwayTotemsThreshold.value + 1);
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

    public boolean enderChestHasGappleShulks() {
        return enderChestHasGappleShulks;
    }

    public void setEnderChestHasGappleShulks(boolean enderChestHasGappleShulks) {
        this.enderChestHasGappleShulks = enderChestHasGappleShulks;
    }

    public boolean enderChestHasTotemShulks() {
        return enderChestHasTotemShulks;
    }

    public void setEnderChestHasTotemShulks(boolean enderChestHasTotemShulks) {
        this.enderChestHasTotemShulks = enderChestHasTotemShulks;
    }

    public boolean refillingEnderChests() {
        return refillingEnderChests;
    }

    public void setRefillingEnderChests(boolean refillingEnderChests) {
        this.refillingEnderChests = refillingEnderChests;
    }

    public boolean refillingGapples() {
        return refillingGapples;
    }

    public void setRefillingGapples(boolean refillingGapples) {
        this.refillingGapples = refillingGapples;
    }

    public boolean refillingTotems() {
        return refillingTotems;
    }

    public void setRefillingTotems(boolean refillingTotems) {
        this.refillingTotems = refillingTotems;
    }

    public boolean stashingShulker() {
        return stashingShulker;
    }

    public void setStashingShulker(boolean stashingShulker) {
        this.stashingShulker = stashingShulker;
    }

    public BlockPos enderChestAccessLoc() {
        return enderChestAccessLoc;
    }

    /**
     * Forget the remembered storage access chest, but only once no refill leg is still latched:
     * trips are chained (an ender chest grab can queue a gapple and a totem top-up behind it), and
     * whichever leg finishes first would otherwise strand the later ones into placing a fresh chest
     * a few blocks from the perfectly good one this trip already put down.
     */
    public void releaseEnderChestAccessLoc() {
        if (!refillingEnderChests && !refillingGapples && !refillingTotems) {
            enderChestAccessLoc = null;
        }
    }

    public void setEnderChestAccessLoc(BlockPos enderChestAccessLoc) {
        this.enderChestAccessLoc = enderChestAccessLoc == null
                ? null
                : new BlockPos(enderChestAccessLoc.getX(), enderChestAccessLoc.getY(), enderChestAccessLoc.getZ());
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

    public String pauseReason() {
        return pauseReason;
    }

    /**
     * Stop the state machine where it stands and say why. Nothing leaves this by itself - only a
     * new nhwbuild or an nhwstop - so the one line it logs is how whoever drives the bot learns
     * about it, instead of having to poll nhwstatus for it. The machine is not ticked while paused,
     * so it is said exactly once.
     */
    public void pause(String reason) {
        paused = true;
        pauseReason = reason;
        Helper.HELPER.logDirect("PAUSED NETHERHIGHWAYBUILDER: " + reason);
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        this.pauseReason = null;
    }

    public BetterBlockPos getClosestPoint(Vec3 origin, Vec3 direction, Vec3 point, LocationType locType) {
        int yLevel = switch (locType) {
            case HighwayBuild -> settings.highwayLowestY.value;
            case ShulkerEchestInteraction -> paving ? settings.highwayMainY.value + 1 : settings.highwayMainY.value;
            case SideStorage -> settings.highwayEmptyShulkEchestY.value;
        };

        // Project onto the highway line, but round the along-line step count once and derive both
        // coordinates from it in integer space. Rounding x and z independently after a double
        // projection can disagree on half-integer results and return a point one block off the
        // line, which laterally shifted offset highways whenever the build restarted.
        long ox = Math.round(origin.x);
        long oz = Math.round(origin.z);
        long dirX = Math.round(direction.x);
        long dirZ = Math.round(direction.z);
        long lenSq = dirX * dirX + dirZ * dirZ;
        if (lenSq == 0) {
            return new BetterBlockPos((int) ox, yLevel, (int) oz);
        }
        long steps = Math.round(((point.x - ox) * dirX + (point.z - oz) * dirZ) / lenSq);
        // Never allow points behind the original starting location. Clamped in step
        // space, after projection: the raw-coordinate test this replaces read
        // laterally offset points (a parallel lane, a diagonal's cross component)
        // as behind and snapped them onto the start.
        if (firstStartingPos != null) {
            long startSteps = Math.round(((firstStartingPos.getX() - ox) * dirX + (firstStartingPos.getZ() - oz) * dirZ) / (double) lenSq);
            steps = Math.max(steps, startSteps);
        }
        // Mirror of the firstStartingPos clamp: never allow points past the set end
        if (endPos != null) {
            long endSteps = dirX != 0 ? (endPos.getX() - ox) * dirX : (endPos.getZ() - oz) * dirZ;
            steps = Math.min(steps, endSteps);
        }
        return new BetterBlockPos((int) (ox + steps * dirX), yLevel, (int) (oz + steps * dirZ));
    }

    /**
     * Signed slice count from {@code from} to {@code to} measured on the driving axis, so points on
     * parallel lines (build/liquid/side-storage) agree on where a given slice ends. Negative means
     * {@code to} is behind {@code from}.
     */
    public int stepsAlongHighway(BlockPos from, BlockPos to) {
        return highwayDirection.getX() != 0
                ? (to.getX() - from.getX()) * highwayDirection.getX()
                : (to.getZ() - from.getZ()) * highwayDirection.getZ();
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

    public int getItemSlotHotbar(int itemId) {
        for (int i = 0; i < 9; i++) {
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
        return putPickaxeHotbar(false);
    }

    public int putPickaxeHotbar(boolean avoidSilkTouch) {
        int itemSlot = getPickaxeSlot(avoidSilkTouch);
        if (itemSlot >= 9) {
            baritone.getInventoryBehavior().attemptToPutOnHotbar(itemSlot, usefulSlots::contains);
            itemSlot = getPickaxeSlot(avoidSilkTouch);
        }

        return itemSlot;
    }

    private int getPickaxeSlot(boolean avoidSilkTouch) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = playerContext.player().getInventory().items.get(i);
            if (stack.getItem() instanceof PickaxeItem) {
                if (settings.itemSaver.value && (stack.getDamageValue() + settings.itemSaverThreshold.value) >= stack.getMaxDamage() && stack.getMaxDamage() > 1) {
                    continue;
                }
                
                if (avoidSilkTouch && hasSilkTouch(stack)) {
                    continue;
                }
                
                return i;
            }
        }

        return -1;
    }

    public int putBestSwordHotbar() {
        int itemSlot = baritone.getInventoryBehavior().bestSwordSlot();
        if (itemSlot == -1) return -1;
        if (itemSlot >= 9) {
            baritone.getInventoryBehavior().attemptToPutOnHotbar(itemSlot, usefulSlots::contains);
            itemSlot = baritone.getInventoryBehavior().bestSwordSlot();
        }
        return itemSlot;
    }

    public void swapOffhand(int slot) {
        playerContext.playerController().windowClick(0, 45, 0, ClickType.PICKUP, playerContext.player());
        playerContext.playerController().windowClick(0, slot < 9 ? slot + 36 : slot, 0, ClickType.PICKUP, playerContext.player());
        playerContext.playerController().windowClick(0, 45, 0, ClickType.PICKUP, playerContext.player());
    }

    // States where the offhand is deliberately occupied (echest farming keeps an ender chest there
    // between PrepEchest and SwapBack) - a totem swap would fight the state's own offhand swaps and
    // loop forever. Every state NOT listed here keeps a totem equipped, so new states default to protected.
    private static final EnumSet<HighwayState> OFFHAND_OCCUPIED_STATES = EnumSet.of(
            HighwayState.FarmingEnderChestPrepEchest,
            HighwayState.FarmingEnderChestPrepPick,
            HighwayState.FarmingEnderChest,
            HighwayState.FarmingEnderChestSwapBack,
            HighwayState.InQueue
    );

    public boolean autoTotem() {
        if (!settings.highwayAutoTotem.value
                || playerContext.player().getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING
                || OFFHAND_OCCUPIED_STATES.contains(currentState.getState())) {
            return false;
        }
        // swapOffhand clicks the player inventory: defer while a container is open, the cursor is
        // carrying a stack (clearCursorItem handles that), or an item (gapple) is mid-use.
        if (playerContext.player().hasContainerOpen()
                || !playerContext.player().containerMenu.getCarried().isEmpty()
                || playerContext.player().isUsingItem()) {
            return false;
        }
        int totemSlot = getItemSlot(Item.getId(Items.TOTEM_OF_UNDYING));
        if (totemSlot != -1) {
            swapOffhand(totemSlot);
            timer = 0;
            return true;
        }
        return false;
    }

    /**
     * Swap the offhand ender chest stack into the inventory, merging onto a partial loose stack
     * first so the chests never fragment into an extra slot; falls back to an empty slot. A merge
     * moves at most (64 - partial) and leaves the remainder in the offhand for a follow-up call.
     * Returns false when the offhand holds no ender chests or the inventory has no room.
     */
    public boolean stashOffhandEnderChests() {
        Item offhandItem = playerContext.player().getOffhandItem().getItem();
        if (!(offhandItem instanceof BlockItem) || !(((BlockItem) offhandItem).getBlock() instanceof EnderChestBlock)) {
            return false;
        }
        int targetSlot = getMergeableEnderChestSlot();
        if (targetSlot == -1) {
            targetSlot = getItemSlot(Item.getId(Items.AIR));
        }
        if (targetSlot == -1) {
            return false;
        }
        swapOffhand(targetSlot);
        return true;
    }

    /**
     * Ender chests belong in the offhand only between PrepEchest and SwapBack; anywhere else they
     * are leftovers of an interrupted farm session, invisible to every inventory count (all scan
     * slots 0-35 only).
     */
    public boolean rescueOffhandEnderChests() {
        Item offhandItem = playerContext.player().getOffhandItem().getItem();
        if (!(offhandItem instanceof BlockItem) || !(((BlockItem) offhandItem).getBlock() instanceof EnderChestBlock)
                || OFFHAND_OCCUPIED_STATES.contains(currentState.getState())) {
            return false;
        }
        // Same interaction guards as autoTotem: swapOffhand clicks the player inventory
        if (playerContext.player().hasContainerOpen()
                || !playerContext.player().containerMenu.getCarried().isEmpty()
                || playerContext.player().isUsingItem()) {
            return false;
        }
        if (stashOffhandEnderChests()) {
            timer = 0;
            return true;
        }
        // No partial chest stack and no empty slot: toss a throwaway stack so the next tick's
        // stash has somewhere to land. Chests always outrank netherrack.
        int throwawaySlot = getThrowawaySlotToToss();
        if (throwawaySlot == -1) {
            return false;
        }
        AbstractContainerMenu menu = playerContext.player().containerMenu;
        playerContext.playerController().windowClick(menu.containerId, invSlotToMenuSlot(throwawaySlot), 0, ClickType.PICKUP, playerContext.player());
        playerContext.playerController().windowClick(menu.containerId, -999, 0, ClickType.PICKUP, playerContext.player());
        timer = 0;
        return true;
    }

    public boolean clearCursorItem() {
        AbstractContainerMenu curContainer = playerContext.player().containerMenu;
        if (!curContainer.getCarried().isEmpty()) {
            if (cursorStackNonEmpty && timer >= 20) {
                // We have some item on our cursor for 20 ticks, try to place it somewhere
                timer = 0;


                int emptySlot = getItemSlot(Item.getId(Items.AIR));
                if (emptySlot != -1) {
                    Helper.HELPER.logDirect("Had " + curContainer.getCarried().getDisplayName() + " on our cursor. Trying to place into slot " + emptySlot);

                    playerContext.playerController().windowClick(curContainer.containerId, invSlotToMenuSlot(emptySlot), 0, ClickType.PICKUP, playerContext.player());
                    cursorStackNonEmpty = false;
                    return true;
                } else {
                    if (isAcceptableThrowawayItem(curContainer.getCarried().getItem())) {
                        // Netherrack on our cursor, we can just throw it out
                        playerContext.playerController().windowClick(curContainer.containerId, -999, 0, ClickType.PICKUP, playerContext.player());
                        cursorStackNonEmpty = false;
                        return true;
                    } else {
                        // We don't have throwaway items on our cursor, might be important so swap with throwaway items and throw away the throwaway
                        int throwawaySlot = getThrowawaySlotToToss();
                        if (throwawaySlot != -1) {
                            playerContext.playerController().windowClick(curContainer.containerId, invSlotToMenuSlot(throwawaySlot), 0, ClickType.PICKUP, playerContext.player());
                            playerContext.playerController().windowClick(curContainer.containerId, -999, 0, ClickType.PICKUP, playerContext.player());
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

    public int invSlotToMenuSlot(int invSlot) {
        AbstractContainerMenu menu = playerContext.player().containerMenu;
        if (menu == playerContext.player().inventoryMenu) {
            // Inventory menu: main slots map directly, hotbar sits after them at 36-44
            return invSlot < 9 ? invSlot + 36 : invSlot;
        }
        // Container menus: container slots first, then main inventory, then hotbar
        int containerSlots = menu.slots.size() - 36;
        return invSlot < 9 ? invSlot + containerSlots + 27 : invSlot + containerSlots - 9;
    }

    public boolean stuckCheck() {
        if (currentState.getState() == HighwayState.InQueue || currentState.getState() == HighwayState.FallRecovery) {
            return false;
        }
        
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
                currentState.getState() != HighwayState.LiquidRemovalGapplePrep && currentState.getState() != HighwayState.LiquidRemovalGapplePreEat && currentState.getState() != HighwayState.LiquidRemovalGappleEat &&
                currentState.getState() != HighwayState.EmergencyGapplePrep && currentState.getState() != HighwayState.EmergencyGapplePreEat && currentState.getState() != HighwayState.EmergencyGappleEat &&
                currentState.getState() != HighwayState.FallRecovery) {
            Component dcMsg = Component.literal("Lost " + (cachedHealth - playerContext.player().getHealth()) + " health. Reconnect");
            Helper.HELPER.logDirect(dcMsg);
            playerContext.player().connection.getConnection().disconnect(dcMsg);
            cachedHealth = playerContext.player().getHealth();
            cachedAbsorption = playerContext.player().getAbsorptionAmount();
            return true;
        }
        
        if (settings.highwayAbsorptionDc.value && playerContext.player().getAbsorptionAmount() < cachedAbsorption && playerContext.player().hasEffect(MobEffects.ABSORPTION)) {
            Component dcMsg = Component.literal("Lost " + (cachedAbsorption - playerContext.player().getAbsorptionAmount()) + " absorption. Reconnect");
            Helper.HELPER.logDirect(dcMsg);
            playerContext.player().connection.getConnection().disconnect(dcMsg);
            cachedHealth = playerContext.player().getHealth();
            cachedAbsorption = playerContext.player().getAbsorptionAmount();
            return true;
        }
        
        cachedHealth = playerContext.player().getHealth(); // Get new HP value
        cachedAbsorption = playerContext.player().getAbsorptionAmount();
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
                BlockPos neighbour = blockPos.offset(side.getUnitVec3i());
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
            BlockPos neighbour = blockPos.offset(side.getUnitVec3i());
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

    private boolean hasSilkTouch(ItemStack itemStack) {
        ItemEnchantments enchantments = itemStack.getEnchantments();
        for (Holder<Enchantment> enchant : enchantments.keySet()) {
            if (enchant.is(Enchantments.SILK_TOUCH) && enchantments.getLevel(enchant) > 0) {
                return true;
            }
        }
        return false;
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


                if (hasSilkTouch(curStack)) {
                    // Pickaxe is enchanted with silk touch
                    return 0;
                }
            } else if (!(curStack.getItem() instanceof AirItem)) {
                if (!settings.highwayAllowMixedShulks.value || !(curStack.getItem() instanceof BlockItem) || !(((BlockItem)curStack.getItem()).getBlock() instanceof EnderChestBlock)) {
                    return 0; // Found a non pickaxe and non-air item
                }
            }
        }

        return pickaxeCount;
    }

    private boolean isDepletedPickShulker(ItemStack shulker) {
        if (!settings.itemSaver.value) {
            return false;
        }
        NonNullList<ItemStack> contents = getShulkerContents(shulker);

        int depletedCount = 0;
        for (ItemStack curStack : contents) {
            if (curStack.getItem() instanceof AirItem) {
                continue;
            }
            if (curStack.getItem() instanceof PickaxeItem && (curStack.getDamageValue() + settings.itemSaverThreshold.value) >= curStack.getMaxDamage() && curStack.getMaxDamage() > 1) {
                depletedCount++;
            } else {
                return false; // Found a usable pick or some other item
            }
        }

        return depletedCount > 0;
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

    private int isTotemShulker(ItemStack shulker) {
        NonNullList<ItemStack> contents = getShulkerContents(shulker);

        int totemCount = 0;
        for (ItemStack curStack : contents) {
            if (Item.getId(curStack.getItem()) != Item.getId(Items.AIR) && Item.getId(curStack.getItem()) != Item.getId(Items.TOTEM_OF_UNDYING)) {
                return 0;
            }

            if (Item.getId(curStack.getItem()) == Item.getId(Items.TOTEM_OF_UNDYING)) {
                totemCount += curStack.getCount();
            }
        }
        return totemCount;
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

                    case Totem: {
                        int count = isTotemShulker(stack);
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

                    case DepletedPickaxe: {
                        if (isDepletedPickShulker(stack)) {
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

    private int farmObsidianStart = 0;
    private int farmChestsStart = 0;

    private int obsidianCountAll() {
        int count = getItemCountInventory(Item.getId(Blocks.OBSIDIAN.asItem()));
        ItemStack offhand = playerContext.player().getOffhandItem();
        if (offhand.is(Blocks.OBSIDIAN.asItem())) {
            count += offhand.getCount();
        }
        return count;
    }

    private int enderChestCountAll() {
        int count = getItemCountInventory(Item.getId(Blocks.ENDER_CHEST.asItem()));
        ItemStack offhand = playerContext.player().getOffhandItem();
        if (offhand.is(Blocks.ENDER_CHEST.asItem())) {
            count += offhand.getCount();
        }
        return count;
    }

    public void beginFarmAccounting() {
        farmObsidianStart = obsidianCountAll();
        farmChestsStart = enderChestCountAll();
    }

    /**
     * Obsidian this farm session has produced that is neither in the
     * inventory nor visible on the ground yet
     */
    public int farmObsidianInFlight(int ground) {
        int picked = obsidianCountAll() - farmObsidianStart;
        int consumed = farmChestsStart - enderChestCountAll();
        return Math.max(0, 8 * consumed - picked - ground);
    }

    /**
     * Obsidian the inventory can still take once this farm session ends,
     * minus what is already dropped or in flight, if one more chest gets broken
     */
    public int farmRoomAfterOneMore() {
        int ground = obsidianOnGroundNearby();
        int remaining = getItemCountInventory(Item.getId(Blocks.ENDER_CHEST.asItem())) + playerContext.player().getOffhandItem().getCount() - 1;
        Item origItem = instantMineOriginalOffhandItem();
        boolean origInInventory = origItem != null && origItem != Items.AIR && getItemSlot(Item.getId(origItem)) != -1
                && playerContext.player().getOffhandItem().is(Blocks.ENDER_CHEST.asItem());
        int freed = enderChestSlotsInventory() + (origInInventory ? 1 : 0) - enderChestSlotsAfterFarming(Math.max(0, remaining), 0);
        return obsidianRoomInventory(freed) - ground - farmObsidianInFlight(ground) - 8;
    }

    public int obsidianOnGroundNearby() {
        int count = 0;
        for (Entity entity : playerContext.entities()) {
            if (!(entity instanceof ItemEntity)) {
                continue;
            }
            ItemStack stack = ((ItemEntity) entity).getItem();
            if (!stack.is(Blocks.OBSIDIAN.asItem()) && !stack.is(Blocks.CRYING_OBSIDIAN.asItem())) {
                continue;
            }
            if (VecUtils.distanceToCenter(playerContext.playerFeet(), (int) entity.getX(), (int) entity.getY(), (int) entity.getZ()) <= settings.highwayObsidianMaxSearchDist.value) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** Obsidian the inventory can still take */
    public int obsidianRoomInventory(int slotsFreedLater) {
        int room = 0;
        int slots = slotsFreedLater;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = playerContext.player().getInventory().items.get(i);
            if (stack.isEmpty()) {
                slots++;
            } else if (stack.is(Blocks.OBSIDIAN.asItem())) {
                room += stack.getMaxStackSize() - stack.getCount();
            } else if (i != 8 && !stack.is(Blocks.CRYING_OBSIDIAN.asItem()) && settings.acceptableThrowawayItems.value.contains(stack.getItem())) {
                slots++;
            }
        }
        // A negative slot count means the kept chests or the returning box need slots that don't
        // exist; partial-stack room can't paper over that.
        return Math.max(0, room + 64 * slots);
    }

    /** Slot breakdown behind the obsidian room figures, for the farm-entry and stuck-collection logs. */
    public String obsidianRoomBreakdown() {
        int empty = 0, throwaway = 0, partial = 0, chestSlots = 0, obsidianSlots = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = playerContext.player().getInventory().items.get(i);
            if (stack.isEmpty()) {
                empty++;
            } else if (stack.is(Blocks.OBSIDIAN.asItem())) {
                obsidianSlots++;
                partial += stack.getMaxStackSize() - stack.getCount();
            } else if (stack.is(Blocks.ENDER_CHEST.asItem())) {
                chestSlots++;
            } else if (i != 8 && !stack.is(Blocks.CRYING_OBSIDIAN.asItem()) && settings.acceptableThrowawayItems.value.contains(stack.getItem())) {
                throwaway++;
            }
        }
        return "empty=" + empty + " throwaway=" + throwaway + " obsidianSlots=" + obsidianSlots + " partialRoom=" + partial
                + " chestSlots=" + chestSlots + " chests=" + getItemCountInventory(Item.getId(Blocks.ENDER_CHEST.asItem()))
                + " offhand=" + playerContext.player().getOffhandItem().getCount() + "x" + playerContext.player().getOffhandItem().getItem()
                + " ground=" + obsidianOnGroundNearby() + " slot8=" + playerContext.player().getInventory().items.get(8).getItem();
    }

    public int enderChestSlotsInventory() {
        int slots = 0;
        for (int i = 0; i < 36; i++) {
            if (playerContext.player().getInventory().items.get(i).is(Blocks.ENDER_CHEST.asItem())) {
                slots++;
            }
        }
        return slots;
    }

    /**
     * Slots the loose chests still hold once a farm has left {@code keep} of them,
     * from the stacks actually carried
     */
    public int enderChestSlotsAfterFarming(int keep, int hypotheticalStack) {
        List<Integer> stacks = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = playerContext.player().getInventory().items.get(i);
            if (stack.is(Blocks.ENDER_CHEST.asItem())) {
                stacks.add(stack.getCount());
            }
        }
        if (hypotheticalStack > 0) {
            stacks.add(hypotheticalStack);
        }
        stacks.sort(Collections.reverseOrder());
        ItemStack offhand = playerContext.player().getOffhandItem();
        if (offhand.is(Blocks.ENDER_CHEST.asItem())) {
            stacks.add(0, offhand.getCount());
        }
        int total = 0;
        for (int c : stacks) {
            total += c;
        }
        int toFarm = total - keep;
        int remainder = 0;
        int i = 0;
        while (i < stacks.size() && toFarm > 0) {
            int c = stacks.get(i++);
            if (toFarm >= c) {
                toFarm -= c;
            } else {
                remainder = c - toFarm;
                toFarm = 0;
            }
        }
        int untouched = 0;
        int roomInUntouched = 0;
        for (; i < stacks.size(); i++) {
            untouched++;
            roomInUntouched += 64 - stacks.get(i);
        }
        return untouched + (Math.max(0, remainder - roomInUntouched) + 63) / 64;
    }

    /**
     * Ender chests that can be broken before their obsidian (8 each) outgrows the inventory,
     * with {@code keep} loose chests staying behind.
     */
    public int enderChestFarmCapacity(int keep, int extraSlotsFreed, int obsidianOnGround, int hypotheticalStack) {
        int slots = enderChestSlotsInventory() + (hypotheticalStack > 0 ? 1 : 0);
        int freed = slots - enderChestSlotsAfterFarming(keep, hypotheticalStack) + extraSlotsFreed;
        return Math.max(0, obsidianRoomInventory(freed) - obsidianOnGround) / 8;
    }

    public int enderChestFarmCapacity(int keep, int extraSlotsFreed, int obsidianOnGround) {
        return enderChestFarmCapacity(keep, extraSlotsFreed, obsidianOnGround, 0);
    }

    public int enderChestFarmKeep(int total, int extraSlotsFreed, int obsidianOnGround) {
        return enderChestFarmKeep(total, extraSlotsFreed, obsidianOnGround, 0);
    }

    public int enderChestFarmKeep(int total, int extraSlotsFreed, int obsidianOnGround, int hypotheticalStack) {
        int keep = settings.highwayEnderChestsToKeep.value;
        for (int i = 0; i < 64; i++) {
            int needed = Math.max(settings.highwayEnderChestsToKeep.value, total - enderChestFarmCapacity(keep, extraSlotsFreed, obsidianOnGround, hypotheticalStack));
            if (needed <= keep) {
                break;
            }
            keep = needed;
        }
        return keep;
    }

    public boolean enderChestFarmCanProgress() {
        int ground = obsidianOnGroundNearby();
        int chests = getItemCountInventory(Item.getId(Blocks.ENDER_CHEST.asItem()));
        if (chests - enderChestFarmKeep(chests, 0, ground) > 0) {
            return true; // what we carry already farms
        }
        // Otherwise the cycle loots a stack first. It lands in a free slot (or merges into a partial),
        // so the live slot counts already describe the inventory after that loot; only the total
        // changes. Fewer loose chests than the keep setting is the common way to get here.
        return (chests + 64) - enderChestFarmKeep(chests + 64, 0, ground, 64) > 0;
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

    /**
     * Totems we can actually pop, so the offhand one counts: it's the one that saves us, and it's
     * invisible to {@link #getItemCountInventory} (which only scans slots 0-35).
     */
    public int getTotemCountInventory() {
        int count = getItemCountInventory(Item.getId(Items.TOTEM_OF_UNDYING));
        ItemStack offhand = playerContext.player().getOffhandItem();
        if (offhand.is(Items.TOTEM_OF_UNDYING)) {
            count += offhand.getCount();
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

    public HighwayState placeShulkerBox(BlockPos shulkerPlaceLoc, HighwayState prevHighwayState, HighwayState currentHighwayState, HighwayState nextHighwayState, ShulkerType shulkerType) {
        // Debug logging to track BlockPos types
        Helper.HELPER.logDebug("placeShulkerBox called with: " + shulkerPlaceLoc + " (class: " + shulkerPlaceLoc.getClass().getSimpleName() + ")");

        BlockState currentState = playerContext.world().getBlockState(shulkerPlaceLoc);
        if (currentState.getBlock() instanceof ShulkerBoxBlock) {
            return currentHighwayState;
        }
        // Check if we can place at this location
        if (!canPlaceBlockAt(shulkerPlaceLoc)) {
            Helper.HELPER.logDirect("Cannot place shulker at " + shulkerPlaceLoc);
            return prevHighwayState;
        }

        // Get shulker slot and validate
        int shulkerSlot = putShulkerHotbar(shulkerType);
        if (shulkerSlot == -1) {
            Helper.HELPER.logDirect("Error getting shulker slot");
            return currentHighwayState;
        }
        if (shulkerSlot >= 9) {
            Helper.HELPER.logDirect("Couldn't put shulker to hotbar, waiting");
            return currentHighwayState;
        }

        // Validate we have the right item
        ItemStack stack = playerContext.player().getInventory().items.get(shulkerSlot);
        if (!shulkerItemList.contains(stack.getItem())) {
            Helper.HELPER.logDirect("Invalid shulker item in slot");
            return currentHighwayState;
        }

        // Determine placement direction and target position. The side matters twice over for a
        // shulker: it is what the box attaches to, and it decides which way the box faces.
        Direction placeSide = getBestPlaceSide(shulkerPlaceLoc, true);
        if (placeSide == null) {
            Helper.HELPER.logDirect("No side to place the shulker against that would leave it openable");
            return prevHighwayState;
        }

        // Last line of defence against placing through a wall: the caller clears the way first,
        // but a click we can't see the face for puts the box somewhere nothing can reach it again.
        Optional<Rotation> targetRotation = shulkerPlaceAim(shulkerPlaceLoc, placeSide);
        if (targetRotation.isEmpty()) {
            Helper.HELPER.logDirect("No clear line to the shulker spot at " + shulkerPlaceLoc.toShortString() + ", not placing");
            return prevHighwayState;
        }

        BlockPos neighbor = shulkerPlaceLoc.relative(placeSide);
        Vec3 hitPos = placeHitPos(shulkerPlaceLoc, placeSide);
        BlockHitResult blockHitResult = new BlockHitResult(hitPos, placeSide.getOpposite(), neighbor, false);

        // Use look behavior to rotate to target, then place
        baritone.getLookBehavior().updateTarget(targetRotation.get(), true);

        // Select the shulker slot
        playerContext.player().getInventory().selected = shulkerSlot;

        // Perform the placement using proper interaction
        InteractionResult result = playerContext.playerController().processRightClickBlock(
            playerContext.player(),
            playerContext.world(),
            InteractionHand.MAIN_HAND,
            blockHitResult
        );

        if (result.consumesAction()) {
            // Placement attempted successfully - don't check for immediate success
            Helper.HELPER.logDirect("Shulker placement attempted at " + shulkerPlaceLoc);
            return currentHighwayState; // Let the calling state handle waiting for confirmation
        } else {
            return noteShulkerPlaceRefused(shulkerPlaceLoc, prevHighwayState);
        }
    }

    public boolean placeBlockAgainstNeighbor(BlockPos placeLoc, int itemSlot) {
        if (!canPlaceBlockAt(placeLoc)) {
            return false;
        }

        Direction placeSide = getBestPlaceSide(placeLoc);
        if (placeSide == null) {
            return false;
        }

        BlockPos neighbor = placeLoc.relative(placeSide);
        Vec3 hitPos = Vec3.atCenterOf(placeLoc).add(
                placeSide.getStepX() * 0.5,
                placeSide.getStepY() * 0.5,
                placeSide.getStepZ() * 0.5
        );
        BlockHitResult blockHitResult = new BlockHitResult(hitPos, placeSide.getOpposite(), neighbor, false);

        Rotation targetRotation = RotationUtils.calcRotationFromVec3d(
                RayTraceUtils.inferSneakingEyePosition(playerContext.player()),
                hitPos,
                playerContext.playerRotations()
        );
        baritone.getLookBehavior().updateTarget(targetRotation, true);
        playerContext.player().getInventory().selected = itemSlot;

        InteractionResult result = playerContext.playerController().processRightClickBlock(
                playerContext.player(),
                playerContext.world(),
                InteractionHand.MAIN_HAND,
                blockHitResult
        );
        return result.consumesAction();
    }

    public boolean isSideStorageSpotSafe(BlockPos placeLoc) {
        BlockPos[] column = {placeLoc, placeLoc.above(), placeLoc.below()};
        for (BlockPos p : column) {
            if (isLavaAt(p)) {
                return false;
            }
        }
        for (BlockPos p : new BlockPos[]{placeLoc, placeLoc.above()}) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (isLavaAt(p.relative(d))) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isLavaAt(BlockPos pos) {
        return playerContext.world().getBlockState(pos).getFluidState().is(FluidTags.LAVA);
    }

    public BlockPos findSafeSideStorageSpot(int minBack, int maxBack) {
        BlockPos behind = scanSideStorageSpots(minBack, maxBack, -1);
        if (behind != null) {
            return behind;
        }
        // Nothing usable behind us. At a crossing with another highway the side lane is that
        // highway's corridor rather than our own rail: open air with nothing to set a box on and no
        // floor to stand on. Look the same distance ahead along our own highway instead.
        return scanSideStorageSpots(minBack, maxBack, 1);
    }

    /**
     * Walks the side-storage line looking for a usable spot, {@code sign} -1 back along the highway
     * and +1 ahead of us.
     */
    private BlockPos scanSideStorageSpots(int minDist, int maxDist, int sign) {
        Vec3 direction = new Vec3(highwayDirection.getX(), highwayDirection.getY(), highwayDirection.getZ());
        Vec3 origin = new Vec3(eChestEmptyShulkOriginVector.x, eChestEmptyShulkOriginVector.y, eChestEmptyShulkOriginVector.z);
        for (int dist = minDist; dist <= maxDist; dist++) {
            Vec3 curPos = new Vec3(
                    playerContext.playerFeet().getX() + (dist * sign * highwayDirection.getX()),
                    playerContext.playerFeet().getY(),
                    playerContext.playerFeet().getZ() + (dist * sign * highwayDirection.getZ())
            );
            BetterBlockPos candidate = getClosestPoint(origin, direction, curPos, LocationType.SideStorage);
            if (isSideStorageSpotUsable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * A side-storage spot we can actually work with: lava-free, with something under it to set the
     * box on, and a floor beside it to stand on while placing and opening it. The lava-only check
     * this wraps happily handed back spots hanging in the middle of a crossing highway's corridor,
     * where the support block has nothing to attach to and the standing spot is thin air.
     */
    public boolean isSideStorageSpotUsable(BlockPos placeLoc) {
        if (!isSideStorageSpotSafe(placeLoc)) {
            return false;
        }

        if (isShulkerSpotKnownBad(placeLoc) || !shulkerColumnWorkable(placeLoc)) {
            return false;
        }
        // Under the box: either solid already, or a face the support block can be placed against -
        // and that second half only counts while we still carry a block to build the support out of
        if (!MovementHelper.canWalkOn(baritone.bsi, placeLoc.getX(), placeLoc.getY() - 1, placeLoc.getZ())
                && !(canBuildSupportBlock() && hasSturdyNeighbor(placeLoc.below()))) {
            return false;
        }
        // We stand one step along the highway from the box to place it and to open it
        BlockPos stand = placeLoc.offset(highwayDirection.getX(), 0, highwayDirection.getZ());
        return MovementHelper.canWalkOn(baritone.bsi, stand.getX(), stand.getY() - 1, stand.getZ());
    }

    private boolean hasSturdyNeighbor(BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos neighbor = pos.relative(d);
            BlockState state = playerContext.world().getBlockState(neighbor);
            if (!state.isAir() && state.getFluidState().isEmpty()
                    && state.isFaceSturdy(playerContext.world(), neighbor, d.getOpposite())) {
                return true;
            }
        }
        return false;
    }

    private boolean canPlaceBlockAt(BlockPos pos) {
        // Check if position is valid
        if (!playerContext.world().isInWorldBounds(pos)) return false;

        // Check if current block is replaceable
        BlockState currentState = playerContext.world().getBlockState(pos);

        return currentState.canBeReplaced() || currentState.getBlock() instanceof SnowLayerBlock;
    }

    /** Whether a block placed at {@code pos} has something to attach to on {@code side}. */
    private boolean canPlaceAgainst(BlockPos pos, Direction side) {
        BlockPos neighbor = pos.relative(side);
        BlockState neighborState = playerContext.world().getBlockState(neighbor);
        return !neighborState.isAir()
                && neighborState.getFluidState().isEmpty()
                && neighborState.isFaceSturdy(playerContext.world(), neighbor, side.getOpposite());
    }

    private Direction getBestPlaceSide(BlockPos pos) {
        return getBestPlaceSide(pos, false);
    }

    private Direction getBestPlaceSide(BlockPos pos, boolean openableFacing) {
        // Set on the floor, a box faces up into the cell above - the one the place states clear and
        // the spot scan insists on. Any other support side leaves it facing sideways at whatever the
        // lane happens to hold, so take the floor whenever there is one.
        if (openableFacing && canPlaceAgainst(pos, Direction.DOWN) && canShulkerOpenToward(pos, Direction.UP)) {
            return Direction.DOWN;
        }

        Vec3 eyePos = playerContext.player().getEyePosition();
        Vec3 blockCenter = Vec3.atCenterOf(pos);
        Vec3 lookVec = blockCenter.subtract(eyePos);

        double bestRelevancy = -Double.MAX_VALUE;
        Direction bestSide = null;

        for (Direction side : Direction.values()) {
            // Check if neighbor can be placed against (and isn't a fluid)
            if (!canPlaceAgainst(pos, side)) {
                continue;
            }

            // The box would face the other way; no room for the lid there means no opening it, ever
            if (openableFacing && !canShulkerOpenToward(pos, side.getOpposite())) {
                continue;
            }

            double relevancy = side.getAxis().choose(lookVec.x, lookVec.y, lookVec.z) * side.getAxisDirection().getStep();
            if (relevancy > bestRelevancy) {
                bestRelevancy = relevancy;
                bestSide = side;
            }
        }

        return bestSide;
    }

    public boolean canShulkerOpenToward(BlockPos pos, Direction facing) {
        AABB lid = Shulker.getProgressDeltaAabb(1.0F, facing, 0.0F, 0.5F, pos.getBottomCenter()).deflate(1.0E-6);
        return playerContext.world().noBlockCollision(null, lid);
    }

    /** A cell a box can sit in or open into: already clear, or something the builder can dig out. */
    private boolean clearOrClearable(BlockPos pos) {
        BlockState state = playerContext.world().getBlockState(pos);
        if (state.getFluidState().is(FluidTags.LAVA)) {
            return false;
        }
        return state.canBeReplaced() || state.getDestroySpeed(playerContext.world(), pos) >= 0;
    }

    private boolean shulkerColumnWorkable(BlockPos placeLoc) {
        return clearOrClearable(placeLoc) && clearOrClearable(placeLoc.above());
    }

    public boolean isShulkerSpotKnownBad(BlockPos pos) {
        return badShulkerSpots.contains(new BlockPos(pos.getX(), pos.getY(), pos.getZ()));
    }

    private void rememberBadShulkerSpot(BlockPos spot) {
        if (badShulkerSpots.size() >= SHULKER_SPOT_MEMORY) {
            badShulkerSpots.clear(); // the lane moves on, so old entries can never come up again
        }
        badShulkerSpots.add(spot);
    }

    private static Vec3 placeHitPos(BlockPos pos, Direction placeSide) {
        return Vec3.atCenterOf(pos).add(placeSide.getStepX() * 0.5, placeSide.getStepY() * 0.5, placeSide.getStepZ() * 0.5);
    }

    private Optional<Rotation> shulkerPlaceAim(BlockPos placeLoc, Direction placeSide) {
        return RotationUtils.reachable(playerContext, placeLoc.relative(placeSide),
                playerContext.playerController().getBlockReachDistance());
    }

    public boolean canReachShulkerPlacement(BlockPos placeLoc) {
        Direction placeSide = getBestPlaceSide(placeLoc, true);
        return placeSide == null || shulkerPlaceAim(placeLoc, placeSide).isPresent();
    }

    private BlockPos shulkerPlaceSightBlocker(BlockPos placeLoc) {
        Direction placeSide = getBestPlaceSide(placeLoc, true);
        if (placeSide == null) {
            return null;
        }
        BlockPos neighbor = placeLoc.relative(placeSide);
        Rotation aim = RotationUtils.calcRotationFromVec3d(playerContext.player().getEyePosition(1.0F),
                VecUtils.calculateBlockCenter(playerContext.world(), neighbor), playerContext.playerRotations());
        HitResult hit = RayTraceUtils.rayTraceTowards(playerContext.player(),
                baritone.getLookBehavior().getAimProcessor().peekRotation(aim),
                playerContext.playerController().getBlockReachDistance(), false);
        if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos hitAt = ((BlockHitResult) hit).getBlockPos();
            if (!hitAt.equals(neighbor)) {
                return hitAt;
            }
        }
        return null; // the ray ran out rather than stopping on something: too far, not walled off
    }

    public boolean handleShulkerPlaceOutOfSight(BlockPos placeLoc, HighwayState walkCloserState, HighwayState relocateState) {
        BlockPos key = new BlockPos(placeLoc.getX(), placeLoc.getY(), placeLoc.getZ());
        if (canReachShulkerPlacement(key)) {
            return false;
        }
        if (!key.equals(shulkerSightFailSpot)) {
            shulkerSightFailSpot = key;
            shulkerSightDigs = 0;
            shulkerSightWalks = 0;
        }

        BlockPos blocker = shulkerPlaceSightBlocker(key);
        if (blocker != null) {
            // A dispatched clearArea takes a couple of ticks to read as an active builder, and until
            // it does we land back here with the wall still standing. Re-sending it into that window
            // would spend the whole dig budget in six ticks without a single block being mined.
            if (contextTick - shulkerSightLastDigTick < SHULKER_SIGHT_DIG_SPINUP) {
                resetTimer();
                return true;
            }
            if (shulkerSightDigs++ < SHULKER_SIGHT_DIGS) {
                Helper.HELPER.logDirect("No line to the shulker spot at " + key.toShortString() + ", digging out the "
                        + playerContext.world().getBlockState(blocker).getBlock().getName().getString()
                        + " at " + blocker.toShortString() + ".");
                baritone.getPathingBehavior().cancelEverything();
                settings.buildRepeat.value = new Vec3i(0, 0, 0);
                baritone.getBuilderProcess().clearArea(blocker, blocker);
                shulkerSightLastDigTick = contextTick;
                resetTimer();
                return true;
            }
        } else if (shulkerSightWalks++ < SHULKER_SIGHT_WALKS) {
            transitionTo(walkCloserState); // nothing in the way, we just aren't on the standing spot
            resetTimer();
            return true;
        }

        rememberBadShulkerSpot(key);
        Helper.HELPER.logDirect("Still can't get a clear line to the shulker spot at " + key.toShortString()
                + ", picking a different one.");
        shulkerSightFailSpot = null;
        baritone.getPathingBehavior().cancelEverything();
        transitionTo(relocateState != null ? relocateState : HighwayState.Nothing);
        resetTimer();
        return true;
    }

    /** A placement that went through is proof the spot works; drop the line-of-sight failures there. */
    public void noteShulkerPlaced() {
        shulkerSightFailSpot = null;
        shulkerSightDigs = 0;
        shulkerSightWalks = 0;
        standClearSpot = null;
        standClearTries = 0;
        placeRefusedSpot = null;
        placeRefusedTries = 0;
    }

    private HighwayState noteShulkerPlaceRefused(BlockPos spot, HighwayState prevHighwayState) {
        BlockPos key = new BlockPos(spot.getX(), spot.getY(), spot.getZ());
        if (!key.equals(placeRefusedSpot)) {
            placeRefusedSpot = key;
            placeRefusedTries = 0;
        }
        if (placeRefusedTries++ < SHULKER_PLACE_REFUSALS) {
            if (placeRefusedTries == 1) {
                Helper.HELPER.logDirect("Failed to place shulker at " + key.toShortString() + ", retrying");
            }
            return prevHighwayState;
        }
        rememberBadShulkerSpot(key);
        Helper.HELPER.logDirect("Placing a shulker at " + key.toShortString()
                + " keeps being refused, picking a different spot.");
        placeRefusedSpot = null;
        placeRefusedTries = 0;
        return HighwayState.Nothing;
    }

    public boolean playerInWayOfPlacement(BlockPos placeLoc) {
        AABB cell = new AABB(placeLoc.getX(), placeLoc.getY(), placeLoc.getZ(),
                placeLoc.getX() + 1.0, placeLoc.getY() + 1.0, placeLoc.getZ() + 1.0);
        return playerContext.player().getBoundingBox().intersects(cell);
    }

    private BlockPos clearStandingSpotFor(BlockPos placeLoc) {
        BetterBlockPos feet = playerContext.playerFeet();
        // Step back on whichever side of the spot we already stand: the flows differ on that, and
        // crossing over the box to reach the far side is a walk we don't need to make
        int side = Integer.signum((feet.x - placeLoc.getX()) * highwayDirection.getX()
                + (feet.z - placeLoc.getZ()) * highwayDirection.getZ());
        if (side == 0) {
            side = 1;
        }
        for (int step : new int[]{2 * side, 3 * side, -2 * side, -3 * side}) {
            BlockPos candidate = placeLoc.offset(step * highwayDirection.getX(), 0, step * highwayDirection.getZ());
            if (candidate.getX() == feet.x && candidate.getZ() == feet.z) {
                continue; // where we already are, so pathing there would move us nowhere
            }
            if (hasFloorUnder(candidate)
                    && passableForRecovery(candidate.getX(), candidate.getY(), candidate.getZ())
                    && passableForRecovery(candidate.getX(), candidate.getY() + 1, candidate.getZ())) {
                return candidate;
            }
        }
        return null;
    }

    public boolean handleStandingInPlaceSpot(BlockPos placeLoc, HighwayState relocateState) {
        BlockPos key = new BlockPos(placeLoc.getX(), placeLoc.getY(), placeLoc.getZ());
        if (!playerInWayOfPlacement(key)) {
            standClearSpot = null;
            standClearTries = 0;
            return false;
        }
        if (baritone.getCustomGoalProcess().isActive()) {
            resetTimer();
            return true; // already walking off it
        }
        if (!key.equals(standClearSpot)) {
            standClearSpot = key;
            standClearTries = 0;
        }

        BlockPos clear = clearStandingSpotFor(key);
        if (clear != null && standClearTries++ < SHULKER_STAND_CLEAR_TRIES) {
            Helper.HELPER.logDirect("Standing in the shulker spot at " + key.toShortString()
                    + ", stepping back to " + clear.toShortString() + ".");
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(new BetterBlockPos(clear)));
            resetTimer();
            return true;
        }

        // Nowhere to step to, or the walk never took: the spot itself is the thing to give up on
        rememberBadShulkerSpot(key);
        Helper.HELPER.logDirect("Can't get out of the shulker spot at " + key.toShortString() + ", picking a different one.");
        standClearSpot = null;
        standClearTries = 0;
        baritone.getPathingBehavior().cancelEverything();
        transitionTo(relocateState != null ? relocateState : HighwayState.Nothing);
        resetTimer();
        return true;
    }

    /** A box that opened is proof its spot is fine; don't carry earlier failures there forward. */
    public void noteShulkerOpened() {
        shulkerOpenFailSpot = null;
        shulkerOpenFailTries = 0;
    }

    public boolean handleShulkerWouldNotOpen(BlockPos spot) {
        BlockPos key = new BlockPos(spot.getX(), spot.getY(), spot.getZ());
        if (!key.equals(shulkerOpenFailSpot)) {
            shulkerOpenFailSpot = key;
            shulkerOpenFailTries = 0;
        }
        shulkerOpenFailTries++;

        BlockState state = playerContext.world().getBlockState(key);
        if (!(state.getBlock() instanceof ShulkerBoxBlock)) {
            return false; // the box isn't there at all, which is the caller's re-place case
        }
        Direction facing = state.getValue(ShulkerBoxBlock.FACING);
        boolean lidHasRoom = canShulkerOpenToward(key, facing);

        if (lidHasRoom && shulkerOpenFailTries <= SHULKER_OPEN_RETRIES) {
            return false; // nothing in the way, so something transient ate the open - retry as before
        }

        // Something is in the lid's way. Digging it out beats a new spot
        if (!lidHasRoom && facing != Direction.DOWN
                && shulkerOpenFailTries <= SHULKER_OPEN_RETRIES + SHULKER_OPEN_UNBLOCK_TRIES) {
            BlockPos blocked = key.relative(facing);
            Helper.HELPER.logDirect("Shulker at " + key.toShortString() + " is facing " + facing + " into "
                    + playerContext.world().getBlockState(blocked).getBlock().getName().getString()
                    + ", digging that out so it can open.");
            baritone.getPathingBehavior().cancelEverything();
            settings.buildRepeat.value = new Vec3i(0, 0, 0);
            baritone.getBuilderProcess().clearArea(blocked, blocked);
            return false; // the placing state waits the builder out, then hands the box back to us
        }

        rememberBadShulkerSpot(key);
        Helper.HELPER.logDirect("Shulker at " + key.toShortString() + " still won't open after "
                + shulkerOpenFailTries + " tries, mining it back to place it somewhere else.");
        shulkerOpenFailSpot = null;
        shulkerOpenFailTries = 0;
        baritone.getPathingBehavior().cancelEverything();
        setPlaceLoc(key);
        transitionTo(HighwayState.MiningMisplacedShulker);
        resetTimer();
        return true;
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

                    swapSlot = getThrowawaySlotToToss();
                    if (swapSlot == -1) {
                        // Also didn't find any throwaway items
                        return 0;
                    }
                    playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, playerContext.player());
                    playerContext.playerController().windowClick(curContainer.containerId, swapSlot < 9 ? swapSlot + 54 : swapSlot + 18, 0, ClickType.PICKUP, playerContext.player()); // Have to convert slot id to single chest slot id
                    playerContext.playerController().windowClick(curContainer.containerId, -999, 0, ClickType.PICKUP, playerContext.player()); // Throw away throwaway item
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
                    // For some reason we have no gapples and no air slots so we have to throw out some throwaway items
                    int throwawaySlot = getThrowawaySlotToToss();
                    if (throwawaySlot == -1) {
                        return 0;
                    }
                    playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, playerContext.player());
                    playerContext.playerController().windowClick(curContainer.containerId, throwawaySlot < 9 ? throwawaySlot + 54 : throwawaySlot + 18, 0, ClickType.PICKUP, playerContext.player()); // Have to convert slot id to single chest slot id
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

    /**
     * With an open totem shulker box, pull one slot's worth of totems into the inventory. Totems
     * don't stack, so every totem needs its own free slot; when there is none, swap a throwaway
     * stack out for the totems and drop it. Returns the number of totems moved (0 when none left).
     */
    public int lootTotemChestSlot() {
        AbstractContainerMenu curContainer = playerContext.player().containerMenu;
        for (int i = 0; i < 27; i++) {
            if (curContainer.getSlot(i).getItem().is(Items.TOTEM_OF_UNDYING)) {
                int count = curContainer.getSlot(i).getItem().getCount();

                if (getItemSlot(Item.getId(Items.AIR)) == -1) {
                    // No free slot for the totems, so throw out some throwaway items to make room
                    int throwawaySlot = getThrowawaySlotToToss();
                    if (throwawaySlot == -1) {
                        return 0;
                    }
                    playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, playerContext.player());
                    playerContext.playerController().windowClick(curContainer.containerId, throwawaySlot < 9 ? throwawaySlot + 54 : throwawaySlot + 18, 0, ClickType.PICKUP, playerContext.player()); // Have to convert slot id to single chest slot id
                    playerContext.playerController().windowClick(curContainer.containerId, -999, 0, ClickType.PICKUP, playerContext.player());
                } else {
                    // There's an air slot so we can just do a quick move
                    playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.QUICK_MOVE, playerContext.player());
                }

                return count;
            }
        }

        return 0;
    }

    public int lootEnderChestSlot() {
        int count = 0;
        AbstractContainerMenu curContainer = playerContext.player().containerMenu;
        for (int i = 0; i < 27; i++) {
            if (curContainer.getSlot(i).getItem().getItem() instanceof BlockItem &&
                    ((BlockItem) curContainer.getSlot(i).getItem().getItem()).getBlock() instanceof EnderChestBlock) {
                count += curContainer.getSlot(i).getItem().getCount();

                if (getItemSlot(Item.getId(Items.AIR)) == -1) {
                    // No empty slot: toss a throwaway now and quick-move on the next call. Swapping the
                    // stack straight into the throwaway's slot skips the merge into partial stacks and
                    // leaves the chests fragmented, which costs a slot when the kept ones are stashed.
                    int throwawaySlot = getThrowawaySlotToToss();
                    if (throwawaySlot == -1) {
                        return 0;
                    }
                    playerContext.playerController().windowClick(curContainer.containerId, throwawaySlot < 9 ? throwawaySlot + 54 : throwawaySlot + 18, 0, ClickType.PICKUP, playerContext.player()); // Have to convert slot id to single chest slot id
                    playerContext.playerController().windowClick(curContainer.containerId, -999, 0, ClickType.PICKUP, playerContext.player());
                    return -1;
                }
                // Quick move merges into partial stacks first, then takes the empty slot
                playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.QUICK_MOVE, playerContext.player());

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

                    case Totem:
                        if (isTotemShulker(stack) > 0) {
                            doLoot = true;
                        }
                        break;

                    case AnyPickaxe:
                        if (isPickaxeShulker(stack) > 0) {
                            doLoot = true;
                        }
                }
                if (doLoot) {
                    int depletedSlot = getShulkerSlot(ShulkerType.DepletedPickaxe);
                    if (depletedSlot != -1) {
                        playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, playerContext.player());
                        playerContext.playerController().windowClick(curContainer.containerId, depletedSlot < 9 ? depletedSlot + 54 : depletedSlot + 18, 0, ClickType.PICKUP, playerContext.player()); // Have to convert slot id to single chest slot id
                        playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, playerContext.player()); // Put depleted shulker in looted slot
                    } else if (getItemSlot(Item.getId(Items.AIR)) == -1) {
                        // For some reason we have no air slots so we have to throw out some throwaway items
                        int throwawaySlot = getThrowawaySlotToToss();
                        if (throwawaySlot == -1) {
                            return 0;
                        }
                        playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, playerContext.player());
                        playerContext.playerController().windowClick(curContainer.containerId, throwawaySlot < 9 ? throwawaySlot + 54 : throwawaySlot + 18, 0, ClickType.PICKUP, playerContext.player()); // Have to convert slot id to single chest slot id
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

    public int depositDepletedShulkerChestSlot() {
        int depletedSlot = getShulkerSlot(ShulkerType.DepletedPickaxe);
        if (depletedSlot == -1) {
            return 0;
        }

        AbstractContainerMenu curContainer = playerContext.player().containerMenu;
        for (int i = 0; i < 27; i++) {
            if (curContainer.getSlot(i).getItem().isEmpty()) {
                playerContext.playerController().windowClick(curContainer.containerId, depletedSlot < 9 ? depletedSlot + 54 : depletedSlot + 18, 0, ClickType.QUICK_MOVE, playerContext.player()); // Have to convert slot id to single chest slot id
                return 1;
            }
        }

        return 0;
    }

    /**
     * Inventory slot of a loose ender chest stack that still has room to merge into (count < 64),
     * preferring the smallest so we top a partial stack rather than starting a new one. -1 if none.
     */
    private int getMergeableEnderChestSlot() {
        int best = -1;
        int bestCount = 64;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = playerContext.player().getInventory().items.get(i);
            if (stack.getItem() instanceof BlockItem && ((BlockItem) stack.getItem()).getBlock() instanceof EnderChestBlock && stack.getCount() < 64) {
                if (stack.getCount() < bestCount) {
                    best = i;
                    bestCount = stack.getCount();
                }
            }
        }
        return best;
    }

    /**
     * With an open ender chest shulker box, top up our loose ender chest stack toward {@code target}
     * (capped at 64) by merging a partial amount out of a single box slot, so it never spills into a
     * second inventory slot. Returns the number of ender chests moved this call (0 when nothing moved).
     */
    public int topUpEnderChestSlotFromShulker(int target) {
        target = Math.min(target, 64);
        int have = getItemCountInventory(Item.getId(Blocks.ENDER_CHEST.asItem()));
        int need = target - have;
        if (need <= 0) {
            return 0;
        }

        AbstractContainerMenu curContainer = playerContext.player().containerMenu;
        int looseSlot = getMergeableEnderChestSlot();
        for (int i = 0; i < 27; i++) {
            ItemStack stack = curContainer.getSlot(i).getItem();
            if (!(stack.getItem() instanceof BlockItem) || !(((BlockItem) stack.getItem()).getBlock() instanceof EnderChestBlock)) {
                continue;
            }
            int boxCount = stack.getCount();
            if (looseSlot == -1) {
                // No partial loose stack to merge into. Only proceed if we have a free slot to hold them;
                // capped target keeps it to a single slot. Bail otherwise (shouldn't happen above 0 chests).
                if (getItemSlot(Item.getId(Items.AIR)) == -1) {
                    return 0;
                }
                playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.QUICK_MOVE, playerContext.player());
                return boxCount;
            }
            int looseCount = playerContext.player().getInventory().items.get(looseSlot).getCount();
            int containerLooseSlot = invSlotToMenuSlot(looseSlot);
            // Pick up the box stack, deposit into our loose slot (fills to at most 64), put any remainder back
            playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, playerContext.player());
            playerContext.playerController().windowClick(curContainer.containerId, containerLooseSlot, 0, ClickType.PICKUP, playerContext.player());
            playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, playerContext.player()); // empty cursor -> no-op, remainder -> back to box slot
            return Math.min(boxCount, 64 - looseCount);
        }

        return 0;
    }

    /**
     * With an open ender storage (ender chest) container, deposit one shulker box of the given type
     * from inventory into a free storage slot. Returns 1 on success, 0 if none to deposit or no space.
     */
    public int depositShulkerChestSlot(ShulkerType shulkerType) {
        int slot = getShulkerSlot(shulkerType);
        if (slot == -1) {
            return 0;
        }

        AbstractContainerMenu curContainer = playerContext.player().containerMenu;
        for (int i = 0; i < 27; i++) {
            if (curContainer.getSlot(i).getItem().isEmpty()) {
                playerContext.playerController().windowClick(curContainer.containerId, invSlotToMenuSlot(slot), 0, ClickType.QUICK_MOVE, playerContext.player());
                return 1;
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

                    case Totem:
                        if (isTotemShulker(stack) > 0) {
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

                    case DepletedPickaxe:
                        if (isDepletedPickShulker(stack)) {
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

    /**
     * How far past the stand-off distance the build front may sit before creeping stops making sense.
     * Within it the creep is just trailing the printer a block at a time, which is all it is for; the
     * printer can only work about a reach ahead of us, so anything further is travel and the pathfinder
     * does travel better than a held walk key.
     */
    public int creepMaxOvershoot() {
        return 2 * (int) Math.ceil(settings.blockReachDistance.value);
    }

    public int getHighwayFrontDistance() {
        // scan far enough that the configured stand-off distance is actually reachable, and past it far
        // enough that the caller can tell "the front is right there" from "the front is a hike away"
        return scanFrontDistance(Math.max(10, settings.highwayEndDistance.value + creepMaxOvershoot()));
    }

    /**
     * Slices ahead of us we have actually seen built, stopping at the first slice whose chunks aren't
     * loaded instead of counting it as built. That is the built front, and it is as far as walking
     * anywhere is safe: past it the highway is either unknown or missing, and pathing over missing
     * highway makes the pathfinder bridge with throwaway netherrack that the printer then has to
     * break out again.
     */
    public int builtSlicesAhead(int maxScan) {
        return scanFrontLength(maxScan);
    }

    // what the last scanFrontDistance was pinned on, for nhwstatus
    private BlockPos frontBlockerSlice;
    private int frontBlockerX;
    private int frontBlockerY;
    private int frontBlockerZ;
    private double frontBlockerAlong;
    private double frontBlockerCross;
    private int frontBlockerColumn;
    private int frontBlockerCrossLen;
    private double frontBlockerPlayerColumn;
    private int frontBlockerLaneLo;
    private int frontBlockerLaneHi;

    // why the walk-creep did or didn't hold the key, tallied since the last nhwstatus
    private final Map<String, Integer> walkGateTally = new LinkedHashMap<>();
    private int walkGateTicks;
    private int walkGateHeld;

    /** Records the first condition that stopped the walk-creep holding the key this tick, or null if it held. */
    public void noteWalkGate(String blocker) {
        walkGateTicks++;
        if (blocker == null) {
            walkGateHeld++;
        } else {
            walkGateTally.merge(blocker, 1, Integer::sum);
        }
    }

    /** What has been stopping the walk-creep since this was last asked. Reading it resets the tally. */
    public String walkGateDiagnostic() {
        if (walkGateTicks == 0) {
            return "Walk gate: not evaluated (highwayEndDistance is -1, or not in BuildingHighway)";
        }
        StringBuilder sb = new StringBuilder(String.format("Walk gate: held %d of %d ticks (%d%%)",
                walkGateHeld, walkGateTicks, Math.round(100.0 * walkGateHeld / walkGateTicks)));
        walkGateTally.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> sb.append(" | ").append(e.getKey()).append(" x").append(e.getValue()));
        walkGateTally.clear();
        walkGateTicks = 0;
        walkGateHeld = 0;
        return sb.toString();
    }

    /** Blocks of travel per schematic slice: 1 on a straight highway, sqrt(2) on a diagonal. */
    private double stepLength() {
        return Math.sqrt(highwayDirection.getX() * highwayDirection.getX()
                + highwayDirection.getZ() * highwayDirection.getZ());
    }

    /**
     * Distance in blocks along the highway from the player to the nearest cell the schematic still
     * wants changed, capped at {@code maxBlocks}.
     * <p>
     * Blocks rather than slices, because a slice index is not a distance on a diagonal. The schematic
     * is sliced on a world axis, so a diagonal slice is sqrt(2) blocks long and its own cells are
     * spread a further (cross section - 1) / sqrt(2) blocks along the travel axis - all of them ahead
     * of the slice's origin for +X+Z and +X-Z, all behind it for -X-Z and -X+Z, since the schematic is
     * laid out from its low-cross-axis corner whichever way we're heading. Counting slices therefore
     * read the front several blocks late in one diagonal sense and several blocks early in the other,
     * and only the latter ever cleared the walk-creep's stand-off distance: the two +X diagonals never
     * creeped at all and built the whole road from pathed break goals, stopping at each end of the
     * 45-degree slice in turn. On a straight highway a slice is exactly one block along travel and
     * this returns what the slice count returned.
     */
    private int scanFrontDistance(int maxBlocks) {
        Vec3 direction = new Vec3(highwayDirection.getX(), highwayDirection.getY(), highwayDirection.getZ());
        Vec3 curPosPlayer = new Vec3(playerContext.playerFeet().getX(), playerContext.playerFeet().getY(), playerContext.playerFeet().getZ());
        BlockPos startCheckPos = getClosestPoint(new Vec3(originVector.x, originVector.y, originVector.z), direction, curPosPlayer, LocationType.HighwayBuild);

        int dirX = highwayDirection.getX();
        int dirZ = highwayDirection.getZ();
        int lenSq = dirX * dirX + dirZ * dirZ;
        double stepLen = stepLength();

        boolean crossZ = schematic.lengthZ() >= schematic.widthX();
        int crossLen = crossZ ? schematic.lengthZ() : schematic.widthX();
        // One column across the highway shifts a cell crossAlong along the highway and crossPerp across
        // it, both unnormalised, so both come out in blocks once divided by stepLen. crossAlong is 0 on
        // a straight, where a slice is perpendicular to travel, and +-1 on a diagonal, where it isn't.
        int crossAlong = crossZ ? dirZ : dirX;
        int crossPerp = crossZ ? dirX : -dirZ;
        // measured from the centre of the block we're standing in, so the reading only moves when we
        // change block rather than jittering with the walk; the +0.5s cancel between the two centres
        int relX = startCheckPos.getX() - playerContext.playerFeet().getX();
        int relZ = startCheckPos.getZ() - playerContext.playerFeet().getZ();
        double alongBase = relX * dirX + relZ * dirZ;
        double perpBase = -relX * dirZ + relZ * dirX;

        // The corridor the creep walks down: the column we stand in and one either side, clamped to
        // the walk lane at cross-axis offsets [1, highwayWidth] so the rails stay out of it. This is
        // the N-block generalisation of canWalkOnFloorAhead/canWalkThroughAhead, which ask the same
        // question about the single next step.
        //
        // Not the full width, because on a diagonal the road's outer columns lie ahead as well as to
        // the side: measured in game at width 7, column 7 of 8 sat 2.8 to 3.5 blocks across and 1.4
        // to 3.5 along, up to 5.3 from the eye against a 4.5 reach, so it could not be dug from the
        // lane at all - the bot digs it on the way past, when it draws level. "The whole width is
        // finished N blocks ahead" is therefore never true while a diagonal is being built. It isn't
        // a sign the printer is behind, and gating the walk on it reduced the walk to an inch.
        // Work to either side is still dug by the builder, still bounded by creepMaxOvershoot, and
        // still verified across the full cross-section by the back-check behind us.
        double playerColumn = -perpBase / crossPerp;
        playerColumn = Math.max(0, Math.min(crossLen - 1, playerColumn)); // standing off the side still measures a real corridor
        int bodyColumn = (int) Math.round(playerColumn);
        int laneLo = Math.max(1, bodyColumn - 1);
        int laneHi = Math.min(Math.min(crossLen - 1, settings.highwayWidth.value), bodyColumn + 1);

        // A cell of slice i in column j sits (alongBase + i * lenSq + j * crossAlong) / stepLen blocks
        // along the highway from us. Solve that for the slices that can put a cell in [0, maxBlocks]:
        // on a diagonal that reaches back past startCheckPos, since a slice trails cells behind its
        // own origin, and one of those can be the nearest thing left unbuilt.
        int alongSpanLo = Math.min(0, (crossLen - 1) * crossAlong);
        int alongSpanHi = Math.max(0, (crossLen - 1) * crossAlong);
        int iLo = (int) Math.floor((-alongBase - alongSpanHi) / lenSq);
        int iHi = (int) Math.ceil((maxBlocks * stepLen - alongBase - alongSpanLo) / lenSq);

        frontBlockerSlice = null;
        frontBlockerCrossLen = crossLen;
        frontBlockerPlayerColumn = playerColumn;
        frontBlockerLaneLo = laneLo;
        frontBlockerLaneHi = laneHi;

        double nearest = maxBlocks; // an all-correct scan means "at least this far built ahead"
        if (endPos != null) {
            int stepsToEnd = stepsAlongHighway(startCheckPos, endPos);
            iHi = Math.min(iHi, stepsToEnd);
            // past the last slice there is only the end, not more highway
            nearest = Math.min(nearest, Math.max(0, (alongBase + stepsToEnd * lenSq) / stepLen));
        }

        for (int i = iLo; i <= iHi; i++) {
            if ((alongBase + i * lenSq + alongSpanLo) / stepLen >= nearest) {
                break; // this slice and every one past it is further off than what we already found
            }
            BlockPos curPos = startCheckPos.offset(i * dirX, 0, i * dirZ);
            for (int y = 0; y < schematic.heightY(); y++) {
                for (int z = 0; z < schematic.lengthZ(); z++) {
                    for (int x = 0; x < schematic.widthX(); x++) {
                        int j = crossZ ? z : x;
                        if (j < laneLo || j > laneHi) {
                            continue;
                        }
                        double along = (alongBase + i * lenSq + j * crossAlong) / stepLen;
                        if (along < 0 || along >= nearest) {
                            continue; // behind us, or no closer than what we already found
                        }
                        if (cellScan(curPos, x, y, z) == CellScan.MISMATCH) {
                            nearest = along;
                            frontBlockerSlice = curPos;
                            frontBlockerX = x;
                            frontBlockerY = y;
                            frontBlockerZ = z;
                            frontBlockerAlong = along;
                            frontBlockerCross = (perpBase + j * crossPerp) / stepLen;
                            frontBlockerColumn = j;
                        }
                    }
                }
            }
        }
        return (int) Math.floor(nearest);
    }

    /**
     * What the walk-creep's front distance is currently pinned on. Rescans, so it reports the state
     * right now rather than whatever the last creep tick happened to see.
     */
    public String frontDistanceDiagnostic() {
        if (schematic == null) {
            return "Front: no schematic";
        }
        int dist = getHighwayFrontDistance();
        String corridor = String.format("cross-section %d wide, we're in column %.0f, watching columns %d-%d",
                frontBlockerCrossLen, frontBlockerPlayerColumn, frontBlockerLaneLo, frontBlockerLaneHi);
        if (frontBlockerSlice == null) {
            return "Front: " + dist + " blocks, nothing unbuilt in range (" + corridor + ")";
        }
        BlockPos pos = frontBlockerSlice.offset(frontBlockerX, frontBlockerY, frontBlockerZ);
        BlockState current = playerContext.world().getBlockState(pos);
        BlockState desired = schematic.desiredState(frontBlockerX, frontBlockerY, frontBlockerZ, current, this.approxPlaceable);
        return String.format("Front: %d blocks, pinned by %d,%d,%d (column %d of %d, %.2f along, %.2f across, y+%d): is %s, wants %s [%s]",
                dist, pos.getX(), pos.getY(), pos.getZ(),
                frontBlockerColumn, frontBlockerCrossLen - 1, frontBlockerAlong, frontBlockerCross, frontBlockerY,
                BlockUtils.blockToString(current.getBlock()), BlockUtils.blockToString(desired.getBlock()), corridor);
    }

    private enum CellScan {CORRECT, MISMATCH, UNLOADED}

    /**
     * Whether the cell at schematic coordinates ({@code x}, {@code y}, {@code z}) of the slice starting
     * at {@code slicePos} already matches the highway. Cells the schematic doesn't cover, and cells held
     * valid by the block above them, count as correct.
     */
    private CellScan cellScan(BlockPos slicePos, int x, int y, int z) {
        int blockX = x + slicePos.getX();
        int blockY = y + slicePos.getY();
        int blockZ = z + slicePos.getZ();
        BlockState current = playerContext.world().getBlockState(new BlockPos(blockX, blockY, blockZ));

        if (!schematic.inSchematic(x, y, z, current)) {
            return CellScan.CORRECT;
        }
        if (!baritone.bsi.worldContainsLoadedChunk(blockX, blockZ)) {
            return CellScan.UNLOADED;
        }
        // we can directly observe this block, it is in render distance

        ISchematic ourSchem = schematic.getSchematic(x, y, z, current).schematic;
        if (ourSchem instanceof WhiteBlackSchematic && ((WhiteBlackSchematic) ourSchem).isValidIfUnder() &&
                MovementHelper.isBlockNormalCube(playerContext.world().getBlockState(new BlockPos(blockX, blockY + 1, blockZ)))) {
            return CellScan.CORRECT;
        }
        return schematic.desiredState(x, y, z, current, this.approxPlaceable).equals(current)
                ? CellScan.CORRECT : CellScan.MISMATCH;
    }

    private int scanFrontLength(int scanLength) {
        Vec3 direction = new Vec3(highwayDirection.getX(), highwayDirection.getY(), highwayDirection.getZ());
        Vec3 curPosPlayer = new Vec3(playerContext.playerFeet().getX(), playerContext.playerFeet().getY(), playerContext.playerFeet().getZ());
        BlockPos startCheckPos = getClosestPoint(new Vec3(originVector.x, originVector.y, originVector.z), direction, curPosPlayer, LocationType.HighwayBuild);

        // Only the columns the player can actually work from where they stand count. On a highway
        // wider than the printer's reach sphere the outer columns are built later from another
        // lane, so demanding a whole correct cross-section would pin this at 0 forever and the
        // walk-creep would never engage. On narrow highways the window covers the full section, so
        // this is exactly the old behaviour.
        boolean crossZ = schematic.lengthZ() >= schematic.widthX();
        int crossLen = crossZ ? schematic.lengthZ() : schematic.widthX();
        int lateral = crossZ ? playerContext.playerFeet().getZ() - startCheckPos.getZ()
                : playerContext.playerFeet().getX() - startCheckPos.getX();
        lateral = Math.max(0, Math.min(crossLen - 1, lateral)); // standing off the side still measures a real corridor
        int corridorReach = (int) Math.ceil(settings.blockReachDistance.value);
        int crossLo = Math.max(0, lateral - corridorReach);
        int crossHi = Math.min(crossLen - 1, lateral + corridorReach);

        int maxResult = scanLength; // an all-correct scan means "at least this much built ahead"
        if (endPos != null) {
            int stepsToEnd = stepsAlongHighway(startCheckPos, endPos);
            if (stepsToEnd + 1 <= scanLength) {
                scanLength = stepsToEnd + 1;
                maxResult = stepsToEnd; // past the last slice there is only the end, not more highway
            }
        }
        for (int i = 0; i < scanLength; i++) {
            BlockPos curPos = startCheckPos.offset(i * highwayDirection.getX(), 0, i * highwayDirection.getZ());
            for (int y = 0; y < schematic.heightY(); y++) {
                for (int z = 0; z < schematic.lengthZ(); z++) {
                    if (crossZ && (z < crossLo || z > crossHi)) {
                        continue;
                    }
                    for (int x = 0; x < schematic.widthX(); x++) {
                        if (!crossZ && (x < crossLo || x > crossHi)) {
                            continue;
                        }
                        CellScan cell = cellScan(curPos, x, y, z);
                        if (cell == CellScan.MISMATCH || cell == CellScan.UNLOADED) {
                            return i;
                        }
                    }
                }
            }
        }

        // the whole scanned stretch is correct; report its length rather than 0, which is how far up
        // the highway the travel walk may head in one hop.
        return maxResult;
    }

    public boolean travelTowardsEnd() {
        if (endPos == null || invalidBlockFixActive()) {
            stopTravelTowardsEnd();
            return false;
        }
        if (travellingToEnd) {
            boolean ours = travelGoal != null && travelTarget != null && baritone.getCustomGoalProcess().isActive()
                    && baritone.getCustomGoalProcess().getGoal() == travelGoal;
            if (!ours) {
                // arrived, or something else took the wheel: let the builder look at what we loaded
                stopTravelTowardsEnd();
                return false;
            }
            int remaining = stepsAlongHighway(playerContext.playerFeet(), travelTarget);
            if (remaining < travelBestSteps) {
                travelBestSteps = remaining;
                travelNoProgressTicks = 0;
                return true; // still closing in
            }
            if (++travelNoProgressTicks <= TRAVEL_STALL_TICKS) {
                return true;
            }
            Helper.HELPER.logDirect("Walk to " + travelTarget + " hasn't gotten any closer in " + TRAVEL_STALL_TICKS + " ticks, handing it back to the builder.");
            stopTravelTowardsEnd();
            travelStallCooldown = TRAVEL_STALL_TICKS;
            return false;
        }
        if (travelStallCooldown > 0) {
            return false;
        }

        Vec3 direction = new Vec3(highwayDirection.getX(), highwayDirection.getY(), highwayDirection.getZ());
        Vec3 feetVec = new Vec3(playerContext.playerFeet().getX(), playerContext.playerFeet().getY(), playerContext.playerFeet().getZ());
        BetterBlockPos feetSlice = getClosestPoint(new Vec3(originVector.x, originVector.y, originVector.z), direction, feetVec, LocationType.HighwayBuild);

        // builtSlicesAhead counts slices while the stand-off distance is in blocks, and a diagonal
        // slice is sqrt(2) of them
        int standOffSlices = (int) Math.ceil(Math.max(0, settings.highwayEndDistance.value) / stepLength());
        int hop = builtSlicesAhead(Math.max(32, playerContext.minecraft().options.renderDistance().get() * 16)) - 1
                - standOffSlices;
        hop = Math.min(hop, stepsAlongHighway(feetSlice, endPos));
        if (hop <= 0) {
            return false;
        }
        travelTarget = laneOnSlice(feetSlice.offset(hop * highwayDirection.getX(), 0, hop * highwayDirection.getZ()));
        travelGoal = new GoalBlock(travelTarget);
        travelBestSteps = stepsAlongHighway(playerContext.playerFeet(), travelTarget);
        travelNoProgressTicks = 0;
        travellingToEnd = true;
        Helper.HELPER.logDirect("Nothing left to build within render distance, walking to " + travelTarget + " to load more of the highway.");
        baritone.getCustomGoalProcess().setGoalAndPath(travelGoal);
        return true;
    }

    public void stopTravelTowardsEnd() {
        if (!travellingToEnd) {
            return;
        }
        travellingToEnd = false;
        travelTarget = null;
        travelBestSteps = Integer.MAX_VALUE;
        travelNoProgressTicks = 0;
        if (travelGoal != null && baritone.getCustomGoalProcess().getGoal() == travelGoal) {
            baritone.getCustomGoalProcess().onLostControl();
            baritone.getPathingBehavior().cancelSegmentIfSafe();
        }
        travelGoal = null;
    }

    private BetterBlockPos laneOnSlice(BlockPos buildLinePos) {
        Vec3 direction = new Vec3(highwayDirection.getX(), highwayDirection.getY(), highwayDirection.getZ());
        BetterBlockPos lane = getClosestPoint(new Vec3(backPathOriginVector.x, backPathOriginVector.y, backPathOriginVector.z),
                direction, new Vec3(buildLinePos.getX(), buildLinePos.getY(), buildLinePos.getZ()), LocationType.ShulkerEchestInteraction);
        int off = stepsAlongHighway(lane, buildLinePos);
        return liftOntoPavement(off == 0 ? lane : new BetterBlockPos(lane.offset(off * highwayDirection.getX(), 0, off * highwayDirection.getZ())));
    }

    public boolean isHighwayEndComplete() {
        if (endPos == null) {
            return false;
        }
        Vec3 direction = new Vec3(highwayDirection.getX(), highwayDirection.getY(), highwayDirection.getZ());
        Vec3 curPosPlayer = new Vec3(playerContext.playerFeet().getX(), playerContext.playerFeet().getY(), playerContext.playerFeet().getZ());
        // feetClosest is clamped to the end: even if we wandered past the end the scan still covers
        // the final checkBackDistance slices instead of degenerating
        BetterBlockPos feetClosest = getClosestPoint(new Vec3(originVector.x, originVector.y, originVector.z), direction, curPosPlayer, LocationType.HighwayBuild);
        Vec3 curPosBack = new Vec3(feetClosest.getX() + (highwayCheckBackDistance * -highwayDirection.getX()), feetClosest.getY(), feetClosest.getZ() + (highwayCheckBackDistance * -highwayDirection.getZ()));
        BlockPos startCheckPos = getClosestPoint(new Vec3(originVector.x, originVector.y, originVector.z), direction, curPosBack, LocationType.HighwayBuild);
        BlockPos startCheckPosLiq = getClosestPoint(new Vec3(liqOriginVector.x, liqOriginVector.y, liqOriginVector.z), direction, curPosBack, LocationType.ShulkerEchestInteraction);
        int scanDist = stepsAlongHighway(startCheckPos, endPos) + 1;
        if (!isStretchLoaded(startCheckPos, schematic.widthX(), schematic.lengthZ(), scanDist)
                || !isStretchLoaded(startCheckPosLiq, liqCheckSchem.widthX(), liqCheckSchem.lengthZ(), scanDist)) {
            return false;
        }
        return isHighwayCorrect(startCheckPos, startCheckPosLiq, scanDist, false) == HighwayBlockState.Air;
    }

    private boolean isStretchLoaded(BlockPos startPos, int widthX, int lengthZ, int distanceToCheck) {
        for (int i = distanceToCheck - 1; i >= 1; i--) {
            BlockPos curPos = startPos.offset(i * highwayDirection.getX(), 0, i * highwayDirection.getZ());
            for (int z = 0; z < lengthZ; z++) {
                for (int x = 0; x < widthX; x++) {
                    if (!baritone.bsi.worldContainsLoadedChunk(x + curPos.getX(), z + curPos.getZ())) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public boolean canWalkOnFloorAhead() {
        BetterBlockPos feet = playerContext.playerFeet();
        int dirX = highwayDirection.getX();
        int dirZ = highwayDirection.getZ();
        int floorY = feet.y - 1;

        // Block we'll be standing on after stepping forward
        if (!MovementHelper.canWalkOn(baritone.bsi, feet.x + dirX, floorY, feet.z + dirZ)) {
            return false;
        }
        // For diagonals also check the two orthogonally-adjacent floor cells
        if (dirX != 0 && dirZ != 0) {
            return MovementHelper.canWalkOn(baritone.bsi, feet.x + dirX, floorY, feet.z)
                    && MovementHelper.canWalkOn(baritone.bsi, feet.x, floorY, feet.z + dirZ);
        }
        return true;
    }

    public boolean canWalkThroughAhead() {
        BetterBlockPos feet = playerContext.playerFeet();
        int x = feet.x + highwayDirection.getX();
        int z = feet.z + highwayDirection.getZ();
        return MovementHelper.canWalkThrough(baritone.bsi, x, feet.y, z)
                && MovementHelper.canWalkThrough(baritone.bsi, x, feet.y + 1, z);
    }

    public boolean isPlayerInPortal() {
        AABB bb = playerContext.player().getBoundingBox();
        for (BlockPos pos : BlockPos.betweenClosed((int) Math.floor(bb.minX), (int) Math.floor(bb.minY), (int) Math.floor(bb.minZ),
                (int) Math.floor(bb.maxX), (int) Math.floor(bb.maxY), (int) Math.floor(bb.maxZ))) {
            if (playerContext.world().getBlockState(pos).getBlock() instanceof NetherPortalBlock) {
                return true;
            }
        }
        return false;
    }

    /**
     * An adjacent cell to step to to get out of the portal we're standing in: walkable at feet and
     * head height, not another portal cell, with solid ground under it. Exits through the 1-thick
     * portal plane are tried first, nearest side first.
     */
    public BlockPos findPortalEscapeTarget() {
        return findPortalEscapeTarget(true);
    }

    /**
     * @param requireFloor false returns an exit cell even when nothing solid is under it, for
     *                     callers that will place their own footing (floating exit portals)
     */
    public BlockPos findPortalEscapeTarget(boolean requireFloor) {
        BetterBlockPos feet = playerContext.playerFeet();
        for (Direction dir : sortedPortalExitDirections()) {
            int x = feet.x + dir.getStepX();
            int z = feet.z + dir.getStepZ();
            if (playerContext.world().getBlockState(new BlockPos(x, feet.y, z)).getBlock() instanceof NetherPortalBlock
                    || playerContext.world().getBlockState(new BlockPos(x, feet.y + 1, z)).getBlock() instanceof NetherPortalBlock) {
                continue;
            }
            if (MovementHelper.canWalkThrough(baritone.bsi, x, feet.y, z)
                    && MovementHelper.canWalkThrough(baritone.bsi, x, feet.y + 1, z)
                    && (!requireFloor || MovementHelper.canWalkOn(baritone.bsi, x, feet.y - 1, z))) {
                return new BlockPos(x, feet.y, z);
            }
        }
        return null;
    }

    /**
     * The cell we would want open to step out of the portal we're standing in, even if it's
     * currently blocked by junk or has nothing under it; the clear+fill target for exit prep.
     */
    public BlockPos portalExitPrepTarget() {
        BetterBlockPos feet = playerContext.playerFeet();
        for (Direction dir : sortedPortalExitDirections()) {
            int x = feet.x + dir.getStepX();
            int z = feet.z + dir.getStepZ();
            if (playerContext.world().getBlockState(new BlockPos(x, feet.y, z)).getBlock() instanceof NetherPortalBlock
                    || playerContext.world().getBlockState(new BlockPos(x, feet.y + 1, z)).getBlock() instanceof NetherPortalBlock) {
                continue;
            }
            return new BlockPos(x, feet.y, z);
        }
        return null;
    }

    /** Horizontal exit directions, exits through the 1-thick portal plane first, nearest side first. */
    private List<Direction> sortedPortalExitDirections() {
        BetterBlockPos feet = playerContext.playerFeet();
        BlockState feetState = playerContext.world().getBlockState(feet);
        BlockState headState = playerContext.world().getBlockState(feet.above());
        BlockState portalState = feetState.getBlock() instanceof NetherPortalBlock ? feetState
                : headState.getBlock() instanceof NetherPortalBlock ? headState : null;

        Vec3 pos = playerContext.player().position();
        List<Direction> candidates = new ArrayList<>(Arrays.asList(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST));
        candidates.sort(java.util.Comparator
                .<Direction>comparingInt(dir -> portalState != null && dir.getAxis() != portalState.getValue(NetherPortalBlock.AXIS) ? 0 : 1)
                .thenComparingDouble(dir -> {
                    double dx = feet.x + 0.5 + dir.getStepX() - pos.x;
                    double dz = feet.z + 0.5 + dir.getStepZ() - pos.z;
                    return dx * dx + dz * dz;
                }));
        return candidates;
    }

    /** Points the player's yaw at the center of the given cell. */
    public void faceBlock(BlockPos target) {
        Vec3 pos = playerContext.player().position();
        double dx = target.getX() + 0.5 - pos.x;
        double dz = target.getZ() + 0.5 - pos.z;
        playerContext.player().setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
    }

    /** Faces the given cell and holds the forward key toward it. */
    public void walkTowardBlock(BlockPos target) {
        faceBlock(target);
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
    }

    /** Walk-creep guard: no lit portal blocks in the cells we're about to walk into. */
    public boolean noPortalAhead() {
        BetterBlockPos feet = playerContext.playerFeet();
        int dirX = highwayDirection.getX();
        int dirZ = highwayDirection.getZ();
        for (int step = 1; step <= 2; step++) {
            if (isPortalCell(feet.x + dirX * step, feet.y, feet.z + dirZ * step)) {
                return false;
            }
            // for diagonals also check the two cells the hitbox brushes on the way
            if (dirX != 0 && dirZ != 0
                    && (isPortalCell(feet.x + dirX * step, feet.y, feet.z + dirZ * (step - 1))
                    || isPortalCell(feet.x + dirX * (step - 1), feet.y, feet.z + dirZ * step))) {
                return false;
            }
        }
        return true;
    }

    private boolean isPortalCell(int x, int y, int z) {
        return playerContext.world().getBlockState(new BlockPos(x, y, z)).getBlock() instanceof NetherPortalBlock
                || playerContext.world().getBlockState(new BlockPos(x, y + 1, z)).getBlock() instanceof NetherPortalBlock;
    }

    /**
     * Signed index of the cross-section column the given block sits in, measured across the highway
     * from its centre line. Steps along the highway don't change it, on diagonals as well as
     * straights: the perpendicular of an unnormalised (dx, dz) is exactly one per column and zero
     * per slice for all eight directions. Any point on the line works as the reference, since a
     * perpendicular kills the along-line component.
     */
    public int lateralColumn(int x, int z) {
        int ox = (int) Math.round(originVector.x);
        int oz = (int) Math.round(originVector.z);
        return -(x - ox) * highwayDirection.getZ() + (z - oz) * highwayDirection.getX();
    }

    /**
     * The cross-section column the given block sits in, counted the way the schematic counts: 0 is
     * the low-side rail, 1 to {@code highwayWidth} the walk lane, {@code highwayWidth + 1} the
     * high-side rail. That is {@link #lateralColumn} with the direction's sign taken back out, so
     * the same column reads the same number whichever of the eight directions we're heading.
     */
    public int crossSectionColumn(int x, int z) {
        // Group A lays the cross-section out along Z and group B along X; the perpendicular grows
        // one per column in both, up to this sign. Same crossPerp scanFrontDistance measures with.
        int crossPerp = NetherHighwayBuilderBehavior.isGroupA(highwayDirection)
                ? highwayDirection.getX() : -highwayDirection.getZ();
        return crossPerp < 0 ? -lateralColumn(x, z) : lateralColumn(x, z);
    }

    /** Whether the given block is in the walk lane rather than one of the rail columns beside it. */
    public boolean inWalkLane(int x, int z) {
        int column = crossSectionColumn(x, z);
        return column >= 1 && column <= settings.highwayWidth.value;
    }

    /** The yaw that points straight down the highway direction. */
    public float highwayDirectionYaw() {
        return (float) Math.toDegrees(Math.atan2(-highwayDirection.getX(), highwayDirection.getZ()));
    }

    public void faceHighwayDirection() {
        double dirX = highwayDirection.getX();
        double dirZ = highwayDirection.getZ();
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len == 0) {
            return;
        }
        dirX /= len;
        dirZ /= len;
        float yaw = (float) Math.toDegrees(Math.atan2(-dirX, dirZ));

        // Both schematic layouts put the walk lane at cross-axis offsets [1, highwayWidth] from
        // the slice origin, so the lane center sits at origin + 1 + width/2 on the cross axis
        Vec3 playerPos = playerContext.player().position();
        BetterBlockPos sliceOrigin = getClosestPoint(new Vec3(originVector.x, originVector.y, originVector.z),
                new Vec3(highwayDirection.getX(), highwayDirection.getY(), highwayDirection.getZ()), playerPos, LocationType.HighwayBuild);
        double laneCenterOffset = 1 + settings.highwayWidth.value / 2.0;
        boolean crossAxisZ = (highwayDirection.getZ() == 0 && Math.abs(highwayDirection.getX()) == 1)
                || (highwayDirection.getX() == 1 && highwayDirection.getZ() == 1)
                || (highwayDirection.getX() == -1 && highwayDirection.getZ() == -1);
        double centerX = crossAxisZ ? sliceOrigin.x + 0.5 : sliceOrigin.x + laneCenterOffset;
        double centerZ = crossAxisZ ? sliceOrigin.z + laneCenterOffset : sliceOrigin.z + 0.5;

        // Signed offset toward the yaw+90 side; steering by -offset walks it back toward zero
        double lateral = (playerPos.x - centerX) * -dirZ + (playerPos.z - centerZ) * dirX;

        double halfLane = settings.highwayWidth.value / 2.0;
        if (highwayDirection.getX() != 0 && highwayDirection.getZ() != 0) {
            halfLane /= Math.sqrt(2); // diagonal corridors are measured perpendicular to travel
        }

        double engage = Math.max(0.3, Math.min(0.75, halfLane - 0.6));
        double release = engage * 0.4;
        if (Math.abs(lateral) > (walkCentering ? release : engage)) {
            walkCentering = true;
            // 10 degrees of steer per block of offset, capped at 20 so the angle stays shallow
            yaw -= (float) Math.max(-20.0, Math.min(20.0, lateral * 10.0));
        } else {
            walkCentering = false;
        }
        playerContext.player().setYRot(yaw);

        // Let a pitch parked by chest opening or block aims drift back to a natural level
        float pitch = playerContext.player().getXRot();
        if (pitch > 10) {
            playerContext.player().setXRot(pitch - 1);
        } else if (pitch < -20) {
            playerContext.player().setXRot(pitch + 1);
        }
    }


    public HighwayBlockState isHighwayCorrect(BlockPos startPos, BlockPos startPosLiq, int distanceToCheck, boolean renderLiquidScan) {
        // startPos needs to be in center of highway
        if (endPos != null) {
            // Terrain past the end stays unbuilt; scanning it would trigger endless fixes
            distanceToCheck = Math.min(distanceToCheck, stepsAlongHighway(startPos, endPos) + 1);
        }
        renderLockBuilding.lock();
        renderBlocksBuilding.clear();
        lastMismatches.clear();
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
                        BlockState desiredState = schematic.desiredState(x, y, z, current, this.approxPlaceable);
                        if (i == 1) {
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

                            if (!desiredState.equals(current)) {
                                if (current.getBlock() instanceof NetherPortalBlock) {
                                    // unbreakable directly; it pops on its own once the builder mines the frame
                                    continue;
                                }
                                if (!baritone.getBuilderProcess().checkNoEntityCollision(new AABB(new BlockPos(blockX, blockY, blockZ)), playerContext.player())) {
                                    List<Entity> entityList = playerContext.world().getEntities(null, new AABB(new BlockPos(blockX, blockY, blockZ)));
                                    for (Entity entity : entityList) {
                                        if (entity instanceof Boat || entity.isVehicle()) {
                                            // can't do boats lol
                                            boatHasPassenger = entity.isVehicle();
                                            boatLocation = new BlockPos(blockX, blockY, blockZ);
                                            renderLockBuilding.unlock();
                                            return HighwayBlockState.Boat;
                                        }
                                    }
                                }

                                if (desiredState.getBlock() instanceof AirBlock && Baritone.settings().buildIgnoreBlocks.value.contains(current.getBlock())) {
                                    continue;
                                }
                                // Never should be liquids, except where the schematic covers them with a
                                // placed block (the support row under the floor, the diagonal rail supports).
                                // Those are ordinary build work: the liquid removal cannot even see them,
                                // since its scan starts at the walking level, so routing there just
                                // bounces between LiquidRemovalPrep and a builder restart forever.
                                if (current.getBlock() instanceof LiquidBlock
                                        && !(ourSchem instanceof WhiteBlackSchematic && ((WhiteBlackSchematic) ourSchem).coversLiquid(current))) {
                                    renderLockBuilding.unlock();
                                    return HighwayBlockState.Liquids;
                                } else {
                                    foundBlocks = true;
                                    if (lastMismatches.size() < 4) {
                                        lastMismatches.add("(" + blockX + ", " + blockY + ", " + blockZ + ") "
                                                + BlockUtils.blockToString(current.getBlock()) + " -> " + BlockUtils.blockToString(desiredState.getBlock()));
                                    }
                                }
                            }
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
        if (endPos != null) {
            distanceToCheck = Math.min(distanceToCheck, stepsAlongHighway(startPos, endPos) + 1);
        }
        if (renderCheckedBlocks) {
            BlockPos slice = startPos.offset(highwayDirection.getX(), 0, highwayDirection.getZ());
            AABB area = new AABB(slice.getX(), slice.getY(), slice.getZ(),
                    slice.getX() + liqCheckSchem.widthX(), slice.getY() + liqCheckSchem.heightY(), slice.getZ() + liqCheckSchem.lengthZ());
            renderLockLiquid.lock();
            renderAreaLiquid = area;
            renderLockLiquid.unlock();
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
                        if (baritone.bsi.worldContainsLoadedChunk(blockX, blockZ)) { // check if its in render distance, not if its in cache
                            // Never should be liquids
                            if (current.getBlock() instanceof LiquidBlock) {
                                return new BlockPos(blockX, blockY, blockZ);
                            }
                        }

                    }
                }
            }
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

    // A pool is enclosed when no block of it (source or flowing) touches air: digging hasn't
    // opened it up yet and nothing can flow out
    public boolean isPoolEnclosed(ArrayList<BlockPos> sourceBlocks, ArrayList<BlockPos> flowingBlocks) {
        for (BlockPos pos : sourceBlocks) {
            if (touchesAir(pos)) {
                return false;
            }
        }
        for (BlockPos pos : flowingBlocks) {
            if (touchesAir(pos)) {
                return false;
            }
        }
        return true;
    }

    private boolean touchesAir(BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (getIssueType(pos.relative(dir)) == HighwayBlockState.Air) {
                return true;
            }
        }
        return false;
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
        Placed,
        Aiming
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

        if (p_Rotate) {
            Optional<Rotation> serverRotation = baritone.getLookBehavior().getServerRotation();
            if (serverRotation.isPresent()) {
                HitResult res = RayTraceUtils.rayTraceTowards(playerContext.player(), serverRotation.get(), p_Distance);
                if (res != null && res.getType() == HitResult.Type.BLOCK) {
                    BlockHitResult hit = (BlockHitResult) res;
                    if (hit.getBlockPos().relative(hit.getDirection()).equals(pos) && clickFace(hit, packetSwing, hand) == PlaceResult.Placed) {
                        return PlaceResult.Placed;
                    }
                }
            }

            Rotation aimRotation = findPlaceRotation(pos, p_Distance);
            if (aimRotation != null) {
                baritone.getLookBehavior().updateTarget(aimRotation, true);
                return PlaceResult.Aiming;
            }
            return PlaceResult.CantPlace;
        }

        for (BlockHitResult blockHitResult : findThroughWallsPlaceHits(pos, p_Distance)) {
            if (clickFace(blockHitResult, packetSwing, hand) == PlaceResult.Placed)
                return PlaceResult.Placed;
        }
        return PlaceResult.CantPlace;
    }

    public boolean placeAimable(BlockPos pos, float p_Distance) {
        return findPlaceRotation(pos, p_Distance) != null;
    }

    public boolean placeThroughWallsAimable(BlockPos pos, float p_Distance) {
        return !findThroughWallsPlaceHits(pos, p_Distance).isEmpty();
    }

    // Placement candidates when line of sight doesn't matter: every face of a solid, non-fluid
    // neighbor that points into pos and whose center is within reach, visible or not
    private List<BlockHitResult> findThroughWallsPlaceHits(BlockPos pos, float p_Distance) {
        final Vec3 eyesPos = getEyesPos();
        final List<BlockHitResult> hits = new ArrayList<>();

        for (final Direction side : Direction.values())
        {
            final BlockPos neighbor = pos.offset(side.getUnitVec3i());
            final Direction side2 = side.getOpposite();

            if (!playerContext.world().getBlockState(neighbor).getFluidState().isEmpty())
                continue;

            VoxelShape collisionShape = playerContext.world().getBlockState(neighbor).getCollisionShape(playerContext.world(), neighbor);
            if (collisionShape == Shapes.empty())
                continue;

            final Vec3 hitVec = new Vec3(neighbor.getX(), neighbor.getY(), neighbor.getZ()).add(0.5, 0.5, 0.5).add(new Vec3(side2.getStepX(), side2.getStepY(), side2.getStepZ()).scale(0.5));
            if (eyesPos.distanceTo(hitVec) <= p_Distance) {
                hits.add(new BlockHitResult(hitVec, side2, neighbor, false));
            }
        }
        return hits;
    }

    private Rotation findPlaceRotation(BlockPos pos, float p_Distance) {
        final Vec3 eyesPos = getEyesPos();

        for (final Direction side : Direction.values())
        {
            final BlockPos neighbor = pos.offset(side.getUnitVec3i());
            final Direction side2 = side.getOpposite();

            if (!playerContext.world().getBlockState(neighbor).getFluidState().isEmpty())
                continue;

            VoxelShape collisionShape = playerContext.world().getBlockState(neighbor).getCollisionShape(playerContext.world(), neighbor);
            if (collisionShape == Shapes.empty())
                continue;

            final Vec3 faceCenter = new Vec3(neighbor.getX(), neighbor.getY(), neighbor.getZ()).add(0.5, 0.5, 0.5).add(new Vec3(side2.getStepX(), side2.getStepY(), side2.getStepZ()).scale(0.5));
            final Vec3 u = side2.getAxis() == Direction.Axis.X ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
            final Vec3 v = side2.getAxis() == Direction.Axis.Z ? new Vec3(0, 1, 0) : new Vec3(0, 0, 1);
            final Vec3[] facePoints = {
                    faceCenter,
                    faceCenter.add(u.scale(0.3)), faceCenter.add(u.scale(-0.3)),
                    faceCenter.add(v.scale(0.3)), faceCenter.add(v.scale(-0.3))
            };

            for (final Vec3 point : facePoints) {
                if (eyesPos.distanceTo(point) > p_Distance)
                    continue;

                Rotation rotation = RotationUtils.calcRotationFromVec3d(playerContext.playerHead(), point, playerContext.playerRotations());
                HitResult res = RayTraceUtils.rayTraceTowards(playerContext.player(), rotation, p_Distance);
                if (res != null && res.getType() == HitResult.Type.BLOCK
                        && ((BlockHitResult) res).getBlockPos().relative(((BlockHitResult) res).getDirection()).equals(pos)) {
                    return rotation;
                }
            }
        }
        return null;
    }

    // Makes the server resend the true state of pos by right-clicking the block below it:
    // handleUseItemOn always ends with block updates for the clicked pos AND the pos across the
    // clicked face, even when the click does nothing. This is the only way to surface ghost air
    // (a local removeBlock whose server-side dig silently failed) - the server never re-sends a
    // block without a change. Main hand must not hold anything placeable.
    public void requestBlockResync(BlockPos pos) {
        BlockPos support = pos.below();
        Vec3 hitVec = new Vec3(support.getX() + 0.5, support.getY() + 1.0, support.getZ() + 0.5);
        playerContext.playerController().processRightClickBlock(playerContext.player(), playerContext.world(), InteractionHand.MAIN_HAND, new BlockHitResult(hitVec, Direction.UP, support, false));
    }

    private PlaceResult clickFace(BlockHitResult blockHitResult, boolean packetSwing, InteractionHand hand) {
        final BlockPos neighbor = blockHitResult.getBlockPos();
        final Block neighborBlock = playerContext.world().getBlockState(neighbor).getBlock();
        final boolean activated = playerContext.world().getBlockState(neighbor).useWithoutItem(playerContext.world(), playerContext.player(), blockHitResult) == InteractionResult.SUCCESS;

        if (blackList.contains(neighborBlock) || shulkerBlockList.contains(neighborBlock) || activated)
        {
            playerContext.player().connection.send(new ServerboundPlayerCommandPacket(playerContext.player(), ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY));
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
                    final BlockPos neighbor = pos.offset(side.getUnitVec3i());

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

    private Vec3 getEyesPos() {
        return new Vec3(playerContext.player().getX(), playerContext.player().getEyeY(), playerContext.player().getZ());
    }

    public boolean calibrationBreakTick(BlockPos pos) {
        if (playerContext.minecraft().gameMode == null) return false;
        Direction face = faceMineTarget(pos);
        instantMineDirection = face;
        instantMineLastBlock = pos;

        IPlayerController controller = playerContext.playerController();
        controller.setHittingBlock(instantMineCalibrationHitting);
        if (controller.hasBrokenBlock()) {
            controller.syncHeldItem();
            controller.clickBlock(pos, face);
            playerContext.player().swing(InteractionHand.MAIN_HAND);
        } else if (controller.onPlayerDamageBlock(pos, face)) {
            playerContext.player().swing(InteractionHand.MAIN_HAND);
        }

        boolean broken = controller.hasBrokenBlock();
        if (broken) {
            try {
                controller.setDestroyDelay(0);
            } catch (Exception ignored) {}
        }
        instantMineCalibrationHitting = !broken;
        controller.setHittingBlock(false);
        return broken;
    }

    public void instantMineTick(BlockPos pos) {
        if (playerContext.minecraft().gameMode == null) return;
        Direction face = faceMineTarget(pos);
        instantMineDirection = face;
        instantMineLastBlock = pos;
        playerContext.player().connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, face));
        playerContext.player().connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        try {
            playerContext.playerController().setDestroyDelay(0);
        } catch (Exception ignored) {}
    }

    private Direction faceMineTarget(BlockPos pos) {
        double reach = playerContext.playerController().getBlockReachDistance();
        Optional<Rotation> rotation = RotationUtils.reachable(playerContext, pos, reach);
        if (rotation.isPresent()) {
            baritone.getLookBehavior().updateTarget(rotation.get(), true);
            HitResult hit = RayTraceUtils.rayTraceTowards(playerContext.player(), rotation.get(), reach);
            if (hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(pos)) {
                return blockHit.getDirection();
            }
        }
        return instantMineDirection != null ? instantMineDirection : Direction.UP;
    }

    public int timer() {
        return timer;
    }
    
    private boolean isAcceptableThrowawayItem(Item item) {
        return settings.acceptableThrowawayItems.value.contains(item);
    }
    
    public int getAcceptableThrowawaySlot() {
        for (Item throwawayItem : settings.acceptableThrowawayItems.value) {
            int slot = getItemSlot(Item.getId(throwawayItem));
            if (slot != -1) {
                return slot;
            }
        }
        return -1;
    }

    public int getThrowawaySlotToToss() {
        for (Item throwawayItem : settings.acceptableThrowawayItems.value) {
            // Paving's product is never a throwaway, whatever the list says; obsidianRoomInventory
            // assumes as much.
            if (paving() && (throwawayItem == Blocks.OBSIDIAN.asItem() || throwawayItem == Blocks.CRYING_OBSIDIAN.asItem())) {
                continue;
            }
            int itemId = Item.getId(throwawayItem);
            for (int i = 0; i < 36; i++) {
                if (i != 8 && Item.getId(playerContext.player().getInventory().items.get(i).getItem()) == itemId) {
                    return i;
                }
            }
        }
        return -1;
    }

    public int getAcceptableThrowawaySlotNoHotbar() {
        for (Item throwawayItem : settings.acceptableThrowawayItems.value) {
            int slot = getItemSlotNoHotbar(Item.getId(throwawayItem));
            if (slot != -1) {
                return slot;
            }
        }
        return -1;
    }

    /** Puts the first acceptableThrowawayItems block we carry onto the hotbar; returns its slot or -1. */
    public int putAcceptableThrowawayHotbar() {
        for (Item throwawayItem : settings.acceptableThrowawayItems.value) {
            int slot = getItemSlotHotbar(Item.getId(throwawayItem));
            if (slot != -1) {
                return slot;
            }
        }
        for (Item throwawayItem : settings.acceptableThrowawayItems.value) {
            int slot = putItemHotbar(Item.getId(throwawayItem));
            if (slot != -1) {
                return slot;
            }
        }
        return -1;
    }
    
    public boolean isInQueue() {
        return inQueue;
    }

    public void enterQueue() {
        if (!inQueue) {
            Helper.HELPER.logDirect("Detected queue, pausing highway builder");
            stateBeforeQueue = currentState.getState();
            inQueue = true;
            transitionTo(HighwayState.InQueue);
            
            if (settings.highwayQueueDisconnect.value) {
                Helper.HELPER.logDirect("Disconnecting due to queue detection");
                playerContext.minecraft().getConnection().getConnection().disconnect(Component.literal("Queue detected - auto disconnect"));
            }
        }
    }
    
    public void exitQueue() {
        if (inQueue) {
            Helper.HELPER.logDirect("Exited queue, resuming highway builder");
            inQueue = false;
            stuckTimer = 0;
            transitionTo(stateBeforeQueue);
            stateBeforeQueue = HighwayState.Nothing;
        }
    }
}
