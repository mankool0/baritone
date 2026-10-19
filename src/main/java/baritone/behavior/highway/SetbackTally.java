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

package baritone.behavior.highway;

import baritone.api.utils.Helper;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The server's corrections of the player's position, an anticheat's set-backs, tallied with what
 * the client sent in the ticks before each one, against how often it sent the same in any such
 * stretch of building. A set-back reaches the client a round trip or more after the movement it
 * corrects, so it's matched against a window of recent ticks rather than the last one.
 * <p>
 * Every move sent is kept with what went out before it in its tick. The anticheat sets back to
 * the last position it accepted, carried a tick further by the speed it had there, so the detail
 * line finds the sent move that lands on and shows the moves around it: the one it went back to
 * and the one after, the move it took the player off on.
 */
final class SetbackTally {

    private static final int WINDOW_TICKS = 10;
    private static final int KEPT_MOVES = 40;
    private static final long DETAIL_EVERY_NANOS = TimeUnit.SECONDS.toNanos(10);
    // what's left of a tick's horizontal speed after friction on ordinary ground
    private static final double GROUND_SPEED_KEPT = 0.6 * 0.91;
    private static final double PLAYER_HALF_WIDTH = 0.3;
    private static final double PLAYER_HEIGHT = 1.8;
    private static final double SNEAKING_HEIGHT = 1.5;
    // how many moves back a place counts as fresh for one clicked against it
    private static final int FRESH_MOVES = 3;

    // what's counted per tick: a block broken by its start packet alone (cleared client-side, unlike
    // the start of a slow dig), one finished with a stop in the same tick, places, clicks, and places
    // under or in the way of the move they went out with
    private static final int LONE_START = 0;
    private static final int DIG_PAIR = 1;
    private static final int PLACE = 2;
    private static final int TWO_PLACES = 3;
    private static final int CLICK = 4;
    private static final int UNDER_STEP = 5;
    private static final int IN_PATH = 6;
    private static final String[] NAMES = {"a break on a lone start", "a break finished with a stop", "a place",
            "two places in a tick", "an inventory click", "a place under its step", "a place in its way"};
    private static final String[] COUNTED = {"breaks on a lone start", "breaks finished with a stop", "places",
            "ticks with two places", "inventory clicks", "places under its step", "places in its way"};

    private record Place(BlockPos clicked, Direction face, BlockPos target) {}

    /** A move sent to the server, with what went out before it in its tick */
    private static final class Move {
        final List<Place> places = new ArrayList<>();
        int starts;
        int stops;
        int slotChanges;
        Vec3 from;
        Vec3 to;
        boolean onGround;
        boolean horizontalCollision;
        Input input;
        boolean sprinting;
        // the move sent to confirm a teleport: from where the client was to where it was sent
        boolean teleportReply;
        // one entry per place: whether it was under the move, in its way, and how many moves before
        // the block it was clicked against was placed (-1 if not lately)
        boolean[] under;
        boolean[] inPath;
        int[] againstPlacedAgo;
    }

    // what this tick sent so far
    private final Set<BlockPos> starts = new HashSet<>();
    private final Set<BlockPos> stops = new HashSet<>();
    private int places;
    private int clicks;
    private int underStep;
    private int inPath;
    private int teleportsAccepted;

    // what the moves sent so far, newest last
    private final ArrayDeque<Move> moves = new ArrayDeque<>();
    private Move building = new Move();
    private Vec3 lastSent;
    private Input lastInput = Input.EMPTY;
    private boolean sprinting;
    private boolean replyNext;

    // how many of each the last ticks sent, newest at head
    private final int[][] window = new int[WINDOW_TICKS][NAMES.length];
    private int head;
    private long lastDetailNanos;

    // since the last diagnostic
    private int ticks;
    private final int[] ticksWith = new int[NAMES.length];
    private int setbacks;
    private final int[] setbacksAfter = new int[NAMES.length];
    private double setbackDistance;
    private int elsewhere;

