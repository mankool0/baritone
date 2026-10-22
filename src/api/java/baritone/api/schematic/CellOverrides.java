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

package baritone.api.schematic;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * World boxes that override a highway schematic cell by cell: {@code highwayCellOverrides}, parsed.
 * Entries are {@code kind x1 y1 z1 x2 y2 z2}, joined by {@code ;}, each an inclusive box with its
 * low corner first. Where a skip and a storage box overlap, skip wins.
 */
public final class CellOverrides {

    public enum Kind {
        /** Out of the schematic: never dug, never placed. */
        SKIP,
        /** Air, an ender chest or any shulker box is correct; anything else is dug, nothing placed. */
        STORAGE
    }

    public static final CellOverrides NONE = new CellOverrides(new Box[0]);

    /** A box spanning more chunk columns than this is checked on every lookup instead of bucketed. */
    private static final int MAX_BUCKETED_CHUNKS = 64;

    private record Box(Kind kind, int x1, int y1, int z1, int x2, int y2, int z2) {
        boolean contains(int x, int y, int z) {
            return x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2;
        }
    }

    private final Box[] boxes;
    private final Long2ObjectOpenHashMap<Box[]> byChunk = new Long2ObjectOpenHashMap<>();
    private final Box[] unbucketed;
    private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
    private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

    private CellOverrides(Box[] boxes) {
        this.boxes = boxes;
        Long2ObjectOpenHashMap<List<Box>> buckets = new Long2ObjectOpenHashMap<>();
        List<Box> big = new ArrayList<>();
        for (Box b : boxes) {
            minX = Math.min(minX, b.x1);
            minY = Math.min(minY, b.y1);
            minZ = Math.min(minZ, b.z1);
            maxX = Math.max(maxX, b.x2);
            maxY = Math.max(maxY, b.y2);
            maxZ = Math.max(maxZ, b.z2);
            long chunks = ((long) (b.x2 >> 4) - (b.x1 >> 4) + 1) * ((long) (b.z2 >> 4) - (b.z1 >> 4) + 1);
            if (chunks > MAX_BUCKETED_CHUNKS) {
                big.add(b);
                continue;
            }
            for (int cx = b.x1 >> 4; cx <= b.x2 >> 4; cx++) {
                for (int cz = b.z1 >> 4; cz <= b.z2 >> 4; cz++) {
                    buckets.computeIfAbsent(chunkKey(cx, cz), k -> new ArrayList<>()).add(b);
                }
            }
        }
        buckets.long2ObjectEntrySet().forEach(e -> byChunk.put(e.getLongKey(), e.getValue().toArray(new Box[0])));
        unbucketed = big.toArray(new Box[0]);
    }

    /**
     * @throws IllegalArgumentException naming the first entry that isn't one, so a value the
     *                                  builder can't read is refused rather than built without
     */
    public static CellOverrides parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        String[] entries = raw.split(";", -1);
        Box[] boxes = new Box[entries.length];
        for (int i = 0; i < entries.length; i++) {
            boxes[i] = parseEntry(entries[i], i);
        }
        return new CellOverrides(boxes);
    }

    private static Box parseEntry(String entry, int index) {
        String[] f = entry.trim().split("\\s+");
        String where = "entry " + (index + 1) + " \"" + entry.trim() + "\"";
        if (f.length != 7) {
            throw new IllegalArgumentException(where + ": expected kind x1 y1 z1 x2 y2 z2");
        }
        Kind kind = switch (f[0]) {
            case "skip" -> Kind.SKIP;
            case "storage" -> Kind.STORAGE;
            default -> throw new IllegalArgumentException(where + ": unknown kind \"" + f[0] + "\"");
        };
        int[] c = new int[6];
        for (int j = 0; j < 6; j++) {
            try {
                c[j] = Integer.parseInt(f[j + 1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(where + ": \"" + f[j + 1] + "\" is not a coordinate");
            }
        }
        if (c[0] > c[3] || c[1] > c[4] || c[2] > c[5]) {
            throw new IllegalArgumentException(where + ": the low corner has to come first");
        }
        return new Box(kind, c[0], c[1], c[2], c[3], c[4], c[5]);
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public boolean isEmpty() {
        return boxes.length == 0;
    }

    public int size() {
        return boxes.length;
    }

    /** What a storage cell keeps, besides air. */
    public static boolean isStorageBlock(BlockState state) {
        return state.getBlock() == Blocks.ENDER_CHEST || state.getBlock() instanceof ShulkerBoxBlock;
    }

    /** What overrides this world cell, or null for nothing. */
    public Kind kindAt(int x, int y, int z) {
        if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) {
            return null;
        }
        Kind found = scan(byChunk.get(chunkKey(x >> 4, z >> 4)), x, y, z, null);
        return found == Kind.SKIP ? found : scan(unbucketed, x, y, z, found);
    }

    private static Kind scan(Box[] in, int x, int y, int z, Kind found) {
        if (in == null) {
            return found;
        }
        for (Box b : in) {
            if (b.contains(x, y, z)) {
                if (b.kind == Kind.SKIP) {
                    return Kind.SKIP;
                }
                found = b.kind;
            }
        }
        return found;
    }

    /**
     * {@code schematic} as it applies with its origin at {@code origin}: itself when there is nothing
     * to override. The binding is fixed; a schematic moved to another origin has to be bound again.
     */
    public CompositeSchematic bind(CompositeSchematic schematic, Vec3i origin) {
        if (isEmpty() || schematic == null) {
            return schematic;
        }
        if (schematic instanceof CellOverrideSchematic bound) {
            schematic = bound.inner();
        }
        return new CellOverrideSchematic(schematic, this, origin);
    }
}
