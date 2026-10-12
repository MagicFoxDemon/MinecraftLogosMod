# Logos — 给 AI 开发工具的入口

动手之前先完整阅读 [`CONTRIBUTING.md`](CONTRIBUTING.md)。协作规则、构建测试方法、代码地图、开发约定和美术规范都在那里，以它为准；本文件只是入口。

最重要的几条：

1. 开工先 `git fetch origin`，和 `origin/main` 比较，在最新的 `main` 上开分支。
2. 不能直接推送 `main`（它受保护），所有改动通过 Pull Request 合并。
3. 一个 PR 只改一个点，最小化修改，不顺手重构或格式化无关代码。
4. 提交前跑 `./gradlew test` 和 `./gradlew runGameTestServer`，如实报告结果。
5. 绝不读取、打印或提交 token、密码、`.env` 等凭据。

## 元素魔弹并行制作

制作单个元素的魔弹飞行特效时，遵守 [PROJECTILE_VFX.md](PROJECTILE_VFX.md) 的文件边界：只修改该元素的实现、专属资源和专属测试。公共入口、参数替换逻辑、其它元素和命中效果不属于元素分支的修改范围。公共修改集中在主协调分支完成；不得顺带在元素分支里修改。

制作魔弹命中特效时，遵守 [PROJECTILE_IMPACT_VFX.md](PROJECTILE_IMPACT_VFX.md)。公共碰撞入口统一后，元素分支只修改自身命中实现、专属资源和专属客户端测试；飞行外观保持只读。
