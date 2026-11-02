# 铜冶炼二期独立后端

当前交付为工程初始化：Java 17、Spring Boot 3.5.16、Spring JDBC、MySQL Connector/J，以及二期独立 Flyway 迁移。没有复制或依赖一期后端，不调用一期 API。

仅提供健康检查、开发库概览及生产设备只读列表；订单、库存、维修、成本等正式业务尚未实现。

## 配置与运行

使用 JDK 17 或更高版本及 Maven 3.9+；源码以 Java 17 编译。配置只从本地环境提供，不把密码写入仓库。

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `SERVER_PORT` | `18280` | 二期 HTTP 端口 |
| `DB_HOST` | `127.0.0.1` | 独立开发 MySQL 地址 |
| `DB_PORT` | `13307` | 开发实例端口，区别于现用库 |
| `DB_NAME` | `copper_phase2_dev` | 只供二期开发使用的数据库 |
| `DB_USER` | `phase2_app` | 本地开发账号 |
| `DB_PASSWORD` | 空，必须通过本地初始化提供 | 随机生成的本地密码，不打印到终端或 API |
| `PHASE2_RUN_MIGRATIONS` | `false` | 仅显式 `true` 时执行二期迁移 |
| `APP_ENV` | `development` | 概览环境标记 |

JDBC 按 `Asia/Shanghai` 解释时间，会话通过 `SET time_zone='+08:00'` 统一为 UTC+8，不依赖开发 MySQL 预装命名时区表；API 时间戳包含 `+08:00`。当前非 TLS 连接仅用于独立本地开发实例，不作为部署环境规范。

先按 `phase2/scripts` 的初始化说明准备隔离开发库、结构及最小演示数据。账号密码由该本地流程提供。随后在本目录运行：

```powershell
mvn -gs ../scripts/maven-settings.xml -s ../scripts/maven-settings.xml clean verify
java -jar target/copper-phase2-backend-0.1.0-SNAPSHOT.jar
```

首次迁移由初始化／启动脚本显式设置 `PHASE2_RUN_MIGRATIONS=true`；不应为现用数据库设置此开关。

## 迁移边界

- 位置：`classpath:db/migration-phase2`；历史表：`p2_flyway_schema_history`，与一期分开。
- 默认关闭迁移，便于对外部数据库仅查询。关闭迁移不代表连接账号自动具有数据库只读权限。
- 独立开发库已有 12 张一期结构副本，因此在**显式开启迁移时**允许基线版本 `0`，之后应用二期 V1。
- V1 仅创建并登记 `p2_project_info` 工程元数据，不建立业务表，不删除／重建一期表，不复制执行一期迁移。
- 表数量排除迁移历史表；V1 后 `phase2TableCount` 为 `1`，不代表正式业务表已实现。
- Flyway `clean` 禁用；迁移失败不通过修改一期历史掩盖。

## API

统一信封：`{code, message, data, timestamp}`；成功 `code=OK`。不向调用方暴露数据库异常文本、连接串、密码或堆栈。

| 接口 | 行为 |
| --- | --- |
| `GET /api/v1/health/live` | 确认后端进程存活，不访问数据库 |
| `GET /api/v1/health/ready` | 实际执行 `SELECT 1`；数据库不可用返回 HTTP 503 |
| `GET /api/v1/bootstrap/overview` | 查询真实 MySQL 库名、版本、表数量与主数据数量；范围 `INITIALIZATION_ONLY` |
| `GET /api/v1/equipment` | 读取 `dim_equipment JOIN dim_workshop`，仅 `equipment_usage='PRODUCTION'`，保留实际 ID／编码和停用标识 |

`ready.data.database` 为对象 `{status:'UP'或'DOWN',check:'SELECT 1'}`。概览表数量来自当前选定库的 `information_schema.tables`，一期仅数约定的 12 张核心表。缺表或查询失败返回 503，不编造零条或演示响应。此轮未提供主数据 CRUD。

## 验证

```powershell
mvn -gs ../scripts/maven-settings.xml -s ../scripts/maven-settings.xml test
```

默认测试不需要数据库，不引入 H2。MockMvc 验证信封、状态码、存活不触库及错误不泄漏；SQL 映射测试验证设备身份、关联字段、可空责任人及停用标识。

隔离 MySQL 已初始化并完成二期 V1 后，可运行只读集成验证：

```powershell
mvn -gs ../scripts/maven-settings.xml -s ../scripts/maven-settings.xml -Pmysql-integration verify
```

该项连接环境变量指定的真实 MySQL，检查数据库产品、会话时区、12 张一期副本结构、二期元数据及 HTTP 查询链路，没有 H2 替代。普通 `mvn test` 不执行此项。

上面的 `-gs` 与 `-s` 只为本次构建使用项目中央仓库配置，不修改电脑的全局 Maven 设置，避免已有镜像覆盖项目源。
