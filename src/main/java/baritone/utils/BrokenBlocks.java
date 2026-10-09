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

import java.util.function.Function;

/**
 * The last blocks broken, and what each one was.
 */
public final class BrokenBlocks {

    private static final int KEPT = 32;

    private final BlockPos[] pos = new BlockPos[KEPT];
    private final BlockState[] was = new BlockState[KEPT];
    private long count;

    public void add(BlockPos at, BlockState state) {
        int i = (int) (count % KEPT);
        pos[i] = at.immutable();
        was[i] = state;
        count++;
    }

    public long count() {
        return count;
    }

    /**
     * Is a block broken after the first {@code since} still gone? A server that puts it back has
     * undone the break, so a bot digging the same ghost block over and over is not making progress.
     */
    public boolean stayedGoneSince(long since, Function<BlockPos, BlockState> world) {
        for (long n = Math.max(since, count - KEPT); n < count; n++) {
            int i = (int) (n % KEPT);
            if (world.apply(pos[i]) != was[i]) {
                return true;
            }
        }
        return false;
    }
}
