<script setup>
import { defineAsyncComponent, computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  Plus,
  Search,
  ArrowUp,
  Copy as CopyDocument,
  Trash2 as Delete,
  Menu,
  X as Close,
  LoaderCircle as Loading,
  PanelRight, PanelLeft, ArrowUpRight, MessageSquare, ChevronRight,
  Workflow, Download, ClipboardPlus, PackageSearch, Factory, ScanLine, Layers, Monitor,
} from 'lucide-vue-next'
import { useUserStore } from '@/stores/user'
import AgentInspector from '@/components/assistant/AgentInspector.vue'
import BusinessQueryAnswer from '@/components/assistant/BusinessQueryAnswer.vue'
import KnowledgeSources from '@/components/assistant/KnowledgeSources.vue'
import { canExportResult, resultCsv } from '@/utils/knowledgeSources'
import WorkbenchMarkdown from '@/components/assistant/WorkbenchMarkdown.vue'
import { isProductionOnly, compactBusinessAnswer, hasBusinessTables } from '@/utils/businessPresentation'
import {
  workbenchStatus,
  streamWorkbench,
  workbenchRun,
} from '@/api/agentWorkbench'
import {
  buildNaturalRequest,
  legacyMessageContext,
  uniqueId,
  historyKey,
  createAssistantMessage,
  applyWorkbenchEvent,
  interruptMessage,
  restoreSessions,
  serializeWorkbenchSessions,
  recoverWorkbenchResult,
} from '@/utils/agentWorkbench'
import { orderStatus, orderTurn, getOrderDraft, reviseOrderDraft, confirmOrderDraft, cancelOrderDraft } from '@/api/assistantOrders'
import OrderFlowCard from '@/components/assistant/OrderFlowCard.vue'
import AgentWorkflowCard from '@/components/assistant/AgentWorkflowCard.vue'
import WorkbenchComposer from '@/components/assistant/WorkbenchComposer.vue'
import InventoryAnswer from '@/components/assistant/InventoryAnswer.vue'
import { isInventoryOnly, inventorySnapshot } from '@/utils/inventoryPresentation'
import { prefersReadOnlyQuery } from '@/utils/workbenchRouting'

