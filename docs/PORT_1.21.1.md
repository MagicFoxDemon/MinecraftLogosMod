# 1.21.1 分支（`port/1.21.1`）

这个分支是 Logos 本体在 Minecraft 1.21.1 + NeoForge 21.1 上的版本，和 `main`（26.1.2）并行维护。
目标是让两边的玩法代码尽量一样，`main` 上的改动能以最少的修改搬过来。

## 依赖

| | 版本 |
|---|---|
| NeoForge | 21.1.267，Java 21 |
| LDLib2 / Photon / KilaGraph | 2.2.46.a / 2.2.11 / 21.1.0.16 |
| GeckoLib | 4.9.3（包名 `software.bernie.geckolib`） |
| Iris / JEI | 1.8.14-beta.1 / 19.70 |

## 替身层：`dev.logos.port` 与 `dev.logos.client.port`

1.21.1 缺的 26.1 API，在这两个包里各写一个同名的替身，玩法代码只改 import。
每个类的注释都写明它替的是 26.1 的哪个类。只写了 Logos 用到的方法。

- `dev.logos.port`（两端）：
  - 存档：`ValueInput`、`ValueOutput`、`TagValueInput`、`TagValueOutput`、`ProblemReporter`，`SavedDataType`。
    1.21.1 的 `saveAdditional(CompoundTag, Provider)` 等方法里先把 tag 包一层，方法体照旧。
  - NeoForge Transfer API：`ItemResource`、`FluidResource`、`ResourceHandler`、`Transaction`、`SnapshotJournal`、
    `ItemStacksResourceHandler`、`FluidStacksResourceHandler`、`CombinedResourceHandler`、`RangedResourceHandler`、
    `IndexModifier`、`ResourceHandlerSlot`。按接口重新实现，没有复制 NeoForge 的源码。
  - `Capabilities`：Logos 自己的物品 / 流体能力，`register` 时和 1.21.1 的 `IItemHandler` / `IFluidHandler` 互相桥接，
    所以漏斗、管道和别的 mod 照常能用。
  - 注册：`ItemRegister`、`BlockRegister`（`p -> p.stacksTo(1)` 这种属性函数）。
  - 配方：`ItemStackTemplate`、`SimpleRecipeSerializer`。
  - 其他：`ClientPayloads` + `RegisterClientPayloadHandlersEvent`（客户端包处理器），`DataTrades`（术士的数据驱动交易，
    读的是和 `main` 同一份 `villager_trade` / `trade_set` 文件），`PlayerInputs`（玩家按键），`StreamCodecs`（7、8 个字段的
    composite），`Persisted`（LDLib2 节点图存取），`ShapeRotations`，`RemovalSideEffects`，`DrinkItem`。
- `dev.logos.client.port`（仅客户端）：26.1 的渲染 API。
  - `SubmitNodeCollector`、`OrderedSubmitNodeCollector`：特效照旧 `submitCustomGeometry`，底层画进 1.21.1 的 buffer source。
    `SubmitCustomGeometryEvent` 由 `WorldGeometry` 在 `AFTER_PARTICLES` 阶段发出，按 order 和提交顺序画。
  - `RenderPipeline`、`RenderPipelines`（只有用到的三个 snippet）、`RenderSetup`、`RenderTypes.create(name, setup)`、
    `BlendFunction`、`DepthStencilState`、`ColorTargetState`、`CompareOp`、`CameraRenderState`、`LightCoordsUtil`。
  - `ShaderPrograms`：把管线编成 1.21.1 的 `ShaderInstance`。**shader 文件不用改**：26.1 的
    `minecraft:dynamictransforms.glsl` 等头文件只对 Logos 自己的程序按需生成，26.1 的雾值映射到 1.21.1 的单一雾；
    管线的 define 插在 `#version` 后面。snippet 自带的着色器在 `assets/logos/shaders/port/core/`。
  - `GuiTexture`、`DrawerHelperClient`：LDLib2 26.1 的写法。

`PackageRulesTest` 里 `port` 谁都能用，它不依赖 mod 的任何包。

## 从 `main` 搬改动时

1. 先照搬，再把 26.1 的 import 换成替身（`Identifier` → `ResourceLocation`、`org.jspecify` → `javax.annotation` 等），
   能编译就基本对了。
2. `@EventBusSubscriber` 在 1.21.1 默认挂游戏总线。**监听模组总线事件（注册、`FMLClientSetupEvent`、
   `RegisterGuiLayersEvent` 之类）要写 `bus = EventBusSubscriber.Bus.MOD`**；两种都有的类，把模组总线的方法放进嵌套类
   `ModBus`（见 `LogosClient`）。编译器查不出这个，漏了就是运行时静默不生效。
3. 配方 JSON：1.21.1 的原料要写成 `{"item": ...}` / `{"tag": ...}`，流体 `{"fluid": ...}`。
   标签里 1.21.1 没有的原版方块或物品（如 `minecraft:bush`）写成 `{"id": ..., "required": false}`，否则整个标签读不出来。
   世界内测试要模拟玩家时用 `gametest/MockPlayers.inLevel(h)`（原版的 `makeMockServerPlayerInLevel` 没有网络频道，
   同步附件时会抛异常）；26.1 的 `connection.markClientLoaded()` 写成 `MockPlayers.loaded(p)`（1.21.1 玩家进服后
   3 秒无敌）。测试结构四周有屏障墙：相对坐标不能小于 0（26.1 每个测试四周留 16 格）。
   战利品修改器（`loot_modifiers`）照 `main` 加文件即可：构建时 `generateLootModifierList` 生成 1.21.1 要的
   `neoforge:global_loot_modifiers` 名单。
