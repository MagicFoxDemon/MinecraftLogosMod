# 交接文档：Logos（Minecraft 26.1.2 · NeoForge）

接手这个项目的人（或会话）先读这一页。玩法与设计的详细说明在 `README.md`，那是本地工作文档，**不进仓库**（已在 `.gitignore` 里）；面向玩家的 README 等发布时再写。

## 仓库

- 地址：https://github.com/MagicFoxDemon/MinecraftLogosMod ，目前是**私有**仓库。
- 主分支 `main`，远程名 `origin`。从现在开始所有改动都提交到这里。
- 提交前必须测试通过（见下文「测试」）。提交说明用英文，一句话说清改了什么。
- 不进仓库的东西：`build/`、`run/`、`.gradle/`、`README.md`（工作文档）、`素材/`（原图）、根目录的 `/net/`（反编译参考）。美术原图和 Blockbench 文件在工程外的 `D:\dev\LogosArt`。
- `.gitignore` 里忽略文件夹时要加前导 `/`，锚定在根目录。曾经写成 `net/`，结果把源码包 `dev/logos/net`（全部网络包）也忽略了，仓库因此编译不过。提交前用 `git status --ignored --short src` 确认源码没有被忽略。

### 凭据

- `GITHUB_USERNAME` 和 `GITHUB_TOKEN` 放在工程外的 `D:\dev\.env`。
- **不要**把 token 写进仓库、远程地址（`git remote`）、git 配置或任何日志输出里，也不要在对话里打印出来。
- 推送时临时读取，只对这一条命令生效，例如（Git Bash）：

```bash
set -a; . /d/dev/.env; set +a
git -c credential.helper= -c "http.https://github.com/.extraheader=AUTHORIZATION: basic $(printf '%s:%s' "$GITHUB_USERNAME" "$GITHUB_TOKEN" | base64 -w0)" push origin main
```

## 构建与测试

需要 JDK 25。依赖：
- LDLib2、Photon、KilaGraph：来自 `maven.firstdark.dev`；
- GeckoLib 5.5.2：从 Modrinth 的 Maven 拉取，坐标 `maven.modrinth:geckolib:<版本 id>`，版本 id 在 `gradle.properties` 的 `geckolib_version`。

它们都是玩家必装的前置，写在 `neoforge.mods.toml` 里。

| 命令 | 内容 |
| --- | --- |
| `./gradlew test` | 编译器单元测试 |
| `./gradlew runGameTestServer` | 世界内测试（GameTest），日志里应出现 `All N required tests passed` |
| `./gradlew runClient -PldTest=group:logos -PldTestHeadless` | 真实客户端的无窗口 UI 测试，截图在 `build/ldlib2-uitest/screenshots/` |
| `./gradlew build` | 打包，jar 在 `build/libs/logos-<版本>.jar` |

- 版本号在 `gradle.properties` 的 `mod_version`，每做完一轮可试玩的改动就加一。
- 新的世界内测试要同时在 `gametest/LogosGameTests.java` 的名单和 `FUNCTIONS.register` 里登记。

## 代码地图（`src/main/java/dev/logos`）

- `lang/`：语言本身。`Type` 类型、`Compiler` 解析与类型检查、`Runes` 全部符文定义、`Examples` 示例术式。
  - `SpellProfile` 是术式的「意图档案」：从语法树读出意图（`Intent`）、作用距离和一次施放的魔力估算，不需要运行术式。编辑器侧栏的总览和魔力生命体的心智都用它。
  - 新增会花魔力的符文时，要在 `SpellProfile.Reader.cost` 里补上它的价格和意图，否则总览会按 0 估算。
- `runtime/`：法术执行。`Interp` 求值，`Familiars` / `FamiliarEvents` / `DragonFamiliar` / `FamiliarRiding` 使魔，`Construct*` 炼成体，`Anchors` 锚点，`Filter` ⊙ 筛选。
- `graph/`：LDLib2 节点编辑器。新符文要在 `RuneNodes` 里加节点类并在 `BY_RUNE` 登记，名字写进 `assets/logos/lang/*.json` 的 `logos.rune.*`。
- `mixin/`：对原版的少量改动，登记在 `src/main/resources/logos.mixins.json`。
- `client/`：渲染、界面与客户端事件。`client/uitest/` 是无窗口客户端场景（截图检查），例如 `LifeformScenario`。
- 魔力生命体：
  - `runtime/ManaLifeform`：实体、心核（主动 / 被动 / 炼金道具三组槽位，按等级开放）、连携规则、GeckoLib 动画控制器；
  - `runtime/LifeformMind`：心智（效用 AI）。每个行为是一个内部类，负责打分、开始、每刻执行和结束；悬浮移动也在这里。加新行为只需加一个类，并放进构造器里的列表。`/logos mind` 显示各行为的分数；
  - `runtime/LifeformAugment`：炼金道具接口（道具还没有做），魔力生命体和巨像共用；
