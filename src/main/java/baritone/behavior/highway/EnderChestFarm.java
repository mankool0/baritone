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

import baritone.api.schematic.CompositeSchematic;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.WhiteBlackSchematic;
import baritone.api.utils.*;
import baritone.api.utils.Rotation;
import baritone.behavior.highway.enums.LocationType;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.ServerDigTarget;
import baritone.utils.accessor.IClientLevel;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntPredicate;

/**
 * The ender chest farm: what a session may break before its obsidian outgrows the inventory, the
 * chest places the server hasn't answered yet, and the instamine calibration.
 */
public class EnderChestFarm extends ContextComponent {
    private long lastObsidianStuckLogTick = Long.MIN_VALUE / 2;

    private boolean instantMineCalibrated = false;
    // Whether we were mid-break last tick during the legit calibration break. The client-side
    // isDestroying flag must be saved/restored each tick since we never actually hold the attack key.
    private boolean instantMineCalibrationHitting = false;
    // ServerDigTarget#startTick of the start the calibration sent itself, telling a dig target it
    // set from one an earlier dig left there
    private long calibrationStart = Long.MIN_VALUE;
    // a stop went out at a target an earlier dig left, with a resync behind it to tell whether it broke the chest
    private boolean calibrationProbing = false;
    // that check is done: once it fails, the target isn't where our packets say
    private boolean calibrationProbed = false;

    private BlockPos instantMineLastBlock;

    private Direction instantMineDirection;

    private int lastEchestPlaceTick = Integer.MIN_VALUE;

    private boolean echestPlacedServerSide = false;

    private boolean farmPlaceLocResynced = false;

    private Item instantMineOriginalOffhandItem;

    private int farmEnderChestsToKeep = -1;

    private int farmObsidianStart = 0;
    private int farmChestsStart = 0;
    // Prediction sequences of farm places the server hasn't acked. The server echoes the offhand as an
    // absolute count, and that echo trails the places by a round trip, so the chest count the client
    // shows still includes these.
    private final IntArrayFIFOQueue farmUnackedPlaces = new IntArrayFIFOQueue();
    // Offhand stack the client decremented for farm places, and by how much. An echo replaces the
    // stack, and those decrements go with it.
    private ItemStack farmOffhandStack = ItemStack.EMPTY;
    private int farmOffhandDecrements = 0;
    // Sequence of the click asking the server to resend placeLoc since the last dig, -1 if none sent.
    private int farmResyncSequence = -1;
    // Times this farm session has put back the block its chests go on top of.
    private int farmSupportRebuilds = 0;

    private int runObsidianNeed = -1;
    private int runObsidianNeedTick = -1;
    private int runObsidianNeedHave = 0;
    private int farmRunChests = -1;
    private boolean farmLooseChests = false;

    EnderChestFarm(HighwayContext ctx) {
        super(ctx);
    }

    /** Rate limit for the "obsidian doesn't fit" log: states are re-created on every transition, so the throttle lives here. */
    public boolean obsidianStuckLogDue() {
        if (ctx.contextTick() - lastObsidianStuckLogTick < 200) {
            return false;
        }
        lastObsidianStuckLogTick = ctx.contextTick();
        return true;
    }

    public boolean instantMineCalibrated() {
        return instantMineCalibrated;
    }

    public void setInstantMineCalibrated(boolean instantMineCalibrated) {
        this.instantMineCalibrated = instantMineCalibrated;
        this.calibrationProbing = false;
        if (!instantMineCalibrated) {
            this.instantMineCalibrationHitting = false;
            this.calibrationStart = Long.MIN_VALUE;
            this.calibrationProbed = false;
        }
    }

    public BlockPos instantMineLastBlock() {
        return instantMineLastBlock;
    }

    public void setInstantMineLastBlock(BlockPos instantMineLastBlock) {
        this.instantMineLastBlock = instantMineLastBlock;
    }

    public Direction instantMineDirection() {
        return instantMineDirection;
    }

    public void setInstantMineDirection(Direction instantMineDirection) {
        this.instantMineDirection = instantMineDirection;
    }

    public int lastEchestPlaceTick() {
        return lastEchestPlaceTick;
    }

