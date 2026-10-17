<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue';
import { getCollectStats, getCollectEvents } from '../api';
import { tracker, track } from '../track';
import { formatTime, humanMessage } from '../utils';
import { toast } from '../stores/ui';
import { session } from '../stores/session';

const stats = ref(null);
const events = ref([]);
const error = ref('');
const loading = ref(true);
const auto = ref(true);
const snap = ref(tracker.snapshot());
const typeFilter = ref('');
let timer = null;

const CARDS = [
    { key: 'click', label: '点击 click', color: '#e1251b' },
    { key: 'expose', label: '曝光 expose', color: '#ff7600' },
    { key: 'pv', label: '页面 pv', color: '#3f6fa8' },
    { key: 'input', label: '输入 input', color: '#2aa512' },
    { key: 'biz', label: '业务 biz', color: '#7a4fd0' },
    { key: 'perf', label: '性能 perf', color: '#0f9e9e' },
    { key: 'error', label: '错误 error', color: '#c8161d' }
];

const totals = computed(() => {
    const s = snap.value;
    return {
        queued: s.queued,
        sent: s.sent,
        failed: s.failed,
        dropped: s.dropped,
        beacons: s.beacons,
        retried: s.retried,
        pending: s.pending,
        deadLetter: s.deadLetter
    };
});

const bars = computed(() => {
    const map = stats.value?.countByType || {};
    const max = Math.max(1, ...Object.values(map));
    return CARDS.map(c => ({
        ...c,
        value: map[c.key] || 0,
        ratio: Math.round(((map[c.key] || 0) / max) * 100)
    }));
});

const topTargets = computed(() => Object.entries(stats.value?.countByTarget || {})
    .sort((a, b) => b[1] - a[1])
    .slice(0, 12));

const topPages = computed(() => Object.entries(stats.value?.countByPage || {})
    .sort((a, b) => b[1] - a[1])
    .slice(0, 8));

const visibleEvents = computed(() =>
    typeFilter.value ? events.value.filter(e => e.type === typeFilter.value) : events.value
);

async function load() {
    error.value = '';
    const [s, e] = await Promise.all([getCollectStats(), getCollectEvents(120)]);
    if (s?.success) stats.value = s.data;
    else error.value = humanMessage(s?.message);
    if (e?.success) events.value = Array.isArray(e.data) ? e.data : [];
    snap.value = tracker.snapshot();
    loading.value = false;
}

async function flushNow() {
    await tracker.flush();
    snap.value = tracker.snapshot();
    toast('已触发一次上报');
    await load();
}

function replay() {
    // 造一批事件走完整 SDK 管道（含批量、重试、死信），用来验证 SDK 本身
    for (let i = 0; i < 25; i++) {
        track('biz', 'sdk-replay', { index: i, at: Date.now() });
    }
    setTimeout(async () => {
        await tracker.flush();
        await load();
        toast('已注入 25 条测试事件并上报', 'ok');
    }, 300);
}

function stopAuto() {
    if (timer) {
        clearInterval(timer);
        timer = null;
    }
}

onMounted(() => {
    load();
    timer = setInterval(() => {
        if (auto.value && document.visibilityState === 'visible') load();
        else snap.value = tracker.snapshot();
    }, 3000);
});

onBeforeUnmount(stopAuto);
</script>

