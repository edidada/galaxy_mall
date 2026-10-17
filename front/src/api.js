
const isDev = import.meta.env.DEV;
let API_BASE = '';

if (isDev) {
    // k3s 集群入口：front 的 NodePort，同源转发 /api/gateway（无需额外 Host 头）
    // 走 Ingress 则用 http://mall.192.168.31.77.nip.io（需先写 hosts）
    // ⚠️ 旧地址 http://192.168.31.171:30897 已失效（.171 是 Proxmox 宿主，不承载 k3s 端口）
    API_BASE = 'http://192.168.31.77:30080';
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
const _origFetch = window.fetch.bind(window);
window.fetch = async (input, init = {}) => {
    const traceId = uuidv4();
    init.headers = { ...(init.headers || {}), 'X-Trace-Id': traceId };
    const res = await _origFetch(input, init);
    const respTrace = res.headers.get('X-Trace-Id') || traceId;
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
