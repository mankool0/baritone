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

package baritone.behavior;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.behavior.INetherHighwayBuilderBehavior;
import baritone.api.event.events.RenderEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.pathing.goals.*;
import baritone.api.schematic.CompositeSchematic;
import baritone.api.schematic.FillSchematic;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.WhiteBlackSchematic;
import baritone.api.utils.*;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.process.BuilderProcess;
import baritone.utils.BlockStateInterface;
import baritone.utils.IRenderer;
import baritone.utils.ToolSet;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.awt.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static baritone.pathing.movement.Movement.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP;

public final class NetherHighwayBuilderBehavior extends Behavior implements INetherHighwayBuilderBehavior, IRenderer {

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
    public enum LocationType {
        HighwayBuild,
        ShulkerEchestInteraction,
        SideStorage
    }

    private final ArrayList<Integer> usefulSlots = new ArrayList<>(Arrays.asList(0, 1, 2, 7)); // Don't try to put into these slots
    private boolean paused = false;
    private State currentState;
    private boolean cursorStackNonEmpty = false;
    private final ArrayList<BlockPos> sourceBlocks = new ArrayList<>();
    private BlockPos placeLoc;


    //private final int picksThreshold = 1;
    private int picksToHave = 5;
    //private final int gappleThreshold = 8;
    //private final int gapplesToHave = 48;
    //private final int obsidianThreshold = 32;
    //private final int pickShulksToHave = 1;
    //private final int enderChestShulksToHave = 1;
    //private final int enderChestsToLoot = 64;
    //private final int enderChestsToKeep = 8;
    private boolean enderChestHasPickShulks = true;
    private boolean enderChestHasEnderShulks = true;
    private boolean repeatCheck = false;

    private ShulkerType picksToUse;

    private BetterBlockPos cachedPlayerFeet = null;
    private int startShulkerCount = 0;
    private boolean liquidPathingCanMine = true;
    private final int highwayCheckBackDistance = 32;

    private int timer = 0;
    private int walkBackTimer = 0;
    private int checkBackTimer = 0;
    private int stuckTimer = 0;
    private float cachedHealth = 0.0f;
    //private final double maxSearchDistance = 6;
    private final List<BlockState> approxPlaceable = new ArrayList<>() {};

    private final Lock renderLockLiquid = new ReentrantLock();
    private final Lock renderLockBuilding = new ReentrantLock();
    private final ArrayList<BlockPos> renderBlocksLiquid = new ArrayList<>();
    private final HashMap<BlockPos, Color> renderBlocksBuilding = new HashMap<>();
    private final ArrayList<Rotation> floatingFixReachables = new ArrayList<>();

    private boolean instantMineActivated = false;
    private boolean instantMinePacketCancel = false;
    private BlockPos instantMineLastBlock;
    private Direction instantMineDirection;
    private Item instantMineOriginalOffhandItem;
    private boolean instantMinePlace = true;

    private static final List<Block> blackList = Arrays.asList(Blocks.ENDER_CHEST, Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.CRAFTING_TABLE, Blocks.ANVIL, Blocks.BREWING_STAND, Blocks.HOPPER,
            Blocks.DROPPER, Blocks.DISPENSER, Blocks.OAK_TRAPDOOR, Blocks.SPRUCE_TRAPDOOR, Blocks.BIRCH_TRAPDOOR, Blocks.JUNGLE_TRAPDOOR, Blocks.ACACIA_TRAPDOOR, Blocks.DARK_OAK_TRAPDOOR, Blocks.MANGROVE_TRAPDOOR, Blocks.ENCHANTING_TABLE);
    private static final List<Block> shulkerBlockList = Arrays.asList(Blocks.WHITE_SHULKER_BOX, Blocks.ORANGE_SHULKER_BOX, Blocks.MAGENTA_SHULKER_BOX, Blocks.LIGHT_BLUE_SHULKER_BOX,
            Blocks.YELLOW_SHULKER_BOX, Blocks.LIME_SHULKER_BOX, Blocks.PINK_SHULKER_BOX, Blocks.GRAY_SHULKER_BOX, Blocks.LIGHT_GRAY_SHULKER_BOX, Blocks.CYAN_SHULKER_BOX, Blocks.PURPLE_SHULKER_BOX,
            Blocks.BLUE_SHULKER_BOX, Blocks.BROWN_SHULKER_BOX, Blocks.GREEN_SHULKER_BOX, Blocks.RED_SHULKER_BOX, Blocks.BLACK_SHULKER_BOX, Blocks.SHULKER_BOX);

    private static final List<ItemLike> shulkerItemList = Arrays.asList(Items.WHITE_SHULKER_BOX, Items.ORANGE_SHULKER_BOX, Items.MAGENTA_SHULKER_BOX, Items.LIGHT_BLUE_SHULKER_BOX,
            Items.YELLOW_SHULKER_BOX, Items.LIME_SHULKER_BOX, Items.PINK_SHULKER_BOX, Items.GRAY_SHULKER_BOX, Items.LIGHT_GRAY_SHULKER_BOX, Items.CYAN_SHULKER_BOX, Items.PURPLE_SHULKER_BOX,
            Items.BLUE_SHULKER_BOX, Items.BROWN_SHULKER_BOX, Items.GREEN_SHULKER_BOX, Items.RED_SHULKER_BOX, Items.BLACK_SHULKER_BOX, Items.SHULKER_BOX);

