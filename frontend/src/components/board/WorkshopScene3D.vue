<template>
  <div class="workshop-scene">
    <div
      class="workshop-scene__viewport"
      :class="{ 'is-dragging': draggedOperator }"
      @dragover.prevent="handleDragOver"
      @dragleave.self="handleDragLeave"
      @drop="handleDrop"
    >
      <div ref="containerRef" class="workshop-scene__canvas" />

      <Transition name="tour-track">
        <div v-if="tourState.active" class="workshop-scene__tour">
          <div class="workshop-scene__tour-head">
            <span>产线巡航</span>
            <strong v-if="tourState.phase === 'settling'">全线通关完成 · 正在进入总览</strong>
            <strong v-else>{{ tourState.from?.name || '产线起点' }} <i>→</i> {{ tourState.to?.name || '下一车间' }}</strong>
            <em>{{ Math.round((tourState.progress || 0) * 100) }}%</em>
          </div>
          <div class="workshop-scene__tour-line">
            <div class="workshop-scene__tour-fill" :style="{ width: `${(tourState.progress || 0) * 100}%` }" />
            <span
              v-for="(stop, index) in tourState.stops"
              :key="stop.key"
              :title="`${stop.stageName} · ${stop.name}`"
              :class="{
                'is-passed': index < tourState.currentIndex,
                'is-current': index === tourState.currentIndex
              }"
              :style="{ left: `${index / Math.max(1, tourState.stops.length - 1) * 100}%` }"
            />
          </div>
          <div class="workshop-scene__tour-stage">{{ tourState.from?.stageName || '生产流程' }}</div>
        </div>
      </Transition>

      <div v-if="draggedOperator" class="workshop-scene__drag-hud" :class="{ 'is-valid': dragTarget?.valid, 'is-invalid': dragTarget && !dragTarget.valid }">
        <img :src="avatarFor(draggedOperator)" alt="">
        <div>
          <strong>{{ draggedOperator.displayName }}</strong>
          <span v-if="dragTarget">{{ dragTarget.valid ? `松开派工到 ${dragTarget.workshop.name}` : dragTarget.reason }}</span>
          <span v-else>移动到目标车间，系统将自动吸附识别</span>
        </div>
      </div>

      <Transition name="dispatch-toast">
        <div v-if="dropFeedback" class="workshop-scene__dispatch-toast">{{ dropFeedback }}</div>
      </Transition>

      <div v-if="hoverWorkshop && !selectedWorkshop" class="workshop-scene__hover">
        <strong>{{ hoverWorkshop.name }}</strong>
        <span>{{ hoverWorkshop.taskTitle || hoverWorkshop.parentStepName }}</span>
        <small>{{ hoverWorkshop.progressLabel || `进度 ${hoverWorkshop.progress || 0}%` }}</small>
      </div>
    </div>

    <aside v-if="selectedWorkshop" class="workshop-scene__panel">
        <button class="workshop-scene__close" type="button" title="关闭并恢复全景" @click="closeDetail">×</button>
        <div class="workshop-scene__panel-kicker">车间与现场人员</div>
        <h3>{{ selectedWorkshop.name }}</h3>
        <p class="workshop-scene__task">{{ selectedWorkshop.taskTitle || selectedWorkshop.parentStepName }}</p>
        <div class="workshop-scene__metrics">
          <div><strong>{{ selectedWorkshop.progress || 0 }}%</strong><span>生产进度</span></div>
          <div><strong>{{ selectedWorkshop.running || 0 }}/{{ selectedWorkshop.total || 0 }}</strong><span>运行设备</span></div>
        </div>
        <div class="workshop-scene__progress"><span :style="{ width: `${selectedWorkshop.progress || 0}%` }" /></div>
        <dl class="workshop-scene__facts">
          <div><dt>生产状态</dt><dd>{{ statusLabel(selectedWorkshop.status) }}</dd></div>
          <div><dt>当前工单</dt><dd>{{ selectedWorkshop.workOrderNo || '暂无' }}</dd></div>
          <div><dt>计划数量</dt><dd>{{ selectedWorkshop.plannedQty || 0 }} 台</dd></div>
          <div><dt>完成数量</dt><dd>{{ selectedWorkshop.completedQty || 0 }} 台</dd></div>
        </dl>
        <div v-if="selectedWorkshop.pendingAssignments?.length" class="workshop-scene__section-title">待接收人员</div>
        <div v-if="selectedWorkshop.pendingAssignments?.length" class="workshop-scene__people">
          <article v-for="assignment in selectedWorkshop.pendingAssignments" :key="assignment.id">
            <img :src="avatarFor({ username: assignment.operator, displayName: assignment.operatorName })" alt="操作员头像">
            <div>
              <strong>{{ assignment.operatorName || OPERATOR_DISPLAY_NAMES[assignment.operator] || assignment.operator }}</strong>
              <span>{{ assignment.processStep || selectedWorkshop.parentStepName }} · 待接收</span>
              <small>{{ assignment.workOrderNo || assignment.workOrderId || '暂无工单号' }}</small>
            </div>
            <button
              type="button"
              :disabled="cancellingDispatchId === assignment.id"
              @click="cancelDispatch({ dispatchId: assignment.id, displayName: assignment.operatorName || assignment.operator, dispatch: assignment })"
            >{{ cancellingDispatchId === assignment.id ? '取消中' : '取消派工' }}</button>
          </article>
        </div>
        <div class="workshop-scene__section-title">现场人员</div>
        <div v-if="selectedWorkshop.productionOperators?.length" class="workshop-scene__people">
          <article v-for="op in selectedWorkshop.productionOperators" :key="op.dispatchId || op.username">
            <img :src="avatarFor(op)" alt="操作员头像">
            <div>
              <strong>{{ op.displayName }}</strong>
              <span>{{ op.dispatch?.processStep || selectedWorkshop.parentStepName }} · {{ op.dispatch?.status || '现场作业' }}</span>
              <small>{{ op.dispatch?.workOrderNo || op.dispatch?.workOrderId || '暂无工单号' }}</small>
            </div>
            <button
              type="button"
              :disabled="cancellingDispatchId === op.dispatchId"
              @click="cancelDispatch(op)"
            >{{ cancellingDispatchId === op.dispatchId ? '取消中' : '取消派工' }}</button>
          </article>
        </div>
        <div v-else class="workshop-scene__empty">当前没有正在生产的操作员，可从底部拖入人员派工</div>
    </aside>

    <section class="workshop-scene__dock">
      <div class="workshop-scene__cards">
        <article
          v-for="op in operatorCards"
          :key="op.username"
          class="workshop-scene__card"
          :class="{ 'is-busy': op.dispatch, 'is-locked': tourState.active }"
          :draggable="!op.dispatch && !tourState.active"
          @dragstart="startDrag(op, $event)"
          @dragend="finishDrag"
        >
          <img :src="avatarFor(op)" alt="操作员头像">
          <div>
            <strong>{{ op.displayName }}</strong>
            <span>{{ op.dispatch?.processStep || op.stageName }}</span>
          </div>
          <em>{{ op.dispatch?.status || '可派工' }}</em>
        </article>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { interpret, execute } from '@/api/assistant'
