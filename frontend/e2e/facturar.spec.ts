import { expect, test } from '@playwright/test';
import { nitCheckDigit } from '../src/lib/nit';

function randomNit(): string {
  return String(900_000_000 + Math.floor(Math.random() * 99_999_999));
}

test('una organización nueva factura en simulación de punta a punta', async ({ page }) => {
  page.on('dialog', (d) => void d.accept());
  const nit = randomNit();
  const suffix = Math.random().toString(36).slice(2, 8);

  // 1. Registro
  await page.goto('/registro');
  await page.getByLabel('Nombre o razón social del negocio').fill(`Fundación E2E ${suffix}`);
  await page.getByLabel('NIT (sin dígito de verificación)').fill(nit);
  await page.getByLabel('Dígito de verificación', { exact: true }).fill(String(nitCheckDigit(nit)));
  await page.getByLabel('Tu nombre').fill('Prueba E2E');
  await page.getByLabel('Tu correo').fill(`e2e-${suffix}@example.com`);
  await page.getByLabel('Contraseña', { exact: true }).fill('caballo bateria grapa correcta');
  await page.getByLabel(/Acepto los términos/).check();
  await page.getByRole('button', { name: 'Crear cuenta gratis' }).click();
  await expect(page.getByRole('heading', { name: /Hola/ })).toBeVisible();

  // 2. Modo simulación y resolución
  await page.getByRole('link', { name: 'Ajustes' }).click();
  await page.getByRole('button', { name: 'Usar modo simulación' }).click();
  await expect(page.getByText(/Activo:/)).toBeVisible();
  await page.getByRole('button', { name: 'Registrar resolución' }).click();
  await page.getByLabel('Número de resolución').fill('18760000001');
  await page.getByLabel('Prefijo').fill('SETT');
  await page.getByLabel('Hasta', { exact: true }).fill('1000');
  await page.getByLabel('Vigente hasta').fill(`${new Date().getFullYear() + 1}-12-31`);
  await page.getByRole('button', { name: 'Guardar resolución' }).click();
  await expect(page.getByRole('cell', { name: '18760000001' })).toBeVisible();

  // 3. Cliente
  const customerNit = randomNit();
  await page.goto('/clientes/nuevo');
  await page.getByLabel('NIT', { exact: true }).fill(customerNit);
  await page.getByLabel('DV').fill(String(nitCheckDigit(customerNit)));
  await page.getByLabel('Nombre o razón social').fill('Cliente E2E S.A.S.');
  await page.getByLabel('Correo', { exact: true }).fill('cliente@example.com');
  await page.getByRole('button', { name: 'Guardar' }).click();
  await expect(page.getByText('Cliente E2E S.A.S.')).toBeVisible();

  // 4. Factura: el servidor calcula 2 × $50.000 + IVA 19 % = $119.000
  await page.goto('/facturas/nueva');
  await page.getByLabel('¿A quién le facturas?').selectOption({ index: 1 });
  await page.getByLabel('Descripción').fill('Consulta');
  await page.getByLabel('Cantidad').fill('2');
  await page.getByLabel('Precio unitario').fill('50.000');
  await page.getByRole('button', { name: 'Guardar y revisar totales' }).click();
  await expect(page.getByRole('heading', { name: 'Borrador de factura' })).toBeVisible();
  await expect(page.locator('dd.grand')).toContainText('119.000');

  // 5. Emitir y ver la respuesta del proveedor simulado, sin validez tributaria
  await page.getByRole('button', { name: 'Emitir factura' }).click();
  await expect(page.getByRole('heading', { name: 'Factura SETT1' })).toBeVisible();
  await expect(page.getByText(/Aceptada en/)).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText(/sin validez tributaria/).first()).toBeVisible();
  await expect(page.getByText(/^SIMULATED-/)).toBeVisible();
});

test('comercio: venta rápida a consumidor final emite el POS en simulación', async ({ page }) => {
  page.on('dialog', (d) => void d.accept());
  const nit = randomNit();
  const suffix = Math.random().toString(36).slice(2, 8);
  await page.goto('/registro');
  await page.getByLabel('Nombre o razón social del negocio').fill(`Tienda E2E ${suffix}`);
  await page.getByLabel('NIT (sin dígito de verificación)').fill(nit);
  await page.getByLabel('Dígito de verificación', { exact: true }).fill(String(nitCheckDigit(nit)));
  await page.getByLabel('Tu nombre').fill('Tendero E2E');
  await page.getByLabel('Tu correo').fill(`pos-${suffix}@example.com`);
  await page.getByLabel('Contraseña', { exact: true }).fill('caballo bateria grapa correcta');
  await page.getByLabel(/Acepto los términos/).check();
  await page.getByRole('button', { name: 'Crear cuenta gratis' }).click();
  await expect(page.getByRole('heading', { name: /Hola/ })).toBeVisible();

  await page.goto('/configuracion');
  await page.getByRole('button', { name: 'Usar modo simulación' }).click();
  await expect(page.getByText(/Activo:/)).toBeVisible();
  await page.getByRole('button', { name: 'Registrar resolución' }).click();
  await page.getByLabel('Tipo de documento').selectOption('POS_EQUIVALENT');
  await page.getByLabel('Número de resolución').fill('18760000002');
  await page.getByLabel('Prefijo').fill('POS');
  await page.getByLabel('Hasta', { exact: true }).fill('100000');
  await page.getByLabel('Vigente hasta').fill(`${new Date().getFullYear() + 1}-12-31`);
  await page.getByRole('button', { name: 'Guardar resolución' }).click();
  await expect(page.getByRole('cell', { name: '18760000002' })).toBeVisible();

  await page.goto('/productos/nuevo');
  await page.getByLabel('Nombre').fill('Gaseosa');
  await page.getByLabel('Tipo', { exact: true }).selectOption('PRODUCT');
  await page.getByLabel('Precio unitario antes de impuestos').fill('2.500');
  await page.getByRole('button', { name: 'Guardar' }).click();
  await expect(page.getByText('Gaseosa')).toBeVisible();

  await page.goto('/vender');
  await page.getByRole('button', { name: /Gaseosa/ }).click();
  await page.getByRole('button', { name: /Gaseosa/ }).click();
  const charge = page.getByRole('button', { name: /Cobrar/ });
  await expect(charge).toContainText('5.950');
  await charge.click();
  await expect(page.getByRole('heading', { name: 'Venta POS1' })).toBeVisible();
  await expect(page.getByText('Consumidor final')).toBeVisible();
  await expect(page.locator('.pos-total')).toContainText('5.950');
  await expect(page.getByText('Aceptada', { exact: true })).toBeVisible({ timeout: 30_000 });
});

test('sin sesión, la app lleva al inicio de sesión', async ({ page }) => {
  await page.goto('/facturas');
  await expect(page.getByRole('heading', { name: 'Inicia sesión' })).toBeVisible();
});
