import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { QrCode } from '../components/QrCode';

describe('QrCode', () => {
  it('dibuja el link de pago como un SVG accesible con zona silenciosa', () => {
    render(<QrCode value="https://checkout.bold.co/LNK_ABC123" label="Código QR para pagar $ 5.950" />);
    const svg = screen.getByRole('img', { name: 'Código QR para pagar $ 5.950' });
    const [x, y, w, h] = svg.getAttribute('viewBox')!.split(' ').map(Number);
    expect(x).toBe(-4);
    expect(y).toBe(-4);
    expect(w).toBe(h);
    expect(w).toBeGreaterThanOrEqual(21 + 8); // versión 1 = 21 módulos + 4 de margen por lado
    expect(svg.querySelector('path')!.getAttribute('d')).toMatch(/^M\d+ \d+h1v1h-1z/);
  });

  it('cambia cuando cambia el link', () => {
    const { rerender, container } = render(<QrCode value="https://checkout.bold.co/A" label="qr" />);
    const first = container.querySelector('path')!.getAttribute('d');
    rerender(<QrCode value="https://checkout.bold.co/B" label="qr" />);
    expect(container.querySelector('path')!.getAttribute('d')).not.toBe(first);
  });
});
