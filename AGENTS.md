# 仓库协作约定

- 本仓负责 Minecraft Java 版 26.2 的公共货币、实物报价、钱货结算、回执与恢复。剧情、货郎货单和生人掉落由各自的内容附属调用公共接口。
- 币种、整数比价、面额、堆叠上限与货郎可拆兑的组合由声明的目录提供。登记新币时同步提供物品模型、透明贴图和双语说明。
- Meridian 各附属按完整装配运行；不得添加缺少必需附属时的替代路径、降级或可选依赖判断。
- 正式 Fabric 模组在 `src/main/`。服务端是唯一结算方，玩家库存与钱匣的实物是余额。报价先试放，双方都能完成交付时才能发布流水。
- 重试用同一稳定操作号。改变写前日志、玩家水位或钱匣恢复逻辑时，须验证交割断点、双方实物守恒和回执去重。
- 简体中文沟通、文档与函数内意图注释；公开标识符用英文。代码按读取、校验、计算、请求、结果展开，命名、换行和注释密度与相邻代码保持一致。
- 文档唯一编辑源为 `docs/wiki/`，README 仅作入口。交付执行 Fabric GameTest、Xvfb 客户端截图测试、文档引用检查和 `git diff --check`。
- 未获授权不提交、推送或远端建仓。许可为 PolyForm Noncommercial 1.0.0，保留第三方许可。

## 构建和测试

构建、测试一律走工作区的 `kz`（`~/Code/Kizuna-remote-session/scripts/kz`），不直接运行 `./gradlew`。细则以 `~/Code/Kizuna-remote-session/handoff/codex-worker-rules.md` 为准，动手前先读。

- 单测、构建和服务端 GameTest：`kz test ~/Code/<本仓的 worktree>`
- 要带上别的仓的 worktree 一起测：每个加一次 `--with ~/Code/<那个仓的 worktree>`
- 客户端测试：`kz client ~/Code/<本仓的 worktree>` 列出有哪些项，`kz client ~/Code/<本仓的 worktree> <项>` 跑其中一项
- 要自己写 Gradle 参数时：`kz gradle ~/Code/<本仓的 worktree> -- <参数>`
- 不提交、不推送；在自己的 worktree（`~/Code/wt-*`）里改，不改主目录。
