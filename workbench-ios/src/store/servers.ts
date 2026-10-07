/**
 * 服务器配置的持久化——从 workbench-android 的 ServerConfig.kt 移植。
 *
 * **必须是独立于 session 的存储**：session 的 clear 会清掉整个键空间，
 * 合并存储会让「退出登录」顺手把服务器列表也抹掉，那恰恰是用户最不想丢的东西
 * （丢了就得重新手敲地址）。AsyncStorage 是扁平键值，所以用不同的键前缀隔离。
 *
 * 两份数据：已配置的地址列表、当前选中的那个。列表保持**添加顺序**，
 * 当前项不排到最前——用户要的是"我加过的那几个"，不是"最近用的"。
 */

import AsyncStorage from '@react-native-async-storage/async-storage'

const SERVERS_KEY = 'wb_servers'
const SELECTED_KEY = 'wb_selected'

/** 构建期默认值：既是兜底，也是首次打开、尚未配置任何地址时的预置项。 */
export const DEFAULT_SERVER = 'http://workbench.example.com'

let servers: string[] = [DEFAULT_SERVER]
let selected: string = DEFAULT_SERVER
let loadPromise: Promise<void> | null = null

/**
 * 冷启动读盘（幂等）：返回同一份 Promise。
 * 调用方可以 await 它来确保存储已水合，再把值同步进组件状态。
 */
export function loadNow(): Promise<void> {
    if (!loadPromise) loadPromise = load()
    return loadPromise
}

async function load() {
    try {
        const [listRaw, sel] = await Promise.all([
            AsyncStorage.getItem(SERVERS_KEY),
            AsyncStorage.getItem(SELECTED_KEY)
        ])
        const list = (listRaw ?? '')
            .split('\n')
            .map((s) => normalize(s))
            .filter((s): s is string => s !== null)
            .filter((s, i, arr) => arr.indexOf(s) === i)
        servers = list.length > 0 ? list : [DEFAULT_SERVER]
        const normalized = sel ? normalize(sel) : null
        selected = normalized && servers.includes(normalized) ? normalized : servers[0]
    } catch {
        servers = [DEFAULT_SERVER]
        selected = DEFAULT_SERVER
    }
}

export function getServers(): string[] {
    return servers
}

export function getSelectedServer(): string {
    return selected
}

/** 切到一个已保存的地址。 */
export async function selectServer(url: string) {
    const normalized = normalize(url)
    if (!normalized || !servers.includes(normalized)) return
    selected = normalized
    await AsyncStorage.setItem(SELECTED_KEY, normalized)
}

/** 新增并选中。地址已存在时只切换过去，不产生重复项。 */
export async function addServer(raw: string): Promise<string | null> {
    const normalized = normalize(raw)
    if (!normalized) return null
    servers = [...servers, normalized].filter((s, i, arr) => arr.indexOf(s) === i)
    selected = normalized
    await AsyncStorage.multiSet([
        [SERVERS_KEY, servers.join('\n')],
        [SELECTED_KEY, normalized]
    ])
    return normalized
}

/**
 * 归一化用户输入，不合法返回 null。
 *
 * - `1.2.3.4:8080` → `http://1.2.3.4:8080`（没写协议默认补 http）
 * - 去首尾空白、去尾斜杠，`host:8080/` 与 `host:8080` 存成同一个
 * - **丢掉路径、查询串、锚点**：服务地址只到 origin。
 */
export function normalize(raw: string): string | null {
    let s = raw.trim()
    if (!s) return null
    if (!s.includes('://')) s = 'http://' + s
    try {
        const url = new URL(s)
        if (!url.hostname) return null
        // 先让 URL 解析，最后才清尾斜杠
        return url.origin
    } catch {
        return null
    }
}