import { createWorkshopScene, mergeWorkshopData } from '@/composables/useWorkshopScene'
import { useMesStore } from '@/stores/mes'
import { useUserStore } from '@/stores/user'
import { OPERATOR_BINDINGS, OPERATOR_DISPLAY_NAMES } from '@/utils/operatorWorkshop'

const emit = defineEmits(['select'])
const props = defineProps({ workshops: { type: Array, default: () => [] } })

const mes = useMesStore()
const userStore = useUserStore()
const containerRef = ref(null)
const selectedKey = ref('')
const selectedSnapshot = ref(null)
const hoverWorkshop = ref(null)
const draggedOperator = ref(null)
const dragTarget = ref(null)
const dropFeedback = ref('')
const cancellingDispatchId = ref('')
const tourState = ref({ active: true, phase: 'touring', progress: 0, currentIndex: 0, stops: [] })
let sceneApi = null
let feedbackTimer = null
let dragGhostEl = null
let dispatchSyncTimer = null
let dispatchSyncBusy = false
let lastSceneSignature = ''

async function refreshDispatchSnapshot() {
  if (dispatchSyncBusy) return
  dispatchSyncBusy = true
  try {
    await mes.hydrateForPage({ force: true })
    syncScene()
  } catch {
    // 保留上一帧数据，下一轮继续同步。
  } finally {
    dispatchSyncBusy = false
  }
}

function handleVisibilitySync() {
  if (!document.hidden) refreshDispatchSnapshot()
}

const ACTIVE_DISPATCH_STATUSES = ['已分配', '已接收', '生产中']

function dispatchForOperator(username) {
  return mes.dispatches.find((item) =>
    item.operator === username && ACTIVE_DISPATCH_STATUSES.includes(item.status)
  ) || null
}

function dispatchesForWorkshop(workshop) {
  return mes.dispatches.filter((item) =>
    ACTIVE_DISPATCH_STATUSES.includes(item.status) &&
    (item.workshopKey === workshop.key || item.workshopName === workshop.name || item.equipment === workshop.name)
  )
}

const displayWorkshops = computed(() => {
  // 一个操作员即使存在多条任务，也只在优先级最高的当前任务车间显示一次。
  const currentOnSiteDispatch = new Map()
  for (const status of ['生产中', '已接收']) {
    mes.dispatches
      .filter((item) => item.operator && item.status === status)
      .forEach((item) => {
        if (!currentOnSiteDispatch.has(item.operator)) currentOnSiteDispatch.set(item.operator, item)
      })
  }

  return mergeWorkshopData(props.workshops).map((workshop) => {
    const assignments = dispatchesForWorkshop(workshop)
    const productionOperators = assignments
      .filter((item) => currentOnSiteDispatch.get(item.operator) === item)
      .map((item) => ({
        username: item.operator,
        displayName: item.operatorName || OPERATOR_DISPLAY_NAMES[item.operator] || item.operator,
        phone: mes.sysUsers.find((user) => user.username === item.operator)?.phone || '',
        dispatchId: item.id,
        dispatch: item
      }))
    const hasOnSiteOperator = productionOperators.length > 0
    const hasAssignment = assignments.length > 0
    return {
      ...workshop,
      status: hasOnSiteOperator ? 'running' : workshop.status,
      running: hasOnSiteOperator ? Math.max(1, workshop.running || 0) : workshop.running,
      dispatchState: hasOnSiteOperator ? 'accepted' : (hasAssignment ? 'assigned' : 'available'),
      productionOperators,
      activeAssignments: assignments,
      pendingAssignments: assignments.filter((item) => item.status === '已分配')
    }
  })
})

