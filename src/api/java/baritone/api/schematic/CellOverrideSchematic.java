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

import net.minecraft.core.Vec3i;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * A highway schematic with {@link CellOverrides} applied at one world origin, consulted before the
 * schematic's own components.
 * <p>
 * A {@link CompositeSchematic} only so the builder and the highway scans, which look up the leaf
 * schematic behind a cell through {@link #getSchematic}, keep seeing one: the inner schematic's,
 * or the storage leaf. It has no entries of its own, so it can't be repeated; repeat the inner one
 * and bind the result.
 */
public final class CellOverrideSchematic extends CompositeSchematic {

    private final CompositeSchematic inner;
    private final CellOverrides overrides;
    private final int originX;
    private final int originY;
    private final int originZ;

    CellOverrideSchematic(CompositeSchematic inner, CellOverrides overrides, Vec3i origin) {
        super(inner.widthX(), inner.heightY(), inner.lengthZ());
        this.inner = inner;
        this.overrides = overrides;
        this.originX = origin.getX();
        this.originY = origin.getY();
        this.originZ = origin.getZ();
    }

    public CompositeSchematic inner() {
        return inner;
    }

    public CellOverrides overrides() {
        return overrides;
    }

    /** The same schematic with its origin at {@code origin}. */
    public CellOverrideSchematic at(Vec3i origin) {
        return new CellOverrideSchematic(inner, overrides, origin);
    }

    private CellOverrides.Kind kind(int x, int y, int z) {
        return overrides.kindAt(originX + x, originY + y, originZ + z);
    }

    @Override
    public boolean inSchematic(int x, int y, int z, BlockState currentState) {
        return kind(x, y, z) != CellOverrides.Kind.SKIP && inner.inSchematic(x, y, z, currentState);
    }

    @Override
    public CompositeSchematicEntry getSchematic(int x, int y, int z, BlockState currentState) {
        CellOverrides.Kind kind = kind(x, y, z);
        if (kind == CellOverrides.Kind.SKIP) {
            return null;
        }
        if (kind == CellOverrides.Kind.STORAGE && inner.inSchematic(x, y, z, currentState)) {
            return new CompositeSchematicEntry(StorageCell.INSTANCE, x, y, z);
        }
        return inner.getSchematic(x, y, z, currentState);
    }

    @Override
    public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
        if (kind(x, y, z) == CellOverrides.Kind.STORAGE) {
            return StorageCell.INSTANCE.desiredState(0, 0, 0, current, approxPlaceable);
        }
        return inner.desiredState(x, y, z, current, approxPlaceable);
    }

    @Override
    public void reset() {
        inner.reset();
    }

    @Override
    public void put(ISchematic extra, int x, int y, int z) {
        throw new UnsupportedOperationException("put into the inner schematic and bind it again");
    }

    @Override
    public <T extends CompositeSchematic> T repeatedInto(T repeated, int stepX, int stepY, int stepZ, int copies) {
        throw new UnsupportedOperationException("repeat the inner schematic and bind the result");
    }

    /** Another road's side storage: air or a box stays, anything else is dug. */
    private static final class StorageCell extends WhiteBlackSchematic {

        // a holder, so nothing touches Blocks before the registries are up
        static final StorageCell INSTANCE = new StorageCell();

        private StorageCell() {
            super(1, 1, 1, allowed(), Blocks.AIR.defaultBlockState(), true, false, false);
        }

        private static List<BlockState> allowed() {
            List<BlockState> states = new ArrayList<>(List.of(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(),
                    Blocks.AIR.defaultBlockState(), Blocks.ENDER_CHEST.defaultBlockState(), Blocks.SHULKER_BOX.defaultBlockState()));
            for (DyeColor color : DyeColor.values()) {
                states.add(ShulkerBoxBlock.getBlockByColor(color).defaultBlockState());
            }
            return states;
        }

        /** The server leaves another road's storage out of the road it's on. */
        @Override
        public ServerRequirement serverRequirement() {
            return ServerRequirement.IGNORE;
        }
    }
}
