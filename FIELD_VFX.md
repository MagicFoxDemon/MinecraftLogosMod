# 领域（field）特效的独立制作边界

本说明只约束领域 `field` 的特效分工。协作与提交规则仍以 CONTRIBUTING.md 为准，渲染必须遵守 SHADER_PACK_VFX.md。领域是一块持续存在的区域：有半径和存在时间，每隔一段时间脉动一次（`on-tick`），能发现进入的敌人（`on-enter`），可以跟随施法者（`:follow`）。判定由服务端决定，这里的一切只改画面。

## 公共入口

- 服务端（`FieldObj`）通过 `FX` 包告诉客户端领域的状态。下表四种 `Kind` 只能在末尾追加：

  | `Kind` | 何时 | 内容 |
  | --- | --- | --- |
  | `FIELD_HOLD` | 第一刻起每 4 刻一次 | 中心、半径 `size`、`(已存在刻数, 总存在刻数, 脉动间隔刻数)`；`look` 是它跟随的施法者网络 id，没有时为 -1 |
  | `FIELD_PULSE` | 每次脉动 | 中心、半径 |
  | `FIELD_ENTER` | 有敌人进入（领域带 `on-enter` 时才检测） | 进入者中心；`look` 是它的网络 id |
  | `FIELD_END` | 领域结束（到期或法术熄灭） | 中心 |

  原来的 `RING`、`PULSE` 不再发送，但这两个 `Kind` 保留（只能追加）。
- 客户端 `FieldVisual.create(元素)` 为每种元素返回 `<Element>FieldVisual`。`FieldVisuals` 为每个领域保留一个：
  - `tick(level, field)`：每个客户端刻调用（暂停时不调用）。领域存在时返回值被忽略；结束后返回 false 即丢弃，最长 `FieldVisuals.LINGER_CAP`（100 刻）。
  - `submit(level, field, camera, partialTick, pose, out)`：每帧调用。`out` 已经过 `ShaderPackOverlay`。
  - `end()`：丢弃时释放资源。
  - 超过 20 刻没有收到 `FIELD_HOLD`，就当它已结束。
- `LiveField` 提供：
  - `element()`、`phase()`（`ACTIVE` / `ENDED`）、`entered()`（第一刻为 true）、`radius()`、`life()`、`every()`、`follows()`；
  - `age(pt)`，以及 `progress(pt)`（0–1，领域生命的进度，就是时钟转一圈）、`sinceEnd(pt)`、`sincePulse(pt)`、`pulses()`；
  - 本刻新收到的事件 `newPulses()`、`newEntries()`（进入者位置与 id）；
  - `centre(pt)`：跟随的领域紧贴施法者，其余在两次更新之间平滑移动；
  - `ground()`、`floor(pt)`：中心下方第一块实心方块的顶面，法阵就平铺在这个高度；
  - `cameraInside(camera, pt)`：镜头是否站在领域里。
  - 时间用 `ClientCharges.ticks()` 的客户端时钟。客户端场景截图时可以用 `ClientCharges.freeze` 冻结它。
- `legacy()` 默认为 true：未升级的元素不进入 `FieldVisuals`，`LogosFx` 继续播放旧的粒子圈（每次 `FIELD_HOLD`）和脉动（每次 `FIELD_PULSE`），外观和以前一样。
- 渲染规则（SHADER_PACK_VFX.md）：自定义几何每个顶点写全位置、颜色、UV、光照和法线，只通过 `out` 提交。自定义片元着色器的写法参照现有的光束与虚空实现，Photon 层用 Photon 自带材质。

### 客户端场景与预览

- `FieldPreview`（`client/uitest`）在本客户端模拟一个领域，走真实的 `LogosFx.handle` 路径：
  - `start(元素, 中心, 半径, 存在刻数, 脉动间隔, 跟随的实体或 -1)`；
  - `enter(位置, 实体)`；
  - `elapsed()` 是它自己的时钟，截图冻结时停走。
- `FieldTour` 是各元素统一的预览展示，依次是：
  - 施法者身边的领域（正面，看展开、稳定、脉动、有人进入、时钟过半和将满、结束）；
  - 从边缘俯看的大领域（半径 9）；
  - 第一人称站在跟随施法者的领域里；
  - 夜晚。

  元素分支继承它、实现 `element()`，注册为 `logos_<element>_field`。截图对齐用 `at(s, 刻)` 和 `shot(s, 名字)`。
- `FieldVisuals.substitute(...)` 只供客户端场景使用。公共场景 `logos_field` 用真实施法检查以下几点：
  - 半径、存在时间、脉动间隔；
  - 落在地面上；
  - 脉动、牛走进领域、结束、跟随；
  - 旧粒子元素不进入新入口。

