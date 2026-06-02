import { ref, nextTick } from 'vue'

/**
 * 流式打字机效果组合式函数
 *
 * - 使用缓冲区收集 token，按 requestAnimationFrame 批量渲染，避免 Vue 逐字重排
 * - 提供闪烁光标视觉
 * - 节流滚动，减少 DOM 操作
 */
export function useTypingEffect(
  onAppend,      // (text: string) => void  追加新文本
  onFlush,       // () => void  强制刷新缓冲区
  options = {}
) {
  const {
    flushInterval = 80,       // 缓冲区最大等待时间(ms)
    scrollThrottle = 150      // 滚动节流间隔(ms)
  } = options

  const isTyping = ref(false)
  const showCursor = ref(false)
  let buffer = ''
  let flushTimer = null
  let rafId = null
  let cursorTimer = null
  let lastScroll = 0

  function start() {
    isTyping.value = true
    showCursor.value = true
    buffer = ''
    lastScroll = 0

    // 光标闪烁
    cursorTimer = setInterval(() => {
      showCursor.value = !showCursor.value
    }, 530)
  }

  function push(token) {
    buffer += token
    scheduleFlush()
  }

  function scheduleFlush() {
    if (flushTimer) clearTimeout(flushTimer)
    flushTimer = setTimeout(() => {
      flush()
    }, flushInterval)

    if (!rafId) {
      rafId = requestAnimationFrame(() => {
        rafId = null
        flush()
      })
    }
  }

  function flush() {
    if (!buffer) return
    const text = buffer
    buffer = ''
    onAppend(text)
    throttledScroll()
  }

  function throttledScroll() {
    const now = Date.now()
    if (now - lastScroll > scrollThrottle) {
      lastScroll = now
      nextTick(() => {
        const el = document.querySelector('.message-list')
        if (el) el.scrollTop = el.scrollHeight
      })
    }
  }

  function finish() {
    flush()
    isTyping.value = false
    showCursor.value = false
    if (flushTimer) clearTimeout(flushTimer)
    if (rafId) cancelAnimationFrame(rafId)
    if (cursorTimer) clearInterval(cursorTimer)
    flushTimer = null
    rafId = null
    cursorTimer = null
    onFlush?.()
  }

  return {
    isTyping,
    showCursor,
    start,
    push,
    finish
  }
}
