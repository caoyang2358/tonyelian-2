# 铜冶炼二期独立工程

工程初始化已完成：可启动的 React 前端、Spring Boot 后端、独立 MySQL 开发库、自动迁移与启动检查。业务开发刚开始；当前可用功能是设备基础信息只读查询，其他 6 个入口显示“待开发”。原型中的订单、库存、排产、维修和成本计算尚未实现。

## 第一次启动（Windows）

安装并加入 PATH：**Node.js 22.21+（22 LTS）、JDK 17 或 21、Maven 3.9+、MySQL 8.x 的 Server 和 Client 程序**。支持 Windows PowerShell 5.1 / PowerShell 7；无需 Docker，无需停止已安装的 MySQL 服务。MySQL 不在默认安装目录时，设置 `MYSQL_HOME` 为安装目录。

在 `phase2` 根目录打开 PowerShell：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Dev.ps1
```

首次会初始化独立 MySQL、生成随机密码、导入开发用元数据、安装前端依赖、测试并构建前后端，随后在后台启动。首次需要访问 npm 官方源和 Maven Central；依赖安装耗时取决于网络。脚本只为这次命令指定软件源，不改电脑的全局配置。

- 页面：[http://127.0.0.1:5174/](http://127.0.0.1:5174/)
- 后端就绪检查：[http://127.0.0.1:18280/api/v1/health/ready](http://127.0.0.1:18280/api/v1/health/ready)
- 数据库：`127.0.0.1:13307/copper_phase2_dev`；应用用户 `phase2_app`。

启动末尾应显示 `PASS ... equipment=8`。访问的都是本机地址；队员在各自电脑克隆工程并分别启动。

```powershell
# 已安装并构建后启动，跳过重复构建
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Dev.ps1 -SkipBuild
# 检查前端、后端、真实数据库查询及 Vite 代理
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Check-Dev.ps1
# 停止本工程的三个进程，保留开发库数据
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Stop-Dev.ps1
# 只停前后端，保留开发库运行
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Stop-Dev.ps1 -KeepDatabase
```

重复启动会复用本工程已运行的服务并跳过构建。修改后端代码或 `.env` 后，先停止再启动，使新代码和配置生效。脚本按 PID、启动时间及程序路径核对归属，不按端口批量杀进程；端口被其他应用占用会报错。

## 数据库边界与连接

一期与二期仅通过数据库关联。二期源码独立；运行二期不需要一期后端，也不调用一期 API。

默认启动创建**独立的开发实例**：数据在 `.local/mysql-data/`，端口为 **13307**。不连接或修改一期的 3306 服务，不读取一期密码。中文目录在本机 MySQL 8.0 上存在兼容问题，因此脚本在用户 TEMP 下建立一个经过目标校验的 ASCII 路径联接；数据仍存于本工程 `.local`，停止后保留。TEMP 被清理后，下次启动会重建联接。

开发库来自 [一期 MySQL 结构快照](database/dev/001_phase1_schema_snapshot.sql)，仅保留 12 张表的建表语句及外键；[种子数据](database/dev/002_demo_metadata_seed.sql)只有 2 个车间、8 台设备、10 个测点及单位/参数定义，标为开发演示资料。**没有现场读数、能源价格、订单、生产或库存数据**。来源文件及边界见 [开发库说明](database/dev/README.md)。这不代表已经连接并核验一期现用数据库。

根目录 `.env` 保存应用连接配置；`.local/database-admin.cnf` 保存本工程数据库管理员的随机密码。两者均被 Git 忽略，不要上传或转发。数据库客户端连接时，在自己电脑打开 `.env`，按其中 `DB_HOST / DB_PORT / DB_NAME / DB_USER / DB_PASSWORD` 填写。每位成员生成自己的本地密码。应用账号在这个独立开发库内有建表权限，不能用于生产环境。

二期 Flyway 使用独立的 `p2_flyway_schema_history` 和迁移目录；本次只建立技术元数据表 `p2_project_info`。后续业务表尚未落地，见[逻辑设计](docs/数据库逻辑表设计与成员使用指南.md)。托管开发库由启动脚本显式开启迁移；后端默认关闭迁移。

**已有的其他开发库**：复制 `.env.example` 到 `.env` 并填写自己的连接，运行 `Start-Dev.ps1 -ExternalDatabase`。这个模式不建库、不导入快照、不运行迁移，要求数据库已经具备所需一期表。不要使用一期生产库做开发；需要团队提供独立库或脱敏备份。切换模式前先停止旧进程。不要把开发快照应用到已有业务库。

## 日常开发与验证

```powershell
# 安装锁定依赖并执行前后端测试、构建（先停止正在运行的前后端）
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Build-Project.ps1
```

修改前端时 Vite 会热更新；后端当前为 JAR 启动，修改后需重新构建并重启。需要在 IDE 启动后端时，先只运行 `Initialize-Database.ps1`，再把本机 `.env` 值设为 IDE 的运行环境变量；Spring Boot 不会自行加载根目录 `.env`。

在 PowerShell 中手工运行后端或真实 MySQL 集成测试：

```powershell
. .\scripts\Common.ps1
Import-DevelopmentEnvironment
$env:PHASE2_RUN_MIGRATIONS = 'true'  # 仅对自己的隔离开发库开启
Push-Location backend
mvn -gs ..\scripts\maven-settings.xml -s ..\scripts\maven-settings.xml -Pmysql-integration verify
# 或：mvn -gs ..\scripts\maven-settings.xml -s ..\scripts\maven-settings.xml spring-boot:run
Pop-Location
```

手工启动前端：在 `frontend` 中运行 `npm ci --offline=false --registry=https://registry.npmjs.org` 和 `npm run dev`。`/api` 由 Vite 转发到后端 18280，浏览器无需跨域配置。详细命令、测试及 API 见[前端说明](frontend/README.md)、[后端说明](backend/README.md)。GitHub Actions 已配置前端测试/构建与后端单元测试/构建；连接真实 MySQL 的集成测试按上面命令本地运行。

