package net.betterthanvillage.mixin.society;

import net.betterthanvillage.society.Trading;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Validates colony offers before the player takes the trade output (06 §1.3). mayPickup covers every click
 * type (pickup, throw, hotbar swap, quick move); remove() is kept as a second guard.
 */
@Mixin(MerchantResultSlot.class)
public abstract class MerchantResultSlotMixin extends Slot {
    @Shadow
    @Final
    private MerchantContainer slots;

    @Shadow
    @Final
    private Player player;

    @Shadow
    @Final
    private Merchant merchant;

    private MerchantResultSlotMixin(Container container, int slot, int x, int y) {
        super(container, slot, x, y);
    }

    @Override
    public boolean mayPickup(Player who) {
        return btv$valid(who) && super.mayPickup(who);
    }

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void btv$validate(int amount, CallbackInfoReturnable<ItemStack> cir) {
        if (!btv$valid(player)) {
            cir.setReturnValue(ItemStack.EMPTY);
        }
    }

    /** False (and the offer disabled) when a tracked colony offer can no longer be settled. */
    @Unique
    private boolean btv$valid(Player who) {
        if (merchant instanceof Villager v && v.level() instanceof ServerLevel level) {
            MerchantOffer offer = slots.getActiveOffer();
            if (offer != null && Trading.isTracked(v, offer) && !Trading.validate(level, v, offer)) {
                Trading.disable(v, offer, who);
                slots.updateSellItem();
                return false;
            }
        }
        return true;
    }
}
