
const isDev = import.meta.env.DEV;
let API_BASE = '';

// 2026-10-17：商城页（/api/mall/*、/api/collect）改成走 vite server.proxy 同源转发，
// 所以开发态默认 API_BASE 为空串。要退回直连集群，在 front/.env.local 里写
// VITE_API_BASE=http://192.168.31.77:30080 即可，无需改代码。
// 集群入口：front 的 NodePort，同源转发 /api/gateway（无需额外 Host 头）
// 走 Ingress 则用 http://mall.192.168.31.77.nip.io（需先写 hosts）
// ⚠️ 旧地址 http://192.168.31.171:30897 已失效（.171 是 Proxmox 宿主，不承载 k3s 端口）
if (isDev) {
    API_BASE = import.meta.env.VITE_API_BASE || '';
}

// 会话令牌：由 stores/session.js 在登录/恢复登录态时写入，所有请求自动带上 X-Auth-Token。
// 放在 api.js 里而不是让 session store 去改 fetch，是为了避免 api <-> store 的循环依赖。
let authToken = '';
export function setAuthToken(token) {
    authToken = token || '';
}
export function getAuthToken() {
    return authToken;
}

export function apiUrl(path) {
    return `${API_BASE}${path}`;
}

export function authHeaders(extra = {}) {
    return { ...(extra || {}), ...(authToken ? { 'X-Auth-Token': authToken } : {}) };
}

/** 统一 JSON 请求：GET 不带 body，POST 自动补 Content-Type；网络异常返回结构化失败而不是抛出。 */
async function jsonRequest(path, { method = 'GET', body, headers } = {}) {
    try {
        const res = await fetch(apiUrl(path), {
            method,
            headers: authHeaders(method === 'GET' ? headers : { 'Content-Type': 'application/json', ...headers }),
            body: body === undefined ? undefined : JSON.stringify(body)
        });
        return await res.json();
    } catch (e) {
        return { success: false, message: `network-error:${e.message}`, data: null };
    }
}


// ========== 全链路追踪：为每个请求生成并注入 X-Trace-Id ==========
// 非安全上下文（http://IP）下 crypto.randomUUID() 不可用，用 getRandomValues 自己拼一个 v4 UUID。
function uuidv4() {
    const b = new Uint8Array(16);
    crypto.getRandomValues(b);
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    const h = Array.from(b, x => x.toString(16).padStart(2, '0'));
    return `${h[0]}${h[1]}${h[2]}${h[3]}-${h[4]}${h[5]}-${h[6]}${h[7]}-${h[8]}${h[9]}-${h[10]}${h[11]}${h[12]}${h[13]}${h[14]}${h[15]}`;
}

// 包装原生 fetch：注入 X-Trace-Id，拿到响应后把（网关回传的）traceId 派发给页面显示。
// 注意 /edge/** 这条链路会经过 Spring Cloud Gateway 的路由过滤器 + 我们的 WebFilter + 拦截器，
// 同一个 X-Trace-Id 被写进响应头三次，fetch 把多值头拼成 "a, a, a"。
// 这里去重只动展示层：改后端那三处写头会影响压测链路的追踪，不划算。
const _origFetch = window.fetch.bind(window);
window.fetch = async (input, init = {}) => {
    const traceId = uuidv4();
    init.headers = { ...(init.headers || {}), 'X-Trace-Id': traceId };
    const res = await _origFetch(input, init);
    const raw = res.headers.get('X-Trace-Id') || traceId;
    const respTrace = [...new Set(raw.split(',').map(v => v.trim()))].join(', ');
    window.dispatchEvent(new CustomEvent('galaxy-trace', { detail: respTrace }));
    return res;
};

// ========== 网关编排接口 ==========

export async function getProduct(productId, userId = 'u100', clientIp = '10.0.0.1') {
    const url = `${API_BASE}/api/gateway/products/${productId}?userId=${encodeURIComponent(userId)}&clientIp=${encodeURIComponent(clientIp)}`;
    const res = await fetch(url);
    return await res.json();
}

export async function submitOrder(data) {
    const res = await fetch(`${API_BASE}/api/gateway/orders`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data)
    });
    return await res.json();
}

export async function getOrder(orderId) {
    const res = await fetch(`${API_BASE}/api/gateway/orders/${orderId}`);
    return await res.json();
}

export async function payOrder(orderId) {
    const res = await fetch(`${API_BASE}/api/gateway/payments/${orderId}`, {
        method: 'POST'
    });
    return await res.json();
}

// ========== 网关 Edge 路由接口 ==========

export async function getUser(userId) {
    const res = await fetch(`${API_BASE}/edge/users/${userId}`);
    return await res.json();
}

export async function getEdgeProduct(productId) {
    const res = await fetch(`${API_BASE}/edge/products/${productId}`);
    return await res.json();
}

export async function reserveStock(data) {
    const res = await fetch(`${API_BASE}/edge/stocks/reserve`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data)
    });
    return await res.json();
}