    /** Called on the main thread with every packet sent */
    void noteSent(Packet<?> packet) {
        if (packet instanceof ServerboundPlayerActionPacket action) {
            switch (action.getAction()) {
                case START_DESTROY_BLOCK -> {
                    starts.add(action.getPos());
                    building.starts++;
                }
                case STOP_DESTROY_BLOCK -> {
                    stops.add(action.getPos());
                    building.stops++;
                }
                default -> {}
            }
        } else if (packet instanceof ServerboundUseItemOnPacket use) {
            places++;
            BlockHitResult hit = use.getHitResult();
            building.places.add(new Place(hit.getBlockPos(), hit.getDirection(), hit.getBlockPos().relative(hit.getDirection())));
        } else if (packet instanceof ServerboundContainerClickPacket) {
            clicks++;
        } else if (packet instanceof ServerboundSetCarriedItemPacket) {
            building.slotChanges++;
        } else if (packet instanceof ServerboundPlayerInputPacket input) {
            lastInput = input.input();
        } else if (packet instanceof ServerboundPlayerCommandPacket command) {
            switch (command.getAction()) {
                case START_SPRINTING -> sprinting = true;
                case STOP_SPRINTING -> sprinting = false;
                default -> {}
            }
        } else if (packet instanceof ServerboundAcceptTeleportationPacket) {
            // sent while the teleport is applied, so the move that follows is to where it put us;
            // the first one of a connection only puts us where we joined
            if (lastSent != null) {
                teleportsAccepted++;
            }
            replyNext = true;
        } else if (packet instanceof ServerboundMovePlayerPacket move) {
            finishMove(move);
        }
    }

    /**
     * A tick the highway builder didn't run: whatever came in belongs to no build
     *
     * @param disconnected whether there's no connection, so the next one starts its moves afresh
     */
    void skipTick(boolean disconnected) {
        teleportsAccepted = 0;
        clearTick();
        if (disconnected) {
            moves.clear();
            lastSent = null;
            replyNext = false;
        }
    }

    /**
     * Ends the tick, after the processes have sent their packets.
     *
     * @param building whether the highway is in its building state
     */
    void noteTick(boolean building, LocalPlayer player) {
        head = (head + 1) % WINDOW_TICKS;
        int[] sent = window[head];
        Arrays.fill(sent, 0);
        for (BlockPos start : starts) {
            if (stops.contains(start)) {
                sent[DIG_PAIR]++;
            } else if (player.level().getBlockState(start).isAir()) {
                sent[LONE_START]++;
            }
        }
        sent[PLACE] = places;
        sent[TWO_PLACES] = places >= 2 ? 1 : 0;
        sent[CLICK] = clicks;
        sent[UNDER_STEP] = underStep;
        sent[IN_PATH] = inPath;
        clearTick();

        int[] recent = new int[NAMES.length];
        for (int[] tick : window) {
            for (int i = 0; i < NAMES.length; i++) {
                recent[i] += tick[i];
            }
        }
        if (building) {
            ticks++;
            for (int i = 0; i < NAMES.length; i++) {
                if (recent[i] > 0) {
                    ticksWith[i]++;
                }
            }
        }
        int count = teleportsAccepted;
        teleportsAccepted = 0;
        if (count == 0) {
            return;
        }
        if (!building) {
            elsewhere += count;
            return;
        }
        setbacks += count;
        for (int i = 0; i < NAMES.length; i++) {
            if (recent[i] > 0) {
                setbacksAfter[i] += count;
            }
        }
        Move reply = lastReply();
        double moved = reply == null || reply.from == null ? 0 : reply.from.distanceTo(reply.to);
        setbackDistance += moved * count;
        long now = System.nanoTime();
        if (reply != null && now - lastDetailNanos >= DETAIL_EVERY_NANOS) {
            lastDetailNanos = now;
            Helper.HELPER.logDirect(String.format("Set back %.2f blocks to %.2f, %.2f, %.2f while building; the last %d ticks sent %s; %s",
                    moved, reply.to.x, reply.to.y, reply.to.z, WINDOW_TICKS, breakdown(recent), describeMovesBefore(reply)));
        }
    }

