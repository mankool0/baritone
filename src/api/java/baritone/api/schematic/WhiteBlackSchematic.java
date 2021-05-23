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

import baritone.api.BaritoneAPI;
import baritone.api.utils.BlockOptionalMeta;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class WhiteBlackSchematic extends AbstractSchematic {
    private final List<BlockOptionalMeta> BomList;
    private BlockOptionalMeta DefaultBom;
    private final boolean WhiteList;
    private final boolean ValidIfUnder; // If true and current in desired state is under blocks then it's a valid state
    private final boolean UseThrowaway;

    public WhiteBlackSchematic(int x, int y, int z, List<IBlockState> bomList, IBlockState defaultState, boolean whiteList, boolean validIfUnder, boolean useThrowaway) {
        super(x, y, z);
        WhiteList = whiteList;
        ValidIfUnder = validIfUnder;
        DefaultBom = new BlockOptionalMeta(defaultState.getBlock(), defaultState.getBlock().getMetaFromState(defaultState));
        BomList = new ArrayList<>();
        for (IBlockState state : bomList) {
            BomList.add(new BlockOptionalMeta(state.getBlock(), state.getBlock().getMetaFromState(state)));
        }
        UseThrowaway = useThrowaway;
    }

    public boolean isValidIfUnder() {
        return ValidIfUnder;
    }

    private IBlockState getDefaultOrThrowaway() {
        if (UseThrowaway) {
            for (Item item : BaritoneAPI.getSettings().acceptableThrowawayItems.value) {
                for (int i = 0; i < 9; i++) {
                    ItemStack stack = BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext().player().inventory.mainInventory.get(i);
                    if (item instanceof ItemBlock && stack.getItem() instanceof ItemBlock && ((ItemBlock) item).getBlock() == ((ItemBlock) stack.getItem()).getBlock()) {
                        return ((ItemBlock) item).getBlock().getDefaultState();
                    }
                }
            }
        }
        return DefaultBom.getAnyBlockState();
    }

    @Override
    public IBlockState desiredState(int x, int y, int z, IBlockState current, List<IBlockState> approxPlaceable) {
        for (BlockOptionalMeta bom : BomList) {
            if (WhiteList) {
                if (bom.matches(current)) {
                    return current; // Found current in whitelist, all good
                }
            } else {
                if ((current.getBlock() instanceof BlockLiquid && bom.getBlock() instanceof BlockLiquid && current.getBlock() == bom.getBlock()) || bom.matches(current)) {
                    return getDefaultOrThrowaway();
                }
            }
        }

        // Current not found in blacklist, return it
        if (!WhiteList) {
            return current;
        }

        if (current.getBlock() != Blocks.AIR) {
            return getDefaultOrThrowaway();
        }

        for (IBlockState placeable : approxPlaceable) {
            for (BlockOptionalMeta bom : BomList) {
                if (bom.matches(placeable)) {
                    return placeable;
                }
            }
            return placeable;
        }

        return BomList.get(0).getAnyBlockState();

    }
}
