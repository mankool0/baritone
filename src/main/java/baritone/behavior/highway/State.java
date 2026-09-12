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


import baritone.behavior.highway.enums.HighwayState;

public abstract class State {

    protected final HighwayState state;

    /**
     * Ticks this state object has been handled for. A fresh instance is built on every transition
     * (see {@link StateFactory}), so this counts from zero on entry and, unlike the context-wide
     * timer, no other state can reset it out from under us. The first {@link #handle} of a state
     * sees zero.
     */
    private int ticksInState;

    public State(HighwayState state) {
        this.state = state;
    }

    public HighwayState getState() {
        return state;
    }

    public int ticksInState() {
        return ticksInState;
    }

    void noteHandled() {
        ticksInState++;
    }

    public abstract void handle(HighwayContext context);

    public void onExit(HighwayContext context) {}
}
