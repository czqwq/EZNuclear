package com.czqwq.EZNuclear.mixin;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.brandon3055.draconicevolution.common.tileentities.multiblocktiles.reactor.TileReactorCore;
import com.czqwq.EZNuclear.Config;
import com.czqwq.EZNuclear.data.PendingMeltdown;
import com.czqwq.EZNuclear.util.Constants;
import com.czqwq.EZNuclear.util.MessageUtils;

/**
 * 接管 DE 反应堆的 {@code goBoom()}：
 * <ul>
 * <li>{@link Config#DEExplosion} 关闭时只广播提示，不产生爆炸；</li>
 * <li>{@link Config#requireCommandToExplode} 打开时登记为手动引信，等玩家输入指令；</li>
 * <li>否则延时 {@link Config#explosionDelaySeconds} 秒后触发爆炸（威力见
 * {@link PendingMeltdown#triggerReactorExplosion}：覆写开启时用 {@link Config#DEManualExplosivePower}，
 * 否则用 DE 按燃料计算的原生威力）。</li>
 * </ul>
 * 重复触发（连锁破坏会对同一坐标再次调用 goBoom）由 PendingMeltdown 的排队状态吞掉。
 */
@SuppressWarnings("UnusedMixin")
@Mixin(value = TileReactorCore.class, remap = false)
public abstract class TileReactorCoreMixin {

    @Inject(method = "goBoom", remap = false, at = @At("HEAD"), cancellable = true)
    private void onGoBoom(CallbackInfo ci) {
        TileEntity te = (TileEntity) (Object) this;
        ChunkCoordinates pos = new ChunkCoordinates(te.xCoord, te.yCoord, te.zCoord);
        int dimension = te.getWorldObj() != null ? te.getWorldObj().provider.dimensionId : 0;

        // DE 爆炸被禁用：只广播提示
        if (!Config.DEExplosion) {
            MessageUtils.broadcast(Constants.MSG_EASY_NUCLEAR);
            PendingMeltdown.schedule(
                pos,
                () -> MessageUtils.broadcast(Constants.MSG_PREVENT_EXPLOSION),
                Config.explosionDelaySeconds * 1000L,
                dimension);
            ci.cancel();
            return;
        }

        // 1) 延时结束后的重入：放行 DE 自己的 goBoom
        if (PendingMeltdown.consumeReentry(pos, dimension)) {
            return;
        }

        // 2) 该位置已经有排队中的爆炸：吞掉这次重复触发
        if (PendingMeltdown.isExplosionPending(pos, dimension)) {
            ci.cancel();
            return;
        }

        // 3) 该位置刚被处理过（连锁反应）：按原逻辑立即爆炸
        if (PendingMeltdown.shouldIgnoreExplosionAt(pos, dimension)) {
            return;
        }

        if (Config.requireCommandToExplode) {
            // 手动模式：登记引信，玩家输入指令后再触发（威力此时才决定，见 PendingMeltdown.triggerReactorExplosion）
            PendingMeltdown.markDEManualTrigger(pos, dimension);
        } else {
            final int x = te.xCoord;
            final int y = te.yCoord;
            final int z = te.zCoord;
            PendingMeltdown.markExplosionPending(pos, dimension);
            PendingMeltdown.schedule(pos, () -> {
                MessageUtils.broadcast(Constants.MSG_OH_NO);
                PendingMeltdown.triggerReactorExplosion(x, y, z, dimension);
            }, Config.explosionDelaySeconds * 1000L, dimension);
        }

        MessageUtils.broadcast(Constants.MSG_EASY_NUCLEAR);
        ci.cancel();
    }
}
