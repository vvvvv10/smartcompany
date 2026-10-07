/**
 * 底部「审批」Tab 的未处理数徽标——从 workbench-android 的 PendingBadge 移植。
 *
 * 单独成一个模块而不是挂在某个组件上：**写入方是概览页**（它拉工作台数据时
 * 顺手拿到 pending-count），**读取方是主框架**，两者没有持有关系。
 * 后端有 pending-count 专用接口，为一个数字拉整页数据太浪费。
 */

import { useSyncExternalStore } from 'react'

let count = 0
const listeners = new Set<() => void>()

function emit() {
    listeners.forEach((l) => l())
}

export const PendingBadge = {
    update(value: number) {
        count = value
        emit()
    },
    /** 退出登录时清掉，别把上一个账号的待办数留给下一个人看。 */
    clear() {
        count = 0
        emit()
    },
    getCount: () => count,
    subscribe(cb: () => void) {
        listeners.add(cb)
        return () => {
            listeners.delete(cb)
        }
    }
}

export function usePendingCount(): number {
    return useSyncExternalStore(PendingBadge.subscribe, PendingBadge.getCount)
}
