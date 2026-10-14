# 效果生效特效（BURST）的独立制作边界

本说明只约束 `BURST` 特效的分工，协作与提交规则仍以 CONTRIBUTING.md 为准。`BURST` 表示“某个节点在目标身上生效了”，和魔弹命中（PROJECTILE_IMPACT_VFX.md）是两回事：伤害、治疗等节点不一定由魔弹触发，魔弹命中时两者会同时出现。

## 公共入口

- 服务端用 `Vfx.burst(level, 目标, 来源位置, 元素, Vfx.Burst)` 说明发生了什么。`Vfx.Burst` 按序号同步，只能在末尾追加：

  | 种类 | 现在的来源 | 客户端实现 |
  | --- | --- | --- |
  | `DAMAGE` | `Effects.damage`（damage、damage-all、drain 等所有法术伤害），execute 悖论反噬 | 按元素：`<Element>DamageVisual` |
  | `GENERIC` | 其余所有旧调用（heal、shield、freeze、disarm、blink、grow、decay、toggle-state、焚式、炼成体、使魔、魔力生命体） | 旧的通用爆点 |

- 治疗、护盾、负面效果等其它作用，以及目前没有任何效果的 ignite、potion、push、pull、launch、glow，之后各自追加种类与实现，不属于本轮。

- 包仍是 `BURST`：variant 为种类序号；除 `GENERIC` 外，aux 为“来源 → 目标”的单位方向（没有来源时为零），size 为按目标碰撞箱算出的缩放（约 0.6–2.5，玩家约 1）。`GENERIC` 的 size 仍是旧的粒子数换算。
- `EffectBurstVisual.create(种类, 元素)` 已注册全部实现。`layers()` 返回空列表时继续播放旧的通用爆点，所以未完成的部分不会消失。
- 客户端把根节点的局部 +Z 转到 aux 方向（从目标的远侧穿出）；没有方向时局部 +Z 朝上。缩放由 `scale(size)` 决定，默认限制在 0.6–2。
- 奥秘和生命不会造成伤害，`DAMAGE` 对这两个元素返回通用实现。

## 伤害的公共核心

`DamageBurstCore.layers(主色, 高光色)` 是所有伤害共用的“法术命中身体、造成伤害”部分，按 UniqueProjectilesVol_5 的 `vfx_Hit_Wind02` 重建：中心旋涡、向外甩出的弧形风刃、十字镜头光、目标远侧射出的尖刺和火花。资源在 `textures/vfx/damage/`，导出脚本与来源哈希在仓库外（`art/burst/prepare_damage_core.py`、`damage-core-sources.txt`）。

- WindMesh_01 是半个圆环网格，SwirlCenter_01 是圆盘网格；Photon 里改为预烘焙的 `arc.png`、`swirl.png` 广告牌，没有移植 Unity 的网格粒子、溶解滚动、HDR/Bloom 和软粒子。
- 五个伤害元素目前都只调用公共核心，颜色取自各自已验收的命中特效。元素分支在自己的文件里追加元素特色层，不修改核心。

## 每个元素可修改的文件

- 自身 `client/<Element>DamageVisual.java`，以及以相同前缀命名的专属辅助类（如材质 `<Element>DamageMaterial`）。
- 自身资源 `assets/logos/textures/vfx/<element>_damage/` 和 `shaders/core/<element>_damage_*.fsh/.vsh/.json`。
- 自身专属客户端场景 `client/uitest/<Element>DamageScenario.java`，注册名 `logos_<element>_damage`，用 `@LDLRegisterClient` 自动注册。

共享文件在元素分支保持只读：`DamageBurstCore`、`textures/vfx/damage/`、`EffectBurstVisual`、`LogosFx`、`Vfx`、网络包、`Effects`、`Runes`、其它元素的伤害/飞行/命中实现。公共入口不足时记录下来交回协调聊天。仅分支聊天而使用同一个工作目录不能隔离修改；必须使用独立工作树。

## 元素特色的参考

参考包：`GabrielAguiarProductions/UniqueProjectilesVol_5/Prefabs/Hits`，贴图在 `GabrielAguiarProductions/Textures`。拆解方法同火焰命中（PROJECTILE_IMPACT_VFX.md）：逐层记录寿命、尺寸、速度、颜色、发射形状与混合方式，灰度纹理导出为白色 RGB + 亮度 alpha，再由发射器染色。

| 元素 | 参考 prefab | 可用的特色层 |
| --- | --- | --- |
| 火焰 | vfx_Hit_Fireball01–03 | FlameSheetDirectional_01 火焰序列、SmokeSheet_02 烟、火星 |
| 寒霜 | vfx_Hit_Icicle01–03 | CrystalSheet_01 冰晶碎片、SmokeSheet_04 寒雾 |
| 雷霆 | vfx_Hit_Lightning01–03 | LooseEnergySheet_01 游离电弧、ImpactShape_02 闪光 |
| 虚空 | vfx_Hit_Ghostly01–03 / Orb18_purple | 屏幕空间 UV 的星空是虚空的标志（见 `VoidProjectileImpactMaterial`），须自建 `void_damage_*` 着色器，不能引用命中特效的材质 |
| 物理 | vfx_Hit_Slash01–03 | SlashShape_01 斩击、Spike_01、LensFlareAlpha_03 |

伤害特色要短促，不能盖过公共核心；颜色沿用该元素魔弹与命中特效的调色。

## 验收

检查普通与大体型目标、昼夜、各阶段（接触、旋涡、风刃、消退、完全消失）、侧面观察方向性、多个目标同时受伤，以及真实法术伤害和魔弹命中叠加时的观感。阶段截图每次重放新的爆发再采样，避免截图耗时跳过短暂的消退段。按 CONTRIBUTING.md 运行测试；资源与代码分别提交 PR。客户端预览：`./gradlew runClient -PldTest=logos_effect_burst --no-configuration-cache`。

## 后续分支

| 任务 | 实现文件 | 资源目录 |
| --- | --- | --- |
| 火焰伤害 | `FireDamageVisual.java` | `fire_damage/` |
| 寒霜伤害 | `FrostDamageVisual.java` | `frost_damage/` |
| 雷霆伤害 | `LightningDamageVisual.java` | `lightning_damage/` |
| 虚空伤害 | `VoidDamageVisual.java` | `void_damage/` |
| 物理伤害 | `PhysicalDamageVisual.java` | `physical_damage/` |

开工前确认本入口的资源 PR 和公共实现 PR 已合并，fetch 最新 main，再各自创建独立工作树。每个分支先做动态预览，维护者验收后再提交。
