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

package baritone.pathing.movement;

import java.util.Arrays;

/**
 * Memo for {@link MovementHelper#getMiningDurationTicks} over one path search, keyed by position.
 * <p>
 * The block under you gets its break cost worked out by four descends, a downward, and then all your
 * neighbours' descends, and every one of those reads five more blocks for avoidBreaking. The answer
 * doesn't change mid search, so it's remembered. Two of everything because includeFalling is part of
 * the question.
 * <p>
 * One table per thread, reused by every search that thread runs, rather than one per
 * {@link CalculationContext}: the tables are 4 x 512 KiB, which G1 allocates as humongous objects on
 * heaps under 4 GB, and contexts are made every tick whether or not a search ever uses them. {@link baritone.pathing.calc.AbstractNodeCostSearch} attaches the
 * table to the context for the length of the search, and only the owning thread reads it, so the main
 * thread's cost checks on the same context never race the search.
 */
public final class MiningDurationCache {

    static final int BITS = 16;

    private static final ThreadLocal<MiningDurationCache> PER_THREAD = ThreadLocal.withInitial(MiningDurationCache::new);

    final Thread owner = Thread.currentThread();
    final long[] keys = new long[1 << BITS];
    final long[] keysFalling = new long[1 << BITS];
    final double[] vals = new double[1 << BITS];
    final double[] valsFalling = new double[1 << BITS];

    private MiningDurationCache() {}

    /**
     * @return this thread's table, emptied. A search is only valid against one context's settings and
     * tools, so nothing may carry over from the previous one.
     */
    public static MiningDurationCache emptyForCurrentThread() {
        MiningDurationCache cache = PER_THREAD.get();
        // -1 is the "nothing here" key, see getMiningDurationTicks for why that's safe
        Arrays.fill(cache.keys, -1L);
        Arrays.fill(cache.keysFalling, -1L);
        return cache;
    }
}