const selectedWorkshop = computed(() =>
  displayWorkshops.value.find((item) => item.key === selectedKey.value) || selectedSnapshot.value
)

const operatorCards = computed(() => Object.entries(OPERATOR_BINDINGS).map(([username, binding]) => {
  const user = mes.sysUsers.find((item) => item.username === username)
  return {
    username,
    displayName: user?.realName || OPERATOR_DISPLAY_NAMES[username] || username,
    phone: user?.phone || '',
    ...binding,
    dispatch: dispatchForOperator(username)
  }
}))

const AVATAR_PALETTE = [
  ['#6d879f', '#d8e5ee'], ['#588b83', '#d9ebe7'], ['#8b735d', '#eee3d8'],
  ['#74739a', '#e3e2ef'], ['#6f8a68', '#e1ebde'], ['#9a6f73', '#f0dfe1']
]

function avatarFor(operator) {
  const key = operator?.username || operator?.displayName || 'operator'
  let hash = 0
  for (let i = 0; i < key.length; i++) hash = (hash * 31 + key.charCodeAt(i)) >>> 0
  const [shirt, background] = AVATAR_PALETTE[hash % AVATAR_PALETTE.length]
  const skin = ['#f1c7a5', '#e8b58f', '#dca27f'][hash % 3]
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96"><rect width="96" height="96" rx="48" fill="${background}"/><path d="M18 94c2-22 14-32 30-32s28 10 30 32" fill="${shirt}"/><circle cx="48" cy="42" r="20" fill="${skin}"/><path d="M27 39c1-19 11-27 22-27 13 0 21 10 20 28-7-7-13-10-22-10-8 0-14 3-20 9" fill="#313a45"/><path d="M25 31h46l-5-14H30z" fill="#f4b942"/><path d="M22 31h52v6H22z" rx="3" fill="#f8cc63"/><circle cx="40" cy="43" r="2" fill="#303943"/><circle cx="56" cy="43" r="2" fill="#303943"/><path d="M41 52q7 6 14 0" fill="none" stroke="#9b604f" stroke-width="2" stroke-linecap="round"/></svg>`
  return `data:image/svg+xml;charset=UTF-8,${encodeURIComponent(svg)}`
}

function statusLabel(status) {
  return ({ running: '生产中', pending: '待机', warning: '预警', abnormal: '异常' })[status] || status || '待机'
}

function sceneSignature(workshops) {
  return JSON.stringify(workshops.map((workshop) => ({
    key: workshop.key,
    status: workshop.status,
    dispatchState: workshop.dispatchState,
    progress: workshop.progress,
    running: workshop.running,
    operators: (workshop.productionOperators || []).map((operator) => `${operator.username}:${operator.dispatch?.status}`),
    assignments: (workshop.activeAssignments || []).map((dispatch) => `${dispatch.id}:${dispatch.status}`),
    machines: (workshop.machines || []).map((machine) => machine.status)
  })))
}

function syncScene(force = false) {
  const signature = sceneSignature(displayWorkshops.value)
  if (!force && signature === lastSceneSignature) return
  lastSceneSignature = signature
  sceneApi?.updateWorkshops(displayWorkshops.value, selectedKey.value)
  if (draggedOperator.value) {
    sceneApi?.setDispatchableWorkshops(dispatchableWorkshopKeys(draggedOperator.value))
  }
}

function dispatchableWorkshopKeys(operator) {
  return displayWorkshops.value
    .filter((workshop) => !!findDispatchableWorkOrder(workshop, operator))
    .map((workshop) => workshop.key)
}

function closeDetail() {
  selectedKey.value = ''
  selectedSnapshot.value = null
  hoverWorkshop.value = null
  emit('select', '')
  sceneApi?.restoreOverview()
}

function startDrag(operator, event) {
  if (operator.dispatch || tourState.value.active) {
    event.preventDefault()
    ElMessage.info(operator.dispatch ? '该操作员已有进行中的任务' : '产线巡航结束后即可拖动派工')
    return
  }
  draggedOperator.value = operator
  dragTarget.value = null
  sceneApi?.setDispatchableWorkshops(dispatchableWorkshopKeys(operator))
  event.dataTransfer.effectAllowed = 'move'
  event.dataTransfer.setData('text/plain', operator.username)
  dragGhostEl?.remove()
  dragGhostEl = document.createElement('div')
  dragGhostEl.className = 'workshop-person-drag-ghost'
  dragGhostEl.innerHTML = `<img src="${avatarFor(operator)}" alt=""><span><b>${operator.displayName}</b><small>拖入车间派工</small></span><i></i>`
  document.body.appendChild(dragGhostEl)
  event.dataTransfer.setDragImage(dragGhostEl, 42, 58)
}

