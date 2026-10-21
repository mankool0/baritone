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

import baritone.utils.PendingPlaces;
import baritone.utils.PendingToolDamage;
import baritone.utils.accessor.IClientLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
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

    // No field initialisers: Mixin only merges them from a constructor with line numbers, which
    // proguard strips from the obfuscated jars, so they'd silently stay null there.
    @Unique
    private PendingToolDamage pendingToolDamage;

    @Unique
    private PendingPlaces pendingPlaces;

    @Accessor("blockStatePredictionHandler")
    @Override
    public abstract BlockStatePredictionHandler getPredictionHandler();

    @Inject(
            method = "<init>",
            at = @At("RETURN")
    )
    private void onInit(CallbackInfo ci) {
        pendingToolDamage = new PendingToolDamage();
        pendingPlaces = new PendingPlaces();
    }

    // Called from the packet handler on the main thread, so it lands in order with the inventory
    // updates the server sent ahead of the ack.
    @Inject(
            method = "handleBlockChangedAck",
            at = @At("HEAD")
    )
    private void onBlockChangedAck(int sequence, CallbackInfo ci) {
        lastAckedSequence = Math.max(lastAckedSequence, sequence);
        pendingToolDamage.onAck(sequence);
        pendingPlaces.onAck(sequence);
    }

    // block updates, single or by section, land here on the main thread, even for a position the
    // client is still predicting
    @Inject(
            method = "setServerVerifiedBlockState",
            at = @At("HEAD")
    )
    private void onServerVerifiedBlockState(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        pendingPlaces.onServerState(pos);
    }

    @Override
    public int getLastAckedSequence() {
        return lastAckedSequence;
    }

    @Override
    public PendingToolDamage getPendingToolDamage() {
        return pendingToolDamage;
    }

    @Override
    public PendingPlaces getPendingPlaces() {
        return pendingPlaces;
    }
}
