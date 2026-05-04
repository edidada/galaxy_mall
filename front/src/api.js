
const isDev = import.meta.env.DEV;
let API_BASE = '';

if (isDev) {
    API_BASE = 'http://192.168.31.171:30897';
}

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
