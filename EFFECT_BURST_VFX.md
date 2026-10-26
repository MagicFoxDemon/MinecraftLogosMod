# 效果生效特效（BURST）的独立制作边界

本说明只约束 `BURST` 特效的分工，协作与提交规则仍以 CONTRIBUTING.md 为准。`BURST` 表示“某个节点在目标身上生效了”，和魔弹命中（PROJECTILE_IMPACT_VFX.md）是两回事：伤害、治疗等节点不一定由魔弹触发，魔弹命中时两者会同时出现。

## 公共入口

- 服务端用 `Vfx.burst(level, 目标, 来源位置, 元素, Vfx.Burst)` 说明发生了什么。`Vfx.Burst` 按序号同步，只能在末尾追加：

  | 种类 | 现在的来源 | 客户端实现 |
  | --- | --- | --- |
  | `DAMAGE` | `Effects.damage`（damage、damage-all、drain 等所有法术伤害），execute 悖论反噬 | 按元素：`<Element>DamageVisual` |
  | `HEAL` | heal | `HealBurstVisual` |
  | `WARD` | shield 生效的一瞬间 | `WardBurstVisual`（持续的护盾球见下文） |
  | `FORCE` | push、pull、disarm | `ForceBurstVisual` |
  | `POTION_BENEFICIAL` / `POTION_HARMFUL` / `POTION_NEUTRAL` | potion，按 `Vfx.potion` 判断正负面 | `PotionBurstVisual`（构造参数是种类） |
  | `GENERIC` | 其余所有旧调用（freeze、blink、grow、decay、toggle-state、焚式、炼成体、使魔、魔力生命体） | 旧的通用爆点 |

- `Vfx.potion`：原版效果按类别分为正面（BENEFICIAL）、负面（HARMFUL）；原版的中性效果，以及所有非 `minecraft` 命名空间（其他模组）的效果，一律算中性。
- 目前没有任何效果的 ignite、launch、glow，以及 freeze 的专属外观，不属于本轮。
- 包仍是 `BURST`：variant 为种类序号；size 为按目标碰撞箱算出的缩放（约 0.6–2.5，玩家约 1）。aux 是方向，没有时为零：伤害和缴械为“施法者 → 目标”，push/pull 为力的方向。`GENERIC` 的 size 仍是旧的粒子数换算。
- `EffectBurstVisual.create(种类, 元素)` 已注册全部实现。`layers()` 返回空列表时继续播放旧的通用爆点，所以未完成的部分不会消失。
- 客户端把根节点的局部 +Z 转到 aux 方向；有方向时，效果画在目标朝向来源的一侧（离中心 0.45×缩放 + 0.3 格），否则在身体中心、局部 +Z 朝上。缩放由 `scale(size)` 决定，默认限制在 0.6–2。
- 七个元素都会造成伤害。奥秘玩家可写；生命玩家不可写，由其他模组的生物通过 `Effects.damage(攻击者, 目标, 数值, 元素)` 造成（如树妖）。这个入口与法术伤害共用元素强度、抗性与受伤特效，但保留原版受伤冷却，也不算作法术。

## 持续的护盾球

shield 产生的护盾存在期间，目标身上保持一个球。服务端用 `max_absorption` 上的 `logos:shield` 修饰符标记法术护盾（护盾被打破时移除）；这个属性会同步给客户端，所以客户端用 `Effects.shielded(entity)` 就知道哪些生物有护盾，不需要新的网络包。

- `WardSpheres`（公共）每个客户端刻扫描镜头 64 格内有护盾的生物，为每个生物保持一个 `WardSphereVisual`，并在每帧绘制时调用它；护盾消失、生物死亡或离开视野时调用 `end()`。
- `WardSphereVisual`（护盾分支负责）提供 `tick`、`submit`、`end`。`submit` 的 `firstPerson` 为真时，表示这个生物就是第一人称的镜头本身：球不能遮挡视野，可以直接不画。
- 绘制收集器已经过 `ShaderPackOverlay`，与魔弹、光束相同。

## 伤害的公共核心

`DamageBurstCore.layers(主色, 高光色)` 是所有伤害共用的“法术命中身体、造成伤害”部分，按 UniqueProjectilesVol_5 的 `vfx_Hit_Wind02` 重建：中心旋涡、向外甩出的弧形风刃、十字镜头光、朝来源溅回的尖刺和火花。资源在 `textures/vfx/damage/`，导出脚本与来源哈希在仓库外（`art/burst/prepare_damage_core.py`、`damage-core-sources.txt`）。

- WindMesh_01 是半个圆环网格，SwirlCenter_01 是圆盘网格；Photon 里改为预烘焙的 `arc.png`、`swirl.png` 广告牌，没有移植 Unity 的网格粒子、溶解滚动、HDR/Bloom 和软粒子。
- 七个伤害元素在公共核心之上各自追加了元素特色：物理、火焰、寒霜、雷霆、虚空见 #126–#135；奥秘（护符十字闪光、被击落的符文）与生命（凋谢的花冠、枯落的叶片）沿用各自命中特效的贴图，没有新增资源。

