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
import baritone.api.behavior.IMapBuilderBehavior;
import baritone.api.event.events.TickEvent;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.IStaticSchematic;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.utils.schematic.MapArtSchematic;
import baritone.utils.schematic.schematica.SchematicaHelper;
import net.minecraft.block.*;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.inventory.GuiShulkerBox;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Container;
import net.minecraft.item.Item;
import net.minecraft.item.ItemAir;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityShulkerBox;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Tuple;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;

import java.util.*;

public class MapBuilderBehavior extends Behavior implements IMapBuilderBehavior {
    public MapBuilderBehavior(Baritone baritone) {
        super(baritone);
    }

    private ISchematic schematic;
    private String schematicName;
    private Vec3i schematicOrigin;
    private boolean paused = true;
    private List<ShulkerInfo> shulkerList = new ArrayList<>();
    private int timer = 0;
    private State currentState = State.Nothing;
    private BetterBlockPos curCheckingShulker = null;
    private List<IBlockState> allBlocks = new LinkedList<>();
    private IBlockState closestNeededBlock;
    private BetterBlockPos cachedPlayerFeet = null;
    private BetterBlockPos pathBackLoc = null;
    private boolean cursorStackNonEmpty = false;
    private int stacksToLoot = 0;

    private enum State {
        Nothing,

        Building,

        ShulkerSearchPathing,
        ShulkerSearchOpening,
        ShulkerSearchChecking,

        SchematicScanning,
        PathingToShulker,
        OpeningShulker,
        LootingShulker,

        PathingBack
    }

    private class ShulkerInfo {
        public ShulkerInfo(BetterBlockPos Pos) {
            pos = Pos;
        }
        public BetterBlockPos pos;
        public List<ItemStack> contents = new ArrayList<>();
        public boolean checked = false;
    }

    @Override
    public void build() {
        if (SchematicaHelper.isSchematicaPresent()) {
            Optional<Tuple<IStaticSchematic, BlockPos>> schematic = SchematicaHelper.getOpenSchematic();
            if (schematic.isPresent()) {
                IStaticSchematic s = schematic.get().getFirst();
                schematicName = schematic.get().getFirst().toString();
                schematicOrigin = schematic.get().getSecond();
                if (Baritone.settings().mapArtMode.value) {
                    this.schematic = new MapArtSchematic(s);
                } else {
                    this.schematic = s;
                }
            } else {
                Helper.HELPER.logDirect("No schematic currently open");
                return;
            }
        } else {
            Helper.HELPER.logDirect("Schematica is not present");
            return;
        }


        shulkerList = new ArrayList<>();
        timer = 0;
        curCheckingShulker = null;
        allBlocks = new LinkedList<>();
        closestNeededBlock = null;
        cachedPlayerFeet = null;
        currentState = State.Nothing;
        pathBackLoc = null;

        populateShulkerInfoList();
        paused = false;
    }

    @Override
    public void stop() {
        paused = true;
        currentState = State.Nothing;
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getPathingBehavior().cancelEverything();
    }

    @Override
    public void printStatus() {

    }

    private void startBuild() {
        Helper.HELPER.logDirect("Starting build");

        Baritone.settings().buildRepeat.value = new Vec3i(0, 0, 0);

        baritone.getPathingBehavior().cancelEverything();
        baritone.getBuilderProcess().build(schematicName, schematic, schematicOrigin);
        currentState = State.Building;
    }

