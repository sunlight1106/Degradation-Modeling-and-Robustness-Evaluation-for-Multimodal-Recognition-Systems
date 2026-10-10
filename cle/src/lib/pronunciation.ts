type Callbacks = { start?: () => void; stop?: () => void; error?: (message: string) => void }
let active: { owner: symbol; utterance: SpeechSynthesisUtterance; stop?: () => void } | undefined
function cancel() {
  if (!active) return
  const previous = active; active = undefined
  previous.utterance.onstart = previous.utterance.onend = previous.utterance.onerror = null
  previous.stop?.(); window.speechSynthesis.cancel()
}
export function stopWordSpeech(){ cancel() }
export function wordSpeaker() {
  const owner = Symbol('word-speaker')
  return {
    say(text: string, accent = 'en-US', slow = false, callbacks: Callbacks = {}) {
      if (!text.trim()) return
      if (!('speechSynthesis' in window) || !('SpeechSynthesisUtterance' in window)) { callbacks.error?.('浏览器不支持听读，请使用支持语音朗读的桌面浏览器。'); return }
      cancel()
      const utterance = new SpeechSynthesisUtterance(text)
      utterance.lang = accent; utterance.rate = slow ? .65 : .9
      const voice = window.speechSynthesis.getVoices().find(v => v.lang.toLowerCase() === accent.toLowerCase())
      if (voice) utterance.voice = voice
      active = { owner, utterance, stop: callbacks.stop }
      utterance.onstart = () => { if (active?.utterance === utterance) callbacks.start?.() }
      const finish = () => { if (active?.utterance === utterance) { active = undefined; callbacks.stop?.() } }
      utterance.onend = finish
      utterance.onerror = event => { finish(); if (event.error !== 'canceled' && event.error !== 'interrupted') callbacks.error?.('未能播放语音，请检查系统英文语音；也可以点击听读重试。') }
      try { window.speechSynthesis.speak(utterance) } catch { finish(); callbacks.error?.('自动听读未能开始，请点击听读重试。') }
    },
    stop() { if (active?.owner === owner) cancel() },
  }
}
