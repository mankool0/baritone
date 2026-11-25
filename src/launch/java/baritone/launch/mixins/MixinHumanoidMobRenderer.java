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

import baritone.utils.accessor.ILivingEntity;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HumanoidMobRenderer.class)
public abstract class MixinHumanoidMobRenderer {

    @Inject(
            method = "extractHumanoidRenderState",
            at = @At("TAIL")
    )
    private static void drawStandingWhileGliding(final LivingEntity entity, final HumanoidRenderState state, final float partialTick,
                                                 final ItemModelResolver itemModelResolver, final CallbackInfo ci) {
        // elytraSaveDurability's standing box: the model stands too, and the flying pose (body laid flat, arms and
        // legs) and the speed it's drawn at follow this flag
        if (entity instanceof ILivingEntity living && living.isGlideHeld()) {
            state.isFallFlying = false;
            state.speedValue = 1.0F;
        }
    }
}
