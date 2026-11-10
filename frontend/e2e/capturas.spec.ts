import { test } from '@playwright/test';
import { registerOrganization, suffix } from './helpers';

// Capturas para revisar el diseño (solo con CAPTURE=1). Se guardan en capturas/.
test.skip(!process.env.CAPTURE, 'solo para revisión visual');

test('capturas de pantallas principales', async ({ page }, info) => {
  const shot = async (name: string, fullPage = false) =>
    page.screenshot({ path: `capturas/${info.project.name}-${name}.jpg`, type: 'jpeg', quality: 62, fullPage });
  await page.goto('/');
  await page.waitForTimeout(2600); // termina la animación del tiquete
  await shot('landing', true);
  await page.goto('/entrar');
  await shot('entrar');
  await registerOrganization(page, `Tienda Doña Rosa ${suffix()}`, `capturas-${suffix()}@example.com`);
  await shot('inicio');
  await page.goto('/empezar');
  await shot('empezar', true);
  await page.goto('/equipo');
  await shot('equipo', true);
  await page.goto('/plan');
  await shot('plan', true);
});
