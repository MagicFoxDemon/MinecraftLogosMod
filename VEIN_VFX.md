# 游脉（vein）特效的独立制作边界

本说明只约束游脉 `vein` 的特效分工。协作与提交规则仍以 CONTRIBUTING.md 为准，渲染必须遵守 SHADER_PACK_VFX.md。

游脉是沿方块表面爬行的形体：

- 它有一个或多个头部（环扩 `ring` 有 8–32 个），每个头部贴着它所在的面走，带 `:climb` 时可以爬上墙、走过天花板、绕过屋檐。
- 它有宽 `:width`、高 `:height`，范围都是 0.25–4，默认 1。
- 带 `:linger` 秒时，它走过的地方会留下形体，留下的部分和头部一样判定命中，从最早留下的开始消失。

判定由服务端决定，这里的一切只改画面。

## 每个元素两种外观

游脉开不开「留下形体」（`:linger`）是两种不同的东西，每个元素都要各做一套：

- **划过**（不留形体）：一个从地面上划过去的东西，走过就没了。例如物理是贴地划过的剑气。
- **留下**（留形体）：地面留下来的东西，在 `:linger` 秒内一直在，然后消失。例如物理是从地里伸出来的地刺。

| 元素 | 划过 | 留下 |
| --- | --- | --- |
| 火 | 待定 | 火焰晶簇（参考 `Impale_Fire`） |
| 霜 | 待定 | 冰刺（参考 `Impale_Frost`） |
| 物理 | 淡金色剑气，地面留下发光的切痕，两侧翻起地面方块（参考 `NS_GroundSlash06`） | 更尖的地刺（参考 `NS_AberrateObelisk`、`NS_DashMagma`） |
| 生命 | 待定 | 荆棘藤蔓（参考 `NS_RoundedVine_Forest`） |
| 雷、虚空、奥术 | 待定 | 待定 |

待定的由维护者找好参考再定。雷和虚空的「留下」不宜再用尖刺。

参考是维护者 Unity 工程里 Piloto Studio 的 *Elemental VFX Mega Bundle*（不在仓库里，路径 `Assets/Piloto Studio/Elemental VFX Mega Bundle/<元素>/`）：

- 直行留下尖刺：参考 `Impale_<元素>`（带 2 的是更尖的版本），几乎每个元素都有；
- 弧扫划过：参考 `ElementalSlash_<元素>`，是一道绕圆扫过去的弧光。

另一部分参考在维护者的 Unreal 工程里（同样不在仓库里，`Content/` 下）：

- 生命留下：`Forest_VFX/Niagara/VineBarrior/NS_RoundedVine_Forest`（由蓝图 `Forest_VFX/Blueprint/BP_GroundAttackFX_Nature` 调参数生成直线和环绕）；
- 物理留下：`__GroundAttackVFX/NS/NS_AberrateObelisk`、`NS_DashMagma`；
- 物理划过：`StylizedSlashesVol1/Particles/NiagaraSystems/NS_GroundSlash06`。

贴图可以从那里取，但先确认仓库里没有同样的，有就按路径复用。

## 公共入口

### 服务端发来的包

`VeinObj` 通过 `FX` 包告诉客户端每个头部的状态。下表三种 `Kind` 只能在末尾追加：

| `Kind` | 何时 | 内容 |
| --- | --- | --- |
| `VEIN_HOLD` | 头部爬行时每刻一次 | `variant` 是第几个头部。位置是它站在面上的那一点，`(ax,ay,az)` 是沿面的朝向。`size` 是宽，`spin` 是高，`look` 是所在的面（`Direction` 的 3D 序号）。`particle` 是留下的部分保持多少刻，没有就是 0 |
| `VEIN_END` | 这个头部停下 | 停下的位置；`size` 为 1 表示撞上了东西（生物或墙），否则为 0 |
| `VEIN_FADE` | 整个游脉连同它留下的部分都没了 | 立刻丢掉这个游脉的所有头部 |

### 客户端

