import { useEffect, useMemo, useRef, useState } from 'react';
import type { Chart as ChartType } from 'chart.js';
import type { Plan } from '../api/types';

const STEPS = [5, 10, 15, 20, 30, 40, 60, 80, 100, 120, 150, 200, 300, 400, 500, 700, 900, 1200, 1500, 2000, 2500, 3000];
const cop = (n: number) => `$ ${Math.round(n).toLocaleString('es-CO')}`;

type Mode = 'GENERAL' | 'COMMERCE';
type Cycle = 'MONTHLY' | 'ANNUAL';

export interface PlanCost {
  plan: Plan;
  monthly: number;
  fits: boolean;
}

/** Costo mensual de cada plan de la familia para ese volumen y ciclo. Exportado para pruebas. */
export function planCosts(plans: Plan[], mode: Mode, docs: number, cycle: Cycle): PlanCost[] {
  return plans
    .filter((p) => p.family === mode && p.quotaDocuments)
    .map((p) => {
      const annualMonthly = p.annualPrice ? Number(p.annualPrice) / 12 : Number.POSITIVE_INFINITY;
      const monthly = cycle === 'MONTHLY' && p.monthlyBillingAllowed && p.monthlyPrice ? Number(p.monthlyPrice) : annualMonthly;
      return { plan: p, monthly, fits: (p.quotaDocuments ?? 0) >= docs };
    })
    .sort((a, b) => (a.plan.quotaDocuments ?? 0) - (b.plan.quotaDocuments ?? 0));
}

export function recommended(costs: PlanCost[]): PlanCost | undefined {
  return costs.filter((c) => c.fits).sort((a, b) => a.monthly - b.monthly)[0];
}

/**
 * "¿Cuánto me cuesta?" con los precios reales del catálogo. Chart.js se descarga solo cuando
 * esta sección se acerca a la pantalla.
 */
export function CostCalculator({ plans }: { plans: Plan[] }) {
  const [mode, setMode] = useState<Mode>('COMMERCE');
  const [step, setStep] = useState(STEPS.indexOf(400));
  const [cycle, setCycle] = useState<Cycle>('MONTHLY');
  const docs = STEPS[step] ?? 100;
  const costs = useMemo(() => planCosts(plans, mode, docs, cycle), [plans, mode, docs, cycle]);
  const best = recommended(costs);
  const perDoc = best ? best.monthly / docs : null;

  const wrapRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const chartRef = useRef<ChartType<'bar'> | null>(null);
  const [chartReady, setChartReady] = useState(false);

  // Carga diferida de Chart.js al acercarse la sección
  useEffect(() => {
    const wrap = wrapRef.current;
    const canvas = canvasRef.current;
    if (!wrap || !canvas) return;
    let cancelled = false;
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (!entry?.isIntersecting) return;
        observer.disconnect();
        void import('chart.js').then(({ Chart, BarController, BarElement, CategoryScale, LinearScale, Tooltip }) => {
          if (cancelled) return;
          Chart.register(BarController, BarElement, CategoryScale, LinearScale, Tooltip);
          const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
          chartRef.current = new Chart(canvas, {
            type: 'bar',
            data: { labels: [], datasets: [{ data: [], backgroundColor: [], borderRadius: 8, maxBarThickness: 56 }] },
            options: {
              responsive: true,
              maintainAspectRatio: false,
              animation: reduced ? false : { duration: 450, easing: 'easeOutQuart' },
              plugins: {
                legend: { display: false },
                tooltip: {
                  callbacks: {
                    label: (ctx) => `${cop(Number(ctx.raw))} al mes`,
                  },
                },
              },
              scales: {
                x: { grid: { display: false }, ticks: { color: '#34463e', font: { family: 'Public Sans Variable', size: 13, weight: 600 } } },
                y: {
                  beginAtZero: true,
                  grid: { color: '#e6ece8' },
                  border: { display: false },
                  ticks: { color: '#5a6b63', font: { family: 'Public Sans Variable', size: 12 }, callback: (v) => cop(Number(v)) },
                },
              },
            },
          });
          setChartReady(true);
        });
      },
      { rootMargin: '300px' },
    );
    observer.observe(wrap);
    return () => {
      cancelled = true;
      observer.disconnect();
      chartRef.current?.destroy();
      chartRef.current = null;
    };
  }, []);

  // Datos de la gráfica
  useEffect(() => {
    const chart = chartRef.current;
    if (!chart) return;
    chart.data.labels = costs.map((c) => c.plan.name);
    const dataset = chart.data.datasets[0]!;
    dataset.data = costs.map((c) => Math.round(c.monthly));
    dataset.backgroundColor = costs.map((c) => (c === best ? '#0b7a5c' : c.fits ? '#9fc9b8' : '#dfe6e2'));
    chart.update();
  }, [costs, best, chartReady]);

  const maxQuota = Math.max(0, ...costs.map((c) => c.plan.quotaDocuments ?? 0));

  return (
    <div className="calc" ref={wrapRef}>
      <div className="calc-controls">
        <div className="segmented" role="radiogroup" aria-label="Cómo vendes">
          <button type="button" role="radio" aria-checked={mode === 'COMMERCE'} onClick={() => setMode('COMMERCE')}>
            Vendo en caja (POS)
          </button>
          <button type="button" role="radio" aria-checked={mode === 'GENERAL'} onClick={() => setMode('GENERAL')}>
            Hago facturas
          </button>
        </div>
        <label className="calc-slider">
          <span>
            Documentos al mes: <strong className="num">{docs.toLocaleString('es-CO')}</strong>
          </span>
          <input type="range" min={0} max={STEPS.length - 1} step={1} value={step} onChange={(e) => setStep(Number(e.target.value))}
            aria-valuetext={`${docs} documentos al mes`} />
        </label>
        <div className="segmented" role="radiogroup" aria-label="Forma de pago">
          <button type="button" role="radio" aria-checked={cycle === 'MONTHLY'} onClick={() => setCycle('MONTHLY')}>
            Pago mensual
          </button>
          <button type="button" role="radio" aria-checked={cycle === 'ANNUAL'} onClick={() => setCycle('ANNUAL')}>
            Pago anual
          </button>
        </div>
      </div>

      <div className="calc-result" aria-live="polite">
        {best ? (
          <>
            <p className="calc-plan">{best.plan.name}</p>
            <p className="calc-price num">
              {cop(best.monthly)} <span>al mes{cycle === 'ANNUAL' || !best.plan.monthlyBillingAllowed ? ', pagando el año' : ''}</span>
            </p>
            <p className="calc-per num">{perDoc !== null && `${cop(perDoc)} por documento`}</p>
          </>
        ) : (
          <>
            <p className="calc-plan">Más de {maxQuota.toLocaleString('es-CO')} al mes</p>
            <p className="calc-per">
              {mode === 'GENERAL' ? 'Para ese volumen te conviene un plan de caja.' : 'Escríbenos y armamos un plan a tu medida.'}
            </p>
          </>
        )}
      </div>

      <div className="calc-chart">
        <canvas ref={canvasRef} role="img" aria-label="Costo mensual de cada plan; el recomendado está resaltado" />
      </div>
      <p className="small muted calc-note">Precios del catálogo vigente. Los documentos adicionales solo se cobran si los aceptas.</p>
    </div>
  );
}
