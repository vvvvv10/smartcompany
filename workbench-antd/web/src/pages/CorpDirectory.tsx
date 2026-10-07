import { useMemo, useState } from 'react'
import { SearchBar } from 'antd-mobile'
import { useAsync } from '../components/useAsync'
import { EmptyBox, ErrorBox, RowSkeleton, SectionCard } from '../components/ui'
import { api } from '../lib/api'
import type { CorpDept, CorpProvider } from '../lib/types'

/**
 * 企业通讯录（只读看板）：钉钉 / 企业微信两套组织树，谁配了凭据谁给真数据。
 *
 * 数据来自 user-center 实时代理（GET /api/admin/corp/tree），登录即可看——
 * 与钉钉默认「全员可见通讯录」一致；没接入的平台给卡片提示去管理台「集成配置」页接入，
 * 不发多余请求。关键词同时搜部门名与成员名。
 */
export default function CorpDirectory() {
  const dir = useAsync(() => api.corpTree(), [])
  const [kw, setKw] = useState('')

  const providers = dir.data?.providers ?? []

  // 关键词在渲染层过滤（递归），不改原始数据
  const filterDept = (d: CorpDept, q: string): CorpDept | null => {
    const children = (d.children ?? []).map((c) => filterDept(c, q)).filter(Boolean) as CorpDept[]
    const members = (d.members ?? []).filter((m) => m.name.includes(q) || (m.title || '').includes(q))
    if (d.name.includes(q) || members.length > 0 || children.length > 0) {
      return { ...d, members: d.name.includes(q) && members.length === 0 ? d.members ?? [] : members, children }
    }
    return null
  }

  const filtered = useMemo(() => {
    const q = kw.trim()
    if (!q) return providers
    return providers.map((p) => ({
      ...p,
      depts: p.depts.map((d) => filterDept(d, q)).filter(Boolean) as CorpDept[],
    }))
    // filterDept 是页内闭包，依赖只看 kw / providers 就够
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [providers, kw])

  const countMembers = (nodes: CorpDept[]): number =>
    nodes.reduce((sum, d) => sum + (d.members?.length ?? 0) + countMembers(d.children ?? []), 0)

  if (dir.loading) {
    return (
      <div className="page">
        <div className="page-head">
          <div className="page-head-title">企业通讯录</div>
          <div className="page-head-sub">钉钉 · 企业微信 组织架构</div>
        </div>
        <SectionCard title="组织架构">
          <RowSkeleton count={6} />
        </SectionCard>
      </div>
    )
  }

  if (dir.error) {
    return (
      <div className="page">
        <div className="page-head">
          <div className="page-head-title">企业通讯录</div>
          <div className="page-head-sub">钉钉 · 企业微信 组织架构</div>
        </div>
        <ErrorBox error={dir.error} onRetry={() => void dir.reload()} />
      </div>
    )
  }

  return (
    <div className="page">
      <div className="page-head">
        <div className="page-head-title">企业通讯录</div>
        <div className="page-head-sub">钉钉 · 企业微信 组织架构</div>
      </div>

      <div style={{ margin: '0 12px 12px' }}>
        <SearchBar placeholder="搜部门 / 成员" value={kw} onChange={setKw} />
      </div>

      {filtered.length === 0 && <EmptyBox text="没有匹配的部门或成员" hint="换个关键词试试" />}

      {filtered.map((p) => (
        <ProviderCard key={p.code} provider={p} isFiltering={kw.trim().length > 0} countMembers={countMembers} />
      ))}
    </div>
  )
}

function ProviderCard({
  provider,
  isFiltering,
  countMembers,
}: {
  provider: CorpProvider
  isFiltering: boolean
  countMembers: (nodes: CorpDept[]) => number
}) {
  // 没接入：直接亮出去哪配，比「暂无数据」可操作
  if (!provider.configured) {
    return (
      <SectionCard title={provider.name}>
        <EmptyBox
          text={`${provider.name}未接入`}
          hint={
            provider.code === 'dingding'
              ? '管理员在管理台「集成配置」页填写 AppKey / AppSecret 后即可接入'
              : '管理员在管理台「集成配置」页填写 CorpID / CorpSecret 后即可接入'
          }
        />
      </SectionCard>
    )
  }

  if (isFiltering && provider.depts.length === 0) {
    return (
      <SectionCard title={provider.name}>
        <EmptyBox text="没有匹配的部门或成员" />
      </SectionCard>
    )
  }

  const total = countMembers(provider.depts)
  return (
    <SectionCard title={`${provider.name} · ${total} 人`}>
      {provider.depts.map((d) => (
        <DeptNodeView key={d.id} dept={d} depth={0} />
      ))}
    </SectionCard>
  )
}

/** 递归部门节点：层级用缩进表达，成员以行内小卡铺开（手机屏不值得做树控件） */
function DeptNodeView({ dept, depth }: { dept: CorpDept; depth: number }) {
  return (
    <div style={{ marginLeft: depth === 0 ? 0 : 14, marginTop: depth === 0 ? 4 : 12 }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
        <span style={{ fontWeight: depth === 0 ? 600 : 500 }}>{dept.name}</span>
        <span style={{ fontSize: 12, color: 'var(--adm-color-weak, #999)' }}>
          {(dept.members ?? []).length} 人
        </span>
      </div>
      {(dept.members ?? []).length > 0 && (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 6 }}>
          {(dept.members ?? []).map((m) => (
            <span
              key={m.userid}
              style={{
                padding: '3px 9px',
                borderRadius: 10,
                fontSize: 13,
                background: 'var(--adm-color-fill, #f5f5f5)',
              }}
            >
              {m.name}
              {m.title ? <span style={{ color: 'var(--adm-color-weak, #999)' }}> · {m.title}</span> : null}
            </span>
          ))}
        </div>
      )}
      {(dept.children ?? []).map((c) => (
        <DeptNodeView key={c.id} dept={c} depth={depth + 1} />
      ))}
    </div>
  )
}