- `VeinVisuals` 为每个头部保留一个 `VeinVisual`：
  - 超过 10 刻没有收到 `VEIN_HOLD`，就当这个头部已经停下；
  - 停下后最多再留 `LINGER_CAP`（100 刻）加上留下的刻数。
- `VeinVisual` 有三个方法：
  - `tick(level, vein)`：每个客户端刻调用（暂停时不调用）。头部爬行时返回值被忽略；停下后返回 false 就丢掉它。
  - `submit(level, vein, camera, partialTick, pose, out)`：每帧调用。`out` 已经过 `ShaderPackOverlay`。
  - `end()`：丢掉时释放资源。
- `VeinVisual.create(元素, 是否留下)` 交给各元素自己的 `<Element>Veins.create(是否留下)`。还没做的外观返回公共的染色外观 `VeinVisual.tinted(...)`：
  - 划过用 `TintedVeinVisual`；
  - 留下用 `TintedLingeringVeinVisual`。
- `LiveVein` 提供：
  - `element()`、`id()`、`head()`；
  - `pos()`、`prev()`：这一刻和上一刻站的位置，两者之间就是这一刻爬过的路；`at(pt)` 在两刻之间插值；
  - `dir()`：沿面的朝向，单位向量；
  - `face()`、`normal()`：所在的面，以及垂直于面向外的单位向量；
  - `width()`、`height()`；
  - `linger()`：留下的部分保持多少刻，0 表示不留；
  - `ended()`、`struck()`（停在了东西上）、`sinceEnd()`。
- 时间用 `ClientCharges.ticks()` 的客户端时钟。截图时可以用 `ClientCharges.freeze` 冻结它。

## 必须做到

- **贴着面画。** 头部可能在地面、墙面或天花板上。所有东西都按 `normal()` 和 `dir()` 摆，不要假定向上是 +Y。地刺要从墙里横着伸出来，从天花板上倒着长下来。
- **留下的部分跟着服务端的时间走。** 头部走过一段，这一段就要在 `linger()` 刻后消失，最早的先没，和服务端判定的范围一致。看起来还在的地方就还能伤人。
- **控制数量。** 一次环扩最多 32 个头部同时在画，每个头部都按「每格多少个」铺东西：
  - 每个头部的几何和粒子要有上限；
  - 远处减少细节（例如超过 40 格不画小晶体）；
  - 用原版粒子时每刻每个头部只放一两个。
- **光影包下可用**（SHADER_PACK_VFX.md）：
  - 自定义几何只通过 `out` 提交，每个顶点写全位置、颜色、UV、第二 UV 或光照、法线；
  - 自定义片元着色器放在 `shaders/core/<element>_vein_*`。

## 公共核心：晶体（元素分支只读）

尖刺类的「留下」外观共用 `VeinCrystals` 和 `CrystalVeinVisual`。

### `VeinCrystals`

头部每爬过一段，它就在这段路上立起晶体：

- 有大有小。大的靠近中线，小的散在周围，每根都随机偏离表面法线一点。
- 每根约 2.5 刻冲出来，略微过冲。
- 一直留到服务端判定的那一段消失，最后的 30%（最多 12 刻）里缩回面里并碎裂溶解。
- 每个头部最多 240 根。超过 40 格不画小晶体，超过 128 格都不画。

外观由 `VeinCrystals.Style` 决定：

| 方法 | 内容 |
| --- | --- |
| `Style.of(纹理, 边缘色)` | `textures/vfx/` 下可平铺的纹理，按它自己的颜色画；边缘色是背对镜头的面和溶解边缘的光 |
| `withTint(颜色)` | 乘在纹理上的颜色 |
| `withClear(不透明度)` | 小于 1 时晶体本体半透明，边缘仍然不透明 |
| `withContrast(对比度)` | 小于 1 时压低纹理的明暗，颜色更干净均匀（物理用 0.3） |
| `withFlow(每格重复, 每秒绕流, 每秒上流)` | 纹理的大小和流动 |
| `withShape(棱数, 尖端占比, 斜切, 最大倾角)` | 形状：四棱斜切的晶体，或五棱尖锐的冰刺 |
| `withSize(粗, 长)` | 半径乘宽、长度乘高的倍数 |
| `withCount(大, 小)` | 每爬一格立几根大的、几根小的 |