    private final List<BlockState> blackListBlocks = Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.LAVA.defaultBlockState(), Blocks.FIRE.defaultBlockState(), Blocks.BROWN_MUSHROOM.defaultBlockState(), Blocks.RED_MUSHROOM.defaultBlockState(), Blocks.MAGMA_BLOCK.defaultBlockState(), Blocks.SOUL_SAND.defaultBlockState(), Blocks.SOUL_SOIL.defaultBlockState());

    private static final List<ItemLike> validPicksList = Arrays.asList(Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE);

    private BlockPos boatLocation = null;
    private boolean boatHasPassenger = false;

    public enum State {
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
        PlacingPickaxeShulker,
        OpeningPickaxeShulker,
        LootingPickaxeShulker,
        MiningPickaxeShulker,
        CollectingPickaxeShulker,
        InventoryCleaningPickaxeShulker,

        GappleShulkerPlaceLocPrep,
        GoingToPlaceLocGappleShulker,
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
        LootingLootEnderChestPicks,
        LootingLootEnderChestEnderChests,
        LootingLootEnderChestGapples,

        EchestMiningPlaceLocPrep,
        GoingToPlaceLocEnderShulker,
        PlacingEnderShulker,
        OpeningEnderShulker,
        LootingEnderShulker,
        MiningEnderShulker,
        CollectingEnderShulker,
        InventoryCleaningEnderShulker,
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
    }

    private enum HighwayState {
        Air,
        Liquids,
        Blocks,
        Boat
    }

    private enum ShulkerType {
        EnderChest,
        NonSilkPickaxe, // For paving
        //SilkPickaxe,
        AnyPickaxe, // For digging
        Gapple,
        Empty,
        Any
    }

    public NetherHighwayBuilderBehavior(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isBuildingHighwayState() {
        return currentState == State.BuildingHighway;
    }

    @Override
    public void build(int startX, int startZ, Vec3i direct, boolean selfSolve, boolean pave) {
        highwayDirection = direct;
        paving = pave;
        cachedHealth = ctx.player().getHealth();

        if (!paving) {
            // Only digging so any pickaxe works
            picksToUse = ShulkerType.AnyPickaxe;
            picksToHave = settings.highwayPicksToHaveDigging.value;
        } else {
            // If paving then we mine echests and need non-silk picks
            picksToUse = ShulkerType.NonSilkPickaxe;
            picksToHave = settings.highwayPicksToHavePaving.value;
        }

        int highwayWidth = settings.highwayWidth.value;
        int highwayHeight = settings.highwayHeight.value;
        int supportWidth = settings.highwaySupportWidth.value;
        int supportOffset = settings.highwaySupportOffset.value;
        boolean highwayRail = settings.highwayRail.value;
        boolean diag = Math.abs(highwayDirection.getX()) == Math.abs(highwayDirection.getZ()) && Math.abs(highwayDirection.getZ()) == 1;
        int highwayWidthOffset = -((Math.round(highwayWidth / 2.0f)) + 1);
        int highwayWidthLiqOffset = -(Math.round(highwayWidth / 2.0f)) - 1;
        int highwayWidthLiqOffsetRail = -(Math.round(highwayWidth / 2.0f)) - 2;

        ISchematic obsidSchemBot;
        WhiteBlackSchematic topAir;
        WhiteBlackSchematic noLavaBotSides = new WhiteBlackSchematic(1, 1, 1, Collections.singletonList(Blocks.LAVA.defaultBlockState()), Blocks.NETHERRACK.defaultBlockState(), false, true, true);
        WhiteBlackSchematic supportNetherRack;
        WhiteBlackSchematic sideRailSupport;
        ISchematic sideRail;
        if (pave)
            sideRail = new FillSchematic(1, 1, 1, Blocks.OBSIDIAN.defaultBlockState());
        else
            sideRail = new WhiteBlackSchematic(1, 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.OBSIDIAN.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false);

        WhiteBlackSchematic sideRailAir = new WhiteBlackSchematic(1, 2, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false);
        CompositeSchematic fullSchem = new CompositeSchematic(0, 0,0);

        // +X, -X, +X +Z, -X -Z
        if ((highwayDirection.getZ() == 0 && (highwayDirection.getX() == -1 || highwayDirection.getX() == 1)) ||
                (highwayDirection.getX() == 1 && highwayDirection.getZ() == 1) || (highwayDirection.getX() == -1 && highwayDirection.getZ() == -1)) {
            if (selfSolve) {
                originVector = new Vec3(0, 0, highwayWidthOffset);
                if (highwayRail) {
                    liqOriginVector = new Vec3(0, 0, highwayWidthLiqOffsetRail);
                } else {
                    liqOriginVector = new Vec3(0, 0, highwayWidthLiqOffset);
                }
                backPathOriginVector = new Vec3(0, 0, -1);
                eChestEmptyShulkOriginVector = new Vec3(0, 0, highwayWidthLiqOffsetRail);
            } else {
                originVector = new Vec3(startX, 0, startZ - highwayWidthOffset);
                if (highwayRail) {
                    liqOriginVector = new Vec3(startX, 0, startZ - highwayWidthLiqOffsetRail);
                } else {
                    liqOriginVector = new Vec3(startX, 0, startZ - highwayWidthLiqOffset);
                }
                backPathOriginVector = new Vec3(startX, 0, startZ - highwayWidthLiqOffsetRail + 1);
                eChestEmptyShulkOriginVector = new Vec3(startX, 0, startZ - highwayWidthLiqOffsetRail);
            }

            topAir = new WhiteBlackSchematic(1, highwayHeight - 1, highwayWidth, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false);
            if (pave) {
                obsidSchemBot = new FillSchematic(1, 1, highwayWidth, Blocks.OBSIDIAN.defaultBlockState());
                liqOriginVector = liqOriginVector.add(0, 0, 1);
                if (highwayRail) {
                    liqCheckSchem = new WhiteBlackSchematic(1, highwayHeight - 1, highwayWidth + 2, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false);
                } else {
                    liqCheckSchem = new WhiteBlackSchematic(1, highwayHeight - 1, highwayWidth, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false);
                }

                if (diag && highwayRail) {
                    sideRailSupport = new WhiteBlackSchematic(1, 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.LAVA.defaultBlockState(), Blocks.FIRE.defaultBlockState()), Blocks.NETHERRACK.defaultBlockState(), false, true, true);
                    fullSchem.put(sideRailSupport, 0, 1, 0);
                    fullSchem.put(sideRailSupport, 0, 1, highwayWidth + 1);
                }
            } else {
                obsidSchemBot = new WhiteBlackSchematic(1, 1, highwayWidth, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.OBSIDIAN.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false); // Allow only air and obsidian
                supportNetherRack = new WhiteBlackSchematic(1, 1, supportWidth, blackListBlocks, Blocks.NETHERRACK.defaultBlockState(), false, true, true); // Allow everything other than air and lava
                if (highwayRail) {
                    liqCheckSchem = new WhiteBlackSchematic(1, highwayHeight + 1, highwayWidth + 4, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false);
                } else {
                    liqCheckSchem = new WhiteBlackSchematic(1, highwayHeight + 1, highwayWidth + 2, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false);
                }
                if (supportWidth + 2 <= highwayWidth) {
                    fullSchem.put(noLavaBotSides, 0, 0, 1);
                    fullSchem.put(noLavaBotSides, 0, 0, highwayWidth);
                }
                fullSchem.put(supportNetherRack, 0, 0, supportOffset);
            }

            fullSchem.put(obsidSchemBot, 0, 1, 1);
            if (highwayRail) {
                fullSchem.put(sideRail, 0, 2, 0);
                fullSchem.put(sideRail, 0, 2, highwayWidth + 1);
                fullSchem.put(sideRailAir, 0, 3, 0);
                fullSchem.put(sideRailAir, 0, 3, highwayWidth + 1);
            }
            fullSchem.put(topAir, 0, 2, 1);
        }
        // +Z, -Z, +X -Z, -X +Z
        else if ((highwayDirection.getX() == 0 && (highwayDirection.getZ() == -1 || highwayDirection.getZ() == 1)) ||
                (highwayDirection.getX() == 1 && highwayDirection.getZ() == -1) || (highwayDirection.getX() == -1 && highwayDirection.getZ() == 1)) {
            if (selfSolve) {
                originVector = new Vec3(highwayWidthOffset, 0, 0);
                if (highwayRail) {
                    liqOriginVector = new Vec3(highwayWidthLiqOffsetRail, 0, 0);
                } else {
                    liqOriginVector = new Vec3(highwayWidthLiqOffset, 0, 0);
                }
                backPathOriginVector = new Vec3(-1, 0, 0);
                eChestEmptyShulkOriginVector = new Vec3(highwayWidthLiqOffsetRail, 0, 0);
            } else {
                originVector = new Vec3(startX - highwayWidthOffset, 0, startZ);
                if (highwayRail) {
                    liqOriginVector = new Vec3(startX - highwayWidthLiqOffsetRail, 0, startZ);
                } else {
                    liqOriginVector = new Vec3(startX - highwayWidthLiqOffset, 0, startZ);
                }
                backPathOriginVector = new Vec3(startX - highwayWidthLiqOffsetRail + 1, 0, startZ);
                eChestEmptyShulkOriginVector = new Vec3(startX - highwayWidthLiqOffsetRail, 0, startZ);
            }

            topAir = new WhiteBlackSchematic(highwayWidth, highwayHeight - 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false);
            if (pave) {
                obsidSchemBot = new FillSchematic(highwayWidth, 1, 1, Blocks.OBSIDIAN.defaultBlockState());
                liqOriginVector = liqOriginVector.add(1, 0, 0);
                if (highwayRail) {
                    liqCheckSchem = new WhiteBlackSchematic(highwayWidth + 2, highwayHeight - 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false);
                } else {
                    liqCheckSchem = new WhiteBlackSchematic(highwayWidth, highwayHeight - 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false);
                }

                if (diag && highwayRail) {
                    sideRailSupport = new WhiteBlackSchematic(1, 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.LAVA.defaultBlockState(), Blocks.FIRE.defaultBlockState()), Blocks.NETHERRACK.defaultBlockState(), false, true, true);
                    fullSchem.put(sideRailSupport, 0, 1, 0);
                    fullSchem.put(sideRailSupport, highwayWidth + 1, 1, 0);
                }
            } else {
                obsidSchemBot = new WhiteBlackSchematic(highwayWidth, 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.OBSIDIAN.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false); // Allow only air and obsidian
                supportNetherRack = new WhiteBlackSchematic(supportWidth, 1, 1, blackListBlocks, Blocks.NETHERRACK.defaultBlockState(), false, true, true); // Allow everything other than air and lava
                if (highwayRail) {
                    liqCheckSchem = new WhiteBlackSchematic(highwayWidth + 4, highwayHeight + 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false);
                } else {
                    liqCheckSchem = new WhiteBlackSchematic(highwayWidth + 2, highwayHeight + 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false);
                }
                if (supportWidth + 2 <= highwayWidth) {
                    fullSchem.put(noLavaBotSides, 1, 0, 0);
                    fullSchem.put(noLavaBotSides, highwayWidth, 0, 0);
                }

                fullSchem.put(supportNetherRack, supportOffset, 0, 0);
            }

            fullSchem.put(obsidSchemBot, 1, 1, 0);
            if (highwayRail) {
                fullSchem.put(sideRail, 0, 2, 0);
                fullSchem.put(sideRail, highwayWidth + 1, 2, 0);
                fullSchem.put(sideRailAir, 0, 3, 0);
                fullSchem.put(sideRailAir, highwayWidth + 1, 3, 0);
            }

            fullSchem.put(topAir, 1, 2, 0);
        }

        schematic = fullSchem;

        Vec3 origin = new Vec3(originVector.x, originVector.y, originVector.z);
        Vec3 direction = new Vec3(highwayDirection.getX(), highwayDirection.getY(), highwayDirection.getZ());
        Vec3 curPos = new Vec3(ctx.playerFeet().getX(), ctx.playerFeet().getY(), ctx.playerFeet().getZ());
        originBuild = getClosestPoint(origin, direction, curPos, LocationType.HighwayBuild);

        firstStartingPos = new BetterBlockPos(originBuild);

        Helper.HELPER.logDirect("Building from " + originBuild.toString());
        settings.buildRepeat.value = new Vec3i(highwayDirection.getX(), 0, highwayDirection.getZ());
        baritone.getPathingBehavior().cancelEverything();


        enderChestHasPickShulks = true;
        enderChestHasEnderShulks = true;
        repeatCheck = false;
        startShulkerCount = getShulkerCountInventory(ShulkerType.Any);
        paused = false;
        currentState = State.Nothing;
    }

    @Override
    public void stop() {
        Helper.HELPER.logDirect("STOPPING NETHERHIGHWAYBUILDER");
        Helper.HELPER.logDirect("Was at state " + currentState + " before termination");

        paused = true;
        repeatCheck = false;
        currentState = State.Nothing;
        baritone.getPathingBehavior().cancelEverything();
        firstStartingPos = null;
        originBuild = null;
    }

    @Override
    public void printStatus() {
        Helper.HELPER.logDirect("State: " + currentState);
        Helper.HELPER.logDirect("Paused: " + paused);
        Helper.HELPER.logDirect("Timer: " + timer);
        Helper.HELPER.logDirect("startShulkerCount: " + startShulkerCount);
    }

    @Override
    public void onTick(TickEvent event) {
        if (paused || schematic == null || ctx.player() == null || ctx.player().getInventory().isEmpty() || event.getType() == TickEvent.Type.OUT) {
            return;
        }

        timer++;
        walkBackTimer++;
        checkBackTimer++;
        stuckTimer++;

        // Auto totem
        if (settings.highwayAutoTotem.value && currentState == State.BuildingHighway && ctx.player().getOffhandItem().getItem() != Items.TOTEM_OF_UNDYING) {
            int totemSlot = getItemSlot(Item.getId(Items.TOTEM_OF_UNDYING));
            if (totemSlot != -1) {
                swapOffhand(totemSlot);
                timer = 0;
                return;
            }
        }

        if (!ctx.player().containerMenu.getCarried().isEmpty() && ctx.player().containerMenu == ctx.player().inventoryMenu) {
            if (cursorStackNonEmpty && timer >= 20) {
                // We have some item on our cursor for 20 ticks, try to place it somewhere
                timer = 0;


                int emptySlot = getItemSlot(Item.getId(Items.AIR));
                if (emptySlot != -1) {
                    Helper.HELPER.logDirect("Had " + ctx.player().containerMenu.getCarried().getDisplayName() + " on our cursor. Trying to place into slot " + emptySlot);

                    if (emptySlot <= 8) {
                        // Fix slot id if it's a hotbar slot
                        emptySlot += 36;
                    }
                    ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, emptySlot, 0, ClickType.PICKUP, ctx.player());
                    cursorStackNonEmpty = false;
                    return;
                } else {
                    if (Item.getId(ctx.player().containerMenu.getCarried().getItem()) == Item.getId(Blocks.NETHERRACK.asItem())) {
                        // Netherrack on our cursor, we can just throw it out
                        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, -999, 0, ClickType.PICKUP, ctx.player());
                        cursorStackNonEmpty = false;
                        return;
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
                            ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, netherRackSlot, 0, ClickType.PICKUP, ctx.player());
                            ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, -999, 0, ClickType.PICKUP, ctx.player());
                            cursorStackNonEmpty = false;
                            return;
                        }
                    }
                }
            } else if (!cursorStackNonEmpty) {
                cursorStackNonEmpty = true;
                timer = 0;
                return;
            }
            return;
        }

        if (repeatCheck && timer <= 120) {
            return;
        }

        // Stuck check
        if (/*baritone.getBuilderProcess().isActive() && !baritone.getBuilderProcess().isPaused() &&*/ stuckTimer >= settings.highwayStuckCheckTicks.value) {
            if (ctx.player().hasContainerOpen()) {
                ctx.player().closeContainer(); // Close chest gui so we can actually build
                stuckTimer = 0;
                return;
            }

            if (cachedPlayerFeet == null) {
                cachedPlayerFeet = new BetterBlockPos(ctx.playerFeet());
                stuckTimer = 0;
                return;
            }

            if (VecUtils.distanceToCenter(cachedPlayerFeet, ctx.playerFeet().x, ctx.playerFeet().y, ctx.playerFeet().z) < settings.highwayStuckDistance.value) {
                // Check for floating case
                if (ctx.world().getBlockState(ctx.playerFeet().below()).getBlock() instanceof AirBlock) {
                    Helper.HELPER.logDirect("Haven't moved in " + settings.highwayStuckCheckTicks.value + " ticks and are floating. Trying to force clear blocks around us");
                    timer = 0;
                    stuckTimer = 0;
                    currentState = State.FloatingFixPrep;
                    baritone.getInputOverrideHandler().clearAllKeys();
                    baritone.getPathingBehavior().cancelEverything();
                    return;
                }

                Helper.HELPER.logDirect("We haven't moved in " + settings.highwayStuckCheckTicks.value + " ticks. Restarting builder");
                timer = 0;
                stuckTimer = 0;
                if (currentState == State.BuildingHighway) {
                    currentState = State.Nothing;
                }
                ctx.player().connection.getConnection().disconnect(Component.literal("Haven't moved in " + settings.highwayStuckCheckTicks.value + " ticks. Reconnect"));
                //ctx.world().sendQuittingDisconnectingPacket();
                return;
            }

            if (!cachedPlayerFeet.equals(ctx.playerFeet())) {
                cachedPlayerFeet = new BetterBlockPos(ctx.playerFeet());
                stuckTimer = 0;
                return;
            }
        }

        // Health loss check
        if (settings.highwayDcOnHealthLoss.value && ctx.player().getHealth() < cachedHealth &&
                currentState != State.LiquidRemovalGapplePrep && currentState != State.LiquidRemovalGapplePreEat && currentState != State.LiquidRemovalGappleEat) {
            Component dcMsg = Component.literal("Lost " + (cachedHealth - ctx.player().getHealth()) + " health. Reconnect");
            Helper.HELPER.logDirect(dcMsg);
            ctx.player().connection.getConnection().disconnect(dcMsg);
            cachedHealth = ctx.player().getHealth();
            return;
        }
        cachedHealth = ctx.player().getHealth(); // Get new HP value

        // Handle distance to keep from end of highway
        if (settings.highwayEndDistance.value != -1) {
            ctx.minecraft().options.keyUp.setDown(getHighwayLengthFront() >= settings.highwayEndDistance.value && currentState == State.BuildingHighway);
        }

        switch (currentState) {
            case Nothing: {
                timer = 0;
                startHighwayBuild();
                break;
            }

            case BuildingHighway: {
                if (!baritone.getBuilderProcess().isActive()) {
                    Helper.HELPER.logDirect("Restarting builder");
                    currentState = State.Nothing;
                    return;
                }

                if (walkBackTimer > 120 && baritone.getPathingControlManager().mostRecentCommand().isPresent()) {
                    walkBackTimer = 0;
                    if (getFarthestGoalDistance(baritone.getPathingControlManager().mostRecentCommand().get().goal) > settings.highwayMaxLostShulkerSearchDist.value) {
                        Helper.HELPER.logDirect("We are walking way too far. Restarting");
                        currentState = State.Nothing;
                        return;
                    }
                }

                // TODO: Change shulker threshold from 0 to a customizable value
                if (getItemCountInventory(Item.getId(Blocks.OBSIDIAN.asItem())) <= settings.highwayObsidianThreshold.value && paving) {
                    if (getShulkerCountInventory(ShulkerType.EnderChest) == 0) {
                        if (repeatCheck) {
                            if (!enderChestHasEnderShulks) {
                                Helper.HELPER.logDirect("Out of ender chests, refill ender chest and inventory and restart.");
                                baritone.getPathingBehavior().cancelEverything();
                                paused = true;
                                return;
                            }
                            Helper.HELPER.logDirect("Shulker count is under threshold, checking ender chest");
                            currentState = State.LootEnderChestPlaceLocPrep;
                        } else {
                            Helper.HELPER.logDirect("Shulker count is under threshold. Player may still be loading. Waiting 120 ticks");
                            timer = 0;
                            repeatCheck = true;
                        }
                        return;
                        //Helper.HELPER.logDirect("No more ender chests, pausing");
                        //baritone.getPathingBehavior().cancelEverything();
                        //paused = true;
                        //return;
                    }
                    currentState = State.EchestMiningPlaceLocPrep;
                    baritone.getPathingBehavior().cancelEverything();
                    timer = 0;
                    return;
                }

                if (getPickCountInventory() <= settings.highwayPicksThreshold.value) {
                    if (getShulkerCountInventory(picksToUse) == 0) {
                        if (repeatCheck) {
                            if (!enderChestHasPickShulks) {
                                Helper.HELPER.logDirect("Out of picks, refill ender chest and inventory and restart.");
                                baritone.getPathingBehavior().cancelEverything();
                                paused = true;
                                return;
                            }
                            Helper.HELPER.logDirect("Shulker count is under threshold, checking ender chest");
                            currentState = State.LootEnderChestPlaceLocPrep;
                        } else {
                            Helper.HELPER.logDirect("Shulker count is under threshold. Player may still be loading. Waiting 120 ticks");
                            timer = 0;
                            repeatCheck = true;
                        }
                        return;
                        //Helper.HELPER.logDirect("No more pickaxes, pausing");
                        //baritone.getPathingBehavior().cancelEverything();
                        //paused = true;
                        //return;
                    }
                    currentState = State.PickaxeShulkerPlaceLocPrep;
                    baritone.getPathingBehavior().cancelEverything();
                    timer = 0;
                    return;
                }

                if (getItemCountInventory(Item.getId(Items.ENCHANTED_GOLDEN_APPLE)) <= settings.highwayGapplesThreshold.value) {
                    if (getShulkerCountInventory(ShulkerType.Gapple) == 0) {
                        Helper.HELPER.logDirect("No more gapples, pausing");
                        baritone.getPathingBehavior().cancelEverything();
                        paused = true;
                        return;
                    }
                    currentState = State.GappleShulkerPlaceLocPrep;
                    baritone.getPathingBehavior().cancelEverything();
                    timer = 0;
                    return;
                }

                if (baritone.getBuilderProcess().isActive() && baritone.getBuilderProcess().isPaused() && timer >= 360) {
                    timer = 0;
                    currentState = State.Nothing;
                    return;
                }

                if (timer >= 10 && isShulkerOnGround()) {
                    Helper.HELPER.logDirect("Detected shulker on the ground, trying to collect.");
                    currentState = State.ShulkerCollection;
                    baritone.getPathingBehavior().cancelEverything();
                    timer = 0;
                    return;
                }

                if (checkBackTimer >= 10) {
                    checkBackTimer = 0;
                    // Time to check highway for correctness
                    Vec3 direction = new Vec3(highwayDirection.getX(), highwayDirection.getY(), highwayDirection.getZ());

                    //double distToWantedStart = VecUtils.distanceToCenter(ctx.playerFeet(), startCheckPos.getX(), startCheckPos.getY(), startCheckPos.getZ());
                    Vec3 curPosNotOffset = new Vec3(ctx.playerFeet().getX(), ctx.playerFeet().getY(), ctx.playerFeet().getZ());
                    // Fix player feet location for diags so we don't check too far ahead
                    // +X,+Z and -X,-Z
                    if (((highwayDirection.getX() == 1 && highwayDirection.getZ() == 1) || (highwayDirection.getX() == -1 && highwayDirection.getZ() == -1)) /*&&
                            Math.abs(curPosNotOffset.z) >= Math.abs(curPosNotOffset.x)*/) {
                        curPosNotOffset = new Vec3(curPosNotOffset.x, curPosNotOffset.y, curPosNotOffset.x - 4);
                    } else if ((highwayDirection.getX() == 1 && highwayDirection.getZ() == -1) || (highwayDirection.getX() == -1 && highwayDirection.getZ() == 1)) {
                        curPosNotOffset = new Vec3(-curPosNotOffset.z - 5, curPosNotOffset.y, curPosNotOffset.z);
                    }

                    Vec3 curPos = new Vec3(curPosNotOffset.x + (highwayCheckBackDistance * -highwayDirection.getX()), curPosNotOffset.y, curPosNotOffset.z + (highwayCheckBackDistance * -highwayDirection.getZ()));
                    BlockPos startCheckPos = getClosestPoint(new Vec3(originVector.x, originVector.y, originVector.z), direction, curPos, LocationType.HighwayBuild);
                    BlockPos startCheckPosLiq = getClosestPoint(new Vec3(liqOriginVector.x, liqOriginVector.y, liqOriginVector.z), new Vec3(highwayDirection.getX(), highwayDirection.getY(), highwayDirection.getZ()), curPos, LocationType.ShulkerEchestInteraction);


                    BlockPos feetClosestPoint = getClosestPoint(new Vec3(originVector.x, originVector.y, originVector.z), direction, curPosNotOffset, LocationType.HighwayBuild);
                    double distToWantedStart;
                    if ((highwayDirection.getX() == 1 && highwayDirection.getZ() == 1) || (highwayDirection.getX() == -1 && highwayDirection.getZ() == -1)) {
                        distToWantedStart = Math.abs(Math.abs(ctx.playerFeet().getX()) - Math.abs(startCheckPos.getX()));
                    } else if ((highwayDirection.getX() == 1 && highwayDirection.getZ() == -1) || (highwayDirection.getX() == -1 && highwayDirection.getZ() == 1)) {
                        distToWantedStart = Math.abs(Math.abs(ctx.playerFeet().getZ()) - Math.abs(startCheckPos.getZ()));
                    } else {
                        distToWantedStart = VecUtils.distanceToCenter(feetClosestPoint, startCheckPos.getX(), startCheckPos.getY(), startCheckPos.getZ());
                    }

                    //double oldDist = VecUtils.distanceToCenter(ctx.playerFeet(), startCheckPos.getX(), startCheckPos.getY(), startCheckPos.getZ());
                    int tempCheckBackDist = highwayCheckBackDistance;
                    if (distToWantedStart < tempCheckBackDist) {
                        tempCheckBackDist = (int) distToWantedStart;
                        //Helper.HELPER.logDirect("Check back dist temp changed to " + tempCheckBackDist);
                    }

                    HighwayState curState;
                    if (baritone.getBuilderProcess().isPaused()) {
                        curState = isHighwayCorrect(startCheckPos, startCheckPosLiq, tempCheckBackDist + 8, settings.highwayRenderLiquidScanArea.value); // Also checking a few blocks in front of us
                    } else {
                        curState = isHighwayCorrect(startCheckPos, startCheckPosLiq, tempCheckBackDist + 5, settings.highwayRenderLiquidScanArea.value);
                    }
                    if (curState == HighwayState.Liquids) {
                        Helper.HELPER.logDirect("Removing liquids.");
                        currentState = State.LiquidRemovalPrep;
                        liquidPathingCanMine = true;
                        timer = 0;
                        return;
                    }

                    if (curState == HighwayState.Boat) {
                        Helper.HELPER.logDirect("Found a boat, trying to remove blocks around and under it.");
                        currentState = State.BoatRemoval;
                        baritone.getPathingBehavior().cancelEverything();
                        timer = 0;
                        return;
                    }

                    curState = isHighwayCorrect(startCheckPos, startCheckPosLiq, tempCheckBackDist, false); // Don't check front for blocks as we are probably just mining
                    if (curState == HighwayState.Blocks) {
                        Helper.HELPER.logDirect("Fixing invalid blocks.");
                        currentState = State.Nothing;
                        timer = 0;
                        return;
                    }

                    // No case for Air because that's what it should be
                    //return;
                }

                if (timer >= 10 && getShulkerCountInventory(ShulkerType.Any) < startShulkerCount) {
                    // Lost a shulker somewhere :(
                    Helper.HELPER.logDirect("We lost a shulker somewhere. Going back a maximum of " + settings.highwayMaxLostShulkerSearchDist.value + " blocks to look for it.");
                    currentState = State.ShulkerSearchPrep;
                    baritone.getPathingBehavior().cancelEverything();
                    timer = 0;
                    return;
                }

                if (timer >= 10 && getShulkerCountInventory(ShulkerType.Any) > startShulkerCount) {
                    Helper.HELPER.logDirect("We picked up a shulker somewhere, updating startShulkerCount from " + startShulkerCount + " to " + getShulkerCountInventory(ShulkerType.Any));
                    startShulkerCount = getShulkerCountInventory(ShulkerType.Any);
                    timer = 0;
                    return;
                }

                if (timer >= 10 && (ctx.player().isOnFire() || ctx.player().getFoodData().getFoodLevel() <= 16)) {
                    MobEffectInstance fireRest = ctx.player().getEffect(MobEffects.FIRE_RESISTANCE);
                    if (fireRest == null || fireRest.getDuration() < settings.highwayFireRestMinDuration.value || ctx.player().getFoodData().getFoodLevel() <= 16) {
                        Helper.HELPER.logDirect("Eating a gapple.");
                        sourceBlocks.clear(); // Should fix occasional crash after eating gapples
                        currentState = State.LiquidRemovalGapplePrep;
                        baritone.getInputOverrideHandler().clearAllKeys();
                        baritone.getPathingBehavior().cancelEverything();
                        return;
                    }
                }

                break;
            }


            case FloatingFixPrep: {
                floatingFixReachables.clear();
                for (int x = -1; x <= 1; x++) {
                    for (int z = -1; z <= 1; z++) {
                        for (int y = -1; y <= 1; y++) {
                            Optional<Rotation> curIssuePosReachable = RotationUtils.reachable(ctx, ctx.playerFeet().offset(x, y, z), ctx.playerController().getBlockReachDistance());
                            BlockState state = ctx.world().getBlockState(ctx.playerFeet().offset(x, y, z));
                            Block block = state.getBlock();
                            if (block != Blocks.BEDROCK && !(block instanceof LiquidBlock) && !(block instanceof AirBlock) && curIssuePosReachable.isPresent()) {
                                floatingFixReachables.add(curIssuePosReachable.get());
                            }
                        }
                    }
                }
                currentState = State.FloatingFix;
                return;
            }

            case FloatingFix: {
                int pickSlot = putPickaxeHotbar();
                if (pickSlot == -1) {
                    Helper.HELPER.logDirect("Error getting pick slot");
                    currentState = State.Nothing;
                    return;
                }
                ItemStack stack = ctx.player().getInventory().items.get(pickSlot);
                if (validPicksList.contains(stack.getItem())) {
                    ctx.player().getInventory().selected = pickSlot;
                }

                if (!floatingFixReachables.isEmpty()) {
                    baritone.getLookBehavior().updateTarget(floatingFixReachables.get(0), true);
                    floatingFixReachables.remove(0);
                    baritone.getInputOverrideHandler().clearAllKeys();
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                    return;
                }
                currentState = State.Nothing;
                timer = 0;
                break;
            }


            case LiquidRemovalPrep: {
                Vec3 direction = new Vec3(highwayDirection.getX(), highwayDirection.getY(), highwayDirection.getZ());
                Vec3 curPos = new Vec3(ctx.playerFeet().getX() + (7 * -highwayDirection.getX()), ctx.playerFeet().getY(), ctx.playerFeet().getZ() + (7 * -highwayDirection.getZ())); // Go back a bit to clear up our mess
                BlockPos startCheckPos = getClosestPoint(new Vec3(liqOriginVector.x, liqOriginVector.y, liqOriginVector.z), direction, curPos, LocationType.ShulkerEchestInteraction);

                BlockPos liquidPos = findFirstLiquidGround(startCheckPos, 18, false);

                if (liquidPos == null) {
                    currentState = State.Nothing; // Nothing found for some reason, shouldn't happen lol
                    return;
                }

                sourceBlocks.clear();
                ArrayList<BlockPos> flowingBlocks = new ArrayList<>();
                findSourceLiquid(liquidPos.getX(), liquidPos.getY(), liquidPos.getZ(), new ArrayList<>(), sourceBlocks, flowingBlocks);
                sourceBlocks.removeIf(blockPos -> (blockPos.getY() > 123)); // Remove all source blocks above Y 123 as it might be unreachable

                // If sourceBlocks is empty we cleared it in the remove, want to fill in flowing lava at top bedrock level
                if (sourceBlocks.isEmpty()) {
                    for (BlockPos flowingPos : flowingBlocks) {
                        if (flowingPos.getY() == 123) {
                            //Helper.HELPER.logDirect("Lava source Y position too high, ignoring and adding " + flowingPos + " to list");
                            sourceBlocks.add(flowingPos);
                        }
                    }
                }

                //int sizeBeforeRemove = sourceBlocks.size();
                //sourceBlocks.removeIf(this::isLiquidCoveredAllSides); // Remove all liquids that are surrounded by blocks
                //if (sizeBeforeRemove >= 1 && sourceBlocks.size() == 0) {
                // All source blocks are covered aka we will do another removal when we uncover them
                //    currentState = State.Nothing;
                //    return;
                //}

                if (firstStartingPos != null &&
                        ((highwayDirection.getZ() == -1 && liquidPos.getZ() > ctx.playerFeet().getZ()) || // NW, N, NE
                                (highwayDirection.getZ() == 1 && liquidPos.getZ() < ctx.playerFeet().getZ()) || // SE, S, SW
                                (highwayDirection.getX() == -1 && highwayDirection.getZ() == 0 && liquidPos.getX() > ctx.playerFeet().getX()) || // W
                                (highwayDirection.getX() == 1 && highwayDirection.getZ() == 0 && liquidPos.getX() < ctx.playerFeet().getX()))) { // E
                    curPos = new Vec3(ctx.playerFeet().getX() + (7 * highwayDirection.getX()), ctx.playerFeet().getY(), ctx.playerFeet().getZ() + (7 * highwayDirection.getZ()));
                }

                placeLoc = getClosestPoint(new Vec3(backPathOriginVector.x, backPathOriginVector.y, backPathOriginVector.z), new Vec3(highwayDirection.getX(), highwayDirection.getY(), highwayDirection.getZ()), curPos, LocationType.ShulkerEchestInteraction);
                // Get the closest point
                if (!sourceBlocks.isEmpty()) {
                    baritone.getPathingBehavior().cancelEverything();
                    currentState = State.LiquidRemovalPathingBack;
                } else {
                    currentState = State.LiquidRemovalPrepWait;
                }

                timer = 0;
                break;
            }

            case LiquidRemovalPrepWait: {
                if (timer < 20) {
                    return;
                }

                currentState = State.LiquidRemovalPrep;
                timer = 0;
                break;
            }

            case LiquidRemovalPathingBack: {
                MobEffectInstance fireRest = ctx.player().getEffect(MobEffects.FIRE_RESISTANCE);
                if (fireRest != null && fireRest.getDuration() >= settings.highwayFireRestMinDuration.value && ctx.playerFeet().getY() == placeLoc.getY()) {
                    currentState = State.LiquidRemovalPathing;
                    ctx.minecraft().options.keyUse.setDown(false);
                    baritone.getInputOverrideHandler().clearAllKeys();
                    baritone.getPathingBehavior().cancelEverything();
                    return;
                }

                BlockState tempState = ctx.world().getBlockState(ctx.playerFeet());
                if (tempState.getBlock() instanceof LiquidBlock) {
                    Helper.HELPER.logDirect("We are stuck in lava, going directly to gapple eating.");
                    currentState = State.LiquidRemovalGapplePrep;
                }
                if (baritone.getCustomGoalProcess().isActive()) {
                    return; // Wait to get there
                }

                if (ctx.playerFeet().getX() == placeLoc.getX() && ctx.playerFeet().getY() == placeLoc.getY() && ctx.playerFeet().getZ() == placeLoc.getZ()) {
                    // We have arrived
                    currentState = State.LiquidRemovalGapplePrep;
                } else {
                    // Keep trying to get there
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(placeLoc.getX(), placeLoc.getY(), placeLoc.getZ()));
                }
                break;
            }

            case LiquidRemovalGapplePrep: {
                int gappleSlot = putItemHotbar(Item.getId(Items.ENCHANTED_GOLDEN_APPLE));
                if (gappleSlot == -1) {
                    Helper.HELPER.logDirect("Error getting gapple slot");
                    currentState = State.LiquidRemovalPrep;
                    return;
                }

                ItemStack stack = ctx.player().getInventory().items.get(gappleSlot);
                if (Item.getId(stack.getItem()) == Item.getId(Items.ENCHANTED_GOLDEN_APPLE)) {
                    ctx.player().getInventory().selected = gappleSlot;
                }

                currentState = State.LiquidRemovalGapplePreEat;
                timer = 0;
                break;
            }

            case LiquidRemovalGapplePreEat: {
                // Constantiam has some weird issue where you want to eat for half a second, stop and then eat again
                if (timer <= 10) {
                    if (ctx.minecraft().screen == null) {
                        ctx.minecraft().options.keyUse.setDown(true);
                    } else {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    }
                } else {
                    ctx.minecraft().options.keyUse.setDown(false);
                    baritone.getInputOverrideHandler().clearAllKeys();
                    currentState = State.LiquidRemovalGappleEat;
                    timer = 0;
                }

                break;
            }

            case LiquidRemovalGappleEat: {
                MobEffectInstance fireRest = ctx.player().getEffect(MobEffects.FIRE_RESISTANCE);
                if (fireRest != null && fireRest.getDuration() >= settings.highwayFireRestMinDuration.value && ctx.player().getFoodData().getFoodLevel() > 16 /*&& ctx.playerFeet().getY() == placeLoc.getY()*/) {
                    currentState = State.LiquidRemovalPathing;
                    ctx.minecraft().options.keyUse.setDown(false);
                    baritone.getInputOverrideHandler().clearAllKeys();
                    baritone.getPathingBehavior().cancelEverything();
                    return;
                }

                if (timer <= 120) {
                    if (ctx.minecraft().screen == null) {
                        ctx.minecraft().options.keyUse.setDown(true);
                    } else {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    }
                } else {
                    ctx.minecraft().options.keyUse.setDown(false);
                    baritone.getInputOverrideHandler().clearAllKeys();
                    currentState = State.LiquidRemovalGapplePrep; // Check if we have fire resistance now
                    timer = 0;
                }
                break;
            }

            case LiquidRemovalPathing: {
                if (timer < 10) {
                    return;
                }

                if (!sourceBlocks.isEmpty() && getIssueType(sourceBlocks.get(0)) == HighwayState.Blocks) {
                    baritone.getInputOverrideHandler().clearAllKeys();
                    currentState = State.LiquidRemovalPrep;
                    timer = 0;
                    return;
                }

                MobEffectInstance fireRest = ctx.player().getEffect(MobEffects.FIRE_RESISTANCE);
                if (fireRest != null && fireRest.getDuration() < settings.highwayFireRestMinDuration.value) {
                    Helper.HELPER.logDirect("Running out of fire resistance. Restarting liquid clearing.");
                    currentState = State.Nothing;
                    baritone.getInputOverrideHandler().clearAllKeys();
                    baritone.getPathingBehavior().cancelEverything();
                    return;
                }


                if (sourceBlocks.isEmpty()) {
                    currentState = State.Nothing;
                    return;
                }

                boolean supportNeeded = false;
                if (getIssueType(sourceBlocks.get(0).north()) != HighwayState.Blocks &&
                        getIssueType(sourceBlocks.get(0).east()) != HighwayState.Blocks &&
                        getIssueType(sourceBlocks.get(0).south()) != HighwayState.Blocks &&
                        getIssueType(sourceBlocks.get(0).west()) != HighwayState.Blocks &&
                        getIssueType(sourceBlocks.get(0).below()) != HighwayState.Blocks) {
                    // Location to place against are not blocks so lets find the closest surrounding block
                    BlockPos tempSourcePos = closestAirBlockWithSideBlock(sourceBlocks.get(0), 5, true);
                    if (tempSourcePos == null) {
                        Helper.HELPER.logDirect("Error finding support block during lava removal. Restarting.");
                        currentState = State.Nothing;
                        return;
                    }
                    sourceBlocks.clear();
                    sourceBlocks.add(tempSourcePos);
                    supportNeeded = true;
                    Helper.HELPER.logDirect("Can't place around lava, placing support block at " + tempSourcePos);
                    //if (sourceBlocks.isEmpty()) {
                    //    currentState = State.Nothing;
                    //    return;
                    //}
                }
                Optional<Rotation> lavaReachable = Optional.empty();

                // From MovementHelper.attemptToPlaceABlock
                for (Direction side : Direction.values()) {//(int i = 0; i < 5; i++) {
                    BlockPos against1 = sourceBlocks.get(0).offset(side.getNormal()); //sourceBlocks.get(0).offset(HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i]);
                    if (MovementHelper.canPlaceAgainst(ctx, against1)) {
                        double faceX = (sourceBlocks.get(0).getX() + against1.getX() + 1.0D) * 0.5D;
                        double faceY = (sourceBlocks.get(0).getY() + against1.getY() + 0.5D) * 0.5D;
                        double faceZ = (sourceBlocks.get(0).getZ() + against1.getZ() + 1.0D) * 0.5D;
                        Rotation place = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), new Vec3(faceX, faceY, faceZ), ctx.playerRotations());
                        HitResult res = RayTraceUtils.rayTraceTowards(ctx.player(), place, ctx.playerController().getBlockReachDistance(), false);
                        if (res.getType() == HitResult.Type.BLOCK && ((BlockHitResult) res).getBlockPos().equals(against1) && ((BlockHitResult) res).getBlockPos().relative(((BlockHitResult) res).getDirection()).equals(sourceBlocks.get(0))) {
                            lavaReachable = Optional.ofNullable(place);
                            if (supportNeeded) {
                                liquidPathingCanMine = false;
                            }
                            break;
                        }
                    }
                }

                if (lavaReachable.isPresent()) {
                    baritone.getLookBehavior().updateTarget(lavaReachable.get(), true);
                    baritone.getInputOverrideHandler().clearAllKeys();

                    int netherRackSlot = putItemHotbar(Item.getId(Blocks.NETHERRACK.asItem()));
                    if (netherRackSlot == -1) {
                        Helper.HELPER.logDirect("Error getting netherrack slot");
                        currentState = State.Nothing;
                        return;
                    }


                    ItemStack stack = ctx.player().getInventory().items.get(netherRackSlot);
                    if (Item.getId(stack.getItem()) == Item.getId(Blocks.NETHERRACK.asItem())) {
                        ctx.player().getInventory().selected = netherRackSlot;
                    }
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    //currentState = State.LiquidRemovalPrep;
                } else {
                    int pickSlot = putPickaxeHotbar();
                    if (pickSlot == -1) {
                        Helper.HELPER.logDirect("Error getting pick slot");
                        currentState = State.Nothing;
                        return;
                    }
                    ItemStack stack = ctx.player().getInventory().items.get(pickSlot);
                    if (validPicksList.contains(stack.getItem())) {
                        ctx.player().getInventory().selected = pickSlot;
                    }

                    Rotation lavaRot = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), new Vec3(sourceBlocks.get(0).getX(), sourceBlocks.get(0).getY(), sourceBlocks.get(0).getZ()), ctx.playerRotations());
                    //RayTraceResult res = RayTraceUtils.rayTraceTowards(ctx.player(), lavaRot, ctx.playerController().getBlockReachDistance(), false);

                    ArrayList<BlockPos> possibleIssuePosList = new ArrayList<>();
                    for (int x = -1; x <= 1; x++) {
                        for (int z = -1; z <= 1; z++) {
                            BlockPos tempLoc = new BlockPos(ctx.playerFeet().x + x, ctx.playerFeet().y, ctx.playerFeet().z + z);
                            for (int i = 0; i < settings.highwayHeight.value; i++) {
                                possibleIssuePosList.add(tempLoc.above(i));
                            }
                        }
                    }
                    if (!liquidPathingCanMine) {
                        possibleIssuePosList.clear();
                    }

                    ArrayList<BlockPos> placeAtList = new ArrayList<>();
                    //BlockPos placeAt = null;
                    for (int x = -2; x <= 2; x++) {
                        for (int z = -2; z <= 2; z++) {
                            BlockPos tempLoc = new BlockPos(ctx.playerFeet().x + x, ctx.playerFeet().y - 1, ctx.playerFeet().z + z);
                            if (getIssueType(tempLoc) != HighwayState.Blocks) {
                                //placeAt = tempLoc;
                                placeAtList.add(tempLoc);
                            }
                        }
                    }




                    //ArrayList<Rotation> belowFeetReachableList = new ArrayList<>();
                    //Optional<Rotation> belowFeetReachable = Optional.empty();
                    for (BlockPos placeAt : placeAtList) {
                        //if (placeAt != null) {
                        // From MovementHelper.attemptToPlaceABlock
                        for (int i = 0; i < 5; i++) {
                            BlockPos against1 = placeAt.relative(HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i]);
                            if (MovementHelper.canPlaceAgainst(ctx, against1)) {
                                double faceX = (placeAt.getX() + against1.getX() + 1.0D) * 0.5D;
                                double faceY = (placeAt.getY() + against1.getY() + 0.5D) * 0.5D;
                                double faceZ = (placeAt.getZ() + against1.getZ() + 1.0D) * 0.5D;
                                Rotation place = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), new Vec3(faceX, faceY, faceZ), ctx.playerRotations());
                                HitResult res = RayTraceUtils.rayTraceTowards(ctx.player(), place, ctx.playerController().getBlockReachDistance(), false);
                                if (res.getType() == HitResult.Type.BLOCK && ((BlockHitResult) res).getBlockPos().equals(against1) && ((BlockHitResult) res).getBlockPos().relative(((BlockHitResult) res).getDirection()).equals(placeAt)) {
                                    baritone.getLookBehavior().updateTarget(place, true);
                                    baritone.getInputOverrideHandler().clearAllKeys();
                                    int netherRackSlot = putItemHotbar(Item.getId(Blocks.NETHERRACK.asItem()));
                                    if (netherRackSlot == -1) {
                                        Helper.HELPER.logDirect("Error getting netherrack slot");
                                        currentState = State.Nothing;
                                        return;
                                    }
                                    if (Item.getId(ctx.player().getInventory().items.get(netherRackSlot).getItem()) == Item.getId(Blocks.NETHERRACK.asItem())) {
                                        ctx.player().getInventory().selected = netherRackSlot;
                                    }

                                    double lastX = ctx.getPlayerEntity().getXLast();
                                    double lastY = ctx.getPlayerEntity().getYLast();
                                    double lastZ = ctx.getPlayerEntity().getZLast();
                                    final Vec3 pos = new Vec3(lastX + (ctx.player().getX() - lastX) * ctx.minecraft().getTimer().getGameTimeDeltaPartialTick(true),
                                            lastY + (ctx.player().getY() - lastY) * ctx.minecraft().getTimer().getGameTimeDeltaPartialTick(true),
                                            lastZ + (ctx.player().getZ() - lastZ) * ctx.minecraft().getTimer().getGameTimeDeltaPartialTick(true));
                                    BetterBlockPos originPos = new BetterBlockPos(pos.x, pos.y+0.5f, pos.z);
                                    double l_Offset = pos.y - originPos.getY();
                                    if (place(placeAt, (float) ctx.playerController().getBlockReachDistance(), true, l_Offset == -0.5f, InteractionHand.MAIN_HAND) == PlaceResult.Placed) {
                                        timer = 0;
                                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                                        return;
                                    }

                                    //belowFeetReachable = Optional.ofNullable(place);
                                    //belowFeetReachableList.add(place);
                                    //break;
                                }
                            }
                        }
                    }

                    /*
                    if (!belowFeetReachableList.isEmpty()) {
                        baritone.getLookBehavior().updateTarget(belowFeetReachableList.get(0), true);
                        baritone.getInputOverrideHandler().clearAllKeys();

                        int netherRackSlot = putItemHotbar(Item.getId(Blocks.NETHERRACK.asItem()));
                        if (netherRackSlot == -1) {
                            Helper.HELPER.logDirect("Error getting netherrack slot");
                            currentState = State.Nothing;
                            return;
                        }
                        if (Item.getId(ctx.player().getInventory().items.get(netherRackSlot).getItem()) == Item.getId(Blocks.NETHERRACK.asItem())) {
                            ctx.player().getInventory().selected = netherRackSlot;
                        }

                        //TODO: Change this placing to use place method
                        //Helper.HELPER.logDirect("Placing " + placeAt);
                        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                        return;
                    }*/

                    ArrayList<Rotation> possibleIssueReachableList = new ArrayList<>();
                    for (BlockPos curIssuePos : possibleIssuePosList) {
                        Optional<Rotation> curIssuePosReachable = RotationUtils.reachable(ctx, curIssuePos, ctx.playerController().getBlockReachDistance());
                        BlockState state = ctx.world().getBlockState(curIssuePos);
                        Block block = state.getBlock();
                        if (block != Blocks.BEDROCK && !(block instanceof LiquidBlock) && !(block instanceof AirBlock) && curIssuePosReachable.isPresent() && curIssuePos.getY() >= settings.highwayMainY.value) {
                            possibleIssueReachableList.add(curIssuePosReachable.get());
                        }
                    }
                    if (!possibleIssueReachableList.isEmpty()) {
                        baritone.getLookBehavior().updateTarget(possibleIssueReachableList.get(0), true);
                        baritone.getInputOverrideHandler().clearAllKeys();
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                        return;
                    }

                    baritone.getLookBehavior().updateTarget(lavaRot, true);
                    baritone.getInputOverrideHandler().clearAllKeys();

                    outerLoop:
                    for (int x = -1; x <= 1; x++) {
                        for (int z = -1; z <= 1; z++) {
                            BlockPos tempLoc = new BlockPos(ctx.playerFeet().x + x, ctx.playerFeet().y - 1, ctx.playerFeet().z + z);
                            if (getIssueType(tempLoc) != HighwayState.Blocks) {
                                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true); // No blocks under feet to walk to
                                break outerLoop;
                            }
                        }
                    }

                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                }

                //timer = 0;
                break;
            }



            case PickaxeShulkerPlaceLocPrep: {
                Vec3 curPos = new Vec3(ctx.playerFeet().getX() + (7 * -highwayDirection.getX()), ctx.playerFeet().getY(), ctx.playerFeet().getZ() + (7 * -highwayDirection.getZ())); // Go back a bit just in case
                Vec3 direction = new Vec3(highwayDirection.getX(), highwayDirection.getY(), highwayDirection.getZ());

                placeLoc = getClosestPoint(new Vec3(backPathOriginVector.x, backPathOriginVector.y, backPathOriginVector.z), direction, curPos, LocationType.ShulkerEchestInteraction);
                // Get the closest point

                settings.buildRepeat.value = new Vec3i(0, 0, 0);
                currentState = State.GoingToPlaceLocPickaxeShulker;
                break;
            }

            case GoingToPlaceLocPickaxeShulker: {
                if (baritone.getCustomGoalProcess().isActive()) {
                    return; // Wait to get there
                }

                if (getPickCountInventory() >= picksToHave || getShulkerSlot(picksToUse) == -1) {
                    // We have enough picks, or no more pickaxe shulker
                    currentState = State.Nothing;
                    return;
                }

                //if (ctx.playerFeet().getX() == placeLoc.getX() && ctx.playerFeet().getY() == placeLoc.getY() && ctx.playerFeet().getZ() == (placeLoc.getZ() - 1)) {
                if (ctx.playerFeet().equals(placeLoc.offset(highwayDirection.getX(), 0, highwayDirection.getZ()))) {
                    // We have arrived
                    currentState = State.PlacingPickaxeShulker;
                    timer = 0;
                } else {
                    // Keep trying to get there
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(placeLoc.offset(highwayDirection.getX(), 0, highwayDirection.getZ())));
                }

                break;
            }

            case PlacingPickaxeShulker: {
                if (!baritone.getBuilderProcess().isPaused() && baritone.getBuilderProcess().isActive()) {
                    timer = 0;
                    return; // Wait for build to complete
                }

                // Shulker box spot isn't air or shulker, lets fix that
                if (!(ctx.world().getBlockState(placeLoc).getBlock() instanceof AirBlock) && !(ctx.world().getBlockState(placeLoc).getBlock() instanceof ShulkerBoxBlock)) {
                    baritone.getPathingBehavior().cancelEverything();
                    baritone.getBuilderProcess().clearArea(placeLoc, placeLoc);
                    timer = 0;
                    return;
                }


                Optional<Rotation> shulkerReachable = RotationUtils.reachable(ctx, placeLoc,
                        ctx.playerController().getBlockReachDistance());

                Optional<Rotation> underShulkerReachable = RotationUtils.reachable(ctx, placeLoc.below(),
                        ctx.playerController().getBlockReachDistance());


                currentState = placeShulkerBox(shulkerReachable.orElse(null), underShulkerReachable.orElse(null), placeLoc, State.GoingToPlaceLocPickaxeShulker, currentState, State.OpeningPickaxeShulker, picksToUse);

                break;
            }

            case OpeningPickaxeShulker: {
                if (timer < 10) {
                    return;
                }

                Optional<Rotation> shulkerReachable = RotationUtils.reachable(ctx, placeLoc,
                        ctx.playerController().getBlockReachDistance());
                shulkerReachable.ifPresent(rotation -> baritone.getLookBehavior().updateTarget(rotation, true));

                baritone.getInputOverrideHandler().clearAllKeys();
                if (!(ctx.minecraft().screen instanceof ShulkerBoxScreen)) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    timer = 0;
                } else {
                    baritone.getInputOverrideHandler().clearAllKeys();
                    currentState = State.LootingPickaxeShulker;
                }

                break;
            }

            case LootingPickaxeShulker: {
                if (timer < 40) {
                    return;
                }

                if (!(ctx.minecraft().screen instanceof ShulkerBoxScreen)) {
                    currentState = State.OpeningPickaxeShulker;
                    return;
                }

                if (getPickCountInventory() < picksToHave) {
                    int picksLooted = lootPickaxeChestSlot();
                    if (picksLooted > 0) {
                        Helper.HELPER.logDirect("Looted " + picksLooted + " pickaxe");
                    } else {
                        Helper.HELPER.logDirect("Can't loot/empty shulker. Rolling with what we have.");
                        currentState = State.MiningPickaxeShulker;
                        ctx.player().closeContainer();
                    }

                    timer = 0;
                } else {
                    currentState = State.MiningPickaxeShulker;
                    ctx.player().closeContainer();
                }

                break;
            }

            case MiningPickaxeShulker: {
                if (timer < 10) {
                    return;
                }

                if (!baritone.getBuilderProcess().isPaused() && baritone.getBuilderProcess().isActive()) {
                    timer = 0;
                    return; // Wait for build to complete
                }

                baritone.getPathingBehavior().cancelEverything();
                if (!(ctx.world().getBlockState(placeLoc).getBlock() instanceof AirBlock)) {
                    baritone.getBuilderProcess().clearArea(placeLoc, placeLoc);
                    timer = 0;
                    return;
                }

                currentState = State.CollectingPickaxeShulker;

                break;
            }

            case CollectingPickaxeShulker: {
                if (baritone.getCustomGoalProcess().isActive()) {
                    return; // Wait for us to reach the goal
                }

                for (Entity entity : ctx.entities()) {
                    if (entity instanceof ItemEntity) {
                        if (shulkerItemList.contains(((ItemEntity) entity).getItem().getItem())) {
                            if (getItemCountInventory(Item.getId(Items.AIR)) == 0) {
                                // No space for shulker, need to do removal
                                currentState = State.InventoryCleaningPickaxeShulker;
                                timer = 0;
                            }
                            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(new BetterBlockPos(entity.getX(), entity.getY(), entity.getZ())));
                            return;
                        }
                    }
                }

                // No more shulker boxes to find
                currentState = State.Nothing;

                break;
            }

            case InventoryCleaningPickaxeShulker: {
                if (timer < 40) {
                    return;
                }

                int netherRackSlot = getItemSlot(Item.getId(Blocks.NETHERRACK.asItem()));
                if (netherRackSlot == 8) {
                    netherRackSlot = getItemSlotNoHotbar(Item.getId(Blocks.NETHERRACK.asItem()));
                }
                if (netherRackSlot == -1) {
                    return;
                }
                baritone.getLookBehavior().updateTarget(new Rotation(45, 0), true);
                ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, netherRackSlot < 9 ? netherRackSlot + 36 : netherRackSlot, 0, ClickType.PICKUP, ctx.player());
                ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, -999, 0, ClickType.PICKUP, ctx.player());
                currentState = State.CollectingPickaxeShulker;
                break;
            }



            case GappleShulkerPlaceLocPrep: {
                Vec3 curPos = new Vec3(ctx.playerFeet().getX() + (7 * -highwayDirection.getX()), ctx.playerFeet().getY(), ctx.playerFeet().getZ() + (7 * -highwayDirection.getZ())); // Go back a bit just in case
                Vec3 direction = new Vec3(highwayDirection.getX(), highwayDirection.getY(), highwayDirection.getZ());

                placeLoc = getClosestPoint(new Vec3(backPathOriginVector.x, backPathOriginVector.y, backPathOriginVector.z), direction, curPos, LocationType.ShulkerEchestInteraction);
                // Get the closest point

                settings.buildRepeat.value = new Vec3i(0, 0, 0);
                currentState = State.GoingToPlaceLocGappleShulker;
                break;
            }

            case GoingToPlaceLocGappleShulker: {
                if (baritone.getCustomGoalProcess().isActive()) {
                    return; // Wait to get there
                }

                if (getItemCountInventory(Item.getId(Items.ENCHANTED_GOLDEN_APPLE)) >= settings.highwayGapplesToHave.value || getShulkerSlot(ShulkerType.Gapple) == -1) {
                    // We have enough gapples, or no more gapple shulker
                    currentState = State.Nothing;
                    return;
                }

                if (ctx.playerFeet().equals(placeLoc.offset(highwayDirection.getX(), 0, highwayDirection.getZ()))) {
                    // We have arrived
                    currentState = State.PlacingGappleShulker;
                    timer = 0;
                } else {
                    // Keep trying to get there
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(placeLoc.offset(highwayDirection.getX(), 0, highwayDirection.getZ())));
                }

                break;
            }

            case PlacingGappleShulker: {
                if (!baritone.getBuilderProcess().isPaused() && baritone.getBuilderProcess().isActive()) {
                    timer = 0;
                    return; // Wait for build to complete
                }

                // Shulker box spot isn't air or shulker, lets fix that
                if (!(ctx.world().getBlockState(placeLoc).getBlock() instanceof AirBlock) && !(ctx.world().getBlockState(placeLoc).getBlock() instanceof ShulkerBoxBlock)) {
                    baritone.getPathingBehavior().cancelEverything();
                    baritone.getBuilderProcess().clearArea(placeLoc, placeLoc);
                    timer = 0;
                    return;
                }


                Optional<Rotation> shulkerReachable = RotationUtils.reachable(ctx, placeLoc,
                        ctx.playerController().getBlockReachDistance());

                Optional<Rotation> underShulkerReachable = RotationUtils.reachable(ctx, placeLoc.below(),
                        ctx.playerController().getBlockReachDistance());


                currentState = placeShulkerBox(shulkerReachable.orElse(null), underShulkerReachable.orElse(null), placeLoc, State.GoingToPlaceLocGappleShulker, currentState, State.OpeningGappleShulker, ShulkerType.Gapple);

                break;
            }

            case OpeningGappleShulker: {
                if (timer < 10) {
                    return;
                }

                Optional<Rotation> shulkerReachable = RotationUtils.reachable(ctx, placeLoc,
                        ctx.playerController().getBlockReachDistance());
                shulkerReachable.ifPresent(rotation -> baritone.getLookBehavior().updateTarget(rotation, true));

                baritone.getInputOverrideHandler().clearAllKeys();
                if (!(ctx.minecraft().screen instanceof ShulkerBoxScreen)) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    timer = 0;
                } else {
                    baritone.getInputOverrideHandler().clearAllKeys();
                    currentState = State.LootingGappleShulker;
                }

                break;
            }

            case LootingGappleShulker: {
                if (timer < 40) {
                    return;
                }

                if (!(ctx.minecraft().screen instanceof ShulkerBoxScreen)) {
                    currentState = State.OpeningGappleShulker;
                    return;
                }

                if (getItemCountInventory(Item.getId(Items.ENCHANTED_GOLDEN_APPLE)) < settings.highwayGapplesToHave.value) {
                    int gapplesLooted = lootGappleChestSlot();
                    if (gapplesLooted > 0) {
                        Helper.HELPER.logDirect("Looted " + gapplesLooted + " gapples");
                    } else {
                        Helper.HELPER.logDirect("Can't loot/empty shulker. Rolling with what we have.");
                        currentState = State.MiningGappleShulker;
                        ctx.player().closeContainer();
                    }

                    timer = 0;
                } else {
                    currentState = State.MiningGappleShulker;
                    ctx.player().closeContainer();
                }

                break;
            }

            case MiningGappleShulker: {
                if (timer < 10) {
                    return;
                }

                if (!baritone.getBuilderProcess().isPaused() && baritone.getBuilderProcess().isActive()) {
                    timer = 0;
                    return; // Wait for build to complete
                }

                baritone.getPathingBehavior().cancelEverything();
                if (!(ctx.world().getBlockState(placeLoc).getBlock() instanceof AirBlock)) {
                    baritone.getBuilderProcess().clearArea(placeLoc, placeLoc);
                    timer = 0;
                    return;
                }

                currentState = State.CollectingGappleShulker;

                break;
            }

            case CollectingGappleShulker: {
                if (baritone.getCustomGoalProcess().isActive()) {
                    return; // Wait for us to reach the goal
                }

                for (Entity entity : ctx.entities()) {
                    if (entity instanceof ItemEntity) {
                        if (shulkerItemList.contains(((ItemEntity) entity).getItem().getItem())) {
                            if (getItemCountInventory(Item.getId(Items.AIR)) == 0) {
                                // No space for shulker, need to do removal
                                currentState = State.InventoryCleaningGappleShulker;
                                timer = 0;
                            }
                            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(new BetterBlockPos(entity.getX(), entity.getY(), entity.getZ())));
                            return;
                        }
                    }
                }

                // No more shulker boxes to find
                currentState = State.Nothing;

                break;
            }

            case InventoryCleaningGappleShulker: {
                if (timer < 40) {
                    return;
                }

                int netherRackSlot = getItemSlot(Item.getId(Blocks.NETHERRACK.asItem()));
                if (netherRackSlot == 8) {
                    netherRackSlot = getItemSlotNoHotbar(Item.getId(Blocks.NETHERRACK.asItem()));
                }
                if (netherRackSlot == -1) {
                    return;
                }
                baritone.getLookBehavior().updateTarget(new Rotation(45, 0), true);
                ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, netherRackSlot < 9 ? netherRackSlot + 36 : netherRackSlot, 0, ClickType.PICKUP, ctx.player());
                ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, -999, 0, ClickType.PICKUP, ctx.player());
                currentState = State.CollectingGappleShulker;
                break;
            }



            case ShulkerCollection: {
                if (baritone.getCustomGoalProcess().isActive()) {
                    return; // Wait for us to reach the goal
                }

                for (Entity entity : ctx.entities()) {
                    if (entity instanceof ItemEntity) {
                        if (shulkerItemList.contains(((ItemEntity) entity).getItem().getItem())) {
                            if (getItemCountInventory(Item.getId(Items.AIR)) == 0) {
                                // No space for shulker, need to do removal
                                currentState = State.InventoryCleaningShulkerCollection;
                                timer = 0;
                            }
                            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(new BetterBlockPos(entity.getX(), entity.getY(), entity.getZ())));
                            return;
                        }
                    }
                }

                // No more shulker boxes to find
                currentState = State.Nothing;
                break;
            }

            case InventoryCleaningShulkerCollection: {
                if (timer < 40) {
                    return;
                }

                int netherRackSlot = getItemSlot(Item.getId(Blocks.NETHERRACK.asItem()));
                if (netherRackSlot == 8) {
                    netherRackSlot = getItemSlotNoHotbar(Item.getId(Blocks.NETHERRACK.asItem()));
                }
                if (netherRackSlot == -1) {
                    return;
                }
                baritone.getLookBehavior().updateTarget(new Rotation(45, 0), true);
                ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, netherRackSlot < 9 ? netherRackSlot + 36 : netherRackSlot, 0, ClickType.PICKUP, ctx.player());
                ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, -999, 0, ClickType.PICKUP, ctx.player());
                currentState = State.ShulkerCollection;
                break;
            }



            case ShulkerSearchPrep: {
                Vec3 curPos = new Vec3(ctx.playerFeet().getX() + (settings.highwayMaxLostShulkerSearchDist.value * -highwayDirection.getX()), ctx.playerFeet().getY(), ctx.playerFeet().getZ() + (settings.highwayMaxLostShulkerSearchDist.value * -highwayDirection.getZ()));
                Vec3 direction = new Vec3(highwayDirection.getX(), highwayDirection.getY(), highwayDirection.getZ());

                placeLoc = getClosestPoint(new Vec3(backPathOriginVector.x, backPathOriginVector.y, backPathOriginVector.z), direction, curPos, LocationType.ShulkerEchestInteraction);
                // Get the closest point and shift it so it's in the middle of the highway

                settings.buildRepeat.value = new Vec3i(0, 0, 0);
                currentState = State.ShulkerSearchPathing;
                break;
            }

            case ShulkerSearchPathing: {
                if (isShulkerOnGround()) {
                    Helper.HELPER.logDirect("Found a missing shulker, going to collection stage.");
                    baritone.getPathingBehavior().cancelEverything();
                    currentState = State.ShulkerCollection;
                    return;
                }

                if (baritone.getCustomGoalProcess().isActive()) {
                    return; // Wait to get there
                }

                if (ctx.playerFeet().equals(placeLoc)) {
                    // We have arrived and no shulkers found, reset start count and go back to building
                    Helper.HELPER.logDirect("Mission failure. No shulkers found. Going back to building.");
                    startShulkerCount = getShulkerCountInventory(ShulkerType.Any);
                    currentState = State.Nothing;
                    timer = 0;
                } else {
                    // Keep trying to get there
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(placeLoc));
                }

                break;
            }



            case LootEnderChestPlaceLocPrep: {
                if (getItemCountInventory(Item.getId(Blocks.ENDER_CHEST.asItem())) == 0) {
                    Helper.HELPER.logDirect("No ender chests, pausing");
                    baritone.getPathingBehavior().cancelEverything();
                    paused = true;
                    return;
                }

                Vec3 curPos = new Vec3(ctx.playerFeet().getX() + (7 * -highwayDirection.getX()), ctx.playerFeet().getY(), ctx.playerFeet().getZ() + (7 * -highwayDirection.getZ())); // Go back a bit just in case
                Vec3 direction = new Vec3(highwayDirection.getX(), highwayDirection.getY(), highwayDirection.getZ());

                placeLoc = getClosestPoint(new Vec3(eChestEmptyShulkOriginVector.x, eChestEmptyShulkOriginVector.y, eChestEmptyShulkOriginVector.z), direction, curPos, LocationType.SideStorage);

                currentState = State.GoingToLootEnderChestPlaceLoc;
                break;
            }

            case GoingToLootEnderChestPlaceLoc: {
                if (baritone.getCustomGoalProcess().isActive()) {
                    return; // Wait to get there
                }

                if (VecUtils.distanceToCenter(ctx.playerFeet(), placeLoc.getX(), placeLoc.getY(), placeLoc.getZ()) <= (ctx.playerController().getBlockReachDistance() - 1)) {
                    // We have arrived
                    baritone.getPathingBehavior().cancelEverything();
                    currentState = State.PlacingLootEnderChestSupport;
                } else {
                    // Keep trying to get there
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(placeLoc.getX(), placeLoc.getY(), placeLoc.getZ() + 1));
                }

                break;
            }

            case PlacingLootEnderChestSupport: {
                if (!baritone.getBuilderProcess().isPaused() && baritone.getBuilderProcess().isActive()) {
                    return; // Wait for build to complete
                }

                baritone.getPathingBehavior().cancelEverything();
                settings.buildRepeat.value = new Vec3i(0, 0, 0);
                if (ctx.world().getBlockState(placeLoc.below()).getBlock() instanceof AirBlock) {
                    baritone.getBuilderProcess().build("supportBlock", new WhiteBlackSchematic(1, 1, 1, blackListBlocks, Blocks.NETHERRACK.defaultBlockState(), false, false, true), placeLoc.below());
                    return;
                }

                currentState = State.PlacingLootEnderChest;
                break;
            }

            case PlacingLootEnderChest: {
                if (!baritone.getBuilderProcess().isPaused() && baritone.getBuilderProcess().isActive()) {
                    return; // Wait for build to complete
                }

                baritone.getPathingBehavior().cancelEverything();
                settings.buildRepeat.value = new Vec3i(0, 0, 0);

                if (ctx.world().getBlockState(placeLoc).getBlock() instanceof EnderChestBlock && ctx.world().getBlockState(placeLoc.above()).getBlock() instanceof AirBlock) {
                    currentState = State.OpeningLootEnderChest;
                    timer = 0;
                    return;
                }
                if (ctx.world().getBlockState(placeLoc).getBlock() instanceof AirBlock && ctx.world().getBlockState(placeLoc.above()).getBlock() instanceof AirBlock) {
                    Optional<Rotation> eChestLocReachable = RotationUtils.reachable(ctx, placeLoc.below(),
                            ctx.playerController().getBlockReachDistance());
                    if (eChestLocReachable.isEmpty()) {
                        currentState = State.LootEnderChestPlaceLocPrep;
                        timer = 0;
                        return;
                    }
                    baritone.getLookBehavior().updateTarget(eChestLocReachable.get(), true);
                    baritone.getBuilderProcess().build("enderChest", new FillSchematic(1, 1, 1, Blocks.ENDER_CHEST.defaultBlockState()), placeLoc);
                }
                else {
                    WhiteBlackSchematic tempSchem = new WhiteBlackSchematic(1, 2, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false);
                    baritone.getBuilderProcess().build("eChestPrep", tempSchem, placeLoc);
                }
                //return;

                //currentState = State.OpeningLootEnderChest;
                timer = 0;
                break;
            }

            case OpeningLootEnderChest: {
                if (timer < 10) {
                    return;
                }

                Optional<Rotation> enderChestReachable = RotationUtils.reachable(ctx, placeLoc,
                        ctx.playerController().getBlockReachDistance());
                enderChestReachable.ifPresent(rotation -> baritone.getLookBehavior().updateTarget(rotation, true));

                baritone.getInputOverrideHandler().clearAllKeys();
                if (!(ctx.minecraft().screen instanceof ContainerScreen)) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                } else {
                    baritone.getInputOverrideHandler().clearAllKeys();
                    currentState = State.LootingLootEnderChestPicks;
                }

                timer = 0;

                break;
            }

            case LootingLootEnderChestPicks: {
                if (timer < 40) {
                    return;
                }

                if (!(ctx.minecraft().screen instanceof ContainerScreen)) {
                    currentState = State.OpeningLootEnderChest;
                    return;
                }

                if (getShulkerCountInventory(picksToUse) < settings.highwayPickShulksToHave.value) {
                    int picksShulksLooted = lootShulkerChestSlot(picksToUse);
                    if (picksShulksLooted > 0) {
                        Helper.HELPER.logDirect("Looted " + picksShulksLooted + " pickaxe shulker");
                        repeatCheck = false;
                    } else {
                        Helper.HELPER.logDirect("No more pickaxe shulkers. Rolling with what we have.");
                        currentState = State.LootingLootEnderChestEnderChests;
                        enderChestHasPickShulks = false;
                    }

                    //if (getItemCountInventory(Item.getId(Items.AIR)) == 0) {
                    //    Helper.HELPER.logDirect("No space for pickaxe shulkers. Rolling with what we have.");
                    //    currentState = State.LootingLootEnderChestEnderChests;
                    //}

                    timer = 0;
                } else {
                    currentState = State.LootingLootEnderChestEnderChests;
                }

                break;
            }

            case LootingLootEnderChestEnderChests: {
                if (timer < 40) {
                    return;
                }
                if (!(ctx.minecraft().screen instanceof ContainerScreen)) {
                    currentState = State.OpeningLootEnderChest;
                    return;
                }

                if (getShulkerCountInventory(ShulkerType.EnderChest) < settings.highwayEnderChestShulksToHave.value) {
                    int enderShulksLooted = lootShulkerChestSlot(ShulkerType.EnderChest);
                    if (enderShulksLooted > 0) {
                        Helper.HELPER.logDirect("Looted " + enderShulksLooted + " ender chest shulker");
                        repeatCheck = false;
                    } else {
                        Helper.HELPER.logDirect("No more ender chest shulkers. Rolling with what we have.");
                        currentState = State.LootingLootEnderChestGapples;
                        enderChestHasEnderShulks = false;
                        ctx.player().closeContainer();
                    }

                    //if (getItemCountInventory(Item.getId(Items.AIR)) == 0) {
                    //    Helper.HELPER.logDirect("No space for ender chest shulkers. Rolling with what we have.");
                    //    currentState = State.LootingLootEnderChestGapples;
                    //    ctx.player().closeContainer();
                    //}

                    timer = 0;
                } else {
                    currentState = State.LootingLootEnderChestGapples;
                    ctx.player().closeContainer();
                }

                break;
            }

            case LootingLootEnderChestGapples: {
                // TODO: Finish this
                currentState = State.Nothing;
                break;
            }



            case EchestMiningPlaceLocPrep: {
                Vec3 curPos = new Vec3(ctx.playerFeet().getX() + (7 * -highwayDirection.getX()), ctx.playerFeet().getY(), ctx.playerFeet().getZ() + (7 * -highwayDirection.getZ())); // Go back a bit just in case
                Vec3 direction = new Vec3(highwayDirection.getX(), highwayDirection.getY(), highwayDirection.getZ());

                placeLoc = getClosestPoint(new Vec3(backPathOriginVector.x, backPathOriginVector.y, backPathOriginVector.z), direction, curPos, LocationType.ShulkerEchestInteraction);
                // Get the closest point

                settings.buildRepeat.value = new Vec3i(0, 0, 0);
                currentState = State.GoingToPlaceLocEnderShulker;
                break;
            }

            case GoingToPlaceLocEnderShulker: {
                if (baritone.getCustomGoalProcess().isActive()) {
                    return; // Wait to get there
                }

                if (ctx.playerFeet().getX() == placeLoc.getX() && ctx.playerFeet().getY() == placeLoc.getY() && ctx.playerFeet().getZ() == (placeLoc.getZ() - 2)) {
                    // We have arrived
                    currentState = State.PlacingEnderShulker;
                } else {
                    // Keep trying to get there
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(placeLoc.getX(), placeLoc.getY(), placeLoc.getZ() - 2));
                }

                break;
            }

            case PlacingEnderShulker: {
                if (!baritone.getBuilderProcess().isPaused() && baritone.getBuilderProcess().isActive()) {
                    timer = 0;
                    return; // Wait for build to complete
                }

                // No shulker in inventory and not placed
                if (getShulkerSlot(ShulkerType.EnderChest) == -1 && !(ctx.world().getBlockState(placeLoc).getBlock() instanceof ShulkerBoxBlock)) {
                    Helper.HELPER.logDirect("Error getting shulker slot at PlacingEnderShulker. Restarting.");
                    currentState = State.Nothing;
                    return;
                }

                // Shulker box spot isn't air or shulker, lets fix that
                if (!(ctx.world().getBlockState(placeLoc).getBlock() instanceof AirBlock) && !(ctx.world().getBlockState(placeLoc).getBlock() instanceof ShulkerBoxBlock)) {
                    baritone.getPathingBehavior().cancelEverything();
                    baritone.getBuilderProcess().clearArea(placeLoc, placeLoc);
                    timer = 0;
                    return;
                }

                Optional<Rotation> shulkerReachable = RotationUtils.reachable(ctx, placeLoc,
                        ctx.playerController().getBlockReachDistance());

                Optional<Rotation> underShulkerReachable = RotationUtils.reachable(ctx, placeLoc.below(),
                        ctx.playerController().getBlockReachDistance());


                currentState = placeShulkerBox(shulkerReachable.orElse(null), underShulkerReachable.orElse(null), placeLoc, State.GoingToPlaceLocEnderShulker, currentState, State.OpeningEnderShulker, ShulkerType.EnderChest);

                break;
            }

            case OpeningEnderShulker: {
                if (timer < 10) {
                    return;
                }

                Optional<Rotation> shulkerReachable = RotationUtils.reachable(ctx, placeLoc,
                        ctx.playerController().getBlockReachDistance());
                shulkerReachable.ifPresent(rotation -> baritone.getLookBehavior().updateTarget(rotation, true));

                if (ctx.world().getBlockState(placeLoc).getBlock() instanceof ShulkerBoxBlock && shulkerReachable.isEmpty()) {
                    Helper.HELPER.logDirect("Shulker has been placed, but can't be reached, pathing closer.");
                    currentState = State.GoingToPlaceLocEnderShulker;
                    timer = 0;
                    return;
                }

                baritone.getInputOverrideHandler().clearAllKeys();
                if (!(ctx.minecraft().screen instanceof ShulkerBoxScreen)) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    timer = 0;
                } else {
                    baritone.getInputOverrideHandler().clearAllKeys();
                    currentState = State.LootingEnderShulker;
                }

                break;
            }

            case LootingEnderShulker: {
                if (timer < 40) {
                    return;
                }

                if (!(ctx.minecraft().screen instanceof ShulkerBoxScreen)) {
                    currentState = State.OpeningEnderShulker;
                    return;
                }

                if (getItemCountInventory(Item.getId(Blocks.ENDER_CHEST.asItem())) < settings.highwayEnderChestsToLoot.value) {
                    int enderChestsLooted = lootEnderChestSlot();
                    if (enderChestsLooted > 0) {
                        Helper.HELPER.logDirect("Looted " + enderChestsLooted + " ender chests");
                    } else {
                        Helper.HELPER.logDirect("No more ender chests. Rolling with what we have.");
                        currentState = State.MiningEnderShulker;
                        ctx.player().closeContainer();
                    }

                    //if (getItemCountInventory(Item.getId(Items.AIR)) == 0) {
                    //    Helper.HELPER.logDirect("No space for ender chests. Rolling with what we have.");
                    //    currentState = HighwayBuilderBehavior.State.MiningEnderShulker;
                    //    ctx.player().closeContainer();
                    //}

                    timer = 0;
                } else {
                    currentState = State.MiningEnderShulker;
                    ctx.player().closeContainer();
                }

                break;
            }

            case MiningEnderShulker: {
                if (timer < 20) {
                    return;
                }

                if (!baritone.getBuilderProcess().isPaused() && baritone.getBuilderProcess().isActive()) {
                    timer = 0;
                    return; // Wait for build to complete
                }

                baritone.getPathingBehavior().cancelEverything();
                if (!(ctx.world().getBlockState(placeLoc).getBlock() instanceof AirBlock)) {
                    baritone.getBuilderProcess().clearArea(placeLoc, placeLoc);
                    timer = 0;
                    return;
                }

                currentState = State.CollectingEnderShulker;

                break;
            }

            case CollectingEnderShulker: {
                if (baritone.getCustomGoalProcess().isActive()) {
                    return; // Wait for us to reach the goal
                }

                for (Entity entity : ctx.entities()) {
                    if (entity instanceof ItemEntity) {
                        if (shulkerItemList.contains(((ItemEntity) entity).getItem().getItem())) {
                            if (getItemCountInventory(Item.getId(Items.AIR)) == 0) {
                                // No space for shulker, need to do removal
                                currentState = State.InventoryCleaningEnderShulker;
                                timer = 0;
                            }
                            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(new BetterBlockPos(entity.getX(), entity.getY(), entity.getZ())));
                            return;
                        }
                    }
                }

                // No more shulker boxes to find
                currentState = State.GoingToPlaceLocEnderChest;

                break;
            }

            case InventoryCleaningEnderShulker: {
                if (timer < 40) {
                    return;
                }

                int netherRackSlot = getItemSlot(Item.getId(Blocks.NETHERRACK.asItem()));
                if (netherRackSlot == 8) {
                    netherRackSlot = getItemSlotNoHotbar(Item.getId(Blocks.NETHERRACK.asItem()));
                }
                if (netherRackSlot == -1) {
                    return;
                }
                baritone.getLookBehavior().updateTarget(new Rotation(45, 0), true);
                ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, netherRackSlot < 9 ? netherRackSlot + 36 : netherRackSlot, 0, ClickType.PICKUP, ctx.player());
                ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, -999, 0, ClickType.PICKUP, ctx.player());
                currentState = State.CollectingEnderShulker;
                break;
            }

            case GoingToPlaceLocEnderChest: {
                if (baritone.getCustomGoalProcess().isActive()) {
                    return; // Wait for us to reach the goal
                }

                if (ctx.playerFeet().getX() == placeLoc.getX() && ctx.playerFeet().getY() == placeLoc.getY() && ctx.playerFeet().getZ() == (placeLoc.getZ() - 2)) {
                    // We have arrived
                    baritone.getPathingBehavior().cancelEverything();
                    settings.buildRepeat.value = new Vec3i(0, 0, 0);
                    timer = 0;
                    instantMineOriginalOffhandItem = ctx.player().getOffhandItem().getItem();
                    currentState = State.FarmingEnderChestPrepEchest;
                } else {
                    // Keep trying to get there
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(placeLoc.getX(), placeLoc.getY(), placeLoc.getZ() - 2));
                }

                break;
            }

            case FarmingEnderChestPrepEchest: {
                if (timer < 10) {
                    return;
                }

                if (ctx.minecraft().screen instanceof ContainerScreen) {
                    // Close container screen if it somehow didn't close
                    ctx.player().closeContainer();
                    timer = 0;
                    return;
                }

                Item origItem = ctx.player().getOffhandItem().getItem();
                if (!(origItem instanceof BlockItem) || !(((BlockItem) origItem).getBlock().equals(Blocks.ENDER_CHEST))) {
                    int eChestSlot = getLargestItemSlot(Item.getId(Blocks.ENDER_CHEST.asItem()));
                    swapOffhand(eChestSlot);
                }

                currentState = State.FarmingEnderChestPrepPick;
                timer = 0;
                break;
            }

            case FarmingEnderChestPrepPick: {
                if (timer < 10) {
                    return;
                }

                int pickSlot = putPickaxeHotbar();
                if (pickSlot == -1) {
                    Helper.HELPER.logDirect("Error getting pick slot");
                    currentState = State.Nothing;
                    return;
                }
                ItemStack stack = ctx.player().getInventory().items.get(pickSlot);
                if (validPicksList.contains(stack.getItem())) {
                    ctx.player().getInventory().selected = pickSlot;
                }

                // TODO: Debug and confirm this works
                if (!(ctx.world().getBlockState(placeLoc).getBlock() instanceof AirBlock)) {
                    setTarget(placeLoc);
                    instantMineActivated = true;
                }

                currentState = State.FarmingEnderChest;
                timer = 0;
                break;
            }

            case FarmingEnderChest: {
                //if (timer < 1) {
                //    return;
                //}


                //baritone.getInputOverrideHandler().clearAllKeys();
                if (timer > 120) {
                    Optional<Rotation> eChestReachable = RotationUtils.reachable(ctx, placeLoc,
                            ctx.playerController().getBlockReachDistance());
                    eChestReachable.ifPresent(rotation -> baritone.getLookBehavior().updateTarget(rotation, true));
                    setTarget(placeLoc);
                    timer = 0;
                }

                int pickSlot = putPickaxeHotbar();
                if (ctx.player().getInventory().selected != pickSlot) {
                    currentState = State.FarmingEnderChestPrepEchest;
                    timer = 0;
                    return;
                }


                Item origItem = ctx.player().getOffhandItem().getItem();
                if ((getItemCountInventory(Item.getId(Blocks.ENDER_CHEST.asItem())) + ctx.player().getOffhandItem().getCount()) <= settings.highwayEnderChestsToKeep.value) {
                    baritone.getInputOverrideHandler().clearAllKeys();
                    instantMinePlace = true;
                    instantMineActivated = false;
                    currentState = State.FarmingEnderChestSwapBack;
                    timer = 0;
                    return;
                }
                else if (!(origItem instanceof BlockItem) || !(((BlockItem) origItem).getBlock().equals(Blocks.ENDER_CHEST))) {
                    currentState = State.FarmingEnderChestPrepEchest;
                    timer = 0;
                    return;
                }

                // Force look at location
                if (ctx.world().getBlockState(placeLoc).getBlock() instanceof AirBlock) {
                    Optional<Rotation> shulkerReachable = RotationUtils.reachable(ctx, placeLoc.below(),
                            ctx.playerController().getBlockReachDistance());
                    shulkerReachable.ifPresent(rotation -> baritone.getLookBehavior().updateTarget(rotation, true));
                }


                if (instantMinePlace) {
                    double lastX = ctx.getPlayerEntity().getXLast();
                    double lastY = ctx.getPlayerEntity().getYLast();
                    double lastZ = ctx.getPlayerEntity().getZLast();
                    final Vec3 pos = new Vec3(lastX + (ctx.player().getX() - lastX) * ctx.minecraft().getTimer().getGameTimeDeltaPartialTick(true),
                            lastY + (ctx.player().getY() - lastY) * ctx.minecraft().getTimer().getGameTimeDeltaPartialTick(true),
                            lastZ + (ctx.player().getZ() - lastZ) * ctx.minecraft().getTimer().getGameTimeDeltaPartialTick(true));
                    BlockPos originPos = new BetterBlockPos(pos.x, pos.y+0.5f, pos.z);
                    double l_Offset = pos.y - originPos.getY();
                    PlaceResult l_Place = place(placeLoc, 5.0f, false, l_Offset == -0.5f, InteractionHand.OFF_HAND);

                    if (l_Place != PlaceResult.Placed)
                        return;
                    instantMinePlace = false;
                    timer = 0;
                    return;
                }


                if (!instantMineActivated) {
                    currentState = State.FarmingEnderChestPrepPick;
                }
                if (instantMineLastBlock != null) {
                    if (validPicksList.contains(ctx.player().getItemInHand(InteractionHand.MAIN_HAND).getItem())) {
                        ctx.player().connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                                instantMineLastBlock, instantMineDirection));
                    }
                }

                try {
                    ctx.playerController().setDestroyDelay(0);
                } catch (Exception ignored) {}

                instantMinePlace = true;
                break;
            }

            case FarmingEnderChestSwapBack: {
                if (timer < 10) {
                    return;
                }
                Item curItem = ctx.player().getOffhandItem().getItem();
                if (!curItem.equals(instantMineOriginalOffhandItem)) {
                    int origItemSlot = getItemSlot(Item.getId(instantMineOriginalOffhandItem));
                    swapOffhand(origItemSlot);
                    timer = 0;
                    return;
                }
                timer = 0;
                currentState = State.FarmingEnderChestClear;
                break;
            }

            case FarmingEnderChestClear: {
                if (timer < 10) {
                    return;
                }

                if (!baritone.getBuilderProcess().isPaused() && baritone.getBuilderProcess().isActive()) {
                    timer = 0;
                    return; // Wait for build to complete
                }

                baritone.getPathingBehavior().cancelEverything();

                if (!(ctx.world().getBlockState(placeLoc).getBlock() instanceof AirBlock)) {
                    baritone.getBuilderProcess().clearArea(placeLoc, placeLoc);
                    timer = 0;
                    return;
                }

                timer = 0;
                currentState = State.CollectingObsidian;

                break;
            }

            case CollectingObsidian: {
                if (baritone.getCustomGoalProcess().isActive()) {
                    return; // Wait for us to reach the goal
                }

                for (Entity entity : ctx.entities()) {
                    if (entity instanceof ItemEntity) {
                        if (((ItemEntity) entity).getItem().getItem() instanceof BlockItem &&
                                ((BlockItem) ((ItemEntity) entity).getItem().getItem()).getBlock() == Blocks.OBSIDIAN) {
                            double obsidDistance = VecUtils.distanceToCenter(ctx.playerFeet(), (int) entity.getX(), (int) entity.getY(), (int) entity.getZ());
                            if (obsidDistance <= settings.highwayObsidianMaxSearchDist.value) {
                                if (getItemCountInventory(Item.getId(Items.AIR)) == 0) {
                                    // No space for obsid, need to do removal
                                    currentState = State.InventoryCleaningObsidian;
                                    timer = 0;
                                }
                                baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(new BetterBlockPos(entity.getX(), entity.getY(), entity.getZ())));
                                return;
                            } else {
                                Helper.HELPER.logDirect("Ignoring found obsidian " + obsidDistance + " blocks away. Max search distance is " + settings.highwayObsidianMaxSearchDist.value + " blocks");
                            }
                        }
                    }
                }

                // No more obsid to find
                currentState = State.EmptyShulkerPlaceLocPrep;
                timer = 0;
                break;
            }

            case InventoryCleaningObsidian: {
                if (timer < 40) {
                    return;
                }

                int netherRackSlot = getItemSlot(Item.getId(Blocks.NETHERRACK.asItem()));
                if (netherRackSlot == 8) {
                    netherRackSlot = getItemSlotNoHotbar(Item.getId(Blocks.NETHERRACK.asItem()));
                }
                if (netherRackSlot == -1) {
                    currentState = State.CollectingObsidian;
                    return;
                }
                baritone.getLookBehavior().updateTarget(new Rotation(45, 0), true);
                ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, netherRackSlot < 9 ? netherRackSlot + 36 : netherRackSlot, 0, ClickType.PICKUP, ctx.player());
                ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, -999, 0, ClickType.PICKUP, ctx.player());
                currentState = State.CollectingObsidian;
                timer = 0;
                break;
            }



            case EmptyShulkerPlaceLocPrep: {
                if (getShulkerSlot(ShulkerType.Empty) == -1) {
                    currentState = State.Nothing;
                    return;
                }

                Vec3 curPos = new Vec3(ctx.playerFeet().getX() + (7 * -highwayDirection.getX()), ctx.playerFeet().getY(), ctx.playerFeet().getZ() + (7 * -highwayDirection.getZ())); // Go back a bit just in case
                Vec3 direction = new Vec3(highwayDirection.getX(), highwayDirection.getY(), highwayDirection.getZ());

                placeLoc = getClosestPoint(new Vec3(eChestEmptyShulkOriginVector.x, eChestEmptyShulkOriginVector.y, eChestEmptyShulkOriginVector.z), direction, curPos, LocationType.SideStorage);

                currentState = State.GoingToEmptyShulkerPlaceLoc;
                break;
            }

            case GoingToEmptyShulkerPlaceLoc: {
                if (baritone.getCustomGoalProcess().isActive()) {
                    return; // Wait to get there
                }

                if (VecUtils.distanceToCenter(ctx.playerFeet(), placeLoc.getX(), placeLoc.getY(), placeLoc.getZ()) <= (ctx.playerController().getBlockReachDistance() - 1)) {
                    // We have arrived
                    baritone.getPathingBehavior().cancelEverything();
                    currentState = State.PlacingEmptyShulkerSupport;
                } else {
                    // Keep trying to get there
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(placeLoc.getX(), placeLoc.getY(), placeLoc.getZ() + 1));
                }

                break;
            }

            case PlacingEmptyShulkerSupport: {
                if (!baritone.getBuilderProcess().isPaused() && baritone.getBuilderProcess().isActive()) {
                    return; // Wait for build to complete
                }

                if (getShulkerSlot(ShulkerType.Empty) == -1) {
                    currentState = State.Nothing;
                    return;
                }

                baritone.getPathingBehavior().cancelEverything();
                settings.buildRepeat.value = new Vec3i(0, 0, 0);
                if (ctx.world().getBlockState(placeLoc.below()).getBlock() instanceof AirBlock) {
                    baritone.getBuilderProcess().build("supportBlock", new WhiteBlackSchematic(1, 1, 1, blackListBlocks, Blocks.NETHERRACK.defaultBlockState(), false, false, true), placeLoc.below());
                    return;
                }

                currentState = State.PlacingEmptyShulker;
                break;
            }

            case PlacingEmptyShulker: {
                if (timer < 20) {
                    return;
                }

                if (!baritone.getBuilderProcess().isPaused() && baritone.getBuilderProcess().isActive()) {
                    timer = 0;
                    return; // Wait for build to complete
                }

                baritone.getPathingBehavior().cancelEverything();
                // Shulker box spot isn't air or shulker, lets fix that
                if (!(ctx.world().getBlockState(placeLoc).getBlock() instanceof AirBlock) && !(ctx.world().getBlockState(placeLoc).getBlock() instanceof ShulkerBoxBlock)) {
                    baritone.getPathingBehavior().cancelEverything();
                    baritone.getBuilderProcess().clearArea(placeLoc, placeLoc.above());
                    timer = 0;
                    return;
                }


                Optional<Rotation> shulkerReachable = RotationUtils.reachable(ctx, placeLoc,
                        ctx.playerController().getBlockReachDistance());

                Optional<Rotation> underShulkerReachable = RotationUtils.reachable(ctx, placeLoc.below(),
                        ctx.playerController().getBlockReachDistance());

                currentState = placeShulkerBox(shulkerReachable.orElse(null), underShulkerReachable.orElse(null), placeLoc, State.GoingToEmptyShulkerPlaceLoc, currentState, State.Nothing, ShulkerType.Empty);
                if (currentState == State.Nothing) {
                    Helper.HELPER.logDirect("Lowering startShulkerCount from " + startShulkerCount + " to " + --startShulkerCount);
                }
                //else if (getShulkerSlot(ShulkerType.Empty) == -1) {
                // Shulker has either been placed, or we dropped it
                //    Helper.HELPER.logDirect("Lowering startShulkerCount from " + startShulkerCount + " to " + --startShulkerCount);
                //}

                if (shulkerReachable.isEmpty() && underShulkerReachable.isEmpty()) {
                    // None are reachable so walk back to location
                    currentState = State.GoingToEmptyShulkerPlaceLoc;
                    timer = 0;
                }

                break;
            }



            case BoatRemoval: {
                if (timer < 10) {
                    return;
                }

                if (boatLocation == null) {
                    Helper.HELPER.logDirect("Boat location is non-existent, restarting builder");
                    currentState = State.Nothing;
                    return;
                }

                boolean done;
                if (boatHasPassenger) {
                    //Helper.HELPER.logDirect("Boat has a passenger, mining deeper");
                    done = baritone.getBuilderProcess().checkNoEntityCollision(new AABB(boatLocation), ctx.player()) && baritone.getBuilderProcess().checkNoEntityCollision(new AABB(boatLocation.below()), ctx.player());
                } else {
                    done = baritone.getBuilderProcess().checkNoEntityCollision(new AABB(boatLocation), ctx.player());
                }

                // Check if boat is still there
                if (done) {
                    Helper.HELPER.logDirect("Boat seems to be gone");
                    baritone.getPathingBehavior().cancelEverything();
                    boatLocation = null;
                    currentState = State.Nothing;
                    return;
                }

                settings.buildRepeat.value = new Vec3i(0, 0, 0);
                if (!baritone.getBuilderProcess().isPaused() && baritone.getBuilderProcess().isActive()) {
                    return; // Wait for build to complete
                }

                int depth = boatHasPassenger ? 3 : 1;
                //int yOffset = boatHasPassenger ? -4 : -2;
                //FillSchematic toClear = new FillSchematic(4, depth, 4, Blocks.AIR.defaultBlockState());
                baritone.getBuilderProcess().clearArea(boatLocation.offset(2, 1, 2), boatLocation.offset(-2, -depth, -2));

                // Check if boat is still there
                //if (!baritone.getBuilderProcess().checkNoEntityCollision(new AABB(boatLocation), ctx.player())) {
                //baritone.getBuilderProcess().build("boatClearing", toClear, boatLocation.add(-1, yOffset, -3));
                //return;
                //}


                break;
            }
        }
    }

    /* Find the farthest distance baritone will travel with the current goal */
    private double getFarthestGoalDistance(Goal goal) {
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
                blockPos = new BlockPos(((GoalXZ) curGoal).getX(), (int) ctx.player().position().y, ((GoalXZ) curGoal).getZ());
            }
            else {
                continue;
            }

            vecPos = new Vec3(blockPos.getX(), blockPos.getY(), blockPos.getZ());
            dist = vecPos.distanceToSqr(ctx.player().position());
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

    private void swapOffhand(int slot) {
        ctx.playerController().windowClick(0, 45, 0, ClickType.PICKUP, ctx.player());
        ctx.playerController().windowClick(0, slot < 9 ? slot + 36 : slot, 0, ClickType.PICKUP, ctx.player());
        ctx.playerController().windowClick(0, 45, 0, ClickType.PICKUP, ctx.player());
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
            if (!ctx.world().getBlockState(neighbour).canBeReplaced())
            {
                return true;
            }
        }
        return false;
    }

    private enum PlaceResult
    {
        NotReplaceable,
        Neighbors,
        CantPlace,
        Placed
    }

    private PlaceResult place(BlockPos pos, float p_Distance, boolean p_Rotate, boolean p_UseSlabRule, InteractionHand hand) {
        return place(pos, p_Distance, p_Rotate, p_UseSlabRule, false, hand);
    }

    private PlaceResult place(BlockPos pos, float p_Distance, boolean p_Rotate, boolean p_UseSlabRule, boolean packetSwing, InteractionHand hand) {
        BlockState l_State = ctx.world().getBlockState(pos);

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

        final Vec3 eyesPos = new Vec3(ctx.player().getX(), ctx.player().getEyeY(), ctx.player().getZ());

        for (final Direction side : Direction.values())
        {
            final BlockPos neighbor = pos.offset(side.getNormal());
            final Direction side2 = side.getOpposite();

            if (!ctx.world().getBlockState(neighbor).getFluidState().isEmpty())
                continue;

            VoxelShape collisionShape = ctx.world().getBlockState(neighbor).getCollisionShape(ctx.world(), neighbor);
            boolean hasCollision = collisionShape != Shapes.empty();
            if (hasCollision)
            {
                final Vec3 hitVec = new Vec3(neighbor.getX(), neighbor.getY(), neighbor.getZ()).add(0.5, 0.5, 0.5).add(new Vec3(side2.getStepX(), side2.getStepY(), side2.getStepZ()).scale(0.5));
                if (eyesPos.distanceTo(hitVec) <= p_Distance)
                {
                    final Block neighborBlock = ctx.world().getBlockState(neighbor).getBlock();

                    BlockHitResult blockHitResult = new BlockHitResult(hitVec, side2, neighbor, false);
                    final boolean activated = ctx.world().getBlockState(neighbor).useWithoutItem(ctx.world(), ctx.player(), blockHitResult) == InteractionResult.SUCCESS;

                    if (blackList.contains(neighborBlock) || shulkerBlockList.contains(neighborBlock) || activated)
                    {
                        ctx.player().connection.send(new ServerboundPlayerCommandPacket(ctx.player(), ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY));
                    }
                    if (p_Rotate)
                    {
                        faceVectorPacketInstant(hitVec);
                    }
                    InteractionResult l_Result2 = ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), hand, blockHitResult);

                    if (l_Result2 == InteractionResult.SUCCESS)
                    {
                        if (packetSwing)
                            ctx.player().connection.send(new ServerboundSwingPacket(hand));
                        else
                            ctx.player().swing(hand);
                        if (activated)
                        {
                            ctx.player().connection.send(new ServerboundPlayerCommandPacket(ctx.player(), ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
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

        BlockState l_State = ctx.world().getBlockState(pos);

        if (l_State.getBlock() instanceof AirBlock)
        {
            final BlockPos[] l_Blocks =
                    { pos.north(), pos.south(), pos.east(), pos.west(), pos.above(), pos.below() };

            for (BlockPos l_Pos : l_Blocks)
            {
                BlockState l_State2 = ctx.world().getBlockState(l_Pos);

                if (l_State2.getBlock() instanceof AirBlock)
                    continue;

                for (final Direction side : Direction.values())
                {
                    final BlockPos neighbor = pos.offset(side.getNormal());

                    boolean l_IsWater = ctx.world().getBlockState(neighbor).getBlock() == Blocks.WATER;

                    // TODO: make sure this works
                    VoxelShape collisionShape = ctx.world().getBlockState(neighbor).getCollisionShape(ctx.world(), neighbor);
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
                { ctx.player().getYRot() + Mth.wrapDegrees(yaw - ctx.player().getYRot()),
                        ctx.player().getXRot() + Mth.wrapDegrees(pitch - ctx.player().getXRot()) };
    }

    private Vec3 getEyesPos() {
        return new Vec3(ctx.player().getX(), ctx.player().getEyeY(), ctx.player().getZ());
    }

    private void faceVectorPacketInstant(Vec3 vec) {
        float[] rotations = getLegitRotations(vec);

        ctx.player().connection.send(new ServerboundMovePlayerPacket.Rot(rotations[0], rotations[1], ctx.player().onGround()));
    }

    private void setTarget(BlockPos pos) {
        instantMinePacketCancel = false;
        ctx.player().connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                pos, Direction.DOWN));
        instantMinePacketCancel = true;
        ctx.player().connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                pos, Direction.DOWN));
        instantMineDirection = Direction.DOWN;
        instantMineLastBlock = pos;
    }

    private void startHighwayBuild() {
        Helper.HELPER.logDirect("Starting highway build");

        settings.buildRepeat.value = new Vec3i(highwayDirection.getX(), 0, highwayDirection.getZ());

        Vec3 origin = new Vec3(originVector.x, originVector.y, originVector.z);
        Vec3 direction = new Vec3(highwayDirection.getX(), highwayDirection.getY(), highwayDirection.getZ());
        Vec3 curPos = new Vec3(ctx.playerFeet().getX() + (highwayCheckBackDistance * -highwayDirection.getX()), ctx.playerFeet().getY(), ctx.playerFeet().getZ() + (highwayCheckBackDistance * -highwayDirection.getZ())); // Go back a bit to clear up our mess
        originBuild = getClosestPoint(origin, direction, curPos, LocationType.HighwayBuild);


        baritone.getPathingBehavior().cancelEverything();
        baritone.getBuilderProcess().build("netherHighway", schematic, originBuild);
        currentState = State.BuildingHighway;
    }

    private BetterBlockPos getClosestPoint(Vec3 origin, Vec3 direction, Vec3 point, LocationType locType) {
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

    private int getLargestItemSlot(int itemId) {
        int largestSlot = -1;
        int largestCount = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = ctx.player().getInventory().items.get(i);
            if (Item.getId(stack.getItem()) == itemId && stack.getCount() > largestCount) {
                largestSlot = i;
                largestCount = stack.getCount();
            }
        }

        return largestSlot;
    }

    private int getItemSlot(int itemId) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = ctx.player().getInventory().items.get(i);
            if (Item.getId(stack.getItem()) == itemId) {
                return i;
            }
        }

        return -1;
    }

    private int getItemSlotNoHotbar(int itemId) {
        for (int i = 9; i < 36; i++) {
            ItemStack stack = ctx.player().getInventory().items.get(i);
            if (Item.getId(stack.getItem()) == itemId) {
                return i;
            }
        }

        return -1;
    }

    private int putItemHotbar(int itemId) {
        int itemSlot = getItemSlot(itemId);
        if (itemSlot >= 9) {
            baritone.getInventoryBehavior().attemptToPutOnHotbar(itemSlot, usefulSlots::contains);
            itemSlot = getItemSlot(itemId);
        }

        return itemSlot;
    }

    private int putPickaxeHotbar() {
        int itemSlot = getPickaxeSlot();
        if (itemSlot >= 9) {
            baritone.getInventoryBehavior().attemptToPutOnHotbar(itemSlot, usefulSlots::contains);
            itemSlot = getPickaxeSlot();
        }

        return itemSlot;
    }

    private int getPickaxeSlot() {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = ctx.player().getInventory().items.get(i);
            if (stack.getItem() instanceof PickaxeItem) {
                if (settings.itemSaver.value && (stack.getDamageValue() + settings.itemSaverThreshold.value) >= stack.getMaxDamage() && stack.getMaxDamage() > 1) {
                    continue;
                }
                return i;
            }
        }

        return -1;
    }

    private ArrayList<BlockPos> highwayObsidToPlace() {
        ArrayList<BlockPos> toPlace = new ArrayList<>();

        Vec3 direction = new Vec3(highwayDirection.getX(), highwayDirection.getY(), highwayDirection.getZ());
        Vec3 curPlayerPos = new Vec3(ctx.playerFeet().getX() + (-highwayDirection.getX()), ctx.playerFeet().getY(), ctx.playerFeet().getZ() + (-highwayDirection.getZ()));
        BlockPos startCheckPos = getClosestPoint(new Vec3(originVector.x, originVector.y, originVector.z), direction, curPlayerPos, LocationType.HighwayBuild);
        int distanceToCheckAhead = 5;
        for (int i = 1; i < distanceToCheckAhead; i++) {
            BlockPos curPos = startCheckPos.offset(i * highwayDirection.getX(), 0, i * highwayDirection.getZ());
            for (int y = 0; y < schematic.heightY(); y++) {
                for (int z = 0; z < schematic.lengthZ(); z++) {
                    for (int x = 0; x < schematic.widthX(); x++) {
                        int blockX = x + curPos.getX();
                        int blockY = y + curPos.getY();
                        int blockZ = z + curPos.getZ();
                        BlockState current = ctx.world().getBlockState(new BlockPos(blockX, blockY, blockZ));

                        if (!schematic.inSchematic(x, y, z, current)) {
                            continue;
                        }

                        if (baritone.bsi.worldContainsLoadedChunk(blockX, blockZ)) { // check if its in render distance, not if its in cache
                            // we can directly observe this block, it is in render distance

                            ISchematic ourSchem = schematic.getSchematic(x, y, z, current).schematic;
                            if (ourSchem instanceof WhiteBlackSchematic && ((WhiteBlackSchematic) ourSchem).isValidIfUnder() &&
                                    MovementHelper.isBlockNormalCube(ctx.world().getBlockState(new BlockPos(blockX, blockY + 1, blockZ)))) {
                                continue;
                            }

                            BlockState desiredState = schematic.desiredState(x, y, z, current, this.approxPlaceable);
                            if (!desiredState.equals(current)) {
                                // If liquids we have nothing to place so bot can remove the liquids
                                if (current.getBlock() instanceof LiquidBlock) {
                                    return new ArrayList<>();
                                } else if (desiredState.is(Blocks.OBSIDIAN)) {
                                    toPlace.add(new BlockPos(blockX, blockY, blockZ));
                                }
                            }
                        }

                    }
                }
            }
        }
        return toPlace;
    }

    private int getHighwayLengthFront() {
        // TODO: Clean this up
        Vec3 direction = new Vec3(highwayDirection.getX(), highwayDirection.getY(), highwayDirection.getZ());
        Vec3 curPosNotOffset = new Vec3(ctx.playerFeet().getX(), ctx.playerFeet().getY(), ctx.playerFeet().getZ());
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
                        BlockState current = ctx.world().getBlockState(new BlockPos(blockX, blockY, blockZ));

                        if (!schematic.inSchematic(x, y, z, current)) {
                            continue;
                        }
                        if (baritone.bsi.worldContainsLoadedChunk(blockX, blockZ)) { // check if its in render distance, not if its in cache
                            // we can directly observe this block, it is in render distance

                            ISchematic ourSchem = schematic.getSchematic(x, y, z, current).schematic;
                            if (ourSchem instanceof WhiteBlackSchematic && ((WhiteBlackSchematic) ourSchem).isValidIfUnder() &&
                                    MovementHelper.isBlockNormalCube(ctx.world().getBlockState(new BlockPos(blockX, blockY + 1, blockZ)))) {
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


    private HighwayState isHighwayCorrect(BlockPos startPos, BlockPos startPosLiq, int distanceToCheck, boolean renderLiquidScan) {
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
                        BlockState current = ctx.world().getBlockState(new BlockPos(blockX, blockY, blockZ));

                        if (!schematic.inSchematic(x, y, z, current)) {
                            continue;
                        }
                        if (i == 1) {
                            BlockState desiredState = schematic.desiredState(x, y, z, current, this.approxPlaceable);
                            if (desiredState.getBlock() instanceof AirBlock) {
                                renderBlocksBuilding.put(new BlockPos(blockX, blockY, blockZ), Color.CYAN);
                            } else if (desiredState.getBlock().equals(Blocks.NETHERRACK)) {
                                renderBlocksBuilding.put(new BlockPos(blockX, blockY, blockZ), Color.RED);
                            } else if (desiredState.getBlock().equals(Blocks.OBSIDIAN)) {
                                renderBlocksBuilding.put(new BlockPos(blockX, blockY, blockZ), Color.BLACK);
                            } else {
                                renderBlocksBuilding.put(new BlockPos(blockX, blockY, blockZ), Color.GREEN);
                            }
                        }
                        if (baritone.bsi.worldContainsLoadedChunk(blockX, blockZ)) { // check if its in render distance, not if its in cache
                            // we can directly observe this block, it is in render distance

                            ISchematic ourSchem = schematic.getSchematic(x, y, z, current).schematic;
                            if (ourSchem instanceof WhiteBlackSchematic && ((WhiteBlackSchematic) ourSchem).isValidIfUnder() &&
                                    MovementHelper.isBlockNormalCube(ctx.world().getBlockState(new BlockPos(blockX, blockY + 1, blockZ)))) {
                                continue;
                            }

                            if (!schematic.desiredState(x, y, z, current, this.approxPlaceable).equals(current)) {
                                if (!baritone.getBuilderProcess().checkNoEntityCollision(new AABB(new BlockPos(blockX, blockY, blockZ)), ctx.player())) {
                                    List<Entity> entityList = ctx.world().getEntities(null, new AABB(new BlockPos(blockX, blockY, blockZ)));
                                    for (Entity entity : entityList) {
                                        if (entity instanceof Boat || entity.isVehicle()) {
                                            // can't do boats lol
                                            boatHasPassenger = entity.isVehicle();
                                            boatLocation = new BlockPos(blockX, blockY, blockZ);
                                            return HighwayState.Boat;
                                        }
                                    }
                                }

                                // Never should be liquids
                                if (current.getBlock() instanceof LiquidBlock) {
                                    renderLockBuilding.unlock();
                                    return HighwayState.Liquids;
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
            return HighwayState.Liquids;
        }

        if (foundBlocks) {
            return HighwayState.Blocks;
        }
        return HighwayState.Air;
    }

    @Override
    public void onRenderPass(RenderEvent event) {
        Entity player = ctx.minecraft().getCameraEntity();
        if (player == null) {
            return;
        }

        if (settings.highwayRenderLiquidScanArea.value) {
            renderLockLiquid.lock();
            //PathRenderer.drawManySelectionBoxes(ctx.minecraft().getRenderViewEntity(), renderBlocks, Color.CYAN);
            BufferBuilder bufferBuilder = IRenderer.startLines(Color.BLUE, 2, settings.renderSelectionBoxesIgnoreDepth.value);

            //BlockPos blockpos = movingObjectPositionIn.getBlockPos();
            BlockStateInterface bsi = new BlockStateInterface(BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext()); // TODO this assumes same dimension between primary baritone and render view? is this safe?

            renderBlocksLiquid.forEach(pos -> {
                BlockState state = bsi.get0(pos);
                VoxelShape shape;
                AABB toDraw;

                if (state.getBlock() instanceof AirBlock) {
                    shape = Blocks.DIRT.defaultBlockState().getShape(player.level(), pos);
                } else {
                    shape = state.getShape(player.level(), pos);
                }
                if (!shape.isEmpty()) {
                    AABB bounds = shape.bounds();
                    toDraw = new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + bounds.getXsize(), pos.getY() + bounds.getYsize(), pos.getZ() + bounds.getZsize());
                    IRenderer.emitAABB(bufferBuilder, event.getModelViewStack(), toDraw, .002D);
                }
            });

            IRenderer.endLines(bufferBuilder, settings.renderSelectionBoxesIgnoreDepth.value);

            renderLockLiquid.unlock();
        }

        if (settings.highwayRenderBuildingArea.value) {
            renderLockBuilding.lock();
            BlockStateInterface bsi = new BlockStateInterface(BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext());
            renderBlocksBuilding.forEach((pos, color) -> {
                BufferBuilder bufferBuilder = IRenderer.startLines(color, 2, settings.renderSelectionBoxesIgnoreDepth.value);

                BlockState state = bsi.get0(pos);
                VoxelShape shape;
                AABB toDraw;

                if (state.getBlock() instanceof AirBlock) {
                    shape = Blocks.DIRT.defaultBlockState().getShape(player.level(), pos);
                } else {
                    shape = state.getShape(player.level(), pos);
                }
                if (!shape.isEmpty()) {
                    AABB bounds = shape.bounds();
                    toDraw = new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + bounds.getXsize(), pos.getY() + bounds.getYsize(), pos.getZ() + bounds.getZsize());
                    IRenderer.emitAABB(bufferBuilder, event.getModelViewStack(), toDraw, .002D);

                    IRenderer.endLines(bufferBuilder, settings.renderSelectionBoxesIgnoreDepth.value);
                }
            });
        }
    }

    private BlockPos findFirstLiquidGround(BlockPos startPos, int distanceToCheck, boolean renderCheckedBlocks) {
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
                        BlockState current = ctx.world().getBlockState(new BlockPos(blockX, blockY, blockZ));
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

    private HighwayState getIssueType(BlockPos blockPos) {
        BlockState state = ctx.world().getBlockState(blockPos);
        if (state.getBlock() instanceof AirBlock) {
            return HighwayState.Air;
        }
        if (state.getBlock() instanceof LiquidBlock) {
            return HighwayState.Liquids;
        }

        return HighwayState.Blocks; // Not air and not liquids so it's some block
    }

    private void findSourceLiquid(int x, int y, int z, ArrayList<BlockPos> checked, ArrayList<BlockPos> sourceBlocks, ArrayList<BlockPos> flowingBlocks) {
        BlockState curBlockState = ctx.world().getBlockState(new BlockPos(x, y, z));

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

    private boolean isLiquidCoveredAllSides(BlockPos blockPos) {
        if (getIssueType(blockPos.north()) != HighwayState.Blocks) {
            return false;
        }
        if (getIssueType(blockPos.east()) != HighwayState.Blocks) {
            return false;
        }
        if (getIssueType(blockPos.south()) != HighwayState.Blocks) {
            return false;
        }
        if (getIssueType(blockPos.west()) != HighwayState.Blocks) {
            return false;
        }
        if (getIssueType(blockPos.above()) != HighwayState.Blocks) {
            return false;
        }
        return getIssueType(blockPos.below()) == HighwayState.Blocks;
    }

    private NonNullList<ItemStack> getShulkerContents(ItemStack shulker) {
        NonNullList<ItemStack> contents = NonNullList.withSize(27, ItemStack.EMPTY);

        if (shulker.has(DataComponents.BLOCK_ENTITY_DATA)) {
            CustomData data = shulker.get(DataComponents.BLOCK_ENTITY_DATA);
            if (data != null && data.contains("Items")) {
                CompoundTag compoundTag = data.copyTag();
                if (compoundTag.contains("Items")) {
                    ContainerHelper.loadAllItems(compoundTag, contents, ctx.player().registryAccess());
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

    private int getShulkerSlot(ShulkerType shulkerType) {
        int bestSlot = -1;
        int bestSlotCount = Integer.MAX_VALUE;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = ctx.player().getInventory().items.get(i);
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

    private int getItemCountInventory(int itemId) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = ctx.player().getInventory().items.get(i);
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

    private int getPickCountInventory() {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = ctx.player().getInventory().items.get(i);
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

    private State placeShulkerBox(Rotation shulkerReachable, Rotation underShulkerReachable, BlockPos shulkerPlaceLoc, State prevState, State currentState, State nextState, ShulkerType shulkerType) {
        if (shulkerReachable != null) {
            Block shulkerLocBlock = ctx.world().getBlockState(shulkerPlaceLoc).getBlock();
            baritone.getLookBehavior().updateTarget(shulkerReachable, true); // Look at shulker spot

            if (shulkerLocBlock instanceof ShulkerBoxBlock) {
                Helper.HELPER.logDirect("Shulker has been placed successfully");
                baritone.getInputOverrideHandler().clearAllKeys();
                return nextState;
            }
            else if (!(shulkerLocBlock instanceof SnowLayerBlock)) {
                Helper.HELPER.logDirect("Something went wrong at " + currentState + ". Have " + shulkerLocBlock + " instead of shulker");
                return prevState; // Go back a state
            }
        }
        else if (underShulkerReachable != null) {
            baritone.getLookBehavior().updateTarget(underShulkerReachable, true);
        }

        if (underShulkerReachable == null) {
            return prevState; // Something is wrong with where we are trying to place, go back
        }

        if (shulkerPlaceLoc.below().equals(ctx.getSelectedBlock().orElse(null))) {
            int shulkerSlot = putShulkerHotbar(shulkerType);
            if (shulkerSlot == -1) {
                Helper.HELPER.logDirect("Error getting shulker slot");
                return currentState;
            }
            if (shulkerSlot >= 9) {
                Helper.HELPER.logDirect("Couldn't put shulker to hotbar, waiting");
                return currentState;
            }
            ItemStack stack = ctx.player().getInventory().items.get(shulkerSlot);
            if (shulkerItemList.contains(stack.getItem())) {
                ctx.player().getInventory().selected = shulkerSlot;
            }

            //baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            double lastX = ctx.getPlayerEntity().getXLast();
            double lastY = ctx.getPlayerEntity().getYLast();
            double lastZ = ctx.getPlayerEntity().getZLast();
            final Vec3 pos = new Vec3(lastX + (ctx.player().getX() - lastX) * ctx.minecraft().getTimer().getGameTimeDeltaPartialTick(true),
                    lastY + (ctx.player().getY() - lastY) * ctx.minecraft().getTimer().getGameTimeDeltaPartialTick(true),
                    lastZ + (ctx.player().getZ() - lastZ) * ctx.minecraft().getTimer().getGameTimeDeltaPartialTick(true));
            BetterBlockPos originPos = new BetterBlockPos(pos.x, pos.y+0.5f, pos.z);
            double l_Offset = pos.y - originPos.getY();
            PlaceResult l_Place = place(shulkerPlaceLoc, 5.0f, true, l_Offset == -0.5f, InteractionHand.MAIN_HAND);
        }

        return currentState;
    }

    private int getDepletedPickSlot() {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = ctx.player().getInventory().items.get(i);
            if (stack.getItem() instanceof PickaxeItem && (stack.getDamageValue() + settings.itemSaverThreshold.value) >= stack.getMaxDamage() && stack.getMaxDamage() > 1) {
                return i;
            }
        }

        return -1;
    }

    private int lootPickaxeChestSlot() {
        AbstractContainerMenu curContainer = ctx.player().containerMenu;
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
                        ctx.playerController().windowClick(curContainer.containerId, i, 0, ClickType.QUICK_MOVE, ctx.player());
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
                    ctx.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, ctx.player());
                    ctx.playerController().windowClick(curContainer.containerId, swapSlot < 9 ? swapSlot + 54 : swapSlot + 18, 0, ClickType.PICKUP, ctx.player()); // Have to convert slot id to single chest slot id
                    ctx.playerController().windowClick(curContainer.containerId, -999, 0, ClickType.PICKUP, ctx.player()); // Throw away netherrack
                } else {
                    // Swap new pickaxe with depleted
                    ctx.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, ctx.player());
                    ctx.playerController().windowClick(curContainer.containerId, swapSlot < 9 ? swapSlot + 54 : swapSlot + 18, 0, ClickType.PICKUP, ctx.player()); // Have to convert slot id to single chest slot id
                    ctx.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, ctx.player()); // Put depleted pick in looted slot
                }
                return 1;
            }
        }

        return 0;
    }

    private int lootGappleChestSlot() {
        int count = 0;
        AbstractContainerMenu curContainer = ctx.player().containerMenu;
        for (int i = 0; i < 27; i++) {
            if (curContainer.getSlot(i).getItem().getItem() == Items.ENCHANTED_GOLDEN_APPLE) {
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
                    ctx.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, ctx.player());
                    ctx.playerController().windowClick(curContainer.containerId, netherRackSlot < 9 ? netherRackSlot + 54 : netherRackSlot + 18, 0, ClickType.PICKUP, ctx.player()); // Have to convert slot id to single chest slot id
                    ctx.playerController().windowClick(curContainer.containerId, -999, 0, ClickType.PICKUP, ctx.player());
                } else {
                    // Gapples exist already or there's an air slot so we can just do a quick move
                    ctx.playerController().windowClick(curContainer.containerId, i, 0, ClickType.QUICK_MOVE, ctx.player());
                }

                return count;
            }
        }

        return count;
    }

    private int lootEnderChestSlot() {
        int count = 0;
        AbstractContainerMenu curContainer = ctx.player().containerMenu;
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
                    ctx.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, ctx.player());
                    ctx.playerController().windowClick(curContainer.containerId, netherRackSlot < 9 ? netherRackSlot + 54 : netherRackSlot + 18, 0, ClickType.PICKUP, ctx.player()); // Have to convert slot id to single chest slot id
                    ctx.playerController().windowClick(curContainer.containerId, -999, 0, ClickType.PICKUP, ctx.player());
                } else {
                    // There's an air slot so we can just do a quick move
                    ctx.playerController().windowClick(curContainer.containerId, i, 0, ClickType.QUICK_MOVE, ctx.player());
                }

                return count;
            }
        }

        return count;
    }

    private int lootShulkerChestSlot(ShulkerType shulkerType) {
        AbstractContainerMenu curContainer = ctx.player().containerMenu;
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
                        ctx.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, ctx.player());
                        ctx.playerController().windowClick(curContainer.containerId, netherRackSlot < 9 ? netherRackSlot + 54 : netherRackSlot + 18, 0, ClickType.PICKUP, ctx.player()); // Have to convert slot id to single chest slot id
                        ctx.playerController().windowClick(curContainer.containerId, -999, 0, ClickType.PICKUP, ctx.player());
                    } else {
                        // There's an air slot so we can just do a quick move
                        ctx.playerController().windowClick(curContainer.containerId, i, 0, ClickType.QUICK_MOVE, ctx.player());
                    }
                    return 1;
                }
            }
        }

        return 0;
    }

    private int getShulkerCountInventory(ShulkerType shulkerType) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = ctx.player().getInventory().items.get(i);
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

    private boolean isShulkerOnGround() {
        for (Entity entity : ctx.entities()) {
            if (entity instanceof ItemEntity) {
                if (shulkerItemList.contains(((ItemEntity) entity).getItem().getItem())) {
                    return true;
                }
            }
        }

        return false;
    }

    private BlockPos closestAirBlockWithSideBlock(BlockPos pos, int radius, boolean allowUp) {
        ArrayList<BlockPos> surroundingBlocks = new ArrayList<>();
        int yRadius = radius;
        if (!allowUp) {
            yRadius = 0;
        }
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= yRadius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos curPos = pos.offset(x, y, z);
                    if (getIssueType(curPos) != HighwayState.Blocks &&
                            (getIssueType(curPos.north()) == HighwayState.Blocks ||
                                    getIssueType(curPos.east()) == HighwayState.Blocks ||
                                    getIssueType(curPos.south()) == HighwayState.Blocks ||
                                    getIssueType(curPos.west()) == HighwayState.Blocks ||
                                    getIssueType(curPos.below()) == HighwayState.Blocks)) {
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
}