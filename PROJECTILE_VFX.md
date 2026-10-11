# 元素魔弹的独立制作边界

本说明只约束魔弹飞行 VFX 的分工，协作与提交规则仍以 CONTRIBUTING.md 为准。

## 一个任务只负责一个元素

| 元素 | 实现文件（src/main/java/dev/logos/client/） | 专属资源名 |
| --- | --- | --- |
| 火焰 | FireProjectileVisual.java | fire |
| 寒霜 | FrostProjectileVisual.java | frost |
| 雷霆 | LightningProjectileVisual.java | lightning |
| 虚空 | VoidProjectileVisual.java | void |
| 物理 | PhysicalProjectileVisual.java | physical |
| 奥秘 | ArcaneProjectileVisual.java | arcane |
| 生命 | LifeProjectileVisual.java | life |

每个元素分支只允许修改：

- 表中的自身实现文件；包含弹头几何、动画、拖尾、Photon 发射器、颜色和视觉缩放。
- 自身资源：`assets/logos/textures/vfx/<元素资源名>/`，以及 `assets/logos/shaders/core/<元素资源名>_*.fsh` / `.vsh`。不要覆盖其它元素贴图，也不要共用可变的元素材质文件。
- 自身新增的专属实现辅助文件，以对应英文元素名开头；不放公共逻辑。
- 自身专属测试，例如 `client/uitest/LightningProjectileScenario.java`、`src/test/java/dev/logos/client/LightningProjectileVisualTest.java`。新元素不要编辑 FrostProjectileScenario 来复用测试入口，而应新建自己的场景并使用不同注册名。

共享文件在元素任务中保持只读：`LogosFx.java`、`ProjectileVisual.java`、`ProjectileParticles.java`、`LegacyProjectileVisual.java`、`ProjectileMotion.java`、`ProjectilePath.java`、`Element.java`、网络包、符文定义、公共调色板、构建和版本配置。共享功能的修改单独回到协调分支完成，再让各任务同步。

所有七个元素已经在 ProjectileVisual.create 中注册。雷、虚空、物理、奥秘、生命暂时继承 LegacyProjectileVisual，效果与原版一致。升级某个元素时可以只在自身文件里改为实现 ProjectileVisual，保持 `(Vec3 position, double now)` 构造器即可，不需要改注册表或 LogosFx。

## 必须保留的接口语义

- `move` 更新自身连续飞行状态；`submit` 根据传入的 `head`、`tail` 分别绘制两部分，不能在 `head=false` 时绘制默认弹头，也不能在 `tail=false` 时绘制默认拖尾。
- `particles(Motion)` 只返回默认拖尾的 Photon 层，不得把默认弹头塞进这些粒子层。玩家只指定 look 时默认拖尾仍会启用，混入弹头会违反替换约定。
- `particleScale(size, customLook)` 与自身几何的缩放保持一致。玩家的 look 模型、spin 与 trail 粒子仍由公共入口处理。
- `legacyAccents()` 默认为 false；新的独立特效通常不需要额外混入原版点状粒子。
- 命中效果不属于本轮飞行特效任务；TRAIL_END、伤害、碰撞、速度和寿命逻辑保持不变。

验收至少覆盖默认、仅 look、仅 trail、两者同时指定四种组合，以及直线、转弯、昼间、夜间。按 CONTRIBUTING.md 运行测试，并在提交前核对分支相对公共基线的全部改动文件都在自身范围内。美术源文件、素材检查脚本和本地预览入口放在仓库外。

## 并行与合并顺序

1. 先提交并统一当前公共基线，包含已经验收的半格冰晶和本次按元素拆分。不要让多个任务分别再次完成这次拆分。
2. 每个元素使用独立分支和独立 Git 工作树；仅分开聊天、但仍指向同一个工作目录，不能隔离本地文件、分支切换或构建输出。
3. 所有任务从同一个包含公共基线的提交开始。基线未提交时，普通新工作树不会自动带上这些未提交的文件。
4. 任务指令明确一个元素和上述文件范围；测试和截图保存在各自工作树，仓库外导出也使用各自元素目录。
5. 每次只合并一个元素，并检查公共入口和其它元素没有变化。需要代码与资源同时接入时，仍按 CONTRIBUTING.md 分成资源 PR 与代码 PR。

建议接下来制作雷霆与虚空魔弹，可以并行；如果希望先完成玩家能直接指定的伤害元素，再制作物理。奥秘与生命是推断出的视觉元素，可以随后处理。
