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

import baritone.api.utils.VecUtils;
import baritone.behavior.highway.enums.HighwayBlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;

/**
 * What the liquid removal keeps between its states: the source blocks it found, and whether placing
 * through walls is still working.
 */
public class LiquidRemoval extends ContextComponent {
    private final ArrayList<BlockPos> sourceBlocks = new ArrayList<>();

    private boolean liquidPathingCanMine = true;

    private int throughWallPlaceStreak = 0;
    private BlockPos throughWallVerifyPos = null;
    private int throughWallVerifyAge = 0;
    private boolean throughWallsSuppressed = false;

    LiquidRemoval(HighwayContext ctx) {
        super(ctx);
    }

    public ArrayList<BlockPos> sourceBlocks() {
        return sourceBlocks;
    }

    public void clearSourceBlocks() {
        sourceBlocks.clear();
    }

    public boolean liquidPathingCanMine() {
        return liquidPathingCanMine;
    }

    public void setLiquidPathingCanMine(boolean liquidPathingCanMine) {
        this.liquidPathingCanMine = liquidPathingCanMine;
    }

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
}