材质是 `core/vein_crystal`：

- 顶点阶段用领域的 `core/field_line`；
- 纹理沿晶体流动，每个面单独受光，背对镜头的面发出边缘光；
- 冲出时会闪一下，溶解时按纹理碎成小块，裂口发光；
- 剔除背面并写深度，所以晶体彼此遮挡，也挡住身后的粒子。

### `CrystalVeinVisual`

留下外观的父类，元素传入自己的 `Style`，再按需要覆盖这些钩子：

- `crawling`：头部爬行的每一刻。默认在头部放一个元素粒子。
- `thrust`：每根晶体刚立起时。默认什么也不做。
- `stopped`：头部停在东西上的下一刻。默认爆一团元素粒子。
- `standing`：只要还有晶体立着，每一刻都调用。默认什么也不做。
- `any(r)`：随机取一根还立着的晶体，`crystals.crystals()` 是全部。

## 分工

- 每个元素只改自己的东西：
  - `<Element>Veins.java`；
  - 自己的 `<Element>…VeinVisual` 类；
  - `textures/vfx/<element>_vein/` 下的贴图；
  - `shaders/core/<element>_vein_*`；
  - 自己的客户端场景。
- 公共入口和核心（本文件、`VeinVisual`、`VeinVisuals`、`LiveVein`、`VeinCrystals`、`CrystalVeinVisual`、`VeinTour`）集中处理。需要新钩子时先提出来，不要在元素分支里改。
- 划过和留下可以分开提交，一个 PR 做一种。

## 客户端场景

`VeinTour` 是各元素统一的展示。它用这个元素的真实施法（默认 `(on-hit (damage target 2 <元素>))`；没有伤害的元素重写 `onHit`，例如生命用 `(heal target 2)`），依次截图：

| 截图 | 内容 |
| --- | --- |
| `<元素>_vein_pass` | 划过，从侧面看走到一半 |
| `<元素>_vein_wall` | 划过，撞上墙停下 |
| `<元素>_vein_linger_rise` | 留 3 秒，走到一半时正在冒出来 |
| `<元素>_vein_linger` | 留 3 秒，头部停下以后 |
| `<元素>_vein_linger_close` | 同上，贴近地面从末端往回看 |
| `<元素>_vein_linger_fade` | 同上，最早的部分开始消失 |
| `<元素>_vein_ring` | 留 2 秒的环扩，从上方看 |
| `<元素>_vein_climb` | 留 3 秒，爬上墙面的时候 |
| `<元素>_vein_climb_held` | 同上，头部停下以后 |
| `<元素>_vein_night` | 夜里留 3 秒 |

- 元素分支继承它、实现 `element()`，注册为 `logos_<element>_vein`，可以在 `extra` 里加自己的步骤。
- `logos_vein_core` 用灰色噪声加奥术配色画公共晶体，借 `VeinVisuals.substitute(...)` 替换外观（只供客户端场景使用）。
- 公共场景 `logos_vein` 用真实施法检查头部的数量、位置、爬墙和留下，不看外观。

```bash
./gradlew runClient -PldTest=logos_fire_vein -PldTestHeadless
```

截图在 `build/ldlib2-uitest/screenshots/logos_<element>_vein/`。

想直接看动态，用预览脚本：它在游戏窗口里跑一遍展示场景，跑完打开截图文件夹（双击 `tools/preview-vein.cmd` 就是把做好的元素全跑一遍）。元素做好外观后，把它加进脚本的 `$known`。

```powershell
tools\preview-vein.ps1 fire
tools\preview-vein.ps1 life,physical
tools\preview-vein.ps1 frost -Headless
```
