/** 一次用户操作一个幂等键；同一操作重试时必须复用该键。 */
export function newIdempotencyKey(prefix = 'cmd'): string {
  return (
    globalThis.crypto?.randomUUID?.() ??
    `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2)}`
  )
}
