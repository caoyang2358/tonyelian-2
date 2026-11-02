# 统一脚本

在 phase2 根目录打开 PowerShell，运行 `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Dev.ps1`。

| 脚本 | 用途 |
| --- | --- |
| Initialize-Database.ps1 | 初始化/复用独立 MySQL 13307；只为全新空库导入一次元数据 |
| Build-Project.ps1 | npm ci、前端测试/构建、后端 Maven verify |
| Start-Dev.ps1 | 默认先初始化数据库、构建、后台启动并验收；-SkipBuild 跳过构建；-ExternalDatabase 只连接用户配置的开发库 |
| Check-Dev.ps1 | 实际请求前端、live、ready、overview及代理设备接口 |
| Stop-Dev.ps1 | 仅停本工程进程，保留数据库文件；-KeepDatabase 保持数据库运行 |
| Common.ps1 | 受限 .env 加载、MySQL客户端、进程归属、编码及端口检查工具 |
| maven-settings.xml | 本次构建使用 Maven Central，不修改全局镜像设置 |

完整前提、数据库连接、首次/重复启动和故障处理见[根说明](../README.md)。脚本支持 Windows PowerShell 5.1 / PowerShell 7；不能直接在 Linux shell 中执行。其他平台可按前后端说明手工启动，并提供独立 MySQL 开发库。
