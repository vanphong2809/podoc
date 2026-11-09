// Private PoDoc ingress. TLS and tailnet access are handled by Tailscale Serve.
import http from 'node:http';
import net from 'node:net';
import { lstat, chmod, unlink, readFile } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { Transform } from 'node:stream';

const hop = ['connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization', 'te', 'trailer', 'transfer-encoding', 'upgrade'];
const removedHeaders = headers => new Set([...hop, ...(headers.connection || '').toLowerCase().split(',').map(s => s.trim())]);
export async function startGateway(config) {
  const {socketPath, authority, serviceHost, upstreamPort = 18088,
    maxBodyBytes = 5 * 1024 * 1024 * 1024, requestTimeoutMs = 3600000,
    upstreamTimeoutMs = 1800000} = config;
  if (![maxBodyBytes, requestTimeoutMs, upstreamTimeoutMs].every(value => Number.isSafeInteger(value) && value > 0)) throw Error('Invalid upload limits');
  if (!/^porender-server\.[a-z0-9-]+\.ts\.net:8443$/.test(authority) || !/^[a-f0-9-]{36}\.localhost$/.test(serviceHost)) throw Error('Invalid fixed application authority');
  if (!path.isAbsolute(socketPath) || Buffer.byteLength(socketPath) > 100 || !Number.isInteger(upstreamPort) || upstreamPort < 1024 || upstreamPort > 65535) throw Error('Invalid private socket or loopback upstream');
  const directory = await lstat(path.dirname(socketPath));
  if (!directory.isDirectory() || directory.uid !== process.getuid() || directory.mode & 0o077) throw Error('Socket directory must be owned and private');
  try {
    const old = await lstat(socketPath);
    if (!old.isSocket() || old.uid !== process.getuid()) throw Error('Refusing to replace unowned socket');
    await new Promise((resolve, reject) => {
      const probe = net.createConnection(socketPath);
      probe.on('connect', () => { probe.destroy(); reject(Error('Listener already active')); });
      probe.on('error', error => ['ECONNREFUSED', 'ENOENT'].includes(error.code) ? resolve() : reject(error));
      probe.setTimeout(1000, () => {probe.destroy(); reject(Error('Listener probe timed out'));});
    });
    await unlink(socketPath);
  } catch (error) { if (error.code !== 'ENOENT') throw error; }
  const sockets = new Set();
  const server = http.createServer({maxHeaderSize: 16384}, (request, response) => {
    const reject = status => {
      const detail = status === 413
        ? `Tệp vượt giới hạn upload ${Math.round(maxBodyBytes / (1024 * 1024))} MB của cổng máy chủ. Tệp chưa được lưu; hãy chọn tệp nhỏ hơn.`
        : status === 502 ? 'Kết nối tới máy chủ bị gián đoạn. Kiểm tra thư viện trước khi tải lại.' : 'Yêu cầu không hợp lệ.';
      if (!response.headersSent) response.writeHead(status, {'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store', connection: 'close'}).end(JSON.stringify({detail}));
      request.resume();
    };
    const serveUnix = request.headers.host === 'localhost' && request.headers['x-forwarded-proto'] === 'https' && request.headers['x-forwarded-host'] === authority;
    if (request.headers.host !== authority && !serveUnix) return reject(421);
    if (!request.url?.startsWith('/') || request.url.startsWith('//')) return reject(400);
    const length = request.headers['content-length'];
    if (length !== undefined && (!/^\d+$/.test(length) || Number(length) > maxBodyBytes)) return reject(413);
    const removed = removedHeaders(request.headers);
    const headers = Object.fromEntries(Object.entries(request.headers).filter(([key]) => !removed.has(key) && !['host','cookie','forwarded'].includes(key) && !key.startsWith('x-forwarded-') && !key.startsWith('tailscale-')));
    headers.host = serviceHost;
    headers['x-forwarded-proto'] = 'https';
    headers['x-forwarded-host'] = authority;
    let received = 0;
    const bounded = new Transform({transform(chunk, encoding, callback) {
      received += chunk.length;
      if (received > maxBodyBytes) callback(Error('Body limit'));
      else callback(null, chunk);
    }});
    const outgoing = http.request({hostname: '127.0.0.1', port: upstreamPort, method: request.method, path: request.url, headers}, incoming => {
      const removedResponse = removedHeaders(incoming.headers);
      for (const [key, value] of Object.entries(incoming.headers)) {
        if (value === undefined || removedResponse.has(key) || key === 'set-cookie') continue;
        if (key === 'location') {
          const location = new URL(String(value), 'http://' + serviceHost);
          response.setHeader(key, location.hostname === serviceHost ? 'https://' + authority + location.pathname + location.search + location.hash : value);
        } else response.setHeader(key, value);
      }
      response.setHeader('x-content-type-options', 'nosniff');
      response.setHeader('strict-transport-security', 'max-age=31536000');
      response.writeHead(incoming.statusCode || 502);
      incoming.on('error', () => response.destroy());
      incoming.pipe(response);
    });
    outgoing.setTimeout(upstreamTimeoutMs, () => outgoing.destroy(Error('Upstream timeout')));
    outgoing.on('error', () => { if (!response.writableEnded) response.headersSent ? response.destroy() : reject(502); });
    bounded.on('error', () => { reject(413); outgoing.destroy(); });
    request.on('aborted', () => outgoing.destroy());
    request.on('error', () => outgoing.destroy());
    response.on('close', () => outgoing.destroy());
    request.pipe(bounded).pipe(outgoing);
  });
  server.on('connection', socket => {sockets.add(socket); socket.on('close', () => sockets.delete(socket));});
  server.on('upgrade', (request, socket) => socket.destroy());
  server.headersTimeout = 15000;
  server.requestTimeout = requestTimeoutMs;
  await new Promise((resolve, reject) => {server.once('error', reject); server.listen(socketPath, resolve);});
  await chmod(socketPath, 0o600);
  return {server, close: async () => {
    const closed = new Promise(resolve => server.close(resolve));
    for (const socket of sockets) socket.destroy();
    await closed;
    await unlink(socketPath).catch(error => {if (error.code !== 'ENOENT') throw error;});
  }};
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const gateway = await startGateway(JSON.parse(await readFile(process.argv[2], 'utf8')));
  console.log('Private PoDoc gateway ready');
  for (const signal of ['SIGTERM','SIGINT']) process.once(signal, () => gateway.close().then(() => process.exit(0)));
}
