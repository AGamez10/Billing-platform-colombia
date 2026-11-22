/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// En desarrollo (npm run dev) la API se alcanza por el mismo origen, como en producción,
// para que la cookie de sesión (SameSite=Strict, Path=/api/v1/auth) funcione igual.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { '/api': 'http://localhost:8080' },
  },
  build: {
    sourcemap: false,
    target: 'es2022',
    // El manifiesto le dice al prerenderizado qué CSS lleva la página pública (se borra después).
    manifest: true,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
  },
});
