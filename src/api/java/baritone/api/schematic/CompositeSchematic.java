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

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.block.state.BlockState;

public class CompositeSchematic extends AbstractSchematic {

    private final List<CompositeSchematicEntry> schematics;
    private CompositeSchematicEntry[] schematicArr;

    private void recalcArr() {
        schematicArr = schematics.toArray(new CompositeSchematicEntry[0]);
        for (CompositeSchematicEntry entry : schematicArr) {
            this.x = Math.max(x, entry.x + entry.schematic.widthX());
            this.y = Math.max(y, entry.y + entry.schematic.heightY());
            this.z = Math.max(z, entry.z + entry.schematic.lengthZ());
        }
    }

    public CompositeSchematic(int x, int y, int z) {
        super(x, y, z);
        schematics = new ArrayList<>();
        recalcArr();
    }

    public void put(ISchematic extra, int x, int y, int z) {
        schematics.add(new CompositeSchematicEntry(extra, x, y, z));
        recalcArr();
    }

    /**
     * @return a new flat composite holding {@code copies} copies of this composite's entries, each
     * successive copy shifted by the step vector. Offsets are normalized to stay non-negative, so
     * for a negative step component the caller must offset the build origin by
     * {@code (copies - 1) * step} on that axis to keep the first copy where it was.
     */
    public CompositeSchematic repeated(int stepX, int stepY, int stepZ, int copies) {
        int shiftX = stepX < 0 ? -(copies - 1) * stepX : 0;
        int shiftY = stepY < 0 ? -(copies - 1) * stepY : 0;
        int shiftZ = stepZ < 0 ? -(copies - 1) * stepZ : 0;
        CompositeSchematic repeated = new CompositeSchematic(0, 0, 0);
        for (int k = 0; k < copies; k++) {
            for (CompositeSchematicEntry entry : schematics) {
                repeated.put(entry.schematic, entry.x + k * stepX + shiftX, entry.y + k * stepY + shiftY, entry.z + k * stepZ + shiftZ);
            }
        }
        return repeated;
    }

    public CompositeSchematicEntry getSchematic(int x, int y, int z, BlockState currentState) {
        for (CompositeSchematicEntry entry : schematicArr) {
            if (x >= entry.x && y >= entry.y && z >= entry.z &&
                    entry.schematic.inSchematic(x - entry.x, y - entry.y, z - entry.z, currentState)) {
                return entry;
            }
        }
        return null;
    }

    @Override
    public boolean inSchematic(int x, int y, int z, BlockState currentState) {
        CompositeSchematicEntry entry = getSchematic(x, y, z, currentState);
        return entry != null && entry.schematic.inSchematic(x - entry.x, y - entry.y, z - entry.z, currentState);
    }

    @Override
    public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
        CompositeSchematicEntry entry = getSchematic(x, y, z, current);
        if (entry == null) {
            throw new IllegalStateException("couldn't find schematic for this position");
        }
        return entry.schematic.desiredState(x - entry.x, y - entry.y, z - entry.z, current, approxPlaceable);
    }

    @Override
    public void reset() {
        for (CompositeSchematicEntry entry : schematicArr) {
            entry.schematic.reset();
        }
    }
}
