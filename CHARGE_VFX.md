# 法杖蓄力（charge）特效的独立制作边界

本说明只约束蓄力特效的分工，协作与提交规则仍以 CONTRIBUTING.md 为准，渲染必须遵守 SHADER_PACK_VFX.md。蓄力是法杖（以及生命体）施法前的阶段：魔力注入、蓄满保持、松手释放，或中途放弃熄灭。准星处的进度圈（`ChargeRing`）是界面，不属于本说明，保持不变；服务端的蓄力音效不变。

## 公共入口

- 服务端每两刻发送 `ChargePayload`（元素、等级、进度、是否蓄满），释放时发送 `released`，放弃时发送 `ended`；这部分没有改动。等级由注入的法力决定（`Charging.tier`）：

  | 等级 | 名称 | 法力 |
  | --- | --- | --- |
  | 0 | 普通 | < 100 |
  | 1 | 精致 | 100 – 800 |
  | 2 | 宏大 | 800 – 3200 |
  | 3 | 巨型 | ≥ 3200 |

- 客户端 `ChargeVisual.create(元素)` 为每种元素返回 `<Element>ChargeVisual`。`ChargeVisuals` 为每个蓄力的施法者保留一个，生命周期：
  - `tick(level, charge)`：从第一次收到更新起每个客户端刻调用（游戏暂停时不调用）。蓄力中、保持中返回值被忽略；释放或熄灭之后返回 false 即丢弃，最长 `ChargeVisuals.LINGER_CAP`（200 刻）。
  - `submit(level, charge, camera, partialTick, pose, out)`：每帧调用。`out` 已经过 `ShaderPackOverlay`。
  - `end()`：丢弃时（播放完毕、施法者离开范围或消失、离开世界、超时）释放仍在播放的 Photon 运行时。
- `LiveCharge` 提供一切所需信息：`element()`、`tier()`、`phase()`（`CHARGING` → `HELD` → `RELEASED` / `FIZZLED`）、`entered()`（每个阶段的第一刻为 true）、`progress(pt)`（0–1，服务端两次更新之间已插值；保持与释放为 1，熄灭时停在当时的进度）、`age(pt)`、`phaseAge(pt)`、`tip(pt)`（施法点：法杖头本帧画出的位置、生命体的施法骨骼或估算）、`feet(pt)`、`look(pt)`、`height()`、`firstPerson()`、`near()`（杖头效果的缩放：第一人称 0.5，其余 1）。施法者消失后位置停在最后看到的地方。
- 没见过蓄力过程就收到的释放（施法者刚进入范围）也会建立一个直接处于 `RELEASED` 的 `LiveCharge`，播放释放效果。
- `legacy()` 默认为 true：未升级的元素不进入 `ChargeVisuals`，由 `ChargeEffects` 继续画旧的原版粒子。升级时在自己的类里返回 false。`ChargeEffects.last()` 仍记录每次释放与熄灭，供场景检查。
- 渲染规则（SHADER_PACK_VFX.md）：Photon 粒子用 Photon 自带材质（`TextureMaterial`、`CustomShaderMaterial`，片元着色器配 `"vertex": "photon:particle"`），不要返回自己构造的 `RenderType`；自定义几何每个顶点写全位置、颜色、UV、光照和法线，只通过 `out` 提交。

### 客户端场景与预览

- `ChargePreview`（`client/uitest`）在本客户端按固定节奏模拟一次蓄力：`release(实体, 元素, 等级, 蓄力刻数, 保持刻数)` 或 `fizzle(实体, 元素, 等级, 蓄力刻数, 中断进度)`。不施法、不消耗法力；本地玩家会像真实蓄力一样举起法杖。
- `ChargeTour` 是各元素预览的统一展示：正面第三人称依次播放四个等级（每级在聚集、将满、保持、释放、释放后各截一张图），第一人称再播放精致与巨型，背后视角中途放弃一次宏大，夜晚再播放一次巨型。元素分支继承它、实现 `element()`，注册为 `logos_<element>_charge`，需要时在 `extra` 里追加步骤。
- `ChargeVisuals.substitute(...)` 只供客户端场景替换所有元素的可视化（公共场景 `logos_charge` 用它检查生命周期）。

