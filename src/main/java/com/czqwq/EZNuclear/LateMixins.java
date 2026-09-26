package com.czqwq.EZNuclear;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.NotNull;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;

@LateMixin
public class LateMixins implements ILateMixinLoader {

    @Override
    public String getMixinConfig() {
        return "mixins.de.late.json";
    }

    @Override
    public @NotNull List<String> getMixins(Set<String> loadedMods) {
        // 只保留真正需要注入的类。
        // DE 1.5.x 的 ProcessHandler 自身已经用 newProcesses 队列规避了 ConcurrentModificationException，
        // 再注入一次只会覆盖上游实现，因此不再注册 ProcessHandlerMixin。
        return new ArrayList<>(Arrays.asList("IC2ExplosionMixin", "TileReactorCoreMixin"));
    }
}