function finishDrag() {
  draggedOperator.value = null
  dragTarget.value = null
  sceneApi?.setDropTarget('', '')
  sceneApi?.setDispatchableWorkshops([])
  dragGhostEl?.remove()
  dragGhostEl = null
}

async function publishProductionNotice(content) {
  try {
    const proposal = await interpret({
      sessionId: `workshop-board-${Date.now()}`,
      text: `通知生产 ${content}`,
      module: 'production'
    })
    if (proposal?.type !== 'confirm' || !proposal?.proposalId) return false
    const result = await execute({
      proposalId: proposal.proposalId,
      decision: 'APPROVE',
      operator: userStore.userInfo?.username || '',
      roleKey: userStore.roleKey || 'manager'
    })
    return !!result?.ok
  } catch {
    return false
  }
}

async function cancelDispatch(operator) {
  if (!operator?.dispatchId || cancellingDispatchId.value) return
  cancellingDispatchId.value = operator.dispatchId
  const workshopName = selectedWorkshop.value?.name || operator.dispatch?.workshopName || operator.dispatch?.equipment || '当前车间'
  try {
    const result = await mes.deleteRecord(
      'deleteDispatch',
      { dispatchId: operator.dispatchId },
      userStore.userInfo?.username,
      userStore.roleKey
    )
    if (!result) throw new Error('取消派工未成功写入')
    await mes.hydrateForPage().catch(() => {})
    syncScene()
    dropFeedback.value = `${operator.displayName} · 已从${workshopName}取消派工`
    clearTimeout(feedbackTimer)
    feedbackTimer = setTimeout(() => { dropFeedback.value = '' }, 1800)
    const notified = await publishProductionNotice(`${operator.displayName}在${workshopName}的派工已由生产主管取消，请停止该任务并等待重新派工。`)
    ElMessage.success(notified ? '派工已取消，操作员通知已同步' : '派工已取消；通知服务暂未响应')
  } catch (error) {
    ElMessage.error(error?.message || '取消派工失败，请稍后重试')
  } finally {
    cancellingDispatchId.value = ''
  }
}

function handleDragOver(event) {
  const operator = draggedOperator.value
  if (!operator || !sceneApi) return
  const workshopKey = sceneApi.pickWorkshopAt(event.clientX, event.clientY)
  const workshop = displayWorkshops.value.find((item) => item.key === workshopKey)
  if (!workshop) {
    dragTarget.value = null
    sceneApi.setDropTarget('', '')
    return
  }
  const workOrder = findDispatchableWorkOrder(workshop, operator)
  dragTarget.value = {
    workshop,
    workOrder,
    valid: !!workOrder,
    reason: workOrder ? '' : `${workshop.name}暂无可派工工单`
  }
  event.dataTransfer.dropEffect = workOrder ? 'move' : 'none'
  sceneApi.setDropTarget(workshop.key, workOrder ? 'valid' : 'invalid')
}

function handleDragLeave() {
  dragTarget.value = null
  sceneApi?.setDropTarget('', '')
}

