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

import baritone.api.BaritoneAPI;
import baritone.api.utils.BlockOptionalMeta;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import sun.misc.Unsafe;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Enough of Minecraft to construct block states and schematics in a unit test: the registries,
 * and nothing that needs a window or a world.
 * <p>
 * Also a client instance with nothing in it, so that {@link BaritoneAPI} can initialise. A class
 * initialiser that fails leaves the class unusable for the rest of the JVM, and some schematic
 * code reaches for BaritoneAPI on paths a test only probes, so without this whichever test
 * touches it first decides whether {@link HeadlessClient} can run at all. With no level and no
 * player in it, code that goes on to read either still fails as it would with no client.
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
        installClient();
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

    /** What BaritoneAPI's initialiser and the Baritone it creates touch of the client, and no more. */
    private static void installClient() {
        File gameDirectory;
        try {
            gameDirectory = Files.createTempDirectory("baritone-headless").toFile();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        gameDirectory.deleteOnExit();
        Minecraft mc = allocate(Minecraft.class);
        put(Minecraft.class, mc, "gameDirectory", gameDirectory);
        // logDirect hands its chat line to the client thread, which here never takes it
        put(BlockableEventLoop.class, mc, "pendingRunnables", new ConcurrentLinkedQueue<>());
        // every builder reset clears the dig cracks it shows
        LevelRenderer levelRenderer = allocate(LevelRenderer.class);
        put(LevelRenderer.class, levelRenderer, "destroyingBlocks", new Int2ObjectOpenHashMap<>());
        put(LevelRenderer.class, levelRenderer, "destructionProgress", new Long2ObjectOpenHashMap<>());
        put(Minecraft.class, mc, "levelRenderer", levelRenderer);
        put(Minecraft.class, null, "instance", mc);
    }

    private static final Unsafe UNSAFE;

    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            UNSAFE = (Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An instance of {@code type} with every field at its default, and no constructor run. */
    @SuppressWarnings("unchecked")
    static <T> T allocate(Class<?> type) {
        try {
            return (T) UNSAFE.allocateInstance(type);
        } catch (InstantiationException e) {
            throw new IllegalStateException(e);
        }
    }

    static void put(Class<?> owner, Object target, String name, Object value) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(owner.getSimpleName() + "." + name + " moved; update the headless test client", e);
        }
    }
}
