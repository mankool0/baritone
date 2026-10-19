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

import baritone.api.event.events.PacketEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Counts the packets sent to the server each tick, by type. A server or proxy that kicks for
 * sending too much at once gives no reason, so what the last ticks sent is logged when the client
 * leaves the server, and the busiest tick since it was last asked is in {@link #diagnostic()}.
 * <p>
 * A tick runs from one tick event to the next, so it holds everything sent in between: actions,
 * the movement packet, the tick end and whatever replies the packet handlers sent. The actions
 * (digging, swings, uses, hotbar switches and entity interactions) are also counted apart, since the
 * replies to the server's pings outnumber everything else and come however the client plays.
 * <p>
 * It also replays the start-delay rule of the Grim anticheat's FastBreak check on the dig packets,
 * which goes only by when they're sent: a start within 275 ms of the last finished dig adds what it
 * came early by to a balance, a later one shrinks it, and every start that takes it over a second
 * is flagged. How many it would flag is counted alongside, since a server can punish those flags
 * without ever cancelling a dig.
 * <p>
 * A limit on one kind of packet a second shows up neither in a tick's count nor in the total, so
 * each kind's count over the last second is kept too: its highest since the last diagnostic, and
 * what it was when the client left.
 * <p>
 * Grim's AirLiquidBreak check is replayed too: it flags a start or a stop on a block its copy of
 * the world has as air, and a start clears the block there at once when the client's own judgment
 * has it instant. So a stop on it, or another start, later in the same tick is a dig on air, long
 * before the server's answer could put the block back.
 */
public final class PacketTrace implements AbstractGameEventListener {

    private static final int KEPT_TICKS = 100;
    // ticks sending more than this are counted as busy
    private static final int BUSY = 8;
    // ticks sending more actions than this are counted apart
    private static final int BUSY_ACTIONS = 7;
    private static final int TICKS_A_SECOND = 20;
    // Grim FastBreak: a start this soon after the last finish counts as early, and the balance flags past the most
    private static final long FAST_BREAK_DELAY_MILLIS = 275;
    private static final double FAST_BREAK_EARLY_MILLIS = 300;
    private static final double FAST_BREAK_MOST = 1000;

    private static final class Tick {
        final long startNanos = System.nanoTime();
        final Map<String, Integer> sent = new LinkedHashMap<>();
        int total;
        int actions;
        int digs;
        int fastBreakFlags;
        int airDigs;
        // where its starts cleared the anticheat's copy of the world
        final Set<BlockPos> cleared = new HashSet<>();

        String breakdown() {
            return PacketTrace.breakdown(sent);
        }
    }

    private final IPlayerContext ctx;
    private final ArrayDeque<Tick> recent = new ArrayDeque<>();
    private Tick current;
    // since the last diagnostic
    private Tick busiest;
    private Tick busiestActions;
    private int ticks;
    private int busyTicks;
    private int busyActionTicks;
    private int mostDigsInASecond;
    private int fastBreakFlags;
    private int airDigs;
    private final Map<String, Integer> mostInASecond = new LinkedHashMap<>();
    // the replayed FastBreak state, kept across ticks like the anticheat keeps it per connection
    private long lastFinishMillis;
    private double fastBreakBalance;

    public PacketTrace(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public synchronized void onTick(TickEvent event) {
        if (event.getState() != EventState.PRE) {
            return;
        }
        if (event.getType() == TickEvent.Type.IN) {
            finishTick();
            current = new Tick();
        } else if (current != null) {
            finishTick();
            current = null;
            logLeft();
            recent.clear();
            // the anticheat starts a new connection with its checks fresh
            lastFinishMillis = 0;
            fastBreakBalance = 0;
        }
    }

    @Override
    public synchronized void onSendPacket(PacketEvent event) {
        if (event.getState() == EventState.PRE && current != null) {
            Packet<?> packet = event.getPacket();
            current.sent.merge(name(packet), 1, Integer::sum);
            current.total++;
            if (isAction(packet)) {
                current.actions++;
            }
            if (packet instanceof ServerboundPlayerActionPacket action) {
                current.digs++;
                replayFastBreak(action);
                replayAirLiquidBreak(action);
            }
        }
    }

    private void replayAirLiquidBreak(ServerboundPlayerActionPacket action) {
        BlockPos pos = action.getPos();
        switch (action.getAction()) {
            case START_DESTROY_BLOCK -> {
                if (current.cleared.contains(pos)) {
                    current.airDigs++;
                } else if (clearsOnStart(pos)) {
                    current.cleared.add(pos.immutable());
                }
            }
            case STOP_DESTROY_BLOCK -> {
                if (current.cleared.contains(pos)) {
                    current.airDigs++;
                }
            }
            default -> {}
        }
    }

    /**
     * @return whether the anticheat clears the block on a start sent now, judging it with the
     * selected item and the client's copy of the dig speed, as the client does
     */
    private boolean clearsOnStart(BlockPos pos) {
        if (ctx.player() == null || ctx.world() == null) {
            return false;
        }
        BlockState state = ctx.world().getBlockState(pos);
        // a lone start predicts the break before it goes out, which it only does for an instant one
        return state.isAir() || state.getDestroyProgress(ctx.player(), ctx.world(), pos) >= 1.0f;
    }

    private void replayFastBreak(ServerboundPlayerActionPacket action) {
        long now = System.currentTimeMillis();
        switch (action.getAction()) {
            case START_DESTROY_BLOCK -> {
                long delay = now - lastFinishMillis;
                if (delay >= FAST_BREAK_DELAY_MILLIS) {
                    fastBreakBalance *= 0.9;
                } else {
                    fastBreakBalance += FAST_BREAK_EARLY_MILLIS - delay;
                }
                if (fastBreakBalance > FAST_BREAK_MOST) {
                    current.fastBreakFlags++;
                }
                fastBreakBalance = Math.max(-FAST_BREAK_MOST, Math.min(FAST_BREAK_MOST, fastBreakBalance));
            }
            case STOP_DESTROY_BLOCK -> lastFinishMillis = now;
            default -> {}
        }
    }

    /**
     * @return the actions sent so far this tick
     */
    public synchronized int actionsThisTick() {
        return current == null ? 0 : current.actions;
    }

    /**
     * The busiest tick since this was last asked, and how many ticks were busy. Reading it resets
     * both.
     */
    public synchronized String diagnostic() {
        String line = busiest == null ? "Packets: none sent"
                : String.format("Packets: at most %d in a tick (%s), %d of %d ticks over %d; at most %d actions in a tick (%s), %d ticks over %d; at most %d dig packets in a second; FastBreak's start delay would have flagged %d starts; AirLiquidBreak would have flagged %d digs; most of each in a second: %s",
                busiest.total, busiest.breakdown(), busyTicks, ticks, BUSY,
                busiestActions.actions, busiestActions.breakdown(), busyActionTicks, BUSY_ACTIONS, mostDigsInASecond, fastBreakFlags,
                airDigs, breakdown(mostInASecond));
        busiest = null;
        busiestActions = null;
        ticks = 0;
        busyTicks = 0;
        busyActionTicks = 0;
        mostDigsInASecond = 0;
        fastBreakFlags = 0;
        airDigs = 0;
        mostInASecond.clear();
        return line;
    }

    private void finishTick() {
        if (current == null) {
            return;
        }
        recent.addLast(current);
        if (recent.size() > KEPT_TICKS) {
            recent.removeFirst();
        }
        ticks++;
        if (current.total > BUSY) {
            busyTicks++;
        }
        if (busiest == null || current.total > busiest.total) {
            busiest = current;
        }
        if (current.actions > BUSY_ACTIONS) {
            busyActionTicks++;
        }
        if (busiestActions == null || current.actions > busiestActions.actions) {
            busiestActions = current;
        }
        mostDigsInASecond = Math.max(mostDigsInASecond, digsInLastSecond());
        fastBreakFlags += current.fastBreakFlags;
        airDigs += current.airDigs;
        lastSecond().forEach((name, count) -> mostInASecond.merge(name, count, Math::max));
    }

    /**
     * @return how many of each kind the last second's ticks sent
     */
    private Map<String, Integer> lastSecond() {
        Map<String, Integer> sum = new LinkedHashMap<>();
        int counted = 0;
        for (Iterator<Tick> it = recent.descendingIterator(); it.hasNext() && counted < TICKS_A_SECOND; counted++) {
            it.next().sent.forEach((name, count) -> sum.merge(name, count, Integer::sum));
        }
        return sum;
    }

    private static String breakdown(Map<String, Integer> sent) {
        return sent.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> e.getValue() > 1 ? e.getKey() + " x" + e.getValue() : e.getKey())
                .collect(Collectors.joining(", "));
    }

    private int digsInLastSecond() {
        int digs = 0;
        int counted = 0;
        for (Iterator<Tick> it = recent.descendingIterator(); it.hasNext() && counted < TICKS_A_SECOND; counted++) {
            digs += it.next().digs;
        }
        return digs;
    }

    private void logLeft() {
        if (recent.isEmpty()) {
            return;
        }
        long end = System.nanoTime();
        double seconds = (end - recent.peekFirst().startNanos) / 1e9;
        Helper.HELPER.logDirect(String.format("Packets sent over the last %.1f s before leaving the server, a tick at a time, newest last: %s",
                seconds, recent.stream().map(tick -> String.valueOf(tick.total)).collect(Collectors.joining(" "))));
        List<Tick> worst = new ArrayList<>(recent);
        worst.sort(Comparator.comparingInt((Tick tick) -> tick.total).reversed());
        Helper.HELPER.logDirect("Busiest of them: " + worst.stream().limit(3)
                .map(tick -> String.format("%d %.2f s before (%s)", tick.total, (end - tick.startNanos) / 1e9, tick.breakdown()))
                .collect(Collectors.joining(" | ")));
        Helper.HELPER.logDirect(String.format("Actions in those ticks, newest last: %s",
                recent.stream().map(tick -> String.valueOf(tick.actions)).collect(Collectors.joining(" "))));
        worst.sort(Comparator.comparingInt((Tick tick) -> tick.actions).reversed());
        int mostDigs = 0;
        List<Tick> kept = new ArrayList<>(recent);
        for (int i = 0; i < kept.size(); i++) {
            int digs = 0;
            for (int j = i; j < Math.min(kept.size(), i + TICKS_A_SECOND); j++) {
                digs += kept.get(j).digs;
            }
            mostDigs = Math.max(mostDigs, digs);
        }
        int flagged = recent.stream().mapToInt(tick -> tick.fastBreakFlags).sum();
        int onAir = recent.stream().mapToInt(tick -> tick.airDigs).sum();
        int flaggedLately = 0;
        int onAirLately = 0;
        int counted = 0;
        for (Iterator<Tick> it = recent.descendingIterator(); it.hasNext() && counted < TICKS_A_SECOND; counted++) {
            Tick tick = it.next();
            flaggedLately += tick.fastBreakFlags;
            onAirLately += tick.airDigs;
        }
        Helper.HELPER.logDirect(String.format("Most actions: %s; at most %d dig packets in a second, %d in the last; FastBreak's start delay would have flagged %d starts in these ticks, %d in the last second; AirLiquidBreak would have flagged %d digs in these ticks, %d in the last second",
                worst.stream().limit(3)
                        .map(tick -> String.format("%d %.2f s before (%s)", tick.actions, (end - tick.startNanos) / 1e9, tick.breakdown()))
                        .collect(Collectors.joining(" | ")),
                mostDigs, digsInLastSecond(), flagged, flaggedLately, onAir, onAirLately));
        Map<String, Integer> all = new LinkedHashMap<>();
        recent.forEach(tick -> tick.sent.forEach((name, count) -> all.merge(name, count, Integer::sum)));
        Helper.HELPER.logDirect(String.format("Of each kind, the last second: %s; all %.1f s: %s", breakdown(lastSecond()), seconds, breakdown(all)));
    }

    private static boolean isAction(Packet<?> packet) {
        return packet instanceof ServerboundPlayerActionPacket || packet instanceof ServerboundSwingPacket
                || packet instanceof ServerboundUseItemOnPacket || packet instanceof ServerboundUseItemPacket
                || packet instanceof ServerboundSetCarriedItemPacket || packet instanceof ServerboundInteractPacket;
    }

    private static String name(Packet<?> packet) {
        String name = packet.type().id().getPath();
        if (packet instanceof ServerboundPlayerActionPacket action) {
            return switch (action.getAction()) {
                case START_DESTROY_BLOCK -> name + " start";
                case STOP_DESTROY_BLOCK -> name + " stop";
                case ABORT_DESTROY_BLOCK -> name + " abort";
                default -> name;
            };
        }
        return name;
    }
}
