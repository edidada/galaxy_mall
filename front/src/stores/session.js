/**
 * 登录态：令牌 + 用户信息，全站单例。
 *
 * 令牌存 localStorage（关闭浏览器仍保持登录，与 JD PC 的行为一致），
 * 真正的有效期由 user 服务的 Session TTL（12h）决定，
 * 所以启动时先 restore() 拿本地令牌去 /api/mall/me 验一次，过期就清本地态。
 */
import { reactive, computed } from 'vue';
import { mallLogin, mallLogout, mallMe, mallRegister, setAuthToken } from '../api';
import { tracker } from '../track';

const LS_KEY = 'gm_session';

const state = reactive({
    status: 'anonymous', // anonymous | checking | authed
    token: '',
    user: null,          // { userId, nickname, level }
    expiresAt: 0,
    error: ''
});

function persist() {
    try {
        if (state.token) {
            localStorage.setItem(LS_KEY, JSON.stringify({ token: state.token, user: state.user, expiresAt: state.expiresAt }));
        } else {
            localStorage.removeItem(LS_KEY);
        }
    } catch {
        /* 隐私模式存不下不影响使用 */
    }
}

function applySession(session) {
    state.user = { userId: session.userId, nickname: session.nickname, level: session.level };
    state.expiresAt = session.expiresAtEpochMillis || 0;
    state.status = 'authed';
    tracker.setUser(session.userId);
    persist();
}

function clear() {
    state.token = '';
    state.user = null;
    state.expiresAt = 0;
    state.status = 'anonymous';
    setAuthToken('');
    tracker.setUser('');
    persist();
}

export async function restore() {
    let cached = null;
    try {
        cached = JSON.parse(localStorage.getItem(LS_KEY) || 'null');
    } catch {
        cached = null;
    }
    if (!cached?.token) return false;
    state.token = cached.token;
    state.user = cached.user || null;
    state.expiresAt = cached.expiresAt || 0;
    state.status = 'checking';
    setAuthToken(cached.token);

    const res = await mallMe();
    if (res?.success && res.data) {
        applySession(res.data);
        return true;
    }
    clear();
    return false;
}

export async function login(username, password) {
    state.error = '';
    const res = await mallLogin(username, password);
    if (res?.success && res.data) {
        state.token = res.data.token;
        setAuthToken(res.data.token);
        applySession(res.data);
        tracker.track('biz', 'login-success', { username, userId: res.data.userId });
        return { ok: true, user: state.user };
    }
    state.status = 'anonymous';
    state.error = res?.message || 'login-failed:bad-credentials';
    tracker.track('biz', 'login-failed', { username, reason: state.error });
    return { ok: false, reason: state.error };
}

export async function register(username, password, nickname) {
    const res = await mallRegister(username, password, nickname);
    if (res?.success && res.data) {
        state.token = res.data.token;
        setAuthToken(res.data.token);
        applySession(res.data);
        return { ok: true, user: state.user };
    }
    return { ok: false, reason: res?.message || 'register-failed' };
}

export async function logout() {
    await mallLogout();
    tracker.track('biz', 'logout', {});
    clear();
}

export function requireLogin() {
    return state.status === 'authed';
}

export const session = state;
export const isLoggedIn = computed(() => state.status === 'authed');
export const currentUser = computed(() => state.user);
/** 下单链路里的 userId 就取这个；未登录时为 null，由页面引导去登录。 */
export const currentUserId = computed(() => state.user?.userId || null);
