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

package baritone.utils;

import baritone.Baritone;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.IPlayerController;
import baritone.api.utils.Rotation;
import baritone.api.utils.VecUtils;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The printer's clicks: which ones the server takes from where we stand without a rotation, the hit
 * to send for one, what a place puts down, and which breaks finish within a tick. The builder's
 * printer and backfill both click this way.
 */
public final class PrinterClicks {

    private PrinterClicks() {}

    /**
     * Whether the server takes a no-rotation place against {@code face} of {@code clicked} from a
     * player standing at {@code feet}. Grim's FarPlace measures to the closest point of the clicked
     * block from every eye height the player could have, crouching included, whatever its pose. That
     * is the reach the stationary place path gets by crouching for real, so measuring the face centre
     * from the standing eye leaves a ring of cells just past the printer that the stationary path
     * stops and crouches for every time the printer runs out of work in reach. Grim's PositionPlace
     * also wants the clicked face turned towards those eyes, and measured to the block rather than
     * the face, a face on its far side would pass the reach check as easily as a near one.
     */
    public static boolean placeReaches(Player player, BlockPos clicked, Direction face, Vec3 feet, double reachSq) {
        double standingEye = feet.y + player.getEyeHeight(Pose.STANDING);
        double crouchingEye = feet.y + player.getEyeHeight(Pose.CROUCHING);
        int x = clicked.getX();
        int y = clicked.getY();
        int z = clicked.getZ();
        boolean facing = switch (face) {
            case UP -> standingEye >= y + 1;
            case DOWN -> crouchingEye <= y;
            case EAST -> feet.x >= x + 1;
            case WEST -> feet.x <= x;
            case SOUTH -> feet.z >= z + 1;
            case NORTH -> feet.z <= z;
        };
        AABB box = new AABB(x, y, z, x + 1, y + 1, z + 1);
        return facing && (box.distanceToSqr(new Vec3(feet.x, standingEye, feet.z)) <= reachSq
                || box.distanceToSqr(new Vec3(feet.x, crouchingEye, feet.z)) <= reachSq);
    }

    /**
     * Whether a no-rotation dig reaches {@code pos} from both eyes: dig packets carry only the
     * position and face, and the vanilla server measures to the closest point of the block.
     */
    public static boolean breakReaches(BlockPos pos, Vec3 eye, Vec3 predictedEye, double reachSq) {
        AABB cell = new AABB(pos);
        return cell.distanceToSqr(eye) <= reachSq && cell.distanceToSqr(predictedEye) <= reachSq;
    }

    /**
     * A click on the centre of {@code face} of {@code clicked}
     */
    public static BlockHitResult faceHit(BlockPos clicked, Direction face) {
        Vec3 hitVec = VecUtils.getBlockPosCenter(clicked)
                .add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        // plain BlockPos on purpose: the hit result flows into vanilla world mutation, and
        // BetterBlockPos's different hashCode must not leak into the block entity map
        return new BlockHitResult(hitVec, face, new BlockPos(clicked.getX(), clicked.getY(), clicked.getZ()), false);
    }

    /**
     * @return what placing {@code stack} with this click and rotation puts down, or null if it
     * places nothing
     */
    public static BlockState wouldPlace(IPlayerContext ctx, ItemStack stack, BlockHitResult hit, Rotation rot) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
            return null;
        }
        float originalYaw = ctx.player().getYRot();
        float originalPitch = ctx.player().getXRot();
        // the state depends on the facing of the player sometimes
        ctx.player().setYRot(rot.getYaw());
        ctx.player().setXRot(rot.getPitch());
        BlockPlaceContext meme = new BlockPlaceContext(new UseOnContext(
                ctx.world(),
                ctx.player(),
                InteractionHand.MAIN_HAND,
                stack,
                hit
        ) {}); // that {} gives us access to a protected constructor lmfao
        BlockState wouldBePlaced = ((BlockItem) stack.getItem()).getBlock().getStateForPlacement(meme);
        ctx.player().setYRot(originalYaw);
        ctx.player().setXRot(originalPitch);
        return wouldBePlaced != null && meme.canPlace() ? wouldBePlaced : null;
    }

    /**
     * Blocks that would open a GUI or otherwise activate when right clicked; the legit place path
     * handles those by sneaking, the printer just leaves them alone.
     */
    public static boolean avoidClicking(BlockState state) {
        Block block = state.getBlock();
        return block instanceof EntityBlock
                || block instanceof DoorBlock
                || block instanceof TrapDoorBlock
                || block instanceof FenceGateBlock
                || block instanceof ButtonBlock
                || block instanceof LeverBlock
                || block instanceof CraftingTableBlock
                || block instanceof AnvilBlock
                || block instanceof CakeBlock
                || block instanceof RepeaterBlock
                || block instanceof ComparatorBlock
                || block instanceof NoteBlock
                || block instanceof BedBlock;
    }

    /**
     * The per-tick dig progress the server will see with the tool
     * {@link MovementHelper#switchToBestToolFor} would pick: its own judgment of a break as instant
     * can lag the client's, so this is 0 while only the client's has it instant. Probes with the
     * same switch the dig itself performs, so aiming and firing can never disagree about what is
     * instant.
     */
    public static float breakProgress(Baritone baritone, BlockState state, BlockPos pos) {
        IPlayerContext ctx = baritone.getPlayerContext();
        var inventory = ctx.player().getInventory();
        int previous = inventory.getSelectedSlot();
        MovementHelper.switchToBestToolFor(ctx, state);
        float progress = baritone.getServerDigSpeed().progress(state, pos);
        inventory.setSelectedSlot(previous);
        return progress;
    }

    /**
     * @return the dig packets to break a block with this per-tick progress: a START for an instant
     * break, START and STOP for a near-instant one, or 0 if it can't be broken within a tick
     */
    public static int breakPackets(float progress) {
        if (progress >= 1.0f) {
            return 1;
        }
        return Baritone.settings().printerBreakNearInstant.value && progress >= IPlayerController.SERVER_FINISH_DIG_PROGRESS ? 2 : 0;
    }

    /**
     * @return whether a swap that changed this hotbar slot is still unconfirmed, so the server may
     * hold something else there. Swaps that leave the slot alone don't hold it up.
     */
    public static boolean slotUnsettled(Baritone baritone, int slot) {
        return Baritone.settings().allowInventory.value
                && baritone.getInventoryBehavior().ticksSinceHotbarMove(slot) < Baritone.settings().printerInventorySettleTicks.value;
    }

    /**
     * @param packets what a break or place would send
     * @param reserve what to leave for a place later in the tick
     * @return whether that fits under printerPacketsPerTick with what this tick already sent; the
     * tick's first action always does, unless room is being kept for a place
     */
    public static boolean packetsFit(Baritone baritone, int packets, int reserve) {
        int sent = baritone.getPacketTrace().actionsThisTick();
        return sent == 0 && reserve == 0 || sent + packets + reserve <= Baritone.settings().printerPacketsPerTick.value;
    }
}