    @Override
    public void onTick(TickEvent event) {
        if (paused || schematic == null || Helper.mc.player == null || Helper.mc.player.inventory.isEmpty()) {
            return;
        }

        timer++;


        if (!Helper.mc.player.inventory.getItemStack().isEmpty() && ctx.player().openContainer == ctx.player().inventoryContainer) {
            if (cursorStackNonEmpty && timer >= 80) {
                // We have some item on our cursor for 80 ticks, try to place it somewhere
                timer = 0;


                int emptySlot = getItemSlot(Item.getIdFromItem(Items.AIR));
                if (emptySlot != -1) {
                    Helper.HELPER.logDirect("Had " + Helper.mc.player.inventory.getItemStack().getDisplayName() + " on our cursor. Trying to place into slot " + emptySlot);

                    if (emptySlot <= 8) {
                        // Fix slot id if it's a hotbar slot
                        emptySlot += 36;
                    }
                    ctx.playerController().windowClick(ctx.player().inventoryContainer.windowId, emptySlot, 0, ClickType.PICKUP, ctx.player());
                    Helper.mc.playerController.updateController();
                    cursorStackNonEmpty = false;
                    return;
                }
            } else if (!cursorStackNonEmpty) {
                cursorStackNonEmpty = true;
                timer = 0;
                return;
            }
            return;
        } else {
            cursorStackNonEmpty = false;
        }

        switch (currentState) {
            case Nothing: {
                for (ShulkerInfo curShulker : shulkerList) {
                    if (!curShulker.checked) {
                        currentState = State.ShulkerSearchPathing;
                        timer = 0;
                        return;
                    }
                }
                startBuild();
                //currentState = State.Building;
                break;
            }

            case Building: {
                if (baritone.getBuilderProcess().isActive() && baritone.getBuilderProcess().isPaused()) {
                    timer = 0;
                    currentState = State.SchematicScanning;
                    return;
                }

                if (!baritone.getBuilderProcess().isActive() && timer >= 300) {
                    // Must have disconnected and reconnected, restart build
                    currentState = State.Nothing;
                    timer = 0;
                    return;
                }

                if (baritone.getBuilderProcess().isActive() && !baritone.getBuilderProcess().isPaused() && timer >= 800) {
                    if (Helper.mc.currentScreen instanceof GuiChest) {
                        ctx.player().closeScreen(); // Close chest gui so we can actually build
                        timer = 0;
                        return;
                    }

                    if (cachedPlayerFeet == null) {
                        cachedPlayerFeet = new BetterBlockPos(ctx.playerFeet());
                        timer = 0;
                        return;
                    }

                    if (cachedPlayerFeet.getDistance(ctx.playerFeet().x, ctx.playerFeet().y, ctx.playerFeet().z) < 5) {
                        Helper.HELPER.logDirect("We haven't moved in 800 ticks. Restarting builder");
                        timer = 0;
                        pathBackLoc = findPathBackLoc(true);
                        Helper.HELPER.logDirect("Pathing back loc: " + pathBackLoc);
                        currentState = State.PathingBack;
                        baritone.getPathingBehavior().cancelEverything();
                        //ctx.player().connection.getNetworkManager().closeChannel(new TextComponentString("Haven't moved in 800 ticks. Reconnect"));
                        //ctx.world().sendQuittingDisconnectingPacket();
                        return;
                    }

                    if (!cachedPlayerFeet.equals(ctx.playerFeet())) {
                        cachedPlayerFeet = new BetterBlockPos(ctx.playerFeet());
                        timer = 0;
                        return;
                    }
                }
                break;
            }

            case ShulkerSearchPathing: {
                if (baritone.getCustomGoalProcess().isActive()) {
                    return; // Wait to get there
                }

                curCheckingShulker = getShulkerToCheck();
                if (curCheckingShulker == null) {
                    currentState = State.Nothing;
                    return;
                }

                Optional<Rotation> shulkerReachable = RotationUtils.reachable(ctx.player(), curCheckingShulker,
                        ctx.playerController().getBlockReachDistance());

                if (shulkerReachable.isPresent()) {
                    currentState = State.ShulkerSearchOpening;
                    timer = 0;
                } else {
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(getPathingSpotByShulker(curCheckingShulker)));
                }
                break;
            }

            case ShulkerSearchOpening: {
                if (timer < 20) {
                    return;
                }

                if (curCheckingShulker == null) {
                    currentState = State.ShulkerSearchPathing;
                    return;
                }

                Optional<Rotation> shulkerReachable = RotationUtils.reachable(ctx.player(), curCheckingShulker,
                        ctx.playerController().getBlockReachDistance());
                shulkerReachable.ifPresent(rotation -> baritone.getLookBehavior().updateTarget(rotation, true));

                if (!shulkerReachable.isPresent()) {
                    currentState = State.ShulkerSearchPathing;
                    timer = 0;
                    return;
                }

                if (!ctx.isLookingAt(curCheckingShulker)) {
                    timer = 0;
                    return;
                }

                baritone.getInputOverrideHandler().clearAllKeys();
                if (!(Helper.mc.currentScreen instanceof GuiShulkerBox)) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    timer = 0;
                } else {
                    baritone.getInputOverrideHandler().clearAllKeys();
                    currentState = State.ShulkerSearchChecking;
                }
                break;
            }

