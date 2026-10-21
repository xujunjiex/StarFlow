// OpenCode Zen 免费通道探针（2026-10-18 版形状）
//
// 用途：区分「插件请求形状过时」和「出口 IP 被风控标记」。
//   · 两个 lane 都 403 FreeTierError（Detect Abuse Traffic）→ 是出口 IP / 网络被标记，
//     改请求形状、换模型都没用，只能换出口。
//   · /inference 200 而 /zen 403 → 插件（0.3.x 走 /zen、带 x-opencode-request）过时，需要换插件。
//
// 用法：
//   node opencode-lane-probe.mjs                       # 探两个匿名 lane（deepseek-v4.1-flash-free）
//   node opencode-lane-probe.mjs big-pickle            # 换模型
//   node opencode-lane-probe.mjs --go <model>          # 用 ~/.local/share/opencode/auth.json 里的
//                                                      # opencode-go key 走带凭据的 Go lane
import { createHash, randomBytes } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'

const toolsJson = JSON.parse(readFileSync(new URL('./opencode_tools.json', import.meta.url), 'utf8'))
const args = process.argv.slice(2)
const useGo = args[0] === '--go'
const model = (useGo ? args[1] : args[0]) || 'deepseek-v4.1-flash-free'

const B62 = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz'
function base62Fixed(value, width) {
  let n = value
  const out = new Array(width)
  for (let i = width - 1; i >= 0; i--) { out[i] = B62.charAt(Number(n % 62n)); n /= 62n }
  return out.join('')
}
// 官方客户端 Identifier.descending() 的 48 位布局：~(ts<<12 | counter)，
// 低 12 bit 落在 0xFC0..0xFFF 才算合法 session。
function sessionId() {
  const sum = createHash('sha256').update('ses\u0000probe' + randomBytes(6).toString('hex')).digest()
  const inverted = ~((BigInt(Date.now()) << 12n) | 1n) & 0xffffffffffffn
  return `ses_${inverted.toString(16).padStart(12, '0')}${base62Fixed(BigInt('0x' + sum.subarray(6, 16).toString('hex')), 14)}`
}
const sid = sessionId()
const project = createHash('sha256').update('opencode-project\u0000probe').digest('hex').slice(0, 40)

function tools(names) {
  return names.flatMap((name) => {
    const def = toolsJson[name]
    if (!def) return []
    return [{ type: 'function', function: { name, description: def.description, parameters: def.parameters } }]
  })
}
const baseBody = { model, stream: true, stream_options: { include_usage: true }, messages: [{ role: 'user', content: '只回复：ok' }] }

const LANES = useGo
  ? [['go (keyed)', 'https://opencode.ai/inference/go/openai/v1/chat/completions']]
  : [
      ['inference (2.x 形状)', 'https://opencode.ai/inference/v1/chat/completions'],
      ['zen (1.x legacy 形状)', 'https://opencode.ai/zen/v1/chat/completions'],
    ]

let key = ''
if (useGo) {
  const auth = JSON.parse(readFileSync(join(homedir(), '.local', 'share', 'opencode', 'auth.json'), 'utf8'))
  key = auth['opencode-go']?.key ?? ''
  if (!key) { console.log('auth.json 里没有 opencode-go key'); process.exit(1) }
}

for (const [label, url] of LANES) {
  const legacy = url.includes('/zen/')
  const headers = {
    'content-type': 'application/json',
    accept: 'application/json, text/event-stream',
    'x-opencode-client': 'cli',
    'x-opencode-session': sid,
    'x-session-affinity': sid,
    'X-Session-Id': sid,
    'user-agent': legacy ? 'opencode/1.18.31 (windows amd64; go1.23.2)' : 'my-coding-agent/1.0',
    authorization: 'Bearer ' + (useGo ? key : 'public'),
  }
  if (legacy) {
    headers['x-opencode-request'] = 'req_' + randomBytes(16).toString('hex')
    headers['x-opencode-project'] = 'prj_' + createHash('sha256').update('probe').digest('hex').slice(0, 24)
  } else {
    headers['x-opencode-session-id'] = sid
    headers['x-opencode-project'] = project
  }
  const body = legacy ? { ...baseBody, tools: tools(['read']) } : { ...baseBody, tools: tools(['shell', 'edit', 'glob', 'grep', 'read']) }
  const started = Date.now()
  try {
    const res = await fetch(url, { method: 'POST', headers, body: JSON.stringify(body) })
    const text = await res.text()
    console.log(`\n[${label}] ${url}\n  status ${res.status} (${Date.now() - started}ms)\n  ${text.slice(0, 500)}`)
  } catch (err) {
    console.log(`\n[${label}] ${url}\n  transport error: ${err.message}`)
  }
}