    /**
     * How often the player was set back while building and what was sent shortly before, against
     * how often the same was sent in any such stretch. Reading it resets the tally.
     */
    String diagnostic() {
        StringBuilder sb = new StringBuilder(String.format("Setbacks: %d while building", setbacks));
        if (setbacks > 0) {
            sb.append(String.format(" (%.2f blocks on average), the last %d ticks before them vs any %d ticks:", setbackDistance / setbacks, WINDOW_TICKS, WINDOW_TICKS));
            for (int i = 0; i < NAMES.length; i++) {
                sb.append(String.format(" %s %d%% vs %d%%%s", NAMES[i], Math.round(100.0 * setbacksAfter[i] / setbacks),
                        ticks == 0 ? 0 : Math.round(100.0 * ticksWith[i] / ticks), i < NAMES.length - 1 ? "," : ""));
            }
        }
        sb.append(String.format("; %d in other states", elsewhere));
        ticks = 0;
        Arrays.fill(ticksWith, 0);
        setbacks = 0;
        Arrays.fill(setbacksAfter, 0);
        setbackDistance = 0;
        elsewhere = 0;
        return sb.toString();
    }

    private void finishMove(ServerboundMovePlayerPacket packet) {
        Move move = building;
        building = new Move();
        move.from = lastSent;
        move.to = packet.hasPosition() ? new Vec3(packet.getX(0), packet.getY(0), packet.getZ(0)) : lastSent;
        move.onGround = packet.isOnGround();
        move.horizontalCollision = packet.horizontalCollision();
        move.input = lastInput;
        move.sprinting = sprinting;
        move.teleportReply = replyNext;
        replyNext = false;
        lastSent = move.to;
        annotatePlaces(move);
        moves.addLast(move);
        if (moves.size() > KEPT_MOVES) {
            moves.removeFirst();
        }
    }

    private void annotatePlaces(Move move) {
        int n = move.places.size();
        move.under = new boolean[n];
        move.inPath = new boolean[n];
        move.againstPlacedAgo = new int[n];
        Arrays.fill(move.againstPlacedAgo, -1);
        AABB swept = null;
        double feetY = 0;
        if (move.from != null && move.to != null && !move.teleportReply) {
            double height = move.input.shift() ? SNEAKING_HEIGHT : PLAYER_HEIGHT;
            AABB box = new AABB(move.from.x - PLAYER_HALF_WIDTH, move.from.y, move.from.z - PLAYER_HALF_WIDTH,
                    move.from.x + PLAYER_HALF_WIDTH, move.from.y + height, move.from.z + PLAYER_HALF_WIDTH);
            swept = box.expandTowards(move.to.subtract(move.from));
            feetY = Math.min(move.from.y, move.to.y);
        }
        for (int i = 0; i < n; i++) {
            Place place = move.places.get(i);
            if (swept != null) {
                AABB cell = new AABB(place.target);
                boolean overlapsAcross = cell.minX < swept.maxX && cell.maxX > swept.minX && cell.minZ < swept.maxZ && cell.maxZ > swept.minZ;
                move.under[i] = overlapsAcross && Math.abs(cell.maxY - feetY) < 1e-6;
                move.inPath[i] = cell.intersects(swept);
                if (move.under[i]) {
                    underStep++;
                }
                if (move.inPath[i]) {
                    inPath++;
                }
            }
            for (int j = 0; j < i; j++) {
                if (move.places.get(j).target.equals(place.clicked)) {
                    move.againstPlacedAgo[i] = 0;
                }
            }
            if (move.againstPlacedAgo[i] < 0) {
                int ago = 1;
                for (Iterator<Move> it = moves.descendingIterator(); it.hasNext() && ago <= FRESH_MOVES; ago++) {
                    Move earlier = it.next();
                    if (earlier.places.stream().anyMatch(p -> p.target.equals(place.clicked))) {
                        move.againstPlacedAgo[i] = ago;
                        break;
                    }
                }
            }
        }
    }

    private Move lastReply() {
        for (Iterator<Move> it = moves.descendingIterator(); it.hasNext(); ) {
            Move move = it.next();
            if (move.teleportReply) {
                return move;
            }
        }
        return null;
    }

