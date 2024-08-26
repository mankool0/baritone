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

package baritone.behavior.highway.state;

import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;

import java.util.Optional;

public class OpeningPickaxeShulker extends State {
    public OpeningPickaxeShulker(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (context.timer() < 10) {
            return;
        }

        Optional<Rotation> shulkerReachable = RotationUtils.reachable(context.playerContext(), context.placeLoc(), context.playerContext().playerController().getBlockReachDistance());
        shulkerReachable.ifPresent(rotation -> context.baritone().getLookBehavior().updateTarget(rotation, true));

        context.baritone().getInputOverrideHandler().clearAllKeys();
        if (!(context.playerContext().minecraft().screen instanceof ShulkerBoxScreen)) {
            context.baritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            context.resetTimer();
        } else {
            context.baritone().getInputOverrideHandler().clearAllKeys();
            context.transitionTo(HighwayState.LootingPickaxeShulker);
        }
    }
}
