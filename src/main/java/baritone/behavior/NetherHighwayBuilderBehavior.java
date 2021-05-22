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
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.schematic.CompositeSchematic;
import baritone.api.schematic.FillSchematic;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.WhiteBlackSchematic;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.IRenderer;
import net.minecraft.block.*;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiShulkerBox;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Blocks;
import net.minecraft.init.Enchantments;
import net.minecraft.init.Items;
import net.minecraft.init.MobEffects;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ItemStackHelper;
import net.minecraft.item.*;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.play.client.CPacketAnimation;
import net.minecraft.network.play.client.CPacketEntityAction;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraft.network.play.client.CPacketPlayerDigging;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.*;
import net.minecraft.util.text.TextComponentString;

import java.awt.*;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static baritone.api.utils.Helper.mc;
import static baritone.pathing.movement.Movement.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP;

public final class NetherHighwayBuilderBehavior extends Behavior implements INetherHighwayBuilderBehavior, IRenderer {

    private CompositeSchematic schematic;
    private FillSchematic liqCheckSchem;
    private BetterBlockPos originBuild;
    private BetterBlockPos firstStartingPos;
    private Vec3d originVector = new Vec3d(0, 0, 0);
    private Vec3d liqOriginVector = new Vec3d(0, 0, 0);
    private Vec3d backPathOriginVector = new Vec3d(0, 0, 0);
    private Vec3d eChestEmptyShulkOriginVector = new Vec3d(0, 0, 0);
    private Vec3d highwayDirection = new Vec3d(1, 0, -1);
    private boolean paving = false;
    private final int highwayLowestY = 118;
    private final int mainHighwayY = 119;
    private final int emptyShulkEchestY = 121;
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
    private final List<IBlockState> approxPlaceable = new ArrayList<IBlockState>() {};

    private final Lock renderLock = new ReentrantLock();
    private final ArrayList<BlockPos> renderBlocks = new ArrayList<>();
    private final ArrayList<Rotation> floatingFixReachables = new ArrayList<>();

    private boolean instantMineActivated = false;
    private boolean instantMinePacketCancel = false;
    private BlockPos instantMineLastBlock;
    private EnumFacing instantMineDirection;
    private Item instantMineOriginalOffhandItem;
    private boolean instantMinePlace = true;

