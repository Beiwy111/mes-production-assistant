// Presentation only: preserve source verdict and calculations.
const LIMITATIONS = {
  CURRENT_STATE_ONLY: '当前结果反映本次查询时的业务状态。',
  TARGET_KIND_DEFAULTS_TO_FACTORY_SHIPMENT:
    '本次按工厂发货口径判断，未确认客户收货时间。',
  PLAN_VERSION_AND_OUTPUT_OWNERSHIP_UNVERIFIED:
    '生产计划版本与产出归属尚未核验。',
  ORDER_RESERVATION_OWNERSHIP_UNAVAILABLE: '缺少订单专属库存预留记录。',
  RECONCILED_LOT_SCENARIO_NOT_ORDER_RESERVATION:
    '核验后的批次库存仍是分配情景，尚未为订单预留。',
  HISTORICAL_DEADLINE_AND_RECEIPT_UNVERIFIED:
    '历史交期变更与客户签收情况尚未核验。',
  WORK_ORDER_BOM_SNAPSHOT_UNAVAILABLE: '缺少工单下达时冻结的 BOM 版本。',
  RETURNS_UNVERIFIED: '退料与退货对数量的影响尚未核验。',
  MULTILEVEL_BOM_AND_MAKE_BUY_UNVERIFIED: '多层 BOM 及自制、外购边界尚未核验。',
  NONOVERLAPPING_PRODUCTION_COVERAGE_UNVERIFIED:
    '生产任务是否重复覆盖订单需求尚未核验。',
  QUALIFIED_SHARED_POOL_UNVERIFIED: '可共享的合格库存范围尚未完整核验。',
  MULTIPLE_DISPATCH_OR_REPORT_REWORK_DEDUPLICATION_UNVERIFIED:
    '重复派工、报工和返工数量尚需去重核验。',
  RESOURCE_CALENDAR_AND_QUALITY_NOT_VERIFIED:
    '设备、人员日历及质量放行条件尚未完整核验。',
  UNLINKED_WORK_ORDERS_NOT_ALLOCATED: '未关联订单的工单没有计入本次产出分配。',
  REDIS_TRANSIENT_WORKFLOW_STATE_NOT_A_DURABLE_LEDGER:
    '临时流程状态不能替代正式业务台账。',
  REVALIDATION_DOES_NOT_RESERVE_STOCK: '数据复核不会自动占用或预留库存。',
  REFRESH_RUN_REQUIRED: '查询期间数据已变更，需要重新查询。',
}
export function limitationLabel(code) {
  if (typeof code !== 'string') return '另有业务记录待核验。'
  if (Object.hasOwn(LIMITATIONS, code)) return LIMITATIONS[code]
  if (code.startsWith('REQUIRED_AGENT_'))
    return '该场景必需的业务专家尚未全部完成。'
  return '另有业务记录或关联关系待核验；本次结论仍受证据完整性限制。'
}
