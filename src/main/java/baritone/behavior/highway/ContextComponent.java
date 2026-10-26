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
 * each other through {@link #ctx}, except for the layout, which nearly all of them read.
 */
abstract class ContextComponent {
    protected final HighwayContext ctx;
    protected final Baritone baritone;
    protected final IPlayerContext playerContext;
    protected final Settings settings;
    protected final HighwayLayout layout;

    ContextComponent(HighwayContext ctx) {
        this.ctx = ctx;
        this.baritone = ctx.baritone();
        this.playerContext = ctx.playerContext();
        this.settings = ctx.settings();
        this.layout = ctx.layout();
    }
}