export async function confirmStock(reservationId) {
    const res = await fetch(`${API_BASE}/edge/stocks/confirm`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ reservationId })
    });
    return await res.json();
}

export async function releaseStock(reservationId, reason) {
    const res = await fetch(`${API_BASE}/edge/stocks/release`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ reservationId, reason })
    });
    return await res.json();
}

export async function createEdgeOrder(data) {
    const res = await fetch(`${API_BASE}/edge/orders`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data)
    });
    return await res.json();
}

export async function getEdgeOrder(orderId) {
    const res = await fetch(`${API_BASE}/edge/orders/${orderId}`);
    return await res.json();
}

export async function markOrderPaid(orderId) {
    const res = await fetch(`${API_BASE}/edge/orders/${orderId}/paid`, {
        method: 'POST'
    });
    return await res.json();
}

export async function createEdgePayment(orderId) {
    const res = await fetch(`${API_BASE}/edge/payments/${orderId}`, {
        method: 'POST'
    });
    return await res.json();
}

// ========== 直连微服务接口 ==========

export async function getDirectUser(userId) {
    const res = await fetch(`${API_BASE}/api/users/${userId}`);
    return await res.json();
}

export async function getDirectProduct(productId) {
    const res = await fetch(`${API_BASE}/api/products/${productId}`);
    return await res.json();
}

export async function getDirectProductPage(productId) {
    const res = await fetch(`${API_BASE}/api/products/${productId}/page`);
    return await res.text();
}

export async function reserveDirectStock(data) {
    const res = await fetch(`${API_BASE}/api/stocks/reserve`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data)
    });
    return await res.json();
}

export async function confirmDirectStock(reservationId) {
    const res = await fetch(`${API_BASE}/api/stocks/confirm`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ reservationId })
    });
    return await res.json();
}

export async function releaseDirectStock(reservationId, reason) {
    const res = await fetch(`${API_BASE}/api/stocks/release`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ reservationId, reason })
    });
    return await res.json();
}

export async function createDirectOrder(data) {
    const res = await fetch(`${API_BASE}/api/orders`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data)
    });
    return await res.json();
}

export async function getDirectOrder(orderId) {
    const res = await fetch(`${API_BASE}/api/orders/${orderId}`);
    return await res.json();
}

export async function markDirectOrderPaid(orderId) {
    const res = await fetch(`${API_BASE}/api/orders/${orderId}/paid`, {
        method: 'POST'
    });
    return await res.json();
}

export async function createDirectPayment(orderId) {
    const res = await fetch(`${API_BASE}/api/payments/${orderId}`, {
        method: 'POST'
    });
    return await res.json();
}

export async function getActuatorHealth() {
    const res = await fetch(`${API_BASE}/actuator/health`);
    return await res.json();
}

export async function getActuatorInfo() {
    const res = await fetch(`${API_BASE}/actuator/info`);
    return await res.json();
}

// ========== 商城前台接口（网关 /api/mall/*，2026-10-17 新增）==========
// 这一组是给人看的商城页用的；/api/gateway/* 留给压测编排，/edge/* 留给透明代理，三套互不干扰。

export function listMallProducts({ category = '', keyword = '' } = {}) {
    const params = new URLSearchParams();
    if (category && category !== '全部') params.set('category', category);
    if (keyword) params.set('keyword', keyword);
    const qs = params.toString();
    return jsonRequest(`/api/mall/products${qs ? `?${qs}` : ''}`);
}

export function getMallProductDetail(productId) {
    return jsonRequest(`/api/mall/products/${encodeURIComponent(productId)}`);
}

export function mallLogin(username, password) {
    return jsonRequest('/api/mall/login', { method: 'POST', body: { username, password, clientTs: Date.now() } });
}

export function mallRegister(username, password, nickname = '') {
    const params = new URLSearchParams({ username, password });
    if (nickname) params.set('nickname', nickname);
    return jsonRequest(`/api/mall/register?${params.toString()}`, { method: 'POST' });
}

export function mallLogout() {
    return jsonRequest('/api/mall/logout', { method: 'POST' });
}

export function mallMe() {
    return jsonRequest('/api/mall/me');
}

// ========== 埋点上报（网关 /api/collect）==========

/**
 * 一批事件的上报。SDK 用 keepalive 以保证页面切走后仍能发出；
 * 页面卸载那条走 sendBeacon，见 src/track/index.js。
 */
export function collectEvents(batch) {
    return fetch(apiUrl('/api/collect'), {
        method: 'POST',
        headers: authHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(batch),
        keepalive: true
    }).then(res => res.json()).catch(() => ({ success: false, message: 'network-error', data: null }));
}

export function getCollectStats() {
    return jsonRequest('/api/collect/stats');
}

export function getCollectEvents(limit = 50) {
    return jsonRequest(`/api/collect/events?limit=${limit}`);
}
