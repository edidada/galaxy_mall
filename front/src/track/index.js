/**
 * Galaxy Mall 前端行为采集 SDK
 * ===================================================================
 * 目标：把"用户在 PC 商城页上做了什么"这条链路真实跑通，
 * 而不是只做一个 console.log 的假埋点。所以它是自包含的一层，
 * 业务组件只需要写 `data-track="xxx"` 就能上报，不需要每个页面 import。
 *
 * 采集面：
 *   click   —— document 捕获阶段事件委托，组件零成本接入
 *   expose  —— IntersectionObserver 元素曝光（卡片/广告位进入视口且面积达标）
 *   pv      —— 路由切换（afterEach）
 *   input   —— 带 data-track-input 的表单控件 change（搜索词、数量）
 *   error   —— window error / unhandledrejection
 *   perf    —— PerformanceObserver 的 paint / LCP / longtask
 *   biz     —— 业务手动调用 track('biz', 'order-submitted', {...})
 *
 * 上报：
 *   按批发送（默认 20 条或 2s），POST /api/collect；
 *   页面隐藏/卸载用 navigator.sendBeacon 兜底（fetch 在 unload 阶段会被取消）；
 *   网络失败或后端返回 accepted < received 时，整批进 localStorage 死信队列，下次 flush 优先带出。
 *
 * 刻意的设计取舍：
 *   1. 采样率默认 1（全量）。本地演示量级很小，采样只会让看板对不上数；
 *      真上线要按 type 分级：error/biz 不采、perf 采 1%。
 *   2. eventId 由客户端生成并随事件上报，服务端按批入环形缓冲，
 *      所以重试带来的重复只影响"条数"，不影响按 target 维度的相对排序。
 *   3. 不入库、不建索引：网关侧的接收端是内存环形缓冲（见 CollectService 注释）。
 */
import { collectEvents, apiUrl } from '../api';

const LS_SESSION = 'gm_track_session';
const LS_VISITOR = 'gm_track_visitor';
const LS_DEADLETTER = 'gm_track_deadletter';

const DEFAULTS = {
    endpoint: '/api/collect',
    batchSize: 20,
    flushInterval: 2000,
    maxQueue: 500,
    maxDeadLetter: 200,
    sampleRate: 1,
    exposeRatio: 0.5,
    exposeDuration: 400,
    debug: false
};

function uuid() {
    const b = new Uint8Array(16);
    crypto.getRandomValues(b);
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    const h = Array.from(b, x => x.toString(16).padStart(2, '0'));
    return `${h[0]}${h[1]}${h[2]}${h[3]}-${h[4]}${h[5]}-${h[6]}${h[7]}-${h[8]}${h[9]}-${h[10]}${h[11]}${h[12]}${h[13]}${h[14]}${h[15]}`;
}

function readLS(key, create) {
    try {
        const cached = localStorage.getItem(key);
        if (cached) return cached;
        const fresh = create();
        localStorage.setItem(key, fresh);
        return fresh;
    } catch {
        return create();
    }
}

/**
 * 从被点击元素向上找语义标识：
 * 优先 data-track；没有就用 data-testid / id / aria-label / 按钮文案，
 * 再没有就退回标签名，保证"任何一次点击都至少有 type+page"。
 */
function resolveTarget(el) {
    // 表单控件本身没有 data-track 时，先认它的 data-track-input 语义名，
    // 否则 closest 会一路爬到 #app，看板上会出现一堆分不清的 "#app"。
    const node = el.closest?.('[data-track],[data-track-input],[data-testid],button,a,input,select,textarea,[role="button"],[id]') || el;
    if (node?.dataset?.track) return node.dataset.track;
    if (node?.dataset?.trackInput) return node.dataset.trackInput;
    if (node?.dataset?.testid) return node.dataset.testid;
    if (node?.id) return `#${node.id}`;
    if (node?.getAttribute?.('aria-label')) return node.getAttribute('aria-label');
    const text = (node?.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 20);
    if (text && node?.tagName !== 'BODY') return `${node.tagName.toLowerCase()}:${text}`;
    return node?.tagName ? node.tagName.toLowerCase() : 'unknown';
}

/** data-track-sku="x" -> payload.sku，业务维度全靠声明式属性携带。 */
function resolvePayload(el) {
    const payload = {};
    const node = el.closest?.('[data-track]') || el;
    Object.entries(node?.dataset || {}).forEach(([key, value]) => {
        if (key === 'track') return;
        if (key.startsWith('track')) {
            const name = key.slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase());
            payload[name.charAt(0).toLowerCase() + name.slice(1)] = value;
        }
    });
    const tag = node?.tagName;
    if (tag === 'A' && node.getAttribute('href')) payload.href = node.getAttribute('href');
    return payload;
}

