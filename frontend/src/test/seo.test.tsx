import { FAQ } from '../landing/faq';
import { render, SEO, structuredData } from '../landing/prerender';

describe('página pública prerenderizada (SEO)', () => {
  const html = render();

  it('tiene un solo h1 y los encabezados en orden', () => {
    expect(html.match(/<h1[\s>]/g)).toHaveLength(1);
    expect(html).toContain('Factura electrónica para todo Colombia.');
    const levels = [...html.matchAll(/<h([1-6])[\s>]/g)].map((m) => Number(m[1]));
    levels.forEach((level, i) => {
      if (i > 0) expect(level - levels[i - 1]!).toBeLessThanOrEqual(1);
    });
  });

  it('las preguntas visibles y las de los datos estructurados son las mismas', () => {
    const faq = structuredData().find((d) => (d as { '@type': string })['@type'] === 'FAQPage') as {
      mainEntity: { name: string; acceptedAnswer: { text: string } }[];
    };
    expect(faq.mainEntity.map((q) => q.name)).toEqual(FAQ.map((f) => f.q));
    FAQ.forEach((f) => expect(html).toContain(f.q));
  });

  it('título y descripción con largo adecuado para buscadores', () => {
    expect(SEO.title.length).toBeLessThanOrEqual(65);
    expect(SEO.description.length).toBeGreaterThan(110);
    expect(SEO.description.length).toBeLessThanOrEqual(165);
  });

  it('todas las imágenes del HTML tienen texto alternativo o son decorativas', () => {
    const imgs = [...html.matchAll(/<img\b[^>]*>/g)].map((m) => m[0]);
    imgs.forEach((tag) => expect(tag).toMatch(/\salt="/));
  });
});
