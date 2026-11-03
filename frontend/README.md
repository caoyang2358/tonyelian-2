# 二期独立前端

从零创建的React、TypeScript、Vite工程。与一期前端及浏览器原型独立，不包含复制的原型业务脚本。

当前范围：两个系统7个主入口。默认设备管理调用二期后端真实只读设备接口；其余入口明确标为待开发。当前没有订单、库存、生产或核算业务实现。

## 系统一页面开发入口

| 页面 | 组件入口 |
| --- | --- |
| 设备管理 | `src/features/equipment/EquipmentPage.tsx` |
| 运维管理 | `src/features/maintenance/MaintenancePage.tsx` |
| 运维知识库 | `src/features/knowledge/KnowledgePage.tsx` |
| 设备组网 | `src/features/network/NetworkPage.tsx` |

各页面的专用组件和接口调用放到对应模块下。全局导航由 `src/App.tsx` 维护，公共组件放在 `src/components/`，共用请求逻辑放在 `src/lib/`。接口字段及共享接口归属见[接口总览](../docs/接口清单.md)。

## 启动

环境：Node.js 22.21及以上兼容版本、npm。依赖版本固定在package.json与package-lock.json中。

```powershell
npm ci
npm run dev
```

访问 `http://127.0.0.1:5174`。端口占用时不会自动切换端口。

开发服务器将 `/api` 代理到 `http://127.0.0.1:18280`。前端只调用二期API，不直接连接数据库、不调用一期API、不保存数据库凭据。

## 检查与构建

```powershell
npm test
npm run build
```

测试采用Node内置测试运行器，覆盖API字段映射、未知值与真实零、重复ID、HTTP及业务错误、网络故障和请求取消。类型检查在构建前执行。测试关闭子进程隔离，使受限开发环境也能运行这组纯API测试。

构建产物为 `dist/`。`npm run preview`只预览静态产物；正式部署需要另外配置 `/api` 转发，开发代理不是生产部署配置。

## 初始化API

- `GET /api/v1/health/live`：二期服务存活状态。
- `GET /api/v1/health/ready`：二期数据库就绪检查。
- `GET /api/v1/bootstrap/overview`：环境、数据库和真实数量概览。
- `GET /api/v1/equipment`：生产设备只读列表。

响应采用 `{code:'OK',message,data,timestamp}`。接口失败不会替换成示例设备；空列表、缺失值和真实零分开处理。设备 `enabled` 仅表示基础数据启用状态，不解释成机械故障、在线或报废状态。

设备页支持名称/编码搜索、车间及启用状态筛选、加载、无数据、无匹配、错误重试。刷新失败时明确标注保留的是上次读取结果。7个入口及查询操作可使用键盘，表格在窄屏内局部滚动。

该工程完成的是初始化及只读接入，不代表二期业务功能已经完成。
