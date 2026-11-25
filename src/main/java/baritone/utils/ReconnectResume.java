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
import baritone.api.process.IBaritoneProcess;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Picks up what Baritone was doing after a lost connection ({@link baritone.api.Settings#resumeAfterReconnect}).
 * <p>
 * Every disconnected tick cancels every process ({@link baritone.behavior.PathingBehavior}). On the first one, just
 * before that, this keeps how to start each active one again ({@link BaritoneProcessHelper#restartAfterReconnect()}).
 * Back on the same server, in the same dimension, near the same spot, it starts them on the first tick the world is
 * loaded, before the processes tick, so they tick that same tick. The player doesn't move before then
 * ({@code LocalPlayer.tick}), and a glide the connection was lost in is still a glide: the server keeps it with the
 * player, and its fall distance too.
 * <p>
 * A proxy moving the player between its servers (to its queue, back to the main one) disconnects too, without a
 * disconnect screen: what was kept stays kept while the queue's world, where nothing is going on, comes and goes.
 */
public final class ReconnectResume implements Helper {

    /**
     * A process started again as it was
     *
     * @param what  What it was doing, for the log: "the path to ...", "mining ..."
     * @param start Starts it again
     */
    public record Restart(String what, Runnable start) {}

    /**
     * What to start again, and where the player was when the connection was lost
     */
    private record Kept(List<Restart> restarts, String server, ResourceKey<Level> dimension, BetterBlockPos feet, long at) {

        String what() {
            return restarts.stream().map(Restart::what).collect(Collectors.joining(", "));
        }
    }

    // back further than this from where the player was, it's somewhere else: a proxy's queue, or a respawn
    private static final double NEAR = 64;
    // the pause menu's Disconnect, or disconnectOnArrival's, is asked for this long at most before the disconnect it's for
    private static final long ON_PURPOSE_MS = 5000;

    private final Baritone baritone;
    private final IPlayerContext ctx;

    // the last tick there was a player in a world
    private boolean connected;
    // where the player was on the last tick in a loaded world: the server's address, the dimension, the feet. the
    // address is gone by the first disconnected tick (it's read off the connection)
    private String server;
    private ResourceKey<Level> dimension;
    private BetterBlockPos feet;
    // when the disconnect about to happen was asked for on purpose, 0 for none
    private long onPurposeAt;
    private Kept kept;

    public ReconnectResume(Baritone baritone) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
    }

    /**
     * The disconnect that's about to happen is on purpose, by the pause menu or {@link baritone.api.Settings#disconnectOnArrival}:
     * what's going on now isn't picked up again, and neither is anything kept from before
     */
    public void disconnectingOnPurpose() {
        this.onPurposeAt = System.currentTimeMillis();
    }

    /**
     * Every tick there's no player or no world, before the processes are cancelled
     */
    public void onDisconnectedTick() {
        if (!this.connected) {
            return;
        }
        this.connected = false;
        final boolean onPurpose = this.onPurposeAt != 0 && System.currentTimeMillis() - this.onPurposeAt < ON_PURPOSE_MS;
        this.onPurposeAt = 0;
        if (onPurpose || !Baritone.settings().resumeAfterReconnect.value || highwayActive()) {
            // the highway builder restarts itself, from the state it's left in
            this.kept = null;
            return;
        }
        if (this.server == null || this.feet == null) {
            return; // singleplayer, or never loaded
        }
        final List<Restart> restarts = this.activeRestarts();
        if (!restarts.isEmpty()) {
            this.kept = new Kept(restarts, this.server, this.dimension, this.feet, System.currentTimeMillis());
        }
        // else nothing's going on, as in a proxy's queue on the way back: what was kept from before stays
    }

    /**
     * Every tick there's a player in a world, before the processes tick
     */
    public void onConnectedTick() {
        this.connected = true;
        final LocalPlayer player = ctx.player();
        final boolean loaded = player.connection.hasClientLoaded();
        if (loaded) {
            final ServerData data = ctx.minecraft().getCurrentServer();
            this.server = data != null ? data.ip : null;
            this.dimension = ctx.world().dimension();
            this.feet = ctx.playerFeet();
        }
        final Kept kept = this.kept;
        if (kept == null) {
            return;
        }
        if (!Baritone.settings().resumeAfterReconnect.value || highwayActive() || !this.activeRestarts().isEmpty()) {
            // turned off, or something else was started since
            this.kept = null;
            return;
        }
        final long timeout = Baritone.settings().resumeAfterReconnectTimeoutMS.value;
        if (System.currentTimeMillis() - kept.at() > timeout) {
            logDirect("Not resuming " + kept.what() + ": not back where it was within " + timeout / 1000 + " s of the disconnect");
            this.kept = null;
            return;
        }
        if (!loaded || player.isDeadOrDying() || player.getInventory().isEmpty()) {
            // the elytra in the chest slot is in the inventory the server sends, and with none a flight won't start
            return;
        }
        if (!kept.server().equals(this.server)) {
            logDirect("Not resuming " + kept.what() + ": this is a different server");
            this.kept = null;
            return;
        }
        if (!kept.dimension().equals(this.dimension) || kept.feet().distSqr(this.feet) > NEAR * NEAR) {
            return; // not back yet: in the queue, or respawned elsewhere until the timeout
        }
        this.kept = null;
        for (Restart restart : kept.restarts()) {
            try {
                restart.start().run();
                logDirect("Resuming " + restart.what() + " after the reconnect");
            } catch (RuntimeException e) {
                logDirect("Couldn't resume " + restart.what() + " after the reconnect: " + e.getMessage());
            }
        }
    }

    private boolean highwayActive() {
        return baritone.getNetherHighwayBuilderBehavior().isHighwayActive();
    }

    private List<Restart> activeRestarts() {
        final List<Restart> restarts = new ArrayList<>();
        for (IBaritoneProcess process : baritone.getPathingControlManager().processes()) {
            if (process instanceof BaritoneProcessHelper helper) {
                final Restart restart = helper.restartAfterReconnect();
                if (restart != null) {
                    restarts.add(restart);
                }
            }
        }
        return restarts;
    }
}