<template>
    <div>
        <div class="gm-breadcrumb"><router-link to="/">首页</router-link> &nbsp;&gt;&nbsp; <b>埋点看板</b></div>

        <div class="gm-alert error" v-if="error" style="margin-bottom:12px">{{ error }}</div>

        <div class="gm-panel">
            <div class="gm-panel-head">
                <span>前端 SDK（本站）</span>
                <div class="gm-row">
                    <button class="gm-btn sm primary" data-track="track-flush" @click="flushNow">立即上报</button>
                    <button class="gm-btn sm" data-track="track-replay" @click="replay">注入 25 条测试事件</button>
                    <label class="sw">
                        <input type="checkbox" v-model="auto" data-track-input="track-auto" /> 3 秒自动刷新
                    </label>
                </div>
            </div>
            <div class="gm-panel-body sdk">
                <div class="kpi">
                    <div><span>已入队</span><b>{{ totals.queued }}</b></div>
                    <div><span>已上报</span><b class="ok">{{ totals.sent }}</b></div>
                    <div><span>待发送</span><b>{{ totals.pending }}</b></div>
                    <div><span>失败</span><b class="err">{{ totals.failed }}</b></div>
                    <div><span>死信待重试</span><b class="warn">{{ totals.deadLetter }}</b></div>
                    <div><span>已重试</span><b>{{ totals.retried }}</b></div>
                    <div><span>Beacon 兜底</span><b>{{ totals.beacons }}</b></div>
                    <div><span>丢弃（超限/采样）</span><b>{{ totals.dropped }}</b></div>
                </div>
                <div class="ids">
                    <div>sessionId <code>{{ snap.sessionId }}</code></div>
                    <div>visitorId <code>{{ snap.visitorId }}</code></div>
                    <div>userId <code>{{ snap.userId || session.user?.userId || '(匿名)' }}</code></div>
                    <div>当前页 <code>{{ snap.page }}</code></div>
                </div>
                <p class="note">
                    配置：批量 {{ snap.config.batchSize }} 条 / {{ snap.config.flushInterval }} ms 定时，队列上限 {{ snap.config.maxQueue }}，
                    采样率 {{ snap.config.sampleRate }}，曝光阈值 {{ Math.round(snap.config.exposeRatio * 100) }}% 停留 {{ snap.config.exposeDuration }}ms。
                    控制台里 <code>window.__gmTrack.snapshot()</code> 可即时核对。
                </p>
            </div>
        </div>

        <div class="gm-panel">
            <div class="gm-panel-head">
                <span>网关接收侧（/api/collect）</span>
                <span class="gray" v-if="stats">环形缓冲 {{ stats.bufferSize }} / {{ stats.capacity }} · 溢出丢弃 {{ stats.droppedByOverflow }}</span>
            </div>
            <div class="gm-panel-body" v-if="loading">
                <div class="gm-empty">正在拉取统计…</div>
            </div>
            <div class="gm-panel-body" v-else-if="!stats">
                <div class="gm-empty">
                    <strong>网关还没有收到任何埋点</strong>
                    <p>确认网关已在 18080 启动，vite 代理把 /api/collect 转到了它。</p>
                </div>
            </div>
            <template v-else>
                <div class="gm-panel-body server">
                    <div class="kpi">
                        <div><span>累计批次</span><b>{{ stats.totalBatches }}</b></div>
                        <div><span>累计事件</span><b class="ok">{{ stats.totalEvents }}</b></div>
                        <div><span>缓冲中</span><b>{{ stats.bufferSize }}</b></div>
                        <div><span>溢出丢弃</span><b class="err">{{ stats.droppedByOverflow }}</b></div>
                    </div>
                    <div class="bars">
                        <div v-for="b in bars" :key="b.key" class="bar-row">
                            <span class="blabel">{{ b.label }}</span>
                            <span class="btrack"><i :style="{ width: b.ratio + '%', background: b.color }"></i></span>
                            <span class="bval">{{ b.value }}</span>
                        </div>
                    </div>
                    <div class="sincetime gray">统计起点 {{ formatTime(stats.sinceEpochMillis) }}</div>
                </div>
            </template>
        </div>

        <div class="two">
            <div class="gm-panel">
                <div class="gm-panel-head"><span>热门 target Top12</span></div>
                <div class="gm-panel-body rank">
                    <div v-for="(t, i) in topTargets" :key="t[0]" class="rank-row">
                        <span class="idx" :class="{ hot: i < 3 }">{{ i + 1 }}</span>
                        <span class="rname">{{ t[0] }}</span>
                        <span class="rval">{{ t[1] }}</span>
                    </div>
                    <div class="gray" v-if="!topTargets.length">暂无数据</div>
                </div>
            </div>
            <div class="gm-panel">
                <div class="gm-panel-head"><span>页面分布</span></div>
                <div class="gm-panel-body rank">
                    <div v-for="(p, i) in topPages" :key="p[0]" class="rank-row">
                        <span class="idx" :class="{ hot: i < 3 }">{{ i + 1 }}</span>
                        <span class="rname">{{ p[0] }}</span>
                        <span class="rval">{{ p[1] }}</span>
                    </div>
                    <div class="gray" v-if="!topPages.length">暂无数据</div>
                </div>
            </div>
        </div>

        <div class="gm-panel">
            <div class="gm-panel-head">
                <span>最近事件（网关内存，最新在前）</span>
                <div class="gm-row">
                    <select class="gm-input sel" v-model="typeFilter" data-track-input="track-filter">
                        <option value="">全部类型</option>
                        <option v-for="c in CARDS" :key="c.key" :value="c.key">{{ c.label }}</option>
                    </select>
                    <button class="gm-btn sm" data-track="track-reload" @click="load">刷新</button>
                </div>
            </div>
            <table class="gm-table" v-if="visibleEvents.length">
                <thead>
                <tr>
                    <th style="width:78px">类型</th>
                    <th style="width:190px">target</th>
                    <th style="width:110px">页面</th>
                    <th style="width:96px">用户</th>
                    <th>payload</th>
                    <th style="width:150px">时间</th>
                </tr>
                </thead>
                <tbody>
                <tr v-for="e in visibleEvents" :key="e.eventId">
                    <td><span class="pill" :style="{ background: (CARDS.find(c => c.key === e.type) || {}).color || '#888' }">{{ e.type }}</span></td>
                    <td class="mono">{{ e.target }}</td>
                    <td>{{ e.page }}</td>
                    <td class="mono">{{ e.userId || '-' }}</td>
                    <td class="pay ellipsis">{{ JSON.stringify(e.payload) }}</td>
                    <td class="gray">{{ formatTime(e.clientTs) }}</td>
                </tr>
                </tbody>
            </table>
            <div class="gm-empty" v-else>
                <strong>暂无事件</strong>
                <p>在站点里点几下、翻几页，再回来看这张表。</p>
            </div>
        </div>
    </div>
