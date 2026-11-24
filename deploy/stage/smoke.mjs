// 시험 환경 동작 확인: 시험 회원 2명 로그인 → 채팅 연결표 → 웹소켓(STOMP) 연결 → 1번이 2번에게 보내고
// 1번은 전송 결과, 2번은 새 메시지 신호를 받는지 본다. 토큰과 비밀번호는 출력하지 않는다.
// 사용: node deploy/stage/smoke.mjs <비밀 폴더> [기본 주소, 기본값 http://127.0.0.1:38500]
// Node 22 이상(내장 WebSocket).
import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';

const secrets = process.argv[2];
const base = process.argv[3] ?? 'http://127.0.0.1:38500';
if (!secrets) {
  console.error('비밀 폴더 경로가 필요합니다');
  process.exit(2);
}
const password = readFileSync(`${secrets}/member-password`, 'utf8');
const email = (n) => `stage-member-${String(n).padStart(4, '0')}@stage.pawbridge.test`;

async function json(path, init = {}) {
  const response = await fetch(base + path, init);
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    throw new Error(`${init.method ?? 'GET'} ${path} → ${response.status} ${body?.message ?? ''}`);
  }
  return body.data;
}

async function login(n) {
  const data = await json('/api/v1/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: email(n), password }),
  });
  return { id: data.userId, token: data.accessToken };
}

function frame(command, headers = {}, body = '') {
  const lines = Object.entries(headers).map(([k, v]) => `${k}:${v}`);
  return `${command}\n${lines.join('\n')}\n\n${body}\0`;
}

function parse(raw) {
  const text = String(raw).replace(/\0$/, '');
  if (text.trim() === '') {
    return null;
  }
  const [head, ...rest] = text.split('\n\n');
  const [command, ...lines] = head.split('\n');
  return { command, headers: Object.fromEntries(lines.map((l) => l.split(/:(.*)/s).slice(0, 2))), body: rest.join('\n\n') };
}

// 연결해서 /user/queue/chat 을 구독하고, 들어오는 프레임을 queue 에 쌓는다.
async function connect(member) {
  const ticket = await json('/api/v1/chats/connection-ticket', {
    method: 'POST',
    headers: { Authorization: `Bearer ${member.token}` },
  });
  const url = base.replace(/^http/, 'ws') + '/api/v1/chats/socket';
  const socket = new WebSocket(url, ['v12.stomp']);
  const frames = [];
  const waiters = [];
  socket.onmessage = (event) => {
    const parsed = parse(event.data);
    if (!parsed) {
      return;
    }
    frames.push(parsed);
    waiters.splice(0).forEach((wake) => wake());
  };
  const next = async (command, timeout = 5000) => {
    const deadline = Date.now() + timeout;
    for (;;) {
      const index = frames.findIndex((f) => f.command === command);
      if (index >= 0) {
        return frames.splice(index, 1)[0];
      }
      if (Date.now() > deadline) {
        throw new Error(`${command} 프레임을 ${timeout}ms 안에 받지 못함 (받은 것: ${frames.map((f) => f.command).join(',') || '없음'})`);
      }
      await new Promise((wake) => {
        waiters.push(wake);
        setTimeout(wake, 200);
      });
    }
  };
  await new Promise((resolve, reject) => {
    socket.onopen = resolve;
    socket.onerror = () => reject(new Error('웹소켓 연결 실패'));
  });
  socket.send(frame('CONNECT', { 'accept-version': '1.2', host: 'localhost', 'heart-beat': '0,0', ticket: ticket.ticket }));
  await next('CONNECTED');
  socket.send(frame('SUBSCRIBE', { id: 'sub-0', destination: '/user/queue/chat' }));
  return { socket, next };
}

const started = Date.now();
const [first, second] = [await login(1), await login(2)];
console.log('로그인 2명 성공');
const [a, b] = [await connect(first), await connect(second)];
console.log('웹소켓 연결과 구독 2개 성공');
// 구독이 서버에 등록될 시간을 짧게 준다.
await new Promise((wake) => setTimeout(wake, 300));
const requestId = randomUUID();
const sentAt = Date.now();
a.socket.send(frame('SEND', { destination: '/app/chat/send', 'content-type': 'application/json' },
  JSON.stringify({ recipientId: second.id, requestId, body: '시험 환경 확인 메시지', contextType: null, contextId: null })));
// 보낸 쪽에는 자기 다른 탭용 신호(kind)와 이 연결만 받는 전송 결과(requestId)가 따로 온다.
let receipt;
while (!receipt) {
  const message = JSON.parse((await a.next('MESSAGE')).body);
  if (message.requestId === requestId) {
    receipt = message;
  }
}
if (receipt.status && receipt.status !== 200) {
  throw new Error(`전송 거절 ${receipt.status} ${receipt.message ?? ''}`);
}
console.log(`1번 전송 결과 수신 ${Date.now() - sentAt}ms`);
const signal = JSON.parse((await b.next('MESSAGE')).body);
if (signal.memberId !== second.id) {
  throw new Error('2번이 받은 신호의 회원이 다릅니다');
}
console.log(`2번 신호 수신(kind=${signal.kind}) ${Date.now() - sentAt}ms`);
const rooms = await json('/api/v1/chats', { headers: { Authorization: `Bearer ${second.token}` } });
console.log(`2번 대화방 목록: ${Array.isArray(rooms) ? rooms.length : (rooms?.content?.length ?? '?')}개`);
a.socket.close();
b.socket.close();
console.log(`끝 ${Date.now() - started}ms`);
