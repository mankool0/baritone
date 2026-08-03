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

package baritone.process;

import baritone.Baritone;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.utils.BaritoneProcessHelper;

public class InventoryPauserProcess extends BaritoneProcessHelper {

    boolean pauseRequestedLastTick;
    boolean safeToCancelLastTick;
    int ticksOfStationary;
    int ticksOfCalm;
    int calmPauseTicks;

    public InventoryPauserProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isActive() {
        if (ctx.player() == null || ctx.world() == null) {
            return false;
        }
        return true;
    }

    private double motion() {
        return ctx.player().getDeltaMovement().multiply(1, 0, 1).length();
    }

    private boolean stationaryNow() {
        return motion() < 0.00001;
    }

    public boolean stationaryForInventoryMove() {
        pauseRequestedLastTick = true;
        return safeToCancelLastTick && ticksOfStationary > 1;
    }

    private boolean calmNow() {
        return !ctx.player().isSprinting()
                && ctx.player().input.forwardImpulse == 0
                && ctx.player().input.leftImpulse == 0;
    }

    /**
     * Quick variant of {@link #stationaryForInventoryMove()}: only needs one tick whose outgoing
     * packets carried no sprint and no movement keys; momentum is fine.
     */
    public boolean calmForInventoryMove() {
        if (safeToCancelLastTick && ticksOfCalm >= 1) {
            calmPauseTicks = 0;
            return true;
        }
        calmPauseTicks = 2; // self-decays in onTick, so an abandoned request can't pause forever
        return false;
    }

    /**
     * @return whether whoever holds real movement keys (the highway's forward walk) should release
     * them this tick so an inventory move can happen against a calm server-visible state
     */
    public boolean calmPausePending() {
        return calmPauseTicks > 0;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        //logDebug(pauseRequestedLastTick + " " + safeToCancelLastTick + " " + ticksOfStationary);
        safeToCancelLastTick = isSafeToCancel;
        if (calmNow()) {
            ticksOfCalm++;
        } else {
            ticksOfCalm = 0;
        }
        if (pauseRequestedLastTick) {
            pauseRequestedLastTick = false;
            if (stationaryNow()) {
                ticksOfStationary++;
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        ticksOfStationary = 0;
        if (calmPauseTicks > 0) {
            calmPauseTicks--;
            if (!(safeToCancelLastTick && ticksOfCalm >= 1)) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            // calm reached: defer so the process that wants the move can tick and click this
            // tick, while the click still compares against the calm state just sent
        }
        return new PathingCommand(null, PathingCommandType.DEFER);
    }

    @Override
    public void onLostControl() {

    }

    @Override
    public String displayName0() {
        return "inventory pauser";
    }

    @Override
    public double priority() {
        return 5.1; // slightly higher than backfill
    }

    @Override
    public boolean isTemporary() {
        return true;
    }
}
