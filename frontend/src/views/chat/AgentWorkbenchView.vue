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
import ChatDetectionFrame from '@/components/assistant/ChatDetectionFrame.vue'
import DisplayMark from '@/components/assistant/DisplayMark.vue'
import { PencilLine, RotateCcw, GitBranch, Share2 } from 'lucide-vue-next'
import AgentInspector from '@/components/assistant/AgentInspector.vue'
import AnswerEvidence from '@/components/assistant/AnswerEvidence.vue'
import KnowledgeSources from '@/components/assistant/KnowledgeSources.vue'
import ExperienceEvidence from '@/components/assistant/ExperienceEvidence.vue'
import VisionImageEvidence from '@/components/assistant/VisionImageEvidence.vue'
import { inspectionImageRecords } from '@/utils/workbenchEvidence'
import { canExportResult, resultCsv } from '@/utils/knowledgeSources'
import WorkbenchMarkdown from '@/components/assistant/WorkbenchMarkdown.vue'
import {
  workbenchStatus,
  streamWorkbench, continueImage, chatEntry, previewProductionOperation, decideProductionOperation,
  workbenchRun,
} from '@/api/agentWorkbench'
import {
  AGENTS,
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
import ProductionOperationCard from '@/components/assistant/ProductionOperationCard.vue'
import ProductionCalculationFlow from '@/components/assistant/ProductionCalculationFlow.vue'
import AgentCollaborationStream from '@/components/assistant/AgentCollaborationStream.vue'
import WorkbenchComposer from '@/components/assistant/WorkbenchComposer.vue'
import WorkbenchModeSelector from '@/components/assistant/WorkbenchModeSelector.vue'
import { isDirectSource } from '@/utils/workbenchModes'
import { precedingWorkRun, imageFollowup, comparePhoto } from '@/utils/imageConversation'
import { prefersReadOnlyQuery, entryContext, productionPreviewContext, productionPreviewIntent } from '@/utils/workbenchRouting'

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
// Annotated photos are session-memory only; chat history stores text.
const imagePreviews = new WeakMap()
const pendingImage = ref(null)
const pendingThumbnail = ref('')
watch(pendingImage, (file, _, cleanup) => {
  pendingThumbnail.value = ''
  if (!file) return
  const url = URL.createObjectURL(file)
  pendingThumbnail.value = url
  cleanup(() => URL.revokeObjectURL(url))
})
const imageMode = ref('auto')
function clearImage() { pendingImage.value = null; imageMode.value = 'auto' }
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
const proposalSessions = new WeakMap()
const productionAllowed = computed(() => ['ADMIN', 'ORDER', 'PLANNER', 'MANAGER', 'OPERATOR'].includes(user.userInfo?.roleCode))
const workflowModel = ref('API')
const bindingReady = computed(() => connection.value?.workflowModels
  ? connection.value.workflowModels[workflowModel.value] === true : workflowModel.value === 'API')
let statusRequest = 0
const followTail = ref(true)
const orderAllowed = computed(() => ['ADMIN', 'ORDER', 'CUSTOMER'].includes(user.userInfo?.roleCode))
const orderReady = computed(() => orderAllowed.value && orderConnection.value?.available === true)
const readDomains = computed(() => connection.value?.capabilities?.domains)
const admin = computed(() => user.userInfo?.roleCode === 'ADMIN')
const active = computed(() =>
  sessions.value.find((s) => s.id === activeId.value),
)
const messages = computed(() => active.value?.messages || [])
const composerArea = ref(null)
watch(() => messages.value.length > 0, async (hasMessages, hadMessages) => {
  const element = composerArea.value
  if (!hasMessages || hadMessages || !busy.value || !element?.animate || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
  const top = element.getBoundingClientRect().top
  await nextTick()
  if (element.isConnected) element.animate([
    { transform: 'translateY(' + (top - element.getBoundingClientRect().top) + 'px)' },
    { transform: 'translateY(0)' },
  ], { duration: 220, easing: 'cubic-bezier(.2,.7,.2,1)' })
})
const inspectedMessage = computed(() => messages.value.findLast((message) => message.role === 'assistant') || null)
function attachTextFile(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (file) return attachFile(file)
}
function pasteFiles(files) {
  if (busy.value) { ElMessage.warning('请等当前回答结束后再粘贴附件。'); return }
  if (files.length > 1) { ElMessage.warning('请一次粘贴一个附件。'); return }
  if (files.length) return attachFile(files[0])
}
async function attachFile(file) {
  if (busy.value) return
  if (/^image\/(png|jpeg)$/i.test(file.type) || /\.(png|jpe?g)$/i.test(file.name)) {
    if (workflowModel.value === 'LOCAL') { ElMessage.warning('微调 v2 是文本模型。分析图片请切换到 API模型。'); return }
    if (file.size > 6 * 1024 * 1024) { ElMessage.warning('请选择 6 MB 内的图片。'); return }
    pendingImage.value = file
    imageMode.value = 'auto'
    composer.value?.focus()
    return
  }
  if (!/\.(txt|md|csv|json)$/i.test(file.name) || file.size > 8000) {
    ElMessage.warning('支持 JPG、PNG 图片，以及 8 KB 内的 TXT、Markdown、CSV 或 JSON 文本。')
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
const queryReady = computed(() => (admin.value || Array.isArray(connection.value?.capabilities?.domains)) && (connection.value?.workflowModels ? bindingReady.value : connection.value?.configured === true) && connection.value?.naturalLanguage === true)
const entryAvailable = computed(() => connection.value?.directChat === true || connection.value?.productionActions === true)
const ready = computed(() => bindingReady.value && (orderReady.value || queryReady.value || entryAvailable.value))
const needsUpgrade = computed(() => connection.value != null && connection.value.naturalLanguage !== true)
const BusinessActions = defineAsyncComponent(() => import('./ChatHomeView.vue'))
const businessToolsOpen = ref(false)
const graphWindow = ref('')
const WorkbenchGraphWindow = defineAsyncComponent(() => import('@/components/assistant/WorkbenchGraphWindow.vue'))
watch([activeId, () => user.token], () => { graphWindow.value = '' })
const canSend = computed(() => !busy.value && ready.value && (input.value.trim().length > 0 || Boolean(pendingImage.value)))
const statusText = computed(() => checking.value ? '正在连接' : ready.value ? '已连接' : needsUpgrade.value ? '服务待升级' : '服务未连接')
const statusHint = computed(() => {
  if (ready.value) return ''
  if (!connection.value) return connectionError.value || '无法连接生产协作工作流。'
  if (!bindingReady.value) return workflowModel.value === 'LOCAL' ? '本地工作流尚未就绪。Ubuntu 上线并完成微调 v2 的首次发布后，请点「重新连接」。' : '云端 API 工作流尚未配置。'
  return needsUpgrade.value ? '当前协作服务尚未升级为自然语言查询，请联系维护人员升级后重新连接。' : '工作流暂不可用，请重新连接。'
})
const productionExamples = {
  ADMIN: { title: '智能排产', description: '制定生产计划或安排工序派工', prompt: '帮我智能排产。' },
  PLANNER: { title: '智能制定生产计划', description: '比较排程方案并保存生产计划', prompt: '帮我智能制定生产计划。' },
  MANAGER: { title: '智能排产', description: '核对工序、设备与操作员', prompt: '帮我为生产计划安排智能派工。' },
  OPERATOR: { title: '接收派工', description: '核对派工单并接收任务', prompt: '我要接收派工。' },
  ORDER: { title: '提交给计划员', description: '将已审核订单转入计划', prompt: '帮我把订单提交给计划员。' },
}
const productionExample = computed(() => {
  const role = user.userInfo?.roleCode
  if (!connection.value?.productionActions && !['PLANNER', 'MANAGER', 'OPERATOR'].includes(role)) return null
  return productionExamples[role]
})
const introductory = computed(() => [
  ...(orderAllowed.value ? [{ title: '创建订单', icon: ClipboardPlus, description: '产品选型与订单草稿', prompt: '我想下单，请帮我选择产品并填写订单信息。' }] : []),
  ...(productionExample.value ? [{ ...productionExample.value, icon: Factory }] : []),
  ...((admin.value || queryReady.value) ? [
    { title: '查库存', icon: PackageSearch, description: '物料、仓库与可用数量', prompt: '帮我查一下当前库存，列出需要关注的物料。' },
    { title: '看生产进度', icon: Factory, description: '工单进展与生产状态', prompt: '帮我看看当前生产进度，哪些工单需要关注？' },
    { title: '分析质量问题', icon: ScanLine, description: '检验结果与缺陷依据', prompt: '查询质检情况，列出检验记录并概括主要质量问题。' },
  ].filter(item => { const domain = ({ 查库存: 'INVENTORY', 看生产进度: 'PRODUCTION', 分析质量问题: 'QUALITY' })[item.title]; return readDomains.value?.includes(domain) ?? admin.value }) : []),
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
function setWorkflowModel(next) {
  if (busy.value || !['API','LOCAL'].includes(next) || workflowModel.value === next) return
  if (next === 'LOCAL' && pendingImage.value) { ElMessage.warning('微调 v2 支持文本。请先发送或移除图片。'); return }
  if (active.value?.messages.length) {
    const draft = input.value
    newSession()
    input.value = draft
  }
  workflowModel.value = next
}
function stop() {
  controller?.abort()
  if (activeMessage?.productionFlow?.deciding) {
    activeMessage.error = '已停止等待，请核对原方案的执行结果。'
  } else if (activeMessage?.orderFlow) {
    activeMessage.orderFlow.pending = ''
    activeMessage.orderFlow.needsRecovery = true
    interruptMessage(activeMessage, 'INTERRUPTED', '已停止等待；请刷新草稿状态，未自动重新提交。')
  } else if (activeMessage?.modelSource && isDirectSource(activeMessage.modelSource)) {
    interruptMessage(activeMessage, 'CANCELLED', '已停止等待本轮模型回复。')
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
  const sequence = ++statusRequest
  checking.value = true
  connectionError.value = ''
  const [orderResult, queryResult] = await Promise.allSettled([
    orderAllowed.value ? orderStatus(token) : Promise.resolve(null),
    workbenchStatus(token),
  ])
  if (disposed || token !== user.token || sequence !== statusRequest) return
  orderConnection.value = orderResult.status === 'fulfilled' ? orderResult.value : null
  connection.value = queryResult.status === 'fulfilled' ? queryResult.value : null
  if ([queryResult, orderResult].some(result => result.status === 'rejected' && [401, 403].includes(result.reason?.code))) {
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
    clearImage()
    owner = id
    statusRequest++
    workflowModel.value = 'API'
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
  workflowModel.value = 'API'
  clearImage()
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
  clearImage()
  if (busy.value) stop()
  followTail.value = true
  activeId.value = session.id
  workflowModel.value = session.workflowModel === 'LOCAL' ? 'LOCAL' : 'API'
  input.value = ''
  recoverSessionOrders(session)
  showHistory.value = false
  nextTick(() => {
    if (list.value) list.value.scrollTop = list.value.scrollHeight
  })
}
const editingTitle = ref(null)
const titleDraft = ref('')
function editTitle(session, event) {
  editingTitle.value = session.id
  titleDraft.value = session.title
  const row = event.currentTarget.closest('.session-item')
  nextTick(() => row?.querySelector('input')?.select())
}
function finishTitle(session) {
  if (editingTitle.value !== session.id) return
  const title = titleDraft.value.trim()
  if (title) {
    session.title = title
    session.renamed = true
    persist()
  }
  editingTitle.value = null
}
function removeSession(id) {
  if (busy.value) return
  sessions.value = sessions.value.filter((s) => s.id !== id)
  if (activeId.value === id) activeId.value = null
  persist()
}
async function sendWorkflowCommand(text) {
  if (busy.value) return
  businessToolsOpen.value = false
  input.value = text
  await send()
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
function canRegenerate(message) {
  return !message.modelSource && message === messages.value.at(-1) && message.status === 'COMPLETED' &&
    !busy.value && !input.value && !pendingImage.value && !message.imageQuestion &&
    !message.orderMessage && !message.orderFlow && !message.productionFlow && !message.evidenceInvalidated &&
    messages.value.at(-2)?.role === 'user' && !messages.value.at(-2)?.orderMessage &&
    (message.directChat ? connection.value?.directChat : queryReady.value && message.runId)
}
async function send(replay = null, supplement = null) {
  if (supplement ? busy.value : replay ? !canRegenerate(replay) : !canSend.value) return
  const imageFile = pendingImage.value, photoMode = imageMode.value
  const typed = supplement?.description || (replay ? messages.value.at(-2).text : input.value.trim())
  const text = typed || (photoMode === 'screen' ? '检测这张屏幕照片上的划痕' : '这张图片里有什么？'),
    token = user.token,
    capturedOwner = owner
  let session = active.value
  const selected = workflowModel.value
  const modelInput = selected === 'LOCAL' || connection.value?.workflowModels ? { workflowModel:selected } : {}
  // Direct-chat history remains readable. A new workflow must acquire fresh evidence and its own task scope.
  if (isDirectSource(session?.modelSource)) session = null
  if (!session) {
    const id = uniqueId()
    sessions.value.unshift({
      id,
      title: text.slice(0, 25),
      profile: 'AUTO',
      modelSource: 'WORKFLOW',
      workflowModel: selected,
      updatedAt: Date.now(),
      messages: [],
    })
    sessions.value = sessions.value.slice(0, 20)
    activeId.value = id
    session = active.value
  }
  const previousMessages = replay ? session.messages.slice(0, -2) : session.messages.slice()
  const previous = previousMessages.at(-1)
  if (!imageFile && !replay && previous?.productionFlow?.status === 'PENDING' &&
      /^(?:好[的啊]?|嗯|是的?|行|可以|确认(?:执行)?|同意|确定|执行|取消|跳过|不执行|ok)[\s!！。]*$/i.test(text)) {
    previous.error = '请核对方案，在卡片上选择确认执行或取消。'
    input.value = ''
    followScroll(true)
    return
  }
  const photoTurn = imageFile || replay ? null : imageFollowup(previousMessages, text)
  const previousTask = previousMessages.findLast(item => item.productionFlow)
  let needsProductionPreview = !!supplement || (!replay && !imageFile && !photoTurn && productionAllowed.value && productionPreviewIntent(text, previousTask || previous))
  // Explicit production requests already have a safe preview destination. Do not let
  // the general classifier turn them into order intake or a conversational answer.
  const classify = !needsProductionPreview && !supplement && !imageFile && !photoTurn && (!replay || replay.directChat) && entryAvailable.value
  const context = classify ? entryContext(previousMessages) : null
  const workAnchor = imageFile ? precedingWorkRun(session.messages) : null
  session.updatedAt = Date.now()
  session.messages.push({
    id: uniqueId(),
    role: 'user',
    text,
    profile: 'AUTO',
    orderMessage: orderReady.value,
    workflowModel: selected,
  })
  session.messages.push(createAssistantMessage())
  const message = session.messages.at(-1)
  message.conversationId = session.id
  message.workflowModel = selected
  let needsOrderIntake = !needsProductionPreview && !replay && !imageFile && !photoTurn && orderReady.value && !prefersReadOnlyQuery(text, { canQuery: queryReady.value })
  if (!classify && !needsOrderIntake) session.messages.at(-2).orderMessage = false
  if (!classify && needsOrderIntake) message.orderFlow = { steps: [], classifying: true }
  activeMessage = message
  if (imageFile) { clearImage(); message.imageUpload = true }
  if (imageFile || photoTurn) message.imageQuestion = true
  if (!supplement) input.value = ''
  busy.value = true
  controller = new AbortController()
  persist()
  followScroll(true)
  try {
    if (classify) {
      message.routing = true
      const entry = await chatEntry(token, { text, ...context, ...modelInput }, {
        signal: controller.signal,
        onDelta: delta => {
          if (disposed || owner !== capturedOwner || token !== user.token || message.status !== 'RUNNING') return
          message.directChat = true
          message.text += delta
          followScroll()
        },
      })
      if (disposed || owner !== capturedOwner || token !== user.token || message.status !== 'RUNNING') return
      if (modelInput.workflowModel && entry?.workflowModel !== selected) throw new Error('工作流未确认本次模型选择，请重新连接。')
      const route = entry.route
      delete message.routing
      if (route === 'GENERAL' || route === 'WEATHER') {
        session.messages.at(-2).orderMessage = false
        message.directChat = true
        message.status = 'COMPLETED'
        return
      }
      if (replay && ['ORDER', 'PRODUCTION_ACTION'].includes(route)) throw new Error('该问题涉及业务办理，请单独发起请求。')
      if (route === 'ORDER' && !orderReady.value) throw new Error('当前账号或连接无法办理订单。')
      if (!['BUSINESS','ORDER','PRODUCTION_ACTION','FALLBACK'].includes(route)) throw new Error('对话分流结果无效，请重试。')
      if (route !== 'FALLBACK') {
        needsProductionPreview = route === 'PRODUCTION_ACTION'
        needsOrderIntake = route === 'ORDER'
      }
      session.messages.at(-2).orderMessage = needsOrderIntake
      if (needsOrderIntake) message.orderFlow = { steps: [], classifying: true }
    }
    if (needsProductionPreview) {
      if (!productionAllowed.value) throw new Error('当前账号没有生产办理权限。')
      message.productionFlow = { status: 'PREPARING', steps: [] }
      const step = beginProductionRequest(message.productionFlow, 'production.preview', '准备生产操作预览', '正在请求业务服务预览；尚未写入业务数据。')
      const request = supplement ? { text: '', actionCode: supplement.actionCode, fields: supplement.fields }
        : { text, ...productionPreviewContext(previous, text) }
      const history = previousMessages.slice(-6).map(item => ({ role: item.role, text: String(item.text || item.result?.reply || '').slice(0, 1200) }))
      const preview = await previewProductionOperation(token, { ...request, sessionId: session.id, history, ...modelInput }, {
        signal: controller.signal,
        onEvent: (event) => {
          if (disposed || owner !== capturedOwner || token !== user.token || activeId.value !== session.id || message.status !== 'RUNNING') return
          if (['run', 'workflow', 'delta'].includes(event.type)) applyWorkbenchEvent(message, event)
          followScroll()
        },
      })
      if (disposed || owner !== capturedOwner || token !== user.token || activeId.value !== session.id || message.status !== 'RUNNING') return
      if (modelInput.workflowModel && preview?.workflowModel !== selected) throw new Error('生产服务未确认本轮工作流模型。')
      if (!['PENDING', 'NEEDS_INPUT', 'UNHANDLED', 'COMPLETED'].includes(preview?.status) ||
          (preview.status === 'PENDING' && (!preview.confirmation?.proposalId || !preview.confirmation.params))) {
        throw new Error('生产服务未返回完整的方案，请重新发起请求。')
      }
      finishProductionRequest(step, 'COMPLETED', productionPreviewSummary(preview))
      message.productionFlow = { ...preview, steps: message.productionFlow.steps }
      message.text = preview.message || (preview.status === 'UNHANDLED' ? '请说明要办理的生产操作和对应的订单、计划或派工编号。' : '')
      message.status = 'COMPLETED'
      if (['PENDING', 'NEEDS_INPUT'].includes(preview.status)) proposalSessions.set(message, { token, sessionId: session.id })
      followScroll()
      return
    }
    if (imageFile) {
      const body = new FormData()
      body.append('file', imageFile)
      body.append('mode', photoMode)
      body.append('sessionId', session.id)
      if (workAnchor) body.append('workRun', workAnchor)
      body.append('question', text)
      const response = await fetch('/api/assistant-v2/images/ask', {
        method: 'POST', headers: { Authorization: 'Bearer ' + token }, body, signal: controller.signal,
      })
      const result = await response.json().catch(() => null)
      if (!response.ok || result?.code !== 200 || !result?.data?.answer)
        throw new Error(result?.message || '图片问答服务暂不可用，请确认后端已加载图片接口。')
      if (capturedOwner !== owner || token !== user.token || disposed || message.status !== 'RUNNING') return
      if (typeof result.data.annotatedImage === 'string' && result.data.annotatedImage.startsWith('data:image/jpeg;base64,'))
        imagePreviews.set(message, result.data.annotatedImage)
      if (/^\d+$/.test(result.data.detectionCount) && imagePreviews.has(message))
        message.frameCount = Number(result.data.detectionCount)
      message.imageStreaming = true
      message.imageRef = result.data.imageRef
      message.text = result.data.answer
      message.status = 'COMPLETED'
      followScroll()
      return
    }
    let photoEvidence
    if (photoTurn) {
      if (selected === 'LOCAL') throw new Error('微调 v2 支持文本。图片追问请使用原来的 API模型会话。')
      message.imageStreaming = true
      photoEvidence = await continueImage(token, { imageRef: photoTurn.imageRef, action: photoTurn.action, sessionId: session.id, question: text }, {
        signal: controller.signal,
        onDelta: (delta) => {
          if (disposed || owner !== capturedOwner || user.token !== token || message.status !== 'RUNNING') return
          if (photoTurn.action === 'compare') message.photoObservation = (message.photoObservation || '') + delta
          else message.text += delta
          followScroll()
        },
      })
      if (disposed || owner !== capturedOwner || user.token !== token || message.status !== 'RUNNING') return
      if (photoTurn.laterWork !== undefined) photoEvidence.workRun = photoTurn.laterWork
      if (photoTurn.action !== 'compare' || !photoEvidence?.workRun) {
        if (photoTurn.action === 'compare') message.text = '当前会话中没有唯一可核对的生产工单。'
        message.status = 'COMPLETED'
        return
      }
    }
    if (needsOrderIntake) {
      let envelope
      try {
        envelope = await orderTurn(token, { sessionId: session.id, requestId: message.id, text, ...modelInput }, { signal: controller.signal })
        if (modelInput.workflowModel && envelope?.workflowModel !== selected) {
          throw new Error('订单服务未确认本轮工作流模型。')
        }
      } catch (error) {
        const unavailable = error.name === 'TypeError' || error.code === 503
        if (selected === 'LOCAL' || !unavailable || error.name === 'AbortError' || controller.signal.aborted || !queryReady.value) throw error
        // Intake never confirms an order. Continue the existing read-only path once,
        // retaining privacy markers because the unclassified text may contain PII.
        message.orderIntakeNotice = '订单信息识别暂不可用，当前仅执行只读查询。订单草稿状态请稍后核对。'
        message.orderMessage = true
        envelope = { handled: false }
      }
      if (capturedOwner !== owner || token !== user.token || disposed || controller?.signal.aborted) return
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
      photoTurn?.action === 'compare'
        ? { ...modelInput, sessionId: session.id, requestId: message.id, previousRunId: photoEvidence.workRun, text: '这张工单的质检情况怎么样' }
        : { ...buildNaturalRequest(replay ? { ...session, messages: previousMessages } : session, text, message.id), ...modelInput },
      {
        signal: controller.signal,
        onEvent: (event) => {
          if (capturedOwner !== owner || token !== user.token || disposed)
            return
          if (event.type === 'result' && modelInput.workflowModel && event.data?.workflowModel !== selected)
            throw new Error('工作流返回的模型与本轮选择不一致。')
          applyWorkbenchEvent(message, event)
          if (photoTurn?.action === 'compare' && event.type === 'result' && !message.evidenceInvalidated)
            message.photoComparison = comparePhoto(photoEvidence.count, message.result)
          if (['run', 'agent', 'result', 'workflow'].includes(event.type)) persist()
          if (activeId.value === session.id) followScroll()
        },
      },
    )
  } catch (error) {
    if (capturedOwner === owner && token === user.token && !disposed) {
      if (message.productionFlow?.status === 'PREPARING') {
        message.productionFlow.status = 'FAILED'
        finishProductionRequest(message.productionFlow.steps?.at(-1), error.name === 'AbortError' ? 'CANCELLED' : 'FAILED',
          error.name === 'AbortError' ? '已停止等待预览响应；未请求业务写入。' : '预览请求未成功返回；未请求业务写入。')
      }
      if (message.orderFlow) {
        delete message.orderFlow.classifying
        message.orderFlow.pending = ''
        message.orderFlow.needsRecovery = true
      }
      if (error.name !== 'AbortError' && !message.runId && !message.orderFlow && !input.value && activeId.value === session.id) {
        input.value = typed
        if (imageFile) { pendingImage.value = imageFile; imageMode.value = photoMode }
      }
      interruptMessage(
        message,
        error.name === 'AbortError' && !message.orderFlow ? 'CANCELLED' : error.taskStatus || 'INTERRUPTED',
        error.name === 'AbortError' ? message.orderFlow ? '已停止等待；请刷新草稿状态，未自动重新提交。' : '已停止生成并请求取消本次任务。' : error.message,
      )
    }
  } finally {
    delete message.routing
    busy.value = false
    controller = null
    activeMessage = null
    if (capturedOwner === owner && token === user.token && !disposed) persist()
  }
}
function beginProductionRequest(flow, toolId, label, detail) {
  const at = new Date().toISOString()
  flow.steps ||= []
  flow.steps.push({ agentId: 'SERVICE', toolId, label, detail, status: 'RUNNING', startedAt: at, at })
  return flow.steps.at(-1)
}
function finishProductionRequest(step, status, detail) {
  if (step) Object.assign(step, { status, detail, at: new Date().toISOString() })
}
function productionPreviewSummary(preview) {
  const details = []
  for (const [key, label] of [['orderId', '待计划订单'], ['planId', '可排产计划'], ['planningTask', '办理方向']]) {
    if (Array.isArray(preview.options?.[key])) details.push(`返回 ${preview.options[key].length} 个${label}`)
  }
  if (preview.schemes?.length) details.push(`返回 ${preview.schemes.length} 种排产方案`)
  for (const [key, label] of [['schedules', '工序排程'], ['recommendations', '派工建议']]) {
    if (Array.isArray(preview.confirmation?.params?.[key])) details.push(`返回 ${preview.confirmation.params[key].length} 项${label}`)
  }
  details.push(preview.status === 'PENDING' ? '方案待确认，未写入业务数据' : preview.status === 'NEEDS_INPUT' ? '等待补充或选择，未写入业务数据' : '未生成可执行方案')
  return details.join('；') + '。'
}
function changeProductionOperator(message, change) {
  const flow = message.productionFlow, scope = proposalSessions.get(message)
  if (busy.value || pendingImage.value || !productionAllowed.value || !scope || scope.token !== user.token ||
      scope.sessionId !== activeId.value || message !== messages.value.at(-1) || flow?.status !== 'PENDING' ||
      flow.actionCode !== 'confirmSmartDispatch' || flow.uncertainDecision) return
  const row = flow.confirmation.params?.recommendations?.find(row => row.processStep === change.processStep)
  const option = flow.confirmation.operatorOptions?.find(option => option.username === change.username && option.processStep === change.processStep && !option.disabled)
  if (!row || !option || row.recommendedOperator === option.username) return
  return send(null, { actionCode: flow.actionCode, fields: { editProposalId: flow.confirmation.proposalId, assignmentOverrides: JSON.stringify({ [change.processStep]: option.username }) }, description: `将${change.processStep}调整为${option.name || option.username}，重新校验派工方案` })
}
function supplementProduction(message, supplied) {
  const flow = message.productionFlow, scope = proposalSessions.get(message)
  if (busy.value || !productionAllowed.value || !scope || scope.token !== user.token || scope.sessionId !== activeId.value ||
      message !== messages.value.at(-1) || flow?.status !== 'NEEDS_INPUT' || pendingImage.value) return
  const fields = { ...flow.fields }, description = []
  for (const field of flow.missingFields || []) {
    const value = String(supplied?.[field.name] || '').trim()
    if (!value) return
    fields[field.name] = value
    description.push(field.label + '：' + value)
  }
  if (description.length) return send(null, { actionCode: flow.actionCode, fields, description: description.join('，') })
}
async function productionDecision(message, decision) {
  const flow = message.productionFlow, scope = proposalSessions.get(message), token = user.token
  if (busy.value || !productionAllowed.value || !scope || scope.token !== token || scope.sessionId !== activeId.value ||
      messages.value.at(-1) !== message || !['APPROVE', 'SKIP'].includes(decision) ||
      flow?.status !== 'PENDING' || (flow.uncertainDecision && decision !== flow.uncertainDecision)) return
  const id = flow.confirmation.proposalId
  const valid = () => !disposed && token === user.token && sessions.value.some(s => s.id === scope.sessionId)
  busy.value = true
  activeMessage = message
  controller = new AbortController()
  flow.deciding = decision
  const step = beginProductionRequest(flow, 'production.decision', decision === 'APPROVE' ? '提交生产操作确认' : '取消生产操作方案',
    '已发送当前方案的处理决定，等待业务服务返回。')
  message.error = ''
  try {
    const reply = await decideProductionOperation(token, id, decision, { signal: controller.signal, ...(flow.taskManaged ? { sessionId: scope.sessionId } : {}) })
    if (!valid()) return
    if (reply?.proposalId !== id || !['EXECUTED', 'REJECTED'].includes(reply.status)) throw new Error('业务服务返回的方案或处理状态不一致')
    flow.status = reply.status
    flow.result = reply.result
    finishProductionRequest(step, 'COMPLETED', reply.status === 'REJECTED' ? '业务服务确认方案已取消。'
      : reply.result?.allDone === false ? '业务服务返回部分完成，请核对未完成项目。' : '业务服务确认操作已执行。')
    message.text = reply.result?.message || reply.reply || reply.message || ''
    proposalSessions.delete(message)
  } catch (error) {
    if (!valid()) return
    if ([400, 401, 403, 404, 409, 422].includes(error.code)) {
      flow.status = 'FAILED'
      finishProductionRequest(step, 'FAILED', '业务服务拒绝本次处理请求，请核对方案状态与权限。')
      message.error = error.message
      proposalSessions.delete(message)
    } else {
      flow.uncertainDecision = decision
      finishProductionRequest(step, 'INTERRUPTED', '未取得确定的处理结果，请核对原方案；未自动重试。')
      message.error = (error.message || '连接中断') + '。操作结果尚未确认，请核对原方案。'
    }
  } finally {
    delete flow.deciding
    activeMessage = null
    controller = null
    busy.value = false
    if (valid()) { persist(); followScroll() }
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
</script>

<template>
  <div class="agent-workbench" :class="{ 'has-messages': messages.length, 'inspector-open': inspectorOpen, 'history-collapsed': historyCollapsed }">
    <button v-if="showHistory" class="history-overlay" aria-label="关闭会话列表" @click="showHistory = false" />
    <aside class="workbench-history" :class="{ 'is-open': showHistory }" aria-label="新助手会话">
      <div class="history-heading"><span class="brand-mark" aria-hidden="true"><DisplayMark /></span><strong>生产协作助手</strong><button class="icon-button history-collapse" aria-label="收起会话栏" @click="historyCollapsed = true"><PanelLeft /></button><button class="icon-button mobile-close" aria-label="关闭会话列表" @click="showHistory = false"><Close /></button></div>
      <div class="sidebar-actions">
        <button class="new-conversation" @click="newSession"><Plus /><span>新对话</span></button>
        <label class="history-search"><Search /><input v-model="search" placeholder="搜索对话…" aria-label="搜索会话" /></label>
      </div>
      <div class="history-label"><span>工作空间</span><span class="workspace-tag">MES</span></div>
      <div class="workspace-folder"><ChevronRight /><span>生产协作</span><span>{{ sessions.length }}</span></div>
      <nav class="session-list" aria-label="会话列表">
        <div v-for="session in visibleSessions" :key="session.id" class="session-item" :class="{ active: activeId === session.id }">
          <input v-if="editingTitle === session.id" v-model="titleDraft" class="session-title-field" maxlength="80" aria-label="会话名称" @blur="finishTitle(session)" @keydown.enter="!$event.isComposing && $event.target.blur()" @keydown.esc="editingTitle = null" />
          <button v-else :aria-current="activeId === session.id ? 'page' : undefined" @click="selectSession(session)"><MessageSquare /><span>{{ session.title }}</span></button>
          <button v-if="editingTitle !== session.id" class="session-delete session-rename" aria-label="重命名会话" title="重命名会话" @click="editTitle(session, $event)"><PencilLine /></button>
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
      <BusinessActions v-if="businessToolsOpen" embedded :workflow-model="workflowModel" @workflow-command="sendWorkflowCommand" />
    </el-drawer>
    <main class="workbench-main">
      <WorkbenchGraphWindow v-if="graphWindow" :key="graphWindow" :mode="graphWindow" :workflow-model="workflowModel" :message="inspectedMessage" :token="user.token" :can-ingest="user.userInfo?.roleCode === 'ADMIN'" @close="graphWindow = ''" />
      <header class="workbench-header">
        <button class="icon-button mobile-history" aria-label="打开会话列表" @click="toggleHistory"><PanelLeft /></button>
        <div class="workspace-breadcrumb"><span>生产协作</span><ChevronRight /><strong class="assistant-header-title">{{ active?.title || '新对话' }}</strong></div>
        <button class="icon-button" title="业务指令与语音" aria-label="业务指令与语音" @click="businessToolsOpen = true"><MessageSquare /></button>
        <div class="graph-launchers">
          <button aria-haspopup="dialog" aria-label="查看 Dify 工作流" title="查看 Dify 工作流" @click="graphWindow = 'workflow'"><GitBranch/><span>Dify 工作流</span></button>
          <button aria-haspopup="dialog" aria-label="查看联合知识图" title="查看联合知识图" @click="graphWindow = 'knowledge'"><Share2/><span>联合知识图</span></button>
        </div>
        <button class="connection-status" :class="{ online: ready }" :disabled="checking" aria-label="刷新连接状态" @click="checkConnection"><i />{{ statusText }}<Loading v-if="checking" class="spin" /></button>
        <button class="icon-button inspector-toggle" :aria-pressed="inspectorOpen" :aria-label="inspectorOpen ? '收起 Agent 面板' : '展开 Agent 面板'" @click="inspectorOpen = !inspectorOpen"><PanelRight /></button>
      </header>
      <div class="chat-stage" :class="{ 'is-empty': !messages.length }">
        <section v-if="!messages.length" class="workbench-welcome">
            <div class="welcome-emblem" aria-hidden="true"><DisplayMark /></div>
            <div class="welcome-eyebrow">MES WORKSPACE</div>
            <h1>从一个生产问题开始</h1>
            <p>{{ productionExample ? '查询生产进度，或提出业务操作并核对执行方案。' : queryReady ? '查询生产现场的数据，跟进工单与质量问题。' : '描述订购需求，核对信息后提交订单审核。' }}</p>
        </section>
        <div v-show="messages.length" ref="list" class="conversation-scroll" @scroll.passive="trackScroll">
          <div class="conversation-content">
            <article v-for="message in messages" :key="message.id" class="workbench-message" :class="message.role">
              <template v-if="message.role === 'user'"><div class="user-bubble">{{ message.text }}</div><div v-if="legacyMessageContext(message)" class="user-context">{{ legacyMessageContext(message) }}</div></template>
              <template v-else>
                <ProductionCalculationFlow :message="message" />
                <AgentCollaborationStream v-if="!message.modelSource && !message.routing && !message.directChat" :message="message" />
                <OrderFlowCard v-if="message.orderFlow && !message.orderFlow.classifying" :flow="message.orderFlow" :busy="busy || !orderReady" :customer="user.userInfo?.roleCode === 'CUSTOMER'" :historical="historicalOrder(message)" @action="orderAction(message, $event)" />
                <ProductionOperationCard v-if="message.productionFlow?.confirmation || message.productionFlow?.planning || message.productionFlow?.status === 'NEEDS_INPUT'" :flow="message.productionFlow" :latest="messages.at(-1) === message" :busy="busy || !!pendingImage" @decision="productionDecision(message, $event)" @supplement="supplementProduction(message, $event)" @operator-change="changeProductionOperator(message, $event)" />
                <small v-if="message.workflowModel" class="model-caption">业务工作流 · {{ message.workflowModel === 'LOCAL' ? '本地模型 · 微调 v2' : 'API模型' }}</small>
                <small v-else-if="isDirectSource(message.modelSource)" class="model-caption">{{ message.modelSource === 'API' ? 'API 模型' : '本地模型' }}{{ message.result?.model ? ' · ' + message.result.model : '' }} · {{ message.ragEnabled ? (message.result?.ragStatus === 'NO_MATCHES' ? 'RAG 未检索到匹配资料' : 'RAG 开启') : 'RAG 关闭' }}</small>
                <p v-if="message.orderIntakeNotice" class="message-error" role="status">{{ message.orderIntakeNotice }}</p>
                <div v-if="(!message.orderFlow || message.orderFlow.classifying) && message.status === 'RUNNING' && !message.directChat && !message.agents.length && !message.workflow" class="waiting-line"><Loading class="spin" />{{ isDirectSource(message.modelSource) ? '正在等待模型回复…' : message.productionFlow ? '正在准备生产方案…' : message.imageQuestion ? '正在分析图片…' : message.routing || message.orderFlow?.classifying ? '正在理解请求…' : message.runId ? '任务已接收，等待协调执行…' : '正在连接协调服务…' }}</div>
                <WorkbenchMarkdown v-if="message.photoObservation" :content="message.photoObservation" :smooth="message.imageStreaming" :streaming="message.status === 'RUNNING' && !message.text" :stopped="['FAILED','CANCELLED','INTERRUPTED'].includes(message.status)" @progress="followScroll()" />
                <div v-if="message.text" :id="`answer-${message.id}`" class="answer-content"><WorkbenchMarkdown :content="message.text" :smooth="message.directChat || message.result?.presentation === 'compact_stream' || message.imageStreaming" :streaming="message.status === 'RUNNING'" :stopped="['FAILED','CANCELLED','INTERRUPTED'].includes(message.status)" @progress="followScroll()" /></div>
                <ChatDetectionFrame v-if="imagePreviews.has(message) || (message.frameCount != null && message.imageRef)"
                  :initial="imagePreviews.get(message)" :image-ref="message.imageRef" :count="message.frameCount"
                  :session-id="active.id" :token="user.token" />
                <WorkbenchMarkdown v-if="message.photoComparison && !message.evidenceInvalidated" :content="message.photoComparison" />
                <AnswerEvidence v-if="message.result" :message="message" />
                <KnowledgeSources :message="message" />
                <details v-if="isDirectSource(message.modelSource) && message.result?.expertExplanations?.length" class="model-analysis">
                  <summary>查看模型对业务记录的分析</summary>
                  <section v-for="entry in message.result.expertExplanations" :key="entry.resultRef">
                    <strong>{{ AGENTS[entry.agentId]?.name || entry.agentId }}</strong>
                    <WorkbenchMarkdown :content="entry.text" />
                  </section>
                </details>
                <ExperienceEvidence :message="message" />
                <details v-for="record in inspectionImageRecords(message)" :key="record.recordId" class="detected-image">
                  <summary>检验图片依据 · {{ record.inspectionNo || record.inspectionId }} · {{ record.recordedAt }}</summary>
                  <VisionImageEvidence :record="record" />
                </details>
                <div v-if="message.error" class="message-error"><span>{{ message.error }}</span><button v-if="message.runId && message.status !== 'CANCELLED'" @click="refreshRun(message)">查询运行结果</button></div>
                <div v-if="message.text" class="message-actions"><button @click="copy([message.photoObservation, message.text, message.evidenceInvalidated ? null : message.photoComparison].filter(Boolean).join('\n\n'))"><CopyDocument />复制</button><button v-if="canRegenerate(message)" aria-label="重新生成回答" @click="send(message)"><RotateCcw />重新生成</button><button v-if="canExportResult(message)" aria-label="导出完整查询 CSV" @click="exportResult(message)"><Download />CSV</button></div>
              </template>
            </article>
          </div>
        </div>
        <footer ref="composerArea" class="composer-area">
          <WorkbenchModeSelector :model="workflowModel" :busy="busy" @change="setWorkflowModel" />
          <div v-if="statusHint" class="connection-notice" role="status"><span>{{ statusHint }}</span><button v-if="!busy" @click="checkConnection">重新连接</button></div>
          <input ref="attachmentInput" class="attachment-input" type="file" accept=".txt,.md,.csv,.json,.jpg,.jpeg,.png" aria-label="选择图片或文本资料" @change="attachTextFile" />
          <div v-if="pendingImage" class="image-attachment">
            <img v-if="pendingThumbnail" :src="pendingThumbnail" alt="待发送的图片" />
            <select v-model="imageMode" aria-label="图片分析方式"><option value="auto">图片问答 · 自动识别</option><option value="screen">屏幕划痕检测</option></select>
            <button type="button" aria-label="移除图片" @click="clearImage"><Close :size="16" /></button>
          </div>
          <WorkbenchComposer ref="composer" v-model="input" :welcome="!messages.length" :busy="busy" :can-send="canSend" @send="send" @stop="stop" @attach="attachmentInput?.click()" @paste-files="pasteFiles" />
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

<style scoped>
.detected-image { margin:8px 0; font-size:13px }
.detected-image img { display:block; max-width:100%; max-height:440px; margin-top:8px; object-fit:contain }
.image-attachment { display:flex; align-items:center; gap:8px; flex-wrap:wrap; font-size:13px; margin-bottom:6px }
.image-attachment img { width: 48px; height: 38px; object-fit: contain; border-radius: 4px; }
.image-attachment select,.image-attachment button { font:inherit; color:inherit; background:transparent; border:1px solid var(--aw-border); border-radius:6px; padding:4px }
.model-caption { display: block; margin: 0 0 6px; font-size: 12px; color: var(--aw-muted); }
.model-analysis { margin-block: 10px; } .model-analysis summary { cursor: pointer; }
</style>
