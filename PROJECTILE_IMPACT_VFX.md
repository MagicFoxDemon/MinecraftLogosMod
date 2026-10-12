# 魔弹命中特效的独立制作边界

本轮与 PROJECTILE_VFX.md 中已经完成的飞行外观分开。先统一公共入口，再从包含入口的同一主线基线创建独立工作树并行制作元素。

## 公共入口

- `ProjectileObj` 在实际实体接触（含穿透）、方块碰撞和炼成体阻挡时发送 `PROJECTILE_IMPACT`。位置为接触点，aux 为朝外方向，size 为弹丸半径。
- `TRAIL_END` 仍只负责终止飞行，variant=0 表示自然/其它结束，variant=1 表示碰撞结束。自然到期和法术被终止不会额外发送命中事件。
- 新包类型追加到 Kind 枚举末尾，不重排既有编号；多人游戏的服务端和客户端应使用相同模组版本。
- 不改变原有 on-hit、on-impact、on-expire、伤害、碰撞、寿命和穿透语义。通用 `BURST` 属于伤害等效果，不能用元素命中特效替换它。
- `ProjectileImpactVisual` 已提前注册全部七种元素。尚未升级的实现返回空列表，保留旧版结束爆点；新实现提供 impact 和 expiry 两组 Photon 模板，expiry 只在非碰撞结束时播放。
- look/trail 只覆盖飞行外观；自定义 trail 原有的结束粒子散落仍保留。

## 每个元素可修改的文件

- 自身 `client/<Element>ProjectileImpactVisual.java`，以及以相同前缀命名的专属辅助类。
- 自身 `assets/logos/textures/vfx/<element>_impact/` 和 `shaders/core/<element>_impact_*.fsh/.vsh`。飞行贴图只读，避免覆盖已验收资产。
- 自身专属客户端场景，例如 `FireProjectileImpactScenario.java`。

共享入口 `LogosFx`、`ProjectileImpactVisual`、网络包、`Vfx`、`ProjectileObj`、公共测试注册、飞行实现及其它元素在后续元素分支保持只读。公共调整单独集中处理。仅分支聊天而使用同一个工作目录不能隔离修改；必须使用独立工作树。

## 验收

检查普通和较大尺寸、昼夜、初闪/余迹/完全消退、多发命中、实际撞墙与实体、自然到期。公共基线额外验证穿透命中后继续飞行及后续自然结束。按 CONTRIBUTING.md 运行测试；资源与代码仍分别提交 PR。源素材、来源清单、检查脚本和本地预览入口留在仓库外。

## 火焰实例的制作方法

1. 先拆解 Unity prefab 引用的材质、纹理和发射器，记录寿命、尺寸、颜色、速度、发射形状与混合方式。prefab 和 Unity shader 不能直接交给 Photon 使用，需按层重建。
2. 火焰使用六类纹理：闪光、放射纹理、柔光、拉伸火星、余烬、星芒。灰度亮度导出到 alpha，RGB 为白色，再由发射器染色；全亮加法混合避免黑底。源参考中的黑色闪片未保留。彩色或带独立透明度的资产不能机械套用此转换。
3. `impact()` 提供一次碰撞爆发，`expiry()` 提供非碰撞结束的少量余烬。模板可静态缓存；发射器使用非循环、一次 burst，避免每个网络事件重新构建配置。
4. 局部 +Y 对齐碰撞朝外方向，公共入口将根节点朝外偏移 0.03 格。普通半径 0.3 对应缩放 1，默认缩放限制为 0.65–1.25；元素确需特殊比例时覆盖自己的 `scale()`。
5. 火焰闪光、放射纹理、柔光分别存活 7、11、14 tick，约寿命前 22% 达到峰值，再收缩、变透明；余烬存活 8–20 tick，发射器 duration 为 24 tick。不要让主要纹理扩张到最后一帧才被删除。尺寸和透明度同时消退，尾部层寿命错开。
6. Photon `ECBCurves` 每段八个数的顺序为起点 XY、控制点 1 XY、控制点 2 XY、终点 XY。以本项目实际依赖为准，不能仅看字段声明顺序推断构造参数。
7. 当前实现没有复现 Unity HDR/Bloom 或软粒子深度淡化，亦未增加持续燃烧、烟雾、地形破坏、点火或爆炸伤害。

截图保存会推进真实游戏时间。初闪、软化、淡出、余烬最好分别用新爆发采样，避免连续截图把短暂的消退段跳过去。自动断言主要验证事件路由与玩法；视觉验收仍需观察动态预览及阶段截图。既有 `BURST` 可能与命中层同时出现，不能误判为新效果的某一层。

## 后续分支分工

| 元素 | 实现文件 | 资源目录 | 建议专属场景 |
| --- | --- | --- | --- |
| 寒冰 | `FrostProjectileImpactVisual.java` | `frost_impact/` | `FrostProjectileImpactScenario.java` |
| 雷霆 | `LightningProjectileImpactVisual.java` | `lightning_impact/` | `LightningProjectileImpactScenario.java` |
| 虚空 | `VoidProjectileImpactVisual.java` | `void_impact/` | `VoidProjectileImpactScenario.java` |
| 物理 | `PhysicalProjectileImpactVisual.java` | `physical_impact/` | `PhysicalProjectileImpactScenario.java` |
| 奥秘 | `ArcaneProjectileImpactVisual.java` | `arcane_impact/` | `ArcaneProjectileImpactScenario.java` |
| 生命 | `LifeProjectileImpactVisual.java` | `life_impact/` | `LifeProjectileImpactScenario.java` |

实现文件位于 `src/main/java/dev/logos/client/`，场景位于其 `uitest/` 子目录，资源目录相对于 `src/main/resources/assets/logos/textures/vfx/`。火焰已完成，可作为只读参考。专属客户端场景用 `@LDLRegisterClient` 自动注册，无需改公共名单，名称用 `logos_<element>_projectile_impact`。

开工前确认资源 PR 和公共实现 PR 已合并，fetch 最新 main，再各自创建独立工作树及分支。只分支聊天不等于隔离工作目录；不要从尚未合并的旧飞行分支开工。后续聊天首先完整阅读 CONTRIBUTING.md 与本文，确认自己的元素、参考 prefab 和工作树；只修改表中对应实现、资源与场景。遇到公共入口不足，记录问题交回协调聊天处理。

每个分支先做一版动态预览，维护者验收后再提交。客户端命令为 `./gradlew runClient -PldTest=logos_<element>_projectile_impact --no-configuration-cache`；提交前运行 `./gradlew test`、`./gradlew runGameTestServer` 和对应客户端场景。独立工作树也应避免同时运行占用同一端口或共享缓存输出的游戏测试进程；先完成代码制作，再错开最终验证。