## 分工：公共核心 + 元素特色

四个等级的效果层次多、各元素共用同一套结构，因此和伤害 BURST 一样分两步：

1. **公共核心**（协调聊天完成，元素分支只读）。按参考做出所有等级的完整效果，由元素的调色与少量可替换项（法阵图案、粒子贴图）参数化：
   - `ChargeCore`：杖头部分，即聚集、环绕聚拢、冲击波、巨型的远方条带、保持时的能量球、巨型释放、熄灭。
   - `ChargeBody`：身上部分，即法阵、光柱、地面扩散的波浪、被升到空中的粒子。
   - 两者共用一个 `ChargeStyle`（主色、副色、法阵图案等）。核心完成后，七个元素先以各自的 `ChargeStyle` 接上核心，外观即全部替换。
2. **元素特色**（各元素分支并行）：在核心之上添加本元素的特色层，并可以调整 `ChargeStyle`。例如火焰的火星与日冕状能量球、寒霜的冰晶、雷霆缠身的电弧（参考图 3）、虚空的屏幕空间星空、生命的叶片、奥秘的符文、物理的白色气压。

## 参考与要求（各等级）

参考资源：

- 杖头完整蓄力演示：Hovl `Auras pack 3/Prefabs/Star aura.prefab`。八层（拆解见仓库外 `art/charge/star-aura-inspection.txt`）：
  - `Star aura`：螺旋拖尾。
  - `SharpTrails`：直线拖尾。
  - `Sparks`、`SubSparks`、`LongSparks`：光点、拖尾上的细点、长火花，都从半径 3 的球面径向吸入中心。
  - `Glass`：旋入的菱形碎片。
  - `SoftTrails`：中心柔光。
  - `ShockWave`：每 0.5 秒一次的冲击波，可复用 `heal/ring`。
- 光束瞬发的前半段：`Piloto Studio/Elemental VFX Mega Bundle` 的 `PS_UltimateBeam_*`，延迟 0.9 秒以前的层（拆解见 `art/charge/ultimate-void-inspection.txt`）：
  - `CS_Impact_Circle_Wind_Soft`：不断缩小的风圈。
  - `Trail_WindyGeneric`：从半径 2.5 的圆周螺旋吸入的风痕，越来越密。
  - 中心的 `Flare_FatDot` 光团。
  - 各元素有同名的本元素版本。
- 巨型的条带：《为美好的世界献上祝福》惠惠爆裂魔法的蓄力。
- 身上上升的粒子与环绕：Unreal `NiagaeaBigVFXPack/Effects/Niagara/Tornado/NS_Tornado_under`。
- 地面扩散的波浪：Unreal `NiagaeaBigVFXPack/Effects/Niagara/Explosion/NS_Explosion_Wind_1`。
- 法阵图案：Unreal `/Game/MagicCircle/Texture` 的 `T_MagicCircle_M_01`–`29`（1024，灰度）。已导出为 TGA，在仓库外 `art/charge/ue/exports/`，总览图为 `art/charge/magic-circles.png`。`M_08`、`M_09` 是彩色的，使用时要去色。Unreal 资源用无界面的 `UnrealEditor-Cmd -run=pythonscript` 导出（脚本见 `art/charge/ue/export_circles.py`）。

### 杖头

| 等级 | 蓄力中 | 快结束时 |
| --- | --- | --- |
| 普通 | 参考 Star aura 的 `SubSparks` 和 `Sparks`：只有粒子汇集的感觉 | 粒子变少，蓄满时不再出现 |
| 精致 | 普通的基础上，加上 `PS_UltimateBeam_*` 前半段那种环绕着向中间聚拢的感觉 | 变少、变细，蓄满时不再出现 |
| 宏大 | Star aura 的全部效果，尤其 `ShockWave`：蓄力时一阵阵的能量冲击 | 粒子变小 |
| 巨型 | 之前等级的部分效果，加上从很远处向蓄力中心聚集的条带：越远越宽、越透明，显得恢宏盛大 | 条带逐渐变细成一条细线，蓄满时消失。同时逐渐显现一个看上去相当高能的球体，向蓄力中心收拢，然后蓄满 |

