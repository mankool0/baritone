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

import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.input.Input;
import baritone.behavior.highway.enums.LocationType;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The walk-creep's footing and portal checks, stepping out of a portal, and facing down the highway.
 */
public class HighwayWalk extends ContextComponent {
    private boolean walkCentering = false;

    private static final int CREEP_FLOOR_LOOKAHEAD = 3;
    // past where friction stops the player, for the creep's steering to move it about
    private static final double EDGE_SLIDE_MARGIN = 0.1;

    HighwayWalk(HighwayContext ctx) {
        super(ctx);
    }

    /**
     * Whether what's left of the player's slide, with this tick's step on top when it walks, would
     * carry its hitbox off the floor anywhere along the way. With a sneak down, vanilla stops the
     * player at the edge instead (Player.maybeBackOffFromEdge), half out over the gap.
     */
    public boolean wouldSlideOffFloor(boolean walking) {
        LocalPlayer player = playerContext.player();
        BetterBlockPos feet = playerContext.playerFeet();
        float friction = playerContext.world().getBlockState(feet.below()).getBlock().getFriction();
        double moveX = player.getDeltaMovement().x;
        double moveZ = player.getDeltaMovement().z;
        if (walking) {
            // a tick of walking along the yaw, the way LivingEntity.travel accelerates on the ground
            double step = player.getAttributeValue(Attributes.MOVEMENT_SPEED) * (0.21600002 / (friction * friction * friction)) * 0.98;
            double yaw = Math.toRadians(player.getYRot());
            moveX -= Math.sin(yaw) * step;
            moveZ += Math.cos(yaw) * step;
        }
        double speed = Math.sqrt(moveX * moveX + moveZ * moveZ);
        if (speed < 1.0E-4) {
            return false;
        }
        // every tick friction keeps friction * 0.91 of it, so this is how far it goes in all
        double slide = speed / (1 - friction * 0.91) + EDGE_SLIDE_MARGIN;
        int floorY = feet.y - 1;
        for (double along = Math.min(0.25, slide); ; along = Math.min(along + 0.25, slide)) {
            AABB box = player.getBoundingBox().move(moveX / speed * along, 0, moveZ / speed * along);
            if (!overFloor(box, floorY)) {
                return true;
            }
            if (along >= slide) {
                return false;
            }
        }
    }

