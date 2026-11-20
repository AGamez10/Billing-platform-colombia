import { expect, test } from '@playwright/test';
import { nitCheckDigit } from '../src/lib/nit';
import { randomNit, registerOrganization, suffix } from './helpers';

test('el inicio dice qué falta y la factura se arma sin salir del editor', async ({ page }) => {
  const s = suffix();
  await registerOrganization(page, `Inicio ${s}`, `inicio-${s}@example.com`);

  // Inicio: lo pendiente con su botón
  const attention = page.getByRole('region', { name: 'Requiere tu atención' });
  await expect(attention.getByText('Conecta la facturación electrónica')).toBeVisible();
  await attention.getByRole('link', { name: 'Configurar' }).click();
  await expect(page.getByRole('heading', { name: 'Configura tu cuenta' })).toBeVisible();

  // Práctica con numeración desde Ajustes
  await page.getByRole('link', { name: 'Ajustes' }).click();
  await page.getByRole('button', { name: 'Usar modo simulación' }).click();
  await expect(page.getByText(/Activo:/)).toBeVisible();
  await page.getByRole('button', { name: 'Registrar resolución' }).click();
  await page.getByLabel('Número de resolución').fill('18760000001');
  await page.getByLabel('Prefijo').fill('INI');
  await page.getByLabel('Hasta', { exact: true }).fill('1000');
  await page.getByLabel('Vigente hasta').fill(`${new Date().getFullYear() + 1}-12-31`);
  await page.getByRole('button', { name: 'Guardar resolución' }).click();
  await expect(page.getByRole('cell', { name: '18760000001' })).toBeVisible();

  // Factura con cliente nuevo creado ahí mismo y total estimado mientras se escribe
  await page.goto('/facturas/nueva');
  await page.getByRole('button', { name: '+ Nuevo cliente' }).click();
  const nit = randomNit();
  const quick = page.getByRole('group', { name: 'Nuevo cliente' });
  await quick.getByLabel('Número').fill(nit);
  await expect(quick.getByLabel('DV')).toHaveValue(String(nitCheckDigit(nit)));
  await quick.getByLabel('Nombre o razón social').fill(`Comprador ${s}`);
  await quick.getByRole('button', { name: 'Guardar cliente' }).click();
  await expect(page.getByRole('combobox', { name: '¿A quién le facturas?' })).toHaveValue(`Comprador ${s}`);

  await page.getByLabel('Descripción').fill('Baño y peluquería');
  await page.getByLabel('Cantidad').fill('2');
  await page.getByLabel('Precio unitario').fill('25.000');
  await expect(page.getByText('Total estimado con impuestos')).toBeVisible();
  await expect(page.locator('.estimate strong')).toContainText('59.500');
  await page.getByRole('button', { name: 'Guardar y revisar totales' }).click();
  await expect(page.getByRole('heading', { name: 'Borrador de factura' })).toBeVisible();
  await expect(page.locator('dd.grand')).toContainText('59.500');

  // De vuelta al inicio ya no pide conectar
  await page.getByRole('link', { name: 'Inicio' }).click();
  await expect(page.getByRole('region', { name: 'Requiere tu atención' })).toBeVisible();
  await expect(page.getByText('Conecta la facturación electrónica')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: /^Ventas de / })).toBeVisible();
});

test('importar clientes desde CSV y ver el reporte del mes', async ({ page }) => {
  const s = suffix();
  await registerOrganization(page, `Importa ${s}`, `importa-${s}@example.com`);
  await page.goto('/clientes');
  await page.getByRole('link', { name: 'Importar' }).click();
  await expect(page.getByRole('heading', { name: 'Importar clientes' })).toBeVisible();
  const nit = randomNit();
  await page.locator('input[type=file]').setInputFiles({
    name: 'clientes.csv',
    mimeType: 'text/csv',
    buffer: Buffer.from(`tipo_documento;numero;nombre;correo\nNIT;${nit};Importado ${s};imp@example.com\nXX;1;Malo;\n`),
  });
  await expect(page.getByText(/1\s*de 2 filas se pueden importar/)).toBeVisible();
  await expect(page.getByRole('cell', { name: /tipo_documento debe ser/ })).toBeVisible();
  await page.getByRole('button', { name: 'Importar 1 clientes' }).click();
  await expect(page.getByText('Se crearon 1 clientes.')).toBeVisible();
  await page.getByRole('link', { name: 'Ver clientes' }).click();
  await expect(page.getByText(`Importado ${s}`)).toBeVisible();

  await page.goto('/reportes');
  await expect(page.getByRole('heading', { name: 'Reportes' })).toBeVisible();
  await expect(page.getByText('Documentos validados')).toBeVisible();
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Descargar para Excel (CSV)' }).click();
  expect((await download).suggestedFilename()).toMatch(/^ventas-.*\.csv$/);
});