- 炼金术巨像：
  - `runtime/AlchemicalColossus`：实体（魔力的显示值与真实值、暴走与平息、座位和手、动作 `Act` 的同步、动画控制器）；
  - `runtime/ColossusMind`：效用 AI 加上按动画计时的动作序列（抓取、挥拳、掌心炮、上肩下肩、献花、懊悔），以及乘客摆放位置 `holdPoint`；
  - `runtime/ColossusCannon`：掌心炮的伤害表与阻挡判定；
  - `runtime/Colossi`：召唤、上下马事件、驾驶控制、隐藏进度、炼金道具面板；
  - `client/ColossusRenderer`、`client/ColossusInput`。
- `KeptFamiliar` 接口：魔力生命体和巨像这类「造出来的使魔」，会随世界保存、有自己的心智，使魔系统只负责传达命令。
  - `runtime/Lifeforms`：召唤规则、每人上限的存档登记、心核界面、被动触发；
  - `client/ManaLifeformRenderer`：渲染。
  - 模型与动画在 `assets/logos/geckolib/{models,animations}/entity/`，原始 bbmodel 在 `D:\dev\LogosArt\mana_lifeform.zip`。补魔物品是数据映射 `data/logos/data_maps/item/mana_fuel.json`。

## 开发约定

- 26.1 用 Mojang 映射：是 `Identifier` 不是 `ResourceLocation`；很多类换了包（如 `projectile.arrow`），找不到时去 `build/moddev/artifacts/minecraft-patched-*.jar` 里查。
- 环境里没有 Python，脚本用 perl / sed / PowerShell。
  - **perl 替换的分隔符不要用 `|`**：Java 代码里到处是 `||`，写成 `s|...|...|` 时，模式里的 `\|` 会被当成分隔符或「或」运算，把文件插得面目全非（已经出过两次事）。多行改动直接用编辑工具；非用 perl 不可时，用 `~` 作为分隔符或 `index`/`substr` 替换，并先 `git diff --stat` 检查改动行数。
  - **PowerShell 的哈希表不区分大小写**：`r` 和 `R` 会被当成同一个键。
- GeckoLib 5：
  - 骨骼位置回调的参数顺序是（世界坐标，模型坐标，相对实体的坐标）。
  - 想让乘客正好待在某块骨骼上：渲染器每帧上报骨骼的世界坐标，客户端摆放乘客时用它；服务端用按动作进度估算的位置（见 `ColossusMind.holdPoint`）。
- 原版会把「第一个是生物的乘客」当作坐骑的控制者（骷髅骑蜘蛛就是这样），而这会关掉坐骑自己的移动目标。会抱着生物的实体（巨像）要重写 `getControllingPassenger`。
- 客户端场景测试的坑：
  - 场景世界默认是和平难度，敌对生物一生成就被删掉，需要先 `/difficulty normal`；
  - 单个场景跑到大约第 410 个客户端刻时，测试工具会卡住不再推进，所以长场景要拆成几个短的（巨像拆成了 3 个）。
- 测试的坑：
  - 模拟玩家要 `connection.markClientLoaded()` 才会受伤；和平难度下怪物对玩家的伤害是 0。
  - 需要实体 tick 的测试（生物移动、游离魔力、使魔行为）先调用 `FamiliarGameTests.forceAround(h, 1)` 强制加载周围区块，否则机器忙时实体可能整场测试都不 tick。
  - GeckoLib 的触发式动画（`triggerAnim`）播完后不会自己把控制器交还给状态处理器，必须调用 `stopTriggeredAnim`，否则停机、苏醒这类状态动画永远不会播放。
  - 等实体行为时用 `h.succeedWhen(...)` 轮询，不要用 `runAfterDelay(固定刻数)`：区块开始 tick 的时间不固定，固定等待会偶发失败。每个测试上限 400 刻。