    // a hitbox's footprint touches something to stand on, shrunk like vanilla's edge check
    private boolean overFloor(AABB box, int floorY) {
        for (int x = Mth.floor(box.minX + 1.0E-7); x <= Mth.floor(box.maxX - 1.0E-7); x++) {
            for (int z = Mth.floor(box.minZ + 1.0E-7); z <= Mth.floor(box.maxZ - 1.0E-7); z++) {
                if (MovementHelper.canWalkOn(baritone.bsi, x, floorY, z)) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean canWalkOnFloorAhead() {
        BetterBlockPos feet = playerContext.playerFeet();
        int dirX = layout().highwayDirection().getX();
        int dirZ = layout().highwayDirection().getZ();
        int floorY = feet.y - 1;

        int lookahead = Math.max(1, Math.min(CREEP_FLOOR_LOOKAHEAD, settings.highwayEndDistance.value));
        if (layout().pattern().isCustom()) {
            int t = layout().sliceIndexNear(feet.x, feet.z);
            Vec3i s1 = layout().pattern().worldStep(t);
            Vec3i s2 = layout().pattern().worldStep(t + 1);
            if (!MovementHelper.canWalkOn(baritone.bsi, feet.x + s1.getX(), floorY, feet.z + s1.getZ())) {
                return false;
            }
            if ((s1.getX() != s2.getX() || s1.getZ() != s2.getZ())
                    && !MovementHelper.canWalkOn(baritone.bsi, feet.x + s1.getX() + s2.getX(), floorY, feet.z + s1.getZ() + s2.getZ())) {
                // approaching a jog: the creep walks the average heading, so the hitbox crosses
                // the corner cell as well
                return false;
            }
        } else {
            // Blocks we'll be standing on after stepping forward, as many steps as fit in the
            // lookahead's blocks of travel. A diagonal step is sqrt(2) blocks, so counting steps
            // asked for floor 4.2 blocks ahead, past what the printer places from where the walk
            // stops, and the creep could only wait for the builder's stationary fallback.
            int steps = Math.max(1, (int) (lookahead / layout().stepLength()));
            for (int d = 1; d <= steps; d++) {
                if (!MovementHelper.canWalkOn(baritone.bsi, feet.x + d * dirX, floorY, feet.z + d * dirZ)) {
                    return false;
                }
            }
            // For diagonals also check the two orthogonally-adjacent floor cells
            if (dirX != 0 && dirZ != 0
                    && !(MovementHelper.canWalkOn(baritone.bsi, feet.x + dirX, floorY, feet.z)
                    && MovementHelper.canWalkOn(baritone.bsi, feet.x, floorY, feet.z + dirZ))) {
                return false;
            }
        }
        // The key walks down the player's yaw, not the axis, and an interact aim can pull it far
        // enough sideways to drift into the outer lane columns the digging profile never floors,
        // with the axis scan above still reporting clear floor. So scan the real heading too.
        Vec3 pos = playerContext.player().position();
        double yaw = Math.toRadians(playerContext.player().getYRot());
        double headX = -Math.sin(yaw);
        double headZ = Math.cos(yaw);
        for (int d = 1; d <= lookahead; d++) {
            int x = (int) Math.floor(pos.x + headX * d);
            int z = (int) Math.floor(pos.z + headZ * d);
            if (!MovementHelper.canWalkOn(baritone.bsi, x, floorY, z)) {
                return false;
            }
        }
        return true;
    }

    public boolean canWalkThroughAhead() {
        BetterBlockPos feet = playerContext.playerFeet();
        int stepX;
        int stepZ;
        if (layout().pattern().isCustom()) {
            Vec3i s1 = layout().pattern().worldStep(layout().sliceIndexNear(feet.x, feet.z));
            stepX = s1.getX();
            stepZ = s1.getZ();
        } else {
            stepX = layout().highwayDirection().getX();
            stepZ = layout().highwayDirection().getZ();
        }
        if (!bodyPassable(feet.x + stepX, feet.x + stepX, feet.z + stepZ, feet.z + stepZ, feet.y)) {
            return false;
        }
        AABB box = playerContext.player().getBoundingBox();
        int minX = Mth.floor(box.minX + 1.0E-7);
        int maxX = Mth.floor(box.maxX - 1.0E-7);
        int minZ = Mth.floor(box.minZ + 1.0E-7);
        int maxZ = Mth.floor(box.maxZ - 1.0E-7);
        boolean xOpen = stepX == 0 || bodyPassable(feet.x + stepX, feet.x + stepX, minZ, maxZ, feet.y);
        boolean zOpen = stepZ == 0 || bodyPassable(minX, maxX, feet.z + stepZ, feet.z + stepZ, feet.y);
        return stepX != 0 && stepZ != 0 ? xOpen || zOpen : xOpen && zOpen;
    }

    private boolean bodyPassable(int minX, int maxX, int minZ, int maxZ, int feetY) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!MovementHelper.canWalkThrough(baritone.bsi, x, feetY, z)
                        || !MovementHelper.canWalkThrough(baritone.bsi, x, feetY + 1, z)) {
                    return false;
                }
            }
        }
        return true;
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
        if (layout().pattern().isCustom()) {
            int t = layout().sliceIndexNear(feet.x, feet.z);
            for (int step = 1; step <= 2; step++) {
                Vec3i d = layout().sliceDelta(t, step);
                if (isPortalCell(feet.x + d.getX(), feet.y, feet.z + d.getZ())) {
                    return false;
                }
            }
            // the average-heading walk can clip the cell beside the first step at a jog
            Vec3i s1 = layout().sliceDelta(t, 1);
            Vec3i s2 = layout().sliceDelta(t, 2);
            if ((s2.getX() != 2 * s1.getX() || s2.getZ() != 2 * s1.getZ())
                    && isPortalCell(feet.x + s2.getX() - s1.getX(), feet.y, feet.z + s2.getZ() - s1.getZ())) {
                return false;
            }
            return true;
        }
        int dirX = layout().highwayDirection().getX();
        int dirZ = layout().highwayDirection().getZ();
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

    public void faceHighwayDirection() {
        Vec3 heading = layout().highwayHeading();
        if (heading.lengthSqr() == 0) {
            return;
        }
        double dirX = heading.x;
        double dirZ = heading.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dirX, dirZ));

        // Both schematic layouts put the walk lane at cross-axis offsets [1, highwayWidth] from
        // the slice origin, so the lane center sits at origin + 1 + width/2 on the cross axis
        Vec3 playerPos = playerContext.player().position();
        BetterBlockPos sliceOrigin = layout().getClosestPoint(new Vec3(layout().originVector().x, layout().originVector().y, layout().originVector().z),
                new Vec3(layout().highwayDirection().getX(), layout().highwayDirection().getY(), layout().highwayDirection().getZ()), playerPos, LocationType.HighwayBuild);
        double laneCenterOffset = 1 + settings.highwayWidth.value / 2.0;
        boolean crossAxisZ = layout().pattern().isCustom() ? layout().pattern().majorIsX()
                : ((layout().highwayDirection().getZ() == 0 && Math.abs(layout().highwayDirection().getX()) == 1)
                || (layout().highwayDirection().getX() == 1 && layout().highwayDirection().getZ() == 1)
                || (layout().highwayDirection().getX() == -1 && layout().highwayDirection().getZ() == -1));
        double centerX = crossAxisZ ? sliceOrigin.x + 0.5 : sliceOrigin.x + laneCenterOffset;
        double centerZ = crossAxisZ ? sliceOrigin.z + laneCenterOffset : sliceOrigin.z + 0.5;

        // Signed offset toward the yaw+90 side; steering by -offset walks it back toward zero
        double lateral = (playerPos.x - centerX) * -dirZ + (playerPos.z - centerZ) * dirX;

        double halfLane = settings.highwayWidth.value / 2.0;
        if (layout().pattern().isCustom()) {
            // corridor half-width measured perpendicular to the average travel direction
            halfLane *= layout().pattern().periodMajorBlocks() / Math.hypot(layout().pattern().periodMajorBlocks(), layout().pattern().periodMinorBlocks());
        } else if (layout().highwayDirection().getX() != 0 && layout().highwayDirection().getZ() != 0) {
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
}
