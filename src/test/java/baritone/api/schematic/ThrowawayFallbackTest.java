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

import baritone.test.HeadlessGame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * The digger's last-resort support block: crying obsidian, then obsidian, and obsidian named as
 * missing when we carry neither.
 */
public class ThrowawayFallbackTest {

    // set after the bootstrap: touching Blocks from a static initializer runs before it
    private static BlockState CRYING;
    private static BlockState OBSIDIAN;
    private static BlockState[] FALLBACK;

    @BeforeClass
    public static void bootstrap() {
        HeadlessGame.bootstrap();
        CRYING = Blocks.CRYING_OBSIDIAN.defaultBlockState();
        OBSIDIAN = Blocks.OBSIDIAN.defaultBlockState();
        FALLBACK = new BlockState[]{CRYING, OBSIDIAN};
    }

    private static List<ItemStack> inventory(Object... slotThenStack) {
        List<ItemStack> inventory = new ArrayList<>(Collections.nCopies(36, ItemStack.EMPTY));
        for (int i = 0; i < slotThenStack.length; i += 2) {
            inventory.set((Integer) slotThenStack[i], (ItemStack) slotThenStack[i + 1]);
        }
        return inventory;
    }

    @Test
    public void cryingObsidianFirstWhereverItIs() {
        assertEquals(CRYING, WhiteBlackSchematic.pickFallback(inventory(2, new ItemStack(Items.OBSIDIAN, 64), 20, new ItemStack(Items.CRYING_OBSIDIAN, 3)), FALLBACK));
        assertEquals(CRYING, WhiteBlackSchematic.pickFallback(inventory(30, new ItemStack(Items.CRYING_OBSIDIAN, 1)), FALLBACK));
    }

    @Test
    public void obsidianOnceThereIsNoCryingObsidian() {
        assertEquals(OBSIDIAN, WhiteBlackSchematic.pickFallback(inventory(2, new ItemStack(Items.OBSIDIAN, 64), 3, new ItemStack(Items.GOLDEN_CARROT, 10)), FALLBACK));
    }

    @Test
    public void carryingNeitherAsksForTheLastOne() {
        assertEquals(OBSIDIAN, WhiteBlackSchematic.pickFallback(inventory(), FALLBACK));
        assertEquals(OBSIDIAN, WhiteBlackSchematic.pickFallback(inventory(), new BlockState[]{OBSIDIAN}));
    }
}
