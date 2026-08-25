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

package baritone.test;

import baritone.api.utils.BlockOptionalMeta;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Enough of Minecraft to construct block states and schematics in a unit test: the registries,
 * and nothing that needs a window, a world or a client instance.
 */
public final class HeadlessGame {

    private static boolean ready;

    private HeadlessGame() {}

    public static synchronized void bootstrap() {
        if (ready) {
            return;
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        seedBlockDrops();
        ready = true;
    }

    /**
     * {@link BlockOptionalMeta} resolves every block's loot-table drops through a {@code ServerLevel}
     * stub built on the running client, which does not exist here. The drops only feed the
     * item-stack hashes, which nothing geometric reads, so pre-fill its cache and no block ever
     * reaches the stub.
     */
    @SuppressWarnings("unchecked")
    private static void seedBlockDrops() {
        try {
            Field field = BlockOptionalMeta.class.getDeclaredField("drops");
            field.setAccessible(true);
            Map<Block, List<Item>> drops = (Map<Block, List<Item>>) field.get(null);
            for (Block block : BuiltInRegistries.BLOCK) {
                drops.put(block, Collections.emptyList());
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("BlockOptionalMeta.drops moved; update HeadlessGame", e);
        }
    }
}