function exportResult(message) {
  const csv = resultCsv(message)
  if (!csv) return
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a'); link.href = url; link.download = 'MES-query.csv'; link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
const user = useUserStore()
const inspectorOpen = ref(typeof window === 'undefined' || window.innerWidth >= 1200)
const historyCollapsed = ref(false)
function toggleHistory() {
  if (window.matchMedia('(max-width: 760px)').matches) showHistory.value = !showHistory.value
  else historyCollapsed.value = !historyCollapsed.value
}
const attachmentInput = ref(null)
const attachmentNotice = ref('')
const sessions = ref([]),
  activeId = ref(null),
  search = ref(''),
  input = ref('')
const busy = ref(false),
  checking = ref(false),
  showHistory = ref(false)
const connection = ref(null),
  connectionError = ref(''),
  list = ref(null),
  composer = ref(null)
let controller = null,
  owner = null,
  activeMessage = null,
  disposed = false
let pendingHistory = null
let lastAuthorizationStamp = null
const orderConnection = ref(null)
const followTail = ref(true)
const orderAllowed = computed(() => ['ADMIN', 'ORDER', 'CUSTOMER'].includes(user.userInfo?.roleCode))
const orderReady = computed(() => orderAllowed.value && orderConnection.value?.available === true)
const admin = computed(() => user.userInfo?.roleCode === 'ADMIN')
const active = computed(() =>
  sessions.value.find((s) => s.id === activeId.value),
)
const messages = computed(() => active.value?.messages || [])
const inspectedMessage = computed(() => messages.value.findLast((message) => message.role === 'assistant') || null)
async function attachTextFile(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file || busy.value) return
  if (!/\.(txt|md|csv|json)$/i.test(file.name) || file.size > 8000) {
    ElMessage.warning('支持 8 KB 内的 TXT、Markdown、CSV 或 JSON 文本，内容会加入输入框。')
    return
  }
  const sessionId = activeId.value, previous = input.value, token = user.token
  try {
    const content = await file.text()
    if (disposed || token !== user.token || activeId.value !== sessionId || input.value !== previous || busy.value) return
    const combined = `${previous}${previous ? '\n\n' : ''}资料：${file.name}\n${content}`
    if (combined.length > 2000) {
      ElMessage.warning('加入资料后超过 2000 字，请选取需要分析的部分再粘贴。')
      return
    }
    input.value = combined
    attachmentNotice.value = '文本已加入输入框，发送前可编辑；文件未上传。'
    composer.value?.focus()
  } catch { ElMessage.warning('无法读取文件，请重新选择或粘贴文字。') }
}
const visibleSessions = computed(() =>
  sessions.value.filter((s) => s.title.includes(search.value.trim())),
)
const queryReady = computed(() => (admin.value || Array.isArray(connection.value?.capabilities?.domains)) && connection.value?.configured === true && connection.value?.naturalLanguage === true)
const ready = computed(() => orderReady.value || queryReady.value)
const needsUpgrade = computed(() => connection.value != null && connection.value.naturalLanguage !== true)
const BusinessActions = defineAsyncComponent(() => import('./ChatHomeView.vue'))
const businessToolsOpen = ref(false)
const canSend = computed(() => ready.value && !busy.value && input.value.trim().length > 0)
const statusText = computed(() => checking.value ? '正在连接' : ready.value ? '已连接' : needsUpgrade.value ? '服务待升级' : '服务未连接')
const statusHint = computed(() => {
  if (ready.value) return ''
  if (needsUpgrade.value) return '当前协作服务尚未升级为自然语言查询，请联系维护人员升级后重新连接。'
  return connectionError.value || '订单服务尚未连接，请稍后重新连接。'
})
const introductory = computed(() => [
  ...(orderAllowed.value ? [{ title: '创建订单', icon: ClipboardPlus, description: '产品选型与订单草稿', prompt: '我想下单，请帮我选择产品并填写订单信息。' }] : []),
  ...((admin.value || queryReady.value) ? [
    { title: '查库存', icon: PackageSearch, description: '物料、仓库与可用数量', prompt: '帮我查一下当前库存，列出需要关注的物料。' },
    { title: '看生产进度', icon: Factory, description: '工单进展与生产状态', prompt: '帮我看看当前生产进度，哪些工单需要关注？' },
    { title: '分析质量问题', icon: ScanLine, description: '检验结果与缺陷依据', prompt: '分析最近七天的质量问题，列出检验和缺陷证据。' },
  ].filter(item => { const domain = ({ 查库存: 'INVENTORY', 看生产进度: 'PRODUCTION', 分析质量问题: 'QUALITY' })[item.title]; return connection.value?.capabilities?.domains?.includes(domain) ?? admin.value }) : []),
])

function persist() {
  const key = historyKey(owner)
  if (!key) return
  try {
    // Keep history small; authorization tokens never enter this store.
    localStorage.setItem(key, serializeWorkbenchSessions(sessions.value))
  } catch {
    /* Quota limits do not interrupt a running answer. */
  }
}
function stop() {
  controller?.abort()
  if (activeMessage?.orderFlow) {
    activeMessage.orderFlow.pending = ''
    activeMessage.orderFlow.needsRecovery = true
    interruptMessage(activeMessage, 'INTERRUPTED', '已停止等待；请刷新草稿状态，未自动重新提交。')
  } else if (activeMessage)
    interruptMessage(
      activeMessage,
      'CANCELLED',
      '已停止等待并请求取消；上游是否停止以服务返回为准。',
    )
  persist()
}
async function checkConnection() {
  const token = user.token
  checking.value = true
  connectionError.value = ''
  const [orderResult, queryResult] = await Promise.allSettled([
    orderAllowed.value ? orderStatus(token) : Promise.resolve(null),
    workbenchStatus(token),
  ])
  if (disposed || token !== user.token) return
  orderConnection.value = orderResult.status === 'fulfilled' ? orderResult.value : null
  connection.value = queryResult.status === 'fulfilled' ? queryResult.value : null
  if (queryResult.status === 'rejected' && [401, 403].includes(queryResult.reason?.code)) {
    stop(); sessions.value = []; activeId.value = null; pendingHistory = null
    try { localStorage.removeItem(historyKey(user.userInfo?.userId)) } catch { /* In-memory history is already cleared. */ }
  }
  const stamp = connection.value?.authorizationStamp
  if (typeof stamp === 'string') {
    const cacheKey = historyKey(user.userInfo?.userId), stampKey = `${cacheKey}:authorization`
    let stored = lastAuthorizationStamp
    try { stored ||= localStorage.getItem(stampKey) } catch { /* Storage may be disabled. */ }
    if ((stored && stored !== stamp) || (!stored && !admin.value && pendingHistory)) {
      stop(); sessions.value = []; activeId.value = null; input.value = ''
      try { localStorage.removeItem(cacheKey) } catch { /* Clear the in-memory copy regardless. */ }
    } else if (pendingHistory) {
      try { sessions.value = restoreSessions(pendingHistory) } catch { sessions.value = [] }
    }
    pendingHistory = null; lastAuthorizationStamp = stamp
    try { localStorage.setItem(stampKey, stamp) } catch { /* Next reload remains fail-closed. */ }
  }

  connectionError.value = orderResult.status === 'rejected' ? orderResult.reason.message : queryResult.status === 'rejected' ? queryResult.reason.message : ''
  checking.value = false
}

