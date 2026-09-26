package com.czqwq.EZNuclear;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import net.minecraftforge.common.config.Configuration;

public class Config {

    public static boolean IC2Explosion = true;
    public static boolean DEExplosion = true;
    public static boolean requireCommandToExplode = false;
    /** 触发爆炸的聊天指令是否需要 OP 权限 */
    public static boolean TriggerRequireOP = true;
    public static int explosionDelaySeconds = 5;

    // 手动覆写爆炸威力：IC2 / DE 各自一个开关和一份威力值
    public static boolean IC2ManualExplosiveOverride = false;
    public static boolean DEManualExplosiveOverride = false;
    public static double IC2ManualExplosivePower = 20.0D;
    public static double DEManualExplosivePower = 20.0D;

    public static int taskTimeoutMinutes = 10; // Default 10 minutes for scheduled tasks cleanup

    /** IC2 配置里读不到威力上限时使用的默认值 */
    private static final double IC2_POWER_FALLBACK = 20.0D;
    /** {@link #IC2ManualExplosivePower} 允许的最大值（对齐 IC2 默认的 nukeExplosionPowerLimit） */
    private static final float IC2_POWER_MAX = 60.0F;

    /**
     * 本模组触发的 IC2 核爆是否使用 {@link #IC2ManualExplosivePower}，
     * 否则沿用 IC2 自己的威力（拦截时从爆炸实例上读到的值）。
     */
    public static boolean isIC2PowerOverridden() {
        return IC2ManualExplosiveOverride;
    }

    /**
     * 本模组触发的 DE 反应堆爆炸是否使用 {@link #DEManualExplosivePower}，
     * 否则让 DE 按自己的燃料公式计算威力（约 2~20）。
     */
    public static boolean isDEPowerOverridden() {
        return DEManualExplosiveOverride;
    }

    public static void synchronizeConfiguration(File configFile) {
        Configuration configuration = new Configuration(configFile);
        IC2Explosion = configuration.getBoolean(
            "IC2Explosion",
            Configuration.CATEGORY_GENERAL,
            IC2Explosion,
            "Allow IC2 nuclear explosions\nAttention: IC2 removes the reactor core and its chambers before the explosion, so blocking the explosion does NOT save the reactor");
        DEExplosion = configuration.getBoolean(
            "DEExplosion",
            Configuration.CATEGORY_GENERAL,
            DEExplosion,
            "Allow Draconic Evolution nuclear explosions");
        requireCommandToExplode = configuration.getBoolean(
            "requireCommandToExplode",
            Configuration.CATEGORY_GENERAL,
            requireCommandToExplode,
            "Require player to send '坏了坏了' command to trigger explosion. When enabled, only sends info.ezunclear message and waits for manual trigger");
        TriggerRequireOP = configuration.getBoolean(
            "TriggerRequireOP",
            Configuration.CATEGORY_GENERAL,
            TriggerRequireOP,
            "Require OP permission to trigger explosions with the 'EZUNCLEAR' / '坏了坏了' chat commands\n"
                + "When disabled, any player can trigger them. Single player and LAN host are always allowed");
        explosionDelaySeconds = configuration.getInt(
            "explosionDelaySeconds",
            Configuration.CATEGORY_GENERAL,
            explosionDelaySeconds,
            1,
            300,
            "Delay in seconds before explosion occurs (default: 5 seconds)");

        IC2ManualExplosiveOverride = configuration.getBoolean(
            "IC2ManualExplosiveOverride",
            Configuration.CATEGORY_GENERAL,
            IC2ManualExplosiveOverride,
            "Use IC2ManualExplosivePower instead of IC2's own power for nuclear explosions triggered by this mod");
        DEManualExplosiveOverride = configuration.getBoolean(
            "DEManualExplosiveOverride",
            Configuration.CATEGORY_GENERAL,
            DEManualExplosiveOverride,
            "Use DEManualExplosivePower instead of DE's fuel based power for reactor explosions triggered by this mod");
        IC2ManualExplosivePower = configuration.getFloat(
            "IC2ManualExplosivePower",
            Configuration.CATEGORY_GENERAL,
            (float) readIC2PowerLimit(configFile.getParentFile()),
            1.0f,
            IC2_POWER_MAX,
            "Explosion power used when IC2ManualExplosiveOverride is true (range 1-60)\n"
                + "Default is read from IC2's own config (protection/nukeExplosionPowerLimit), or 20.0 when unavailable\n"
                + "Note: the override bypasses IC2's own reactorExplosionPowerLimit clamp");
        DEManualExplosivePower = configuration.getFloat(
            "DEManualExplosivePower",
            Configuration.CATEGORY_GENERAL,
            (float) DEManualExplosivePower,
            1.0f,
            50.0f,
            "Explosion power used when DEManualExplosiveOverride is true (range 1-50, default: 20.0)\n"
                + "DE's own power is about 2-20, and its explosion lasts power*10 ticks");

        taskTimeoutMinutes = configuration.getInt(
            "taskTimeoutMinutes",
            Configuration.CATEGORY_GENERAL,
            taskTimeoutMinutes,
            1,
            1440, // Max 24 hours
            "Timeout in minutes for scheduled tasks before they are removed (default: 10 minutes)");

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }

    /**
     * 从 IC2 自己的配置文件里读核爆威力上限：{@code config/IC2.ini} 的
     * {@code [protection] nukeExplosionPowerLimit}（缺失时退回 {@code reactorExplosionPowerLimit}）。
     *
     * 读不到（没装 IC2、文件还没生成、键被改名）时返回 {@link #IC2_POWER_FALLBACK}。
     * 只读不写，不会改动 IC2 的配置文件。
     */
    private static double readIC2PowerLimit(File configDir) {
        if (configDir == null) {
            return IC2_POWER_FALLBACK;
        }

        File ic2Config = new File(configDir, "IC2.ini");
        if (!ic2Config.isFile()) {
            EZNuclear.LOG
                .debug("[EZNuclear] IC2.ini not found, IC2ManualExplosivePower default = {}", IC2_POWER_FALLBACK);
            return IC2_POWER_FALLBACK;
        }

        Double nukeLimit = null;
        Double reactorLimit = null;
        String section = null;
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(ic2Config), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue;
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length() - 1)
                        .trim();
                    continue;
                }
                if (!"protection".equals(section)) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq)
                    .trim();
                if ("nukeExplosionPowerLimit".equals(key)) {
                    nukeLimit = parseDouble(line.substring(eq + 1));
                } else if ("reactorExplosionPowerLimit".equals(key)) {
                    reactorLimit = parseDouble(line.substring(eq + 1));
                }
            }
        } catch (Throwable t) {
            EZNuclear.LOG.warn("[EZNuclear] Failed to read IC2.ini power limit: " + t.getMessage());
            return IC2_POWER_FALLBACK;
        }

        Double limit = nukeLimit != null ? nukeLimit : reactorLimit;
        if (limit == null) {
            return IC2_POWER_FALLBACK;
        }

        double clamped = Math.max(1.0D, Math.min(IC2_POWER_MAX, limit));
        EZNuclear.LOG.debug("[EZNuclear] IC2 power limit is {}, IC2ManualExplosivePower default = {}", limit, clamped);
        return clamped;
    }

    private static Double parseDouble(String raw) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (Throwable t) {
            return null;
        }
    }
}
