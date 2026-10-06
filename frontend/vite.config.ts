/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { VitePWA } from 'vite-plugin-pwa'

const backendUrl = process.env.KARNAME_BACKEND_URL ?? 'http://localhost:8080'

const apiProxy = {
  '/api': { target: backendUrl, changeOrigin: false },
  '/actuator': { target: backendUrl, changeOrigin: false },
}

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['favicon.svg', 'apple-touch-icon.png'],
      manifest: {
        name: 'کارنامه — مدیریت مالی شخصی',
        short_name: 'کارنامه',
        description: 'مدیریت مالی و دارایی شخصی با دستیار هوشمند',
        lang: 'fa',
        dir: 'rtl',
        start_url: '/',
        display: 'standalone',
        background_color: '#f7faf9',
        theme_color: '#0f766e',
        icons: [
          { src: '/pwa-192.png', sizes: '192x192', type: 'image/png' },
          { src: '/pwa-512.png', sizes: '512x512', type: 'image/png' },
          { src: '/pwa-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
      workbox: {
        // Only the app shell is cached; financial data is always fetched live.
        navigateFallback: '/index.html',
        navigateFallbackDenylist: [/^\/api\//, /^\/actuator\//, /^\/swagger-ui/, /^\/v3\//],
        globPatterns: ['**/*.{js,css,html,svg,png,woff2}'],
      },
    }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    rolldownOptions: {
      output: {
        // Long-lived vendor chunks: app deploys don't invalidate the cached libraries.
        codeSplitting: {
          groups: [
            // Higher priority captures first, so shared helpers (clsx…) stay out of the lazily loaded chart chunk.
            { name: 'vendor-react', test: /[\\/]node_modules[\\/](react|react-dom|scheduler|react-router)[\\/]/, priority: 3 },
            { name: 'vendor-ui', test: /[\\/]node_modules[\\/](radix-ui|@radix-ui|@floating-ui|@tanstack|sonner|lucide-react|clsx|tailwind-merge|class-variance-authority)[\\/]/, priority: 2 },
            { name: 'vendor-charts', test: /[\\/]node_modules[\\/](recharts|d3-[a-z-]+|victory-vendor|@reduxjs|react-redux|redux|reselect|immer|decimal\.js-light|es-toolkit)[\\/]/, priority: 1 },
          ],
        },
      },
    },
  },
  server: {
    port: 5173,
    proxy: apiProxy,
  },
  preview: {
    port: 4173,
    proxy: apiProxy,
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    css: false,
  },
})
