package com.czqwq.EZNuclear.mixin;

import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.Explosion;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.czqwq.EZNuclear.Config;
import com.czqwq.EZNuclear.EZNuclear;
import com.czqwq.EZNuclear.data.PendingMeltdown;
import com.czqwq.EZNuclear.util.Constants;
import com.czqwq.EZNuclear.util.MessageUtils;

import cpw.mods.fml.common.FMLCommonHandler;
import ic2.core.ExplosionIC2;

/**
 * 拦截 IC2 核爆（核反应堆熔毁以及核弹实体产生的 {@link ExplosionIC2.Type#Nuclear} 爆炸）。
 *
 * <p>
 * 判定顺序（很重要，改动前请先看这里）：
 * <ol>
 * <li>总开关 {@link Config#IC2Explosion}：关闭时任何 IC2 核爆都不允许发生，并清掉残留的一次性放行令牌；</li>
 * <li>一次性放行令牌：手动触发时由本模组创建的那一次爆炸；</li>
 * <li>{@link #eznuclear_ignoreNext}：本实例被延时后重新调用自己；</li>
 * <li>同一位置已有排队中的爆炸：吞掉连锁反应造成的重复触发；</li>
 * <li>该位置刚刚被处理过：放行原生爆炸（不再重复延时）；</li>
 * <li>否则按 {@link Config#requireCommandToExplode} 决定手动等待还是延时爆炸。</li>
 * </ol>
 * 威力：{@link Config#isIC2PowerOverridden()} 为真时使用 {@link Config#IC2ManualExplosivePower}，
 * 否则沿用 IC2 自己算出的威力（{@code explosionSize}）。
 */
@Mixin(value = ExplosionIC2.class, remap = false)
public class IC2ExplosionMixin {

    static {
        EZNuclear.LOG.info("[EZNuclear] IC2ExplosionMixin loaded");
    }

    /** 允许本实例在延时结束后重新调用一次 {@code doExplosion} 而不再被拦截。 */
    @Unique
    private volatile boolean eznuclear_ignoreNext = false;

    @Shadow(remap = false)
    private World worldObj;

    @Shadow(remap = false)
    private ExplosionIC2.Type type;

    @Inject(method = "doExplosion", at = @At("HEAD"), cancellable = true, remap = false)
    private void onDoExplosion(CallbackInfo ci) {
        // 只拦截核爆，其他 IC2 爆炸（机器、蒸汽机、炸药等）原样放行
        if (type != ExplosionIC2.Type.Nuclear) {
            if (EZNuclear.LOG.isDebugEnabled()) {
                Explosion self = (Explosion) (Object) this;
                EZNuclear.LOG.debug(
                    "[EZNuclear] Skipping non-nuclear IC2 explosion: type={}, pos=({},{},{}), dim={}",
                    type,
                    (int) Math.floor(self.explosionX),
                    (int) Math.floor(self.explosionY),
                    (int) Math.floor(self.explosionZ),
                    worldObj != null ? worldObj.provider.dimensionId : -1);
            }
            return;
        }

        Explosion self = (Explosion) (Object) this;
        ChunkCoordinates pos = new ChunkCoordinates(
            (int) Math.floor(self.explosionX),
            (int) Math.floor(self.explosionY),
            (int) Math.floor(self.explosionZ));
        int dimension = worldObj != null ? worldObj.provider.dimensionId : 0;
        // IC2 自己算出来的威力，未开启覆写时沿用它
        float nativePower = self.explosionSize;

        // 1) 总开关优先：即使有残留的放行令牌，也不允许 IC2 核爆真的发生
        if (!Config.IC2Explosion) {
            PendingMeltdown.clearAllowExplosion(pos, dimension);
            MessageUtils.broadcast(Constants.MSG_EASY_NUCLEAR);
            PendingMeltdown.schedule(
                pos,
                () -> MessageUtils.broadcast(Constants.MSG_PREVENT_EXPLOSION),
                Config.explosionDelaySeconds * 1000L,
                dimension);
            ci.cancel();
            return;
        }

        // 2) 手动触发时本模组自己创建的那一次爆炸
        if (PendingMeltdown.consumeAllowExplosion(pos, dimension)) {
            return;
        }

        // 3) 延时结束后的自我回调
        if (eznuclear_ignoreNext) {
            eznuclear_ignoreNext = false;
            return;
        }

        // 4) 该位置已经有排队中的爆炸，吞掉这次重复触发
        if (PendingMeltdown.isExplosionPending(pos, dimension)) {
            ci.cancel();
            return;
        }

        // 5) 该位置刚被处理过（连锁反应），按原逻辑立即爆炸
        if (PendingMeltdown.shouldIgnoreExplosionAt(pos, dimension)) {
            return;
        }

        // 仅服务端处理；专用客户端上服务器实例为 null
        if (FMLCommonHandler.instance()
            .getMinecraftServerInstance() == null) {
            return;
        }

        MessageUtils.broadcast(Constants.MSG_EASY_NUCLEAR);

        if (Config.requireCommandToExplode) {
            // 取消即时爆炸，等玩家输入指令后再触发；
            // 开启覆写时用 IC2ManualExplosivePower，否则沿用 IC2 自己的威力
            ci.cancel();
            PendingMeltdown.markManualTriggerWithPower(
                pos,
                dimension,
                Config.isIC2PowerOverridden() ? Config.IC2ManualExplosivePower : nativePower);
            return;
        }

        // 取消即时爆炸，延时后触发
        ci.cancel();
        PendingMeltdown.markExplosionPending(pos, dimension);
        final int x = pos.posX;
        final int y = pos.posY;
        final int z = pos.posZ;
        if (Config.isIC2PowerOverridden()) {
            // 覆写威力：延时后按 IC2ManualExplosivePower 重新制造一次爆炸
            PendingMeltdown.schedule(pos, () -> {
                MessageUtils.broadcast(Constants.MSG_OH_NO);
                PendingMeltdown
                    .createAndExecuteIC2ExplosionAt(x, y, z, dimension, (float) Config.IC2ManualExplosivePower);
            }, Config.explosionDelaySeconds * 1000L, dimension);
        } else {
            // 原生威力：延时后在同一个实例上重新调用 doExplosion
            PendingMeltdown.schedule(pos, () -> {
                MessageUtils.broadcast(Constants.MSG_OH_NO);
                eznuclear_ignoreNext = true;
                try {
                    ((ExplosionIC2) (Object) this).doExplosion();
                } catch (Throwable t) {
                    EZNuclear.LOG.error("[EZNuclear] Failed to run deferred IC2 nuclear explosion", t);
                }
            }, Config.explosionDelaySeconds * 1000L, dimension);
        }
    }
}
