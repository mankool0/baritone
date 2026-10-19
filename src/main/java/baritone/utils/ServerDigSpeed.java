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

import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.utils.IPlayerContext;
import baritone.utils.accessor.IPlayerControllerMP;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Per-tick dig progress the way the server judges a dig packet sent now.
 * <p>
 * Efficiency is the mining efficiency attribute, which the server only updates from the held item
 * when it ticks the player, after handling the packets that arrived since its last tick. So a dig
 * packet is judged with the speed of the tool selected for it but the efficiency of what was held
 * at the end of our last tick. The client's copy of the attribute is the server's from a round
 * trip ago, which right after switching between a block and the pick is wrong either way: too
 * high and an instant break is refused, too low and instant breaks are missed and slow digs are
 * timed as if the pick had no Efficiency.
 * <p>
 * An anticheat keeps its own copy of the world and replays digs in it with the client's copy of
 * the attribute, so it only clears a block a lone start packet broke when the client's judgment
 * has it instant too. Otherwise it keeps the block until the server's update reaches it, and the
 * player walking into that cell meanwhile gets set back. The other way round, it clears the block
 * on the start while the server digs on, so the stop that would finish it there is a dig on air,
 * which it flags; such a block waits until the two agree again.
 */
public final class ServerDigSpeed implements AbstractGameEventListener {

    /**
     * Enchantments name their attribute modifiers after the slot they apply from.
     */
    private static final String MAIN_HAND_SUFFIX = "/" + EquipmentSlot.MAINHAND.getSerializedName();

    /**
     * Near-instant: a dig finished with a stop packet in the tick it starts, which every copy of
     * the world clears.
     */
    private static final float JUST_UNDER_INSTANT = Math.nextDown(1.0f);

    /**
     * After a block is held at one tick's end the two efficiencies agree again within two ticks,
     * and every other tick while switching back and forth, so disagreeing for longer means the
     * server's is worked out wrong, and waiting on it would hold digs for good
     */
    private static final int MOST_TICKS_DISAGREEING = 40;

    private final IPlayerContext ctx;
    // what the server held at the end of our last two ticks gives this much efficiency, NaN until known
    private double efficiencyLastTick = Double.NaN;
    private double efficiencyTickBefore = Double.NaN;
    // ticks in a row that has differed from the client's copy of the attribute
    private int ticksDisagreeing;

    public ServerDigSpeed(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onPostTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT || ctx.minecraft().gameMode == null) {
            efficiencyLastTick = Double.NaN;
            efficiencyTickBefore = Double.NaN;
            ticksDisagreeing = 0;
            return;
        }
        // the slot last sent, not the one selected: a later switch goes out with the next tick's packets
        int slot = ((IPlayerControllerMP) ctx.minecraft().gameMode).getCarriedIndex();
        efficiencyTickBefore = efficiencyLastTick;
        efficiencyLastTick = efficiencyWith(ctx.player().getInventory().getItem(slot));
        double server = Math.min(efficiencyLastTick, efficiencyTickBefore);
        boolean agree = Double.isNaN(server) || server == ctx.player().getAttributeValue(Attributes.MINING_EFFICIENCY);
        ticksDisagreeing = agree ? 0 : ticksDisagreeing + 1;
    }

    /**
     * @return per-tick progress on the block with the item selected now, as the server judges a
     * dig packet sent this tick, but instant only when the client's own judgment agrees: a block
     * only the server has instant comes out just under, so it's finished with a stop packet, and
     * one only the client has instant comes out 0, see {@link #mustWait}
     */
    public float progress(BlockState state, BlockPos pos) {
        float client = clientProgress(state, pos);
        float server = serverProgress(state, client);
        if (mustWait(client, server)) {
            return 0;
        }
        return server >= 1.0f && client < 1.0f ? JUST_UNDER_INSTANT : server;
    }

    /**
     * @return whether a dig of the block with the item selected now has to wait for the server's
     * efficiency to catch up with the client's copy, because only the client's judgment has it
     * instant. The anticheat clears it on the start, so the stop that would finish it on the
     * server is a dig on air there; a lone start is refused by the server instead
     */
    public boolean mustWait(BlockState state, BlockPos pos) {
        float client = clientProgress(state, pos);
        return mustWait(client, serverProgress(state, client));
    }

    private boolean mustWait(float client, float server) {
        return client >= 1.0f && server < 1.0f && ticksDisagreeing < MOST_TICKS_DISAGREEING;
    }

    /**
     * @return whether the client's own judgment, the one an anticheat replays, has the block
     * instant with the item selected now
     */
    public boolean clientInstant(BlockState state, BlockPos pos) {
        return clientProgress(state, pos) >= 1.0f;
    }

    private float clientProgress(BlockState state, BlockPos pos) {
        return state.getDestroyProgress(ctx.player(), ctx.world(), pos);
    }

    private float serverProgress(BlockState state, float clientProgress) {
        LocalPlayer player = ctx.player();
        // the lower of the two, since two ticks' packets can reach the server between two of its
        // player ticks; NaN until both are known
        double server = Math.min(efficiencyLastTick, efficiencyTickBefore);
        double synced = player.getAttributeValue(Attributes.MINING_EFFICIENCY);
        float speed = player.getMainHandItem().getDestroySpeed(state);
        if (Double.isNaN(server) || server == synced || speed <= 1.0f) {
            return clientProgress; // efficiency only adds to a tool that is faster than a hand
        }
        // it adds to the tool's speed, and everything else in the progress multiplies that
        return clientProgress * ((speed + (float) server) / (speed + (float) synced));
    }

    /**
     * @return the mining efficiency the server gives the player with this in the main hand: the
     * client's copy of the attribute with whatever the main hand gave swapped for this item's
     */
    private double efficiencyWith(ItemStack mainHand) {
        AttributeInstance synced = ctx.player().getAttribute(Attributes.MINING_EFFICIENCY);
        if (synced == null) {
            return 0;
        }
        AttributeInstance with = new AttributeInstance(Attributes.MINING_EFFICIENCY, instance -> {});
        with.setBaseValue(synced.getBaseValue());
        for (AttributeModifier modifier : synced.getModifiers()) {
            if (!modifier.id().getPath().endsWith(MAIN_HAND_SUFFIX)) {
                with.addTransientModifier(modifier);
            }
        }
        if (!mainHand.isBroken()) { // the server applies nothing from a broken item
            mainHand.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
                if (attribute.is(Attributes.MINING_EFFICIENCY)) {
                    with.addOrUpdateTransientModifier(modifier);
                }
            });
        }
        return with.getValue();
    }
}
