/** 枚举中文标签与标签颜色（与后端 /api/meta/enums 保持一致） */

export const LABELS = {
  InfectionMarker: { NONE: '无', HBV: '乙肝', HCV: '丙肝', TP: '梅毒', OTHER: '其他传染' },
  RiskLevel: { LOW: '低危', MEDIUM: '中危', HIGH: '高危' },
  AccessType: { AVF: '自体动静脉内瘘', AVG: '移植物内瘘', CATHETER: '带 cuff 导管' },
  TransportMode: { SELF: '自行来院', FAMILY: '家属接送', SHUTTLE: '班车接送' },
  Shift: { AM: '上午班', PM: '下午班', EVE: '晚班' },
  MachineZone: { NORMAL: '普通区', HBV: '乙肝区', HCV: '丙肝区', ISOLATION: '隔离区' },
  MachineStatus: { IDLE: '空闲', IN_USE: '使用中', DISINFECTING: '消毒中', FAULT: '故障', MAINTENANCE: '维护中' },
  SessionStatus: {
    SCHEDULED: '已排班', ASSESSED: '已评估', ON_DIALYSIS: '透析中', OFF_DIALYSIS: '已下机',
    FINISHED: '已完成', ABSENT: '请假/缺席', CANCELLED: '已取消'
  },
  AccessCondition: { NORMAL: '正常', REDNESS: '红肿', SWELLING: '肿胀', BRUIT_WEAK: '震颤减弱', INFECTION: '感染迹象' },
  DepartureMode: { SELF: '自行离开', FAMILY: '家属接回', SHUTTLE: '班车送回', WHEELCHAIR: '轮椅', STRETCHER: '平车' },
  DisinfectionType: { DAILY: '日常消毒', HEAT: '热消毒', CITRIC_ACID: '柠檬酸消毒', FILTER_CHANGE: '滤芯更换', WATER_TREATMENT: '水处理维护', WATER_TEST: '水质检测' },
  DisinfectionResult: { PASS: '合格', FAIL: '不合格' },
  TxnType: { PRE_DRAW: '预领', USE: '使用', RETURN: '归还', WASTE: '损耗', RESTOCK: '入库' },
  ExceptionType: {
    LATE: '患者迟到', LEAVE: '临时请假', INFECTION_CHANGE: '感染标记变化', MACHINE_FAULT: '机器故障',
    CONSUMABLE_SHORTAGE: '耗材不足', HYPOTENSION: '透析中低血压', ORDER_CHANGE: '医嘱调整', OTHER: '其他'
  },
  ExceptionStatus: { OPEN: '待处理', PROCESSING: '处理中', RESOLVED: '已解决' },
  BillingStatus: { PENDING: '待核对', CONFIRMED: '已核对' },
  RescheduleAction: { DELAY: '延后', SWITCH_MACHINE: '换机', SHIFT_CHANGE: '改班次', SWAP: '协商调换' },
  NotifyChannel: { PHONE: '电话通知', SMS: '短信通知', IN_PERSON: '现场告知', FAMILY_PHONE: '通知家属' }
}

export const TAG_TYPES = {
  MachineStatus: { IDLE: 'success', IN_USE: 'primary', DISINFECTING: 'warning', FAULT: 'danger', MAINTENANCE: 'info' },
  SessionStatus: {
    SCHEDULED: 'info', ASSESSED: 'warning', ON_DIALYSIS: 'primary', OFF_DIALYSIS: 'success',
    FINISHED: 'success', ABSENT: 'danger', CANCELLED: 'info'
  },
  ExceptionStatus: { OPEN: 'danger', PROCESSING: 'warning', RESOLVED: 'success' },
  BillingStatus: { PENDING: 'warning', CONFIRMED: 'success' },
  RiskLevel: { LOW: 'success', MEDIUM: 'warning', HIGH: 'danger' },
  InfectionMarker: { NONE: 'success', HBV: 'warning', HCV: 'warning', TP: 'danger', OTHER: 'danger' },
  DisinfectionResult: { PASS: 'success', FAIL: 'danger' },
  RescheduleAction: { DELAY: 'warning', SWITCH_MACHINE: 'primary', SHIFT_CHANGE: 'success', SWAP: 'danger' }
}

export function label(group, value) {
  return LABELS[group]?.[value] ?? value ?? '-'
}

export function tagType(group, value) {
  return TAG_TYPES[group]?.[value] ?? 'info'
}

/** 下拉选项辅助 */
export function options(group) {
  return Object.entries(LABELS[group] || {}).map(([value, text]) => ({ value, label: text }))
}

export function fmtTime(t) {
  return t ? String(t).replace('T', ' ').slice(0, 16) : '-'
}

export function fmtDate(t) {
  return t ? String(t).slice(0, 10) : '-'
}
