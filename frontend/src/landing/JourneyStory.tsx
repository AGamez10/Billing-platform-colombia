import { useEffect, useRef, useState } from 'react';
import { Seal } from './Motifs';

const STEPS = [
  {
    title: 'Vendes',
    text: 'Registras la venta en el celular o en el computador. Nítido calcula el IVA, los descuentos y el total con la tarifa vigente ese día.',
  },
  {
    title: 'Se numera y se firma',
    text: 'Toma el siguiente número de tu resolución de la DIAN y arma el documento electrónico, firmado por un proveedor tecnológico autorizado.',
  },
  {
    title: 'La DIAN lo valida',
    text: 'En segundos llega el CUFE, la prueba de que la DIAN lo aceptó. Si la DIAN se demora, el envío se reintenta solo y nunca se duplica.',
  },
  {
    title: 'Tu cliente lo recibe',
    text: 'Por correo, por WhatsApp o con un QR en la caja. Y queda en tu reporte del mes, listo para el contador.',
  },
];

/**
 * El viaje de una factura, contado con el scroll: a la izquierda el documento cambia de estado,
 * a la derecha cada paso. Sin JavaScript (o sin IntersectionObserver) se ven todos los pasos y el
 * documento completo.
 */
export function JourneyStory() {
  const [active, setActive] = useState(0);
  const [live, setLive] = useState(false);
  const refs = useRef<(HTMLLIElement | null)[]>([]);

  useEffect(() => {
    if (typeof IntersectionObserver === 'undefined') return;
    setLive(true);
    const io = new IntersectionObserver(
      (entries) => {
        for (const e of entries) {
          if (e.isIntersecting) setActive(Number((e.target as HTMLElement).dataset.step));
        }
      },
      { rootMargin: '-45% 0px -45% 0px' },
    );
    refs.current.forEach((el) => el && io.observe(el));
    return () => io.disconnect();
  }, []);

  const step = live ? active : STEPS.length - 1;

  return (
    <div className="journey">
      <div className="journey-stage-wrap">
        <div className={['journey-stage', ...STEPS.map((_, i) => (i <= step ? `ge-${i}` : ''))].join(' ').trim()} aria-hidden="true">
          <svg className="journey-thread" viewBox="0 0 400 520" preserveAspectRatio="none">
            <path d="M40 30 C 140 80, 20 170, 120 220 S 340 300, 260 380 S 120 470, 360 500" pathLength={100} />
          </svg>
          <div className="jdoc">
            <div className="jdoc-head">
              <span className="jdoc-kind">Factura electrónica de venta</span>
              <strong className="jdoc-number">FE 1043</strong>
              <span className="jdoc-res">Resolución 18764000000000</span>
            </div>
            <ul className="jdoc-lines">
              <li>
                <span>Mantenimiento preventivo</span>
                <span className="num">$ 180.000</span>
              </li>
              <li>
                <span>Cambio de aceite × 2</span>
                <span className="num">$ 130.000</span>
              </li>
              <li>
                <span>Alineación y balanceo</span>
                <span className="num">$ 90.000</span>
              </li>
            </ul>
            <div className="jdoc-tax">
              <span>IVA 19 %</span>
              <span className="num">$ 76.000</span>
            </div>
            <div className="jdoc-total">
              <span>Total</span>
              <strong className="num">$ 476.000</strong>
            </div>
            <div className="jdoc-cufe">
              <span>CUFE</span>
              <code>8f3a61c2d9e04b7a95c1…</code>
            </div>
            <div className="jdoc-seal">
              <Seal />
              <span>
                Aceptada
                <br />
                por la DIAN
              </span>
            </div>
          </div>
          <div className="jphone">
            <p className="jphone-bubble">Hola, aquí está tu factura FE 1043 por $ 476.000.</p>
            <div className="jphone-qr">
              {Array.from({ length: 25 }, (_, i) => (
                <span key={i} className={(i * 7) % 3 === 0 || i % 5 === 0 ? 'on' : ''} />
              ))}
            </div>
          </div>
        </div>
      </div>
      <ol className="journey-steps">
        {STEPS.map((s, i) => (
          <li
            key={s.title}
            data-step={i}
            ref={(el) => {
              refs.current[i] = el;
            }}
            className={i === step ? 'is-active' : undefined}
          >
            <h3>{s.title}</h3>
            <p>{s.text}</p>
          </li>
        ))}
      </ol>
    </div>
  );
}
