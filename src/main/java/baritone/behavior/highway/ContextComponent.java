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

import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.utils.IPlayerContext;

/**
 * One part of the {@link HighwayContext}, with the handles every part works through. The parts reach
 * each other through {@link #ctx}, and the running build's layout through {@link #layout()}.
 */
abstract class ContextComponent {
    protected final HighwayContext ctx;
    protected final Baritone baritone;
    protected final IPlayerContext playerContext;
    protected final Settings settings;

    ContextComponent(HighwayContext ctx) {
        this.ctx = ctx;
        this.baritone = ctx.baritone();
        this.playerContext = ctx.playerContext();
        this.settings = ctx.settings();
    }

    /** The running build's layout. Every nhwbuild replaces it, so it is never worth keeping. */
    protected HighwayLayout layout() {
        return ctx.layout();
    }

    /** A new build starts, with its layout already in place: forget what the last build left behind. */
    void onBuildStart() {
    }

    /** The build is stopping, or about to restart: let go of whatever it holds outside the context. */
    void onTeardown() {
    }
}
