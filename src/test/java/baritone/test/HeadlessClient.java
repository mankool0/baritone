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

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.BetterWorldBorder;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The real {@link Baritone}, against a client that is only ever allocated: a level that is a map of
 * block states with every chunk loaded, and a player that is a position with an empty inventory.
 * Enough for the highway state machine to run its scans and pick its next state, which is what a
 * unit test of a state's decision needs. Nothing ticks, renders, paths or talks to a server; chat
 * goes into the event loop's queue and is never run.
 * <p>
 * The level and the player go into the client instance {@link HeadlessGame} installs, and the
 * Baritone is {@link BaritoneAPI}'s primary one, so there is one of each per JVM.
 */
public final class HeadlessClient {

    private static HeadlessClient instance;

    /**
     * The y range {@code baritone.bsi} answers for, outside which it reads air. The level itself
     * has no limits, so any range covering the cells a test builds will do.
     */
    private static final int MIN_Y = -64;
    private static final int HEIGHT = 384;

    private final Baritone baritone;
    private final FakeLevel level;
    private final FakePlayer player;

    private HeadlessClient(Baritone baritone, FakeLevel level, FakePlayer player) {
        this.baritone = baritone;
        this.level = level;
        this.player = player;
    }

    public static synchronized HeadlessClient get() {
        if (instance == null) {
            instance = boot();
        }
        return instance;
    }

    public Baritone baritone() {
        return baritone;
    }

    /** Back to nothing but air. Game time only ever goes forward, see {@link #advanceTime}. */
    public void clearWorld() {
        level.blocks.clear();
    }

    public void setBlock(BlockPos pos, BlockState state) {
        level.blocks.put(pos.asLong(), state);
    }

    public BlockState getBlock(BlockPos pos) {
        return level.getBlockState(pos);
    }

    public void advanceTime(long ticks) {
        level.gameTime += ticks;
    }

    /** Stands the player in the middle of the block at {@code feet}. */
    public void moveTo(BlockPos feet) {
        Vec3 position = new Vec3(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
        put(Entity.class, player, "position", position);
        put(Entity.class, player, "blockPosition", feet.immutable());
        put(Entity.class, player, "bb", new AABB(position.x - 0.3, position.y, position.z - 0.3, position.x + 0.3, position.y + 1.8, position.z + 0.3));
    }

    private static HeadlessClient boot() {
        HeadlessGame.bootstrap();
        Minecraft mc = Minecraft.getInstance();

        Options options = allocate(Options.class);
        OptionInstance<Integer> renderDistance = allocate(OptionInstance.class);
        put(OptionInstance.class, renderDistance, "value", 12);
        put(Options.class, options, "renderDistance", renderDistance);
        // key holds are released on every teardown
        List<KeyMapping> keys = new ArrayList<>();
        for (Field field : Options.class.getDeclaredFields()) {
            if (field.getType() == KeyMapping.class && !Modifier.isStatic(field.getModifiers())) {
                KeyMapping key = allocate(KeyMapping.class);
                put(Options.class, options, field.getName(), key);
                keys.add(key);
            }
        }
        put(Options.class, options, "keyMappings", keys.toArray(new KeyMapping[0]));
        put(Minecraft.class, mc, "options", options);

        FakeLevel level = allocate(FakeLevel.class);
        level.blocks = new Long2ObjectOpenHashMap<>();
        put(Minecraft.class, mc, "level", level);

        FakePlayer player = allocate(FakePlayer.class);
        put(Entity.class, player, "level", level);
        put(Player.class, player, "inventory", new Inventory(player, new EntityEquipment()));
        put(Minecraft.class, mc, "player", player);

        Baritone baritone = (Baritone) BaritoneAPI.getProvider().getPrimaryBaritone();
        baritone.bsi = new BlockStateInterface(new BetterWorldBorder(new WorldBorder()), MIN_Y, HEIGHT) {
            @Override
            protected BlockState getUncached(int x, int y, int z) {
                // get0 hands this y counted up from MIN_Y
                return level.getBlockState(new BlockPos(x, y + MIN_Y, z));
            }

            @Override
            public boolean isLoaded(int x, int z) {
                return true;
            }

            @Override
            public boolean worldContainsLoadedChunk(int blockX, int blockZ) {
                return true;
            }
        };
        HeadlessClient client = new HeadlessClient(baritone, level, player);
        client.moveTo(BlockPos.ZERO);
        return client;
    }

    /** A level whose blocks are a map; everything else about it is never read. */
    private static final class FakeLevel extends ClientLevel {
        // allocated, never constructed: these are set by boot, not by initialisers
        Long2ObjectOpenHashMap<BlockState> blocks;
        long gameTime;

        private FakeLevel() {
            super(null, null, null, null, 0, 0, null, false, 0L, 0);
            throw new AssertionError("allocated, never constructed");
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            BlockState state = blocks.get(pos.asLong());
            return state != null ? state : Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public long getGameTime() {
            return gameTime;
        }

        @Override
        public ResourceKey<Level> dimension() {
            return Level.NETHER;
        }

        @Override
        public List<Entity> getEntities(Entity except, AABB box, Predicate<? super Entity> predicate) {
            return List.of();
        }
    }

    /** Full health, no absorption, and whatever {@link #moveTo} put where. */
    private static final class FakePlayer extends LocalPlayer {

        private FakePlayer() {
            super(null, null, null, null, null, null, false);
            throw new AssertionError("allocated, never constructed");
        }

        @Override
        public float getHealth() {
            return 20f;
        }

        @Override
        public float getAbsorptionAmount() {
            return 0f;
        }
    }

    private static <T> T allocate(Class<?> type) {
        return HeadlessGame.allocate(type);
    }

    private static void put(Class<?> owner, Object target, String name, Object value) {
        HeadlessGame.put(owner, target, name, value);
    }
}