function formatDateTime(date) {
  const pad = (value) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

function findDispatchableWorkOrder(workshop, operator) {
  const binding = OPERATOR_BINDINGS[operator?.username]
  if (!binding || binding.stageName !== workshop.parentStepName) return null
  if (workshop.activeAssignments?.length) return null
  const candidates = mes.workOrders.filter((order) => {
    if (!['已下达', '已派工', '生产中'].includes(order.status)) return false
    return !mes.dispatches.some((item) =>
      item.workOrderId === order.id &&
      item.operator === operator.username &&
      (item.workshopKey === workshop.key || item.workshopName === workshop.name || item.equipment === workshop.name) &&
      ACTIVE_DISPATCH_STATUSES.includes(item.status)
    )
  })
  return candidates.find((order) => order.status === '已下达') || candidates[0] || null
}

async function handleDrop(event) {
  const operator = draggedOperator.value
  draggedOperator.value = null
  sceneApi?.setDispatchableWorkshops([])
  if (!operator || !sceneApi) return

  const pickedKey = sceneApi.pickWorkshopAt(event.clientX, event.clientY)
  const workshop = dragTarget.value?.workshop?.key === pickedKey
    ? dragTarget.value.workshop
    : displayWorkshops.value.find((item) => item.key === pickedKey)
  dragTarget.value = null
  if (!workshop) {
    sceneApi.setDropTarget('', '')
    ElMessage.warning('请将操作员卡片放到具体车间模型上')
    return
  }
  const workOrder = findDispatchableWorkOrder(workshop, operator)
  if (!workOrder) {
    sceneApi.setDropTarget(workshop.key, 'invalid')
    setTimeout(() => sceneApi?.setDropTarget('', ''), 700)
    ElMessage.warning(`${workshop.name}暂无可派工的生产工单`)
    return
  }

  const start = new Date()
  const end = new Date(start.getTime() + 8 * 60 * 60 * 1000)
  const planned = Number(workOrder.quantity ?? workOrder.planQty ?? 1)
  const completed = Number(workOrder.completedQty ?? 0)
  try {
    const result = await mes.createDispatch({
      workOrderId: workOrder.id,
      processStep: workshop.parentStepName || workshop.taskTitle,
      equipment: workshop.name,
      workshopKey: workshop.key,
      workshopName: workshop.name,
      operator: operator.username,
      operatorName: operator.displayName,
      planQty: Math.max(1, planned - completed),
      planStart: formatDateTime(start),
      planEnd: formatDateTime(end)
    }, userStore.userInfo?.username, userStore.roleKey)
    if (!result) throw new Error('派工未成功写入')
    await mes.hydrateForPage().catch(() => {})
    syncScene()
    selectedKey.value = workshop.key
    selectedSnapshot.value = workshop
    sceneApi.setDropTarget(workshop.key, 'success')
    sceneApi.focusWorkshop(workshop.key)
    dropFeedback.value = `${operator.displayName} → ${workshop.name} · 派工与通知已同步`
    clearTimeout(feedbackTimer)
    feedbackTimer = setTimeout(() => {
      dropFeedback.value = ''
      sceneApi?.setDropTarget('', '')
    }, 1800)
    const notified = await publishProductionNotice(`${operator.displayName}已派往${workshop.name}执行${workshop.parentStepName || workshop.taskTitle}，请及时在操作员端接收任务。`)
    ElMessage.success(notified ? `已将${operator.displayName}派往${workshop.name}，任务通知已同步` : `已完成派工；操作员端待办已同步`)
  } catch (error) {
    sceneApi.setDropTarget('', '')
    ElMessage.error(error?.message || '派工失败，请稍后重试')
  }
}

watch(displayWorkshops, () => syncScene(), { deep: true })

onMounted(async () => {
  if (!containerRef.value) return
  sceneApi = createWorkshopScene(containerRef.value, {
    onSelectWorkshop(key, data) {
      selectedKey.value = key
      selectedSnapshot.value = displayWorkshops.value.find((item) => item.key === key) || data || null
      hoverWorkshop.value = null
      emit('select', key)
    },
    onSelectOperator({ workshopKey, username, workshop }) {
      selectedKey.value = workshopKey
      selectedSnapshot.value = displayWorkshops.value.find((item) => item.key === workshopKey) || workshop || null
      hoverWorkshop.value = null
    },
    onHoverWorkshop(data) {
      hoverWorkshop.value = data
    },
    onIntroProgress(state) {
      tourState.value = { ...tourState.value, ...state }
    }
  })
  syncScene()
  try {
    await mes.hydrateForPage({ force: true })
    syncScene()
  } catch {
    // 大屏仍使用当前快照，不阻断 3D 场景。
  }
  document.addEventListener('visibilitychange', handleVisibilitySync)
  dispatchSyncTimer = window.setInterval(() => {
    if (!document.hidden) refreshDispatchSnapshot()
  }, 1800)
})

onUnmounted(() => {
  clearTimeout(feedbackTimer)
  window.clearInterval(dispatchSyncTimer)
  document.removeEventListener('visibilitychange', handleVisibilitySync)
  dragGhostEl?.remove()
  sceneApi?.dispose()
  sceneApi = null
})
</script>

<style scoped>
.workshop-scene { position: relative; display: grid; grid-template-rows: minmax(0, 1fr) auto; width: 100%; height: 100%; min-height: 520px; overflow: hidden; background: #eef2f6; border-radius: 8px; }
.workshop-scene__viewport { position: relative; min-height: 0; overflow: hidden; }
.workshop-scene__viewport.is-dragging::after { content: '绿色车间可派工 · 红色车间不可派工'; position: absolute; top: 86px; left: 50%; z-index: 5; padding: 9px 18px; transform: translateX(-50%); border: 1px solid rgba(91, 119, 114, .3); border-radius: 20px; color: #315f57; font-weight: 800; background: rgba(255,255,255,.9); box-shadow: 0 8px 22px rgba(31,41,55,.1); backdrop-filter: blur(10px); pointer-events: none; }
.workshop-scene__canvas { width: 100%; height: 100%; cursor: pointer; }
.workshop-scene__tour { position: absolute; top: 24px; right: 740px; left: 24px; z-index: 8; width: auto; max-width: 980px; margin: 0 auto; padding: 16px 26px 18px; transform: none; border: 1px solid rgba(169, 192, 205, .78); border-radius: 18px; background: rgba(255, 255, 255, .94); box-shadow: 0 14px 36px rgba(42, 58, 79, .16); backdrop-filter: blur(14px); pointer-events: none; }
.workshop-scene__tour-head { display: grid; grid-template-columns: auto 1fr auto; align-items: center; gap: 20px; color: #52677e; font-size: 14px; }.workshop-scene__tour-head span { padding: 5px 12px; border-radius: 12px; color: #326e65; background: #e7f3f0; font-size: 15px; font-weight: 800; }.workshop-scene__tour-head strong { overflow: hidden; color: #26364d; font-size: 16px; text-align: center; text-overflow: ellipsis; white-space: nowrap; }.workshop-scene__tour-head i { color: #5a9a7a; font-style: normal; }.workshop-scene__tour-head em { color: #5a738d; font-size: 15px; font-style: normal; font-weight: 800; }
.workshop-scene__tour-line { position: relative; height: 6px; margin: 18px 7px 7px; border-radius: 6px; background: #dfe7ed; }.workshop-scene__tour-fill { position: absolute; inset: 0 auto 0 0; border-radius: inherit; background: linear-gradient(90deg, #728aa2, #58a28f); transition: width .08s linear; }.workshop-scene__tour-line > span { position: absolute; top: 50%; width: 13px; height: 13px; transform: translate(-50%, -50%); border: 2px solid #d3dee6; border-radius: 50%; background: #fff; }.workshop-scene__tour-line > span.is-passed { border-color: #69a99a; background: #69a99a; }.workshop-scene__tour-line > span.is-current { width: 18px; height: 18px; border: 3px solid #fff; background: #4c8d80; box-shadow: 0 0 0 6px rgba(76, 141, 128, .2); }.workshop-scene__tour-stage { margin-top: 4px; color: #667b91; font-size: 12px; font-weight: 700; text-align: center; }
.workshop-scene__drag-hud { position: absolute; left: 18px; bottom: 18px; z-index: 9; display: grid; grid-template-columns: 42px 1fr; align-items: center; gap: 10px; min-width: 260px; padding: 9px 12px; border: 1px solid #cdd9e3; border-radius: 12px; background: rgba(255,255,255,.94); box-shadow: 0 9px 26px rgba(15,23,42,.13); backdrop-filter: blur(10px); pointer-events: none; }.workshop-scene__drag-hud.is-valid { border-color: #74b3a4; background: rgba(239,249,246,.96); }.workshop-scene__drag-hud.is-invalid { border-color: #d9a2a7; }.workshop-scene__drag-hud img { width: 42px; height: 42px; border-radius: 50%; }.workshop-scene__drag-hud div { display: grid; gap: 2px; }.workshop-scene__drag-hud strong { color: #26364d; }.workshop-scene__drag-hud span { color: #718096; font-size: 11px; }
.workshop-scene__dispatch-toast { position: absolute; top: 82px; left: 50%; z-index: 9; padding: 9px 16px; transform: translateX(-50%); border: 1px solid #b9d8cf; border-radius: 18px; color: #326e65; background: rgba(244,251,249,.96); box-shadow: 0 8px 24px rgba(15,23,42,.1); font-weight: 700; pointer-events: none; }
.tour-track-enter-active,.tour-track-leave-active,.dispatch-toast-enter-active,.dispatch-toast-leave-active { transition: opacity .25s ease, transform .25s ease; }.tour-track-enter-from,.tour-track-leave-to { opacity: 0; transform: translateY(-10px); }.dispatch-toast-enter-from,.dispatch-toast-leave-to { opacity: 0; transform: translate(-50%,-8px); }
.workshop-scene__hover { position: absolute; right: 18px; top: 18px; z-index: 3; display: grid; gap: 4px; width: 230px; padding: 12px 14px; border: 1px solid rgba(210, 219, 229, .9); border-radius: 10px; background: rgba(255, 255, 255, .92); color: #475569; box-shadow: 0 8px 24px rgba(15, 23, 42, .08); pointer-events: none; }
.workshop-scene__hover strong { color: #172033; font-size: 14px; }.workshop-scene__hover span { color: #506784; }.workshop-scene__hover small { color: #7b8798; }
.workshop-scene__panel { position: absolute; top: 14px; right: 14px; bottom: 162px; z-index: 1000; width: 300px; overflow: auto; padding: 20px; border: 1px solid rgba(218, 225, 234, .9); border-radius: 14px; background: rgba(255, 255, 255, .98); color: #334155; box-shadow: 0 16px 40px rgba(15, 23, 42, .18); backdrop-filter: blur(12px); pointer-events: auto; }
.workshop-scene__close { position: absolute; top: 10px; right: 12px; width: 30px; height: 30px; border: 0; border-radius: 50%; background: #eef2f6; color: #64748b; font-size: 22px; cursor: pointer; }
.workshop-scene__panel-kicker { color: #5a9a7a; font-size: 11px; font-weight: 700; letter-spacing: .12em; }.workshop-scene__panel h3 { margin: 7px 34px 4px 0; color: #172033; font-size: 20px; }.workshop-scene__task { margin: 0 0 16px; color: #64748b; }
.workshop-scene__metrics { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }.workshop-scene__metrics div { display: grid; gap: 2px; padding: 11px; border-radius: 9px; background: #f5f7fa; }.workshop-scene__metrics strong { color: #26364d; font-size: 18px; }.workshop-scene__metrics span { color: #8491a3; font-size: 10px; }
.workshop-scene__progress { height: 7px; margin: 12px 0 15px; overflow: hidden; border-radius: 4px; background: #edf1f5; }.workshop-scene__progress span { display: block; height: 100%; border-radius: inherit; background: linear-gradient(90deg, #506784, #5a9a7a); }
.workshop-scene__facts { display: grid; gap: 8px; margin: 0; }.workshop-scene__facts div { display: flex; justify-content: space-between; gap: 12px; padding-bottom: 7px; border-bottom: 1px solid #edf1f5; }.workshop-scene__facts dt { color: #8491a3; }.workshop-scene__facts dd { margin: 0; color: #334155; text-align: right; font-weight: 600; }
.workshop-scene__section-title { margin: 18px 0 8px; color: #506784; font-size: 12px; font-weight: 700; }.workshop-scene__people { display: grid; gap: 8px; }.workshop-scene__people article { display: grid; grid-template-columns: 38px minmax(0,1fr) auto; align-items: center; gap: 9px; padding: 9px; border: 1px solid #e1e7ed; border-radius: 10px; background: #f9fbfc; }.workshop-scene__people img { width: 38px; height: 38px; border-radius: 50%; object-fit: cover; }.workshop-scene__people article > div { display: grid; min-width: 0; gap: 1px; }.workshop-scene__people strong,.workshop-scene__people span,.workshop-scene__people small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.workshop-scene__people strong { color: #2f4055; }.workshop-scene__people span { color: #62748a; font-size: 10px; }.workshop-scene__people small { color: #95a0af; font-size: 9px; }.workshop-scene__people button { padding: 5px 7px; border: 1px solid #e2b9bd; border-radius: 7px; color: #a85f66; background: #fff7f7; font-size: 10px; cursor: pointer; }.workshop-scene__people button:disabled { opacity: .55; cursor: wait; }.workshop-scene__empty { padding: 14px; border-radius: 8px; background: #f7f8fa; color: #94a3b8; text-align: center; }
.workshop-scene__dock { min-height: 148px; padding: 10px 14px; border-top: 1px solid #dfe5ec; background: rgba(255, 255, 255, .97); }.workshop-scene__cards { display: flex; gap: 9px; overflow-x: auto; padding: 2px 2px 7px; }.workshop-scene__card { display: grid; grid-template-rows: 46px auto auto auto; justify-items: center; gap: 3px; flex: 0 0 106px; width: 106px; min-height: 124px; padding: 8px 7px; border: 1px solid #dfe6ed; border-radius: 10px; background: #fff; cursor: grab; text-align: center; box-shadow: 0 3px 10px rgba(15, 23, 42, .05); transition: transform .16s ease, border-color .16s ease, box-shadow .16s ease; }.workshop-scene__card:hover:not(.is-busy):not(.is-locked) { transform: translateY(-3px); border-color: #7fb6aa; box-shadow: 0 8px 18px rgba(37,93,82,.13); }.workshop-scene__card:active { cursor: grabbing; }.workshop-scene__card.is-busy,.workshop-scene__card.is-locked { background: #f7f8fa; cursor: not-allowed; opacity: .72; }.workshop-scene__card img { width: 46px; height: 46px; border-radius: 50%; object-fit: cover; }.workshop-scene__card div { display: contents; }.workshop-scene__card strong { width: 100%; overflow: hidden; color: #334155; text-overflow: ellipsis; white-space: nowrap; }.workshop-scene__card span { width: 100%; overflow: hidden; color: #8491a3; font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }.workshop-scene__card em { padding: 2px 7px; border-radius: 10px; color: #3f756a; background: #edf7f4; font-style: normal; font-size: 9px; white-space: nowrap; }
@media (max-width: 1100px) { .workshop-scene__panel { width: 270px; } }
</style>

<style>
.workshop-3d-label { min-width: 145px; padding: 0; pointer-events: none; transition: opacity .18s ease, filter .18s ease, transform .18s ease; }
.workshop-3d-card { padding: 6px 8px; border: 1px solid #e8eaf0; border-radius: 8px; background: rgba(255,255,255,.94); text-align: center; box-shadow: 0 4px 16px rgba(31,41,55,.1); }
.workshop-3d-label.is-overview-card { min-width: 122px; }.workshop-3d-label.is-overview-card .workshop-3d-card { padding: 7px 10px; border-radius: 9px; box-shadow: 0 3px 10px rgba(31,41,55,.1); }.workshop-3d-label.is-overview-card .workshop-3d-card__name { margin: 0; font-size: 12px; white-space: nowrap; }.workshop-3d-label.is-overview-card .workshop-3d-card__task,.workshop-3d-label.is-overview-card .workshop-3d-card__bar,.workshop-3d-label.is-overview-card .workshop-3d-card__meta { display: none; }.workshop-3d-label.is-overview-card .workshop-3d-card__dispatch { margin: 5px 0 0; padding: 2px 7px; font-size: 10px; }
.workshop-3d-label.is-hovered .workshop-3d-card { border-color: #506784; box-shadow: 0 4px 20px rgba(31,41,55,.14); }
.workshop-3d-card__dispatch { display: inline-block; margin: 1px 0 5px; padding: 2px 7px; border-radius: 9px; font-size: 9px; font-weight: 700; }.workshop-3d-label.is-dispatch-available .workshop-3d-card { border-color: #9fc4ca; }.workshop-3d-label.is-dispatch-available .workshop-3d-card__dispatch { color: #467884; background: #e6f1f3; }.workshop-3d-label.is-dispatch-assigned .workshop-3d-card { border-color: #d9bd82; background: rgba(255,251,240,.97); box-shadow: 0 5px 18px rgba(153,112,40,.14); }.workshop-3d-label.is-dispatch-assigned .workshop-3d-card__dispatch { color: #946d25; background: #f7ebcd; }.workshop-3d-label.is-dispatch-accepted .workshop-3d-card { border-color: #8bb9a4; background: rgba(244,251,247,.97); box-shadow: 0 5px 19px rgba(52,113,84,.16); }.workshop-3d-label.is-dispatch-accepted .workshop-3d-card__dispatch { color: #38735a; background: #deefe6; }
.workshop-3d-label.is-dispatch-option { z-index: 50 !important; opacity: 1 !important; transform: translateY(-5px) scale(1.06); filter: none; animation: dispatch-option-pulse 1.1s ease-in-out infinite alternate; }.workshop-3d-label.is-dispatch-option .workshop-3d-card { border: 2px solid #2fbd79; background: rgba(234,251,242,.99); box-shadow: 0 0 0 7px rgba(47,189,121,.2),0 14px 28px rgba(31,126,80,.24); }.workshop-3d-label.is-dispatch-option .workshop-3d-card__dispatch { color: #17764a; background: #cef3df; }.workshop-3d-label.is-dispatch-disabled { z-index: 45 !important; opacity: 1 !important; filter: none; }.workshop-3d-label.is-dispatch-disabled .workshop-3d-card { border: 2px solid #e35d6a; background: rgba(255,239,241,.98); box-shadow: 0 0 0 5px rgba(227,93,106,.16),0 10px 22px rgba(152,49,59,.17); }.workshop-3d-label.is-dispatch-disabled .workshop-3d-card__dispatch { color: #a6323e; background: #ffd9dd; }
@keyframes dispatch-option-pulse { from { transform: translateY(-3px) scale(1.04); } to { transform: translateY(-7px) scale(1.08); } }
.workshop-3d-label.is-drop-target .workshop-3d-card { border-color: #4f9d8d; background: rgba(236,250,246,.98); box-shadow: 0 0 0 5px rgba(79,157,141,.16),0 12px 30px rgba(35,91,80,.18); }.workshop-3d-label.is-drop-invalid .workshop-3d-card { border-color: #c97880; background: rgba(255,244,245,.98); }.workshop-3d-label.is-drop-success .workshop-3d-card { border-color: #3f927e; background: #effaf6; box-shadow: 0 0 0 6px rgba(63,146,126,.18); }
.workshop-3d-card__name { margin-bottom: 2px; color: #172033; font-size: 13px; font-weight: 700; }.workshop-3d-card__task { margin-bottom: 5px; color: #506784; font-size: 10px; }.workshop-3d-card__bar { height: 5px; margin-bottom: 3px; overflow: hidden; border-radius: 3px; background: #f0f2f5; }.workshop-3d-card__bar span { display: block; width: 0; height: 100%; border-radius: inherit; background: linear-gradient(90deg,#506784,#5a9a7a); transition: width .35s; }.workshop-3d-card__meta { color: #64748b; font-size: 10px; }
.operator-3d-label { padding: 2px 6px; border: 1px solid #d8e0e8; border-radius: 5px; background: rgba(255,255,255,.95); color: #334155; font-size: 10px; font-weight: 700; white-space: nowrap; box-shadow: 0 2px 7px rgba(15,23,42,.1); pointer-events: none; user-select: none; }.operator-3d-label.is-working { border-color: #a8d5c2; background: rgba(240,252,247,.96); color: #2e6b52; }.operator-3d-label.is-chatting { border-color: #c5d0e0; color: #506784; }.operator-3d-chat { padding: 1px 5px; border: 1px solid #dbe3ef; border-radius: 8px; background: #fff; color: #506784; font-size: 9px; box-shadow: 0 2px 6px rgba(15,23,42,.1); pointer-events: none; }
.workshop-person-drag-ghost { position: fixed; left: -9999px; top: -9999px; z-index: 99999; display: grid; grid-template-columns: 52px 1fr; align-items: center; gap: 10px; width: 210px; padding: 10px 14px; border: 1px solid rgba(81,145,131,.75); border-radius: 18px; background: rgba(244,252,249,.82); box-shadow: 0 16px 34px rgba(25,65,58,.2); backdrop-filter: blur(9px); }.workshop-person-drag-ghost::after { content: ''; position: absolute; left: 25px; top: 55px; width: 28px; height: 45px; border-radius: 14px 14px 9px 9px; background: linear-gradient(rgba(87,145,133,.48),rgba(87,145,133,.08)); filter: blur(.2px); }.workshop-person-drag-ghost img { width: 52px; height: 52px; border-radius: 50%; opacity: .9; }.workshop-person-drag-ghost span { display: grid; gap: 3px; }.workshop-person-drag-ghost b { color: #294b45; }.workshop-person-drag-ghost small { color: #66827c; }.workshop-person-drag-ghost i { position: absolute; left: 13px; top: 92px; width: 54px; height: 14px; border-radius: 50%; background: rgba(62,116,105,.13); filter: blur(4px); }
</style>