    public void setLastEchestPlaceTick(int lastEchestPlaceTick) {
        this.lastEchestPlaceTick = lastEchestPlaceTick;
    }

    public boolean echestPlacedServerSide() {
        return echestPlacedServerSide;
    }

    public void setEchestPlacedServerSide(boolean echestPlacedServerSide) {
        this.echestPlacedServerSide = echestPlacedServerSide;
    }

    public boolean farmPlaceLocResynced() {
        return farmPlaceLocResynced;
    }

    public void setFarmPlaceLocResynced(boolean farmPlaceLocResynced) {
        this.farmPlaceLocResynced = farmPlaceLocResynced;
    }

    public Item instantMineOriginalOffhandItem() {
        return instantMineOriginalOffhandItem;
    }

    public void setInstantMineOriginalOffhandItem(Item instantMineOriginalOffhandItem) {
        this.instantMineOriginalOffhandItem = instantMineOriginalOffhandItem;
    }

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

    private int obsidianCountAll() {
        int count = ctx.inventory().getItemCountInventory(Item.getId(Blocks.OBSIDIAN.asItem()));
        ItemStack offhand = playerContext.player().getOffhandItem();
        if (offhand.is(Blocks.OBSIDIAN.asItem())) {
            count += offhand.getCount();
        }
        return count;
    }

    private int enderChestCountAll() {
        int count = ctx.inventory().getItemCountInventory(Item.getId(Blocks.ENDER_CHEST.asItem()));
        ItemStack offhand = playerContext.player().getOffhandItem();
        if (offhand.is(Blocks.ENDER_CHEST.asItem())) {
            count += offhand.getCount();
        }
        return count;
    }

    public void beginFarmAccounting() {
        farmObsidianStart = obsidianCountAll();
        farmChestsStart = enderChestCountAll();
        farmUnackedPlaces.clear();
        farmOffhandStack = ItemStack.EMPTY;
        farmOffhandDecrements = 0;
        farmResyncSequence = -1;
        farmSupportRebuilds = 0;
    }

    /**
     * Prediction sequences start over on a new level, which a reconnect brings. Places recorded on
     * the old one would never be acked and hold the farm waiting on them forever, and the new
     * connection has no dig of ours left to finish instantly.
     */
    private void forgetFarmSequencesOfOldLevel(BlockStatePredictionHandler predictions) {
        int current = predictions.currentSequence();
        if (!farmUnackedPlaces.isEmpty() && farmUnackedPlaces.lastInt() > current || farmResyncSequence > current) {
            farmUnackedPlaces.clear();
            farmOffhandStack = ItemStack.EMPTY;
            farmOffhandDecrements = 0;
            farmResyncSequence = -1;
            setInstantMineCalibrated(false);
        }
    }

    /** Whether the block the farm's chests go on top of is still there to place them against */
    public boolean farmSupportHolds() {
        BlockPos support = ctx.placeLoc().below();
        return !playerContext.world().getBlockState(support).getCollisionShape(playerContext.world(), support).isEmpty();
    }

    public int farmSupportRebuilds() {
        return farmSupportRebuilds;
    }

    public void noteFarmSupportRebuilt() {
        farmSupportRebuilds++;
    }

    /** Places the next farm chest from the offhand and tracks it until the server acks it */
    public BlockPlacer.PlaceResult placeFarmEnderChest() {
        BlockStatePredictionHandler predictions = ((IClientLevel) playerContext.minecraft().level).getPredictionHandler();
        forgetFarmSequencesOfOldLevel(predictions);
        ItemStack offhand = playerContext.player().getOffhandItem();
        int sequence = predictions.currentSequence();
        BlockPlacer.PlaceResult result = ctx.placer().place(ctx.placeLoc(), 5.0f, false, false, InteractionHand.OFF_HAND);
        // Every click that went out counts, even one the client didn't predict: the server may still place it.
        while (sequence < predictions.currentSequence()) {
            farmUnackedPlaces.enqueue(++sequence);
        }
        if (result == BlockPlacer.PlaceResult.Placed) {
            if (offhand != farmOffhandStack) {
                farmOffhandStack = offhand;
                farmOffhandDecrements = 0;
            }
            farmOffhandDecrements++;
        }
        return result;
    }

