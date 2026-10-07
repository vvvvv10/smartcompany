/**
 * 枚举 → 中文文案。
 *
 * 与 `workbench-android` 的 `ui/Labels.kt`、web 端 `pages/crm/shared.tsx` 同一套
 * 枚举码。后端只给英文码，文案一律在前端收口，别散落到各个页面里。
 */

export const CUSTOMER_STATUS: Record<string, string> = {
  POTENTIAL: '潜在',
  FOLLOWING: '跟进中',
  DEAL: '成交',
  LOST: '流失',
}

export const CUSTOMER_LEVEL: Record<string, string> = {
  KEY: '重点',
  NORMAL: '普通',
  LOW: '低优先',
}

export const STAGE: Record<string, string> = {
  LEAD: '线索',
  PROPOSAL: '方案',
  NEGOTIATION: '谈判',
  WON: '赢单',
  LOST: '输单',
}

export const REQUEST_STATUS: Record<string, string> = {
  PENDING: '待审批',
  APPROVED: '已批准',
  REJECTED: '已驳回',
}

export const FOLLOW_TYPE: Record<string, string> = {
  CALL: '电话',
  VISIT: '拜访',
  WECHAT: '微信',
  MAIL: '邮件',
  OTHER: '其他',
}

export const TODO_DUE: Record<string, string> = {
  OVERDUE: '已过期',
  TODAY: '今天',
  UPCOMING: '未来 7 天',
}

/** TMS 运单状态 */
export const TMS_ORDER_STATUS: Record<string, string> = {
  PENDING: '待处理',
  IN_TRANSIT: '运输中',
  DELIVERED: '已送达',
  CANCELLED: '已取消',
}

/** TMS 车辆状态 */
export const TMS_VEHICLE_STATUS: Record<string, string> = {
  IDLE: '空闲',
  BUSY: '运输中',
  MAINTENANCE: '维修中',
}

/** TMS 司机状态 */
export const TMS_DRIVER_STATUS: Record<string, string> = {
  ACTIVE: '在职',
  INACTIVE: '停用',
}

/** WMS 出入库类型 */
export const WMS_RECORD_TYPE: Record<string, string> = {
  IN: '入库',
  OUT: '出库',
}

/** OMS 订单状态 */
export const OMS_ORDER_STATUS: Record<string, string> = {
  UNPAID: '待付款',
  TO_SHIP: '待发货',
  SHIPPED: '已发货',
  DONE: '已完成',
  AFTER_SALE: '售后',
  CANCELLED: '已取消',
}

/** OMS 销售渠道 */
export const OMS_CHANNEL: Record<string, string> = {
  DOUYIN: '抖音',
  TAOBAO: '淘宝',
  P1688: '1688批发',
  OFFLINE: '线下',
  WECHAT: '微信',
}

/** OMS 商品品类 */
export const OMS_CATEGORY: Record<string, string> = {
  TOP: '上衣',
  PANTS: '裤装',
  DRESS: '裙装',
  OUTER: '外套',
  KNIT: '针织',
}

/** OMS 商品上下架 */
export const OMS_PRODUCT_STATUS: Record<string, string> = {
  ON: '上架',
  OFF: '下架',
}

export const ROLE: Record<string, string> = {
  ADMIN: '管理员',
  OPS: '运营',
  LEAD: '团队负责人',
  USER: '普通用户',
}
