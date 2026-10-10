export class RequestTimeoutError extends Error {
  constructor() { super('Request deadline exceeded'); this.name = 'RequestTimeoutError' }
}

/** Bound both transport and body reads, while preserving explicit caller cancellation. */
export async function withRequestDeadline<T>(parent: AbortSignal | null | undefined, milliseconds: number, work: (signal: AbortSignal) => Promise<T>): Promise<T> {
  if (parent?.aborted) throw parent.reason ?? new DOMException('Request cancelled', 'AbortError')
  const controller = new AbortController()
  let timedOut = false, rejectPending: (reason: unknown) => void = () => {}
  const interrupted = new Promise<never>((_, reject) => { rejectPending = reject })
  const cancel = () => { controller.abort(parent?.reason); rejectPending(parent?.reason ?? new DOMException('Request cancelled', 'AbortError')) }
  parent?.addEventListener('abort', cancel, { once: true })
  const timer = setTimeout(() => {
    timedOut = true
    const reason = new RequestTimeoutError()
    controller.abort(reason); rejectPending(reason)
  }, milliseconds)
  try { return await Promise.race([work(controller.signal), interrupted]) }
  catch (reason) { if (timedOut) throw new RequestTimeoutError(); throw reason }
  finally { clearTimeout(timer); parent?.removeEventListener('abort', cancel) }
}
