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
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public class WhiteBlackSchematic extends AbstractSchematic {
    private final List<BlockOptionalMeta> BomList;
    private BlockOptionalMeta DefaultBom;
    private final boolean WhiteList;
    private final boolean ValidIfUnder; // If true and current in desired state is under blocks then it's a valid state
    private final boolean UseThrowaway;
    private BlockState[] ThrowawayFallbacks = new BlockState[0]; // Only used when UseThrowaway is set and the inventory has no throwaway blocks at all

    public WhiteBlackSchematic(int x, int y, int z, List<BlockState> bomList, BlockState defaultState, boolean whiteList, boolean validIfUnder, boolean useThrowaway) {
        super(x, y, z);
        WhiteList = whiteList;
        ValidIfUnder = validIfUnder;
        DefaultBom = new BlockOptionalMeta(defaultState.getBlock());
        BomList = new ArrayList<>();
        for (BlockState state : bomList) {
            BomList.add(new BlockOptionalMeta(state.getBlock()));
        }
        UseThrowaway = useThrowaway;
    }

    public boolean isValidIfUnder() {
        return ValidIfUnder;
    }

    public enum ServerRequirement { OBSIDIAN, AIR, IGNORE }

    /**
     * How the highway coordination server models a position covered by this schematic: obsidian it
     * requires, air it requires, or scaffolding it has no requirement for. Blacklist mode is only
     * ever used for scaffolding, and its placed block depends on hotbar contents
     * (getDefaultOrThrowaway), so it is not predictable anyway - the server ignores those
     * positions entirely.
     */
    public ServerRequirement serverRequirement() {
        if (!WhiteList) {
            return ServerRequirement.IGNORE;
        }

        boolean allAir = BomList.stream()
                .allMatch(b -> b.getBlock() == Blocks.AIR
                        || b.getBlock() == Blocks.CAVE_AIR
                        || b.getBlock() == Blocks.VOID_AIR);
        if (allAir) {
            return ServerRequirement.AIR;
        }

        boolean anyObsidian = BomList.stream()
                .anyMatch(b -> b.getBlock() == Blocks.OBSIDIAN
                        || b.getBlock() == Blocks.CRYING_OBSIDIAN);
        if (anyObsidian) {
            return ServerRequirement.OBSIDIAN;
        }

        // A whitelist schematic that is neither all-air nor obsidian-bearing means the highway
        // profile grew something the server's model doesn't know about; silently ignoring it is
        // exactly the divergence the conformance dump exists to prevent.
        throw new IllegalStateException("Whitelist schematic with no server requirement mapping: " + BomList);
    }

    /**
     * What to place once the inventory has no throwaway blocks at all: the first of these we carry,
     * in the order given, else the last one, which is then what we're missing.
     */
    public void setThrowawayFallback(BlockState... states) {
        ThrowawayFallbacks = states.clone();
    }

    public boolean coversLiquid(BlockState current) {
        if (WhiteList || !(current.getBlock() instanceof LiquidBlock)) {
            return false;
        }
        for (BlockOptionalMeta bom : BomList) {
            if ((bom.getBlock() instanceof LiquidBlock && current.getBlock() == bom.getBlock()) || bom.matches(current)) {
                return true;
            }
        }
        return false;
    }

    private BlockState getDefaultOrThrowaway() {
        if (UseThrowaway) {
            List<ItemStack> inventory = BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext().player().getInventory().getNonEquipmentItems();
            BlockState onHotbar = firstThrowawayIn(inventory, 0, 9);
            if (onHotbar != null) {
                return onHotbar;
            }
            // Nothing on the hotbar, so the builder will have to pull a stack up from the main
            // inventory - ask for something we actually carry. The declared default wins whenever we
            // still have it, otherwise any acceptable throwaway block we're holding: asking for the
            // default when we own none of it just parks the builder on "missing materials" forever.
            if (inventoryContains(inventory, DefaultBom.getBlock().asItem())) {
                return DefaultBom.getAnyBlockState();
            }
            BlockState inInventory = firstThrowawayIn(inventory, 9, inventory.size());
            if (inInventory != null) {
                return inInventory;
            }
            // The fallback only kicks in once the whole inventory is out of throwaway blocks, and
            // stops being used as soon as we pick some up again
            if (ThrowawayFallbacks.length > 0) {
                return pickFallback(inventory, ThrowawayFallbacks);
            }
        }
        return DefaultBom.getAnyBlockState();
    }

    /** First acceptableThrowawayItems block held in [from, to), in the setting's own priority order. */
    private static BlockState firstThrowawayIn(List<ItemStack> inventory, int from, int to) {
        for (Item item : BaritoneAPI.getSettings().acceptableThrowawayItems.value) {
            if (!(item instanceof BlockItem)) {
                continue;
            }
            for (int i = from; i < to; i++) {
                ItemStack stack = inventory.get(i);
                if (stack.getItem() instanceof BlockItem && ((BlockItem) item).getBlock() == ((BlockItem) stack.getItem()).getBlock()) {
                    return ((BlockItem) item).getBlock().defaultBlockState();
                }
            }
        }
        return null;
    }

    /** {@link #setThrowawayFallback}'s choice: the first of {@code fallbacks} we carry, else the last one. */
    static BlockState pickFallback(List<ItemStack> inventory, BlockState[] fallbacks) {
        for (BlockState fallback : fallbacks) {
            if (inventoryContains(inventory, fallback.getBlock().asItem())) {
                return fallback;
            }
        }
        return fallbacks[fallbacks.length - 1];
    }

    private static boolean inventoryContains(List<ItemStack> inventory, Item item) {
        for (ItemStack stack : inventory) {
            if (stack.getItem() == item) {
                return true;
            }
        }
        return false;
    }

    @Override
    public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
        for (BlockOptionalMeta bom : BomList) {
            if (WhiteList) {
                if (bom.matches(current)) {
                    return current; // Found current in whitelist, all good
                }
            } else {
                if ((current.getBlock() instanceof LiquidBlock && bom.getBlock() instanceof LiquidBlock && current.getBlock() == bom.getBlock()) || bom.matches(current)) {
                    return getDefaultOrThrowaway();
                }
            }
        }

        // Current not found in blacklist, return it
        if (!WhiteList) {
            return current;
        }

        // cave and void air get filled like air: asking for the default there instead had a bot
        // that carries only another listed block park on missing materials under a cave
        if (!current.isAir()) {
            return getDefaultOrThrowaway();
        }

        // An empty cell takes the first listed block we carry, so the list order is the placement
        // preference. With none of them aboard, ask for the declared default.
        for (BlockOptionalMeta bom : BomList) {
            for (BlockState placeable: approxPlaceable) {
                if (bom.matches(placeable)) {
                    return placeable;
                }
            }
        }

        return DefaultBom.getAnyBlockState();

    }
}