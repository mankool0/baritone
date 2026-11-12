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

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Spots the builder has given up breaking a block from. A break goal takes any spot beside the
 * block or beside the cell above it, and standing in one is all it asks: a block that can't be
 * seen from there is never broken, and since the goal is met nothing ever paths us anywhere else.
 * A wall sign hung on the side of the block we stand on can hold us there indefinitely that way.
 * <p>
 * So once we have stood in a goal {@link #STALL_TICKS} ticks running with nothing in reach to
 * break and nothing placed, the spot is struck off the goal of each block that pinned us there,
 * and the next path goes round to another side. A block that has had {@link #SPOTS_PER_BLOCK}
 * spots struck off is one we can't get at, and the caller gives up on it out loud.
 * <p>
 * Positions are kept as {@link BlockPos#asLong} values: a {@code BetterBlockPos} hashes
 * differently from a {@code BlockPos} at the same place, and both kinds come through here.
 */
public final class BreakGoalStall {
    /** Ticks pinned in a row before the spot we stand on is struck off. */
    public static final int STALL_TICKS = 40;
    /** Spots struck off one block before it counts as out of reach from anywhere. */
    public static final int SPOTS_PER_BLOCK = 3;

    private record Struck(BlockState state, Set<Long> spots) {}

    private final Map<Long, Struck> struck = new HashMap<>();
    private int pinnedTicks;
    private boolean checkedThisTick = true;

    /**
     * The start of a builder tick. A tick that returned before it got as far as {@link #pinned}
     * was busy with something else - a break, a place, a hold - so it ends the run.
     */
    public void startTick() {
        if (!checkedThisTick) {
            pinnedTicks = 0;
        }
        checkedThisTick = false;
    }

    /**
     * Notes whether we are pinned this tick.
     *
     * @return true on the {@link #STALL_TICKS}th pinned tick in a row, after which the run starts over
     */
    public boolean pinned(boolean pinned) {
        checkedThisTick = true;
        if (!pinned) {
            pinnedTicks = 0;
            return false;
        }
        if (++pinnedTicks < STALL_TICKS) {
            return false;
        }
        pinnedTicks = 0;
        return true;
    }

    /** The spots struck off {@code block}'s goal, as a copy safe to hand a path calculation; empty if none. */
    public Set<Long> struckSpots(BlockPos block) {
        Struck entry = struck.get(block.asLong());
        return entry == null ? Set.of() : Set.copyOf(entry.spots);
    }

    /** How many spots have been struck off {@code block}'s goal. */
    public int struckCount(BlockPos block) {
        Struck entry = struck.get(block.asLong());
        return entry == null ? 0 : entry.spots.size();
    }

    /**
     * Strikes {@code stand} off {@code block}'s goal, {@code state} being what the block is now.
     *
     * @return how many spots that block has had struck off, this one included
     */
    public int strike(BlockPos block, BlockState state, BlockPos stand) {
        Struck entry = struck.computeIfAbsent(block.asLong(), k -> new Struck(state, new HashSet<>()));
        entry.spots.add(stand.asLong());
        return entry.spots.size();
    }

    /** The blocks that have had a spot struck off, as {@link BlockPos#asLong} values. */
    public Set<Long> struckBlocks() {
        return Set.copyOf(struck.keySet());
    }

    /**
     * Forgets the blocks that aren't what they were when their first spot was struck off: broken,
     * or replaced, which makes them a different job that may well be reachable from where we were.
     */
    public void forgetChanged(Function<BlockPos, BlockState> world) {
        if (!struck.isEmpty()) {
            struck.entrySet().removeIf(e -> world.apply(BlockPos.of(e.getKey())) != e.getValue().state);
        }
    }

    public boolean isEmpty() {
        return struck.isEmpty();
    }

    /** A new build: nothing struck off, no run going. */
    public void clear() {
        struck.clear();
        pinnedTicks = 0;
        checkedThisTick = true;
    }
}