watch(
  () => [user.userInfo?.userId, user.token, user.userInfo?.roleCode],
  ([id]) => {
    stop()
    owner = id
    activeId.value = null
    input.value = ''
    connection.value = null
    orderConnection.value = null
    connectionError.value = ''
    try {
      sessions.value = []
      pendingHistory = localStorage.getItem(historyKey(id)) || ''
      lastAuthorizationStamp = null
    } catch {
      sessions.value = []
    }
    if (user.token) checkConnection()
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  disposed = true
  stop()
})

function newSession() {
  if (busy.value) stop()
  followTail.value = true
  activeId.value = null
  input.value = ''
  attachmentNotice.value = ''
  showHistory.value = false
  composer.value?.focus()
}
function selectSession(session) {
  if (session.id === activeId.value) return
  if (busy.value) stop()
  followTail.value = true
  activeId.value = session.id
  input.value = ''
  recoverSessionOrders(session)
  showHistory.value = false
  nextTick(() => {
    if (list.value) list.value.scrollTop = list.value.scrollHeight
  })
}
function removeSession(id) {
  if (busy.value) return
  sessions.value = sessions.value.filter((s) => s.id !== id)
  if (activeId.value === id) activeId.value = null
  persist()
}
function chooseExample(item) {
  input.value = item.prompt
  composer.value?.focus()
}
function followScroll(force = false) {
  const el = list.value
  if (!el) return
  if (force) followTail.value = true
  if (followTail.value)
    nextTick(() => {
      if (followTail.value) el.scrollTop = el.scrollHeight
    })
}
function trackScroll() {
  const el = list.value
  if (el) followTail.value = el.scrollHeight - el.scrollTop - el.clientHeight < 100
}
async function send() {
  if (!canSend.value) return
  const text = input.value.trim(),
    token = user.token,
    capturedOwner = owner
  let session = active.value
  if (!session) {
    const id = uniqueId()
    sessions.value.unshift({
      id,
      title: text.slice(0, 25),
      profile: 'AUTO',
      updatedAt: Date.now(),
      messages: [],
    })
    sessions.value = sessions.value.slice(0, 20)
    activeId.value = id
    session = active.value
  }
  session.updatedAt = Date.now()
  session.messages.push({
    id: uniqueId(),
    role: 'user',
    text,
    profile: 'AUTO',
    orderMessage: orderReady.value,
  })
  session.messages.push(createAssistantMessage())
  const message = session.messages.at(-1)
  message.conversationId = session.id
  const needsOrderIntake = orderReady.value && !prefersReadOnlyQuery(text, { canQuery: queryReady.value })
  if (!needsOrderIntake) session.messages.at(-2).orderMessage = false
  if (needsOrderIntake) message.orderFlow = { steps: [], pending: '总协调正在理解订单需求' }
  activeMessage = message
  input.value = ''
  busy.value = true
  controller = new AbortController()
  persist()
  followScroll(true)
  try {
    if (needsOrderIntake) {
      let envelope
      try {
        envelope = await orderTurn(token, { sessionId: session.id, requestId: message.id, text }, { signal: controller.signal })
      } catch (error) {
        const unavailable = error.name === 'TypeError' || error.code === 503
        if (!unavailable || error.name === 'AbortError' || controller.signal.aborted || !queryReady.value) throw error
        // Intake never confirms an order. Continue the existing read-only path once,
        // retaining privacy markers because the unclassified text may contain PII.
        message.orderIntakeNotice = '订单信息识别暂不可用，当前仅执行只读查询。订单草稿状态请稍后核对。'
        message.orderMessage = true
        envelope = { handled: false }
      }
      if (capturedOwner !== owner || token !== user.token || disposed) return
      if (envelope.handled) {
        applyOrderEnvelope(message, envelope)
        return
      }
      if (!queryReady.value) {
        applyOrderEnvelope(message, { handled: true, reply: '当前账号可办理订单，请说明需要购买的产品和数量。', steps: envelope.steps || [] })
        return
      }
      delete message.orderFlow
      if (!message.orderIntakeNotice) session.messages.at(-2).orderMessage = false
    }
    if (!queryReady.value) throw new Error('查询服务暂不可用，请稍后重新连接。')
    await streamWorkbench(
      token,
      buildNaturalRequest(session, text, message.id),
      {
        signal: controller.signal,
        onEvent: (event) => {
          if (capturedOwner !== owner || token !== user.token || disposed)
            return
          applyWorkbenchEvent(message, event)
          if (['run', 'agent', 'result', 'workflow'].includes(event.type)) persist()
          if (activeId.value === session.id) followScroll()
        },
      },
    )
  } catch (error) {
    if (capturedOwner === owner && token === user.token && !disposed) {
      if (message.orderFlow) {
        message.orderFlow.pending = ''
        message.orderFlow.needsRecovery = true
      }
      if (error.name !== 'AbortError' && !message.runId && !message.orderFlow && !input.value && activeId.value === session.id) input.value = text
      interruptMessage(
        message,
        error.name === 'AbortError' && !message.orderFlow ? 'CANCELLED' : error.taskStatus || 'INTERRUPTED',
        error.name === 'AbortError' ? message.orderFlow ? '已停止等待；请刷新草稿状态，未自动重新提交。' : '已停止生成并请求取消本次任务。' : error.message,
      )
    }
  } finally {
    busy.value = false
    controller = null
    activeMessage = null
    if (capturedOwner === owner && token === user.token && !disposed) persist()
  }
}
function applyOrderEnvelope(message, envelope) {
  message.orderFlow = { ...envelope, steps: Array.isArray(envelope.steps) ? envelope.steps : [], needsRecovery: false }
  message.text = envelope.reply || ''
  message.status = 'COMPLETED'
  message.error = ''
  message.orderMessage = true
  persist()
  followScroll()
}
function historicalOrder(message) {
  const id = message.orderFlow?.orderDraft?.id
  return !!id && messages.value.some((item, index) => index > messages.value.indexOf(message) && item.orderFlow?.orderDraft?.id === id)
}
async function recoverSessionOrders(session) {
  if (!orderReady.value) return
  const token = user.token
  const ids = [...new Set(session.messages.map((m) => m.orderFlow?.orderDraft?.id).filter(Boolean))]
  for (const id of ids) {
    // History actions remain disabled until this owner-scoped server read succeeds.
    const items = session.messages.filter((m) => m.orderFlow?.orderDraft?.id === id)
    for (const item of items) item.orderFlow.needsRecovery = true
    try {
      const envelope = await getOrderDraft(token, id)
      if (disposed || token !== user.token) return
      for (const item of items) applyOrderEnvelope(item, envelope)
    } catch (error) {
      if (disposed || token !== user.token) return
      for (const item of items) item.error = error.message
    }
  }
}
async function orderAction(message, { action, fields } = {}) {
  const draft = message.orderFlow?.orderDraft
  if (!draft?.id || busy.value || !orderReady.value || (action !== 'recover' && (historicalOrder(message) || message.orderFlow.needsRecovery))) return
  const token = user.token, capturedOwner = owner, expectedVersion = draft.version
  const valid = () => !disposed && token === user.token && owner === capturedOwner
  busy.value = true
  activeMessage = message
  controller = new AbortController()
  const signal = controller.signal
  message.orderFlow.pending = '正在读取最新草稿'
  try {
    const fresh = await getOrderDraft(token, draft.id)
    if (!valid()) return
    if (fresh.orderDraft?.id !== draft.id) throw new Error('未能核对订单草稿，请刷新后再操作')
    applyOrderEnvelope(message, fresh)
    if (signal.aborted || action === 'recover') return
    if (fresh.orderDraft?.version !== expectedVersion) {
      message.error = '订单草稿已有变化，已更新。请核对后重新操作。'
      return
    }
    if (!['COLLECTING', 'READY'].includes(fresh.orderDraft?.status)) return
    const handlers = { revise: reviseOrderDraft, confirm: confirmOrderDraft, cancel: cancelOrderDraft }
    if (!handlers[action]) return
    let version = expectedVersion
    if (action === 'confirm') {
      const patch = { ...fields }
      if (user.userInfo?.roleCode === 'CUSTOMER' || fresh.orderDraft.customerIdentityLocked === true) delete patch.customerName
      if (Object.keys(patch).length) {
        message.orderFlow.pending = '正在校验订单信息'
        const revised = await reviseOrderDraft(token, draft.id, { version, requestId: uniqueId(), fields: patch }, { signal })
        if (!valid()) return
        // A revision must be the acknowledged next version of this exact draft.
        // Any uncertain response goes through read-only recovery, never confirmation.
        if (revised.orderDraft?.id !== draft.id || !Number.isInteger(version) || revised.orderDraft?.version !== version + 1) {
          throw new Error('订单修改结果待核对')
        }
        applyOrderEnvelope(message, revised)
        version = revised.orderDraft.version
      }
      if (signal.aborted) return
      if (message.orderFlow.orderDraft.status !== 'READY') {
        message.error = '请补全订单信息后再提交审核。'
        return
      }
    }
    if (signal.aborted || !valid()) return
    message.orderFlow.pending = { revise: '正在暂存订单信息', confirm: '正在提交订单审核', cancel: '正在取消草稿' }[action]
    const body = { version, requestId: uniqueId(), ...(action === 'revise' ? { fields } : {}) }
    const envelope = await handlers[action](token, draft.id, body, { signal })
    if (valid()) applyOrderEnvelope(message, envelope)
  } catch (error) {
    if (!valid()) return
    message.orderFlow.needsRecovery = true
    message.error = `${error.message}；正在查询草稿当前状态，未自动重新提交。`
    try {
      // A lost POST response is resolved with GET, never a repeated order creation.
      const recovered = await getOrderDraft(token, draft.id)
      if (!valid()) return
      applyOrderEnvelope(message, recovered)
      if (recovered.orderDraft?.status !== 'SUBMITTED') message.error = `${error.message}；已刷新草稿，请核对后再操作。`
    } catch {
      if (valid()) message.error = '暂时无法确认处理结果，请恢复订单草稿后再操作。系统不会自动重新提交。'
    }
  } finally {
    busy.value = false
    controller = null
    activeMessage = null
    if (valid()) {
      message.orderFlow.pending = ''
      persist()
    }
  }
}
async function refreshRun(message) {
  if (!message.runId || busy.value) return
  const token = user.token
  try {
    const run = await workbenchRun(token, message.runId)
    if (token !== user.token || disposed) return
    const recovered = recoverWorkbenchResult(message, run)
    persist()
    if (!recovered) ElMessage.info('已查询运行状态，尚无可恢复的最终回答。')
  } catch (error) {
    ElMessage.warning(error.message)
  }
}
async function copy(text) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制回答')
  } catch {
    ElMessage.info('复制不可用，请选中文字复制。')
  }
}
const displayAnswer = (message) =>
  message.result?.presentation === 'compact_stream' ? message.text :
  compactBusinessAnswer(message) || (
  isInventoryOnly(message) ? '已查询到以下库存信息：' :
  isProductionOnly(message) ? '已查询到以下生产工单：' :
  message.result?.queryMode === 'NATURAL' || message.result?.workflowProfile === 'AUTO'
    ? message.text
    : message.text
        .split(
          /\n\n(?:ORDER|SUPPLY|PRODUCTION|QUALITY|EQUIPMENT|AFTERSALES)补充说明：/,
        )[0]
        .split('缺失/限制：')[0]
        .replace(/^已启动的专家：.*\n*/m, '')
        .replace(/^数据复核时间：.*\n*/m, '')
        .replace(/^范围：本机只读预览.*\n*/m, ''))