## 分工：公共核心 + 元素特色

和蓄力一样分两步：先由协调聊天做公共核心（`FieldCore` 及其辅助类，结构、时间与几何都在这里），各元素以样式接上；再由七个元素分支并行做各自的特色。领域的元素特色比蓄力更重，因为法阵线条的纹理、边界光幕和悬浮符文都要体现元素。

### 与蓄力区分开

蓄力是竖向、向内收拢，主角是杖头的能量球。领域是横向、贴地、向外铺开，主角是一块区域和它的边界；节奏是平稳存在，每次触发时脉动一下。法阵铺满领域的实际半径，不是施法者脚下的小阵。

## 参考与要求

维护者对每一层的要求：

1. **法阵本体**：多层法阵铺满整个半径。外圈最大、转得最慢，中层反转，内圈小而亮，还可以加一圈只有细线的边框。
   - **出现**：领域生成时，法阵快速从中心放大到实际大小。只是视觉效果，不影响判定。
   - **结束**：从外向内碎裂、熄灭。
2. **法阵线条的纹理要下功夫**：线条里要能看到元素的质感在流动。法阵贴图作为遮罩，用着色器在线条内部填充流动的元素纹理。
   - 火焰：线条里是流动的火。
   - 虚空：线条里是屏幕空间星空。
   - 其他元素各自设计。
3. **时钟**：在领域中心外加一圈同心圆，一个小法阵（或别的小物件）沿着它绕一周，身后留下发光的轨迹。绕满一周的时间正好等于领域的存在时间，即 `progress(pt)`。
4. **边界**：在半径处画一圈矮的、透明的光幕，向上渐隐，带有元素特色（纹理、颜色、流动方向等）。
5. **触发脉动**：每次 `on-tick`，一道光波从中心扫到边界，法阵线条依次亮起再暗下，边界光幕亮一下。
6. **有人进入**：进入者脚下泛起一个小涟漪。
7. **悬浮符文**：法阵中悬浮一些代表这种元素的符文。
8. **区域内的氛围**：可以稀疏地飘一些元素特有的东西（余烬、雪与低雾、星空、光点与叶片等），注意克制。
9. **染色**：复用蓄力的元素染色机制（`ChargeStyle` 的配色）。
10. **法阵图案**：复用现有法阵贴图（蓄力核心的 `charge/circle_*`，以及各元素蓄力已加的太阳纹、晶格、眼、星盘、六芒星）。也可以从 Unreal `/Game/MagicCircle/Texture` 的 29 张里（已导出到仓库外的 `art/charge/ue/exports/`，总览图 `art/charge/magic-circles.png`）为不同元素挑选合适的新图案，按蓄力核心法阵的同样处理方式加入。
11. **地面**：先平铺在 `ground()` 高度。坡地上会悬空或插进地里，这个问题以后再处理。
12. **第一人称**：站在领域里时，边界光幕和符文不能糊在镜头前；法阵是平铺在地上的，照常画。跟随施法者的领域要跟着走。
13. **性能**：领域可能很大（半径到 16 格）、持续很久（到 30 秒），同时可能有好几个。几何量和每帧的提交次数都要有上限，远处的领域可以精简。

## 每个元素可修改的文件

- 自身 `client/<Element>FieldVisual.java`，以及以 `<Element>Field` 开头的专属辅助类。
- 自身资源 `assets/logos/textures/vfx/<element>_field/` 和 `shaders/core/<element>_field_*`。
- 自身专属客户端场景 `client/uitest/<Element>FieldScenario.java`，继承 `FieldTour`，注册名 `logos_<element>_field`。

共享文件在元素分支保持只读，包括：
- 入口：`FieldVisual`、`FieldVisuals`、`LiveField`、`FieldPreview`、`FieldTour`；
- 公共核心及其资源；
- `LogosFx`、`Vfx`、`FieldObj`、网络包、`ShaderPackOverlay`；
- 蓄力相关类，以及其它元素的实现。

已有的贴图直接按路径引用，不要复制出重复的美术资源。公共入口不足时记录下来交回协调聊天。必须使用独立工作树。

## 验收

检查以下各项：
- 展开、稳定、脉动、有人进入、时钟过半与将满、结束；
- 小领域与大领域；
- 跟随的领域，以及在第一人称下站在领域里；
- 昼夜；
- 多个领域重叠。

按 CONTRIBUTING.md 运行测试。渲染改动按 SHADER_PACK_VFX.md 在光影下检查，没测就写“光影下未测”。资源与代码分别提交 PR。

公共场景：`./gradlew runClient -PldTest=logos_field --no-configuration-cache`。每个分支先做动态预览，并在工作区根目录提供 `预览<元素>领域.cmd`（运行 `logos_<element>_field`），维护者验收后再提交。
