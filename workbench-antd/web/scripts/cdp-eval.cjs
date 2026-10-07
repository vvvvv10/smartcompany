/**
 * 通过 CDP 在模拟器里的 WebView 中执行一段 JS，把结果打到 stdout。
 *
 * WebView 壳的调试手段：`adb forward` 把 devtools socket 接到本机，再用 CDP 的
 * Runtime.evaluate 直接读 DOM / 跑表达式。没有它的话，"组件没渲染"这类问题只能
 * 靠截图猜——本页出现的 CapsuleTabs 空白就是这么查出来的。
 *
 * 用法：node scripts/cdp-eval.cjs "document.querySelectorAll('.adm-capsule-tabs').length"
 * 前置：adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
 */
const WebSocket = require('ws')

const PORT = process.env.CDP_PORT || 9222

async function main() {
  const expr = process.argv[2]
  if (!expr) {
    console.error('用法: node scripts/cdp-eval.cjs "<js 表达式>"')
    process.exit(1)
  }
  const list = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json()
  const page = list.find((t) => t.type === 'page')
  if (!page) throw new Error('没有可调试的页面，先跑 adb forward')

  const ws = new WebSocket(page.webSocketDebuggerUrl, { perMessageDeflate: false })
  const result = await new Promise((resolve, reject) => {
    ws.on('open', () =>
      ws.send(
        JSON.stringify({
          id: 1,
          method: 'Runtime.evaluate',
          params: { expression: expr, returnByValue: true, awaitPromise: true },
        }),
      ),
    )
    ws.on('message', (raw) => {
      const msg = JSON.parse(raw.toString())
      if (msg.id !== 1) return
      if (msg.result?.exceptionDetails) {
        reject(new Error(JSON.stringify(msg.result.exceptionDetails)))
      } else {
        resolve(msg.result?.result?.value)
      }
      ws.close()
    })
    ws.on('error', reject)
  })
  console.log(typeof result === 'string' ? result : JSON.stringify(result, null, 2))
}

main().catch((e) => {
  console.error('CDP 调用失败:', e.message)
  process.exit(1)
})