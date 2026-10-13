# 光影包（Iris）下的特效

Iris + Sodium 几乎是玩家必装的模组。本模组的特效在不开光影时正常，开了光影曾经先崩溃、后看不见。本文记录原因、现在的做法，以及替换特效时必须遵守的规则和验收方法。协作与提交规则仍以 CONTRIBUTING.md 为准。

## 一句话结论

- 自己写的几何（自定义 `RenderType`、自定义着色器）：**每个顶点写全所有元素（含法线）**，并且**通过 `ShaderPackOverlay.collector(...)` 提交**，由它在光影合成之后用我们自己的着色器叠加上去。
- **不要**把管线登记给 Iris 的程序（`IrisApi.assignPipeline`），也不要去猜用哪个程序。
- Photon 粒子**用 Photon 自带的材质**（`TextureMaterial`、`SpriteMaterial`、`CustomShaderMaterial` 等）：Photon 自己处理光影，不需要也不要走我们的叠加。**不要让材质返回自己构造的 `RenderType`**，那样会绕过 Photon 的光影处理，开光影时看不见。

## 开了光影以后发生了什么

1. **顶点格式被换掉。** Iris 会把我们的顶点格式换成它的扩展格式，其中多了法线（Normal）等元素。原版 `BLOCK` 格式（信标光束片段用的）没有法线，所以以前从不设法线也没事；开了光影后，缺任何一个元素都会在施法时直接崩溃：`IllegalStateException: Missing elements in vertex: Normal`（#102）。
2. **自定义管线没有对应的程序。** Iris 按管线挑选光影包里的程序。它不认识我们的管线，日志会出现 `Missing program logos:pipeline/... in override list`，然后仍用我们的着色器画进光影的中间缓冲，结果被光影后面的合成步骤盖掉——法球和拖尾几乎看不见。
3. **登记程序也不行。** 曾经试过把管线登记给 `BEACON_BEAM`、`TEXTURED`、`PARTICLES_TRANSLUCENT`、`EMISSIVE_ENTITIES`、`ENTITIES_TRANSLUCENT`。程序会**替换掉我们自己的着色器**（遮罩、加法发光全部丢失），而且效果完全取决于光影包：Complementary 下拖尾变成暗色方盒；photon 光影下五种程序都看不到法球，因为它的 `gbuffers_beaconbeam` 是延迟光照的不透明通道，而我们的发光不写深度，被当成天空重新画了一遍。

## 现在的做法：合成后叠加（#106）

- `LogosFx.submit` 每帧先调用 `ShaderPackOverlay.begin()`，再把 `ShaderPackOverlay.collector(event.getSubmitNodeCollector())` 交给魔弹飞行外观。
- 没有 Iris、或装了 Iris 但没开光影时，`collector` 原样返回真正的收集器，什么都不变。
- 开了光影时，它返回一个只把 `submitCustomGeometry` 记下来的收集器。`IrisRenderingPipelineMixin`（`@Pseudo`，目标 `IrisRenderingPipeline.finalizeLevelRendering` 的末尾）在光影合成完、原版为手持物清除深度之前调用 `ShaderPackOverlay.draw()`，用我们自己的 `RenderType` 画上去。
- 所以：外观和不开光影时一致；方块仍会挡住特效；手持物仍画在特效前面。
- 代价：不吃光影的光照、雾和泛光，也不投影。
- Iris 只是编译期依赖（`compileOnly`，版本 id 在 `gradle.properties` 的 `iris_version`），不打包、不要求玩家安装。Iris 的类只能在 `ShaderPackOverlay` 的内部类 `Bridge` 里引用，没装 Iris 时这个类不会被加载。

## 替换特效时的规则

