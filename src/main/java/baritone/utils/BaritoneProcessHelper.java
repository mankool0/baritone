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
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;

public abstract class BaritoneProcessHelper implements IBaritoneProcess, Helper {

    protected final Baritone baritone;
    protected final IPlayerContext ctx;

    public BaritoneProcessHelper(Baritone baritone) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
    }

    @Override
    public boolean isTemporary() {
        return false;
    }

    /**
     * How to start this process again, doing what it's doing now, once the player is back after a lost connection
     * ({@link baritone.api.Settings#resumeAfterReconnect}). Asked on the first disconnected tick, just before every
     * process is cancelled, so there's no player or world to look at, only this process's own fields.
     *
     * @return The restart, or {@code null} when there's nothing to pick up again
     */
    public ReconnectResume.Restart restartAfterReconnect() {
        return null;
    }
}