## 出错时

| 现象 | 处理 |
| --- | --- |
| 找不到 node/java/mvn/mysqld/mysql | 安装对应程序并重开终端；MySQL 可设 `MYSQL_HOME` |
| 5174 / 18280 / 13307 被占用 | 关闭自己确认的冲突程序；脚本不会停止其他项目 |
| npm 或 Maven 下载失败 | 检查网络/企业代理，重新运行；不要删除锁文件绕过问题 |
| 后端未就绪 | 查看 `.local/logs/backend.log`、`backend-error.log` |
| MySQL 未就绪 | 查看 `.local/logs/mysql-error.log`；检查 TEMP 路径及 MySQL 安装 |
| 设备读取失败 | 用 `Check-Dev.ps1` 检查连接，再看后端日志；不会用假数据替代错误 |
| 提示非空库没有完成导入标记 | 首次导入曾中断，脚本拒绝覆盖现有表。保留 `.local` 供排查，不要把一期建库脚本拿来重灌 |
| 修改配置后仍运行旧版本 | `Stop-Dev.ps1` 后重新启动；已运行进程不会自动接收新环境变量 |

日志、依赖、构建物和本地数据库都不提交 Git。首次初始化失败也不会自动删除数据；需要重建时，先停止工程并备份自己的开发数据，再由负责人处理。

## 目录和团队协作

```text
frontend/          React + TypeScript + Vite；原型 7 入口及真实设备查询
backend/           Spring Boot + JDBC + MySQL；健康检查和只读查询
  src/main/resources/db/migration-phase2/  二期 Flyway 增量迁移
database/dev/      一期开发用结构快照及最小演示资料
scripts/           初始化、构建、启动、停止和连通检查
docs/              当前需求、数据库边界、协作任务和本次验收记录
.github/           任务/PR模板及构建检查
.local/            忽略：数据库文件、凭据、日志、进程记录、下载缓存
```

技术基线锁定：React/React DOM 19.3.1、TypeScript 7.0.2、Vite 8.3.5；Java 17 编译目标、Spring Boot 3.5.16、MySQL 8.x。本机使用 Node 22.21.0、Java 21.0.7、Maven 3.9.4、MySQL 8.0.42 验证。

先读[当前开发约定](docs/当前开发约定.md)、[需求基线](docs/需求基线.md)、[GitHub共同开发指南](docs/GitHub共同开发指南.md)。代码仓库：[caoyang2358/tonyelian-2](https://github.com/caoyang2358/tonyelian-2)。负责人已确认使用公开仓库；源码和需求文档公开，真实密码与本地数据库文件不上传。队员从仓库 main 克隆，各自在任务分支开发；另外两名成员的协作邀请仍待负责人完成。

本次执行结果与剩余范围见[工程初始化验收记录](docs/工程初始化验收记录.md)。
