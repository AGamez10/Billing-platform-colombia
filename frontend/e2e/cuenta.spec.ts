import { expect, test } from '@playwright/test';
import { linkFromEmail, PASSWORD, registerOrganization, suffix } from './helpers';

test('la página pública: demo sin registro, calculadora con precios reales y registro', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1, name: 'Vende, cobra y la factura sale sola.' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Cada suscripción le factura a alguien más' })).toBeVisible();

  // La caja de demostración funciona de punta a punta sin cuenta
  await page.getByRole('button', { name: /Leche 1 L/ }).click();
  await page.getByRole('button', { name: /Cobrar con QR \$ 12\.700/ }).click();
  await expect(page.getByRole('img', { name: 'Código QR de demostración' })).toBeVisible();
  await page.getByRole('button', { name: 'Simular que el cliente pagó' }).click();
  await expect(page.getByText('Pago recibido')).toBeVisible();
  await expect(page.getByText('Demostración: no se envió a la DIAN.')).toBeVisible();

  // La calculadora recomienda con el catálogo real
  await page.getByRole('radio', { name: 'Vendo en caja (POS)' }).click();
  await expect(page.locator('.calc-plan')).toHaveText('Comercio 500');
  await page.getByRole('slider').focus();
  await page.keyboard.press('End');
  await expect(page.locator('.calc-plan')).toHaveText('Comercio 3.000');
  await page.getByText('Ver todos los planes').click();
  await expect(page.getByRole('rowheader', { name: 'Comercio 1.200' })).toBeVisible();
  await page.getByRole('link', { name: /Empieza gratis con 20 documentos/ }).click();
  await expect(page).toHaveURL(/\/registro$/);
});

test('recuperar la contraseña con el enlace del correo', async ({ page, request }) => {
  const email = `olvido-${suffix()}@example.com`;
  await registerOrganization(page, `Fundación Olvido ${suffix()}`, email);
  await page.goto('/configuracion');
  await page.getByRole('button', { name: 'Cerrar sesión' }).last().click();

  await page.goto('/entrar');
  await page.getByLabel('Correo').fill(email);
  await page.getByRole('link', { name: '¿Olvidaste tu contraseña?' }).click();
  await expect(page.getByRole('heading', { name: '¿Olvidaste tu contraseña?' })).toBeVisible();
  await expect(page.getByLabel('Correo')).toHaveValue(email); // el correo del inicio de sesión viene escrito
  await page.getByRole('button', { name: 'Enviar enlace' }).click();
  await expect(page.getByRole('heading', { name: 'Revisa tu correo' })).toBeVisible();

  const link = await linkFromEmail(request, email, '/restablecer');
  await page.goto(link);
  await expect(page).not.toHaveURL(/#t=/); // el token sale de la barra de direcciones
  await page.getByLabel('Contraseña nueva').fill('una clave nueva muy segura');
  await page.getByLabel('Repítela').fill('una clave nueva muy segura');
  await page.getByRole('button', { name: 'Guardar contraseña' }).click();
  await expect(page.getByRole('heading', { name: 'Listo, tu contraseña cambió' })).toBeVisible();

  await page.getByRole('link', { name: 'Iniciar sesión' }).click();
  await page.getByLabel('Correo').fill(email);
  await page.getByLabel('Contraseña', { exact: true }).fill('una clave nueva muy segura');
  await page.getByRole('button', { name: 'Entrar' }).click();
  await expect(page.getByRole('heading', { name: /Hola/ })).toBeVisible();
});

test('invitar a la caja: acepta desde el correo y entra con su propio usuario', async ({ page, browser, request }) => {
  const org = `Tienda Equipo ${suffix()}`;
  await registerOrganization(page, org, `duena-${suffix()}@example.com`);
  await page.goto('/equipo');
  const caja = `caja-${suffix()}@example.com`;
  await page.getByLabel('Correo').fill(caja);
  await page.getByLabel(/Facturador/).check();
  await page.getByRole('button', { name: 'Enviar invitación' }).click();
  await expect(page.getByText(`Enviamos la invitación a ${caja}`)).toBeVisible();
  await expect(page.getByText('Invitaciones sin aceptar')).toBeVisible();

  const link = await linkFromEmail(request, caja, '/invitacion');
  const other = await browser.newContext();
  const guest = await other.newPage();
  await guest.goto(link);
  await expect(guest.getByRole('heading', { name: `Únete a ${org}` })).toBeVisible();
  await guest.getByLabel('Tu nombre completo').fill('Carlos Caja');
  await guest.getByLabel('Crea una contraseña').fill(PASSWORD);
  await guest.getByLabel(/Acepto los/).check();
  await guest.getByRole('button', { name: `Unirme a ${org}` }).click();
  await expect(guest.getByRole('heading', { name: /Hola/ })).toBeVisible();
  await guest.goto('/equipo');
  await expect(guest.getByText(/Solo el dueño o un administrador/)).toBeVisible();
  await other.close();

  await page.reload();
  await expect(page.getByText('Carlos Caja', { exact: true })).toBeVisible();
});

test('configuración guiada: de cuenta nueva a lista para vender', async ({ page }) => {
  await registerOrganization(page, `Droguería Guía ${suffix()}`, `guia-${suffix()}@example.com`);
  await page.getByRole('link', { name: 'Configuración guiada' }).first().click();
  await expect(page.getByRole('heading', { name: 'Configura tu cuenta' })).toBeVisible();
  await page.getByRole('radio', { name: /En mostrador/ }).click();
  await page.getByRole('button', { name: 'Empezar en práctica' }).click();
  await expect(page.getByText(/Listo: estás en/)).toBeVisible();
  await page.getByRole('button', { name: 'Crear numeración de práctica' }).click();
  await expect(page.getByText(/ya tienes numeración vigente para tu POS/)).toBeVisible();
  await page.getByRole('link', { name: 'Hacer una venta' }).click();
  await expect(page).toHaveURL(/\/vender$/);
});
