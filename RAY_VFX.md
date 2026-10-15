# 射线特效（ray）的独立制作边界

本说明只约束 `ray` 射线（以及 drain、execute 的连线）特效的分工，协作与提交规则仍以 CONTRIBUTING.md 为准，渲染必须遵守 SHADER_PACK_VFX.md。射线和持续光束（beam）是两回事：射线是瞬间成立的一条线，没有持续时间；蓄力已经由法杖完成，射线只负责“瞬间爆发射出”以及之后留在线上的余迹。

## 公共入口

- 服务端用 `Vfx.ray(level, from, to, 元素, 施法者, Vfx.RayEnd)` 发送，仍是 `BEAM` 包：variant 是 `RayEnd` 序号，look 是施法者的网络 id（从眼睛射出时客户端改从施法点画起），没有时为 -1。`RayEnd` 只能在末尾追加：

  | `RayEnd` | 含义 |
  | --- | --- |
  | `OPEN` | 射程用尽，打在空中 |
  | `BLOCK` | 被方块挡住，`to` 是撞击点 |
  | `CREATURE` | 停在一个生物身上（`:to` 指定的目标，或无法再穿透的那个），`to` 是它的中心 |
  | `LINK` | drain、execute 在两个身体之间画的连线（`Vfx.line`） |

- 客户端 `RayVisual.create(元素)` 为每种元素返回 `<Element>RayVisual`。生命周期：
  - `start(level, from, to, end)`：射线到达时调用一次。`from` 已经是施法点。
  - `tick(level)`：之后每个客户端刻调用，返回 false 时丢弃。上限 200 刻（`LogosFx.RAY_CAP`），超时也会丢弃并调用 `end()`。
  - `submit(level, camera, partialTick, pose, out)`：每帧调用。`out` 已经过 `ShaderPackOverlay`，自定义几何直接 `out.submitCustomGeometry(...)`。
  - `end()`：提前丢弃（离开世界、超时）时释放仍在播放的 Photon 运行时。
- `legacy()` 默认为 true：未升级的元素继续画旧的珠串（每格 5 颗、最多 80 个 Photon 运行时）。升级时在自己的类里返回 false 并实现上面的方法。
- 渲染规则（SHADER_PACK_VFX.md）：Photon 粒子用 Photon 自带材质（`TextureMaterial`、`CustomShaderMaterial`，片元着色器配 `"vertex": "photon:particle"`），不要返回自己构造的 `RenderType`；自定义几何每个顶点写全位置、颜色、UV、光照和法线，只通过 `out` 提交。
- 第一人称：射线从施法点（法杖头）射出，紧贴镜头。起点附近的爆发不能遮住视野，起点的闪光要克制或偏离镜头。

## 每个元素可修改的文件

- 自身 `client/<Element>RayVisual.java`，以及以 `<Element>Ray` 开头的专属辅助类（如材质 `<Element>RayMaterial`）。
- 自身资源 `assets/logos/textures/vfx/<element>_ray/` 和 `shaders/core/<element>_ray_*`。
- 自身专属客户端场景 `client/uitest/<Element>RayScenario.java`，注册名 `logos_<element>_ray`，用 `@LDLRegisterClient` 自动注册。

共享文件在元素分支保持只读：`RayVisual`、`LogosFx`、`Vfx`、`Runes`、网络包、`ShaderPackOverlay`、其它元素的射线、光束、魔弹和 BURST 实现。可以参照已验收的同元素光束（`<Element>BeamVisual`）与魔弹的写法和调色，但不要修改它们，也不要引用它们的私有资源；需要同样的贴图时复制到自己的目录。公共入口不足时记录下来交回协调聊天。仅分支聊天而使用同一个工作目录不能隔离修改；必须使用独立工作树。

## 参考与要求

参考包：`Piloto Studio/Elemental VFX Mega Bundle` 的 `PS_UltimateBeam_*` 系列。它们是“蓄力后向前瞬间发射”，蓄力部分不要做，**只取后半段瞬间爆发射出的部分**。拆解方法同之前：逐层记录寿命、尺寸、速度、颜色、发射形状与混合方式，灰度纹理导出为白色 RGB + 亮度 alpha 再染色；网格层预烘焙或改用广告牌/自定义几何。

除物理外，每个元素爆发之后都要在那条线上**留下一条缓慢消失的余迹**，体现元素特色：

| 元素 | 参考 prefab | 余迹 |
| --- | --- | --- |
| 火焰 | `Fire/PS_UltimateBeam_Fire.prefab` | 一道烟，随后缓慢飘散 |
| 寒霜 | `Frost/PS_UltimateBeam_Frost_01.prefab`（及 `_02`） | 自行设计，如寒雾与冰晶碎屑 |
| 雷霆 | `Shock/PS_UltimateBeam_ShockBlue.prefab` | 自行设计，如残留电弧明灭 |
| 虚空 | `Void/PS_UltimateBeam_Void.prefab` | 一条逐渐变窄、最后消失的直线（可沿用虚空的屏幕空间星空） |
| 奥秘 | `Arcane/PS_UltimateBeam_Arcane.prefab` | 自行设计 |
| 生命 | `Nature/PS_UltimateBeam_Nature.prefab` | 自行设计 |

**物理与众不同**，要表现张力、力量感、纯粹的劲大和速度感（参考 EVA 的雷天使；参考图在仓库外 `art/ray/refs/`），可参照已验收的物理光束（#150、#151）：

- 射线本体只是一条**细长的白色亮线**。
- 发射时段：起点的**十字闪光**；压缩空气并推开的**气浪**（沿线的压力环）；**音爆**（锥形冲击环）。
- 命中时：**气浪冲击**（`BLOCK`、`CREATURE` 时在终点爆开）。
- 结束之后：原地留下一条**细长的直线，间断闪烁并消失**。

特效要短促有力，余迹不能长时间遮挡视线；颜色沿用该元素魔弹、命中、光束的调色。

## 验收

检查空中、撞墙、命中生物、连线（drain）四种结束方式；第一人称（不遮挡视野）与第三人称；侧面看射线全长；近距离与最远射程（64 格）；昼夜；连发多条；余迹的完整消失。阶段截图每次重新施放再采样。按 CONTRIBUTING.md 运行测试，渲染改动按 SHADER_PACK_VFX.md 在光影下检查（没测就写“光影下未测”）；资源与代码分别提交 PR。公共路由场景：`./gradlew runClient -PldTest=logos_ray --no-configuration-cache`。

开工前确认本入口的公共实现 PR 已合并，fetch 最新 main，再各自创建独立工作树。每个分支先做动态预览并在工作区根目录提供 `预览<元素>射线.cmd`，维护者验收后再提交。
