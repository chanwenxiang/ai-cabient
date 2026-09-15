/**
 * 共享工具：从 Redis 读取图形验证码原文。
 *
 * 两种后端按环境自动选择：
 * - 设置 REDIS_HOST 时走 TCP 直连（内置最小 RESP 客户端，零外部依赖）—— 供 CI 使用
 * - 否则回退 `docker exec <REDIS_CONTAINER> redis-cli` —— 供本地开发栈使用
 *
 * 返回 trim 后的原始字符串（不改变大小写），由调用方决定 toUpperCase/toLowerCase。
 */
import { execSync } from 'node:child_process';
import net from 'node:net';

const DEFAULT_REDIS_CONTAINER = 'ai-cabinet-redis-1';

/** 解析 RESP 的 `$<len>` 批量串回复；nil / error 视为空串。 */
export function parseRespBulk(raw) {
  if (!raw || raw.startsWith('$-1') || raw.startsWith('-')) return '';
  const nl = raw.indexOf('\r\n');
  if (nl < 0) return String(raw).trim();
  return raw.slice(nl + 2, nl + 2 + Number(raw.slice(1, nl)));
}

/** 最小 RESP 客户端：GET 单个 key，避免依赖 redis-cli / docker。 */
export function redisGetViaSocket(key) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({
      host: process.env.REDIS_HOST || '127.0.0.1',
      port: Number(process.env.REDIS_PORT || 6379)
    });
    let buffer = '';
    let settled = false;
    const finish = (fn, arg) => {
      if (settled) return;
      settled = true;
      socket.destroy();
      fn(arg);
    };
    socket.setTimeout(5000);
    socket.on('connect', () => {
      socket.write(`*2\r\n$3\r\nGET\r\n$${Buffer.byteLength(key)}\r\n${key}\r\n`);
    });
    socket.on('data', (chunk) => {
      buffer += chunk.toString('utf8');
      // Redis 保持长连接不会主动 end，故收到首帧即视为回复完整（单条 GET 必在一个 TCP 段内）
      finish(resolve, parseRespBulk(buffer));
    });
    socket.on('end', () => finish(resolve, parseRespBulk(buffer)));
    socket.on('timeout', () => finish(reject, new Error('redis timeout')));
    socket.on('error', (err) => finish(reject, err));
  });
}

/** 读取 `aicabinet:captcha:<id>` 原文；缺失或报错时抛异常。 */
export async function captchaFromRedis(captchaId) {
  const key = `aicabinet:captcha:${captchaId}`;
  const raw = process.env.REDIS_HOST
    ? await redisGetViaSocket(key)
    : execSync(
        `docker exec ${process.env.REDIS_CONTAINER || DEFAULT_REDIS_CONTAINER} redis-cli GET ${key}`,
        { encoding: 'utf8' }
      );
  const value = String(raw).trim();
  if (!value || /nil|ERR/i.test(value)) throw new Error(`captcha missing in redis: ${captchaId}`);
  return value;
}