            case ShulkerSearchChecking: {
                if (timer < 40) {
                    return;
                }

                if (!(Helper.mc.currentScreen instanceof GuiShulkerBox)) {
                    currentState = State.ShulkerSearchOpening;
                    return;
                }

                for (ShulkerInfo shulkerInfo : shulkerList) {
                    if (shulkerInfo.pos.equals(curCheckingShulker)) {
                        shulkerInfo.checked = true;
                        shulkerInfo.contents = getOpenShulkerContents();
                        if (shulkerInfo.contents != null) {
                            for (ItemStack itemStack : shulkerInfo.contents) {
                                //Block curBlock = Block.getBlockFromItem(item);
                                IBlockState state = ((ItemBlock) itemStack.getItem()).getBlock().getStateForPlacement(ctx.world(), ctx.playerFeet(), EnumFacing.UP, (float) ctx.player().posX, (float) ctx.player().posY, (float) ctx.player().posZ, itemStack.getItem().getMetadata(itemStack.getMetadata()), ctx.player());
                                if (!allBlocks.contains(state) && !(state.getBlock() instanceof BlockAir)) {
                                    allBlocks.add(state);
                                }
                            }
                        }
                        break;
                    }
                }

                currentState = State.ShulkerSearchPathing;
                ctx.player().closeScreen();
                timer = 0;
                break;
            }

            case SchematicScanning: {
                closestNeededBlock = findNeededBlockNew();
                if (closestNeededBlock == null || closestNeededBlock.getBlock() instanceof BlockAir) {
                    // We probably have everything we need, but baritone is just being retarded
                    // So we have to manually walk back to our building spot
                    Helper.HELPER.logDirect("Have what we need trying to force path back");
                    pathBackLoc = findPathBackLoc(false);
                    Helper.HELPER.logDirect("Pathing back loc: " + pathBackLoc);
                    currentState = State.PathingBack;
                    baritone.getPathingBehavior().cancelEverything();
                    return;
                }
                int stacksNeeded = stacksOfBlockNeededSchematic(closestNeededBlock);
                int airSlots = getItemStackCountInventory(Blocks.AIR.getDefaultState());
                if (isSingleBlockBuild()) {
                    stacksToLoot = Math.min(stacksNeeded, airSlots);
                } else {
                    stacksToLoot = Math.min(stacksNeeded, 5);
                    if (airSlots < stacksToLoot) {
                        stacksToLoot = airSlots;
                    }
                }

                Helper.HELPER.logDirect("We need " + stacksToLoot + " stacks of: " + closestNeededBlock.toString());
                curCheckingShulker = null;
                for (ShulkerInfo curShulker : shulkerList) {
                    for (ItemStack stack : curShulker.contents) {
                        IBlockState state = ((ItemBlock) stack.getItem()).getBlock().getStateForPlacement(ctx.world(), ctx.playerFeet(), EnumFacing.UP, (float) ctx.player().posX, (float) ctx.player().posY, (float) ctx.player().posZ, stack.getItem().getMetadata(stack.getMetadata()), ctx.player());

                        // Torches can be placed in diff facing directions so we need this
                        if (closestNeededBlock.getBlock() instanceof BlockTorch) {
                            if (((ItemBlock) stack.getItem()).getBlock().equals(closestNeededBlock.getBlock())) {
                                curCheckingShulker = curShulker.pos;
                            }
                        } else {
                            if (state.equals(closestNeededBlock)) {
                                curCheckingShulker = curShulker.pos;
                            }
                        }
                    }
                }
                if (curCheckingShulker == null) {
                    Helper.HELPER.logDirect("Shulkers don't have any " + closestNeededBlock);
                    Helper.HELPER.logDirect("Please refill and restart building");
                    paused = true;
                    return;
                }
                currentState = State.PathingToShulker;
                break;
            }

