package net.betterthanvillage.mixin.society;

import net.betterthanvillage.society.Trading;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Validates colony offers before the player takes the trade output (06 §1.3). */
@Mixin(MerchantResultSlot.class)
public abstract class MerchantResultSlotMixin {
    @Shadow
    @Final
    private MerchantContainer slots;

    @Shadow
    @Final
    private Player player;

    @Shadow
    @Final
    private Merchant merchant;

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void btv$validate(int amount, CallbackInfoReturnable<ItemStack> cir) {
        if (merchant instanceof Villager v && v.level() instanceof ServerLevel level) {
            MerchantOffer offer = slots.getActiveOffer();
            if (offer != null && Trading.isTracked(v, offer) && !Trading.validate(level, v, offer)) {
                Trading.disable(v, offer, player);
                slots.updateSellItem();
                cir.setReturnValue(ItemStack.EMPTY);
            }
        }
    }
}
