package net.betterthanvillage.mixin.society;

import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes the vanilla trade start (special prices, customer, trade screen). */
@Mixin(Villager.class)
public interface VillagerTradeAccess {
    @Invoker("startTrading")
    void btv$startTrading(Player player);
}
