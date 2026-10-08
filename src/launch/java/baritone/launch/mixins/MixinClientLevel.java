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

package baritone.launch.mixins;

import baritone.utils.accessor.IClientLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class MixinClientLevel implements IClientLevel {

    @Unique
    private int lastAckedSequence;

    @Accessor("blockStatePredictionHandler")
    @Override
    public abstract BlockStatePredictionHandler getPredictionHandler();

    // Called from the packet handler on the main thread, so it lands in order with the inventory
    // updates the server sent ahead of the ack.
    @Inject(
            method = "handleBlockChangedAck",
            at = @At("HEAD")
    )
    private void onBlockChangedAck(int sequence, CallbackInfo ci) {
        lastAckedSequence = Math.max(lastAckedSequence, sequence);
    }

    @Override
    public int getLastAckedSequence() {
        return lastAckedSequence;
    }
}
