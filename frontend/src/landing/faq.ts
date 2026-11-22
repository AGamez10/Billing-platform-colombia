/**
 * Preguntas frecuentes de la página pública. La misma lista alimenta el HTML visible y los datos
 * estructurados FAQPage del HTML prerenderizado: los buscadores exigen que coincidan.
 */
export const FAQ: { q: string; a: string }[] = [
  {
    q: '¿Las facturas de Nítido son válidas ante la DIAN?',
    a: 'Sí, cuando facturas en producción: los documentos se emiten a través de un proveedor tecnológico autorizado por la DIAN y cada uno lleva su CUFE. Mientras practicas, todo queda marcado como práctica y no tiene validez tributaria.',
  },
  {
    q: '¿Puedo facturar desde cualquier municipio de Colombia?',
    a: 'Sí. Nítido trae los 1.122 municipios y áreas no municipalizadas de la DIVIPOLA del DANE, con el código que exige la factura electrónica en la dirección del emisor y del cliente.',
  },
  {
    q: '¿Qué tipos de documento electrónico puedo emitir?',
    a: 'Factura electrónica de venta, documento equivalente electrónico POS (tiquete de caja) y nota crédito para anulaciones y devoluciones, con tu numeración autorizada por la DIAN.',
  },
  {
    q: '¿Qué incluye la prueba gratis?',
    a: 'Veinte documentos en producción, sin tarjeta. La práctica es ilimitada y no gasta documentos.',
  },
  {
    q: '¿Quién recibe el dinero de las ventas que cobro con QR?',
    a: 'Tú. Conectas tu propia cuenta Bold y el dinero llega directo a ella. Nítido solo crea el cobro y emite el documento cuando el pago se confirma.',
  },
  {
    q: '¿Puedo emitir facturas desde mi propio software o tienda en línea?',
    a: 'Sí. Creas una clave de API con los permisos que quieras; tu sistema emite y consulta documentos, y te avisamos por webhook firmado cuando la DIAN responde.',
  },
  {
    q: '¿Qué pasa si se acaba mi cupo de documentos del mes?',
    a: 'Te avisamos antes. Puedes subir de plan o, si tu plan lo permite y lo aceptas expresamente, seguir con documentos adicionales a un precio fijo. Nunca cobramos documentos extra sin tu aceptación.',
  },
];
