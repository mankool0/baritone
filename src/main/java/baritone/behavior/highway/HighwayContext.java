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
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.effects.EnchantmentAttributeEffect;
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
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
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
    private final List<BlockState> blackListBlocks = Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.LAVA.defaultBlockState(), Blocks.FIRE.defaultBlockState(), Blocks.BROWN_MUSHROOM.defaultBlockState(), Blocks.RED_MUSHROOM.defaultBlockState(), Blocks.MAGMA_BLOCK.defaultBlockState(), Blocks.SOUL_SAND.defaultBlockState(), Blocks.SOUL_SOIL.defaultBlockState());
    private Settings settings = BaritoneAPI.getSettings();
    private State currentState;
    private HighwayState previousState = HighwayState.Nothing;
    private HighwayState emergencyEatReturnState = HighwayState.Nothing;
    private Entity currentMobTarget = null;
    private BetterBlockPos combatReturnPos = null;
    private BetterBlockPos recoveryTarget = null;
    private int recoveryExtraBack = 0;
    private Boolean recoverySwimThroughLavaSaved = null; // non-null while we've overridden allowSwimThroughLava for recovery
    private boolean invalidBlockFixActive = false;
    private int invalidBlockFixNoPathTicks = 0;
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
    private boolean refillingEnderChests = false;
    private boolean stashingEnderShulker = false;
    private BlockPos enderChestAccessLoc = null;
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
    private float cachedAbsorption = 0.0f;

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

    public Item instantMineOriginalOffhandItem() {
        return instantMineOriginalOffhandItem;
    }

    public void setInstantMineOriginalOffhandItem(Item instantMineOriginalOffhandItem) {
        this.instantMineOriginalOffhandItem = instantMineOriginalOffhandItem;
    }

    private Item instantMineOriginalOffhandItem;

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
        // Leaving the eat flow by any path: release the use key and drop the hitResult
        // suppression so they can't leak into (and act during) other states.
        if (currentState != null && GAPPLE_EAT_STATES.contains(currentState.getState())
                && !GAPPLE_EAT_STATES.contains(nextState)) {
            NetherHighwayBuilderBehavior.suppressHitResult = false;
            if (playerContext.minecraft() != null) {
                playerContext.minecraft().options.keyUse.setDown(false);
            }
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
        NetherHighwayBuilderBehavior.suppressHitResult = false;
        if (playerContext.minecraft() != null) {
            playerContext.minecraft().options.keyJump.setDown(false);
            playerContext.minecraft().options.keyUse.setDown(false);
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

    public boolean invalidBlockFixActive() {
        return invalidBlockFixActive;
    }

    /** Mark that we've dispatched the builder to clear invalid blocks and (re)start the stall timer. */
    public void startInvalidBlockFix() {
        invalidBlockFixActive = true;
        invalidBlockFixNoPathTicks = 0;
    }

    public void clearInvalidBlockFix() {
        invalidBlockFixActive = false;
        invalidBlockFixNoPathTicks = 0;
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

    public void handle() {
        HighwayState currentStateEnum = currentState.getState();
        boolean inEmergencyEat = currentStateEnum == HighwayState.EmergencyGapplePrep || currentStateEnum == HighwayState.EmergencyGapplePreEat || currentStateEnum == HighwayState.EmergencyGappleEat;
        boolean inCombat = currentStateEnum == HighwayState.MobCombat || currentStateEnum == HighwayState.MobCombatReturn;
        boolean inRecovery = currentStateEnum == HighwayState.FallRecovery;

        if (inCombat) {
            int swordSlot = putBestSwordHotbar();
            if (swordSlot != -1) {
                playerContext.player().getInventory().selected = swordSlot;
            }
        }

        if (!inEmergencyEat && !inCombat && !inRecovery && currentStateEnum != HighwayState.Nothing) {
            java.util.Optional<Entity> mob = findMobTargetingPlayer();
            if (mob.isPresent()) {
                setPreviousState(currentStateEnum);
                setCurrentMobTarget(mob.get());
                setCombatReturnPos(playerContext.playerFeet());
                transitionTo(HighwayState.MobCombat);
                return;
            }
        }
        boolean inLiquidEat = currentStateEnum == HighwayState.LiquidRemovalGapplePrep || currentStateEnum == HighwayState.LiquidRemovalGapplePreEat || currentStateEnum == HighwayState.LiquidRemovalGappleEat;
        if (!inEmergencyEat && !inLiquidEat && !inRecovery && (!inCombat || settings.highwayEmergencyEatDuringCombat.value)) {
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

        // Fell off the highway: recover by pathing back to a built spot behind us
        int highwayFeetY = settings.highwayMainY.value + (paving ? 1 : 0);
        if (settings.highwayFallRecovery.value && currentStateEnum == HighwayState.BuildingHighway
                && playerContext.playerFeet().y <= highwayFeetY - settings.highwayFallDetectThreshold.value) {
            Helper.HELPER.logDirect("Fell off the highway (y=" + playerContext.playerFeet().y + "). Recovering.");
            setPreviousState(currentStateEnum);
            resetRecovery();
            baritone.getInputOverrideHandler().clearAllKeys();
            baritone.getPathingBehavior().cancelEverything();
            transitionTo(HighwayState.FallRecovery);
            return;
        }
        currentState.handle(this);
    }

    public void incrementTimers() {
        timer++;
        walkBackTimer++;
        checkBackTimer++;
        stuckTimer++;

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

    public boolean refillingEnderChests() {
        return refillingEnderChests;
    }

    public void setRefillingEnderChests(boolean refillingEnderChests) {
        this.refillingEnderChests = refillingEnderChests;
    }

    public boolean stashingEnderShulker() {
        return stashingEnderShulker;
    }

    public void setStashingEnderShulker(boolean stashingEnderShulker) {
        this.stashingEnderShulker = stashingEnderShulker;
    }

    public BlockPos enderChestAccessLoc() {
        return enderChestAccessLoc;
    }

    public void setEnderChestAccessLoc(BlockPos enderChestAccessLoc) {
        this.enderChestAccessLoc = enderChestAccessLoc == null
                ? null
                : new BlockPos(enderChestAccessLoc.getX(), enderChestAccessLoc.getY(), enderChestAccessLoc.getZ());
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
        // Mirror of the firstStartingPos clamp above: never allow points past the set end
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
        int itemSlot = getBestSwordSlot();
        if (itemSlot == -1) return -1;
        if (itemSlot >= 9) {
            baritone.getInventoryBehavior().attemptToPutOnHotbar(itemSlot, usefulSlots::contains);
            itemSlot = getBestSwordSlot();
        }
        return itemSlot;
    }

    // Ordered from highest to lowest priority
    private static final List<Item> SWORD_PRIORITY = List.of(
            Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD,
            Items.STONE_SWORD, Items.GOLDEN_SWORD, Items.WOODEN_SWORD
    );

    private int getBestSwordSlot() {
        for (Item swordType : SWORD_PRIORITY) {
            int bestSlot = -1;
            double bestEnchantBonus = -1;
            for (int i = 0; i < 36; i++) {
                ItemStack stack = playerContext.player().getInventory().items.get(i);
                if (Item.getId(stack.getItem()) != Item.getId(swordType)) continue;
                double bonus = getSwordAttackEnchantBonus(stack);
                if (bonus > bestEnchantBonus) {
                    bestEnchantBonus = bonus;
                    bestSlot = i;
                }
            }
            if (bestSlot != -1) return bestSlot;
        }
        return -1;
    }

    private double getSwordAttackEnchantBonus(ItemStack stack) {
        double bonus = 0;
        ItemEnchantments enchantments = stack.getEnchantments();
        for (Holder<Enchantment> enchant : enchantments.keySet()) {
            for (EnchantmentAttributeEffect e : enchant.value().getEffects(EnchantmentEffectComponents.ATTRIBUTES)) {
                if (e.attribute().is(Attributes.ATTACK_DAMAGE.unwrapKey().get())) {
                    bonus += e.amount().calculate(enchantments.getLevel(enchant));
                }
            }
        }
        return bonus;
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
                        int throwawaySlot = getAcceptableThrowawaySlot();
                        if (throwawaySlot == 8) {
                            throwawaySlot = getAcceptableThrowawaySlotNoHotbar();
                        }
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

    /**
     * True once the open container shows any item in its chest/shulker slots, i.e. the server's
     * initial content sync has arrived. False for a genuinely empty container too, so callers
     * should keep a timer fallback rather than waiting on this forever.
     */
    public boolean openContainerHasContents() {
        AbstractContainerMenu menu = playerContext.player().containerMenu;
        if (menu == playerContext.player().inventoryMenu) {
            return false;
        }
        int containerSlots = menu.slots.size() - 36;
        for (int i = 0; i < containerSlots; i++) {
            if (!menu.getSlot(i).getItem().isEmpty()) {
                return true;
            }
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
        // Debug logging to track BlockPos types
        Helper.HELPER.logDirect("placeShulkerBox called with: " + shulkerPlaceLoc + " (class: " + shulkerPlaceLoc.getClass().getSimpleName() + ")");

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

        // Determine placement direction and target position
        Direction placeSide = getBestPlaceSide(shulkerPlaceLoc);
        if (placeSide == null) {
            Helper.HELPER.logDirect("No valid placement side found");
            return prevHighwayState;
        }

        BlockPos neighbor = shulkerPlaceLoc.relative(placeSide);
        Vec3 hitPos = Vec3.atCenterOf(shulkerPlaceLoc).add(
            placeSide.getStepX() * 0.5,
            placeSide.getStepY() * 0.5,
            placeSide.getStepZ() * 0.5
        );

        BlockHitResult blockHitResult = new BlockHitResult(hitPos, placeSide.getOpposite(), neighbor, false);

        // Calculate rotation for placement
        Rotation targetRotation = RotationUtils.calcRotationFromVec3d(
            RayTraceUtils.inferSneakingEyePosition(playerContext.player()),
            hitPos,
            playerContext.playerRotations()
        );

        // Use look behavior to rotate to target, then place
        baritone.getLookBehavior().updateTarget(targetRotation, true);

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
            Helper.HELPER.logDirect("Failed to place shulker at " + shulkerPlaceLoc);
            return prevHighwayState;
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
        Vec3 direction = new Vec3(highwayDirection.getX(), highwayDirection.getY(), highwayDirection.getZ());
        Vec3 origin = new Vec3(eChestEmptyShulkOriginVector.x, eChestEmptyShulkOriginVector.y, eChestEmptyShulkOriginVector.z);
        for (int back = minBack; back <= maxBack; back++) {
            Vec3 curPos = new Vec3(
                    playerContext.playerFeet().getX() + (back * -highwayDirection.getX()),
                    playerContext.playerFeet().getY(),
                    playerContext.playerFeet().getZ() + (back * -highwayDirection.getZ())
            );
            BetterBlockPos candidate = getClosestPoint(origin, direction, curPos, LocationType.SideStorage);
            if (isSideStorageSpotSafe(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean canPlaceBlockAt(BlockPos pos) {
        // Check if position is valid
        if (!playerContext.world().isInWorldBounds(pos)) return false;

        // Check if current block is replaceable
        BlockState currentState = playerContext.world().getBlockState(pos);

        return currentState.canBeReplaced() || currentState.getBlock() instanceof SnowLayerBlock;
    }

    private Direction getBestPlaceSide(BlockPos pos) {
        Vec3 eyePos = playerContext.player().getEyePosition();
        Vec3 blockCenter = Vec3.atCenterOf(pos);
        Vec3 lookVec = blockCenter.subtract(eyePos);

        double bestRelevancy = -Double.MAX_VALUE;
        Direction bestSide = null;

        for (Direction side : Direction.values()) {
            BlockPos neighbor = pos.relative(side);
            BlockState neighborState = playerContext.world().getBlockState(neighbor);

            // Check if neighbor can be placed against
            if (neighborState.isAir() || !neighborState.isFaceSturdy(playerContext.world(), neighbor, side.getOpposite())) {
                continue;
            }

            // Check if neighbor is a fluid
            if (!neighborState.getFluidState().isEmpty()) {
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

                    swapSlot = getAcceptableThrowawaySlot();
                    if (swapSlot == 8) {
                        swapSlot = getAcceptableThrowawaySlotNoHotbar();
                    }
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
                    int throwawaySlot = getAcceptableThrowawaySlot();
                    if (throwawaySlot == 8) {
                        throwawaySlot = getAcceptableThrowawaySlotNoHotbar();
                    }
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

    public int lootEnderChestSlot() {
        int count = 0;
        AbstractContainerMenu curContainer = playerContext.player().containerMenu;
        for (int i = 0; i < 27; i++) {
            if (curContainer.getSlot(i).getItem().getItem() instanceof BlockItem &&
                    ((BlockItem) curContainer.getSlot(i).getItem().getItem()).getBlock() instanceof EnderChestBlock) {
                count += curContainer.getSlot(i).getItem().getCount();

                if (getItemSlot(Item.getId(Items.AIR)) == -1) {
                    // For some reason we have no air slots so we have to throw out some throwaway items
                    int throwawaySlot = getAcceptableThrowawaySlot();
                    if (throwawaySlot == 8) {
                        throwawaySlot = getAcceptableThrowawaySlotNoHotbar();
                    }
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
                    int depletedSlot = getShulkerSlot(ShulkerType.DepletedPickaxe);
                    if (depletedSlot != -1) {
                        playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, playerContext.player());
                        playerContext.playerController().windowClick(curContainer.containerId, depletedSlot < 9 ? depletedSlot + 54 : depletedSlot + 18, 0, ClickType.PICKUP, playerContext.player()); // Have to convert slot id to single chest slot id
                        playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, playerContext.player()); // Put depleted shulker in looted slot
                    } else if (getItemSlot(Item.getId(Items.AIR)) == -1) {
                        // For some reason we have no air slots so we have to throw out some throwaway items
                        int throwawaySlot = getAcceptableThrowawaySlot();
                        if (throwawaySlot == 8) {
                            throwawaySlot = getAcceptableThrowawaySlotNoHotbar();
                        }
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

    public int getHighwayLengthFront() {
        Vec3 direction = new Vec3(highwayDirection.getX(), highwayDirection.getY(), highwayDirection.getZ());
        Vec3 curPosPlayer = new Vec3(playerContext.playerFeet().getX(), playerContext.playerFeet().getY(), playerContext.playerFeet().getZ());
        BlockPos startCheckPos = getClosestPoint(new Vec3(originVector.x, originVector.y, originVector.z), direction, curPosPlayer, LocationType.HighwayBuild);

        int scanLength = 10;
        if (endPos != null) {
            scanLength = Math.min(scanLength, stepsAlongHighway(startCheckPos, endPos) + 1);
        }
        for (int i = 0; i < scanLength; i++) {
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
        for (int i = 1; i < distanceToCheck; i++) {
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
                                // Never should be liquids
                                if (current.getBlock() instanceof LiquidBlock) {
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

        final Vec3 eyesPos = getEyesPos();

        for (final Direction side : Direction.values())
        {
            final BlockPos neighbor = pos.offset(side.getUnitVec3i());
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
                    BlockHitResult blockHitResult = new BlockHitResult(hitVec, side2, neighbor, false);
                    if (clickFace(blockHitResult, packetSwing, hand) == PlaceResult.Placed)
                        return PlaceResult.Placed;
                }
            }
        }
        return PlaceResult.CantPlace;
    }

    public boolean placeAimable(BlockPos pos, float p_Distance) {
        return findPlaceRotation(pos, p_Distance) != null;
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
    
    public int getAcceptableThrowawaySlotNoHotbar() {
        for (Item throwawayItem : settings.acceptableThrowawayItems.value) {
            int slot = getItemSlotNoHotbar(Item.getId(throwawayItem));
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
