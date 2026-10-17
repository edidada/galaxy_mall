<script setup>
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { login, register, session } from '../stores/session';
import { humanMessage } from '../utils';
import { track } from '../track';
import { toast } from '../stores/ui';

const route = useRoute();
const router = useRouter();

const mode = ref(route.query.mode === 'register' ? 'register' : 'login');
const username = ref('');
const password = ref('');
const nickname = ref('');
const agree = ref(true);
const busy = ref(false);
const error = ref('');

// 演示账号来自 user 服务的播种数据（UserServiceApplication 里 bindDefaultPassword 那批）
const DEMO = [
    { login: 'andromeda', userId: 'u100', level: '黄金会员' },
    { login: 'orion', userId: 'u101', level: '白银会员' },
    { login: 'nova', userId: 'u200', level: '普通会员' }
];

const canSubmit = computed(() =>
    username.value.trim().length >= 3 && password.value.length >= 6 && (mode.value === 'login' || agree.value)
);

function switchMode(next) {
    mode.value = next;
    error.value = '';
    track('click', `login-switch-${next}`, {});
}

function useDemo(d) {
    username.value = d.login;
    password.value = '123456';
    error.value = '';
}

async function submit() {
    if (!canSubmit.value || busy.value) return;
    busy.value = true;
    error.value = '';
    const res = mode.value === 'login'
        ? await login(username.value.trim(), password.value)
        : await register(username.value.trim(), password.value, nickname.value.trim());
    busy.value = false;
    if (!res.ok) {
        error.value = humanMessage(res.reason);
        return;
    }
    toast(mode.value === 'login' ? `欢迎回来，${res.user.nickname}` : `注册成功，已登录为 ${res.user.nickname}`, 'ok');
    track('biz', mode.value === 'login' ? 'login-done' : 'register-done', { userId: res.user.userId });
    // 登录后回到来源页；来源是登录页本身（或直接进登录页）时回首页
    const redirect = route.query.redirect;
    router.replace(redirect && !String(redirect).startsWith('/login') ? String(redirect) : '/');
}

onMounted(() => {
    if (session.status === 'authed') router.replace('/');
});
</script>

<template>
    <div class="login-wrap">
        <div class="gm-card login-card">
            <div class="tabs">
                <button :class="{ on: mode === 'login' }" data-track="login-tab-login" @click="switchMode('login')">京东用户登录</button>
                <button :class="{ on: mode === 'register' }" data-track="login-tab-register" @click="switchMode('register')">免费注册</button>
            </div>

            <div class="gm-panel-body">
                <div class="gm-alert error" v-if="error" style="margin-bottom:12px">{{ error }}</div>

                <div class="gm-field">
                    <label>{{ mode === 'login' ? '用户名 / 登录名' : '用户名（至少 3 位，将作为登录名）' }}</label>
                    <input class="gm-input" v-model="username" placeholder="例如 andromeda" autocomplete="username"
                           data-track-input="login-username" @keyup.enter="submit" />
                </div>

                <div class="gm-field">
                    <label>密码</label>
                    <input class="gm-input" v-model="password" type="password" :placeholder="mode === 'login' ? '演示账号默认密码 123456' : '至少 6 位'"
                           autocomplete="current-password" data-track-input="login-password" @keyup.enter="submit" />
                </div>

                <div class="gm-field" v-if="mode === 'register'">
                    <label>昵称（选填）</label>
                    <input class="gm-input" v-model="nickname" placeholder="不填则与用户名相同" data-track-input="login-nickname" />
                </div>

                <label class="agree" v-if="mode === 'register'">
                    <input type="checkbox" v-model="agree" data-track-input="login-agree" />
                    <span>我已阅读并同意《Galaxy Mall 演示协议》，明白本站不涉及真实交易</span>
                </label>

                <button class="gm-btn primary lg submit" :disabled="!canSubmit || busy" data-track="login-submit" :data-track-mode="mode" @click="submit">
                    {{ busy ? '处理中…' : (mode === 'login' ? '立即登录' : '注册并登录') }}
                </button>

                <p class="note">
                    令牌由 user 服务签发（内存会话，12 小时有效），保存在浏览器 localStorage；
                    之后所有请求自动带 <code>X-Auth-Token</code>，购物车与下单用它识别用户。
                </p>
            </div>
        </div>

        <aside class="side">
            <div class="gm-panel">
                <div class="gm-panel-head"><span>演示账号</span></div>
                <div class="gm-panel-body">
                    <table class="gm-table demo-table">
                        <thead>
                        <tr><th>登录名</th><th>对应 userId</th><th>等级</th><th></th></tr>
                        </thead>
                        <tbody>
                        <tr v-for="d in DEMO" :key="d.login">
                            <td><code>{{ d.login }}</code></td>
                            <td>{{ d.userId }}</td>
                            <td>{{ d.level }}</td>
                            <td>
                                <button class="gm-btn sm" data-track="login-use-demo" :data-track-account="d.login" @click="useDemo(d)">一键填入</button>
                            </td>
                        </tr>
                        </tbody>
                    </table>
                    <p class="note">口令统一 <code>123456</code>。也可以自己注册一个新账号 —— 注册会真实写入 user 服务的用户表，
                        所以新账号能正常下单，不会被网关的 <code>user-not-found</code> 挡掉。</p>
                </div>
            </div>
            <div class="gm-panel">
                <div class="gm-panel-head"><span>为什么登录是"演示级"</span></div>
                <div class="gm-panel-body">
                    <p class="note">口令只存 SHA-256 摘要、不落明文；令牌是随机 UUID 存在进程内存里，服务重启即失效。
                        没有刷新令牌、验证码与风控 —— 它的定位是给商城页和埋点链路一个真实的"已登录用户"，不是安全方案。</p>
                </div>
            </div>
        </aside>
    </div>
</template>

<style scoped>
.login-wrap {
    display: grid;
    grid-template-columns: 400px 1fr;
    gap: 16px;
    align-items: start;
    padding: 24px 0 40px;
}

.login-card {
    overflow: hidden;
}

.tabs {
    display: flex;
    border-bottom: 1px solid var(--gm-border);
}

.tabs button {
    flex: 1;
    height: 46px;
    border: 0;
    background: #f7f7f7;
    color: var(--gm-text-weak);
    font-size: 15px;
    cursor: pointer;
}

.tabs button.on {
    background: #fff;
    color: var(--gm-red);
    font-weight: 600;
}

.submit {
    width: 100%;
    justify-content: center;
    margin-top: 4px;
}

.agree {
    display: flex;
    align-items: flex-start;
    gap: 6px;
    font-size: 12px;
    color: var(--gm-text-light);
    margin-bottom: 12px;
    cursor: pointer;
}

.note {
    font-size: 12px;
    color: var(--gm-text-light);
    line-height: 1.8;
    margin-top: 12px;
}

.note code {
    background: #f5f5f5;
    padding: 0 4px;
    border-radius: 2px;
    color: var(--gm-red-dark);
}

.demo-table td {
    padding: 8px 6px;
}

.side {
    min-width: 0;
}
</style>
