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

import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Places a single block by clicking a face of one of its neighbours, with or without line of sight.
 */
public class BlockPlacer extends ContextComponent {
    BlockPlacer(HighwayContext ctx) {
        super(ctx);
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

    /**
     * The sneak state rides on the input packet since 1.21.6. LocalPlayer only resends that when
     * its own keys change, so releasing means restoring what it last sent.
     */
    private void sendShiftKey(boolean down) {
        net.minecraft.world.entity.player.Input last = playerContext.player().getLastSentInput();
        net.minecraft.world.entity.player.Input input = down ? new net.minecraft.world.entity.player.Input(last.forward(), last.backward(), last.left(), last.right(), last.jump(), true, last.sprint()) : last;
        playerContext.player().connection.send(new ServerboundPlayerInputPacket(input));
    }

    private PlaceResult clickFace(BlockHitResult blockHitResult, boolean packetSwing, InteractionHand hand) {
        final BlockPos neighbor = blockHitResult.getBlockPos();
        final Block neighborBlock = playerContext.world().getBlockState(neighbor).getBlock();
        final boolean activated = playerContext.world().getBlockState(neighbor).useWithoutItem(playerContext.world(), playerContext.player(), blockHitResult) == InteractionResult.SUCCESS;

        final boolean sneak = HighwayContext.blackList.contains(neighborBlock) || HighwayContext.shulkerBlockList.contains(neighborBlock) || activated;
        if (sneak)
        {
            sendShiftKey(true);
        }
        InteractionResult l_Result2 = playerContext.playerController().processRightClickBlock(playerContext.player(), playerContext.world(), hand, blockHitResult);
        // Release whatever the click returned. LocalPlayer won't resend its input until its own keys
        // change, so a sneak left down here turns the next box click with a block in hand into a
        // placement against the box. An offhand place the client refuses comes back FAIL.
        if (sneak)
        {
            sendShiftKey(false);
        }

        if (l_Result2 == InteractionResult.SUCCESS)
        {
            if (packetSwing)
                playerContext.player().connection.send(new ServerboundSwingPacket(hand));
            else
                playerContext.player().swing(hand);
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
}
