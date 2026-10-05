/** Abort transport and reject late responses after selection, editor, or navigation changes. */
export function createRequestGuard() {
  let epoch = 0
  let controller: AbortController | undefined
  return {
    start() {
      controller?.abort()
      controller = new AbortController()
      const version = ++epoch
      return { signal: controller.signal, current: () => version === epoch && !controller?.signal.aborted }
    },
    cancel() { epoch++; controller?.abort(); controller = undefined },
  }
}

export function isPreviewExpired(expiresAt: string, now = Date.now()): boolean {
  const expiry = Date.parse(expiresAt)
  return !Number.isFinite(expiry) || expiry <= now
}
