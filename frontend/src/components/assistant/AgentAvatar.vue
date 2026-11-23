<script setup lang="ts">
defineProps<{ agentId: string; active?: boolean }>()
</script>

<template>
  <span aria-hidden="true" class="agent-avatar" :data-role="agentId" :class="{ working: active }">
    <span class="portrait"></span>
  </span>
</template>

<style scoped>
.agent-avatar {
  --x: 0%; --y: 0%; --ink: #8061ad;
  position: relative; display: inline-grid; flex: none; width: var(--avatar-size, 32px);
  aspect-ratio: 1; border-radius: 50%; vertical-align: middle;
}
.agent-avatar[data-role="ORDER"] { --x: 33.333333%; --ink: #488465; }
.agent-avatar[data-role="SUPPLY"] { --x: 66.666667%; --ink: #b16436; }
.agent-avatar[data-role="PRODUCTION"] { --x: 100%; --ink: #aa791c; }
.agent-avatar[data-role="QUALITY"] { --y: 100%; --ink: #2a8e93; }
.agent-avatar[data-role="EQUIPMENT"] { --x: 33.333333%; --y: 100%; --ink: #497cbb; }
.agent-avatar[data-role="AFTERSALES"] { --x: 66.666667%; --y: 100%; --ink: #b26782; }
.portrait {
  width: 100%; height: 100%; border-radius: inherit;
  background: url('../../assets/images/assistant-portraits.webp') var(--x) var(--y) / 400% 200% no-repeat;
}
.working::before, .working::after {
  content: ''; position: absolute; pointer-events: none; border-radius: inherit;
}
.working::before {
  inset: -3px; border: 2px solid transparent; border-top-color: var(--ink); border-bottom-color: var(--ink);
  animation: agent-orbit 1.6s linear infinite;
}
.working::after {
  inset: -4px; border: 1px solid var(--ink); animation: agent-wave 2.4s ease-out infinite;
}
@keyframes agent-orbit { to { transform: rotate(360deg); } }
@keyframes agent-wave { from { transform: scale(.94); opacity: .44; } to { transform: scale(1.23); opacity: 0; } }
@media (prefers-reduced-motion: reduce) {
  .working::before { animation: none; border: 1px solid var(--ink); }
  .working::after { display: none; }
}
</style>
