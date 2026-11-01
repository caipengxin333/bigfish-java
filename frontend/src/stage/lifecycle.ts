/** Hidden tabs keep their lease. Freeze/pagehide stop local playback; showing a page only validates. */
export function bindStageLifecycle(page: EventTarget & { hidden: boolean }, windowTarget: EventTarget,
  callbacks: { resume: () => void; freeze: () => void; leave: () => void }) {
  const visible = () => { if (!page.hidden) callbacks.resume() }
  page.addEventListener('visibilitychange', visible)
  page.addEventListener('freeze', callbacks.freeze)
  windowTarget.addEventListener('pageshow', visible)
  windowTarget.addEventListener('pagehide', callbacks.leave)
  return () => {
    page.removeEventListener('visibilitychange', visible); page.removeEventListener('freeze', callbacks.freeze)
    windowTarget.removeEventListener('pageshow', visible); windowTarget.removeEventListener('pagehide', callbacks.leave)
  }
}
