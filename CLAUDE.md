# 交接文档：Logos（Minecraft 26.1.2 · NeoForge）

接手这个项目的人（或会话）先读这一页。玩法与设计的详细说明在 `README.md`，那是本地工作文档，**不进仓库**（已在 `.gitignore` 里）；面向玩家的 README 等发布时再写。

## 仓库

- 地址：https://github.com/MagicFoxDemon/MinecraftLogosMod ，目前是**私有**仓库。
- 主分支 `main`，远程名 `origin`。从现在开始所有改动都提交到这里。
- 提交前必须测试通过（见下文「测试」）。提交说明用英文，一句话说清改了什么。
- 不进仓库的东西：`build/`、`run/`、`.gradle/`、`README.md`（工作文档）、`素材/`（原图）、`net/`（反编译参考）。美术原图和 Blockbench 文件在工程外的 `D:\dev\LogosArt`。

### 凭据

- `GITHUB_USERNAME` 和 `GITHUB_TOKEN` 放在工程外的 `D:\dev\.env`。
- **不要**把 token 写进仓库、远程地址（`git remote`）、git 配置或任何日志输出里，也不要在对话里打印出来。
- 推送时临时读取，只对这一条命令生效，例如（Git Bash）：

```bash
set -a; . /d/dev/.env; set +a
git -c credential.helper= -c "http.https://github.com/.extraheader=AUTHORIZATION: basic $(printf '%s:%s' "$GITHUB_USERNAME" "$GITHUB_TOKEN" | base64 -w0)" push origin main
```

## 构建与测试

需要 JDK 25。

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
- `runtime/`：法术执行。`Interp` 求值，`Familiars` / `FamiliarEvents` / `DragonFamiliar` / `FamiliarRiding` 使魔，`Construct*` 炼成体，`Anchors` 锚点，`Filter` ⊙ 筛选。
- `graph/`：LDLib2 节点编辑器。新符文要在 `RuneNodes` 里加节点类并在 `BY_RUNE` 登记，名字写进 `assets/logos/lang/*.json` 的 `logos.rune.*`。
- `mixin/`：对原版的少量改动，登记在 `src/main/resources/logos.mixins.json`。
- `client/`：渲染、界面与客户端事件。

## 开发约定

- 26.1 用 Mojang 映射：是 `Identifier` 不是 `ResourceLocation`；很多类换了包（如 `projectile.arrow`），找不到时去 `build/moddev/artifacts/minecraft-patched-*.jar` 里查。
- 环境里没有 Python，脚本用 perl / sed / PowerShell。
- 测试的坑：
  - 模拟玩家要 `connection.markClientLoaded()` 才会受伤；和平难度下怪物对玩家的伤害是 0。
  - 需要实体 tick 的测试（生物移动、游离魔力、使魔行为）先调用 `FamiliarGameTests.forceAround(h, 1)` 强制加载周围区块，否则机器忙时实体可能整场测试都不 tick。
  - 等实体行为时用 `h.succeedWhen(...)` 轮询，不要用 `runAfterDelay(固定刻数)`：区块开始 tick 的时间不固定，固定等待会偶发失败。每个测试上限 400 刻。
