import { Empty, message } from 'antd'
import { useCallback, useEffect, useState } from 'react'
import { api, errorMessage } from '../api/client'
import type { PermissionRow, TeamRow } from '../api/types'
import { OrgExplorer } from '../components/OrgExplorer'

/**
 * 我的团队：团队负责人的**只读**视图。
 *
 * <p><b>范围是服务端给的</b>——`/api/users/me/teams` 只返回该负责人带队节点的整棵子树，
 * 兄弟团队、上级团队、其它中心连响应体都不带。所以这里没有任何「过滤/隐藏」逻辑：
 * 前端负责不显示，后端负责给不了，两者不是同一个量级的保证。</p>
 *
 * <p><b>不给任何写操作</b>：加人减人、改角色、调上下级都在权限中心（`role:manage`），
 * 延续「团队只做展示层」的既有约定。能点的东西越少，越不会出现「负责人悄悄给自己
 * 加了个权限」的口子——那正是把授权和查看混在一起时最容易漏的缝。</p>
 */
export default function MyTeamPage() {
    const [teams, setTeams] = useState<TeamRow[]>([])
    const [loading, setLoading] = useState(true)
    const [permRows, setPermRows] = useState<PermissionRow[]>([])

    const load = useCallback(async () => {
        setLoading(true)
        try {
            const res = await api.get<TeamRow[]>('/users/me/teams')
            setTeams(res.data ?? [])
        } catch (err) {
            message.error(errorMessage(err, '加载我的团队失败'))
        } finally {
            setLoading(false)
        }
    }, [])

    useEffect(() => {
        load()
        // 权限点目录只为把 code 翻译成中文名；取不到就退化成裸编码，
        // 不该因此把整页判失败——目录是锦上添花，团队数据才是这页的主菜
        api.get<PermissionRow[]>('/users/me/permission-catalog')
            .then((r) => setPermRows(r.data ?? []))
            .catch(() => {})
    }, [load])

    if (!loading && teams.length === 0) {
        return (
            <Empty
                style={{ marginTop: 80 }}
                description="你目前没有带队的团队，这里没有可展示的成员。"
            />
        )
    }

    return (
        <OrgExplorer
            teams={teams}
            loading={loading}
            permRows={permRows}
            rootLabel="我的团队"
            note={
                <>
                    只显示<b>你带队的团队及其全部下级</b>；同级团队、你的上级、其它中心
                    都不在此显示——不是隐藏了，是服务端根本没有返回。
                    本页<b>只读</b>，改角色与成员变动仍在「权限中心」。
                </>
            }
            rootHint="你带队范围内未加入任何团队的成员不在这棵树上——他们的权限由角色决定。"
        />
    )
}

/** 页头说明单独导出，方便 AppLayout 的副标题与正文用同一套措辞。 */
export const MY_TEAM_SUB = '自己带队的团队与成员（只读）'