            case PathingToShulker: {
                if (baritone.getCustomGoalProcess().isActive()) {
                    return; // Wait to get there
                }

                if (curCheckingShulker == null) {
                    currentState = State.Nothing;
                    return;
                }

                Optional<Rotation> shulkerReachable = RotationUtils.reachable(ctx.player(), curCheckingShulker,
                        ctx.playerController().getBlockReachDistance());

                if (shulkerReachable.isPresent()) {
                    currentState = State.OpeningShulker;
                } else {
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(getPathingSpotByShulker(curCheckingShulker)));
                }
                break;
            }

            case OpeningShulker: {
                if (timer < 10) {
                    return;
                }

                if (curCheckingShulker == null) {
                    currentState = State.PathingToShulker;
                    baritone.getInputOverrideHandler().clearAllKeys();
                    return;
                }

                Optional<Rotation> shulkerReachable = RotationUtils.reachable(ctx.player(), curCheckingShulker,
                        ctx.playerController().getBlockReachDistance());
                shulkerReachable.ifPresent(rotation -> baritone.getLookBehavior().updateTarget(rotation, true));

                if (!shulkerReachable.isPresent()) {
                    currentState = State.PathingToShulker;
                    return;
                }

                baritone.getInputOverrideHandler().clearAllKeys();
                if (!(Helper.mc.currentScreen instanceof GuiShulkerBox)) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    timer = 0;
                } else {
                    currentState = State.LootingShulker;
                }
                break;
            }

            case LootingShulker: {
                if (timer < 40) {
                    return;
                }

                if (!(Helper.mc.currentScreen instanceof GuiShulkerBox)) {
                    currentState = State.OpeningShulker;
                    return;
                }

                if (getItemStackCountInventory(closestNeededBlock) >= stacksToLoot) {
                    // Have what we need
                    timer = 0;
                    ctx.player().closeScreen();
                    currentState = State.Nothing;
                    return;
                }

                if (getChestSlotCount(closestNeededBlock) == 0) {
                    // Shulker doesn't have what we need, did we accidentally open the wrong one?
                    timer = 0;
                    ctx.player().closeScreen();
                    currentState = State.PathingToShulker;
                    return;
                }

                IBlockState itemLooted = lootItemChestSlot(closestNeededBlock);
                for (ShulkerInfo curShulker : shulkerList) {
                    if (curShulker.pos.equals(curCheckingShulker)) {
                        curShulker.contents = getOpenShulkerContents(); // Update the shulker contents
                        if (curShulker.contents == null || curShulker.contents.isEmpty()) {
                            Helper.HELPER.logDirect("Shulker no longer has items. Finishing looting early");
                            ctx.player().closeScreen();
                            currentState = State.Nothing;
                        }
                        /*
                        // Shulker we are looting
                        for (ItemStack curStack : curShulker.contents) {
                            IBlockState state = ((ItemBlock) curStack.getItem()).getBlock().getStateForPlacement(ctx.world(), ctx.playerFeet(), EnumFacing.UP, (float) ctx.player().posX, (float) ctx.player().posY, (float) ctx.player().posZ, curStack.getItem().getMetadata(curStack.getMetadata()), ctx.player());
                            if (state.equals(closestNeededBlock)) {
                                curShulker.contents.remove(curStack);
                                break;
                            }
                        }
                        if (!itemLooted.equals(Blocks.AIR.getDefaultState())) {
                            // Swapped with some inventory block so update the shulker list with that
                            // TODO : FIX THIS :D
                            //curShulker.contents.add(itemLooted);
                        }
                         */
                        timer = 0;
                        return;
                    }
                }

                break;
            }

            case PathingBack: {
                if (baritone.getCustomGoalProcess().isActive()) {
                    return; // Wait to get there
                }

                if (pathBackLoc == null) {
                    currentState = State.Nothing;
                    return;
                }

                if (ctx.playerFeet().getDistance(pathBackLoc.getX(), pathBackLoc.getY(), pathBackLoc.getZ()) < 3) {
                    // We have arrived
                    currentState = State.Nothing;
                } else {
                    // Keep trying to get there
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(pathBackLoc.getX(), pathBackLoc.getY(), pathBackLoc.getZ()));
                }
                break;
            }
        }
    }

    private boolean isSingleBlockBuild() {
        IBlockState firstState = null;
        for (ShulkerInfo curShulker : shulkerList) {
            for (ItemStack stack : curShulker.contents) {
                if (stack.getItem() instanceof ItemBlock) {
                    IBlockState state = ((ItemBlock) stack.getItem()).getBlock().getStateForPlacement(ctx.world(), ctx.playerFeet(), EnumFacing.UP, (float) ctx.player().posX, (float) ctx.player().posY, (float) ctx.player().posZ, stack.getItem().getMetadata(stack.getMetadata()), ctx.player());
                    if (firstState == null) {
                        firstState = state;
                    } else if (!firstState.equals(state)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private BetterBlockPos findPathBackLoc(boolean findFurthest) {
        List<BlockPos> set = findInvalidBlocks();//baritone.getBuilderProcess().getIncorrectPositions();
        List<BlockPos> validPathBacks = new LinkedList<>();
        Helper.HELPER.logDirect("Found " + set.size() + " invalid locations.");
        for (BlockPos pos : set) {
            // If invalid block we are checking isn't loaded or we don't have any of it in our inventory just skip
            if (!Helper.mc.world.isBlockLoaded(pos, false) || Helper.mc.world.getBlockState(pos).getBlock() instanceof BlockAir || getItemStackCountInventory(Helper.mc.world.getBlockState(pos)) == 0) {
                continue;
            }
            List<BlockPos> validSideBlocks = new LinkedList<>();
            for (int x = -4; x < 4; x++) {
                for (int z = -4; z < 4; z++) {
                    BlockPos curBlockPos = pos.add(x, 0, z);
                    if (Helper.mc.world.isBlockLoaded(curBlockPos, false)) { // check if its in render distance, not if its in cache
                        Block sideBlock = Helper.mc.world.getBlockState(curBlockPos).getBlock();
                        // Make sure side block isn't air, water, or web
                        if (!(sideBlock instanceof BlockAir) && !(sideBlock instanceof BlockLiquid) && !(sideBlock instanceof BlockWeb)) {
                            // We can stand here
                            if (sideBlock instanceof BlockCarpet) {
                                validSideBlocks.add(curBlockPos);
                            } else {
                                validSideBlocks.add(curBlockPos.add(0, 1, 0)); // Add one height so we can stand
                            }
                        }
                    }
                }
            }
            BlockPos closestSideBlock = null;
            double closestDistSideBlock = Double.MAX_VALUE;
            for (BlockPos curPos : validSideBlocks) {
                double tempDist = pos.getDistance(curPos.getX(), curPos.getY(), curPos.getZ());
                if (tempDist < closestDistSideBlock) {
                    closestSideBlock = curPos;
                    closestDistSideBlock = tempDist;
                }
            }
            if (closestSideBlock != null) {
                validPathBacks.add(closestSideBlock);
            }
        }

        if (findFurthest) {
            BlockPos furthestPos = null;
            double furthestDist = 0;
            for (BlockPos curPos : validPathBacks) {
                double tempDist = ctx.playerFeet().getDistance(curPos.getX(), curPos.getY(), curPos.getZ());
                if (tempDist > furthestDist) {
                    furthestPos = curPos;
                    furthestDist = tempDist;
                }
            }
            return furthestPos != null ? new BetterBlockPos(furthestPos) : null;
        }

        BlockPos closestPos = null;
        double closestDist = Double.MAX_VALUE;
        for (BlockPos curPos : validPathBacks) {
            double tempDist = ctx.playerFeet().getDistance(curPos.getX(), curPos.getY(), curPos.getZ());
            if (tempDist < closestDist) {
                closestPos = curPos;
                closestDist = tempDist;
            }
        }
        return closestPos != null ? new BetterBlockPos(closestPos) : null;
    }

    private List<BlockPos> findInvalidBlocks() {
        List<BlockPos> invalidPos = new LinkedList<>();
        for (int y = 0; y < schematic.heightY(); y++) {
            for (int z = 0; z < schematic.lengthZ(); z++) {
                for (int x = 0; x < schematic.widthX(); x++) {
                    int blockX = x + schematicOrigin.getX();
                    int blockY = y + schematicOrigin.getY();
                    int blockZ = z + schematicOrigin.getZ();
                    BlockPos curBlockPos = new BlockPos(blockX, blockY, blockZ);
                    IBlockState current = Helper.mc.world.getBlockState(curBlockPos);
                    if (!schematic.inSchematic(x, y, z, current)) {
                        continue;
                    }
                    if (Helper.mc.world.isBlockLoaded(curBlockPos, false)) { // check if its in render distance, not if its in cache
                        // we can directly observe this block, it is in render distance
                        if (!current.equals(schematic.desiredState(x, y, z, current, this.allBlocks))) {
                            invalidPos.add(curBlockPos);
                        }
                    }
                }
            }
        }
        return invalidPos;
    }

    private int getChestSlotCount(IBlockState item) {
        int count = 0;
        Container curContainer = Helper.mc.player.openContainer;
        for (int i = 0; i < 27; i++) {
            if (!(curContainer.getSlot(i).getStack().getItem() instanceof ItemBlock)) {
                continue;
            }
            IBlockState state = ((ItemBlock) curContainer.getSlot(i).getStack().getItem()).getBlock().getStateForPlacement(ctx.world(), ctx.playerFeet(), EnumFacing.UP, (float) ctx.player().posX, (float) ctx.player().posY, (float) ctx.player().posZ, curContainer.getSlot(i).getStack().getItem().getMetadata(curContainer.getSlot(i).getStack().getMetadata()), ctx.player());
            if (state.equals(item)) {
                count++;
            }
        }
        return count;
    }

    // Returns the item that was in our inventory before looting
    // So if we loot into air slot then it's an Air item
    // Otherwise it's the item that got swapped into the chest
    private IBlockState lootItemChestSlot(IBlockState itemLoot) {
        Container curContainer = Helper.mc.player.openContainer;
        for (int i = 0; i < 27; i++) {
            if (curContainer.getSlot(i).getStack().getItem() instanceof ItemAir) {
                continue;
            }
            IBlockState swappedItem = Blocks.AIR.getDefaultState();
            IBlockState state = ((ItemBlock) curContainer.getSlot(i).getStack().getItem()).getBlock().getStateForPlacement(ctx.world(), ctx.playerFeet(), EnumFacing.UP, (float) ctx.player().posX, (float) ctx.player().posY, (float) ctx.player().posZ, curContainer.getSlot(i).getStack().getItem().getMetadata(curContainer.getSlot(i).getStack().getMetadata()), ctx.player());
            if (state.equals(itemLoot)) {
                int swapSlot = getRandomBlockIdSlot();

                if (getItemStackCountInventory(itemLoot) == 0 && getItemSlot(Item.getIdFromItem(Items.AIR)) == -1) {
                    // We have no needed items and no air slots so we have to swap
                    if (swapSlot == 8) {
                        swapSlot = getRandomBlockIdSlotNoHotbar();
                        if (swapSlot == -1) {
                            return Blocks.AIR.getDefaultState();
                        }
                    }
                    swappedItem = ((ItemBlock) ctx.player().inventory.mainInventory.get(swapSlot).getItem()).getBlock().getStateForPlacement(ctx.world(), ctx.playerFeet(), EnumFacing.UP, (float) ctx.player().posX, (float) ctx.player().posY, (float) ctx.player().posZ, ctx.player().inventory.mainInventory.get(swapSlot).getItem().getMetadata(ctx.player().inventory.mainInventory.get(swapSlot).getMetadata()), ctx.player());
                    ctx.playerController().windowClick(curContainer.windowId, i, 0, ClickType.PICKUP, ctx.player()); // Pickup from chest
                    ctx.playerController().windowClick(curContainer.windowId, swapSlot < 9 ? swapSlot + 54 : swapSlot + 18, 0, ClickType.PICKUP, ctx.player()); // Have to convert slot id to single chest slot id
                    ctx.playerController().windowClick(curContainer.windowId, i, 0, ClickType.PICKUP, ctx.player()); // Place back into chest
                } else {
                    // Item exist already or there's an air slot so we can just do a quick move
                    ctx.playerController().windowClick(curContainer.windowId, i, 0, ClickType.QUICK_MOVE, Helper.mc.player);
                }

                Helper.mc.playerController.updateController();
                return swappedItem;
            }
        }

        return Blocks.AIR.getDefaultState();
    }

    private int getRandomBlockIdSlotNoHotbar() {
        for (int i = 35; i >= 9; i--) {
            ItemStack stack = ctx.player().inventory.mainInventory.get(i);
            if (stack.getItem() instanceof ItemBlock) {
                return i;
            }
        }
        return -1;
    }

    private int getRandomBlockIdSlot() {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = ctx.player().inventory.mainInventory.get(i);
            if (stack.getItem() instanceof ItemBlock) {
                return i;
            }
        }
        return -1;
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

    private IBlockState findNeededBlockNew() {
        List<Tuple<BetterBlockPos, IBlockState>> blocksNeeded = new LinkedList<>();
        HashSet<BetterBlockPos> set = baritone.getBuilderProcess().getIncorrectPositions();
        for (BetterBlockPos pos : set) {
            IBlockState current = Helper.mc.world.getBlockState(pos);
            if (!schematic.inSchematic(pos.x - schematicOrigin.getX(), pos.y - schematicOrigin.getY(), pos.z - schematicOrigin.getZ(), current)) {
                continue;
            }
            //Item blockNeeded = Item.getItemFromBlock(schematic.desiredState(pos.x, pos.y, pos.z, current, this.allBlocks).getBlock());
            IBlockState desiredState = schematic.desiredState(pos.x - schematicOrigin.getX(), pos.y - schematicOrigin.getY(), pos.z - schematicOrigin.getZ(), current, this.allBlocks);
            if (getItemStackCountInventory(desiredState) == 0) {
                blocksNeeded.add(new Tuple<>(pos, desiredState));
            }
        }

        IBlockState closestItem = null;
        double closestDistance = Double.MAX_VALUE;
        for (Tuple<BetterBlockPos, IBlockState> curCheck : blocksNeeded) {
            double tempDistance = baritone.getPlayerContext().playerFeet().getDistance(curCheck.getFirst().getX(), curCheck.getFirst().getY(), curCheck.getFirst().getZ());
            if (tempDistance < closestDistance) {
                closestDistance = tempDistance;
                closestItem = curCheck.getSecond();
            }
        }

        if (closestItem != null) {
            return closestItem;
        }

        return findNeededClosestBlock();
    }

    private IBlockState findNeededClosestBlock() {
        List<Tuple<BlockPos, IBlockState>> blocksNeeded = new LinkedList<>();
        for (int y = 0; y < schematic.heightY(); y++) {
            for (int z = 0; z < schematic.lengthZ(); z++) {
                for (int x = 0; x < schematic.widthX(); x++) {
                    int blockX = x + schematicOrigin.getX();
                    int blockY = y + schematicOrigin.getY();
                    int blockZ = z + schematicOrigin.getZ();
                    BlockPos curBlockPos = new BlockPos(blockX, blockY, blockZ);
                    IBlockState current = Helper.mc.world.getBlockState(curBlockPos);
                    if (!schematic.inSchematic(x, y, z, current)) {
                        continue;
                    }
                    if (Helper.mc.world.isBlockLoaded(curBlockPos, false)) { // check if its in render distance, not if its in cache
                        // we can directly observe this block, it is in render distance
                        if (!current.equals(schematic.desiredState(x, y, z, current, this.allBlocks))) {
                            if (getItemStackCountInventory(schematic.desiredState(x, y, z, current, this.allBlocks)) == 0) {
                                // We don't have any of that block, see if we can even place there
                                boolean canPlace = false;
                                List<BlockPos> sideBlocks = new LinkedList<>();
                                sideBlocks.add(curBlockPos.north());
                                sideBlocks.add(curBlockPos.east());
                                sideBlocks.add(curBlockPos.south());
                                sideBlocks.add(curBlockPos.west());
                                sideBlocks.add(curBlockPos.down());
                                for (BlockPos curPos : sideBlocks) {
                                    if (!(Helper.mc.world.getBlockState(curPos).getBlock() instanceof BlockAir) && !(Helper.mc.world.getBlockState(curPos).getBlock() instanceof BlockLiquid)) {
                                        canPlace = true;
                                    }
                                }
                                if (canPlace) {
                                    blocksNeeded.add(new Tuple<>(curBlockPos, schematic.desiredState(x, y, z, current, this.allBlocks)));
                                }

                            }
                        }
                    }
                }
            }
        }

        IBlockState closestItem = Blocks.AIR.getDefaultState();
        double closestDistance = Double.MAX_VALUE;
        for (Tuple<BlockPos, IBlockState> curCheck : blocksNeeded) {
            double tempDistance = baritone.getPlayerContext().playerFeet().getDistance(curCheck.getFirst().getX(), curCheck.getFirst().getY(), curCheck.getFirst().getZ());
            if (tempDistance < closestDistance) {
                closestDistance = tempDistance;
                closestItem = curCheck.getSecond();
            }
        }

        return closestItem;
    }

    private int stacksOfBlockNeededSchematic(IBlockState neededBlock) {
        int count = 0;
        for (int y = 0; y < schematic.heightY(); y++) {
            for (int z = 0; z < schematic.lengthZ(); z++) {
                for (int x = 0; x < schematic.widthX(); x++) {
                    int blockX = x + schematicOrigin.getX();
                    int blockY = y + schematicOrigin.getY();
                    int blockZ = z + schematicOrigin.getZ();
                    BlockPos curBlockPos = new BlockPos(blockX, blockY, blockZ);
                    IBlockState current = Helper.mc.world.getBlockState(curBlockPos);
                    if (!schematic.inSchematic(x, y, z, current)) {
                        continue;
                    }
                    if (Helper.mc.world.isBlockLoaded(curBlockPos, false)) { // check if its in render distance, not if its in cache
                        IBlockState desiredState = schematic.desiredState(x, y, z, current, this.allBlocks);
                        if (!current.equals(desiredState)) {
                            // Block isn't in its correct state so we need some
                            if (desiredState.equals(neededBlock)) {
                                // Found the type we were searching for
                                count++;
                            }
                        }
                    }
                }
            }
        }

        return (int) Math.ceil(count / 64.0);
    }

    private int getItemStackCountInventory(IBlockState item) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = ctx.player().inventory.mainInventory.get(i);
            if (stack.getItem() instanceof ItemBlock) {
                IBlockState state = ((ItemBlock) stack.getItem()).getBlock().getStateForPlacement(ctx.world(), ctx.playerFeet(), EnumFacing.UP, (float) ctx.player().posX, (float) ctx.player().posY, (float) ctx.player().posZ, stack.getItem().getMetadata(stack.getMetadata()), ctx.player());
                if (state.equals(item)) {
                    count++;
                }
            } else if (stack.isEmpty() && item.getBlock() instanceof BlockAir) {
                // Counting air slots
                count++;
            }
        }

        return count;
    }

    private List<ItemStack> getOpenShulkerContents() {
        if (!(Helper.mc.currentScreen instanceof GuiShulkerBox)) {
            return null;
        }

        List<ItemStack> shulkerContents = new ArrayList<>();
        Container curContainer = Helper.mc.player.openContainer;
        for (int i = 0; i < 27; i++) {
            if (!(curContainer.getSlot(i).getStack().getItem() instanceof ItemAir)) {
                //int itemId = Item.getIdFromItem(curContainer.getSlot(i).getStack().getItem());
                shulkerContents.add(curContainer.getSlot(i).getStack());
            }
        }

        return shulkerContents;
    }

    private BetterBlockPos getPathingSpotByShulker(BetterBlockPos shulkerSpot) {
        BetterBlockPos closestPos = shulkerSpot.north().up(); // Just a fallback so we can stand somewhere
        double closestDist = Double.MAX_VALUE;
        for (int x = -2; x < 2; x++) {
            for (int z = -2; z < 2; z++) {
                if (Helper.mc.world.getBlockState(shulkerSpot.add(x, 0, z)).getBlock() instanceof BlockAir &&
                        Helper.mc.world.getBlockState(shulkerSpot.add(x, 1, z)).getBlock() instanceof BlockAir) {
                    // We can probably stand here, check shulker list
                    BetterBlockPos tempPos = new BetterBlockPos(shulkerSpot.add(x, 0, z));
                    boolean canStand = true;
                    for (ShulkerInfo curInfo : shulkerList) {
                        // Needed because if position is unloaded then it'll show up as a valid location
                        if (curInfo.pos.equals(tempPos)) {
                            canStand = false;
                            break;
                        }
                    }
                    if (canStand) {
                        double tempDist = tempPos.getDistance(shulkerSpot.x, shulkerSpot.y, shulkerSpot.z);
                        if (tempDist < closestDist) {
                            closestDist = tempDist;
                            closestPos = tempPos;
                        }
                    }
                }
            }
        }

        return closestPos;
    }

    private BetterBlockPos getShulkerToCheck() {
        BetterBlockPos closestPos = null;
        double closestDist = Double.MAX_VALUE;
        for (ShulkerInfo shulkerInfo : shulkerList) {
            if (!shulkerInfo.checked) {
                double dist = shulkerInfo.pos.getDistance(ctx.playerFeet().getX(), ctx.playerFeet().getY(), ctx.playerFeet().getZ());
                if (dist < closestDist) {
                    closestDist = dist;
                    closestPos = shulkerInfo.pos;
                }
            }
        }
        return closestPos;
    }

    private void populateShulkerInfoList() {
        shulkerList.clear();
        List<BetterBlockPos> shulkerBoxes = findShulkerBoxes();
        for (BetterBlockPos pos : shulkerBoxes) {
            shulkerList.add(new ShulkerInfo(pos));
        }
    }

    private List<BetterBlockPos> findShulkerBoxes() {
        List<BetterBlockPos> foundBoxes = new LinkedList<>();

        for (TileEntity tileEntity : Helper.mc.world.loadedTileEntityList) {
            if (tileEntity instanceof TileEntityShulkerBox) {
                foundBoxes.add(new BetterBlockPos(tileEntity.getPos()));
            }
        }

        return foundBoxes;
    }
}