function createTracker(userConfig = {}) {
    const config = { ...DEFAULTS, ...userConfig };
    const sessionId = readLS(LS_SESSION, uuid);
    const visitorId = readLS(LS_VISITOR, uuid);
    let userId = '';
    let page = 'unknown';
    let queue = [];
    let timer = null;
    let observer = null;
    const exposedOnce = new WeakSet();
    const stats = { queued: 0, sent: 0, failed: 0, dropped: 0, beacons: 0, retried: 0, startedAt: Date.now() };

    function log(...args) {
        if (config.debug) console.info('[gm-track]', ...args);
    }

    function setPage(name) {
        page = name || 'unknown';
    }

    function setUser(id) {
        userId = id || '';
    }

    function track(type, target, payload = {}, extra = {}) {
        if (Math.random() > config.sampleRate) {
            stats.dropped++;
            return null;
        }
        const event = {
            eventId: uuid(),
            type,
            page,
            target: target || 'unknown',
            userId,
            sessionId,
            traceId: extra.traceId || null,
            clientTs: Date.now(),
            payload: { visitorId, ...payload, ...extra }
        };
        queue.push(event);
        stats.queued++;
        if (queue.length > config.maxQueue) {
            // 队列超限先丢最旧的，保证刚刚发生的行为一定还在待上报集合里
            queue.splice(0, queue.length - config.maxQueue);
            stats.dropped++;
        }
        if (queue.length >= config.batchSize) {
            flush();
        } else {
            scheduleFlush();
        }
        log('enqueue', type, event.target);
        return event;
    }

    function scheduleFlush() {
        if (timer) return;
        timer = setTimeout(() => {
            timer = null;
            flush();
        }, config.flushInterval);
    }

    function readDeadLetter() {
        try {
            const raw = localStorage.getItem(LS_DEADLETTER);
            return raw ? JSON.parse(raw) : [];
        } catch {
            return [];
        }
    }

    function writeDeadLetter(events) {
        try {
            localStorage.setItem(LS_DEADLETTER, JSON.stringify(events.slice(-config.maxDeadLetter)));
        } catch {
            /* 隐私模式或配额满：埋点不该因为存不下而抛错 */
        }
    }

    /** 死信优先带出，避免用户行为被无限期压在本地。 */
    function takeBatch(capacity = config.batchSize) {
        const pending = readDeadLetter();
        if (pending.length) {
            const fromDead = pending.slice(0, capacity);
            writeDeadLetter(pending.slice(capacity));
            stats.retried += fromDead.length;
            const rest = capacity - fromDead.length;
            return [...fromDead, ...queue.splice(0, rest)];
        }
        return queue.splice(0, capacity);
    }

    function buildBatch(events) {
        return {
            sessionId,
            userId,
            page,
            sentAt: Date.now(),
            events
        };
    }

    /**
     * @param {boolean} useBeacon 页面卸载路径：sendBeacon 是唯一能保证发出去的 API，
     *        但它拿不到响应，所以这条路径不做失败重试（事件已在内存队列里丢失即丢失）。
     */
    function flush(useBeacon = false) {
        const events = takeBatch();
        if (!events.length) return Promise.resolve({ skipped: true });
        if (timer) {
            clearTimeout(timer);
            timer = null;
        }
        const batch = buildBatch(events);

        if (useBeacon && navigator.sendBeacon) {
            const ok = navigator.sendBeacon(apiUrl(config.endpoint), new Blob([JSON.stringify(batch)], { type: 'application/json' }));
            if (ok) {
                stats.beacons += events.length;
                stats.sent += events.length;
                return Promise.resolve({ beacon: true });
            }
            // sendBeacon 入队失败（离线/配额），退回死信而不是丢弃
            pushDeadLetter(events);
            stats.failed++;
            return Promise.resolve({ beacon: false });
        }

        return collectEvents(batch).then(res => {
            if (res && res.success) {
                stats.sent += events.length;
                const accepted = res.data?.accepted ?? events.length;
                if (accepted < events.length) pushDeadLetter(events.slice(accepted));
            } else {
                stats.failed++;
                pushDeadLetter(events);
                log('flush failed', res && res.message);
            }
            return res;
        }).catch(e => {
            stats.failed++;
            pushDeadLetter(events);
            log('flush error', e.message);
        });
    }

    function pushDeadLetter(events) {
        writeDeadLetter([...readDeadLetter(), ...events]);
    }

    function snapshot() {
        return {
            ...stats,
            pending: queue.length,
            deadLetter: readDeadLetter().length,
            sessionId,
            visitorId,
            userId,
            page,
            config
        };
    }

    // ---------- 各类采集器 ----------

    function bindClicks() {
        // 捕获阶段：业务组件里的 @click.stop() 会阻断冒泡，埋点不能被它挡掉
        document.addEventListener('click', e => {
            if (!e.target) return;
            track('click', resolveTarget(e.target), resolvePayload(e.target), {
                x: e.clientX,
                y: e.clientY
            });
        }, true);
    }

    function bindInput() {
        document.addEventListener('change', e => {
            const node = e.target?.closest?.('[data-track-input]');
            if (!node) return;
            track('input', resolveTarget(node), {
                ...resolvePayload(node),
                value: String(node.value ?? '').slice(0, 64)
            });
        }, true);
    }

    function bindExpose() {
        if (!('IntersectionObserver' in window)) return;
        observer = new IntersectionObserver(entries => {
            entries.forEach(entry => {
                if (!entry.isIntersecting || entry.intersectionRatio < config.exposeRatio) return;
                const el = entry.target;
                if (exposedOnce.has(el)) return;
                exposedOnce.add(el);
                observer.unobserve(el);
                // 曝光要"停留"才算：快速划过的列表项不算一次有效曝光
                setTimeout(() => {
                    if (!document.contains(el)) return;
                    track('expose', el.dataset.trackExpose || resolveTarget(el), resolvePayload(el), {
                        dwellMs: config.exposeDuration
                    });
                }, config.exposeDuration);
            });
        }, { threshold: [0, config.exposeRatio, 1] });
        scanExposables();
    }

    /** 列表/详情页是异步渲染的，DOM 变了要重新挂观察器。 */
    function scanExposables() {
        if (!observer) return;
        document.querySelectorAll('[data-track-expose]').forEach(el => {
            if (!exposedOnce.has(el)) observer.observe(el);
        });
    }

    function bindErrors() {
        window.addEventListener('error', e => {
            track('error', e.target === window ? 'window.onerror' : 'resource.onerror', {
                message: String(e.message || '').slice(0, 200),
                source: e.filename,
                line: e.lineno,
                col: e.colno
            });
        });
        window.addEventListener('unhandledrejection', e => {
            track('error', 'unhandledrejection', {
                message: String(e.reason?.message || e.reason || '').slice(0, 200)
            });
        });
    }

    function bindPerf() {
        if (!('PerformanceObserver' in window)) return;
        const report = (name, value) => track('perf', name, { value: Math.round(value) });
        try {
            const po = new PerformanceObserver(list => {
                list.getEntries().forEach(entry => {
                    if (entry.name === 'first-contentful-paint') report('fcp', entry.startTime);
                    if (entry.name === 'largest-contentful-paint') report('lcp', entry.startTime);
                });
            });
            po.observe({ type: 'paint', buffered: true });
            po.observe({ type: 'largest-contentful-paint', buffered: true });
        } catch {
            /* 老浏览器没有 LCP entry type，忽略即可 */
        }
        try {
            const lo = new PerformanceObserver(list => {
                list.getEntries().forEach(entry => report('longtask', entry.duration));
            });
            lo.observe({ type: 'longtask', buffered: true });
        } catch {
            /* longtask 需要 Chrome 系，Safari 上没有 */
        }
    }

    function bindLifecycle() {
        document.addEventListener('visibilitychange', () => {
            if (document.visibilityState === 'hidden') flush(true);
        });
        window.addEventListener('pagehide', () => flush(true));
    }

    function start() {
        bindClicks();
        bindInput();
        bindExpose();
        bindErrors();
        bindPerf();
        bindLifecycle();
        // DOM 变化后补挂曝光观察器（列表翻页、详情异步返回都会走到这里）
        const mo = new MutationObserver(() => scanExposables());
        mo.observe(document.body, { childList: true, subtree: true });
        scheduleFlush();
        log('started', snapshot());
    }

    return { track, flush, setPage, setUser, snapshot, start, scanExposables, sessionId, visitorId, config };
}

/**
 * 单例导出：页面任意位置 import { track } 即可打点，
 * 全局对象 window.__gmTrack 方便在控制台核对"这个点击到底上报了没"。
 */
export const tracker = createTracker({ debug: import.meta.env.DEV });
export const track = tracker.track;

export function installTracker(app, router) {
    if (router) {
        router.afterEach((to, from) => {
            tracker.setPage(to.name || to.path);
            track('pv', to.name || to.path, {
                path: to.path,
                query: JSON.stringify(to.query || {}),
                from: from?.name || from?.path || 'entry'
            });
            // 路由切换后新渲染的卡片要重新纳入曝光统计
            setTimeout(() => tracker.scanExposables(), 100);
        });
    }
    app.config.globalProperties.$track = track;
    window.__gmTrack = tracker;
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => tracker.start(), { once: true });
    } else {
        tracker.start();
    }
    return tracker;
}

export default tracker;
