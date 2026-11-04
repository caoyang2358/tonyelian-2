// scripts/ 下 UI 验收脚本共用的连库配置与前置探活（Node 22，无第三方依赖）。
//
// 只服务于本地验收脚本，不参与应用运行。端口解析优先级与历史保持一致：
//     环境变量 DB_PORT > 项目根 .env > 内置默认值 3306
// 改动只落在失败路径上：
//   · 连不上时立刻退出，并说明「这个端口是从哪来的」，而不是在脚本末尾清理阶段才抛
//     Access denied——那时测试数据已经造好，会留下孤儿测点、孤儿参数；
//   · 3306 常被本机其他 MySQL 实例占用（账号也不同），提前失败也避免把校验数据写进别人的库。
//
// 用法：
//   import {loadDbConfig, preflightDatabase} from './db-env.mjs';
//   const DB = loadDbConfig();
//   preflightDatabase(DB);      // 放在任何造数据动作之前

import {existsSync, readFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import path from 'node:path';

const ROOT = path.resolve(import.meta.dirname, '..');
const DEFAULT_PORT = '3306'; // 与历史行为一致：三层都缺省时回落到 MySQL 出厂端口
const DEFAULT_MYSQL_BIN = 'D:/MySQL/MySQL Server 8.0/bin/mysql.exe';

const PORT_SOURCE_ENV = '环境变量 DB_PORT';
const PORT_SOURCE_FILE = '.env 文件';
const PORT_SOURCE_DEFAULT = '脚本内置默认值（未配置）';

export function readEnvFile() {
  const values = {};
  const file = path.join(ROOT, '.env');
  if (!existsSync(file)) return values;
  for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#') || !trimmed.includes('=')) continue;
    const index = trimmed.indexOf('=');
    values[trimmed.slice(0, index).trim()] = trimmed.slice(index + 1).trim();
  }
  return values;
}

export function resolveMysqlBin(fileEnv = readEnvFile()) {
  const override = process.env.MYSQL_BIN ?? fileEnv.MYSQL_BIN;
  if (override && existsSync(override)) return override;
  if (existsSync(DEFAULT_MYSQL_BIN)) return DEFAULT_MYSQL_BIN;
  return 'mysql';
}

export function loadDbConfig() {
  const fileEnv = readEnvFile();
  let port;
  let portSource;
  if (process.env.DB_PORT) {
    port = process.env.DB_PORT;
    portSource = PORT_SOURCE_ENV;
  } else if (fileEnv.DB_PORT) {
    port = fileEnv.DB_PORT;
    portSource = PORT_SOURCE_FILE;
  } else {
    port = DEFAULT_PORT;
    portSource = PORT_SOURCE_DEFAULT;
  }
  return {
    host: process.env.DB_HOST ?? fileEnv.DB_HOST ?? '127.0.0.1',
    port,
    portSource,
    name: process.env.DB_NAME ?? fileEnv.DB_NAME ?? '',
    user: process.env.DB_USER ?? fileEnv.DB_USER ?? '',
    password: process.env.DB_PASSWORD ?? fileEnv.DB_PASSWORD ?? '',
    bin: resolveMysqlBin(fileEnv),
  };
}

export function mysqlArgs(config, statement) {
  // 密码为空时也必须用 --password=：单独一个 -p 会让 mysql 转去交互式索要密码而卡住。
  return [
    '--no-defaults', '--default-character-set=utf8mb4',
    `-h${config.host}`, `-P${config.port}`,
    `-u${config.user || 'root'}`, `--password=${config.password || ''}`,
    config.name, '-N', '-e', statement,
  ];
}

function reasonLine(stderr) {
  const lines = String(stderr ?? '').split(/\r?\n/)
    .map(line => line.trim())
    .filter(line => line && !line.startsWith('mysql: [Warning]'));
  return lines.at(-1) ?? '（mysql 未输出原因）';
}

function diagnose(config, stderr) {
  const reason = reasonLine(stderr);
  const lines = [
    `数据库连接失败：${config.host}:${config.port}（端口来源：${config.portSource}）`,
    `  账号 ${config.user || '(未配置)'} / 库 ${config.name || '(未配置)'}`,
    `  原因：${reason}`,
  ];
  if (reason.includes('Access denied')) {
    lines.push('  判断：账号或密码和这个实例不匹配，多半是连错了端口（不同实例账号不同）。');
  } else if (reason.includes('Unknown database')) {
    lines.push(`  判断：这个实例上没有 ${config.name} 库，多半是连错了端口。`);
  } else if (reason.includes("Can't connect") || reason.includes('10061')) {
    lines.push('  判断：该端口没有 MySQL 在监听。');
  }
  lines.push(
    '  处理：① 核对仓库根 .env 的 DB_PORT 是否就是运行中实例的端口；',
    '        ② 或临时指定：export DB_PORT=<实际端口>（PowerShell: $env:DB_PORT="<端口>"）；',
    '        ③ 查看本机监听：netstat -ano | findstr LISTENING',
    '  脚本尚未创建测试数据，修正后可直接重跑。',
  );
  return lines.join('\n');
}

/** 动数据之前先探一次库；失败打印诊断并退出（退出码 1）。 */
export function preflightDatabase(config, {required = true} = {}) {
  if (!config.name || !config.user) {
    console.error('数据库连接信息不完整：请检查仓库根 .env 的 DB_NAME / DB_USER / DB_PASSWORD。');
    process.exit(1);
  }
  if (config.bin !== 'mysql' && !existsSync(config.bin)) {
    console.log(`[DB] 未找到 MySQL 客户端（${config.bin}）。`);
    if (required) {
      console.error('请在 .env 或环境变量 MYSQL_BIN 中指定 mysql 客户端路径。');
      process.exit(1);
    }
    return false;
  }
  try {
    // stderr 默认会转发到父进程，这里显式吃掉，避免原始 mysql 报错和诊断信息重复。
    execFileSync(config.bin, mysqlArgs(config, 'SELECT 1;'),
      {encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe']});
  } catch (error) {
    if (error?.code === 'ENOENT') {
      console.log(`[DB] 未找到 MySQL 客户端（${config.bin}）。`);
      if (required) process.exit(1);
      return false;
    }
    console.error(diagnose(config, error?.stderr));
    process.exit(1);
  }
  console.log(`[DB] ${config.host}:${config.port} 连接正常（端口来源：${config.portSource}）`);
  return true;
}