</template>

<style scoped>
.gray {
    font-size: 12px;
    color: var(--gm-text-light);
    font-weight: 400;
}

.sw {
    font-size: 12px;
    color: var(--gm-text-weak);
    display: flex;
    align-items: center;
    gap: 6px;
    cursor: pointer;
}

.kpi {
    display: grid;
    grid-template-columns: repeat(8, 1fr);
    gap: 8px;
    margin-bottom: 12px;
}

.server .kpi {
    grid-template-columns: repeat(4, 1fr);
}

.kpi div {
    background: #fafafa;
    border: 1px solid var(--gm-border);
    border-radius: var(--gm-radius);
    padding: 10px 12px;
}

.kpi span {
    display: block;
    font-size: 12px;
    color: var(--gm-text-light);
}

.kpi b {
    font-size: 22px;
    font-weight: 700;
}

.kpi .ok {
    color: #1c7a35;
}

.kpi .err {
    color: var(--gm-red);
}

.kpi .warn {
    color: var(--gm-orange);
}

.ids {
    display: flex;
    flex-wrap: wrap;
    gap: 18px;
    font-size: 12px;
    color: var(--gm-text-weak);
}

code {
    background: #f1f1f1;
    padding: 1px 5px;
    border-radius: 2px;
    font-family: ui-monospace, Consolas, monospace;
    font-size: 12px;
}

.note {
    margin-top: 10px;
    font-size: 12px;
    color: var(--gm-text-light);
    line-height: 1.8;
}

.bars {
    display: flex;
    flex-direction: column;
    gap: 6px;
    margin: 10px 0;
}

.bar-row {
    display: flex;
    align-items: center;
    gap: 10px;
}

.blabel {
    width: 120px;
    flex: none;
    font-size: 12px;
    color: var(--gm-text-weak);
}

.btrack {
    flex: 1;
    height: 12px;
    background: #f0f0f0;
    border-radius: 6px;
    overflow: hidden;
}

.btrack i {
    display: block;
    height: 100%;
    border-radius: 6px;
    transition: width 0.4s;
}

.bval {
    width: 60px;
    text-align: right;
    font-size: 13px;
}

.two {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 12px;
}

.rank-row {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 5px 0;
    border-bottom: 1px dashed var(--gm-border);
    font-size: 13px;
}

.idx {
    width: 20px;
    height: 20px;
    line-height: 20px;
    text-align: center;
    border-radius: 4px;
    background: #eee;
    color: var(--gm-text-light);
    font-size: 12px;
}

.idx.hot {
    background: var(--gm-red);
    color: #fff;
}

.rname {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.rval {
    color: var(--gm-red);
}

.sel {
    width: 150px;
    height: 28px;
}

.pill {
    display: inline-block;
    padding: 1px 7px;
    border-radius: 9px;
    color: #fff;
    font-size: 11px;
}

.mono {
    font-family: ui-monospace, Consolas, monospace;
    font-size: 12px;
}

.pay {
    font-size: 12px;
    color: var(--gm-text-weak);
}

.ellipsis {
    max-width: 420px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

p {
    font-size: 13px;
    color: var(--gm-text-light);
    margin-bottom: 12px;
}
</style>
