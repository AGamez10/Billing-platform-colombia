import { useEffect, useRef } from 'react';
import type { Chart as ChartType } from 'chart.js';
import { formatCop } from '../lib/format';

/**
 * Ventas por día del mes: una sola serie (sin leyenda: el título la nombra), barras delgadas
 * con extremo redondeado, cuadrícula tenue y descripción por día en un tooltip. Chart.js se
 * carga solo cuando hay ventas que mostrar. La tabla equivalente queda para lectores de pantalla.
 */
export function SalesChart({ daily }: { daily: { date: string; total: number }[] }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const chartRef = useRef<ChartType | null>(null);
  const labels = daily.map((d) => String(Number(d.date.slice(8, 10))));
  const dataKey = JSON.stringify(daily);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    let cancelled = false;
    void import('chart.js').then(({ Chart, BarController, BarElement, CategoryScale, LinearScale, Tooltip }) => {
      if (cancelled) return;
      Chart.register(BarController, BarElement, CategoryScale, LinearScale, Tooltip);
      const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      chartRef.current?.destroy();
      chartRef.current = new Chart(canvas, {
        type: 'bar',
        data: {
          labels,
          datasets: [
            {
              data: daily.map((d) => Number(d.total)),
              backgroundColor: '#0b7a5c',
              hoverBackgroundColor: '#075a44',
              borderRadius: { topLeft: 4, topRight: 4 },
              borderSkipped: 'bottom',
              maxBarThickness: 18,
              categoryPercentage: 0.8,
              barPercentage: 0.9,
            },
          ],
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          animation: reduced ? false : { duration: 350 },
          interaction: { mode: 'index', intersect: false },
          plugins: {
            legend: { display: false },
            tooltip: {
              displayColors: false,
              callbacks: {
                title: (items) => `Día ${items[0]?.label ?? ''}`,
                label: (ctx) => formatCop(Number(ctx.raw)),
              },
            },
          },
          scales: {
            x: { grid: { display: false }, border: { color: '#dde4de' }, ticks: { color: '#5a6b63', maxRotation: 0, autoSkipPadding: 8 } },
            y: {
              beginAtZero: true,
              grid: { color: '#eef2ef' },
              border: { display: false },
              ticks: { color: '#5a6b63', maxTicksLimit: 4, callback: (v) => formatCop(Number(v)) },
            },
          },
        },
      });
    });
    return () => {
      cancelled = true;
      chartRef.current?.destroy();
      chartRef.current = null;
    };
  }, [dataKey]);

  const best = daily.reduce((a, d) => (Number(d.total) > Number(a.total) ? d : a), daily[0] ?? { date: '', total: 0 });
  return (
    <figure className="sales-chart">
      <div className="chart-box">
        <canvas ref={canvasRef} role="img" aria-label={`Ventas por día del mes. Mejor día: ${best.date ? Number(best.date.slice(8, 10)) : '—'} con ${formatCop(Number(best.total))}.`} />
      </div>
      <table className="visually-hidden">
        <caption>Ventas por día</caption>
        <tbody>
          {daily.map((d) => (
            <tr key={d.date}>
              <th scope="row">{d.date}</th>
              <td>{formatCop(Number(d.total))}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </figure>
  );
}
