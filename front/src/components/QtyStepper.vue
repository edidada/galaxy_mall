<script setup>
const props = defineProps({
    modelValue: { type: Number, required: true },
    min: { type: Number, default: 1 },
    max: { type: Number, default: 99 },
    disabled: { type: Boolean, default: false }
});
const emit = defineEmits(['update:modelValue']);

function clamp(value) {
    return Math.max(props.min, Math.min(props.max, Number(value) || props.min));
}

function step(delta) {
    emit('update:modelValue', clamp(props.modelValue + delta));
}
</script>

<template>
    <span class="gm-stepper">
        <button type="button" :disabled="disabled || modelValue <= min" data-track="qty-minus" @click="step(-1)">－</button>
        <input
            :value="modelValue"
            :disabled="disabled"
            aria-label="购买数量"
            data-track-input="qty-input"
            @change="$emit('update:modelValue', clamp($event.target.value))"
        />
        <button type="button" :disabled="disabled || modelValue >= max" data-track="qty-plus" @click="step(1)">＋</button>
    </span>
</template>
