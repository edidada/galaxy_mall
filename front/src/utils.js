/** 价格与文案的小工具，全站共用一份，避免每个页面各写一遍分转元。 */

export function fenToYuan(cent) {
    return (cent / 100).toFixed(2);
}

/** JD 的展示习惯：整数价不带小数，非整数带两位。 */
export function formatPrice(cent) {
    if (cent == null || Number.isNaN(cent)) return '--';
    const yuan = cent / 100;
    return Number.isInteger(yuan) ? String(yuan) : yuan.toFixed(2);
}

export function formatTime(ms) {
    if (!ms) return '--';
    const d = new Date(ms);
    const p = n => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

const ORDER_STATUS_TEXT = {
    SUBMITTED: '已提交',
    WAITING_PAYMENT: '待付款',
    PAID: '已付款',
    REFUNDED: '已退款',
    QUEUED: '排队中',
    CANCELLED: '已取消'
};

export function orderStatusText(status) {
    return ORDER_STATUS_TEXT[status] || status || '未知';
}

/** 后端 message 用的是机器码（order-not-found、downstream-unavailable:product），给人看要翻一下。 */
const MESSAGE_TEXT = {
    'login-failed:bad-credentials': '账号或密码不正确',
    'session-not-found': '登录已过期，请重新登录',
    'register-failed:username-taken-or-password-too-short': '用户名已被占用，或密码少于 6 位',
    'product-detail-not-found': '商品不存在或已下架',
    'product-not-found': '商品不存在',
    'query-throttled': '访问太频繁，请稍后再试',
    'waiting-room-full': '排队人数已满，请稍后再试',
    'order-not-payable': '订单当前不可支付',
    'order-not-found': '订单不存在'
};

export function humanMessage(message, fallback = '操作失败，请重试') {
    if (!message) return fallback;
    if (MESSAGE_TEXT[message]) return MESSAGE_TEXT[message];
    if (message.startsWith('downstream-unavailable:')) {
        return `后端服务暂不可用（${message.split(':')[1]}），请确认服务已启动`;
    }
    if (message.startsWith('network-error:')) return '网络请求失败，请检查后端服务是否已启动';
    return message;
}