## 每个分支可修改的文件

| 任务 | 实现文件（`client/`） | 资源（`textures/vfx/`、`shaders/core/`） | 专属场景（`client/uitest/`，注册名） |
| --- | --- | --- | --- |
| `<元素>`伤害 | `<Element>DamageVisual` 及 `<Element>Damage*` | `<element>_damage/`、`<element>_damage_*` | `<Element>DamageScenario`，`logos_<element>_damage` |
| 治疗 | `HealBurstVisual` 及 `HealBurst*` | `heal/`、`heal_*` | `HealBurstScenario`，`logos_heal_burst` |
| 护盾 | `WardSphereVisual`、`WardBurstVisual` 及 `Ward*`（`WardSpheres` 除外） | `ward/`、`ward_*` | `WardScenario`，`logos_ward` |
| 推拉与缴械 | `ForceBurstVisual` 及 `ForceBurst*` | `force/`、`force_*` | `ForceBurstScenario`，`logos_force_burst` |
| 药水 | `PotionBurstVisual` 及 `PotionBurst*` | `potion/`、`potion_*` | `PotionBurstScenario`，`logos_potion_burst` |

共享文件在各分支保持只读：`DamageBurstCore`、`textures/vfx/damage/`、`EffectBurstVisual`、`WardSpheres`、`LogosFx`、`Vfx`、网络包、`Effects`、`Runes`、其它分支的实现。公共入口不足时记录下来交回协调聊天。仅分支聊天而使用同一个工作目录不能隔离修改；必须使用独立工作树。

## 参考

拆解方法同火焰命中（PROJECTILE_IMPACT_VFX.md）：逐层记录寿命、尺寸、速度、颜色、发射形状与混合方式，灰度纹理导出为白色 RGB + 亮度 alpha，再由发射器染色。

伤害（参考包 `GabrielAguiarProductions/UniqueProjectilesVol_5/Prefabs/Hits`）：

| 元素 | 参考 prefab | 可用的特色层 |
| --- | --- | --- |
| 火焰 | vfx_Hit_Fireball01–03 | FlameSheetDirectional_01 火焰序列、SmokeSheet_02 烟、火星 |
| 寒霜 | vfx_Hit_Icicle01–03 | CrystalSheet_01 冰晶碎片、SmokeSheet_04 寒雾 |
| 雷霆 | vfx_Hit_Lightning01–03 | LooseEnergySheet_01 游离电弧、ImpactShape_02 闪光 |
| 虚空 | vfx_Hit_Ghostly01–03 / Orb18_purple | 屏幕空间 UV 的星空是虚空的标志，须自建 `void_damage_*` 着色器 |
| 物理 | vfx_Hit_Slash01–03 | SlashShape_01 斩击、Spike_01、LensFlareAlpha_03 |
| 奥秘 | 无，沿用奥秘命中与射线 | `arcane_impact/cross` 十字闪光、`arcane_ray/runes` 符文 |
| 生命 | 无，沿用生命命中 | `life_impact/petal` 凋谢花冠、`life_impact/leaf` 枯落叶片（与治疗上升的叶片相反） |

其它作用：

| 任务 | 参考 prefab | 要求 |
| --- | --- | --- |
| 治疗 | `Hovl Studio/Auras pack 3/Prefabs/Heal aura.prefab` | 原版是持续光环；按它的风格改成一次生效、随即消散的效果 |
| 护盾 | `Piloto Studio/Elemental VFX Mega Bundle/Bees/HoneyShield.prefab` | 护盾存在期间保持那个球（Sphere_InOut 网格 + 蜂巢纹）；改成无属性的颜色与感觉，不遮挡第一人称视野，第一人称可以不可见 |
| 推拉与缴械 | `QFX/ProjectilesFX/VFX_Prefabs/Flashes/VFX_Comet_Flash.prefab` | 无属性的力的闪光，朝力的方向（局部 +Z） |
| 药水 | `Piloto Studio/Elemental VFX Mega Bundle/Poison And Acid/Green/18_PoisonSkull.prefab` | 改成出现后消散的一团药水烟（不要骷髅）；负面用毒药绿，正面用淡粉色，中性用第三种中性色 |

特效要短促，不能长时间遮挡目标；伤害颜色沿用该元素魔弹与命中特效的调色。

## 验收

检查普通与大体型目标、昼夜、各阶段（出现、峰值、消退、完全消失）、侧面观察方向性、多个目标同时生效，以及真实法术打在生物上的观感。护盾还要检查：第三人称看自己和看别的生物、第一人称不遮挡、护盾被打破时球消失。阶段截图每次重放新的效果再采样，避免截图耗时跳过短暂的消退段。按 CONTRIBUTING.md 运行测试；资源与代码分别提交 PR。公共预览：`./gradlew runClient -PldTest=logos_effect_burst --no-configuration-cache`。

开工前确认本入口的公共实现 PR 已合并，fetch 最新 main，再各自创建独立工作树。每个分支先做动态预览，维护者验收后再提交。
