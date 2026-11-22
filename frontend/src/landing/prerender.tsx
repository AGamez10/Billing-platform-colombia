import { renderToString } from 'react-dom/server';
import { StaticRouter } from 'react-router';
import { BRAND_NAME, BRAND_TAGLINE } from '../brand';
import { FAQ } from './faq';
import { Landing } from './Landing';

/**
 * Entrada de prerenderizado (solo en el build): la página pública en HTML para buscadores, redes
 * sociales y la primera pintura. `__SITE_URL__` lo reemplaza nginx por el dominio real al servirla.
 */
export function render(): string {
  return renderToString(
    <StaticRouter location="/">
      <Landing />
    </StaticRouter>,
  );
}

export const SEO = {
  title: `${BRAND_NAME}: factura electrónica DIAN para todo Colombia`,
  description:
    'Facturas electrónicas, tiquetes POS y notas crédito con validez ante la DIAN, desde el celular. Para los 1.122 municipios de Colombia. Prueba gratis.',
};

/** Datos estructurados schema.org. Solo afirmaciones verificables y visibles en la página. */
export function structuredData(): object[] {
  const site = '__SITE_URL__';
  return [
    {
      '@context': 'https://schema.org',
      '@type': 'Organization',
      name: BRAND_NAME,
      url: `${site}/`,
      logo: `${site}/icons/icon-512.png`,
      areaServed: { '@type': 'Country', name: 'Colombia' },
    },
    {
      '@context': 'https://schema.org',
      '@type': 'WebSite',
      name: BRAND_NAME,
      url: `${site}/`,
      inLanguage: 'es-CO',
    },
    {
      '@context': 'https://schema.org',
      '@type': 'SoftwareApplication',
      name: BRAND_NAME,
      description: BRAND_TAGLINE,
      applicationCategory: 'BusinessApplication',
      applicationSubCategory: 'Facturación electrónica',
      operatingSystem: 'Web, Android, iOS',
      inLanguage: 'es-CO',
      url: `${site}/`,
      image: `${site}/og.png`,
      offers: { '@type': 'Offer', price: '0', priceCurrency: 'COP', description: 'Prueba gratis con 20 documentos en producción' },
      featureList: [
        'Factura electrónica de venta',
        'Documento equivalente electrónico POS',
        'Nota crédito electrónica',
        'Cobro con QR',
        'API REST y webhooks firmados',
        'Verificación en dos pasos',
      ],
    },
    {
      '@context': 'https://schema.org',
      '@type': 'FAQPage',
      mainEntity: FAQ.map((f) => ({ '@type': 'Question', name: f.q, acceptedAnswer: { '@type': 'Answer', text: f.a } })),
    },
  ];
}
