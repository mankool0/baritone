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

package baritone.utils;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.schematic.FillSchematic;
import baritone.process.BuilderProcess;
import baritone.process.CustomGoalProcess;
import baritone.test.HeadlessClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A lost connection and the way back, through the real {@link baritone.behavior.PathingBehavior} disconnected tick
 * (which cancels every process) and {@link ReconnectResume}'s connected tick.
 */
public class ReconnectResumeTest {

    private static final String SERVER = "play.example.net";
    private static final BlockPos HERE = new BlockPos(10, 70, 10);
    private static final Goal GOAL = new GoalBlock(500, 70, -300);

    private HeadlessClient client;
    private Baritone baritone;
    private ReconnectResume resume;
    private CustomGoalProcess goto_;
    private LocalPlayer player;
    private ClientPacketListener connectionBefore;

    @Before
    public void setUp() {
        client = HeadlessClient.get();
        baritone = client.baritone();
        resume = baritone.getReconnectResume();
        goto_ = baritone.getCustomGoalProcess();
        player = Minecraft.getInstance().player;
        connectionBefore = player.connection;
        if (baritone.getNetherHighwayBuilderBehavior().isHighwayActive()) {
            // left building by a highway test: while it builds nothing is kept, it restarts itself
            baritone.getNetherHighwayBuilderBehavior().stop();
        }
        client.moveTo(HERE);
        player.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE));
        // nothing kept from another test: a disconnect from the pause menu forgets everything
        join(SERVER, true);
        resume.disconnectingOnPurpose();
        disconnect();
        join(SERVER, true);
    }

    @After
    public void tearDown() {
        baritone.getPathingControlManager().cancelEverything();
        player.getInventory().clearContent();
        put(LocalPlayer.class, player, "connection", connectionBefore);
        Baritone.settings().resumeAfterReconnect.reset();
        Baritone.settings().resumeAfterReconnectTimeoutMS.reset();
    }

    @Test
    public void lostConnectionResumesThePathOnTheFirstLoadedTick() {
        goto_.setGoalAndPath(GOAL);
        disconnect();
        assertFalse("the disconnected tick cancels everything, as before", goto_.isActive());
        join(SERVER, false);
        assertFalse("not before the world is loaded", goto_.isActive());
        join(SERVER, true);
        assertTrue(goto_.isActive());
        assertEquals(GOAL, goto_.getGoal());
    }

    @Test
    public void waitsForTheInventory() {
        goto_.setGoalAndPath(GOAL);
        disconnect();
        player.getInventory().clearContent();
        join(SERVER, true);
        assertFalse(goto_.isActive());
        player.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE));
        resume.onConnectedTick();
        assertTrue(goto_.isActive());
    }

    @Test
    public void pauseMenuDisconnectIsNotResumed() {
        goto_.setGoalAndPath(GOAL);
        resume.disconnectingOnPurpose();
        disconnect();
        join(SERVER, true);
        assertFalse(goto_.isActive());
    }

    @Test
    public void anotherServerDropsIt() {
        goto_.setGoalAndPath(GOAL);
        disconnect();
        join("other.example.net", true);
        assertFalse(goto_.isActive());
        disconnect();
        join(SERVER, true);
        assertFalse("dropped, not kept for the way back", goto_.isActive());
    }

    @Test
    public void aQueueOnTheWayBackKeepsIt() {
        goto_.setGoalAndPath(GOAL);
        disconnect();
        // the proxy's queue: far from where the player was, with nothing going on
        client.moveTo(new BlockPos(0, 100, 0).offset(5000, 0, 5000));
        join(SERVER, true);
        assertFalse(goto_.isActive());
        disconnect();
        client.moveTo(HERE.offset(3, 0, -2));
        join(SERVER, true);
        assertTrue(goto_.isActive());
        assertEquals(GOAL, goto_.getGoal());
    }

    @Test
    public void somethingStartedMeanwhileWins() {
        goto_.setGoalAndPath(GOAL);
        disconnect();
        join(SERVER, false);
        final Goal other = new GoalBlock(-40, 70, 80);
        goto_.setGoalAndPath(other);
        join(SERVER, true);
        assertEquals(other, goto_.getGoal());
        goto_.onLostControl();
        resume.onConnectedTick();
        assertFalse("dropped once something else started", goto_.isActive());
    }

    @Test
    public void tooLongAfterTheDisconnectIsNotResumed() {
        Baritone.settings().resumeAfterReconnectTimeoutMS.value = -1L;
        goto_.setGoalAndPath(GOAL);
        disconnect();
        join(SERVER, true);
        assertFalse(goto_.isActive());
    }

    @Test
    public void offIsNotResumed() {
        Baritone.settings().resumeAfterReconnect.value = false;
        goto_.setGoalAndPath(GOAL);
        disconnect();
        Baritone.settings().resumeAfterReconnect.value = true;
        join(SERVER, true);
        assertFalse(goto_.isActive());
    }

    @Test
    public void aPausedBuildComesBackPaused() {
        final BuilderProcess builder = baritone.getBuilderProcess();
        builder.build("test", new FillSchematic(2, 1, 2, Blocks.STONE.defaultBlockState()), HERE.offset(4, 0, 0));
        builder.pause();
        disconnect();
        assertFalse(builder.isActive());
        join(SERVER, true);
        assertTrue(builder.isActive());
        assertTrue(builder.isPaused());
    }

    /**
     * The player is back on {@code server}, its world loaded or not, for a tick
     */
    private void join(String server, boolean loaded) {
        final ClientPacketListener connection = allocate(ClientPacketListener.class);
        put(ClientCommonPacketListenerImpl.class, connection, "serverData", new ServerData("test", server, ServerData.Type.OTHER));
        put(ClientPacketListener.class, connection, "clientLoaded", loaded);
        put(LocalPlayer.class, player, "connection", connection);
        resume.onConnectedTick();
    }

    /**
     * A tick with no player or no world, as {@link baritone.behavior.PathingBehavior} gets it
     */
    private void disconnect() {
        baritone.getPathingBehavior().onTick(TickEvent.createNextProvider().apply(EventState.PRE, TickEvent.Type.OUT));
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) {
        try {
            final Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (T) ((Unsafe) field.get(null)).allocateInstance(type);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void put(Class<?> owner, Object target, String name, Object value) {
        try {
            final Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(owner.getSimpleName() + "." + name + " moved; update the test", e);
        }
    }
}