const answerParts = (message) => {
  const content = displayAnswer(message)
  const split = content.indexOf('\n')
  if (split < 0 || !(hasBusinessTables(message) || inventorySnapshot(message))) return { intro: content, analysis: '' }
  return { intro: content.slice(0, split), analysis: content.slice(split).trim() }
}
</script>

<template>
  <div class="agent-workbench" :class="{ 'has-messages': messages.length, 'inspector-open': inspectorOpen, 'history-collapsed': historyCollapsed }">
    <button v-if="showHistory" class="history-overlay" aria-label="关闭会话列表" @click="showHistory = false" />
    <aside class="workbench-history" :class="{ 'is-open': showHistory }" aria-label="新助手会话">
      <div class="history-heading"><span class="brand-mark" aria-hidden="true"><Monitor /></span><strong>生产协作助手</strong><button class="icon-button history-collapse" aria-label="收起会话栏" @click="historyCollapsed = true"><PanelLeft /></button><button class="icon-button mobile-close" aria-label="关闭会话列表" @click="showHistory = false"><Close /></button></div>
      <div class="sidebar-actions">
        <button class="new-conversation" @click="newSession"><Plus /><span>新对话</span></button>
        <label class="history-search"><Search /><input v-model="search" placeholder="搜索对话…" aria-label="搜索会话" /></label>
      </div>
      <div class="history-label"><span>工作空间</span><span class="workspace-tag">MES</span></div>
      <div class="workspace-folder"><ChevronRight /><span>生产协作</span><span>{{ sessions.length }}</span></div>
      <nav class="session-list" aria-label="会话列表">
        <div v-for="session in visibleSessions" :key="session.id" class="session-item" :class="{ active: activeId === session.id }">
          <button :aria-current="activeId === session.id ? 'page' : undefined" @click="selectSession(session)"><MessageSquare /><span>{{ session.title }}</span></button>
          <button class="session-delete" :disabled="busy" aria-label="删除此会话" @click="removeSession(session.id)"><Delete /></button>
        </div>
        <p v-if="!visibleSessions.length" class="history-empty">{{ search ? '没有匹配的对话' : '开始一次生产协作，对话将保存在这里。' }}</p>
      </nav>
      <div class="history-bottom">
        <router-link :to="user.dashboardPath"><PanelLeft /><span>业务系统</span><ArrowUpRight /></router-link>
        <div class="account-row"><span class="account-avatar">{{ user.displayName?.slice(0, 1) || 'M' }}</span><span class="account-name">{{ user.displayName }}<small>MES 工作空间</small></span><span class="account-presence" /></div>
      </div>
    </aside>
    <el-drawer v-model="businessToolsOpen" title="业务指令与语音" size="min(680px, 100vw)" append-to-body destroy-on-close>
      <BusinessActions v-if="businessToolsOpen" embedded />
    </el-drawer>
    <main class="workbench-main">
      <header class="workbench-header">
        <button class="icon-button mobile-history" aria-label="打开会话列表" @click="toggleHistory"><PanelLeft /></button>
        <div class="workspace-breadcrumb"><span>生产协作</span><ChevronRight /><strong class="assistant-header-title">{{ active?.title || '新对话' }}</strong></div>
        <button class="icon-button" title="业务指令与语音" aria-label="业务指令与语音" @click="businessToolsOpen = true"><MessageSquare /></button>
        <button class="connection-status" :class="{ online: ready }" :disabled="checking" aria-label="刷新连接状态" @click="checkConnection"><i />{{ statusText }}<Loading v-if="checking" class="spin" /></button>
        <button class="icon-button inspector-toggle" :aria-pressed="inspectorOpen" :aria-label="inspectorOpen ? '收起 Agent 面板' : '展开 Agent 面板'" @click="inspectorOpen = !inspectorOpen"><PanelRight /></button>
      </header>
      <div class="chat-stage" :class="{ 'is-empty': !messages.length }">
        <section v-if="!messages.length" class="workbench-welcome">
            <div class="welcome-eyebrow"><Layers /> MES WORKSPACE</div>
            <h1>从一个生产问题开始</h1>
            <p>{{ admin ? '从查询、分析到下单，让每一步都有依据。' : '描述订购需求，核对信息后提交订单审核。' }}</p>
        </section>
        <div v-show="messages.length" ref="list" class="conversation-scroll" @scroll.passive="trackScroll">
          <div class="conversation-content">
            <article v-for="message in messages" :key="message.id" class="workbench-message" :class="message.role">
              <template v-if="message.role === 'user'"><div class="user-bubble">{{ message.text }}</div><div v-if="legacyMessageContext(message)" class="user-context">{{ legacyMessageContext(message) }}</div></template>
              <template v-else>
                <OrderFlowCard v-if="message.orderFlow" :flow="message.orderFlow" :busy="busy || !orderReady" :customer="user.userInfo?.roleCode === 'CUSTOMER'" :historical="historicalOrder(message)" @action="orderAction(message, $event)" />
                <AgentWorkflowCard v-else :message="message" />
                <p v-if="message.orderIntakeNotice" class="message-error" role="status">{{ message.orderIntakeNotice }}</p>
                <div v-if="!message.orderFlow && message.status === 'RUNNING' && !message.agents.length && !message.workflow" class="waiting-line"><Loading class="spin" />{{ message.runId ? '任务已接收，等待协调执行…' : '正在连接协调服务…' }}</div>
                <div v-if="message.text" :id="`answer-${message.id}`" class="answer-content"><WorkbenchMarkdown :content="answerParts(message).intro" :smooth="message.result?.presentation === 'compact_stream'" :streaming="message.status === 'RUNNING'" :stopped="['FAILED','CANCELLED','INTERRUPTED'].includes(message.status)" :cursor="!answerParts(message).analysis" @progress="followScroll()" /></div>
                <InventoryAnswer :message="message" />
                <BusinessQueryAnswer :message="message" />
                <div v-if="answerParts(message).analysis" class="answer-analysis"><WorkbenchMarkdown :content="answerParts(message).analysis" :smooth="message.result?.presentation === 'compact_stream'" :streaming="message.status === 'RUNNING'" :stopped="['FAILED','CANCELLED','INTERRUPTED'].includes(message.status)" @progress="followScroll()" /></div>
                <KnowledgeSources :message="message" />
                <div v-if="message.error" class="message-error"><span>{{ message.error }}</span><button v-if="message.runId && message.status !== 'CANCELLED'" @click="refreshRun(message)">查询运行结果</button></div>
                <div v-if="message.text" class="message-actions"><button @click="copy(message.text)"><CopyDocument />复制</button><button v-if="canExportResult(message)" aria-label="导出完整查询 CSV" @click="exportResult(message)"><Download />CSV</button></div>
              </template>
            </article>
          </div>
        </div>
        <footer class="composer-area">
          <div v-if="statusHint" class="connection-notice" role="status"><span>{{ statusHint }}</span><button v-if="!busy" @click="checkConnection">重新连接</button></div>
          <input ref="attachmentInput" class="attachment-input" type="file" accept=".txt,.md,.csv,.json" aria-label="选择文本资料" @change="attachTextFile" />
          <WorkbenchComposer ref="composer" v-model="input" :welcome="!messages.length" :busy="busy" :can-send="canSend" @send="send" @stop="stop" @attach="attachmentInput?.click()" />
        </footer>
        <nav v-if="!messages.length" class="welcome-shortcuts" aria-label="快捷提问">
          <div class="suggestions"><button v-for="item in introductory" :key="item.title" @click="chooseExample(item)"><span class="suggestion-icon"><component :is="item.icon" /></span><span class="suggestion-copy"><strong>{{ item.title }}</strong><small>{{ item.description }}</small></span><ArrowUpRight /></button></div>
        </nav>
      </div>
    </main>
    <aside v-if="inspectorOpen" class="workbench-inspector" aria-label="Agent 执行检查面板"><AgentInspector :message="inspectedMessage" @close="inspectorOpen = false" /></aside>
  </div>
</template>

<style scoped src="./workbench-theme.css"></style>
