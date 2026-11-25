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

package baritone.process.elytra;

import baritone.api.utils.BetterBlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Somewhere near a spot for a landing moved by glide steps to come down on: a block it can stand in, with nothing in
 * the way straight up from it to the spot's height, nearest column first and in a column the highest. That's all a
 * glide moved by steps needs: it comes down a column one block wide, slowly, and stands on whatever floor its box fits
 * on. The search for a firework glide's landing wants 15 blocks of air over the floor and a 9 block bubble of it, which
 * a cave or the space under the nether roof seldom has, and when it has none the flight hovers at the end of its path.
 * <p>
 * The search goes column by column for as long as {@link #advance} is given, so it can be spread over ticks. Over lava,
 * where nothing near is a floor, it can look along the path ahead first and then much further around
 * ({@link #aheadThenAround}).
 */
public final class GlideLandingSearch {

    /**
     * The blocks the search reads
     */
    public interface World {
        /**
         * @return Whether the glide's box can go through block (x, y, z): nothing to collide with, no fluid, no fire
         */
        boolean open(int x, int y, int z);

        /**
         * @return Whether a landing can stand in block (x, y, z)
         */
        boolean standable(int x, int y, int z);

        /**
         * @return Whether the column at (x, z) is loaded, so what it holds is known
         */
        boolean loaded(int x, int z);
    }

    private final World world;
    private final int depth;
    private final int minY;
    // x, z, and the height to look down from, in the order to look
    private final List<int[]> columns;
    private int next;

    /**
     * @param from   Where to look from: its column first, and its height is where each column is looked down from
     * @param radius How far across to look
     * @param depth  How far down to look
     * @param minY   The lowest block there is
     */
    public GlideLandingSearch(World world, BetterBlockPos from, int radius, int depth, int minY) {
        this(world, around(from, radius, new HashSet<>()), depth, minY);
    }

    /**
     * @param columns x, z, and the height to look down from, in the order to look
     */
    private GlideLandingSearch(World world, List<int[]> columns, int depth, int minY) {
        this.world = world;
        this.depth = depth;
        this.minY = minY;
        this.columns = columns;
    }

    /**
     * Along {@code ahead}, the path on from the player, the columns within {@code aheadRadius} of each point, each
     * looked down from the higher of the point and {@code from}, the way the path goes; then the rest within
     * {@code radius} of {@code from}, nearest first
     *
     * @param ahead How far along the path to look, in blocks
     */
    public static GlideLandingSearch aheadThenAround(World world, BetterBlockPos from, List<BetterBlockPos> path,
                                                     double ahead, int aheadRadius, int radius, int depth, int minY) {
        final Set<Long> seen = new HashSet<>();
        final List<int[]> columns = new ArrayList<>();
        double along = 0;
        BetterBlockPos last = from;
        for (BetterBlockPos point : path) {
            along += Math.sqrt(last.distanceSq(point));
            if (along > ahead) {
                break;
            }
            last = point;
            columns.addAll(around(new BetterBlockPos(point.x, Math.max(point.y, from.y), point.z), aheadRadius, seen));
        }
        columns.addAll(around(from, radius, seen));
        return new GlideLandingSearch(world, columns, depth, minY);
    }

    /**
     * @return The columns within {@code radius} of {@code from} not in {@code seen}, nearest first, now in it
     */
    private static List<int[]> around(BetterBlockPos from, int radius, Set<Long> seen) {
        final List<int[]> columns = new ArrayList<>();
        for (int[] offset : columnsWithin(radius)) {
            final int cx = from.x + offset[0], cz = from.z + offset[1];
            if (seen.add(((long) cx << 32) ^ (cz & 0xFFFFFFFFL))) {
                columns.add(new int[]{cx, cz, from.y});
            }
        }
        return columns;
    }

    /**
     * Looks on for up to {@code budgetNanos}
     *
     * @return The block to stand in, {@code null} when there is none yet: {@link #exhausted()} says whether there's
     * nowhere left to look
     */
    public BetterBlockPos advance(long budgetNanos) {
        final long start = System.nanoTime();
        while (this.next < this.columns.size()) {
            final int[] column = this.columns.get(this.next++);
            final BetterBlockPos found = this.inColumn(column[0], column[1], column[2]);
            if (found != null) {
                return found;
            }
            if (System.nanoTime() - start >= budgetNanos) {
                return null;
            }
        }
        return null;
    }

    public boolean exhausted() {
        return this.next >= this.columns.size();
    }

    /**
     * @return The highest block in the column to stand in that is open all the way up to {@code top}
     */
    private BetterBlockPos inColumn(int cx, int cz, int top) {
        if (!this.world.loaded(cx, cz)) {
            return null;
        }
        final int bottom = Math.max(this.minY, top - this.depth);
        for (int y = top; y > bottom; y--) {
            if (!this.world.open(cx, y, cz)) {
                // something in the way down, or the floor: the block over it may be the one
                return y < top && this.world.standable(cx, y + 1, cz) ? new BetterBlockPos(cx, y + 1, cz) : null;
            }
        }
        return null;
    }

    private static List<int[]> columnsWithin(int radius) {
        final List<int[]> columns = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz <= radius * radius) {
                    columns.add(new int[]{dx, dz});
                }
            }
        }
        columns.sort(Comparator.comparingInt(c -> c[0] * c[0] + c[1] * c[1]));
        return columns;
    }
}
