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

import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;

/**
 * Position -> state cache for {@link BlockStateInterface#get0} over one path search. The 22 movements
 * out of one node read the same 50 blocks about 110 times between them, and the next node reads most
 * of them again. 64k entries hits ~80% of the time, 16k only managed 73%, so 64k it is.
 * <p>
 * One table per thread, reused by every search that thread runs, for the same reasons as
 * {@link baritone.pathing.movement.MiningDurationCache}: the key array is a 512 KiB humongous object on
 * heaps under 4 GB, every threaded context gets its own BlockStateInterface, and the main thread keeps
 * using a context's BlockStateInterface while a search runs on it.
 */
public final class BlockStateCache {

    static final int BITS = 16;

    private static final ThreadLocal<BlockStateCache> PER_THREAD = ThreadLocal.withInitial(BlockStateCache::new);

    final Thread owner = Thread.currentThread();
    final long[] keys = new long[1 << BITS];
    final BlockState[] vals = new BlockState[1 << BITS];

    private BlockStateCache() {}

    /**
     * @return this thread's table, emptied. Blocks can change between searches.
     */
    public static BlockStateCache emptyForCurrentThread() {
        BlockStateCache cache = PER_THREAD.get();
        // -1 would be a block at shifted y=4095. good luck
        Arrays.fill(cache.keys, -1L);
        return cache;
    }
}
