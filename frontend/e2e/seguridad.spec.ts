import { expect, test } from '@playwright/test';
import { PASSWORD, registerOrganization, suffix } from './helpers';
import { totp } from './totp';

test('activar la verificación en dos pasos y entrar con el código de la app', async ({ page }) => {
  const s = suffix();
  const email = `dos-pasos-${s}@example.com`;
  await registerOrganization(page, `Segura ${s}`, email);

  await page.goto('/seguridad');
  await expect(page.getByRole('heading', { name: 'Seguridad de tu cuenta' })).toBeVisible();
  await page.getByRole('button', { name: 'Activar verificación en dos pasos' }).click();
  await expect(page.getByRole('img', { name: /Código QR/ })).toBeVisible();
  await page.getByText('¿No puedes escanearlo? Escribe la clave').click();
  const secret = (await page.getByLabel('Clave para escribir en la app').textContent())!.replace(/\s/g, '');
  expect(secret).toMatch(/^[A-Z2-7]{32}$/);

  await page.getByLabel('Código de 6 dígitos').fill(totp(secret));
  await page.getByRole('button', { name: 'Confirmar y activar' }).click();
  await expect(page.getByRole('heading', { name: 'Guarda tus códigos de recuperación' })).toBeVisible();
  await expect(page.getByRole('list', { name: 'Códigos de recuperación' }).getByRole('listitem')).toHaveCount(10);
  await page.getByRole('button', { name: 'Ya los guardé' }).click();
  await expect(page.getByText('Activa', { exact: true })).toBeVisible();
  await expect(page.getByText('Te quedan 10 códigos de recuperación sin usar.')).toBeVisible();

  // Sin la cookie de sesión hay que volver a entrar: la contraseña sola ya no basta.
  await page.context().clearCookies();
  await page.goto('/entrar');
  await page.getByLabel('Correo').fill(email);
  await page.getByLabel('Contraseña').fill(PASSWORD);
  await page.getByRole('button', { name: 'Entrar' }).click();
  await expect(page.getByRole('heading', { name: 'Verificación en dos pasos' })).toBeVisible();
  // El código con el que se activó ya se usó: la app mostraría el siguiente.
  await page.getByLabel('Código de 6 dígitos').fill(totp(secret, 1));
  await page.getByRole('button', { name: 'Entrar' }).click();
  await expect(page.getByRole('heading', { name: /Hola/ })).toBeVisible();
});
