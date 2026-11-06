import { defineConfig, devices } from '@playwright/test';

// Pruebas de punta a punta contra el stack de Docker (docker compose up): navegador real,
// nginx, backend y PostgreSQL. En CI corren en el job "Docker".
export default defineConfig({
  testDir: './e2e',
  timeout: 90_000,
  expect: { timeout: 15_000 },
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:3000',
    locale: 'es-CO',
    timezoneId: 'America/Bogota',
    trace: 'off',
  },
  projects: [
    { name: 'escritorio', use: { ...devices['Desktop Chrome'] } },
    { name: 'celular', use: { ...devices['Pixel 7'] } },
  ],
});
