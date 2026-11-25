# 贡献守则

## 主干与分支

`main` 受保护，禁止直推和强推。请从最新 `main` 切出短期分支，例如 `feat/room-search`、`fix/visit-time`、`docs/api-baseline` 或 `chore/build-tools`。通过 Pull Request 提交变更，等待 `contracts-web-java` 检查通过后以 Squash Merge 合入。合并后 GitHub 自动清理远端分支。

## 提交与评审

每个 PR 解决一个可以独立验证的问题。提交信息与 PR 标题使用 Conventional Commits 格式 `<type>(<scope>): <summary>`，范围可省略，如 `fix(housing): 修正房号筛选` 或 `docs: 更新验收基线`。类型使用 `feat`、`fix`、`docs`、`refactor`、`test`、`chore` 或 `ci`。在 PR 中关联 Issue，说明行为变化、验证方法与结果。

## 契约与迁移

三端对照的唯一接口契约是 `contracts/openapi.json`。在 `contracts/generate_openapi.py` 中维护定义，重新生成 OpenAPI、TypeScript 与 ArkTS 产物，并通过 API 1.1 向后兼容检查。[共享契约说明](contracts/API.md)

已提交的初始 `schema.sql` 与 V2 至 V8 历史迁移脚本不可修改，包括换行符。服务启动时会核对已执行迁移的 SHA-256。结构变更从 V9 起追加同版本的 openGauss 与 MySQL／H2 脚本。[架构与数据设计](docs/02-架构与数据设计.md)

## 提交前检查

在仓库根目录按 `.nvmrc` 选用 Node，首次运行 `npm ci --prefix web` 安装锁定依赖。后端需要 JDK 21 或以上版本，Maven 由仓库 Wrapper 提供。

```powershell
python scripts/check-contracts.py
python scripts/check-local-secrets.py
.\backend\mvnw.cmd -B -f backend/pom.xml verify
python scripts/format-java.py
python scripts/test-quality.py
python scripts/check-java.py
npm --prefix web test
npm --prefix web run build
npm --prefix web run format:check
node --test harmony/tests/*.test.mjs
```

Linux／macOS 上用 `./backend/mvnw -B -f backend/pom.xml verify` 运行同一后端检查。涉及交互的修改，在 PR 中记录相应浏览器、模拟器或真机的版本及复验结果。

## 工作区卫生

本机 `.env*` 文件中的连接信息、口令和令牌只留在开发机。仓库中的配置示例仅使用占位值。提交前检查暂存区，排除私有配置、`output/`、`.tools/`、构建缓存和个人 AI 协作指令。第三方资产沿用随附许可与署名。[安全策略](SECURITY.md) · [第三方资产声明](NOTICE)