    /**
     * Finds the sent move the set-back lands just past and describes it with the one before and the
     * one after.
     */
    private String describeMovesBefore(Move reply) {
        List<Move> before = new ArrayList<>();
        for (Move move : moves) {
            if (move == reply) {
                break;
            }
            before.add(move);
        }
        int best = -1;
        double bestOff = Double.MAX_VALUE;
        for (int i = 0; i < before.size(); i++) {
            Move move = before.get(i);
            if (move.teleportReply || move.from == null || move.to == null) {
                continue;
            }
            Vec3 delta = move.to.subtract(move.from);
            Vec3 landing = move.to.add(delta.x * GROUND_SPEED_KEPT, 0, delta.z * GROUND_SPEED_KEPT);
            double off = landing.distanceTo(reply.to);
            if (off <= bestOff) { // the latest of equals: standing still matches every tick of it
                bestOff = off;
                best = i;
            }
        }
        if (best < 0) {
            return "no move sent before it to match it to";
        }
        StringBuilder sb = new StringBuilder(String.format("it lands %.3f from just past the move sent %d ticks before; oldest first:",
                bestOff, before.size() - best));
        for (int i = Math.max(0, best - 1); i <= Math.min(before.size() - 1, best + 1); i++) {
            sb.append(i == best ? " [went back to]" : i < best ? " [before]" : " [after]").append(' ').append(describe(before.get(i)));
        }
        return sb.toString();
    }

    private static String describe(Move move) {
        StringBuilder sb = new StringBuilder();
        if (move.from != null && move.to != null) {
            Vec3 delta = move.to.subtract(move.from);
            sb.append(String.format("moved %+.3f %+.3f %+.3f", delta.x, delta.y, delta.z));
        }
        sb.append(move.onGround ? " on the ground" : " in the air");
        if (move.horizontalCollision) {
            sb.append(", against a wall");
        }
        sb.append(", keys ").append(keys(move.input)).append(move.sprinting ? " sprinting" : "");
        if (move.starts > 0 || move.stops > 0) {
            sb.append(String.format(", %d starts %d stops", move.starts, move.stops));
        }
        if (move.slotChanges > 0) {
            sb.append(String.format(", %d slot changes", move.slotChanges));
        }
        if (!move.places.isEmpty()) {
            BlockPos feet = move.from == null ? BlockPos.ZERO : BlockPos.containing(move.from);
            sb.append(", places at");
            for (int i = 0; i < move.places.size(); i++) {
                Place place = move.places.get(i);
                BlockPos at = place.target.subtract(feet);
                sb.append(String.format(" %d,%d,%d on %s", at.getX(), at.getY(), at.getZ(), place.face.getSerializedName()));
                if (move.under[i]) {
                    sb.append(" under its step");
                }
                if (move.inPath[i]) {
                    sb.append(" in its way");
                }
                if (move.againstPlacedAgo[i] == 0) {
                    sb.append(" against one placed that tick");
                } else if (move.againstPlacedAgo[i] > 0) {
                    sb.append(String.format(" against one placed %d ticks before", move.againstPlacedAgo[i]));
                }
                sb.append(i < move.places.size() - 1 ? ";" : "");
            }
        }
        return sb.append('.').toString();
    }

    private static String keys(Input input) {
        StringBuilder sb = new StringBuilder();
        if (input.forward()) {
            sb.append('W');
        }
        if (input.left()) {
            sb.append('A');
        }
        if (input.backward()) {
            sb.append('S');
        }
        if (input.right()) {
            sb.append('D');
        }
        if (input.jump()) {
            sb.append(" jump");
        }
        if (input.shift()) {
            sb.append(" sneak");
        }
        return sb.length() == 0 ? "none" : sb.toString().trim();
    }

    private void clearTick() {
        starts.clear();
        stops.clear();
        places = 0;
        clicks = 0;
        underStep = 0;
        inPath = 0;
    }

    private static String breakdown(int[] counts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < counts.length; i++) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(COUNTED[i]).append(' ').append(counts[i]);
        }
        return sb.toString();
    }
}