- **保持（`HELD`）**：按等级显示不同的能量球，等级越高效果越复杂。
- **释放（`RELEASED`）**：目前只有巨型需要额外效果，即能量球向外扩散后炸开。其余等级的能量球随释放干净地消失，法术本身的效果紧随其后。
- **熄灭（`FIZZLED`）**：不是重点，做简单就好，不要喧宾夺主。例如能量球（或正在成形的效果）暗下去熄灭，或漏出一点能量后消散。

### 身上

| 等级 | 效果 |
| --- | --- |
| 普通 | 没有 |
| 精致 | 脚下一个简单的法阵 |
| 宏大 | 更复杂的法阵；施法者被光柱包围 |
| 巨型 | 更复杂的多层法阵与图案；施法者被光柱包围；脚下向外扩散的波浪；周围的粒子被升到空中，并有环绕效果（参考 `NS_Tornado_under`） |

- **法阵图案**：核心为每个等级选定一组（建议精致用简单线圈如 `M_16`、`M_17`；宏大用 `M_15`、`M_18`；巨型叠加 `M_03`、`M_11`、`M_29` 等）。元素可以在 `ChargeStyle` 里替换为更贴合的图案，例如：
  - 火焰：`M_05`、`M_06`
  - 寒霜：`M_23`、`M_24`
  - 雷霆：`M_10`
  - 奥秘：`M_04`、`M_12`
  - 生命：`M_13`、`M_14`
  - 虚空、物理：`M_07`、`M_27`
- **第一人称**：
  - 杖头效果紧贴镜头，大小按 `near()` 减半，能量球与闪光不能遮挡视野。
  - 光柱包围施法者，从内部看会糊满屏幕，所以第一人称不画施法者自己的光柱（或只保留很淡的外缘）。
  - 地面法阵和波浪低头可见，照常画。
  - 远处聚来的条带照常画，只是在杖头附近收细。
- **生命体**：施法点是施法骨骼，身体效果按 `height()` 和体型缩放。
- **性能**：同屏可能有多个施法者同时蓄力，每个蓄力的 Photon 运行时数量要有上限。远处施法者的大范围效果（巨型的条带、波浪）可以按距离精简。

颜色沿用该元素魔弹、命中、射线、光束的调色。

## 每个元素可修改的文件

- 自身 `client/<Element>ChargeVisual.java`，以及以 `<Element>Charge` 开头的专属辅助类。
- 自身资源 `assets/logos/textures/vfx/<element>_charge/` 和 `shaders/core/<element>_charge_*`。
- 自身专属客户端场景 `client/uitest/<Element>ChargeScenario.java`（继承 `ChargeTour`），注册名 `logos_<element>_charge`。

共享文件在元素分支保持只读：`ChargeVisual`、`ChargeVisuals`、`LiveCharge`、`ClientCharges`、`ChargeEffects`、`ChargeRing`、`ChargePreview`、`ChargeTour`、公共核心（`ChargeCore`、`ChargeBody`、`ChargeStyle` 及其资源 `textures/vfx/charge/`）、`LogosFx`、网络包、`ShaderPackOverlay`、其它元素的实现。已有的贴图（如 `heal/ring`、`heal/glow`、各元素射线和光束的贴图）直接按路径引用，不要复制出重复的美术资源。公共入口不足时记录下来交回协调聊天。必须使用独立工作树。

## 验收

检查四个等级的蓄力中、将满、保持、释放、释放后；中途放弃；第一人称（不遮挡视野）与第三人称；昼夜；多个施法者同时蓄力；生命体施法。按 CONTRIBUTING.md 运行测试，渲染改动按 SHADER_PACK_VFX.md 在光影下检查（没测就写“光影下未测”）；资源与代码分别提交 PR。公共场景：`./gradlew runClient -PldTest=logos_charge --no-configuration-cache`。每个分支先做动态预览并在工作区根目录提供 `预览<元素>蓄力.cmd`（运行 `logos_<element>_charge`），维护者验收后再提交。
