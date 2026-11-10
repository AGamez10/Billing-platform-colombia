import { expect, type APIRequestContext, type Page } from '@playwright/test';
import { nitCheckDigit } from '../src/lib/nit';

export const PASSWORD = 'caballo bateria grapa correcta';
const MAILPIT = process.env.E2E_MAILPIT_URL ?? 'http://127.0.0.1:8025';

export function randomNit(): string {
  return String(900_000_000 + Math.floor(Math.random() * 99_999_999));
}

export function suffix(): string {
  return Math.random().toString(36).slice(2, 8);
}

/** Registra una organización desde la interfaz y deja la sesión abierta en el inicio. */
export async function registerOrganization(page: Page, name: string, email: string): Promise<void> {
  const nit = randomNit();
  await page.goto('/registro');
  await page.getByLabel('Nombre o razón social del negocio').fill(name);
  await page.getByLabel('NIT (sin dígito de verificación)').fill(nit);
  await page.getByLabel('Dígito de verificación', { exact: true }).fill(String(nitCheckDigit(nit)));
  await page.getByLabel('Tu nombre').fill('Prueba E2E');
  await page.getByLabel('Tu correo').fill(email);
  await page.getByLabel('Contraseña', { exact: true }).fill(PASSWORD);
  await page.getByLabel(/Acepto los términos/).check();
  await page.getByRole('button', { name: 'Crear cuenta gratis' }).click();
  await expect(page.getByRole('heading', { name: /Hola/ })).toBeVisible();
}

/** Último enlace enviado a ese correo, leído del buzón de desarrollo (Mailpit). */
export async function linkFromEmail(request: APIRequestContext, to: string, path: string): Promise<string> {
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    const search = await request.get(`${MAILPIT}/api/v1/search`, { params: { query: `to:"${to}"` } });
    if (search.ok()) {
      const { messages } = (await search.json()) as { messages: { ID: string }[] };
      if (messages.length > 0) {
        const message = await request.get(`${MAILPIT}/api/v1/message/${messages[0]!.ID}`);
        const { Text } = (await message.json()) as { Text: string };
        const match = new RegExp(`https?://[^\\s]+${path}#t=[A-Za-z0-9_-]+`).exec(Text);
        if (match) return match[0];
      }
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`No llegó el correo a ${to}`);
}
