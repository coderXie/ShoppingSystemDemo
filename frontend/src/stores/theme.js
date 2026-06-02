import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

const THEME_KEY = 'shop-agent-theme'

export const useThemeStore = defineStore('theme', () => {
  const isDark = ref(true)

  function init() {
    const saved = localStorage.getItem(THEME_KEY)
    if (saved !== null) {
      isDark.value = saved === 'dark'
    } else {
      isDark.value = window.matchMedia('(prefers-color-scheme: dark)').matches
    }
    apply()
  }

  function toggle() {
    isDark.value = !isDark.value
    apply()
    localStorage.setItem(THEME_KEY, isDark.value ? 'dark' : 'light')
  }

  function apply() {
    const html = document.documentElement
    if (isDark.value) {
      html.classList.add('dark')
      html.classList.remove('light')
    } else {
      html.classList.add('light')
      html.classList.remove('dark')
    }
  }

  watch(isDark, apply, { immediate: false })

  return { isDark, init, toggle }
})
