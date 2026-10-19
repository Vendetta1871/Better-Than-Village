package net.betterthanvillage.mixin.society;

import net.betterthanvillage.society.PlayerBlocks;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Theft detection on container clicks in colony storages (06 §4.4); the session lives as long as the open menu. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Shadow
    public ServerPlayer player;

    @Unique
    private PlayerBlocks.TheftState btv$theft;

    @Inject(method = "handleContainerClick", at = @At("HEAD"))
    private void btv$head(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        if (!player.server.isSameThread()) return;
        btv$theft = PlayerBlocks.beforeClick(player.serverLevel(), player, btv$theft);
    }

    @Inject(method = "handleContainerClick", at = @At("RETURN"))
    private void btv$return(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        if (!player.server.isSameThread()) return;
        PlayerBlocks.afterClick(player.serverLevel(), player, btv$theft);
    }
}
