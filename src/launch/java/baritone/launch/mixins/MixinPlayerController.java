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
import baritone.utils.accessor.IPlayerControllerMP;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.client.multiplayer.prediction.PredictiveAction;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class MixinPlayerController implements IPlayerControllerMP {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Accessor("isDestroying")
    @Override
    public abstract void setIsHittingBlock(boolean isHittingBlock);

    @Accessor("isDestroying")
    @Override
    public abstract boolean isHittingBlock();

    @Accessor("destroyBlockPos")
    @Override
    public abstract BlockPos getCurrentBlock();

    @Invoker("ensureHasSentCarriedItem")
    @Override
    public abstract void callSyncCurrentPlayItem();

    @Accessor("destroyDelay")
    @Override
    public abstract void setDestroyDelay(int destroyDelay);

    @Invoker("startPrediction")
    @Override
    public abstract void callStartPrediction(ClientLevel level, PredictiveAction action);

    // Every vanilla caller predicts the break inside the prediction its dig packet goes out under,
    // so the current sequence is the one the server will ack it with.
    @Inject(
            method = "destroyBlock",
            at = @At("HEAD")
    )
    private void onDestroyBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            return;
        }
        BlockStatePredictionHandler predictions = ((IClientLevel) level).getPredictionHandler();
        if (predictions.isPredicting()) {
            ((IClientLevel) level).getPendingToolDamage().onDestroyBlock(minecraft.player, level, pos, predictions.currentSequence());
        }
    }
}
