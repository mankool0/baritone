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

import baritone.test.HeadlessGame;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The game takes a section's count of blocks from the server's packet as it is, and a section with a count of none is
 * all air to it whatever is in it. Such a section with blocks in it is told apart from one that's really empty.
 */
public class SentEmptySectionTest {

    @BeforeClass
    public static void bootstrap() {
        HeadlessGame.bootstrap();
    }

    private static PalettedContainer<BlockState> air() {
        return new PalettedContainer<>(Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
    }

    @Test
    public void aCountOfNoneOverBlocksIsSentEmpty() {
        final PalettedContainer<BlockState> states = air();
        // counted with nothing in it, as the packet's count would have it
        final LevelChunkSection section = new LevelChunkSection(states, null);
        // and the blocks the packet's states hold, which the count never saw
        states.set(1, 2, 3, Blocks.NETHERRACK.defaultBlockState());
        assertTrue(section.hasOnlyAir());
        assertTrue(NetherPathfinderContext.sentEmpty(section));
        assertEquals(Blocks.NETHERRACK, section.getBlockState(1, 2, 3).getBlock());
    }

    @Test
    public void aSectionCountedRightIsNot() {
        final PalettedContainer<BlockState> states = air();
        states.set(1, 2, 3, Blocks.NETHERRACK.defaultBlockState());
        final LevelChunkSection section = new LevelChunkSection(states, null);
        assertFalse(section.hasOnlyAir());
        assertFalse(NetherPathfinderContext.sentEmpty(section));
    }

    @Test
    public void anEmptySectionIsNot() {
        assertFalse(NetherPathfinderContext.sentEmpty(new LevelChunkSection(air(), null)));
        assertFalse(NetherPathfinderContext.sentEmpty(null));
    }
}
