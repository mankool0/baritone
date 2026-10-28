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
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.VecUtils;
import baritone.behavior.highway.enums.HighwayState;
import baritone.behavior.highway.enums.LocationType;
import baritone.process.BuilderProcess;
import baritone.utils.BrokenBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

/**
 * The highway builder's state machine and what it keeps between ticks. Every transition builds a
 * fresh state object, so whatever has to outlive one lives here instead, in the parts the
 * accessors below hand out; this class itself keeps the machine, its timers and the watchdogs.
 */
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
    static final List<BlockState> blackListBlocks = Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.LAVA.defaultBlockState(), Blocks.FIRE.defaultBlockState(), Blocks.BROWN_MUSHROOM.defaultBlockState(), Blocks.RED_MUSHROOM.defaultBlockState(), Blocks.MAGMA_BLOCK.defaultBlockState(), Blocks.SOUL_SAND.defaultBlockState(), Blocks.SOUL_SOIL.defaultBlockState());
    private final Baritone baritone;
    private final IPlayerContext playerContext;
    private final Settings settings = BaritoneAPI.getSettings();
    private HighwayLayout layout;
    private final HighwayInventory inventory;
    private final ContainerLooting looting;
    private final EnderChestFarm farm;
    private final ShulkerPlacement shulkerPlacement;
    private final ContainerRecovery containerRecovery;
    private final Resupply resupply;
    private final Eating eating;
    private final Threats threats;
    private final RecoveryTracker recovery;
    private final HighwayScanner scanner;
    private final LiquidRemoval liquidRemoval;
    private final EndTracker endTracker;
    private final HighwayWalk walk;
    private final BlockPlacer placer;
    private final ServerSync sync;
    private final HighwayDiagnostics diagnostics;
    private final List<ContextComponent> parts;

    private State currentState;
    private HighwayState previousState = HighwayState.Nothing;

    private boolean invalidBlockFixActive = false;
    private int invalidBlockFixNoPathTicks = 0;
    private BlockPos invalidBlockFixScanStart = null; // stretch the running repair was dispatched for, in build-line coords
    private BlockPos invalidBlockFixScanStartLiq = null;
    private int invalidBlockFixScanDist = 0;
    private final ArrayList<String> invalidBlockFixLastMismatches = new ArrayList<>(); // mismatch snapshot of the last check, to spot progress

    private boolean paused = false;
    private String pauseReason = null;

    private BetterBlockPos originBuild; // where the builder restarts from; Nothing moves it up as the build goes

    private BlockPos placeLoc;
    private LocationType placeLocLine = LocationType.ShulkerEchestInteraction;

    private BetterBlockPos cachedPlayerFeet = null;
    private long brokenAtStuckCheck = 0;

    private int timer = 0;

    /** Our own tick counter: a respawn hands us a fresh player whose tickCount restarts at zero. */
    private long contextTick = 0;

    private int walkBackTimer = 0;
    private int checkBackTimer = 0;
    private int stuckTimer = 0;
    private float cachedHealth = 0.0f;
    private float cachedAbsorption = 0.0f;

    private boolean inQueue = false;
    private HighwayState stateBeforeQueue = HighwayState.Nothing;

    // All states of the two gapple-eat flows and the golden carrot one; used to release eat-side
    // effects when any code path (mob combat, floating fix, stuck handling, ...) yanks the state
    // machine out of them.
    private static final EnumSet<HighwayState> EAT_STATES = EnumSet.of(
            HighwayState.EmergencyGapplePrep, HighwayState.EmergencyGappleEat,
            HighwayState.LiquidRemovalGapplePrep, HighwayState.LiquidRemovalGappleEat,
            HighwayState.GoldenCarrotEatPrep, HighwayState.GoldenCarrotEat
    );

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
            HighwayState.SupplyShulkerPlaceLocPrep,
            HighwayState.EchestMiningPlaceLocPrep,
            HighwayState.EmptyShulkerPlaceLocPrep,
            HighwayState.EnderChestStashPlaceLocPrep,
            HighwayState.LootEnderChestPlaceLocPrep,
            HighwayState.ShulkerSearchPrep
    );

    public HighwayContext(Baritone baritone) {
        this.baritone = baritone;
        this.playerContext = baritone.getPlayerContext();
        this.layout = HighwayLayout.none(baritone, settings);
        this.inventory = new HighwayInventory(this);
        this.looting = new ContainerLooting(this);
        this.farm = new EnderChestFarm(this);
        this.shulkerPlacement = new ShulkerPlacement(this);
        this.containerRecovery = new ContainerRecovery(this);
        this.resupply = new Resupply(this);
        this.eating = new Eating(this);
        this.threats = new Threats(this);
        this.recovery = new RecoveryTracker(this);
        this.scanner = new HighwayScanner(this);
        this.liquidRemoval = new LiquidRemoval(this);
        this.endTracker = new EndTracker(this);
        this.walk = new HighwayWalk(this);
        this.placer = new BlockPlacer(this);
        this.sync = new ServerSync(this);
        this.diagnostics = new HighwayDiagnostics(this);
        this.parts = List.of(inventory, looting, farm, shulkerPlacement, containerRecovery, resupply, eating, threats,
                recovery, scanner, liquidRemoval, endTracker, walk, placer, sync, diagnostics);
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

    public HighwayLayout layout() {
        return layout;
    }

    public HighwayInventory inventory() {
        return inventory;
    }

    public ContainerLooting looting() {
        return looting;
    }

    public EnderChestFarm farm() {
        return farm;
    }

    public ShulkerPlacement shulkerPlacement() {
        return shulkerPlacement;
    }

    public ContainerRecovery containerRecovery() {
        return containerRecovery;
    }

    public Resupply resupply() {
        return resupply;
    }

    public Eating eating() {
        return eating;
    }

    public Threats threats() {
        return threats;
    }

    public RecoveryTracker recovery() {
        return recovery;
    }

    public HighwayScanner scanner() {
        return scanner;
    }

    public LiquidRemoval liquidRemoval() {
        return liquidRemoval;
    }

    public EndTracker endTracker() {
        return endTracker;
    }

    public HighwayWalk walk() {
        return walk;
    }

    public BlockPlacer placer() {
        return placer;
    }

    public ServerSync sync() {
        return sync;
    }

    public HighwayDiagnostics diagnostics() {
        return diagnostics;
    }

    /**
     * Starts a build on {@code layout}: every part forgets what the last build left behind, and the
     * machine starts over from Nothing.
     */
    public void startBuild(HighwayLayout layout) {
        this.layout = layout;
        originBuild = layout.firstStartingPos();
        cachedHealth = playerContext.player().getHealth();
        cachedAbsorption = playerContext.player().getAbsorptionAmount();
        for (ContextComponent part : parts) {
            part.onBuildStart();
        }
        setPaused(false);
        transitionTo(HighwayState.Nothing);
    }

    /** Lets go of everything the running build holds, ahead of a stop or a new build. */
    public void teardown() {
        for (ContextComponent part : parts) {
            part.onTeardown();
        }
        clearInvalidBlockFix();
        transitionTo(HighwayState.Nothing); // onExit: whatever the state we interrupt holds
        layout = layout.withBounds(null, null); // a stopped build has no start or end to clamp to, or to report
        originBuild = null;
    }

    public BetterBlockPos originBuild() {
        return originBuild;
    }

    public void setOriginBuild(BetterBlockPos originBuild) {
        this.originBuild = originBuild;
    }

    public BlockPos placeLoc() {
        return placeLoc;
    }

    public LocationType placeLocLine() {
        return placeLocLine;
    }

    public void setPlaceLoc(BlockPos placeLoc) {
        setPlaceLoc(placeLoc, LocationType.ShulkerEchestInteraction);
    }

    /**
     * Remember a placing spot along with the parallel line it was picked on, so the stand-off spots
     * around it can be stepped along that same line. Under a custom pattern {@link HighwayLayout#highwayDirection}
     * is only a sign carrier, so "one along the highway" is a slice step, not a multiple of it.
     */
    public void setPlaceLoc(BlockPos placeLoc, LocationType line) {
        this.placeLoc = new BlockPos(placeLoc.getX(), placeLoc.getY(), placeLoc.getZ());
        this.placeLocLine = line;
    }

    /**
     * Where to stand to work on {@link #placeLoc}: {@code slices} along the highway from it, on the
     * line it was picked on, and never closer than {@link HighwayLayout#standOffSlices} allows.
     */
    public BetterBlockPos placeLocStand(int slices) {
        return layout.standOffAlongLine(placeLoc, placeLocLine, slices);
    }

    public State currentState() {
        return currentState;
    }

    public void transitionTo(HighwayState nextState) {
        Helper.HELPER.logDebug(currentState + " -> " + nextState);
        // The travel walk belongs to BuildingHighway; never let it outlive the state that drives it,
        // or the next state's pathing runs with our goal still set and we cancel a walk (or a builder)
        // that isn't ours several states later.
        if (currentState != null && currentState.getState() == HighwayState.BuildingHighway && nextState != HighwayState.BuildingHighway) {
            endTracker.stopTravelTowardsEnd();
        }
        // Leaving the eat flow by any path: release the use key and drop the hitResult
        // suppression so they can't leak into (and act during) other states.
        if (currentState != null && EAT_STATES.contains(currentState.getState())
                && !EAT_STATES.contains(nextState)) {
            NetherHighwayBuilderBehavior.suppressHitResult = false;
            if (playerContext.minecraft() != null) {
                eating.releaseUseToEat();
            }
        }
        if (currentState != null && currentState.getState() != nextState) {
            currentState.onExit(this);
        }
        currentState = StateFactory.getState(nextState);
    }

    public HighwayState previousState() {
        return previousState;
    }

    public void setPreviousState(HighwayState state) {
        this.previousState = state;
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
        invalidBlockFixLastMismatches.addAll(scanner.lastMismatches());
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

    public void handle() {
        HighwayState currentStateEnum = currentState.getState();
        boolean inEmergencyEat = currentStateEnum == HighwayState.EmergencyGapplePrep || currentStateEnum == HighwayState.EmergencyGappleEat;
        boolean inCarrotEat = currentStateEnum == HighwayState.GoldenCarrotEatPrep || currentStateEnum == HighwayState.GoldenCarrotEat;
        boolean inCombat = currentStateEnum == HighwayState.MobCombat || currentStateEnum == HighwayState.MobCombatReturn;
        boolean inRecovery = currentStateEnum == HighwayState.FallRecovery;
        boolean inPortalEscape = currentStateEnum == HighwayState.PortalEscape;

        if (inCombat) {
            int swordSlot = inventory.putBestSwordHotbar();
            if (swordSlot != -1 && swordSlot < 9) {
                playerContext.player().getInventory().setSelectedSlot(swordSlot);
            }
        }

        if (!inEmergencyEat && !inCombat && !inRecovery && !inPortalEscape && currentStateEnum != HighwayState.Nothing) {
            java.util.Optional<Entity> mob = threats.findMobTargetingPlayer();
            if (mob.isPresent()) {
                // A carrot eat resumes from its prep: combat leaves the sword selected
                setPreviousState(inCarrotEat ? HighwayState.GoldenCarrotEatPrep : currentStateEnum);
                // A preempted thief hunt resumes at the thief, not at where combat started
                threats.setCombatReturnPos(currentStateEnum == HighwayState.ShulkerThiefHunt ? null : playerContext.playerFeet());
                transitionTo(HighwayState.MobCombat);
                return;
            }
        }
        boolean inLiquidEat = currentStateEnum == HighwayState.LiquidRemovalGapplePrep || currentStateEnum == HighwayState.LiquidRemovalGappleEat;
        boolean asked = eating.gappleAsked();
        if (asked && (inEmergencyEat || inLiquidEat)) {
            eating.forgetAsk();
        }
        if (!inEmergencyEat && !inLiquidEat && !inRecovery && !inPortalEscape && (!inCombat || settings.highwayEmergencyEatDuringCombat.value)) {
            if (currentStateEnum != HighwayState.Nothing) {
                float healthThreshold = eating.gappleEatHealthThreshold();
                int foodThreshold = eating.gappleEatFoodThreshold();
                boolean healthTrigger = healthThreshold > 0 && playerContext.player().getHealth() < healthThreshold;
                boolean foodTrigger = foodThreshold > 0 && playerContext.player().getFoodData().getFoodLevel() <= foodThreshold;
                // Hunger alone is golden carrot work while we have any; low health still wants a gapple
                if (healthTrigger || asked || foodTrigger && !eating.goldenCarrotsFeed(currentStateEnum)) {
                    Helper.HELPER.logDirect((asked ? "Gapple eat asked for" : "Emergency gapple eat triggered") + " (health=" + playerContext.player().getHealth() + ", food=" + playerContext.player().getFoodData().getFoodLevel() + ").");
                    if (asked) {
                        eating.beginAskedEat();
                    }
                    // Out of a carrot eat, back to where that one was going: the gapple leaves its own slot selected
                    eating.setEmergencyEatReturnState(inCarrotEat ? eating.goldenCarrotEatReturnState() : currentStateEnum);
                    baritone.getInputOverrideHandler().clearAllKeys();
                    baritone.getPathingBehavior().cancelEverything();
                    transitionTo(HighwayState.EmergencyGapplePrep);
                    return;
                }
                if (foodTrigger && !inCarrotEat) {
                    Helper.HELPER.logDirect("Emergency golden carrot eat triggered (food=" + playerContext.player().getFoodData().getFoodLevel() + ").");
                    eating.setGoldenCarrotEatReturnState(currentStateEnum);
                    baritone.getInputOverrideHandler().clearAllKeys();
                    baritone.getPathingBehavior().cancelEverything();
                    transitionTo(HighwayState.GoldenCarrotEatPrep);
                    return;
                }
            }
        }

        // Fell off the highway, or got paved over while below it: recover by pathing back to a
        // built spot behind us
        boolean fellFar = recovery.fellOffHighway(currentStateEnum == HighwayState.BuildingHighway);
        if (settings.highwayFallRecovery.value && currentStateEnum == HighwayState.BuildingHighway
                && (fellFar || recovery.trappedUnderRoad())) {
            String why = !fellFar ? "Trapped underneath the highway"
                    : Boolean.TRUE.equals(recovery.descentPlanned()) ? "Stuck below the highway"
                    : "Fell off the highway";
            Helper.HELPER.logDirect(why + " (y=" + playerContext.playerFeet().y + "). Recovering.");
            setPreviousState(currentStateEnum);
            recovery.resetRecovery();
            baritone.getInputOverrideHandler().clearAllKeys();
            baritone.getPathingBehavior().cancelEverything();
            transitionTo(HighwayState.FallRecovery);
            return;
        }

        for (int i = 0; ; i++) {
            State handled = currentState;
            if (i == 0) {
                diagnostics.noteStateTick(handled.getState()); // chained instant states are free, so charge one tick
            }
            handled.handle(this);
            handled.noteHandled();
            // stop() inside a handle leaves us in Nothing with the end cleared: chaining into it
            // restarts the builder unbounded, and it places until the next nhwstop lands
            if (paused || i >= CHAIN_LIMIT || currentState == handled || !INSTANT_STATES.contains(currentState.getState())) {
                break;
            }
        }
    }

    public int timer() {
        return timer;
    }

    public int walkBackTimer() {
        return walkBackTimer;
    }

    public int checkBackTimer() {
        return checkBackTimer;
    }

    public long contextTick() {
        return contextTick;
    }

    public void incrementTimers() {
        timer++;
        walkBackTimer++;
        checkBackTimer++;
        stuckTimer++;
        contextTick++;
        sync.tickContainerSync();
        sync.tickObsidianCount();
        threats.tickThiefHuntCooldown();
        endTracker.tickTravelStallCooldown();

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

    public List<BlockState> blackListBlocks() {
        return blackListBlocks;
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

    public boolean stuckCheck() {
        if (currentState.getState() == HighwayState.InQueue || currentState.getState() == HighwayState.FallRecovery) {
            return false;
        }
        
        if (stuckTimer >= settings.highwayStuckCheckTicks.value) {
            BrokenBlocks broken = baritone.getInputOverrideHandler().getBlockBreakHelper().broken();
            long dug = broken.count() - brokenAtStuckCheck;
            boolean digging = broken.stayedGoneSince(brokenAtStuckCheck, playerContext.world()::getBlockState);
            brokenAtStuckCheck = broken.count();

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

                if (digging) {
                    Helper.HELPER.logDirect("Haven't moved in " + settings.highwayStuckCheckTicks.value + " ticks, but dug " + dug + " blocks - not stuck");
                    cachedPlayerFeet = new BetterBlockPos(playerContext.playerFeet());
                    stuckTimer = 0;
                    return false;
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
                currentState.getState() != HighwayState.LiquidRemovalGapplePrep && currentState.getState() != HighwayState.LiquidRemovalGappleEat &&
                currentState.getState() != HighwayState.EmergencyGapplePrep && currentState.getState() != HighwayState.EmergencyGappleEat &&
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