    /** Farm places sent that the server hasn't acked yet */
    public int farmPlacesInFlight() {
        IClientLevel level = (IClientLevel) playerContext.minecraft().level;
        forgetFarmSequencesOfOldLevel(level.getPredictionHandler());
        int acked = level.getLastAckedSequence();
        while (!farmUnackedPlaces.isEmpty() && farmUnackedPlaces.firstInt() <= acked) {
            farmUnackedPlaces.dequeueInt();
        }
        return farmUnackedPlaces.size();
    }

    private int farmOffhandDecrementsStanding() {
        return playerContext.player().getOffhandItem() == farmOffhandStack ? farmOffhandDecrements : 0;
    }

    /**
     * Loose ender chests left once every farm place still in flight has landed. At high ping the
     * client's own count runs high by about one chest per tick of round trip.
     */
    public int farmEnderChestsLeft() {
        int inFlight = farmPlacesInFlight();
        return enderChestCountAll() + farmOffhandDecrementsStanding() - inFlight;
    }

    /** Ender chests the server's offhand holds once every farm place still in flight has landed */
    public int farmOffhandChestsLeft() {
        int inFlight = farmPlacesInFlight();
        ItemStack offhand = playerContext.player().getOffhandItem();
        return (offhand.is(Blocks.ENDER_CHEST.asItem()) ? offhand.getCount() : 0) + farmOffhandDecrementsStanding() - inFlight;
    }

    /**
     * Whether placeLoc as shown is what the server has had since the farm's last dig. The client never
     * sees that dig itself, only the server's block update a round trip later, so a chest still on
     * screen may already be gone. The first call asks the server to resend placeLoc; once that click
     * is acked, its reply has landed.
     */
    public boolean farmPlaceLocConfirmed() {
        IClientLevel level = (IClientLevel) playerContext.minecraft().level;
        forgetFarmSequencesOfOldLevel(level.getPredictionHandler());
        if (farmResyncSequence == -1) {
            ctx.placer().requestBlockResync(ctx.placeLoc());
            farmResyncSequence = level.getPredictionHandler().currentSequence();
            return false;
        }
        return level.getLastAckedSequence() >= farmResyncSequence;
    }

    /**
     * Obsidian this farm session has produced that is neither in the
     * inventory nor visible on the ground yet
     */
    public int farmObsidianInFlight(int ground) {
        int picked = obsidianCountAll() - farmObsidianStart;
        int consumed = farmChestsStart - farmEnderChestsLeft();
        return Math.max(0, 8 * consumed - picked - ground);
    }

    /**
     * Obsidian the inventory can still take once this farm session ends,
     * minus what is already dropped or in flight, if one more chest gets broken
     */
    public int farmRoomAfterOneMore() {
        int ground = obsidianOnGroundNearby();
        int remaining = farmEnderChestsLeft() - 1;
        Item origItem = instantMineOriginalOffhandItem();
        boolean origInInventory = origItem != null && origItem != Items.AIR && ctx.inventory().getItemSlot(Item.getId(origItem)) != -1
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
        return obsidianRoom(playerContext.player().getInventory().getNonEquipmentItems(), ctx.inventory().junkSlots(),
                settings.acceptableThrowawayItems.value, slotsFreedLater);
    }

