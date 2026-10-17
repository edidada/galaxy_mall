/** 轻量 toast：全站一个队列，避免每个页面各自实现一份提示动画。 */
import { reactive } from 'vue';

const state = reactive({ items: [] });
let seq = 0;

export function toast(message, kind = '', duration = 2600) {
    const id = ++seq;
    state.items.push({ id, message, kind });
    setTimeout(() => {
        const index = state.items.findIndex(item => item.id === id);
        if (index >= 0) state.items.splice(index, 1);
    }, duration);
    return id;
}

export const toasts = state;
