package net.betterthanvillage.mixin.society;

import net.betterthanvillage.society.Trading;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Shift-click on the trade output bypasses the slot's remove(); validate there too. */
@Mixin(MerchantMenu.class)
public abstract class MerchantMenuMixin {
    @Shadow
    @Final
    private Merchant trader;

    @Shadow
    @Final
    private MerchantContainer tradeContainer;

    @Inject(method = "quickMoveStack", at = @At("HEAD"), cancellable = true)
    private void btv$validate(Player player, int index, CallbackInfoReturnable<ItemStack> cir) {
        if (index == 2 && trader instanceof Villager v && v.level() instanceof ServerLevel level) {
            MerchantOffer offer = tradeContainer.getActiveOffer();
            if (offer != null && Trading.isTracked(v, offer) && !Trading.validate(level, v, offer)) {
                Trading.disable(v, offer, player);
                tradeContainer.updateSellItem();
                cir.setReturnValue(ItemStack.EMPTY);
            }
        }
    }
}
