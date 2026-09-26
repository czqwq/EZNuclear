# EZNuclear

EZNuclear是一个Minecraft模组，专门用于管理和控制IndustrialCraft 2 (IC2) 和Draconic Evolution (DE) 中的核反应堆爆炸行为。该模组允许服务器管理员更好地控制核反应堆的爆炸机制，防止意外或恶意的破坏，并提供延迟和手动触发选项。

## 功能特性

### 1. 爆炸拦截与管理
- **IC2核反应堆爆炸控制**：拦截IC2核反应堆的爆炸，并提供配置选项来控制是否允许爆炸发生
- **Draconic Evolution反应堆爆炸控制**：拦截DE反应堆的爆炸，并提供配置选项来控制是否允许爆炸发生
- **爆炸延迟机制**：在爆炸发生前提供可配置的延迟时间，让玩家有时间撤离或做准备

### 2. 手动触发系统
- **命令触发**：提供"坏了坏了"命令，允许玩家手动触发已延迟的爆炸
- **全局触发**：提供"核电，轻而易举啊！"命令，触发所有已计划的爆炸
- **精确控制**：支持对特定位置的爆炸进行手动触发，而不影响其他区域

### 3. 防重复处理机制
- **位置跟踪**：跟踪已处理的爆炸位置，防止同一位置的爆炸被重复处理
- **时间窗口**：实现10秒的时间窗口机制，防止连锁反应导致的重复拦截
- **去重逻辑**：确保手动触发执行后，相同位置的爆炸不会被重复拦截

### 4. 配置选项
- **IC2爆炸控制**：`IC2Explosion` - 启用/禁用IC2核反应堆爆炸
- **DE爆炸控制**：`DEExplosion` - 启用/禁用Draconic Evolution反应堆爆炸
- **手动触发需求**：`requireCommandToExplode` - 要求玩家使用命令手动触发爆炸
- **触发权限**：`TriggerRequireOP` - 默认 true，开启时只有 OP 才能用触发指令引爆；关闭时任何人都能触发（单人/局域网房主始终允许）
- **延迟时间**：`explosionDelaySeconds` - 配置爆炸前的延迟时间（秒）
- **IC2 威力覆写**：`IC2ManualExplosiveOverride` / `IC2ManualExplosivePower` - IC2 侧开关与威力（1~60，默认读取 IC2 配置的 `protection/nukeExplosionPowerLimit`，读不到则 20）
- **DE 威力覆写**：`DEManualExplosiveOverride` / `DEManualExplosivePower` - DE 侧开关与威力（1~50），默认关闭即沿用 DE 燃料威力

### 5. 消息通知系统
- **预警告**：在爆炸前发送"核电，轻而易举啊！"消息通知玩家
- **交互提示**：发送"坏了坏了"消息提示玩家可以手动触发
- **拦截通知**：当爆炸被成功拦截时显示"[EZNuclear]成功拦截了一个爆炸"

### 6. 自动扫描功能
- **过热检测**：定期扫描世界中的反应堆，检测温度超过2000度的过热反应堆
- **自动处理**：对检测到的过热反应堆安排延迟爆炸或发送警告消息

## 依赖项

此模组需要以下前置模组：
- IndustrialCraft 2 (IC2)
- structurelib
- dreamcraft
- gregtech
- Draconic Evolution

## 使用说明

### 基本使用
1. 安装模组及其依赖项
2. 启动服务器，模组会自动创建配置文件
3. 根据需要调整配置文件中的参数
4. 重启服务器以应用配置更改

### 手动触发爆炸
- 当`requireCommandToExplode`设置为true时，需要玩家在聊天中输入"坏了坏了"来手动触发已计划的爆炸
- 输入"核电，轻而易举啊！"可以触发所有已计划的爆炸
- `TriggerRequireOP`为true时（默认），只有 OP 可以使用这两个指令；设为 false 则任何人都能触发。单人游戏与局域网房主始终允许

### 配置调整
编辑配置文件以调整以下参数：
- 是否启用IC2/DE爆炸
- 爆炸延迟时间
- 是否需要手动触发
- 爆炸威力设置

## 技术实现

该模组使用Mixin技术注入到IC2和DE的核心类中，通过拦截关键方法来控制爆炸行为。主要的注入点包括：
- `ic2.core.ExplosionIC2#doExplosion` - 控制IC2爆炸
- `com.brandon3055.draconicevolution.common.tileentities.multiblocktiles.reactor.TileReactorCore#goBoom` - 控制DE反应堆爆炸

所有状态（延时任务、手动引信、放行令牌、去重标记）都由 `com.czqwq.EZNuclear.data.PendingMeltdown` 统一管理，
以 `(x, y, z, 维度)` 为 key，任务在服务器 tick 线程中执行。

## 适用版本

- Minecraft 1.7.10
- 需要Forge模组加载器

## 注意事项

- IC2核反应堆在爆炸被阻止后仍会被移除：IC2 在调用爆炸前就已经清空了反应堆并移除了核心与腔体方块
- 默认不覆写威力：本模组触发的爆炸沿用游戏原生威力（DE 按燃料计算，约 2~20；IC2 使用其自身数值）
- 需要固定威力时打开对应侧开关并用 `IC2ManualExplosivePower` / `DEManualExplosivePower` 指定威力（1~50）
- 旧的 `IC2ExplosionPower` / `DEExplosionPower` 已被上面的配置取代，老配置文件里的这两项不再生效
- 触发指令默认需要 OP 权限，避免任意玩家引爆全服已排队的爆炸