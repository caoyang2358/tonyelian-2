# 三人怎样共同开发

## 先理解四个词

| 词 | 在本项目中的意思 |
| --- | --- |
| 本地工程 | 每个人电脑上的 `phase2`，在这里编辑和运行 |
| GitHub仓库 | [tonyelian-2](https://github.com/caoyang2358/tonyelian-2)，按负责人决定公开；任何人可查看，只有获准协作者可推送 |
| 任务分支 | 从当前共同版本分出本任务的改动，其他人的任务各有分支 |
| Pull Request，简称PR | 请求把本任务改动合回共同版本，供另一个人检查 |

`main`作为三人共同维护的已集成版本；每项任务单独建分支。三个人各自运行自己的工程，借Git同步改动。

## 负责人首次准备

1. 仓库已指定为 `https://github.com/caoyang2358/tonyelian-2.git`；负责人已确认保持公开，二期源码和需求文档均纳入。
2. 若准备推送本地仓库，远程先建空仓库，不勾选自动生成README等内容，避免首轮出现两套独立历史。
3. 在仓库设置中邀请另外两人的GitHub账号为协作者，等待其接受。成员账号本次尚未收集，也未发送邀请。
4. 在本地 `phase2` 设置本仓库使用的提交姓名、邮箱，完成首个提交，再绑定实际远程地址并推送。

从 `phase2` 目录运行，先将示例值换成真实信息：

```powershell
git config user.name "你的提交姓名"
git config user.email "你的GitHub提交邮箱"
git add .
git commit -m "chore: initialize phase2 workspace"
git remote add origin https://github.com/caoyang2358/tonyelian-2.git
git push -u origin main
```

目录已做本地初始化时无需再次 `git init`。以上命令供负责人首次配置参考，已经绑定origin时不要重复添加；队员直接使用下方克隆命令。首次推送如需登录，使用GitHub支持的登录方式，认证由账号本人完成。

另两名成员接受邀请后，在自己的电脑运行：

```powershell
git clone https://github.com/caoyang2358/tonyelian-2.git
cd tonyelian-2
```

目录名称可以不同，内容来自同一个仓库。每人可用自己熟悉的编辑器和AI工具。

## 每项任务都按这条路走

开始任务前，先保存手头工作，确保工作区没有未处理改动；随后同步主分支并建立新任务分支：

```powershell
git switch main
git pull --ff-only
git switch -c feat/INV-001-plate-stock
```

在自己的分支实现页面、接口和数据变更，运行相关检查，确认 `git diff` 和 `git status` 中只有本任务文件，再提交并推送：

```powershell
git add 明确的文件路径
git commit -m "feat: add plate stock registration"
git push -u origin feat/INV-001-plate-stock
```

随后在GitHub为该分支创建PR，使用仓库模板说明目标、改动和验证结果。另一名成员检查，通过相关构建/业务验证后，由集成负责人合入main。其他人再同步main开始后续任务。首次首个提交可 `git add .`；日常提交尽量明确要加入的文件。

分支名按任务编号，而不是长期固定为A/B/C三条互不合并的分支。共享代码改动与对应负责人对齐；出现冲突时由相关成员核对后解决，不随意覆盖另一人的内容。

## 数据库怎样协作

- Git共享建表和迁移脚本，开发数据库中每人的测试记录不自动同步。
- 每人使用自己的本地或专属开发库；共同测试库用于验证合并后的完整工程。
- 所有表结构改动进入迁移文件并随PR审查；不只在个人数据库里手工改表。
- 迁移编号及共用字段由一人协调，已执行脚本通过后续迁移修正。
- 每个人用同一组演示种子数据，便于复现数量和成本案例；不得将现用数据库转储当作仓库内容。

## 日常安排

每天简短说明本任务进展、依赖及接口变化。至少每两天集成一批可运行改动。每个功能完成须能操作页面、写入开发数据库、刷新查回结果，相关异常得到预期结果并有另一人审查。

说明依据：[GitHub创建仓库](https://docs.github.com/en/repositories/creating-and-managing-repositories/creating-a-new-repository)、[邀请协作者](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/repository-access-and-collaboration/inviting-collaborators-to-a-personal-repository)、[GitHub Flow](https://docs.github.com/en/get-started/using-github/github-flow)。本指南给出团队约定，不表示远程权限或自动检查规则已配置。
