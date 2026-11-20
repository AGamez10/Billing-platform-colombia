import { expect, test, type Page } from '@playwright/test';
import { nitCheckDigit } from '../src/lib/nit';
import { randomNit, registerOrganization, suffix } from './helpers';

// Capturas de la app con datos (solo con CAPTURE=1): cómo se ve después de unas semanas de uso.
test.skip(!process.env.CAPTURE, 'solo para revisión visual');

async function settle(page: Page) {
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(400);
}

test('capturas de la app en uso', async ({ page }, info) => {
  test.setTimeout(300_000);
  page.on('dialog', (d) => void d.accept());
  const shot = async (name: string, fullPage = true) => {
    await settle(page);
    await page.screenshot({ path: `capturas/${info.project.name}-app-${name}.jpg`, type: 'jpeg', quality: 62, fullPage });
  };

  const s = suffix();
  await registerOrganization(page, `Fundación Patitas ${s}`, `uso-${s}@example.com`);

  // Simulación + resoluciones de factura, POS y nota crédito
  await page.goto('/configuracion');
  await page.getByRole('button', { name: 'Usar modo simulación' }).click();
  await expect(page.getByText(/Activo:/)).toBeVisible();
  for (const [kind, number, prefix] of [
    ['INVOICE', '18760000001', 'FP'],
    ['POS_EQUIVALENT', '18760000002', 'POS'],
    ['CREDIT_NOTE', '18760000003', 'NC'],
  ] as const) {
    await page.getByRole('button', { name: 'Registrar resolución' }).click();
    await page.getByLabel('Tipo de documento').selectOption(kind);
    await page.getByLabel('Número de resolución').fill(number);
    await page.getByLabel('Prefijo').fill(prefix);
    await page.getByLabel('Hasta', { exact: true }).fill('5000');
    await page.getByLabel('Vigente hasta').fill(`${new Date().getFullYear() + 1}-12-31`);
    await page.getByRole('button', { name: 'Guardar resolución' }).click();
    await expect(page.getByRole('cell', { name: number })).toBeVisible();
  }

  // Clientes desde CSV
  await page.goto('/importar?tipo=clientes');
  const rows = [
    ['NIT', randomNit(), 'Veterinaria San Roque S.A.S.', 'contabilidad@sanroque.co'],
    ['NIT', randomNit(), 'Alimentos Nutrican Ltda.', 'facturas@nutrican.co'],
    ['CC', '1020304050', 'Laura Martínez Gómez', 'laura.martinez@example.com'],
    ['CC', '79845123', 'Andrés Felipe Rojas', ''],
    ['NIT', randomNit(), 'Clínica Huellitas', 'admin@huellitas.co'],
  ];
  const csv = 'tipo_documento;numero;nombre;correo\n' + rows.map((r) => r.join(';')).join('\n') + '\n';
  await page.locator('input[type=file]').setInputFiles({ name: 'clientes.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) });
  await expect(page.getByText(/se pueden importar/)).toBeVisible();
  await shot('importar');
  await page.getByRole('button', { name: /Importar \d+ clientes/ }).click();
  await expect(page.getByText(/Se crearon \d+ clientes/)).toBeVisible();

  // Productos
  for (const [name, price, type] of [
    ['Consulta veterinaria', '45.000', 'SERVICE'],
    ['Baño y peluquería', '35.000', 'SERVICE'],
    ['Concentrado 2 kg', '28.500', 'PRODUCT'],
    ['Vacuna antirrábica', '25.000', 'SERVICE'],
  ] as const) {
    await page.goto('/productos/nuevo');
    await page.getByLabel('Nombre').fill(name);
    await page.getByLabel('Tipo', { exact: true }).selectOption(type);
    await page.getByLabel('Precio unitario antes de impuestos').fill(price);
    await page.getByRole('button', { name: 'Guardar' }).click();
    await expect(page.getByText(name).first()).toBeVisible();
  }

  // Tres facturas emitidas
  const invoices: [string, string, string, string][] = [
    ['Veterinaria San Roque', 'Esterilización y control', '1', '180.000'],
    ['Alimentos Nutrican', 'Concentrado 2 kg', '12', '28.500'],
    ['Clínica Huellitas', 'Jornada de vacunación', '20', '25.000'],
  ];
  let firstInvoiceUrl = '';
  for (const [customer, description, qty, price] of invoices) {
    await page.goto('/facturas/nueva');
    await page.getByRole('combobox', { name: '¿A quién le facturas?' }).fill(customer);
    await page.getByRole('option', { name: new RegExp(customer) }).click();
    await page.getByLabel('Descripción').fill(description);
    await page.getByLabel('Cantidad').fill(qty);
    await page.getByLabel('Precio unitario').fill(price);
    if (!firstInvoiceUrl) await shot('factura-editor');
    await page.getByRole('button', { name: 'Guardar y revisar totales' }).click();
    await expect(page.getByRole('heading', { name: 'Borrador de factura' })).toBeVisible();
    await page.getByRole('button', { name: 'Emitir factura' }).click();
    await expect(page.getByText(/Aceptada en/)).toBeVisible({ timeout: 30_000 });
    if (!firstInvoiceUrl) {
      firstInvoiceUrl = page.url();
      await shot('factura-detalle');
    }
  }

  // Un borrador pendiente para que el inicio tenga algo que decir
  await page.goto('/facturas/nueva');
  await page.getByRole('combobox', { name: '¿A quién le facturas?' }).fill('Laura');
  await page.getByRole('option', { name: /Laura/ }).click();
  await page.getByLabel('Descripción').fill('Consulta veterinaria');
  await page.getByLabel('Cantidad').fill('1');
  await page.getByLabel('Precio unitario').fill('45.000');
  await page.getByRole('button', { name: 'Guardar y revisar totales' }).click();
  await expect(page.getByRole('heading', { name: 'Borrador de factura' })).toBeVisible();

  // Representación gráfica
  await page.goto(firstInvoiceUrl);
  await page.getByRole('link', { name: 'Ver e imprimir' }).click();
  await expect(page.getByRole('img', { name: /Código QR del CUFE/ })).toBeVisible();
  await shot('documento');

  // Caja: carrito con productos, y una venta cobrada
  await page.goto('/vender');
  await page.getByRole('button', { name: /Concentrado 2 kg/ }).click();
  await page.getByRole('button', { name: /Concentrado 2 kg/ }).click();
  await page.getByRole('button', { name: /Baño y peluquería/ }).click();
  await shot('vender', false);
  await page.getByRole('button', { name: /Cobrar/ }).click();
  await expect(page.getByText('Aceptada', { exact: true })).toBeVisible({ timeout: 30_000 });
  await shot('venta-emitida', false);

  // Pantallas principales con datos
  await page.goto('/');
  await expect(page.getByRole('heading', { name: /^Ventas de / })).toBeVisible();
  await page.waitForTimeout(800); // gráfica diferida
  await shot('inicio');
  await page.goto('/facturas');
  await shot('facturas');
  await page.goto('/clientes');
  await shot('clientes');
  await page.goto('/productos');
  await shot('productos');
  await page.goto('/reportes');
  await expect(page.getByText('Documentos validados')).toBeVisible();
  await shot('reportes');
  await page.goto('/integraciones');
  await shot('integraciones');
  await page.goto('/seguridad');
  await page.getByRole('button', { name: 'Activar verificación en dos pasos' }).click();
  await expect(page.getByRole('img', { name: /Código QR/ })).toBeVisible();
  await shot('seguridad');

  // Nuevo cliente con DV automático (formulario completo)
  await page.goto('/clientes/nuevo');
  const nit = randomNit();
  await page.getByLabel('NIT', { exact: true }).fill(nit);
  await page.getByLabel('DV').fill(String(nitCheckDigit(nit)));
  await shot('cliente-nuevo');
});
