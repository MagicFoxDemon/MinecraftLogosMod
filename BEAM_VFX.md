# 持续光束（beam）特效的独立制作边界

本说明只约束持续光束 `beam` 的特效分工，协作与提交规则仍以 CONTRIBUTING.md 为准，渲染必须遵守 SHADER_PACK_VFX.md。光束和射线（RAY_VFX.md）是两回事：光束持续存在、每刻由服务端重新报告、跟随视线；射线是瞬间的一条线。

## 公共入口

- 服务端每刻发送 `BEAM_HOLD`（from、to、半径、锚定实体），结束时发送 `BEAM_END`；超过 10 刻没有报告也视为结束。这部分没有改动，判定不变。
- 五个伤害元素各有 `<Element>BeamVisual`（`FireBeamVisual`、`FrostBeamVisual`、`LightningBeamVisual`、`VoidBeamVisual`、`PhysicalBeamVisual`，以及新注册的 `ArcaneBeamVisual`、`LifeBeamVisual`），由 `LogosFx.submitBeam` 每帧调用 `submit(level, from, to, radius, camera, pose, out)`，结束时调用 `end()`。`out` 已经过 `ShaderPackOverlay`。奥秘和生命的 `legacy()` 目前返回 true，`LogosFx` 继续画信标式的通用管子；它们的分支把 `legacy()` 改为 false 并实现绘制。
- **伸出（公共，所有元素自动生效）**：光束出现时，终点从施法点以 `LogosFx.BEAM_REACH`（每刻 4 格，64 格约 0.8 秒）的速度向前伸到全长，之后保持。只改画面，服务端的判定与伤害不变。各元素的 `submit` 收到的 `to` 就是伸出中的终点，命中处的效果应跟着终点走，未到达全长前不要在真正的终点画命中。
- **余迹（各元素自己做）**：光束结束（`BEAM_END` 或超时）时，`LogosFx` 调用 `BeamWake.create(元素)` 得到 `<Element>BeamWake`，以最后画出的那条线调用 `start(level, from, to, RayEnd.OPEN)`，之后按射线的生命周期每刻 `tick`、每帧 `submit`、最长 200 刻（见 RAY_VFX.md）。`<Element>BeamWake` 是一个 `RayVisual`，默认 `legacy()` 为 true，表示不留余迹、和以前一样直接消失。带 `:trail` 粒子的光束不留余迹。
- `LogosFx.clearRays()` 结束所有正在显示的射线与余迹，供客户端场景每次重新采样；`LogosFx.drawnBeamLengths()` 返回每条光束当前画出的长度。

## 每个元素可修改的文件

- 自身 `client/<Element>BeamVisual.java`、`client/<Element>BeamWake.java`，以及以 `<Element>Beam` 开头的专属辅助类（如 `FireBeamCore`）。
- 自身资源 `assets/logos/textures/vfx/<element>_beam/` 和 `shaders/core/<element>_beam_*`。
- 自身专属客户端场景（已有的 `<Element>BeamScenario`、`<Element>BeamCasterScenario` 等，或新建 `<Element>BeamWakeScenario`），注册名以 `logos_<element>_beam` 开头。
- 余迹可以复用本元素射线的效果：允许为此修改本元素的 `<Element>RayVisual`（例如抽出只画余迹的模式），但射线本身的外观和时长不能改变。

共享文件在元素分支保持只读：`LogosFx`、`BeamWake`、`RayVisual`、`Vfx`、`Runes`、`BeamObj`、网络包、`ShaderPackOverlay`、其它元素的实现。公共入口不足时记录下来交回协调聊天。必须使用独立工作树。

## 参考与要求

参考包：`Piloto Studio/Elemental VFX Mega Bundle` 的 `<元素>Ray` 系列。**命名和我们相反**：它的 Ray 是持续光束，对应我们的 beam；它的 Beam（`PS_UltimateBeam_*`）是瞬间发射，对应我们的 ray。拆解方法同之前。

| 元素 | 参考 prefab | 本轮要求 |
| --- | --- | --- |
| 火焰 | `Fire/FireRay.prefab`（另有 `Fire/MagmaRay.prefab`） | 大幅修改甚至重做：现在像一根水管，没有质感。加余迹（参考火焰射线的烟带） |
| 寒霜 | `Frost/FrostRay.prefab` | 同上，大幅修改甚至重做。加余迹 |
| 雷霆 | `Shock/ShockBlueRay.prefab` | 光束本身维护者很满意，**不要改**；只加余迹（参考雷霆射线的电离通道） |
| 虚空 | `Void/VoidRay.prefab` | 杖尖的球太大、挡视野：缩小球，并让光束的每一层都更紧凑。余迹复用虚空射线的逐渐变窄直线 |
| 奥秘 | `Arcane/ArcaneRay.prefab` | 新做：目前只是通用管子。加余迹（参考奥秘射线浮起熄灭的符文） |
| 生命 | `Nature/NatureRay.prefab` | 新做：目前只是通用管子。加余迹（参考生命射线的落叶与光点） |
| 物理 | （物理不按元素 prefab）可参照物理射线 #167、#168 | 优化：十字爆闪现在晚于光束出现，要和光束同时；气浪等元素参考物理射线的音爆锥、压力环。加余迹（参考物理射线断续闪烁的细线） |

余迹要和修改后的光束外观匹配，缓慢消失（维护者对射线余迹的要求是 5–7 秒左右）。第一人称时杖头附近不能遮挡视野。颜色沿用该元素魔弹、命中、射线的调色。

## 验收

检查出现时的伸出、持续中、结束后的余迹与完全消失；第一人称（不遮挡视野）、第三人称、侧面全长；撞墙与命中生物；昼夜；短光束与长光束。阶段截图每次重新施放并先 `LogosFx.clearRays()` 再采样。按 CONTRIBUTING.md 运行测试，渲染改动按 SHADER_PACK_VFX.md 在光影下检查（没测就写“光影下未测”）；资源与代码分别提交 PR。公共场景：`./gradlew runClient -PldTest=logos_beam_reach --no-configuration-cache`。每个分支先做动态预览并在工作区根目录提供 `预览<元素>光束.cmd`。