1. **顶点写全。** 每个顶点依次写位置、颜色、UV、光照和法线，例如 `.setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0)`。格式里没有的元素，原版会直接忽略，多写不会出错；少写在光影下会崩溃。用实体类渲染类型时还要写 `setOverlay(OverlayTexture.NO_OVERLAY)`。
2. **自定义几何走叠加收集器。** 魔弹飞行外观的 `submit(...)` 拿到的 `out` 已经是叠加收集器，元素分支照常调用 `out.submitCustomGeometry(...)` 即可，不需要碰 Iris。新增别的特效入口（不是魔弹飞行）时，在公共入口里同样用 `ShaderPackOverlay.collector(...)` 包一层；这属于公共改动，单独提 PR。
3. **只有 `submitCustomGeometry` 会被叠加。** 通过同一个收集器提交的物品模型、方块等其他内容照常交给光影处理。
4. **不要做的事：** 调用 `IrisApi.assignPipeline`；为了「看起来更亮」改颜色或混合方式去迁就某一个光影包；在元素分支里直接引用 Iris 的类。
5. **Photon 效果（命中特效、拖尾粒子层）：** Photon 26.1 自带一套 Iris 兼容（`com.lowdragmc.photon.client.compat.iris`）：它会识别光影包的类型，必要时把整层特效放到光影合成之后再叠加（配置 `photon-client.toml` 的 `iris_composite_mode`，默认 `AUTO`；游戏内可用 `/photon_iris` 查看状态，日志里有 `[Iris compat]` 提示）。实测 photon 光影下 Photon 自己的层都可见，只是没有泛光、在明亮的天空前对比度低一些。
   - **会出问题的是自己构造 `RenderType` 的材质。** 物理、虚空命中特效的材质曾经重写 `getRenderType` 返回自己的管线，绕过了 Photon 的光影处理：两个光影包下物理命中的螺旋、虚空命中的星空核心都看不见，而同一次命中里 Photon 自己的层正常。
   - **自定义着色器的做法**（参照 `PhysicalProjectileImpactMaterial`、`VoidProjectileImpactMaterial`）：材质继承 `CustomShaderMaterial`，构造时传入着色器 id；在 `shaders/core/<名字>.json` 写 `"vertex": "photon:particle"`、`"fragment": "logos:<名字>"` 和 `samplers` 列表；只写片元着色器，输入是 `texCoord0`、`vertexColor`、`sphericalVertexDistance`、`cylindricalVertexDistance`，可以用 `GameTime`、`ScreenSize`（导入 `globals.glsl`）。采样器名**不能用** `Sampler0`–`Sampler2`（Photon 自己占用），贴图用 `PhotonShaderTextures.bind(...)` 绑定。混合方式、剔除、深度由发射器的 `MaterialSetting` 决定，不在材质里写死。
   - Photon 的 `photon:pipeline/hdr_particle_*`、`bloom_*` 在日志里报 `Missing program` 是 Photon 自己的事，它的兼容层会处理，不要替它登记程序。
   - Photon 本身有问题时，向上游提 issue 或 PR（许可证 CC BY-NC-SA 4.0；移植到 1.21.x 以外版本的公开发布需原作者书面同意），不要自己 fork 一份随模组发布：模组 id 冲突、其他依赖 Photon 的模组会出问题，也要自己追上游更新。
6. **原版粒子**（`level.addParticle` 之类）由光影包正常处理，开光影时可见。

## 在开发环境里测光影

不开光影的测试通过，不代表开光影没问题。改动渲染时，至少用两个光影包各跑一遍对应的客户端场景：

1. 从 Modrinth 下载与 `gradle.properties` 里 `iris_version` 对应的 Iris，以及它要求的 Sodium，放进 `run/mods/`。
2. 光影包放进 `run/shaderpacks/`。至少选两种：一个**延迟光照**类（如 photon，最容易把特效当天空盖掉），一个常见的（如 Complementary Reimagined）。
3. 写 `run/config/iris.properties`，指定 `enableShaders=true` 和 `shaderPack=<文件名>`；改成 `enableShaders=false` 就能测「装了 Iris 但没开光影」。
4. 跑 `./gradlew runClient -PldTest=<场景名> -PldTestHeadless`，截图在 `build/ldlib2-uitest/screenshots/`。无窗口客户端同样会加载 Iris 和光影。
5. 检查：
   - 日志里没有 `Missing elements in vertex`；
   - 特效在两个光影包下都可见，外观和不开光影时一致；
   - 方块能挡住特效（可临时写一个在飞行路线前放石柱的场景，验证后删掉，不要提交）；
   - 拿掉 Iris 再跑一遍，确认没装 Iris 时照常工作（日志里那条 `Error loading class: net/irisshaders/iris/pipeline/IrisRenderingPipeline` 是 `@Pseudo` 的正常提示）。
6. **测完把 Iris 和 Sodium 移出 `run/mods/`。** 同一个工作目录的所有客户端测试都会加载 `run/mods/` 里的模组，留着会让别人的测试也在光影下跑。`run/` 不进仓库，光影包和模组文件都不要提交。

在 PR 的「怎么测的」里写明用了哪个光影包、哪些场景。没在光影下测过就写「光影下未测」，不能写「已验证」。

## 尚未验证

- 叠加的特效在水、玻璃等半透明方块后面时的遮挡与混色。
- 光束、炼成体、锚点标记、游离魔力用的是原版渲染类型（`beaconBeam`、`eyes` 等），没有走叠加，也还没在光影下逐一验收。
