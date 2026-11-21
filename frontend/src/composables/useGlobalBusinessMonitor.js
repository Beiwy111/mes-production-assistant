import { onMounted, onUnmounted } from 'vue'
import { fetchAlarmViews } from '@/api/business'
import { fetchVoiceNotices } from '@/api/assistant'
import { fetchOperatorNotifications } from '@/api/mes'
import { useNotificationStore, NOTIF_TYPE } from '@/stores/notification'
import { useUserStore } from '@/stores/user'
import { fetchSystemNotifications } from '@/api/workbench'

const POLL_INTERVAL = 15000

/** 协办通知目标模块 → 可见角色（admin 全可见） */
const NOTICE_AUDIENCE = {
  aftersale:  ['aftersale'],
  device:     ['device'],
  production: ['manager', 'operator', 'planner'],
  purchase:   ['purchase'],
  warehouse:  ['warehouse'],
  quality:    ['quality'],
  order:      ['order'],
  cost:       ['cost'],
  system:     ['admin'],
}

export function useGlobalBusinessMonitor() {
  const notifications = useNotificationStore()
  const user = useUserStore()
  let timer = null

  function sameUser(name) {
    const normalize = value => String(value || '').replace(/\s+/g, '').toLowerCase()
    const target = normalize(name)
    return !!target && [user.userInfo?.username, user.userInfo?.realName, user.displayName]
      .map(normalize)
      .some(value => value && value === target)
  }

  function buildAlarmInbox(alarms) {
    const role = user.roleKey
    return (alarms || []).flatMap(alarm => {
      const status = alarm.alarmStatus
      const isReporter = sameUser(alarm.reporterName)
      const base = {
        type: NOTIF_TYPE.ALARM,
        content: alarm.alarmDescription || '',
        createdAt: Date.parse(alarm.reportedAt) || Date.now()
      }

      if (status === 'OPEN' && ['device', 'manager'].includes(role) && !isReporter) {
        return [{
          ...base,
          sourceKey: `alarm:${alarm.alarmId}:OPEN:${user.userInfo?.username}`,
          title: `待接收安灯 ${alarm.alarmNo}`,
          from: `${alarm.reporterName || '操作员'} 安灯上报`,
          link: '/device/alarm'
        }]
      }

      if (isReporter && ['RECEIVED', 'PROCESSING', 'CLOSED'].includes(status)) {
        return [{
          ...base,
          sourceKey: `alarm:${alarm.alarmId}:${status}:${user.userInfo?.username}`,
          title: `安灯处理进度 ${alarm.alarmNo}`,
          content: status === 'CLOSED' ? (alarm.closeResult || '报警已解除') : `${alarm.receiverName || '设备人员'}已${status === 'RECEIVED' ? '接收' : '开始处理'}`,
          from: `${alarm.receiverName || '设备人员'} 处理回执`,
          link: '/production/andon-report'
        }]
      }

      return []
    })
  }

  /** 语音助手跨模块协办通知：只投递给目标模块的角色（仅通知，不改业务数据） */
  function buildNoticeInbox(notices) {
    const role = user.roleKey
    return (notices || [])
      .filter(n => role === 'admin' || (NOTICE_AUDIENCE[n.targetModule] || []).includes(role))
      .map(n => ({
        type: NOTIF_TYPE.SYSTEM,
        sourceKey: `voice-notice:${n.id}:${user.userInfo?.username}`,
        title: n.title || '协办通知',
        content: n.content || '',
        from: n.from || '语音助手',
        createdAt: Date.parse(n.createdAt) || Date.now()
      }))
  }

  /** 派工 / 质检 / 成品入库通知：按真实登录账号投递（勿用 admin→操作员映射） */
  function buildDispatchInbox(notices) {
    const username = String(user.userInfo?.username || '').trim()
    if (!username) return []
    return (notices || []).map(n => {
      const kind = n.kind || 'dispatch'
      const isInbound = kind === 'inbound'
      const isQc = kind === 'qc'
      return {
        type: isInbound || isQc ? NOTIF_TYPE.QUALITY : NOTIF_TYPE.PROCESS,
        sourceKey: `${kind}-notice:${n.id}:${username}`,
        title: n.title || (isInbound ? '新成品待入库' : isQc ? '新成品质检任务' : '新派工任务'),
        content: n.content || '',
        from: n.from || (isInbound ? '质检员' : isQc ? '操作员' : '生产主管'),
        link: n.link || (isInbound
          ? '/warehouse/inbound-hub?tab=finished'
          : isQc ? '/quality/fp/inspection' : '/production/my-dispatch'),
        createdAt: Date.parse(n.createdAt) || Date.now()
      }
    })
  }

  /** AI 全局分析、质检→仓储等持久化业务消息，后端已按当前登录角色过滤。 */
  function buildSystemInbox(notices) {
    const username = user.userInfo?.username || 'anonymous'
    return (notices || []).map(n => {
      const isInbound = n.businessType === 'QC_INBOUND'
      return {
        type: isInbound ? NOTIF_TYPE.QUALITY : NOTIF_TYPE.SYSTEM,
        sourceKey: `system-notification:${n.notificationId}:${username}`,
        title: n.title || '系统业务提醒',
        content: n.content || '',
        from: n.businessType === 'AI_GLOBAL_ACTION'
          ? '管理员 · AI全局分析'
          : isInbound ? '质量管理 · 成品质检' : 'MES 消息系统',
        link: n.targetPath || null,
        createdAt: Date.parse(n.createdAt) || Date.now()
      }
    })
  }

  async function poll() {
    try {
      // 仓储/质检等角色的业务通知必须用登录账号；admin 演示操作员映射仅用于操作员专属接口
      const loginUsername = String(user.userInfo?.username || '').trim()
      const [alarmRes, noticeRes, dispatchRes, systemRes] = await Promise.all([
        fetchAlarmViews(),
        fetchVoiceNotices().catch(() => []),
        loginUsername ? fetchOperatorNotifications(loginUsername).catch(() => []) : Promise.resolve([]),
        fetchSystemNotifications().catch(() => [])
      ])
      const alarms = Array.isArray(alarmRes) ? alarmRes : alarmRes?.data || []
      const notices = Array.isArray(noticeRes) ? noticeRes : noticeRes?.data || []
      const dispatchNotices = Array.isArray(dispatchRes) ? dispatchRes : dispatchRes?.data || []
      const systemNotices = Array.isArray(systemRes) ? systemRes : systemRes?.data || []
      notifications.sync([
        ...buildAlarmInbox(alarms),
        ...buildNoticeInbox(notices),
        ...buildDispatchInbox(dispatchNotices),
        ...buildSystemInbox(systemNotices)
      ])
    } catch {
      // 保留上一次成功同步的收件箱。
    }
  }

  onMounted(() => {
    notifications.configureAudience(user.userInfo)
    poll()
    timer = window.setInterval(poll, POLL_INTERVAL)
  })
  onUnmounted(() => window.clearInterval(timer))
}