    /**
     * Obsidian room in an inventory: partial obsidian stacks plus 64 for every slot that is empty
     * or that the paving pickers would throw out for it (junk, then non-obsidian throwaways).
     */
    static int obsidianRoom(List<ItemStack> inventory, IntPredicate junkSlot, List<Item> throwaway, int slotsFreedLater) {
        int room = 0;
        int slots = slotsFreedLater;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.get(i);
            if (stack.isEmpty()) {
                slots++;
            } else if (stack.is(Blocks.OBSIDIAN.asItem())) {
                room += stack.getMaxStackSize() - stack.getCount();
            } else if (junkSlot.test(i) || (i != 8 && !stack.is(Blocks.CRYING_OBSIDIAN.asItem()) && throwaway.contains(stack.getItem()))) {
                slots++;
            }
        }
        // A negative slot count means the kept chests or the returning box need slots that don't
        // exist; partial-stack room can't paper over that.
        return Math.max(0, room + 64 * slots);
    }

    /** Slot breakdown behind the obsidian room figures, for the farm-entry and stuck-collection logs. */
    public String obsidianRoomBreakdown() {
        List<ItemStack> inventory = playerContext.player().getInventory().getNonEquipmentItems();
        IntPredicate junkSlot = ctx.inventory().junkSlots();
        int empty = 0, junk = 0, throwaway = 0, partial = 0, chestSlots = 0, obsidianSlots = 0;
        // item -> {count, slots} for everything no bucket takes, slot 8 aside (it has its own field)
        Map<Item, int[]> other = new LinkedHashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.get(i);
            if (stack.isEmpty()) {
                empty++;
            } else if (stack.is(Blocks.OBSIDIAN.asItem())) {
                obsidianSlots++;
                partial += stack.getMaxStackSize() - stack.getCount();
            } else if (stack.is(Blocks.ENDER_CHEST.asItem())) {
                chestSlots++;
            } else if (junkSlot.test(i)) {
                junk++;
            } else if (i != 8 && !stack.is(Blocks.CRYING_OBSIDIAN.asItem()) && settings.acceptableThrowawayItems.value.contains(stack.getItem())) {
                throwaway++;
            } else if (i != 8) {
                int[] tally = other.computeIfAbsent(stack.getItem(), item -> new int[2]);
                tally[0] += stack.getCount();
                tally[1]++;
            }
        }
        StringBuilder others = new StringBuilder();
        for (Map.Entry<Item, int[]> entry : other.entrySet()) {
            if (others.length() > 0) {
                others.append(',');
            }
            others.append(BuiltInRegistries.ITEM.getKey(entry.getKey()).getPath()).append('x').append(entry.getValue()[0]);
            if (entry.getValue()[1] > 1) {
                others.append('/').append(entry.getValue()[1]).append("slots");
            }
        }
        return "empty=" + empty + " junk=" + junk + " throwaway=" + throwaway + " obsidianSlots=" + obsidianSlots + " partialRoom=" + partial
                + " chestSlots=" + chestSlots + " chests=" + ctx.inventory().getItemCountInventory(Item.getId(Blocks.ENDER_CHEST.asItem()))
                + " offhand=" + playerContext.player().getOffhandItem().getCount() + "x" + playerContext.player().getOffhandItem().getItem()
                + " ground=" + obsidianOnGroundNearby() + " slot8=" + inventory.get(8).getItem() + " other=[" + others + "]";
    }

    public int enderChestSlotsInventory() {
        int slots = 0;
        for (int i = 0; i < 36; i++) {
            if (playerContext.player().getInventory().getNonEquipmentItems().get(i).is(Blocks.ENDER_CHEST.asItem())) {
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
            ItemStack stack = playerContext.player().getInventory().getNonEquipmentItems().get(i);
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
        int chests = ctx.inventory().getItemCountInventory(Item.getId(Blocks.ENDER_CHEST.asItem()));
        if (chests - enderChestFarmKeep(chests, 0, ground) > 0) {
            return true; // what we carry already farms
        }
        // Otherwise the cycle loots a stack first. It lands in a free slot (or merges into a partial),
        // so the live slot counts already describe the inventory after that loot; only the total
        // changes. Fewer loose chests than the keep setting is the common way to get here.
        return (chests + 64) - enderChestFarmKeep(chests + 64, 0, ground, 64) > 0;
    }

    /** Ender chests to break for {@code need} obsidian with {@code have} on hand */
    public static int chestsForObsidian(int need, int have) {
        return Math.max(0, need - have + 7) / 8;
    }

    /** The farm's keep once it may break no more than {@code runChests} (-1: no cap) of {@code chests} */
    public static int farmKeepForRun(int keep, int chests, int runChests) {
        return runChests < 0 ? keep : Math.max(keep, chests - runChests);
    }

    /**
     * Obsidian still to place from the check-back slice to the end, or -1 when the run has no end,
     * any of it is out of view, or it needs more than highwayFarmForRunMaxObsidian. Rescanned at
     * most once a second. In between, what we spent of {@code have} since the scan went into cells
     * the scan counted, so the need drops with it: held against a live count, the scan's own need
     * comes up short as soon as the printer spends the few spare blocks a farm rounds up to.
     */
    public int runObsidianNeed(int have) {
        int tick = playerContext.player().tickCount;
        if (runObsidianNeedTick >= 0 && tick >= runObsidianNeedTick && tick - runObsidianNeedTick < 20) {
            if (runObsidianNeed < 0) {
                return -1;
            }
            return Math.max(0, runObsidianNeed - Math.max(0, runObsidianNeedHave - have));
        }
        runObsidianNeedTick = tick;
        runObsidianNeedHave = have;
        runObsidianNeed = scanRunObsidianNeed();
        return runObsidianNeed;
    }

    private int scanRunObsidianNeed() {
        int max = settings.highwayFarmForRunMaxObsidian.value;
        if (layout.endPos() == null || max <= 0) {
            return -1;
        }
        // The range isHighwayEndComplete scans, so the count is what stands between us and the end
        Vec3 direction = new Vec3(layout.highwayDirection().getX(), layout.highwayDirection().getY(), layout.highwayDirection().getZ());
        Vec3 feet = new Vec3(playerContext.playerFeet().getX(), playerContext.playerFeet().getY(), playerContext.playerFeet().getZ());
        BetterBlockPos feetClosest = layout.getClosestPoint(layout.originVector(), direction, feet, LocationType.HighwayBuild);
        BlockPos startPos = layout.sliceAlongLine(layout.originVector(), new Vec3(feetClosest.getX(), feetClosest.getY(), feetClosest.getZ()), -layout.highwayCheckBackDistance(), LocationType.HighwayBuild);
        int distanceToCheck = layout.stepsAlongHighway(startPos, layout.endPos()) + 1;
        int scanT0 = layout.pattern().isCustom() ? layout.sliceIndexOf(startPos, layout.originVector()) : 0;
        int need = 0;
        for (int i = distanceToCheck - 1; i >= 1; i--) {
            BlockPos curPos = layout.pattern().isCustom()
                    ? startPos.offset(layout.sliceDelta(scanT0, i))
                    : startPos.offset(i * layout.highwayDirection().getX(), 0, i * layout.highwayDirection().getZ());
            CompositeSchematic sliceSchem = layout.schematicForSlice(scanT0 + i, curPos);
            for (int y = 0; y < layout.schematic().heightY(); y++) {
                for (int z = 0; z < layout.schematic().lengthZ(); z++) {
                    for (int x = 0; x < layout.schematic().widthX(); x++) {
                        BlockPos pos = new BlockPos(x + curPos.getX(), y + curPos.getY(), z + curPos.getZ());
                        if (!baritone.bsi.worldContainsLoadedChunk(pos.getX(), pos.getZ())) {
                            return -1;
                        }
                        BlockState current = playerContext.world().getBlockState(pos);
                        if (current.is(Blocks.OBSIDIAN) || current.is(Blocks.CRYING_OBSIDIAN) || !sliceSchem.inSchematic(x, y, z, current)) {
                            continue;
                        }
                        ISchematic sub = sliceSchem.getSchematic(x, y, z, current).schematic;
                        if (!(sub instanceof WhiteBlackSchematic wb) || wb.serverRequirement() != WhiteBlackSchematic.ServerRequirement.OBSIDIAN) {
                            continue;
                        }
                        if (wb.isValidIfUnder() && MovementHelper.isBlockNormalCube(playerContext.world().getBlockState(pos.above()))) {
                            continue;
                        }
                        if (++need > max) {
                            return -1;
                        }
                    }
                }
            }
        }
        return need;
    }

    /** Ender chests the farm being set up may break for the run, -1 for no cap */
    public int farmRunChests() {
        return farmRunChests;
    }

    public void setFarmRunChests(int farmRunChests) {
        this.farmRunChests = farmRunChests;
    }

    /** The farm being set up breaks loose chests only and never places the ender chest shulker */
    public boolean farmLooseChests() {
        return farmLooseChests && layout.paving();
    }

    public void setFarmLooseChests(boolean farmLooseChests) {
        this.farmLooseChests = farmLooseChests;
    }

    public void resetFarmForRun() {
        runObsidianNeedTick = -1;
        farmRunChests = -1;
        farmLooseChests = false;
    }

    public boolean looseChestsCoverRun(int runChests) {
        return runChests >= 0 && enderChestCountAll() - settings.highwayEnderChestsToKeep.value >= runChests;
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

    /**
     * One tick of calibrating on the chest at pos, which the server has: makes pos the server's dig
     * target and breaks the chest with a stop once the server's dig timer covers it, rather than
     * mining it out client-side. A target already at pos from an earlier dig, usually the box mined
     * there, needs no start of its own, but the pipeline would stall on a wrong one, so this chest
     * checks it first: a stop with a resync behind it, then {@link #farmChestCleared} once the
     * chest goes. If it's still there once the resync is answered, the target is elsewhere.
     *
     * @return whether this tick broke the chest and calibrated pos
     */
    public boolean calibrationTick(BlockPos pos) {
        if (playerContext.minecraft().gameMode == null) return false;
        Direction face = faceMineTarget(pos);
        instantMineDirection = face;
        instantMineLastBlock = pos;
        if (calibrationProbing) {
            if (!farmPlaceLocConfirmed()) {
                return false;
            }
            calibrationProbing = false;
        }

        ServerDigTarget target = baritone.getServerDigTarget();
        boolean here = target.isTarget(pos);
        boolean ours = here && target.startTick() == calibrationStart;
        IPlayerController controller = playerContext.playerController();
        if (!ours && (!here || calibrationProbed)) {
            controller.setHittingBlock(false); // a fresh start, not more of a dig under way
            controller.syncHeldItem();
            controller.clickBlock(pos, face);
            playerContext.player().swing(InteractionHand.MAIN_HAND);
            // Never mined out client-side: left hitting, the attack key we don't hold aborts it.
            controller.setHittingBlock(false);
            calibrationStart = target.startTick();
            return false;
        }
        if (!target.stopBreaks(pos, baritone.getServerDigSpeed().progress(playerContext.world().getBlockState(pos), pos))) {
            return false;
        }
        if (ours) {
            controller.syncHeldItem();
            controller.finishBlock(pos, face);
            playerContext.player().swing(InteractionHand.MAIN_HAND);
            try {
                controller.setDestroyDelay(0);
            } catch (Exception ignored) {}
            return true;
        }
        // the chest on screen is the server's only once our place is answered
        if (farmPlacesInFlight() > 0) {
            return false;
        }
        instantMineTick(pos);
        farmPlaceLocConfirmed(); // sends the resync, after the stop
        calibrationProbing = true;
        calibrationProbed = true;
        return false;
    }

    /**
     * placeLoc shows air. While a calibration probe is out nothing but the server clears the
     * chest, which means the probe's stop found the target.
     */
    public void farmChestCleared() {
        if (calibrationProbing) {
            setInstantMineCalibrated(true);
        }
    }

    public void instantMineTick(BlockPos pos) {
        if (playerContext.minecraft().gameMode == null) return;
        Direction face = faceMineTarget(pos);
        instantMineDirection = face;
        instantMineLastBlock = pos;
        farmResyncSequence = -1; // the server's earlier reply predates this dig
        playerContext.player().connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, face));
        playerContext.player().connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        try {
            playerContext.playerController().setDestroyDelay(0);
        } catch (Exception ignored) {}
    }

    public Rotation farmAim(Rotation rotation) {
        float wobble = (playerContext.player().tickCount & 1) == 0 ? 0.5f : -0.5f;
        return new Rotation(rotation.getYaw(), rotation.getPitch() + wobble);
    }

    private Direction faceMineTarget(BlockPos pos) {
        double reach = playerContext.playerController().getBlockReachDistance();
        Optional<Rotation> rotation = RotationUtils.reachable(playerContext, pos, reach);
        if (rotation.isPresent()) {
            baritone.getLookBehavior().updateTarget(farmAim(rotation.get()), true);
            HitResult hit = RayTraceUtils.rayTraceTowards(playerContext.player(), rotation.get(), reach);
            if (hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(pos)) {
                return blockHit.getDirection();
            }
        }
        return instantMineDirection != null ? instantMineDirection : Direction.UP;
    }
}
