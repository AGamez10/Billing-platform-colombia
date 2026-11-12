import { expect, test } from '@playwright/test';
import { registerOrganization, suffix } from './helpers';

// Capturas para revisar el diseño (solo con CAPTURE=1). Se guardan en capturas/.
test.skip(!process.env.CAPTURE, 'solo para revisión visual');

test('capturas de pantallas principales', async ({ page }, info) => {
  const shot = async (name: string, fullPage = false) =>
    page.screenshot({ path: `capturas/${info.project.name}-${name}.jpg`, type: 'jpeg', quality: 62, fullPage });
  await page.goto('/');
  await page.waitForTimeout(4500); // carga diferida del 3D y primeros segundos de la escena
  await shot('landing-hero');
  await page.waitForTimeout(3500); // el tiquete sale impreso
  await shot('landing-hero-tiquete');
  await page.locator('#precios').scrollIntoViewIfNeeded();
  await page.waitForTimeout(1200); // Chart.js se carga al acercarse
  await shot('landing', true);
  await page.goto('/entrar');
  await shot('entrar');
  await registerOrganization(page, `Tienda Doña Rosa ${suffix()}`, `capturas-${suffix()}@example.com`);
  await shot('inicio');
  await page.goto('/empezar');
  await expect(page.getByRole('heading', { name: 'Configura tu cuenta' })).toBeVisible();
  await shot('empezar', true);
  await page.goto('/empresa');
  await expect(page.getByRole('heading', { name: 'Datos de tu empresa' })).toBeVisible();
  await page.getByRole('combobox', { name: 'Municipio' }).fill('bogo');
  await expect(page.getByRole('option').first()).toBeVisible();
  await shot('empresa', true);
  await page.goto('/equipo');
  await expect(page.getByRole('heading', { name: 'Invitar a alguien' })).toBeVisible();
  await shot('equipo', true);
  await page.goto('/plan');
  await expect(page.getByRole('heading', { name: 'Tu plan también factura por otros' })).toBeVisible();
  await page.waitForTimeout(500);
  await shot('plan', true);
  await page.goto('/configuracion');
  await expect(page.getByRole('heading', { name: 'Ajustes' })).toBeVisible();
  await page.waitForTimeout(500);
  await shot('ajustes', true);
});