    private static final List<Block> blackList = Arrays.asList(Blocks.ENDER_CHEST, Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.CRAFTING_TABLE, Blocks.ANVIL, Blocks.BREWING_STAND, Blocks.HOPPER,
            Blocks.DROPPER, Blocks.DISPENSER, Blocks.TRAPDOOR, Blocks.ENCHANTING_TABLE);
    private static final List<Block> shulkerList = Arrays.asList(Blocks.WHITE_SHULKER_BOX, Blocks.ORANGE_SHULKER_BOX, Blocks.MAGENTA_SHULKER_BOX, Blocks.LIGHT_BLUE_SHULKER_BOX,
            Blocks.YELLOW_SHULKER_BOX, Blocks.LIME_SHULKER_BOX, Blocks.PINK_SHULKER_BOX, Blocks.GRAY_SHULKER_BOX, Blocks.SILVER_SHULKER_BOX, Blocks.CYAN_SHULKER_BOX, Blocks.PURPLE_SHULKER_BOX,
            Blocks.BLUE_SHULKER_BOX, Blocks.BROWN_SHULKER_BOX, Blocks.GREEN_SHULKER_BOX, Blocks.RED_SHULKER_BOX, Blocks.BLACK_SHULKER_BOX);

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
    public void build(int startX, int startZ, Vec3d direct, boolean selfSolve, boolean pave) {
        highwayDirection = direct;
        paving = pave;
        cachedHealth = ctx.player().getHealth();

        if (!paving) {
            // Only digging so any pickaxe works
            picksToUse = ShulkerType.AnyPickaxe;
            picksToHave = settings.highwayPicksToHaveDigging.value;
        } else {
            // If paving then we mine echests and need non silk picks
            picksToUse = ShulkerType.NonSilkPickaxe;
            picksToHave = settings.highwayPicksToHavePaving.value;
        }

        ISchematic obsidSchemBot;
        FillSchematic topAir;
        WhiteBlackSchematic noLavaBotSides = new WhiteBlackSchematic(1, 1, 1, Arrays.asList(Blocks.LAVA.getDefaultState(), Blocks.FLOWING_LAVA.getDefaultState()), Blocks.NETHERRACK.getDefaultState(), false, true);
        WhiteBlackSchematic supportNetherRack;
        WhiteBlackSchematic sideRailSupport;
        ISchematic sideRail;
        if (pave)
            sideRail = new FillSchematic(1, 1, 1, Blocks.OBSIDIAN.getDefaultState());
        else
            sideRail = new WhiteBlackSchematic(1, 1, 1, Arrays.asList(Blocks.AIR.getDefaultState(), Blocks.OBSIDIAN.getDefaultState()), Blocks.AIR.getDefaultState(), true, false);

        FillSchematic sideRailAir = new FillSchematic(1, 2, 1, Blocks.AIR.getDefaultState());
        CompositeSchematic fullSchem = new CompositeSchematic(0, 0,0);

        // +X and -X
        if (highwayDirection.z == 0 && (highwayDirection.x == -1 || highwayDirection.x == 1)) {
            if (selfSolve) {
                originVector = new Vec3d(0, 0, -3);
                liqOriginVector = new Vec3d(0, 0, -4);
                backPathOriginVector = new Vec3d(0, 0, 0);
                eChestEmptyShulkOriginVector = new Vec3d(0, 0, -4);
            } else {
                originVector = new Vec3d(startX, 0, startZ);
                liqOriginVector = new Vec3d(startX, 0, startZ - 1);
                backPathOriginVector = new Vec3d(startX, 0, startZ + 3);
                eChestEmptyShulkOriginVector = new Vec3d(startX, 0, startZ - 1);
            }

            topAir = new FillSchematic(1, 3, 4, Blocks.AIR.getDefaultState());
            if (pave) {
                obsidSchemBot = new FillSchematic(1, 1, 4, Blocks.OBSIDIAN.getDefaultState());
                liqCheckSchem = new FillSchematic(1, 3, 6, Blocks.AIR.getDefaultState());
                liqOriginVector = liqOriginVector.add(0, 0, 1);
            } else {
                obsidSchemBot = new WhiteBlackSchematic(1, 1, 4, Arrays.asList(Blocks.AIR.getDefaultState(), Blocks.OBSIDIAN.getDefaultState()), Blocks.AIR.getDefaultState(), true, false); // Allow only air and obsidian
                supportNetherRack = new WhiteBlackSchematic(1, 1, 2, Arrays.asList(Blocks.AIR.getDefaultState(), Blocks.LAVA.getDefaultState(), Blocks.FLOWING_LAVA.getDefaultState()), Blocks.NETHERRACK.getDefaultState(), false, true); // Allow everything other than air and lava
                liqCheckSchem = new FillSchematic(1, 5, 8, Blocks.AIR.getDefaultState());
                fullSchem.put(noLavaBotSides, 0, 0, 1);
                fullSchem.put(noLavaBotSides, 0, 0, 4);
                fullSchem.put(supportNetherRack, 0, 0, 2);
            }

            fullSchem.put(obsidSchemBot, 0, 1, 1);
            fullSchem.put(sideRail, 0, 2, 0);
            fullSchem.put(sideRail, 0, 2, 5);
            fullSchem.put(sideRailAir, 0, 3, 0);
            fullSchem.put(sideRailAir, 0, 3, 5);
            fullSchem.put(topAir, 0, 2, 1);
        }
        // +Z and -Z
        else if (highwayDirection.x == 0 && (highwayDirection.z == -1 || highwayDirection.z == 1)) {

            if (selfSolve) {
                originVector = new Vec3d(-3, 0, 0);
                liqOriginVector = new Vec3d(-4, 0, 0);
                backPathOriginVector = new Vec3d(0, 0, 0);
                eChestEmptyShulkOriginVector = new Vec3d(-4, 0, 0);
            } else {
                originVector = new Vec3d(startX, 0, startZ);
                liqOriginVector = new Vec3d(startX - 1, 0, startZ);
                backPathOriginVector = new Vec3d(startX + 3, 0, startZ);
                eChestEmptyShulkOriginVector = new Vec3d(startX - 1, 0, startZ);
            }

            topAir = new FillSchematic(4, 3, 1, Blocks.AIR.getDefaultState());
            if (pave) {
                obsidSchemBot = new FillSchematic(4, 1, 1, Blocks.OBSIDIAN.getDefaultState());
                liqCheckSchem = new FillSchematic(6, 3, 1, Blocks.AIR.getDefaultState());
                liqOriginVector = liqOriginVector.add(1, 0, 0);
            } else {
                obsidSchemBot = new WhiteBlackSchematic(4, 1, 1, Arrays.asList(Blocks.AIR.getDefaultState(), Blocks.OBSIDIAN.getDefaultState()), Blocks.AIR.getDefaultState(), true, false); // Allow only air and obsidian
                supportNetherRack = new WhiteBlackSchematic(2, 1, 1, Arrays.asList(Blocks.AIR.getDefaultState(), Blocks.LAVA.getDefaultState(), Blocks.FLOWING_LAVA.getDefaultState()), Blocks.NETHERRACK.getDefaultState(), false, true); // Allow everything other than air and lava
                liqCheckSchem = new FillSchematic(8, 5, 1, Blocks.AIR.getDefaultState());
                fullSchem.put(noLavaBotSides, 1, 0, 0);
                fullSchem.put(noLavaBotSides, 4, 0, 0);
                fullSchem.put(supportNetherRack, 2, 0, 0);
            }

            fullSchem.put(obsidSchemBot, 1, 1, 0);
            fullSchem.put(sideRail, 0, 2, 0);
            fullSchem.put(sideRail, 5, 2, 0);
            fullSchem.put(sideRailAir, 0, 3, 0);
            fullSchem.put(sideRailAir, 5, 3, 0);
            fullSchem.put(topAir, 1, 2, 0);
        }
        // +X,-Z and -X,+Z
        else if ((highwayDirection.x == 1 && highwayDirection.z == -1) || (highwayDirection.x == -1 && highwayDirection.z == 1)) {
            if (selfSolve) {
                originVector = new Vec3d(-5, 0, 0);
                liqOriginVector = new Vec3d(-6, 0, 0);
                backPathOriginVector = new Vec3d(0, 0, 0);
                eChestEmptyShulkOriginVector = new Vec3d(-6, 0, 0);
            } else {
                originVector = new Vec3d(startX - 5, 0, startZ);
                liqOriginVector = new Vec3d(startX - 6, 0, startZ);
                backPathOriginVector = new Vec3d(startX, 0, startZ);
                eChestEmptyShulkOriginVector = new Vec3d(startX - 6, 0, startZ);
            }

            topAir = new FillSchematic(7, 3, 1, Blocks.AIR.getDefaultState());
            if (pave) {
                obsidSchemBot = new FillSchematic(7, 1, 1, Blocks.OBSIDIAN.getDefaultState());
                liqCheckSchem = new FillSchematic(9, 3, 1, Blocks.AIR.getDefaultState());
                liqOriginVector = liqOriginVector.add(1, 0, 0);
                sideRailSupport = new WhiteBlackSchematic(1, 1, 1, Arrays.asList(Blocks.AIR.getDefaultState(), Blocks.LAVA.getDefaultState(), Blocks.FLOWING_LAVA.getDefaultState(), Blocks.FIRE.getDefaultState()), Blocks.NETHERRACK.getDefaultState(), false, true);
                fullSchem.put(sideRailSupport, 0, 1, 0);
                fullSchem.put(sideRailSupport, 8, 1, 0);
            } else {
                obsidSchemBot = new WhiteBlackSchematic(7, 1, 1, Arrays.asList(Blocks.AIR.getDefaultState(), Blocks.OBSIDIAN.getDefaultState()), Blocks.AIR.getDefaultState(), true, false); // Allow only air and obsidian
                supportNetherRack = new WhiteBlackSchematic(5, 1, 1, Arrays.asList(Blocks.AIR.getDefaultState(), Blocks.LAVA.getDefaultState(), Blocks.FLOWING_LAVA.getDefaultState(), Blocks.FIRE.getDefaultState()), Blocks.NETHERRACK.getDefaultState(), false, true); // Allow everything other than air and lava
                liqCheckSchem = new FillSchematic(11, 5, 1, Blocks.AIR.getDefaultState());

                // Blocks around 2x2, and right below paved obby, don't want to be lava as someone might fall in
                fullSchem.put(noLavaBotSides, 1, 0, 0);
                fullSchem.put(noLavaBotSides, 7, 0, 0);

                fullSchem.put(supportNetherRack, 2, 0, 0); // to walk on if digging through open nether, want a 2x2 path
            }

            fullSchem.put(obsidSchemBot, 1, 1, 0);
            fullSchem.put(sideRail, 0, 2, 0);
            fullSchem.put(sideRail, 8, 2, 0);
            fullSchem.put(sideRailAir, 0, 3, 0);
            fullSchem.put(sideRailAir, 8, 3, 0);
            fullSchem.put(topAir, 1, 2, 0);
        }
        // +X,+Z and -X,-Z
        else if ((highwayDirection.x == 1 && highwayDirection.z == 1) || (highwayDirection.x == -1 && highwayDirection.z == -1)) {
            if (selfSolve) {
                originVector = new Vec3d(0, 0, -4);
                liqOriginVector = new Vec3d(0, 0, -5);
                backPathOriginVector = new Vec3d(0, 0, 0);
                eChestEmptyShulkOriginVector = new Vec3d(0, 0, -5);
            } else {
                originVector = new Vec3d(startX, 0, startZ - 4);
                liqOriginVector = new Vec3d(startX, 0, startZ - 5);
                backPathOriginVector = new Vec3d(startX, 0, startZ);
                eChestEmptyShulkOriginVector = new Vec3d(startX, 0, startZ - 5);
            }

            topAir = new FillSchematic(1, 3, 7, Blocks.AIR.getDefaultState());
            if (pave) {
                obsidSchemBot = new FillSchematic(1, 1, 7, Blocks.OBSIDIAN.getDefaultState());
                liqCheckSchem = new FillSchematic(1, 3, 9, Blocks.AIR.getDefaultState());
                liqOriginVector = liqOriginVector.add(0, 0, 1);
                sideRailSupport = new WhiteBlackSchematic(1, 1, 1, Arrays.asList(Blocks.AIR.getDefaultState(), Blocks.LAVA.getDefaultState(), Blocks.FLOWING_LAVA.getDefaultState(), Blocks.FIRE.getDefaultState()), Blocks.NETHERRACK.getDefaultState(), false, true);
                fullSchem.put(sideRailSupport, 0, 1, 0);
                fullSchem.put(sideRailSupport, 0, 1, 8);
            } else {
                obsidSchemBot = new WhiteBlackSchematic(1, 1, 7, Arrays.asList(Blocks.AIR.getDefaultState(), Blocks.OBSIDIAN.getDefaultState()), Blocks.AIR.getDefaultState(), true, false); // Allow only air and obsidian
                supportNetherRack = new WhiteBlackSchematic(1, 1, 5, Arrays.asList(Blocks.AIR.getDefaultState(), Blocks.LAVA.getDefaultState(), Blocks.FLOWING_LAVA.getDefaultState(), Blocks.FIRE.getDefaultState()), Blocks.NETHERRACK.getDefaultState(), false, true); // Allow everything other than air and lava
                liqCheckSchem = new FillSchematic(1, 5, 11, Blocks.AIR.getDefaultState());

                // Blocks around 2x2, and right below paved obby, don't want to be lava as someone might fall in
                fullSchem.put(noLavaBotSides, 0, 0, 1);
                fullSchem.put(noLavaBotSides, 0, 0, 7);

                fullSchem.put(supportNetherRack, 0, 0, 2); // to walk on if digging through open nether, want a 2x2 path
            }

            fullSchem.put(obsidSchemBot, 0, 1, 1);
            fullSchem.put(sideRail, 0, 2, 0);
            fullSchem.put(sideRail, 0, 2, 8);
            fullSchem.put(sideRailAir, 0, 3, 0);
            fullSchem.put(sideRailAir, 0, 3, 8);
            fullSchem.put(topAir, 0, 2, 1);
        }

        schematic = fullSchem;

        //if (selfSolve) {
            Vec3d origin = new Vec3d(originVector.x, originVector.y, originVector.z);
            Vec3d direction = new Vec3d(highwayDirection.x, highwayDirection.y, highwayDirection.z);
            Vec3d curPos = new Vec3d(ctx.playerFeet().getX(), ctx.playerFeet().getY(), ctx.playerFeet().getZ());
            originBuild = getClosestPoint(origin, direction, curPos, LocationType.HighwayBuild);
        //} else {
        //    originBuild = new BetterBlockPos(startX, highwayLowestY, startZ);
        //}

        firstStartingPos = new BetterBlockPos(originBuild);

        Helper.HELPER.logDirect("Building from " + originBuild.toString());
        settings.buildRepeat.value = new Vec3i(highwayDirection.x, 0, highwayDirection.z);
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
        if (paused || schematic == null || mc.player == null || mc.player.inventory.isEmpty()) {
            return;
        }

        timer++;
        walkBackTimer++;
        checkBackTimer++;
        stuckTimer++;

        if (!mc.player.inventory.getItemStack().isEmpty() && ctx.player().openContainer == ctx.player().inventoryContainer) {
            if (cursorStackNonEmpty && timer >= 20) {
                // We have some item on our cursor for 20 ticks, try to place it somewhere
                timer = 0;


                int emptySlot = getItemSlot(Item.getIdFromItem(Items.AIR));
                if (emptySlot != -1) {
                    Helper.HELPER.logDirect("Had " + mc.player.inventory.getItemStack().getDisplayName() + " on our cursor. Trying to place into slot " + emptySlot);

                    if (emptySlot <= 8) {
                        // Fix slot id if it's a hotbar slot
                        emptySlot += 36;
                    }
                    ctx.playerController().windowClick(ctx.player().inventoryContainer.windowId, emptySlot, 0, ClickType.PICKUP, ctx.player());
                    mc.playerController.updateController();
                    cursorStackNonEmpty = false;
                    return;
                } else {
                    if (Item.getIdFromItem(mc.player.inventory.getItemStack().getItem()) == Item.getIdFromItem(Item.getItemFromBlock(Blocks.NETHERRACK))) {
                        // Netherrack on our cursor, we can just throw it out
                        ctx.playerController().windowClick(ctx.player().inventoryContainer.windowId, -999, 0, ClickType.PICKUP, ctx.player());
                        mc.playerController.updateController();
                        cursorStackNonEmpty = false;
                        return;
                    } else {
                        // We don't have netherrack on our cursor, might be important so swap with netherrack and throw away the netherrack
                        int netherRackSlot = getItemSlot(Item.getIdFromItem(Item.getItemFromBlock(Blocks.NETHERRACK)));
                        if (netherRackSlot == 8) {
                            netherRackSlot = getItemSlotNoHotbar(Item.getIdFromItem(Item.getItemFromBlock(Blocks.NETHERRACK)));
                        }
                        if (netherRackSlot != -1) {
                            if (netherRackSlot <= 8) {
                                // Fix slot id if it's a hotbar slot
                                netherRackSlot += 36;
                            }
                            ctx.playerController().windowClick(ctx.player().inventoryContainer.windowId, netherRackSlot, 0, ClickType.PICKUP, ctx.player());
                            ctx.playerController().windowClick(ctx.player().inventoryContainer.windowId, -999, 0, ClickType.PICKUP, ctx.player());
                            mc.playerController.updateController();
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
        if (baritone.getBuilderProcess().isActive() /*&& !baritone.getBuilderProcess().isPaused()*/ && stuckTimer >= settings.highwayStuckCheckTicks.value) {
            if (mc.currentScreen instanceof GuiChest) {
                ctx.player().closeScreen(); // Close chest gui so we can actually build
                stuckTimer = 0;
                return;
            }

            if (cachedPlayerFeet == null) {
                cachedPlayerFeet = new BetterBlockPos(ctx.playerFeet());
                stuckTimer = 0;
                return;
            }

            if (cachedPlayerFeet.getDistance(ctx.playerFeet().x, ctx.playerFeet().y, ctx.playerFeet().z) < settings.highwayStuckDistance.value) {
                // Check for floating case
                if (ctx.world().getBlockState(ctx.playerFeet().down()).getBlock() instanceof BlockAir) {
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
                currentState = State.Nothing;
                ctx.player().connection.getNetworkManager().closeChannel(new TextComponentString("Haven't moved in " + settings.highwayStuckCheckTicks.value + " ticks. Reconnect"));
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
        if (settings.highwayDcOnHealthLoss.value && ctx.player().getHealth() < cachedHealth) {
            TextComponentString dcMsg = new TextComponentString("Lost " + (cachedHealth - ctx.player().getHealth()) + " health. Reconnect");
            ctx.player().connection.getNetworkManager().closeChannel(dcMsg);
        }
        cachedHealth = ctx.player().getHealth(); // Get new HP value

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
                    boolean issueDetected = false;
                    Goal ourGoal = baritone.getPathingBehavior().getGoal();
                    if (ourGoal instanceof GoalComposite) {
                        Goal[] goals = ((GoalComposite) ourGoal).goals();
                        for (Goal goal : goals) {
                            if (goal instanceof GoalBlock) {
                                if (((GoalBlock) goal).getGoalPos().getDistance(ctx.playerFeet().getX(), ctx.playerFeet().getY(), ctx.playerFeet().getZ()) > settings.highwayMaxLostShulkerSearchDist.value) {
                                    issueDetected = true;
                                    break;
                                }
                            }
                        }
                    }
                    if (ourGoal instanceof GoalBlock) {
                        if (((GoalBlock) ourGoal).getGoalPos().getDistance(ctx.playerFeet().getX(), ctx.playerFeet().getY(), ctx.playerFeet().getZ()) > settings.highwayMaxLostShulkerSearchDist.value) {
                            issueDetected = true;
                        }
                    }
                    if (issueDetected) {
                        Helper.HELPER.logDirect("We are walking way too far. Restarting");
                        currentState = State.Nothing;
                        return;
                    }
                }

                // TODO: Change shulker threshold from 0 to a customizable value
                if (getItemCountInventory(Item.getIdFromItem(Item.getItemFromBlock(Blocks.OBSIDIAN))) <= settings.highwayObsidianThreshold.value && paving) {
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

                if (getItemCountInventory(Item.getIdFromItem(Items.GOLDEN_APPLE)) <= settings.highwayGapplesThreshold.value) {
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
                    Vec3d direction = new Vec3d(highwayDirection.x, highwayDirection.y, highwayDirection.z);

                    //double distToWantedStart = ctx.playerFeet().getDistance(startCheckPos.getX(), startCheckPos.getY(), startCheckPos.getZ());
                    Vec3d curPosNotOffset = new Vec3d(ctx.playerFeet().getX(), ctx.playerFeet().getY(), ctx.playerFeet().getZ());
                    // Fix player feet location for diags so we don't check too far ahead
                    // +X,+Z and -X,-Z
                    if (((highwayDirection.x == 1 && highwayDirection.z == 1) || (highwayDirection.x == -1 && highwayDirection.z == -1)) /*&&
                            Math.abs(curPosNotOffset.z) >= Math.abs(curPosNotOffset.x)*/) {
                        curPosNotOffset = new Vec3d(curPosNotOffset.x, curPosNotOffset.y, curPosNotOffset.x - 4);
                    } else if ((highwayDirection.x == 1 && highwayDirection.z == -1) || (highwayDirection.x == -1 && highwayDirection.z == 1)) {
                        curPosNotOffset = new Vec3d(-curPosNotOffset.z - 5, curPosNotOffset.y, curPosNotOffset.z);
                    }

                    Vec3d curPos = new Vec3d(curPosNotOffset.x + (highwayCheckBackDistance * -highwayDirection.x), curPosNotOffset.y, curPosNotOffset.z + (highwayCheckBackDistance * -highwayDirection.z));
                    BlockPos startCheckPos = getClosestPoint(new Vec3d(originVector.x, originVector.y, originVector.z), direction, curPos, LocationType.HighwayBuild);
                    BlockPos startCheckPosLiq = getClosestPoint(new Vec3d(liqOriginVector.x, liqOriginVector.y, liqOriginVector.z), new Vec3d(highwayDirection.x, highwayDirection.y, highwayDirection.z), curPos, LocationType.ShulkerEchestInteraction);


                    BlockPos feetClosestPoint = getClosestPoint(new Vec3d(originVector.x, originVector.y, originVector.z), direction, curPosNotOffset, LocationType.HighwayBuild);
                    double distToWantedStart;
                    if ((highwayDirection.x == 1 && highwayDirection.z == 1) || (highwayDirection.x == -1 && highwayDirection.z == -1)) {
                        distToWantedStart = Math.abs(Math.abs(ctx.playerFeet().getX()) - Math.abs(startCheckPos.getX()));
                    } else if ((highwayDirection.x == 1 && highwayDirection.z == -1) || (highwayDirection.x == -1 && highwayDirection.z == 1)) {
                        distToWantedStart = Math.abs(Math.abs(ctx.playerFeet().getZ()) - Math.abs(startCheckPos.getZ()));
                    } else {
                        distToWantedStart = feetClosestPoint.getDistance(startCheckPos.getX(), startCheckPos.getY(), startCheckPos.getZ());
                    }

                    //double oldDist = ctx.playerFeet().getDistance(startCheckPos.getX(), startCheckPos.getY(), startCheckPos.getZ());
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
                        Helper.HELPER.logDirect("Found liquids that should be something else. Fixing.");
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
                        Helper.HELPER.logDirect("Found blocks that should be something else. Fixing.");
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

                if (timer >= 10 && (ctx.player().isBurning() || ctx.player().getFoodStats().getFoodLevel() <= 16)) {
                    PotionEffect fireRest = ctx.player().getActivePotionEffect(MobEffects.FIRE_RESISTANCE);
                    if (fireRest == null || fireRest.getDuration() < settings.highwayFireRestMinDuration.value || ctx.player().getFoodStats().getFoodLevel() <= 16) {
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
                            Optional<Rotation> curIssuePosReachable = RotationUtils.reachable(ctx.player(), ctx.playerFeet().add(x, y, z), ctx.playerController().getBlockReachDistance());
                            IBlockState state = ctx.world().getBlockState(ctx.playerFeet().add(x, y, z));
                            Block block = state.getBlock();
                            if (block != Blocks.BEDROCK && !(block instanceof BlockLiquid) && !(block instanceof BlockAir) && curIssuePosReachable.isPresent()) {
                                floatingFixReachables.add(curIssuePosReachable.get());
                            }
                        }
                    }
                }
                currentState = State.FloatingFix;
                return;
            }

            case FloatingFix: {
                int pickSlot = putItemHotbar(Item.getIdFromItem(Items.DIAMOND_PICKAXE));
                if (pickSlot == -1) {
                    Helper.HELPER.logDirect("Error getting pick slot");
                    currentState = State.Nothing;
                    return;
                }
                ItemStack stack = ctx.player().inventory.mainInventory.get(pickSlot);
                if (Item.getIdFromItem(stack.getItem()) == Item.getIdFromItem(Items.DIAMOND_PICKAXE)) {
                    ctx.player().inventory.currentItem = pickSlot;
                    mc.playerController.updateController();
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
                Vec3d direction = new Vec3d(highwayDirection.x, highwayDirection.y, highwayDirection.z);
                Vec3d curPos = new Vec3d(ctx.playerFeet().getX() + (7 * -highwayDirection.x), ctx.playerFeet().getY(), ctx.playerFeet().getZ() + (7 * -highwayDirection.z)); // Go back a bit to clear up our mess
                BlockPos startCheckPos = getClosestPoint(new Vec3d(liqOriginVector.x, liqOriginVector.y, liqOriginVector.z), direction, curPos, LocationType.ShulkerEchestInteraction);

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
                        ((highwayDirection.z == -1 && liquidPos.getZ() > ctx.playerFeet().getZ()) || // NW, N, NE
                                (highwayDirection.z == 1 && liquidPos.getZ() < ctx.playerFeet().getZ()) || // SE, S, SW
                                (highwayDirection.x == -1 && highwayDirection.z == 0 && liquidPos.getX() > ctx.playerFeet().getX()) || // W
                                (highwayDirection.x == 1 && highwayDirection.z == 0 && liquidPos.getX() < ctx.playerFeet().getX()))) { // E
                    curPos = new Vec3d(ctx.playerFeet().getX() + (7 * highwayDirection.x), ctx.playerFeet().getY(), ctx.playerFeet().getZ() + (7 * highwayDirection.z));
                }

                placeLoc = getClosestPoint(new Vec3d(backPathOriginVector.x, backPathOriginVector.y, backPathOriginVector.z), new Vec3d(highwayDirection.x, highwayDirection.y, highwayDirection.z), curPos, LocationType.ShulkerEchestInteraction);
                if (!sourceBlocks.isEmpty()) {// && )
                    //TODO: Might not need this
                    if (sourceBlocks.get(0).getY() >= 124) {
                        placeLoc = new BlockPos(placeLoc.getX(), 121, placeLoc.getZ());
                    }
                    else if (sourceBlocks.get(0).getY() - 3 > 119) {
                        placeLoc = new BlockPos(placeLoc.getX(), sourceBlocks.get(0).getY() - 3, placeLoc.getZ());
                    }
                }
                // Get closest point
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
                PotionEffect fireRest = ctx.player().getActivePotionEffect(MobEffects.FIRE_RESISTANCE);
                if (fireRest != null && fireRest.getDuration() >= settings.highwayFireRestMinDuration.value /*&& ctx.playerFeet().getY() == placeLoc.getY()*/) {
                    currentState = State.LiquidRemovalPathing;
                    KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
                    baritone.getInputOverrideHandler().clearAllKeys();
                    baritone.getPathingBehavior().cancelEverything();
                    return;
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
                int gappleSlot = putItemHotbar(Item.getIdFromItem(Items.GOLDEN_APPLE));
                if (gappleSlot == -1) {
                    Helper.HELPER.logDirect("Error getting gapple slot");
                    currentState = State.LiquidRemovalPrep;
                    return;
                }

                ItemStack stack = ctx.player().inventory.mainInventory.get(gappleSlot);
                if (Item.getIdFromItem(stack.getItem()) == Item.getIdFromItem(Items.GOLDEN_APPLE)) {
                    ctx.player().inventory.currentItem = gappleSlot;
                    mc.playerController.updateController();
                }

                currentState = State.LiquidRemovalGapplePreEat;
                timer = 0;
                break;
            }

            case LiquidRemovalGapplePreEat: {
                // Constantiam has some weird issue where you want to eat for half a second, stop and then eat again
                if (timer <= 10) {
                    if (mc.currentScreen == null) {
                        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), true);
                    } else {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    }
                } else {
                    KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
                    baritone.getInputOverrideHandler().clearAllKeys();
                    currentState = State.LiquidRemovalGappleEat;
                    timer = 0;
                }

                break;
            }

            case LiquidRemovalGappleEat: {
                PotionEffect fireRest = ctx.player().getActivePotionEffect(MobEffects.FIRE_RESISTANCE);
                if (fireRest != null && fireRest.getDuration() >= settings.highwayFireRestMinDuration.value && ctx.player().getFoodStats().getFoodLevel() > 16 /*&& ctx.playerFeet().getY() == placeLoc.getY()*/) {
                    currentState = State.LiquidRemovalPathing;
                    KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
                    baritone.getInputOverrideHandler().clearAllKeys();
                    baritone.getPathingBehavior().cancelEverything();
                    return;
                }

                if (timer <= 120) {
                    if (mc.currentScreen == null) {
                        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), true);
                    } else {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    }
                } else {
                    KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
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

                PotionEffect fireRest = ctx.player().getActivePotionEffect(MobEffects.FIRE_RESISTANCE);
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
                        getIssueType(sourceBlocks.get(0).down()) != HighwayState.Blocks) {
                    // Location to place against are not blocks so lets find the closest surrounding block
                    BlockPos tempSourcePos = closestAirBlockWithSideBlock(sourceBlocks.get(0), 5, false);
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
                for (EnumFacing side : EnumFacing.values()) {//(int i = 0; i < 5; i++) {
                    BlockPos against1 = sourceBlocks.get(0).offset(side); //sourceBlocks.get(0).offset(HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i]);
                    if (MovementHelper.canPlaceAgainst(ctx, against1)) {
                        double faceX = (sourceBlocks.get(0).getX() + against1.getX() + 1.0D) * 0.5D;
                        double faceY = (sourceBlocks.get(0).getY() + against1.getY() + 0.5D) * 0.5D;
                        double faceZ = (sourceBlocks.get(0).getZ() + against1.getZ() + 1.0D) * 0.5D;
                        Rotation place = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), new Vec3d(faceX, faceY, faceZ), ctx.playerRotations());
                        RayTraceResult res = RayTraceUtils.rayTraceTowards(ctx.player(), place, ctx.playerController().getBlockReachDistance(), false);
                        if (res != null && res.typeOfHit == RayTraceResult.Type.BLOCK && res.getBlockPos().equals(against1) && res.getBlockPos().offset(res.sideHit).equals(sourceBlocks.get(0))) {
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

                    int netherRackSlot = putItemHotbar(Item.getIdFromItem(Item.getItemFromBlock(Blocks.NETHERRACK)));
                    if (netherRackSlot == -1) {
                        Helper.HELPER.logDirect("Error getting netherrack slot");
                        currentState = State.Nothing;
                        return;
                    }


                    ItemStack stack = ctx.player().inventory.mainInventory.get(netherRackSlot);
                    if (Item.getIdFromItem(stack.getItem()) == Item.getIdFromItem(Item.getItemFromBlock(Blocks.NETHERRACK))) {
                        ctx.player().inventory.currentItem = netherRackSlot;
                        mc.playerController.updateController();
                    }
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    //currentState = State.LiquidRemovalPrep;
                } else {
                    int pickSlot = putItemHotbar(Item.getIdFromItem(Items.DIAMOND_PICKAXE));
                    if (pickSlot == -1) {
                        Helper.HELPER.logDirect("Error getting pick slot");
                        currentState = State.Nothing;
                        return;
                    }
                    ItemStack stack = ctx.player().inventory.mainInventory.get(pickSlot);
                    if (Item.getIdFromItem(stack.getItem()) == Item.getIdFromItem(Items.DIAMOND_PICKAXE)) {
                        ctx.player().inventory.currentItem = pickSlot;
                        mc.playerController.updateController();
                    }

                    Rotation lavaRot = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), new Vec3d(sourceBlocks.get(0).getX(), sourceBlocks.get(0).getY(), sourceBlocks.get(0).getZ()), ctx.playerRotations());
                    //RayTraceResult res = RayTraceUtils.rayTraceTowards(ctx.player(), lavaRot, ctx.playerController().getBlockReachDistance(), false);

                    ArrayList<BlockPos> possibleIssuePosList = new ArrayList<>();
                    for (int x = -1; x <= 1; x++) {
                        for (int z = -1; z <= 1; z++) {
                            BlockPos tempLoc = new BlockPos(ctx.playerFeet().x + x, ctx.playerFeet().y, ctx.playerFeet().z + z);
                                possibleIssuePosList.add(tempLoc);
                                possibleIssuePosList.add(tempLoc.up());
                                possibleIssuePosList.add(tempLoc.up().up());
                                possibleIssuePosList.add(tempLoc.up().up().up());
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
                            BlockPos against1 = placeAt.offset(HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i]);
                            if (MovementHelper.canPlaceAgainst(ctx, against1)) {
                                double faceX = (placeAt.getX() + against1.getX() + 1.0D) * 0.5D;
                                double faceY = (placeAt.getY() + against1.getY() + 0.5D) * 0.5D;
                                double faceZ = (placeAt.getZ() + against1.getZ() + 1.0D) * 0.5D;
                                Rotation place = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), new Vec3d(faceX, faceY, faceZ), ctx.playerRotations());
                                RayTraceResult res = RayTraceUtils.rayTraceTowards(ctx.player(), place, ctx.playerController().getBlockReachDistance(), false);
                                if (res != null && res.typeOfHit == RayTraceResult.Type.BLOCK && res.getBlockPos().equals(against1) && res.getBlockPos().offset(res.sideHit).equals(placeAt)) {
                                    baritone.getLookBehavior().updateTarget(place, true);
                                    baritone.getInputOverrideHandler().clearAllKeys();
                                    int netherRackSlot = putItemHotbar(Item.getIdFromItem(Item.getItemFromBlock(Blocks.NETHERRACK)));
                                    if (netherRackSlot == -1) {
                                        Helper.HELPER.logDirect("Error getting netherrack slot");
                                        currentState = State.Nothing;
                                        return;
                                    }
                                    if (Item.getIdFromItem(ctx.player().inventory.mainInventory.get(netherRackSlot).getItem()) == Item.getIdFromItem(Item.getItemFromBlock(Blocks.NETHERRACK))) {
                                        ctx.player().inventory.currentItem = netherRackSlot;
                                        mc.playerController.updateController();
                                    }

                                    final Vec3d pos = new Vec3d(mc.player.lastTickPosX + (mc.player.posX - mc.player.lastTickPosX) * mc.getRenderPartialTicks(),
                                            mc.player.lastTickPosY + (mc.player.posY - mc.player.lastTickPosY) * mc.getRenderPartialTicks(),
                                            mc.player.lastTickPosZ + (mc.player.posZ - mc.player.lastTickPosZ) * mc.getRenderPartialTicks());
                                    BlockPos originPos = new BlockPos(pos.x, pos.y+0.5f, pos.z);
                                    double l_Offset = pos.y - originPos.getY();
                                    if (place(placeAt, (float) ctx.playerController().getBlockReachDistance(), true, l_Offset == -0.5f, EnumHand.MAIN_HAND) == PlaceResult.Placed) {
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

                        int netherRackSlot = putItemHotbar(Item.getIdFromItem(Item.getItemFromBlock(Blocks.NETHERRACK)));
                        if (netherRackSlot == -1) {
                            Helper.HELPER.logDirect("Error getting netherrack slot");
                            currentState = State.Nothing;
                            return;
                        }
                        if (Item.getIdFromItem(ctx.player().inventory.mainInventory.get(netherRackSlot).getItem()) == Item.getIdFromItem(Item.getItemFromBlock(Blocks.NETHERRACK))) {
                            ctx.player().inventory.currentItem = netherRackSlot;
                            mc.playerController.updateController();
                        }

                        //TODO: Change this placing to use place method
                        //Helper.HELPER.logDirect("Placing " + placeAt);
                        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                        return;
                    }*/

                    ArrayList<Rotation> possibleIssueReachableList = new ArrayList<>();
                    for (BlockPos curIssuePos : possibleIssuePosList) {
                        Optional<Rotation> curIssuePosReachable = RotationUtils.reachable(ctx.player(), curIssuePos, ctx.playerController().getBlockReachDistance());
                        IBlockState state = ctx.world().getBlockState(curIssuePos);
                        Block block = state.getBlock();
                        if (block != Blocks.BEDROCK && !(block instanceof BlockLiquid) && !(block instanceof BlockAir) && curIssuePosReachable.isPresent() && curIssuePos.getY() >= 119) {
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
                Vec3d curPos = new Vec3d(ctx.playerFeet().getX() + (7 * -highwayDirection.x), ctx.playerFeet().getY(), ctx.playerFeet().getZ() + (7 * -highwayDirection.z)); // Go back a bit just in case
                Vec3d direction = new Vec3d(highwayDirection.x, highwayDirection.y, highwayDirection.z);

                placeLoc = getClosestPoint(new Vec3d(backPathOriginVector.x, backPathOriginVector.y, backPathOriginVector.z), direction, curPos, LocationType.ShulkerEchestInteraction);
                // Get closest point

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
                if (ctx.playerFeet().equals(placeLoc.add(highwayDirection.x, 0, highwayDirection.z))) {
                    // We have arrived
                    currentState = State.PlacingPickaxeShulker;
                    timer = 0;
                } else {
                    // Keep trying to get there
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(placeLoc.add(highwayDirection.x, 0, highwayDirection.z)));
                }

                break;
            }

            case PlacingPickaxeShulker: {
                if (!baritone.getBuilderProcess().isPaused() && baritone.getBuilderProcess().isActive()) {
                    timer = 0;
                    return; // Wait for build to complete
                }

                // Shulker box spot isn't air or shulker, lets fix that
                if (!(ctx.world().getBlockState(placeLoc).getBlock() instanceof BlockAir) && !(ctx.world().getBlockState(placeLoc).getBlock() instanceof BlockShulkerBox)) {
                    baritone.getPathingBehavior().cancelEverything();
                    baritone.getBuilderProcess().clearArea(placeLoc, placeLoc);
                    timer = 0;
                    return;
                }


                Optional<Rotation> shulkerReachable = RotationUtils.reachable(ctx.player(), placeLoc,
                        ctx.playerController().getBlockReachDistance());

                Optional<Rotation> underShulkerReachable = RotationUtils.reachable(ctx.player(), placeLoc.down(),
                        ctx.playerController().getBlockReachDistance());


                currentState = placeShulkerBox(shulkerReachable, underShulkerReachable, placeLoc, State.GoingToPlaceLocPickaxeShulker, currentState, State.OpeningPickaxeShulker, picksToUse);

                break;
            }

            case OpeningPickaxeShulker: {
                if (timer < 10) {
                    return;
                }

                Optional<Rotation> shulkerReachable = RotationUtils.reachable(ctx.player(), placeLoc,
                        ctx.playerController().getBlockReachDistance());
                shulkerReachable.ifPresent(rotation -> baritone.getLookBehavior().updateTarget(rotation, true));

                baritone.getInputOverrideHandler().clearAllKeys();
                if (!(mc.currentScreen instanceof GuiShulkerBox)) {
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

                if (!(mc.currentScreen instanceof GuiShulkerBox)) {
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
                        ctx.player().closeScreen();
                    }

                    timer = 0;
                } else {
                    currentState = State.MiningPickaxeShulker;
                    ctx.player().closeScreen();
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
                if (!(ctx.world().getBlockState(placeLoc).getBlock() instanceof BlockAir)) {
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

                for (Entity entity : mc.world.loadedEntityList) {
                    if (entity instanceof EntityItem) {
                        if (((EntityItem) entity).getItem().getItem() instanceof ItemShulkerBox) {
                            if (getItemCountInventory(Item.getIdFromItem(Items.AIR)) == 0) {
                                // No space for shulker, need to do removal
                                currentState = State.InventoryCleaningPickaxeShulker;
                                timer = 0;
                            }
                            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(new BlockPos(entity.posX, entity.posY, entity.posZ)));
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

                int netherRackSlot = getItemSlot(Item.getIdFromItem(Item.getItemFromBlock(Blocks.NETHERRACK)));
                if (netherRackSlot == 8) {
                    netherRackSlot = getItemSlotNoHotbar(Item.getIdFromItem(Item.getItemFromBlock(Blocks.NETHERRACK)));
                }
                if (netherRackSlot == -1) {
                    return;
                }
                baritone.getLookBehavior().updateTarget(new Rotation(45, 0), true);
                ctx.playerController().windowClick(ctx.player().inventoryContainer.windowId, netherRackSlot < 9 ? netherRackSlot + 36 : netherRackSlot, 0, ClickType.PICKUP, ctx.player());
                ctx.playerController().windowClick(ctx.player().inventoryContainer.windowId, -999, 0, ClickType.PICKUP, ctx.player());
                mc.playerController.updateController();
                currentState = State.CollectingPickaxeShulker;
                break;
            }



            case GappleShulkerPlaceLocPrep: {
                Vec3d curPos = new Vec3d(ctx.playerFeet().getX() + (7 * -highwayDirection.x), ctx.playerFeet().getY(), ctx.playerFeet().getZ() + (7 * -highwayDirection.z)); // Go back a bit just in case
                Vec3d direction = new Vec3d(highwayDirection.x, highwayDirection.y, highwayDirection.z);

                placeLoc = getClosestPoint(new Vec3d(backPathOriginVector.x, backPathOriginVector.y, backPathOriginVector.z), direction, curPos, LocationType.ShulkerEchestInteraction);
                // Get closest point

                settings.buildRepeat.value = new Vec3i(0, 0, 0);
                currentState = State.GoingToPlaceLocGappleShulker;
                break;
            }

            case GoingToPlaceLocGappleShulker: {
                if (baritone.getCustomGoalProcess().isActive()) {
                    return; // Wait to get there
                }

                if (getItemCountInventory(Item.getIdFromItem(Items.GOLDEN_APPLE)) >= settings.highwayGapplesToHave.value || getShulkerSlot(ShulkerType.Gapple) == -1) {
                    // We have enough gapples, or no more gapple shulker
                    currentState = State.Nothing;
                    return;
                }

                if (ctx.playerFeet().equals(placeLoc.add(highwayDirection.x, 0, highwayDirection.z))) {
                    // We have arrived
                    currentState = State.PlacingGappleShulker;
                    timer = 0;
                } else {
                    // Keep trying to get there
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(placeLoc.add(highwayDirection.x, 0, highwayDirection.z)));
                }

                break;
            }

            case PlacingGappleShulker: {
                if (!baritone.getBuilderProcess().isPaused() && baritone.getBuilderProcess().isActive()) {
                    timer = 0;
                    return; // Wait for build to complete
                }

                // Shulker box spot isn't air or shulker, lets fix that
                if (!(ctx.world().getBlockState(placeLoc).getBlock() instanceof BlockAir) && !(ctx.world().getBlockState(placeLoc).getBlock() instanceof BlockShulkerBox)) {
                    baritone.getPathingBehavior().cancelEverything();
                    baritone.getBuilderProcess().clearArea(placeLoc, placeLoc);
                    timer = 0;
                    return;
                }


                Optional<Rotation> shulkerReachable = RotationUtils.reachable(ctx.player(), placeLoc,
                        ctx.playerController().getBlockReachDistance());

                Optional<Rotation> underShulkerReachable = RotationUtils.reachable(ctx.player(), placeLoc.down(),
                        ctx.playerController().getBlockReachDistance());


                currentState = placeShulkerBox(shulkerReachable, underShulkerReachable, placeLoc, State.GoingToPlaceLocGappleShulker, currentState, State.OpeningGappleShulker, ShulkerType.Gapple);

                break;
            }

            case OpeningGappleShulker: {
                if (timer < 10) {
                    return;
                }

                Optional<Rotation> shulkerReachable = RotationUtils.reachable(ctx.player(), placeLoc,
                        ctx.playerController().getBlockReachDistance());
                shulkerReachable.ifPresent(rotation -> baritone.getLookBehavior().updateTarget(rotation, true));

                baritone.getInputOverrideHandler().clearAllKeys();
                if (!(mc.currentScreen instanceof GuiShulkerBox)) {
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

                if (!(mc.currentScreen instanceof GuiShulkerBox)) {
                    currentState = State.OpeningGappleShulker;
                    return;
                }

                if (getItemCountInventory(Item.getIdFromItem(Items.GOLDEN_APPLE)) < settings.highwayGapplesToHave.value) {
                    int gapplesLooted = lootGappleChestSlot();
                    if (gapplesLooted > 0) {
                        Helper.HELPER.logDirect("Looted " + gapplesLooted + " gapples");
                    } else {
                        Helper.HELPER.logDirect("Can't loot/empty shulker. Rolling with what we have.");
                        currentState = State.MiningGappleShulker;
                        ctx.player().closeScreen();
                    }

                    timer = 0;
                } else {
                    currentState = State.MiningGappleShulker;
                    ctx.player().closeScreen();
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
                if (!(ctx.world().getBlockState(placeLoc).getBlock() instanceof BlockAir)) {
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

                for (Entity entity : mc.world.loadedEntityList) {
                    if (entity instanceof EntityItem) {
                        if (((EntityItem) entity).getItem().getItem() instanceof ItemShulkerBox) {
                            if (getItemCountInventory(Item.getIdFromItem(Items.AIR)) == 0) {
                                // No space for shulker, need to do removal
                                currentState = State.InventoryCleaningGappleShulker;
                                timer = 0;
                            }
                            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(new BlockPos(entity.posX, entity.posY, entity.posZ)));
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

                int netherRackSlot = getItemSlot(Item.getIdFromItem(Item.getItemFromBlock(Blocks.NETHERRACK)));
                if (netherRackSlot == 8) {
                    netherRackSlot = getItemSlotNoHotbar(Item.getIdFromItem(Item.getItemFromBlock(Blocks.NETHERRACK)));
                }
                if (netherRackSlot == -1) {
                    return;
                }
                baritone.getLookBehavior().updateTarget(new Rotation(45, 0), true);
                ctx.playerController().windowClick(ctx.player().inventoryContainer.windowId, netherRackSlot < 9 ? netherRackSlot + 36 : netherRackSlot, 0, ClickType.PICKUP, ctx.player());
                ctx.playerController().windowClick(ctx.player().inventoryContainer.windowId, -999, 0, ClickType.PICKUP, ctx.player());
                mc.playerController.updateController();
                currentState = State.CollectingGappleShulker;
                break;
            }



            case ShulkerCollection: {
                if (baritone.getCustomGoalProcess().isActive()) {
                    return; // Wait for us to reach the goal
                }

                for (Entity entity : mc.world.loadedEntityList) {
                    if (entity instanceof EntityItem) {
                        if (((EntityItem) entity).getItem().getItem() instanceof ItemShulkerBox) {
                            if (getItemCountInventory(Item.getIdFromItem(Items.AIR)) == 0) {
                                // No space for shulker, need to do removal
                                currentState = State.InventoryCleaningShulkerCollection;
                                timer = 0;
                            }
                            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(new BlockPos(entity.posX, entity.posY, entity.posZ)));
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

                int netherRackSlot = getItemSlot(Item.getIdFromItem(Item.getItemFromBlock(Blocks.NETHERRACK)));
                if (netherRackSlot == 8) {
                    netherRackSlot = getItemSlotNoHotbar(Item.getIdFromItem(Item.getItemFromBlock(Blocks.NETHERRACK)));
                }
                if (netherRackSlot == -1) {
                    return;
                }
                baritone.getLookBehavior().updateTarget(new Rotation(45, 0), true);
                ctx.playerController().windowClick(ctx.player().inventoryContainer.windowId, netherRackSlot < 9 ? netherRackSlot + 36 : netherRackSlot, 0, ClickType.PICKUP, ctx.player());
                ctx.playerController().windowClick(ctx.player().inventoryContainer.windowId, -999, 0, ClickType.PICKUP, ctx.player());
                mc.playerController.updateController();
                currentState = State.ShulkerCollection;
                break;
            }



            case ShulkerSearchPrep: {
                Vec3d curPos = new Vec3d(ctx.playerFeet().getX() + (settings.highwayMaxLostShulkerSearchDist.value * -highwayDirection.x), ctx.playerFeet().getY(), ctx.playerFeet().getZ() + (settings.highwayMaxLostShulkerSearchDist.value * -highwayDirection.z));
                Vec3d direction = new Vec3d(highwayDirection.x, highwayDirection.y, highwayDirection.z);

                placeLoc = getClosestPoint(new Vec3d(backPathOriginVector.x, backPathOriginVector.y, backPathOriginVector.z), direction, curPos, LocationType.ShulkerEchestInteraction);
                // Get closest point and shift it so it's in the middle of the highway

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
                if (getItemCountInventory(Item.getIdFromItem(Item.getItemFromBlock(Blocks.ENDER_CHEST))) == 0) {
                    Helper.HELPER.logDirect("No ender chests, pausing");
                    paused = true;
                    return;
                }

                Helper.HELPER.logDirect("LootEnderChestPlaceLocPrep");
                Vec3d curPos = new Vec3d(ctx.playerFeet().getX() + (7 * -highwayDirection.x), ctx.playerFeet().getY(), ctx.playerFeet().getZ() + (7 * -highwayDirection.z)); // Go back a bit just in case
                Vec3d direction = new Vec3d(highwayDirection.x, highwayDirection.y, highwayDirection.z);

                placeLoc = getClosestPoint(new Vec3d(eChestEmptyShulkOriginVector.x, eChestEmptyShulkOriginVector.y, eChestEmptyShulkOriginVector.z), direction, curPos, LocationType.SideStorage);

                currentState = State.GoingToLootEnderChestPlaceLoc;
                break;
            }

            case GoingToLootEnderChestPlaceLoc: {
                if (baritone.getCustomGoalProcess().isActive()) {
                    return; // Wait to get there
                }

                Helper.HELPER.logDirect("GoingToLootEnderChestPlaceLoc");
                if (ctx.playerFeet().getDistance(placeLoc.getX(), placeLoc.getY(), placeLoc.getZ()) <= (ctx.playerController().getBlockReachDistance() - 1)) {
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
                if (Helper.mc.world.getBlockState(placeLoc.down()).getBlock() instanceof BlockAir) {
                    baritone.getBuilderProcess().build("supportBlock", new WhiteBlackSchematic(1, 1, 1, Arrays.asList(Blocks.AIR.getDefaultState(), Blocks.LAVA.getDefaultState(), Blocks.FLOWING_LAVA.getDefaultState()), Blocks.NETHERRACK.getDefaultState(), false, false), placeLoc.down());
                    return;
                }

                currentState = State.PlacingLootEnderChest;
                break;
            }

            case PlacingLootEnderChest: {
                if (!baritone.getBuilderProcess().isPaused() && baritone.getBuilderProcess().isActive()) {
                    return; // Wait for build to complete
                }

                Helper.HELPER.logDirect("PlacingLootEnderChest");
                baritone.getPathingBehavior().cancelEverything();
                settings.buildRepeat.value = new Vec3i(0, 0, 0);

                if (mc.world.getBlockState(placeLoc).getBlock() instanceof BlockEnderChest && mc.world.getBlockState(placeLoc.up()).getBlock() instanceof BlockAir) {
                    currentState = State.OpeningLootEnderChest;
                    timer = 0;
                    return;
                }
                if (mc.world.getBlockState(placeLoc).getBlock() instanceof BlockAir && mc.world.getBlockState(placeLoc.up()).getBlock() instanceof BlockAir) {
                    baritone.getBuilderProcess().build("enderChest", new FillSchematic(1, 1, 1, Blocks.ENDER_CHEST.getDefaultState()), placeLoc);
                }
                else {
                    baritone.getBuilderProcess().build("eChestPrep", new FillSchematic(1, 2, 1, Blocks.AIR.getDefaultState()), placeLoc);
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

                Helper.HELPER.logDirect("OpeningLootEnderChest");
                Optional<Rotation> enderChestReachable = RotationUtils.reachable(ctx.player(), placeLoc,
                        ctx.playerController().getBlockReachDistance());
                enderChestReachable.ifPresent(rotation -> baritone.getLookBehavior().updateTarget(rotation, true));

                baritone.getInputOverrideHandler().clearAllKeys();
                if (!(Helper.mc.currentScreen instanceof GuiChest)) {
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

                Helper.HELPER.logDirect("LootingLootEnderChestPicks");
                if (!(Helper.mc.currentScreen instanceof GuiChest)) {
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

                    //if (getItemCountInventory(Item.getIdFromItem(Items.AIR)) == 0) {
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
                Helper.HELPER.logDirect("LootingLootEnderChestEnderChests");
                if (!(Helper.mc.currentScreen instanceof GuiChest)) {
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
                        ctx.player().closeScreen();
                    }

                    //if (getItemCountInventory(Item.getIdFromItem(Items.AIR)) == 0) {
                    //    Helper.HELPER.logDirect("No space for ender chest shulkers. Rolling with what we have.");
                    //    currentState = State.LootingLootEnderChestGapples;
                    //    ctx.player().closeScreen();
                    //}

                    timer = 0;
                } else {
                    currentState = State.LootingLootEnderChestGapples;
                    ctx.player().closeScreen();
                }

                break;
            }

            case LootingLootEnderChestGapples: {
                // TODO: Finish this
                Helper.HELPER.logDirect("LootingLootEnderChestGapples");
                currentState = State.Nothing;
                break;
            }



            case EchestMiningPlaceLocPrep: {
                Vec3d curPos = new Vec3d(ctx.playerFeet().getX() + (7 * -highwayDirection.x), ctx.playerFeet().getY(), ctx.playerFeet().getZ() + (7 * -highwayDirection.z)); // Go back a bit just in case
                Vec3d direction = new Vec3d(highwayDirection.x, highwayDirection.y, highwayDirection.z);

                placeLoc = getClosestPoint(new Vec3d(backPathOriginVector.x, backPathOriginVector.y, backPathOriginVector.z), direction, curPos, LocationType.ShulkerEchestInteraction);
                // Get closest point

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
                if (getShulkerSlot(ShulkerType.EnderChest) == -1 && !(Helper.mc.world.getBlockState(placeLoc).getBlock() instanceof BlockShulkerBox)) {
                    Helper.HELPER.logDirect("Error getting shulker slot at PlacingEnderShulker. Restarting.");
                    currentState = State.Nothing;
                    return;
                }

                // Shulker box spot isn't air or shulker, lets fix that
                if (!(Helper.mc.world.getBlockState(placeLoc).getBlock() instanceof BlockAir) && !(Helper.mc.world.getBlockState(placeLoc).getBlock() instanceof BlockShulkerBox)) {
                    baritone.getPathingBehavior().cancelEverything();
                    baritone.getBuilderProcess().clearArea(placeLoc, placeLoc);
                    timer = 0;
                    return;
                }

                Optional<Rotation> shulkerReachable = RotationUtils.reachable(ctx.player(), placeLoc,
                        ctx.playerController().getBlockReachDistance());

                Optional<Rotation> underShulkerReachable = RotationUtils.reachable(ctx.player(), placeLoc.down(),
                        ctx.playerController().getBlockReachDistance());


                currentState = placeShulkerBox(shulkerReachable, underShulkerReachable, placeLoc, State.GoingToPlaceLocEnderShulker, currentState, State.OpeningEnderShulker, ShulkerType.EnderChest);

                break;
            }

            case OpeningEnderShulker: {
                if (timer < 10) {
                    return;
                }

                Optional<Rotation> shulkerReachable = RotationUtils.reachable(ctx.player(), placeLoc,
                        ctx.playerController().getBlockReachDistance());
                shulkerReachable.ifPresent(rotation -> baritone.getLookBehavior().updateTarget(rotation, true));

                if (mc.world.getBlockState(placeLoc).getBlock() instanceof BlockShulkerBox && !shulkerReachable.isPresent()) {
                    Helper.HELPER.logDirect("Shulker has been placed, but can't be reached, pathing closer.");
                    currentState = State.GoingToPlaceLocEnderShulker;
                    timer = 0;
                    return;
                }

                baritone.getInputOverrideHandler().clearAllKeys();
                if (!(Helper.mc.currentScreen instanceof GuiShulkerBox)) {
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

                if (!(Helper.mc.currentScreen instanceof GuiShulkerBox)) {
                    currentState = State.OpeningEnderShulker;
                    return;
                }

                if (getItemCountInventory(Item.getIdFromItem(Item.getItemFromBlock(Blocks.ENDER_CHEST))) < settings.highwayEnderChestsToLoot.value) {
                    int enderChestsLooted = lootEnderChestSlot();
                    if (enderChestsLooted > 0) {
                        Helper.HELPER.logDirect("Looted " + enderChestsLooted + " ender chests");
                    } else {
                        Helper.HELPER.logDirect("No more ender chests. Rolling with what we have.");
                        currentState = State.MiningEnderShulker;
                        ctx.player().closeScreen();
                    }

                    //if (getItemCountInventory(Item.getIdFromItem(Items.AIR)) == 0) {
                    //    Helper.HELPER.logDirect("No space for ender chests. Rolling with what we have.");
                    //    currentState = HighwayBuilderBehavior.State.MiningEnderShulker;
                    //    ctx.player().closeScreen();
                    //}

                    timer = 0;
                } else {
                    currentState = State.MiningEnderShulker;
                    ctx.player().closeScreen();
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
                if (!(Helper.mc.world.getBlockState(placeLoc).getBlock() instanceof BlockAir)) {
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

                for (Entity entity : mc.world.loadedEntityList) {
                    if (entity instanceof EntityItem) {
                        if (((EntityItem) entity).getItem().getItem() instanceof ItemShulkerBox) {
                            if (getItemCountInventory(Item.getIdFromItem(Items.AIR)) == 0) {
                                // No space for shulker, need to do removal
                                currentState = State.InventoryCleaningEnderShulker;
                                timer = 0;
                            }
                            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(new BlockPos(entity.posX, entity.posY, entity.posZ)));
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

                int netherRackSlot = getItemSlot(Item.getIdFromItem(Item.getItemFromBlock(Blocks.NETHERRACK)));
                if (netherRackSlot == 8) {
                    netherRackSlot = getItemSlotNoHotbar(Item.getIdFromItem(Item.getItemFromBlock(Blocks.NETHERRACK)));
                }
                if (netherRackSlot == -1) {
                    return;
                }
                baritone.getLookBehavior().updateTarget(new Rotation(45, 0), true);
                ctx.playerController().windowClick(ctx.player().inventoryContainer.windowId, netherRackSlot < 9 ? netherRackSlot + 36 : netherRackSlot, 0, ClickType.PICKUP, ctx.player());
                ctx.playerController().windowClick(ctx.player().inventoryContainer.windowId, -999, 0, ClickType.PICKUP, ctx.player());
                mc.playerController.updateController();
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
                    instantMineOriginalOffhandItem = mc.player.getHeldItemOffhand().getItem();
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

                if (mc.currentScreen instanceof GuiContainer) {
                    // Close container screen if it somehow didn't close
                    ctx.player().closeScreen();
                    timer = 0;
                    return;
                }

                Item origItem = mc.player.getHeldItemOffhand().getItem();
                if (!(origItem instanceof ItemBlock) || !(((ItemBlock) origItem).getBlock().equals(Blocks.ENDER_CHEST))) {
                    int eChestSlot = getLargestItemSlot(Item.getIdFromItem(Item.getItemFromBlock(Blocks.ENDER_CHEST)));
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

                int pickSlot = putItemHotbar(Item.getIdFromItem(Items.DIAMOND_PICKAXE));
                if (pickSlot == -1) {
                    Helper.HELPER.logDirect("Error getting pick slot");
                    currentState = State.Nothing;
                    return;
                }
                ItemStack stack = ctx.player().inventory.mainInventory.get(pickSlot);
                if (Item.getIdFromItem(stack.getItem()) == Item.getIdFromItem(Items.DIAMOND_PICKAXE)) {
                    ctx.player().inventory.currentItem = pickSlot;
                    mc.playerController.updateController();
                }

                if (!mc.world.getBlockState(placeLoc).getBlock().equals(Blocks.AIR)) {
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
                    Optional<Rotation> eChestReachable = RotationUtils.reachable(ctx.player(), placeLoc,
                            ctx.playerController().getBlockReachDistance());
                    eChestReachable.ifPresent(rotation -> baritone.getLookBehavior().updateTarget(rotation, true));
                    setTarget(placeLoc);
                    timer = 0;
                }

                int pickSlot = putItemHotbar(Item.getIdFromItem(Items.DIAMOND_PICKAXE));
                if (ctx.player().inventory.currentItem != pickSlot) {
                    currentState = State.FarmingEnderChestPrepEchest;
                    timer = 0;
                    return;
                }


                Item origItem = mc.player.getHeldItemOffhand().getItem();
                if ((getItemCountInventory(Item.getIdFromItem(Item.getItemFromBlock(Blocks.ENDER_CHEST))) + mc.player.getHeldItemOffhand().getCount()) <= settings.highwayEnderChestsToKeep.value) {
                    baritone.getInputOverrideHandler().clearAllKeys();
                    instantMinePlace = true;
                    instantMineActivated = false;
                    currentState = State.FarmingEnderChestSwapBack;
                    timer = 0;
                    return;
                }
                else if (!(origItem instanceof ItemBlock) || !(((ItemBlock) origItem).getBlock().equals(Blocks.ENDER_CHEST))) {
                    currentState = State.FarmingEnderChestPrepEchest;
                    timer = 0;
                    return;
                }

                // Force look at location
                if (mc.world.getBlockState(placeLoc).getBlock().equals(Blocks.AIR)) {
                    Optional<Rotation> shulkerReachable = RotationUtils.reachable(ctx.player(), placeLoc.down(),
                            ctx.playerController().getBlockReachDistance());
                    shulkerReachable.ifPresent(rotation -> baritone.getLookBehavior().updateTarget(rotation, true));
                }


                if (instantMinePlace /*&& placeLoc.down().equals(ctx.getSelectedBlock().orElse(null))*/) {
                    //baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    //baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);

                    final Vec3d pos = new Vec3d(mc.player.lastTickPosX + (mc.player.posX - mc.player.lastTickPosX) * mc.getRenderPartialTicks(),
                            mc.player.lastTickPosY + (mc.player.posY - mc.player.lastTickPosY) * mc.getRenderPartialTicks(),
                            mc.player.lastTickPosZ + (mc.player.posZ - mc.player.lastTickPosZ) * mc.getRenderPartialTicks());
                    BlockPos originPos = new BlockPos(pos.x, pos.y+0.5f, pos.z);
                    double l_Offset = pos.y - originPos.getY();
                    PlaceResult l_Place = place(placeLoc, 5.0f, false, l_Offset == -0.5f, EnumHand.OFF_HAND);

                    if (l_Place != PlaceResult.Placed)
                        return;
                    instantMinePlace = false;
                    timer = 0;
                    return;
                }
                if (!instantMinePlace) {
                    //baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
                    //if (mc.world.getBlockState(placeLoc).getBlock().equals(Blocks.ENDER_CHEST)) {
                    if (!instantMineActivated) {
                        currentState = State.FarmingEnderChestPrepPick;
                    }
                    if (instantMineLastBlock != null) {
                        if (mc.player.getHeldItem(EnumHand.MAIN_HAND).getItem() == Items.DIAMOND_PICKAXE) {
                            mc.player.connection.sendPacket(new CPacketPlayerDigging(CPacketPlayerDigging.Action.STOP_DESTROY_BLOCK,
                                    instantMineLastBlock, instantMineDirection));
                        }
                    }

                    try {
                        Field delay = PlayerControllerMP.class.getDeclaredField("blockHitDelay");
                        delay.setAccessible(true);
                        delay.set(mc.playerController, 0);
                    } catch (Exception ignored) {}

                    instantMinePlace = true;
                    //}
                }

/*
                if (mc.world.getBlockState(placeLoc).getBlock().equals(Blocks.AIR) && placeLoc.down().equals(ctx.getSelectedBlock().orElse(null))) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    timer = 0;
                    return;
                }
*/

                break;
            }

            case FarmingEnderChestSwapBack: {
                if (timer < 10) {
                    return;
                }
                Item curItem = mc.player.getHeldItemOffhand().getItem();
                if (!curItem.equals(instantMineOriginalOffhandItem)) {
                    int origItemSlot = getItemSlot(Item.getIdFromItem(instantMineOriginalOffhandItem));
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

                if (!(Helper.mc.world.getBlockState(placeLoc).getBlock() instanceof BlockAir)) {
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

                for (Entity entity : mc.world.loadedEntityList) {
                    if (entity instanceof EntityItem) {
                        if (((EntityItem) entity).getItem().getItem() instanceof ItemBlock &&
                                ((ItemBlock) ((EntityItem) entity).getItem().getItem()).getBlock() instanceof BlockObsidian) {
                            double obsidDistance = ctx.playerFeet().getDistance((int) entity.posX, (int) entity.posY, (int) entity.posZ);
                            if (obsidDistance <= settings.highwayObsidianMaxSearchDist.value) {
                                if (getItemCountInventory(Item.getIdFromItem(Items.AIR)) == 0) {
                                    // No space for obsid, need to do removal
                                    currentState = State.InventoryCleaningObsidian;
                                    timer = 0;
                                }
                                baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(new BlockPos(entity.posX, entity.posY, entity.posZ)));
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

                int netherRackSlot = getItemSlot(Item.getIdFromItem(Item.getItemFromBlock(Blocks.NETHERRACK)));
                if (netherRackSlot == 8) {
                    netherRackSlot = getItemSlotNoHotbar(Item.getIdFromItem(Item.getItemFromBlock(Blocks.NETHERRACK)));
                }
                if (netherRackSlot == -1) {
                    currentState = State.CollectingObsidian;
                    return;
                }
                baritone.getLookBehavior().updateTarget(new Rotation(45, 0), true);
                ctx.playerController().windowClick(ctx.player().inventoryContainer.windowId, netherRackSlot < 9 ? netherRackSlot + 36 : netherRackSlot, 0, ClickType.PICKUP, ctx.player());
                ctx.playerController().windowClick(ctx.player().inventoryContainer.windowId, -999, 0, ClickType.PICKUP, ctx.player());
                mc.playerController.updateController();
                currentState = State.CollectingObsidian;
                timer = 0;
                break;
            }



            case EmptyShulkerPlaceLocPrep: {
                if (getShulkerSlot(ShulkerType.Empty) == -1) {
                    currentState = State.Nothing;
                    return;
                }

                Helper.HELPER.logDirect("EmptyShulkerPlaceLocPrep");
                Vec3d curPos = new Vec3d(ctx.playerFeet().getX() + (7 * -highwayDirection.x), ctx.playerFeet().getY(), ctx.playerFeet().getZ() + (7 * -highwayDirection.z)); // Go back a bit just in case
                Vec3d direction = new Vec3d(highwayDirection.x, highwayDirection.y, highwayDirection.z);

                placeLoc = getClosestPoint(new Vec3d(eChestEmptyShulkOriginVector.x, eChestEmptyShulkOriginVector.y, eChestEmptyShulkOriginVector.z), direction, curPos, LocationType.SideStorage);

                currentState = State.GoingToEmptyShulkerPlaceLoc;
                break;
            }

            case GoingToEmptyShulkerPlaceLoc: {
                if (baritone.getCustomGoalProcess().isActive()) {
                    return; // Wait to get there
                }

                Helper.HELPER.logDirect("GoingToEmptyShulkerPlaceLoc");
                if (ctx.playerFeet().getDistance(placeLoc.getX(), placeLoc.getY(), placeLoc.getZ()) <= (ctx.playerController().getBlockReachDistance() - 1)) {
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
                if (Helper.mc.world.getBlockState(placeLoc.down()).getBlock() instanceof BlockAir) {
                    baritone.getBuilderProcess().build("supportBlock", new WhiteBlackSchematic(1, 1, 1, Arrays.asList(Blocks.AIR.getDefaultState(), Blocks.LAVA.getDefaultState(), Blocks.FLOWING_LAVA.getDefaultState()), Blocks.NETHERRACK.getDefaultState(), false, false), placeLoc.down());
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

                Helper.HELPER.logDirect("PlacingEmptyShulker");
                baritone.getPathingBehavior().cancelEverything();
                // Shulker box spot isn't air or shulker, lets fix that
                if (!(Helper.mc.world.getBlockState(placeLoc).getBlock() instanceof BlockAir) && !(Helper.mc.world.getBlockState(placeLoc).getBlock() instanceof BlockShulkerBox)) {
                    baritone.getPathingBehavior().cancelEverything();
                    baritone.getBuilderProcess().clearArea(placeLoc, placeLoc.up());
                    timer = 0;
                    return;
                }


                Optional<Rotation> shulkerReachable = RotationUtils.reachable(ctx.player(), placeLoc,
                        ctx.playerController().getBlockReachDistance());

                Optional<Rotation> underShulkerReachable = RotationUtils.reachable(ctx.player(), placeLoc.down(),
                        ctx.playerController().getBlockReachDistance());

                currentState = placeShulkerBox(shulkerReachable, underShulkerReachable, placeLoc, State.GoingToEmptyShulkerPlaceLoc, currentState, State.Nothing, ShulkerType.Empty);
                if (currentState == State.Nothing) {
                    Helper.HELPER.logDirect("Lowering startShulkerCount from " + startShulkerCount + " to " + --startShulkerCount);
                }
                //else if (getShulkerSlot(ShulkerType.Empty) == -1) {
                    // Shulker has either been placed, or we dropped it
                //    Helper.HELPER.logDirect("Lowering startShulkerCount from " + startShulkerCount + " to " + --startShulkerCount);
                //}

                if (!shulkerReachable.isPresent() && !underShulkerReachable.isPresent()) {
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
                    done = baritone.getBuilderProcess().checkNoEntityCollision(new AxisAlignedBB(boatLocation), mc.player) && baritone.getBuilderProcess().checkNoEntityCollision(new AxisAlignedBB(boatLocation.down()), mc.player);
                } else {
                    done = baritone.getBuilderProcess().checkNoEntityCollision(new AxisAlignedBB(boatLocation), mc.player);
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
                //FillSchematic toClear = new FillSchematic(4, depth, 4, Blocks.AIR.getDefaultState());
                baritone.getBuilderProcess().clearArea(boatLocation.add(2, 1, 2), boatLocation.add(-2, -depth, -2));

                // Check if boat is still there
                //if (!baritone.getBuilderProcess().checkNoEntityCollision(new AxisAlignedBB(boatLocation), mc.player)) {
                    //baritone.getBuilderProcess().build("boatClearing", toClear, boatLocation.add(-1, yOffset, -3));
                    //return;
                //}


                break;
            }
        }
    }

    private void swapOffhand(int slot) {
        mc.playerController.windowClick(0, 45, 0, ClickType.PICKUP, mc.player);
        mc.playerController.windowClick(0, slot < 9 ? slot + 36 : slot, 0, ClickType.PICKUP, mc.player);
        mc.playerController.windowClick(0, 45, 0, ClickType.PICKUP, mc.player);
    }

    private boolean checkForNeighbours(BlockPos blockPos)
    {
        // check if we don't have a block adjacent to blockpos
        if (!hasNeighbour(blockPos))
        {
            // find air adjacent to blockpos that does have a block adjacent to it, let's fill this first as to form a bridge between the player and the original blockpos. necessary if the player is
            // going diagonal.
            for (EnumFacing side : EnumFacing.values())
            {
                BlockPos neighbour = blockPos.offset(side);
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
        for (EnumFacing side : EnumFacing.values())
        {
            BlockPos neighbour = blockPos.offset(side);
            if (!Minecraft.getMinecraft().world.getBlockState(neighbour).getMaterial().isReplaceable())
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

    private PlaceResult place(BlockPos pos, float p_Distance, boolean p_Rotate, boolean p_UseSlabRule, EnumHand hand) {
        return place(pos, p_Distance, p_Rotate, p_UseSlabRule, false, hand);
    }

    private PlaceResult place(BlockPos pos, float p_Distance, boolean p_Rotate, boolean p_UseSlabRule, boolean packetSwing, EnumHand hand) {
        IBlockState l_State = mc.world.getBlockState(pos);

        boolean l_Replaceable = l_State.getMaterial().isReplaceable();

        boolean l_IsSlabAtBlock = l_State.getBlock() instanceof BlockSlab;

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
            if (l_IsSlabAtBlock && !l_State.isFullCube())
                return PlaceResult.CantPlace;
        }

        final Vec3d eyesPos = new Vec3d(mc.player.posX, mc.player.posY + mc.player.getEyeHeight(), mc.player.posZ);

        for (final EnumFacing side : EnumFacing.values())
        {
            final BlockPos neighbor = pos.offset(side);
            final EnumFacing side2 = side.getOpposite();

            //boolean l_IsWater = mc.world.getBlockState(neighbor).getBlock() == Blocks.WATER;

            if (mc.world.getBlockState(neighbor).getBlock().canCollideCheck(mc.world.getBlockState(neighbor), false))
            {
                final Vec3d hitVec = new Vec3d(neighbor).add(0.5, 0.5, 0.5).add(new Vec3d(side2.getDirectionVec()).scale(0.5));
                if (eyesPos.distanceTo(hitVec) <= p_Distance)
                {
                    final Block neighborPos = mc.world.getBlockState(neighbor).getBlock();

                    final boolean activated = neighborPos.onBlockActivated(mc.world, pos, mc.world.getBlockState(pos), mc.player, hand, side, 0, 0, 0);

                    if (blackList.contains(neighborPos) || shulkerList.contains(neighborPos) || activated)
                    {
                        mc.player.connection.sendPacket(new CPacketEntityAction(mc.player, CPacketEntityAction.Action.START_SNEAKING));
                    }
                    if (p_Rotate)
                    {
                        faceVectorPacketInstant(hitVec);
                    }
                    EnumActionResult l_Result2 = mc.playerController.processRightClickBlock(mc.player, mc.world, neighbor, side2, hitVec, hand);

                    if (l_Result2 != EnumActionResult.FAIL)
                    {
                        if (packetSwing)
                            mc.player.connection.sendPacket(new CPacketAnimation(hand));
                        else
                            mc.player.swingArm(hand);
                        if (activated)
                        {
                            mc.player.connection.sendPacket(new CPacketEntityAction(mc.player, CPacketEntityAction.Action.STOP_SNEAKING));
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
        if (!mc.world.checkNoEntityCollision(new AxisAlignedBB(pos)))
            return ValidResult.NoEntityCollision;

        if (!checkForNeighbours(pos))
            return ValidResult.NoNeighbors;

        IBlockState l_State = mc.world.getBlockState(pos);

        if (l_State.getBlock() == Blocks.AIR)
        {
            final BlockPos[] l_Blocks =
                    { pos.north(), pos.south(), pos.east(), pos.west(), pos.up(), pos.down() };

            for (BlockPos l_Pos : l_Blocks)
            {
                IBlockState l_State2 = mc.world.getBlockState(l_Pos);

                if (l_State2.getBlock() == Blocks.AIR)
                    continue;

                for (final EnumFacing side : EnumFacing.values())
                {
                    final BlockPos neighbor = pos.offset(side);

                    boolean l_IsWater = mc.world.getBlockState(neighbor).getBlock() == Blocks.WATER;

                    if (mc.world.getBlockState(neighbor).getBlock().canCollideCheck(mc.world.getBlockState(neighbor), false))
                    {
                        return ValidResult.Ok;
                    }
                }
            }

            return ValidResult.NoNeighbors;
        }

        return ValidResult.AlreadyBlockThere;
    }

    private float[] getLegitRotations(Vec3d vec) {
        Vec3d eyesPos = getEyesPos();

        double diffX = vec.x - eyesPos.x;
        double diffY = vec.y - eyesPos.y;
        double diffZ = vec.z - eyesPos.z;

        double diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);

        float yaw = (float) Math.toDegrees(Math.atan2(diffZ, diffX)) - 90F;
        float pitch = (float) -Math.toDegrees(Math.atan2(diffY, diffXZ));

        return new float[]
                { Minecraft.getMinecraft().player.rotationYaw + MathHelper.wrapDegrees(yaw - Minecraft.getMinecraft().player.rotationYaw),
                        Minecraft.getMinecraft().player.rotationPitch + MathHelper.wrapDegrees(pitch - Minecraft.getMinecraft().player.rotationPitch) };
    }

    private Vec3d getEyesPos() {
        return new Vec3d(Minecraft.getMinecraft().player.posX, Minecraft.getMinecraft().player.posY + Minecraft.getMinecraft().player.getEyeHeight(), Minecraft.getMinecraft().player.posZ);
    }

    private void faceVectorPacketInstant(Vec3d vec) {
        float[] rotations = getLegitRotations(vec);

        Minecraft.getMinecraft().player.connection.sendPacket(new CPacketPlayer.Rotation(rotations[0], rotations[1], Minecraft.getMinecraft().player.onGround));
    }

    private void setTarget(BlockPos pos) {
        instantMinePacketCancel = false;
        mc.player.connection.sendPacket(new CPacketPlayerDigging(CPacketPlayerDigging.Action.START_DESTROY_BLOCK,
                pos, EnumFacing.DOWN));
        instantMinePacketCancel = true;
        mc.player.connection.sendPacket(new CPacketPlayerDigging(CPacketPlayerDigging.Action.STOP_DESTROY_BLOCK,
                pos, EnumFacing.DOWN));
        instantMineDirection = EnumFacing.DOWN;
        instantMineLastBlock = pos;
    }

    private void startHighwayBuild() {
        Helper.HELPER.logDirect("Starting highway build");

        settings.buildRepeat.value = new Vec3i(highwayDirection.x, 0, highwayDirection.z);

        Vec3d origin = new Vec3d(originVector.x, originVector.y, originVector.z);
        Vec3d direction = new Vec3d(highwayDirection.x, highwayDirection.y, highwayDirection.z);
        Vec3d curPos = new Vec3d(ctx.playerFeet().getX() + (highwayCheckBackDistance * -highwayDirection.x), ctx.playerFeet().getY(), ctx.playerFeet().getZ() + (highwayCheckBackDistance * -highwayDirection.z)); // Go back a bit to clear up our mess
        originBuild = getClosestPoint(origin, direction, curPos, LocationType.HighwayBuild);


        baritone.getPathingBehavior().cancelEverything();
        baritone.getBuilderProcess().build("netherHighway", schematic, originBuild);
        currentState = State.BuildingHighway;
    }

    private BetterBlockPos getClosestPoint(Vec3d origin, Vec3d direction, Vec3d point, LocationType locType) {
        // https://stackoverflow.com/a/51906100

        int yLevel = 120;
        switch (locType) {
            case HighwayBuild:
                yLevel = highwayLowestY;
                break;
            case ShulkerEchestInteraction:
                yLevel = paving ? mainHighwayY + 1 : mainHighwayY;
                break;
            case SideStorage:
                yLevel = emptyShulkEchestY;
                break;
        }
        //forHighwayBuild ? highwayLowestY : mainHighwayY;
        //if (paving && !forHighwayBuild) {
        //    yLevel++;
        //}

        //Vec3d directBackup = new Vec3d(direction.x, direction.y, direction.z);
        // Never allow points behind the original starting location
        if (firstStartingPos != null &&
                ((direction.z == -1 && point.z > firstStartingPos.z) || // NW, N, NE
                (direction.z == 1 && point.z < firstStartingPos.z) || // SE, S, SW
                (direction.x == -1 && direction.z == 0 && point.x > firstStartingPos.x) || // W
                (direction.x == 1 && direction.z == 0 && point.x < firstStartingPos.x))) { // E
            point = new Vec3d(firstStartingPos.getX(), firstStartingPos.getY(), firstStartingPos.getZ());
        }

        direction = direction.normalize();
        Vec3d lhs = point.subtract(origin);
        double dotP = lhs.dotProduct(direction);
        Vec3d closest = origin.add(direction.scale(dotP));
        return new BetterBlockPos(Math.round(closest.x), yLevel, Math.round(closest.z));
        /*
        boolean diag = direction.x != 0 && direction.z != 0;
        direction = direction.normalize();

        Vec3d lhs = point.subtract(origin);
        //Vec3d lhs = new Vec3d(point.getX() - origin.getX(), point.getY() - origin.getY(), point.getZ() - origin.getZ()); //Vector2f.sub(point, origin, null);

        double dotP = lhs.dotProduct(direction);
        Vec3d scaled = direction.scale(dotP);
        Vec3d closestPos =  origin.add(scaled); //new Vec3d(origin.getX() + scaled.x, origin.getY() + scaled.y, origin.getZ() + scaled.z);//  Vector2f.add(origin, direction.scale(dotP), null);

        int iClosestPosX = (int) closestPos.x;
        int iClosestPosZ = (int) closestPos.z;

        if (directBackup.x == 0) {
            if (iClosestPosX != origin.x) {
                Helper.HELPER.logDirect("New X pos doesn't match origin: " + iClosestPosX + " Origin: " + origin.x);
            }
            iClosestPosX = (int) origin.x;
        }
        else if (directBackup.z == 0) {
            if (iClosestPosZ != origin.z) {
                Helper.HELPER.logDirect("New Z pos doesn't match origin: " + iClosestPosZ + " Origin: " + origin.z);
            }
            iClosestPosZ = (int) origin.z;
        }

        if (diag) {
            int absX = Math.abs(iClosestPosX);
            int absY = Math.abs(iClosestPosZ);
            if ((absX + Math.abs(origin.x)) == (absY + Math.abs(origin.z))) {
                return new BetterBlockPos(iClosestPosX, yLevel, iClosestPosZ);
            } else {
                Helper.HELPER.logDirect("Was an issue calculating start position. New position will be shifted from requested position");
                Helper.HELPER.logDirect("Player position: " + ctx.playerFeet());
                Helper.HELPER.logDirect("New start position: " + new BlockPos((absY + origin.x) * highwayDirection.x, yLevel, (absY + origin.z) * highwayDirection.z));
                return new BetterBlockPos((absY + origin.x) * highwayDirection.x, yLevel, (absY + origin.z) * highwayDirection.z);
            }
        }
        return new BetterBlockPos(iClosestPosX, yLevel, iClosestPosZ);
        */
    }

    private int getLargestItemSlot(int itemId) {
        int largestSlot = -1;
        int largestCount = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = ctx.player().inventory.mainInventory.get(i);
            if (Item.getIdFromItem(stack.getItem()) == itemId && stack.getCount() > largestCount) {
                largestSlot = i;
                largestCount = stack.getCount();
            }
        }

        return largestSlot;
    }

    private int getItemSlot(int itemId) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = ctx.player().inventory.mainInventory.get(i);
            if (Item.getIdFromItem(stack.getItem()) == itemId) {
                return i;
            }
        }

        return -1;
    }

    private int getItemSlotNoHotbar(int itemId) {
        for (int i = 9; i < 36; i++) {
            ItemStack stack = ctx.player().inventory.mainInventory.get(i);
            if (Item.getIdFromItem(stack.getItem()) == itemId) {
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

    private ArrayList<BlockPos> highwayObsidToPlace() {
        ArrayList<BlockPos> toPlace = new ArrayList<>();

        Vec3d direction = new Vec3d(highwayDirection.x, highwayDirection.y, highwayDirection.z);
        Vec3d curPlayerPos = new Vec3d(ctx.playerFeet().getX() + (-highwayDirection.x), ctx.playerFeet().getY(), ctx.playerFeet().getZ() + (-highwayDirection.z));
        BlockPos startCheckPos = getClosestPoint(new Vec3d(originVector.x, originVector.y, originVector.z), direction, curPlayerPos, LocationType.HighwayBuild);
        int distanceToCheckAhead = 5;
        for (int i = 1; i < distanceToCheckAhead; i++) {
            BlockPos curPos = startCheckPos.add(i * (int)highwayDirection.x, 0, i * (int)highwayDirection.z);
            for (int y = 0; y < schematic.heightY(); y++) {
                for (int z = 0; z < schematic.lengthZ(); z++) {
                    for (int x = 0; x < schematic.widthX(); x++) {
                        int blockX = x + curPos.getX();
                        int blockY = y + curPos.getY();
                        int blockZ = z + curPos.getZ();
                        IBlockState current = ctx.world().getBlockState(new BlockPos(blockX, blockY, blockZ));

                        if (!schematic.inSchematic(x, y, z, current)) {
                            continue;
                        }

                        if (baritone.bsi.worldContainsLoadedChunk(blockX, blockZ)) { // check if its in render distance, not if its in cache
                            // we can directly observe this block, it is in render distance

                            ISchematic ourSchem = schematic.getSchematic(x, y, z, current).schematic;
                            if (ourSchem instanceof WhiteBlackSchematic && ((WhiteBlackSchematic) ourSchem).isValidIfUnder() &&
                                    ctx.world().getBlockState(new BlockPos(blockX, blockY + 1, blockZ)).isFullBlock()) {
                                continue;
                            }

                            IBlockState desiredState = schematic.desiredState(x, y, z, current, this.approxPlaceable);
                            if (!desiredState.equals(current)) {
                                // If liquids we have nothing to place so bot can remove the liquids
                                if (current.getBlock() instanceof BlockLiquid) {
                                    return new ArrayList<>();
                                } else if (desiredState.getBlock() instanceof BlockObsidian) {
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

    private HighwayState isHighwayCorrect(BlockPos startPos, BlockPos startPosLiq, int distanceToCheck, boolean renderLiquidScan) {
        // startPos needs to be in center of highway
        //renderLock.lock();
        //renderBlocks.clear();
        boolean foundBlocks = false;
        for (int i = 1; i < distanceToCheck; i++) {
            BlockPos curPos = startPos.add(i * (int)highwayDirection.x, 0, i * (int)highwayDirection.z);
            for (int y = 0; y < schematic.heightY(); y++) {
                for (int z = 0; z < schematic.lengthZ(); z++) {
                    for (int x = 0; x < schematic.widthX(); x++) {
                        int blockX = x + curPos.getX();
                        int blockY = y + curPos.getY();
                        int blockZ = z + curPos.getZ();
                        IBlockState current = ctx.world().getBlockState(new BlockPos(blockX, blockY, blockZ));

                        if (!schematic.inSchematic(x, y, z, current)) {
                            continue;
                        }
                        //if (i == 1)
                            //renderBlocks.add(new BlockPos(blockX, blockY, blockZ));
                        if (baritone.bsi.worldContainsLoadedChunk(blockX, blockZ)) { // check if its in render distance, not if its in cache
                            // we can directly observe this block, it is in render distance

                            ISchematic ourSchem = schematic.getSchematic(x, y, z, current).schematic;
                            if (ourSchem instanceof WhiteBlackSchematic && ((WhiteBlackSchematic) ourSchem).isValidIfUnder() &&
                                    ctx.world().getBlockState(new BlockPos(blockX, blockY + 1, blockZ)).isFullBlock()) {
                                continue;
                            }

                            if (!schematic.desiredState(x, y, z, current, this.approxPlaceable).equals(current)) {
                                if (!baritone.getBuilderProcess().checkNoEntityCollision(new AxisAlignedBB(new BlockPos(blockX, blockY, blockZ)), mc.player)) {
                                    List<Entity> entityList = ctx.world().getEntitiesWithinAABBExcludingEntity((Entity) null, new AxisAlignedBB(new BlockPos(blockX, blockY, blockZ)));
                                    for (Entity entity : entityList) {
                                        if (entity instanceof EntityBoat || entity.isRiding()) {
                                            // can't do boats lol
                                            boatHasPassenger = entity.isBeingRidden() || entity.isRiding();
                                            boatLocation = new BlockPos(blockX, blockY, blockZ);
                                            return HighwayState.Boat;
                                        }
                                    }
                                }

                                // Never should be liquids
                                if (current.getBlock() instanceof BlockLiquid) {
                                    //renderLock.unlock();
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
        //renderLock.unlock();

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
        Entity player = mc.getRenderViewEntity();
        if (player == null) {
            return;
        }
        renderLock.lock();
        //PathRenderer.drawManySelectionBoxes(mc.getRenderViewEntity(), renderBlocks, Color.CYAN);
        IRenderer.startLines(Color.RED, 2, settings.renderSelectionBoxesIgnoreDepth.value);

        //BlockPos blockpos = movingObjectPositionIn.getBlockPos();
        BlockStateInterface bsi = new BlockStateInterface(BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext()); // TODO this assumes same dimension between primary baritone and render view? is this safe?

        renderBlocks.forEach(pos -> {
            IBlockState state = bsi.get0(pos);
            AxisAlignedBB toDraw;

            if (state.getBlock().equals(Blocks.AIR)) {
                toDraw = Blocks.DIRT.getDefaultState().getSelectedBoundingBox(player.world, pos);
            } else {
                toDraw = state.getSelectedBoundingBox(player.world, pos);
            }

            IRenderer.drawAABB(toDraw, .002D);
        });

        IRenderer.endLines(settings.renderSelectionBoxesIgnoreDepth.value);


        renderLock.unlock();
    }

    private BlockPos findFirstLiquidGround(BlockPos startPos, int distanceToCheck, boolean renderCheckedBlocks) {
        if (renderCheckedBlocks) {
            renderLock.lock();
            renderBlocks.clear();
        }
        //Liquid Checking all around
        for (int i = 1; i < distanceToCheck; i++) {
            BlockPos curPos = startPos.add(i * highwayDirection.x, 0, i * highwayDirection.z);
            for (int y = 0; y < liqCheckSchem.heightY(); y++) {
                for (int z = 0; z < liqCheckSchem.lengthZ(); z++) {
                    for (int x = 0; x < liqCheckSchem.widthX(); x++) {
                        int blockX = x + curPos.getX();
                        int blockY = y + curPos.getY();
                        int blockZ = z + curPos.getZ();
                        IBlockState current = ctx.world().getBlockState(new BlockPos(blockX, blockY, blockZ));
                        if (!liqCheckSchem.inSchematic(x, y, z, current)) {
                            continue;
                        }
                        if (renderCheckedBlocks) {
                            //if (i == 1)
                            renderBlocks.add(new BlockPos(blockX, blockY, blockZ));
                        }
                        if (baritone.bsi.worldContainsLoadedChunk(blockX, blockZ)) { // check if its in render distance, not if its in cache
                            // Never should be liquids
                            if (current.getBlock() instanceof BlockLiquid) {
                                if (renderCheckedBlocks) {
                                    renderLock.unlock();
                                }
                                return new BlockPos(blockX, blockY, blockZ);
                            }
                        }

                    }
                }
            }
        }
        if (renderCheckedBlocks) {
            renderLock.unlock();
        }
        return null;
    }

    private HighwayState getIssueType(BlockPos blockPos) {
        IBlockState state = ctx.world().getBlockState(blockPos);
        if (state.getBlock() instanceof BlockAir) {
            return HighwayState.Air;
        }
        if (state.getBlock() instanceof BlockLiquid) {
            return HighwayState.Liquids;
        }

        return HighwayState.Blocks; // Not air and not liquids so it's some block
    }

    private void findSourceLiquid(int x, int y, int z, ArrayList<BlockPos> checked, ArrayList<BlockPos> sourceBlocks, ArrayList<BlockPos> flowingBlocks) {
        IBlockState curBlockState = ctx.world().getBlockState(new BlockPos(x, y, z));

        if (!(curBlockState.getBlock() instanceof BlockLiquid)) {
            return;
        }

        if (curBlockState.getBlock() instanceof BlockLiquid) {
            if (curBlockState.getValue(BlockLiquid.LEVEL) == 0) {
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
        if (getIssueType(blockPos.up()) != HighwayState.Blocks) {
            return false;
        }
        return getIssueType(blockPos.down()) == HighwayState.Blocks;
    }

    private NonNullList<ItemStack> getShulkerContents(ItemStack shulker) {
        NonNullList<ItemStack> contents = NonNullList.withSize(27, ItemStack.EMPTY);
        NBTTagCompound nbtTagCompound = shulker.getTagCompound();
        if (nbtTagCompound != null && nbtTagCompound.hasKey("BlockEntityTag", 10)) {
            NBTTagCompound nbtTagCompoundTags = nbtTagCompound.getCompoundTag("BlockEntityTag");
            if (nbtTagCompoundTags.hasKey("Items", 9)) {
                ItemStackHelper.loadAllItems(nbtTagCompoundTags, contents);
            }
        }

        return contents;
    }

    private boolean isPickaxeShulker(ItemStack shulker) {
        NonNullList<ItemStack> contents = getShulkerContents(shulker);

        boolean foundPickaxe = false;
        for (ItemStack curStack : contents) {
            if (curStack.getItem() instanceof ItemPickaxe) {
                foundPickaxe = true;
            } else if (!(curStack.getItem() instanceof ItemAir)) {
                return false; // Found a non pickaxe and non air item
            }
        }

        return foundPickaxe;
    }

    private boolean isNonSilkPickShulker(ItemStack shulker) {
        NonNullList<ItemStack> contents = getShulkerContents(shulker);

        boolean foundPickaxe = false;
        for (ItemStack curStack : contents) {
            if (curStack.getItem() instanceof ItemPickaxe) {
                foundPickaxe = true;

                if (hasEnchant(curStack, Enchantments.SILK_TOUCH)) {
                    // Pickaxe is enchanted with silk touch
                    return false;
                }
            } else if (!(curStack.getItem() instanceof ItemAir)) {
                return false; // Found a non pickaxe and non air item
            }
        }

        return foundPickaxe;
    }

    private boolean hasEnchant(ItemStack stack, Enchantment enchantment) {
        if (stack == null) {
            return false;
        }
        NBTTagList tagList = stack.getEnchantmentTagList();
        for (int i = 0; i < tagList.tagCount(); i++) {
            NBTTagCompound tagCompound = tagList.getCompoundTagAt(i);
            if (tagCompound.getShort("id") == Enchantment.getEnchantmentID(enchantment)) {
                return true;
            }
        }
        return false;
    }

    private boolean isEnderChestShulker(ItemStack shulker) {
        NonNullList<ItemStack> contents = getShulkerContents(shulker);

        boolean foundEnderChest = false;
        for (ItemStack curStack : contents) {
            if (Item.getIdFromItem(curStack.getItem()) != Item.getIdFromItem(Items.AIR) && Item.getIdFromItem(curStack.getItem()) != Item.getIdFromItem(Item.getItemFromBlock(Blocks.ENDER_CHEST))) {
                return false;
            }

            if (Item.getIdFromItem(curStack.getItem()) == Item.getIdFromItem(Item.getItemFromBlock(Blocks.ENDER_CHEST))) {
                foundEnderChest = true;
            }
        }
        return foundEnderChest;
    }

    private boolean isGappleShulker(ItemStack shulker) {
        NonNullList<ItemStack> contents = getShulkerContents(shulker);

        boolean foundGapple = false;
        for (ItemStack curStack : contents) {
            if (Item.getIdFromItem(curStack.getItem()) != Item.getIdFromItem(Items.AIR) && Item.getIdFromItem(curStack.getItem()) != Item.getIdFromItem(Items.GOLDEN_APPLE)) {
                return false;
            }

            if (Item.getIdFromItem(curStack.getItem()) == Item.getIdFromItem(Items.GOLDEN_APPLE)) {
                foundGapple = true;
            }
        }
        return foundGapple;
    }

    private boolean isEmptyShulker(ItemStack shulker) {
        NonNullList<ItemStack> contents = getShulkerContents(shulker);

        for (ItemStack curStack : contents) {
            if (!(curStack.getItem() instanceof ItemAir)) {
                // Not air so shulker contains something
                return false;
            }
        }

        // Didn't find anything in the shulker, so it's empty
        return true;
    }

    private int getShulkerSlot(ShulkerType shulkerType) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = ctx.player().inventory.mainInventory.get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof ItemBlock)) {
                continue;
            }
            if (stack.getItem() instanceof ItemShulkerBox) {
                switch (shulkerType) {
                    case AnyPickaxe:
                        if (isPickaxeShulker(stack)) {
                            return i;
                        }
                        break;

                    case Gapple:
                        if (isGappleShulker(stack)) {
                            return i;
                        }
                        break;

                    case NonSilkPickaxe:
                        if (isNonSilkPickShulker(stack)) {
                            return i;
                        }
                        break;

                    case EnderChest:
                        if (isEnderChestShulker(stack)) {
                            return i;
                        }
                        break;

                    case Empty:
                        if (isEmptyShulker(stack)) {
                            return i;
                        }
                        break;
                }
            }
        }

        return -1;
    }

    private int getItemCountInventory(int itemId) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = ctx.player().inventory.mainInventory.get(i);
            if (Item.getIdFromItem(stack.getItem()) == itemId) {
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
            ItemStack stack = ctx.player().inventory.mainInventory.get(i);
            if (stack.getItem() instanceof ItemPickaxe) {
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

    private State placeShulkerBox(Optional<Rotation> shulkerReachable, Optional<Rotation> underShulkerReachable, BlockPos shulkerPlaceLoc, State prevState, State currentState, State nextState, ShulkerType shulkerType) {
        if (shulkerReachable.isPresent()) {
            Block shulkerLocBlock = ctx.world().getBlockState(shulkerPlaceLoc).getBlock();
            baritone.getLookBehavior().updateTarget(shulkerReachable.get(), true); // Look at shulker spot

            if (shulkerLocBlock instanceof BlockShulkerBox) {
                Helper.HELPER.logDirect("Shulker has been placed successfully");
                baritone.getInputOverrideHandler().clearAllKeys();
                return nextState;
            }
            else if (!(shulkerLocBlock instanceof BlockSnow)) {
                Helper.HELPER.logDirect("Something went wrong at " + currentState + ". Have " + shulkerLocBlock + " instead of shulker");
                return prevState; // Go back a state
            }
        }
        else underShulkerReachable.ifPresent(rotation -> baritone.getLookBehavior().updateTarget(rotation, true));

        if (!underShulkerReachable.isPresent()) {
            return prevState; // Something is wrong with where we are trying to place, go back
        }

        if (shulkerPlaceLoc.down().equals(ctx.getSelectedBlock().orElse(null))) {
            int shulkerSlot = putShulkerHotbar(shulkerType);
            if (shulkerSlot == -1) {
                Helper.HELPER.logDirect("Error getting shulker slot");
                return currentState;
            }
            ItemStack stack = ctx.player().inventory.mainInventory.get(shulkerSlot);
            if (stack.getItem() instanceof ItemShulkerBox) {
                ctx.player().inventory.currentItem = shulkerSlot;
                mc.playerController.updateController();
            }

            //baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            final Vec3d pos = new Vec3d(mc.player.lastTickPosX + (mc.player.posX - mc.player.lastTickPosX) * mc.getRenderPartialTicks(),
                    mc.player.lastTickPosY + (mc.player.posY - mc.player.lastTickPosY) * mc.getRenderPartialTicks(),
                    mc.player.lastTickPosZ + (mc.player.posZ - mc.player.lastTickPosZ) * mc.getRenderPartialTicks());
            BlockPos originPos = new BlockPos(pos.x, pos.y+0.5f, pos.z);
            double l_Offset = pos.y - originPos.getY();
            PlaceResult l_Place = place(shulkerPlaceLoc, 5.0f, false, l_Offset == -0.5f, EnumHand.MAIN_HAND);
        }

        return currentState;
    }

    private int lootPickaxeChestSlot() {
        Container curContainer = mc.player.openContainer;
        for (int i = 0; i < 27; i++) {
            if (curContainer.getSlot(i).getStack().getItem() instanceof ItemPickaxe) {
                int airSlot = getItemSlot(Item.getIdFromItem(Items.AIR));
                if (airSlot != -1) {
                    ctx.playerController().windowClick(curContainer.windowId, i, 0, ClickType.QUICK_MOVE, ctx.player());
                    mc.playerController.updateController();
                    return 1;
                }

                int netherRackSlot = getItemSlot(Item.getIdFromItem(Item.getItemFromBlock(Blocks.NETHERRACK)));
                if (netherRackSlot == 8) {
                    netherRackSlot = getItemSlotNoHotbar(Item.getIdFromItem(Item.getItemFromBlock(Blocks.NETHERRACK)));
                }
                if (netherRackSlot == -1) {
                    return 0;
                }
                ctx.playerController().windowClick(curContainer.windowId, i, 0, ClickType.PICKUP, ctx.player());
                ctx.playerController().windowClick(curContainer.windowId, netherRackSlot < 9 ? netherRackSlot + 54 : netherRackSlot + 18, 0, ClickType.PICKUP, ctx.player()); // Have to convert slot id to single chest slot id
                ctx.playerController().windowClick(curContainer.windowId, -999, 0, ClickType.PICKUP, ctx.player());
                mc.playerController.updateController();
                return 1;
            }
        }

        return 0;
    }

    private int lootGappleChestSlot() {
        int count = 0;
        Container curContainer = mc.player.openContainer;
        for (int i = 0; i < 27; i++) {
            if (curContainer.getSlot(i).getStack().getItem() instanceof ItemAppleGold) {
                count += curContainer.getSlot(i).getStack().getCount();

                if (getItemCountInventory(Item.getIdFromItem(Items.GOLDEN_APPLE)) == 0 && getItemSlot(Item.getIdFromItem(Items.AIR)) == -1) {
                    // For some reason we have no gapples and no air slots so we have to throw out some netherrack
                    int netherRackSlot = getItemSlot(Item.getIdFromItem(Item.getItemFromBlock(Blocks.NETHERRACK)));
                    if (netherRackSlot == 8) {
                        netherRackSlot = getItemSlotNoHotbar(Item.getIdFromItem(Item.getItemFromBlock(Blocks.NETHERRACK)));
                    }
                    if (netherRackSlot == -1) {
                        return 0;
                    }
                    ctx.playerController().windowClick(curContainer.windowId, i, 0, ClickType.PICKUP, ctx.player());
                    ctx.playerController().windowClick(curContainer.windowId, netherRackSlot < 9 ? netherRackSlot + 54 : netherRackSlot + 18, 0, ClickType.PICKUP, ctx.player()); // Have to convert slot id to single chest slot id
                    ctx.playerController().windowClick(curContainer.windowId, -999, 0, ClickType.PICKUP, ctx.player());
                } else {
                    // Gapples exist already or there's an air slot so we can just do a quick move
                    ctx.playerController().windowClick(curContainer.windowId, i, 0, ClickType.QUICK_MOVE, mc.player);
                }

                mc.playerController.updateController();
                return count;
            }
        }

        return count;
    }

    private int lootEnderChestSlot() {
        int count = 0;
        Container curContainer = mc.player.openContainer;
        for (int i = 0; i < 27; i++) {
            if (curContainer.getSlot(i).getStack().getItem() instanceof ItemBlock &&
                    ((ItemBlock) curContainer.getSlot(i).getStack().getItem()).getBlock() instanceof BlockEnderChest) {
                count += curContainer.getSlot(i).getStack().getCount();

                if (getItemSlot(Item.getIdFromItem(Items.AIR)) == -1) {
                    // For some reason we have no air slots so we have to throw out some netherrack
                    int netherRackSlot = getItemSlot(Item.getIdFromItem(Item.getItemFromBlock(Blocks.NETHERRACK)));
                    if (netherRackSlot == 8) {
                        netherRackSlot = getItemSlotNoHotbar(Item.getIdFromItem(Item.getItemFromBlock(Blocks.NETHERRACK)));
                    }
                    if (netherRackSlot == -1) {
                        return 0;
                    }
                    ctx.playerController().windowClick(curContainer.windowId, i, 0, ClickType.PICKUP, ctx.player());
                    ctx.playerController().windowClick(curContainer.windowId, netherRackSlot < 9 ? netherRackSlot + 54 : netherRackSlot + 18, 0, ClickType.PICKUP, ctx.player()); // Have to convert slot id to single chest slot id
                    ctx.playerController().windowClick(curContainer.windowId, -999, 0, ClickType.PICKUP, ctx.player());
                } else {
                    // There's an air slot so we can just do a quick move
                    ctx.playerController().windowClick(curContainer.windowId, i, 0, ClickType.QUICK_MOVE, mc.player);
                }

                mc.playerController.updateController();
                return count;
            }
        }

        return count;
    }

    private int lootShulkerChestSlot(ShulkerType shulkerType) {
        Container curContainer = Helper.mc.player.openContainer;
        for (int i = 0; i < 27; i++) {
            ItemStack stack = curContainer.getSlot(i).getStack();
            if (stack.isEmpty() || !(stack.getItem() instanceof ItemBlock)) {
                continue;
            }
            if (stack.getItem() instanceof ItemShulkerBox) {
                boolean doLoot = false;
                switch (shulkerType) {
                    case EnderChest:
                        if (isEnderChestShulker(stack)) {
                            doLoot = true;
                        }
                        break;

                    case Empty:
                        if (isEmptyShulker(stack)) {
                            doLoot = true;
                        }
                        break;

                    case NonSilkPickaxe:
                        if (isNonSilkPickShulker(stack)) {
                            doLoot = true;
                        }
                        break;

                    case Gapple:
                        if (isGappleShulker(stack)) {
                            doLoot = true;
                        }
                        break;

                    case AnyPickaxe:
                        if (isPickaxeShulker(stack)) {
                            doLoot = true;
                        }
                }
                if (doLoot) {
                    if (getItemSlot(Item.getIdFromItem(Items.AIR)) == -1) {
                        // For some reason we have no air slots so we have to throw out some netherrack
                        int netherRackSlot = getItemSlot(Item.getIdFromItem(Item.getItemFromBlock(Blocks.NETHERRACK)));
                        if (netherRackSlot == 8) {
                            netherRackSlot = getItemSlotNoHotbar(Item.getIdFromItem(Item.getItemFromBlock(Blocks.NETHERRACK)));
                        }
                        if (netherRackSlot == -1) {
                            return 0;
                        }
                        ctx.playerController().windowClick(curContainer.windowId, i, 0, ClickType.PICKUP, ctx.player());
                        ctx.playerController().windowClick(curContainer.windowId, netherRackSlot < 9 ? netherRackSlot + 54 : netherRackSlot + 18, 0, ClickType.PICKUP, ctx.player()); // Have to convert slot id to single chest slot id
                        ctx.playerController().windowClick(curContainer.windowId, -999, 0, ClickType.PICKUP, ctx.player());
                    } else {
                        // There's an air slot so we can just do a quick move
                        ctx.playerController().windowClick(curContainer.windowId, i, 0, ClickType.QUICK_MOVE, mc.player);
                    }
                    Helper.mc.playerController.updateController();
                    return 1;
                }
            }
        }

        return 0;
    }

    private int getShulkerCountInventory(ShulkerType shulkerType) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = ctx.player().inventory.mainInventory.get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof ItemBlock)) {
                continue;
            }
            if (stack.getItem() instanceof ItemShulkerBox) {
                switch (shulkerType) {
                    case AnyPickaxe:
                        if (isPickaxeShulker(stack)) {
                            count++;
                        }
                        break;

                    case Gapple:
                        if (isGappleShulker(stack)) {
                            count++;
                        }
                        break;

                    case Empty:
                        if (isEmptyShulker(stack)) {
                            count++;
                        }
                        break;

                    case NonSilkPickaxe:
                        if (isNonSilkPickShulker(stack)) {
                            count++;
                        }
                        break;

                    case EnderChest:
                        if (isEnderChestShulker(stack)) {
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
        for (Entity entity : mc.world.loadedEntityList) {
            if (entity instanceof EntityItem) {
                if (((EntityItem) entity).getItem().getItem() instanceof ItemShulkerBox) {
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
                    BlockPos curPos = pos.add(x, y, z);
                    if (getIssueType(curPos) != HighwayState.Blocks &&
                            (getIssueType(curPos.north()) == HighwayState.Blocks ||
                             getIssueType(curPos.east()) == HighwayState.Blocks ||
                             getIssueType(curPos.south()) == HighwayState.Blocks ||
                             getIssueType(curPos.west()) == HighwayState.Blocks ||
                             getIssueType(curPos.down()) == HighwayState.Blocks)) {
                        surroundingBlocks.add(curPos);
                    }
                }
            }
        }

        BlockPos closestBlock = null;
        double closestDistance = Double.MAX_VALUE;
        for (BlockPos curBlock : surroundingBlocks) {
            if (curBlock.getDistance(pos.getX(), pos.getY(), pos.getZ()) < closestDistance) {
                closestDistance = curBlock.getDistance(pos.getX(), pos.getY(), pos.getZ());
                closestBlock = curBlock;
            }
        }

        return closestBlock;
    }
}