import { describe, expect, it } from 'vitest'
import { itemKey, stageText, typeText } from '../lib/approvals'
import { keepByOwner } from '../lib/crm'
import { applyableCodes, canSee, MODULE_DEFS, SYSTEM_MANAGE } from '../lib/modules'
import { bytes, dateOnly, dateTime, money, moneyShort } from '../lib/format'

describe('统一审批的渲染标签', () => {
  it('阶段文案跟 status/stage 对齐', () => {
    expect(stageText({ status: 'PENDING', stage: 1 })).toBe('第 1 级（团队负责人）待批')
    expect(stageText({ status: 'PENDING', stage: 2 })).toBe('第 2 级（系统管理员）待批')
    expect(stageText({ status: 'APPROVED', stage: 1 })).toBe('已批准')
    expect(stageText({ status: 'REJECTED', stage: 2 })).toBe('已驳回')
  })

  it('typeText 区分花名和权限', () => {
    expect(typeText({ type: 'NICKNAME' })).toBe('花名变更')
    expect(typeText({ type: 'PERMISSION' })).toBe('权限申请')
  })

  it('itemKey 用 type 前缀避免两张表自增撞号', () => {
    expect(itemKey({ type: 'NICKNAME', id: 32 })).toBe('NICKNAME:32')
    expect(itemKey({ type: 'PERMISSION', id: 32 })).toBe('PERMISSION:32')
  })
})

describe('CRM「只看我的」过滤', () => {
  it('留下 owner 是我的，也留下 owner 为空的（未分配不该被藏起来）', () => {
    const rows = [
      { id: 1, ownerName: '蓝河' },
      { id: 2, ownerName: '白露' },
      { id: 3, ownerName: null },
      { id: 4, ownerName: '' },
    ]
    expect(keepByOwner(rows, '蓝河').map((r) => r.id)).toEqual([1, 3, 4])
  })

  it('没拿到花名时不过滤（宁可多显示，不要让客户“消失”）', () => {
    const rows = [{ id: 1, ownerName: '白露' }]
    expect(keepByOwner(rows, '')).toHaveLength(1)
    expect(keepByOwner(rows, undefined)).toHaveLength(1)
  })
})

describe('展示格式化', () => {
  it('字节数走容量单位，绝不当金额渲染', () => {
    expect(bytes(21396681000000)).toBe('19.5 TB')
    expect(bytes(5368709120)).toBe('5.0 GB')
    expect(bytes(512)).toBe('512 B')
    expect(bytes(null)).toBe('—')
  })

  it('金额千分位，大额压缩成万', () => {
    expect(money(60000)).toBe('¥60,000')
    expect(moneyShort(1250000)).toBe('¥125.0万')
    expect(moneyShort(6000)).toBe('¥6,000')
  })

  it('金额空值给破折号，不给 ¥0', () => {
    expect(money(null)).toBe('—')
    expect(money(undefined)).toBe('—')
    expect(moneyShort(null)).toBe('—')
  })

  it('两个服务的时间格式都收敛成 MM-DD HH:mm', () => {
    expect(dateTime('2026-09-29T20:53:32')).toBe('09-29 20:53')
    expect(dateTime('2026-10-01 00:24:42')).toBe('10-01 00:24')
    expect(dateTime(null)).toBe('—')
  })

  it('日期只取月日', () => {
    expect(dateOnly('2026-10-31')).toBe('10-31')
    expect(dateOnly(null)).toBe('—')
  })
})
describe('模块可见性（开关 ∧ 权限）', () => {
  const tms = MODULE_DEFS.tms
  const perms = ['nickname:review', 'permission:review']

  it('没有任何权限点时，业务模块一律不可见', () => {
    expect(canSee(tms, perms, false)).toBe(false)
    expect(canSee(MODULE_DEFS.crm, perms, false)).toBe(false)
  })

  it('有主码或国际版查看码任一命中即可见', () => {
    expect(canSee(tms, [...perms, 'tms:view'], false)).toBe(true)
    expect(canSee(tms, [...perms, 'tms:intl:view'], false)).toBe(true)
  })

  it('业务模块不给管理员特例：管理员少授一个码也该看不见', () => {
    expect(canSee(tms, perms, true)).toBe(false)
  })

  it('系统管理对管理员无条件可见（网关段按角色放行，权限点里没有 gateway:*）', () => {
    expect(canSee(SYSTEM_MANAGE, perms, true)).toBe(true)
    // 普通账号要看数据看板 / 用户列表 / CRM 之一
    expect(canSee(SYSTEM_MANAGE, perms, false)).toBe(false)
    expect(canSee(SYSTEM_MANAGE, [...perms, 'dashboard:view'], false)).toBe(true)
    expect(canSee(SYSTEM_MANAGE, [...perms, 'crm:read'], false)).toBe(true)
  })

  it('可申请权限点取自目录；目录为空时兜底只给主码', () => {
    const catalog = [
      { code: 'tms:view', name: '查看', module: 'tms' },
      { code: 'tms:intl:edit', name: '编辑', module: 'tms' },
      { code: 'crm:read', name: '查看', module: 'crm' },
    ]
    expect(applyableCodes(catalog, tms).map((c) => c.code)).toEqual(['tms:view', 'tms:intl:edit'])
    // 目录没这个模块时别让申请入口消失
    expect(applyableCodes([], tms).map((c) => c.code)).toEqual(['tms:view', 'tms:intl:view'])
  })
})