4. 访问不到的字段和方法写进 `META-INF/accesstransformer.cfg`，注释写明替的是 26.1 的什么。
5. 渲染器：实体和方块实体的渲染器在这个分支是 1.21.1 的写法（`render(...)` 直接画），没有渲染状态。
   施法姿势用的人形渲染状态有替身（`client/port` 的 `HumanoidRenderState.of(entity, model)` 等），只给姿势代码用。
   效果代码要画物品、方块、实体时用替身的 `SubmitNodeCollector.submitDrawing`（26.1 是各自的 submit）。
6. GeckoLib：`main` 是 GeckoLib 5（渲染状态、DataTicket、BoneSnapshots），这里是 GeckoLib 4。
   改骨骼放在渲染器的 `renderRecursively` 或模型的 `setCustomAnimations`（动画之后）；要骨骼画在哪，
   `preRender` 里 `setTrackingMatrices(true)`，`renderFinal` 里用 `getLocalPosition()` 加上这一帧的实体位置。
   GeckoLib 4 的烘焙模型在同一资源的所有实例间共享，骨骼状态会留下：改了哪块骨骼，每次画都要重新设。
7. 渲染类型改名了：26.1 的 `entityCutout` 是 1.21.1 的 `entityCutoutNoCull`（两面都画），26.1 的 `entityCutoutCull`
   是 1.21.1 的 `entityCutout`。
8. 单元测试读编译好的类用 ASM（Java 21 没有 `java.lang.classfile`）。`ClientBoundaryTest` 的 `KNOWN` 多了
   `client/port` 里 LDLib2 替身的条目；`PackageRulesTest` 里 `mixin` 只用 `runtime`。

## 和 26.1 不同的地方

- 时间加速：1.21.1 没有世界时钟的速率，每刻直接推进时间并发给这个维度的玩家。
- 附身留下的身体：1.21.1 没有人体模型实体，用 `runtime/PossessionBody`（按主人 UUID 取皮肤），没有名字下面那行说明；
  背包没有鞍的格子（马的鞍在它携带的物品里）。身体画的是主人的皮肤，不是主人本身，所以玩家模型 mod 的外观不会套到它上面。
  它手里能拿东西、摆施法姿势（客户端场景里当 26.1 的人体模型用）。
- `neoforge.mods.toml` 开头要有 `modLoader` 和 `loaderVersion`（26.1 的 FML 不要了，1.21.1 的必须写）。
- 法杖蓄力时全速移动：`mixin/LocalPlayerMixin`（26.1 是物品的 use-effects 组件）。长杖充能时不重新举起：`StaffItem#shouldCauseReequipAnimation`。
- 饮品、魔力药剂、药粉：用食物属性加 `DrinkItem`（26.1 是 consumable 组件）。融合药水的名字放在物品名组件上。
  1.21.1 的食物只给玩家留下瓶子，`DrinkItem` 给任何喝的生物都留下。
- 炼成体换维度：1.21.1 复制成新实体再把旧的标记移除（`setRemoved` 是 final），旧的在 `restoreFrom` 里离开索引。
- 区域天气：玩法侧改 `Level#isRainingAt`（26.1 是 `precipitationAt`）。
- 骑乘末影龙：按键靠 `PlayerInputs` 从客户端发来。
- 水上行走：1.21.1 分不出放置方块的碰撞检查。
- 巨像格挡：盾牌被击飞固定 5 秒（1.21.1 只能开关，时长同斧头）。
- 贤者锻造模板：1.21.1 的模板自己建属性，注册时给的属性不生效。
- 世界内测试：用 `@GameTestGenerator` 注册，结构是 `data/logos/structure/empty.nbt`（32×16×40 的空气，用尺寸把相邻测试隔开），开着 sky access（否则屏障顶盖住整个结构，雨、阳光、落雷都没有）。
- 物品模型：`assets/logos/items/*.json` 和 `main` 保持同一份。构建时 `generateItemModels` 由它生成 1.21.1 的
  `models/item/<id>.json`（引用别的模型的写成 parent，组合模型写成 `neoforge:composite`），不进仓库；着色（`tints`）
  运行时由 `client/port/ItemTints` 读同一份文件；药水的分支由 `client/PotionBottles` 读 `items/potion.json`。
  新物品照 `main` 加 `items/` 文件即可，和物品同名的模型照旧放 `models/item/`。
- 物品表面特效、魔力缠绕：挂在 `ItemRenderer.render` 末尾（`mixin/ItemRendererMixin`），物品归谁由
  `EntityRenderDispatcherMixin` 和手持物品的 `renderStatic` 记下；画之前先把已经写进缓冲的物品画掉，特效才盖在物品上。
- 输入法：1.21.1 不会每刻关掉输入法，没有 `LdlibIme`；拼写中的文字由输入法自己的窗口显示。
- 区域天空的云高：取维度效果的云高（没有云的维度按 192.33）。云随时间加速：用日内时间代替世界时钟。
