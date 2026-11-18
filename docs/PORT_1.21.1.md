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
4. 访问不到的字段和方法写进 `META-INF/accesstransformer.cfg`，注释写明替的是 26.1 的什么。

## 和 26.1 不同的地方

- 时间加速：1.21.1 没有世界时钟的速率，每刻直接推进时间并发给这个维度的玩家。
- 附身留下的身体：1.21.1 没有人体模型实体，用 `runtime/PossessionBody`（按主人 UUID 取皮肤），没有名字下面那行说明；
  背包没有鞍的格子（马的鞍在它携带的物品里）。
- 法杖蓄力时全速移动：`mixin/LocalPlayerMixin`（26.1 是物品的 use-effects 组件）。长杖充能时不重新举起：`StaffItem#shouldCauseReequipAnimation`。
- 饮品、魔力药剂、药粉：用食物属性加 `DrinkItem`（26.1 是 consumable 组件）。融合药水的名字放在物品名组件上。
- 区域天气：玩法侧改 `Level#isRainingAt`（26.1 是 `precipitationAt`）。
- 骑乘末影龙：按键靠 `PlayerInputs` 从客户端发来。
- 水上行走：1.21.1 分不出放置方块的碰撞检查。
- 巨像格挡：盾牌被击飞固定 5 秒（1.21.1 只能开关，时长同斧头）。
- 贤者锻造模板：1.21.1 的模板自己建属性，注册时给的属性不生效。
- 世界内测试：用 `@GameTestGenerator` 注册，结构是 `data/logos/structure/empty.nbt`（32×16×40 的空气，用尺寸把相邻测试隔开）。
