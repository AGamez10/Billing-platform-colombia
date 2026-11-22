import { render, screen } from '@testing-library/react';
import { useId, useState } from 'react';
import { dropPrerender, PRERENDER_ID } from '../landing/prerenderSwap';

function Search() {
  const id = useId();
  const [q, setQ] = useState('');
  return (
    <div>
      <label htmlFor={id}>¿Dónde está tu negocio?</label>
      <input id={id} value={q} onChange={(e) => setQ(e.target.value)} />
      <p>Buscando: {q}</p>
    </div>
  );
}

describe('cambio del HTML prerenderizado a React', () => {
  afterEach(() => document.getElementById(PRERENDER_ID)?.remove());

  it('conserva lo que la persona escribió antes de que cargara JavaScript, y el foco', () => {
    const pre = document.createElement('div');
    pre.id = PRERENDER_ID;
    pre.innerHTML = '<label for="R1">¿Dónde está tu negocio?</label><input id="R1" value="">';
    document.body.appendChild(pre);
    const staticInput = pre.querySelector('input')!;
    staticInput.focus();
    staticInput.value = 'cienaga';

    const root = document.createElement('div');
    root.id = 'root';
    document.body.appendChild(root);
    render(<Search />, { container: root });

    dropPrerender();

    expect(document.getElementById(PRERENDER_ID)).toBeNull();
    expect(screen.getByLabelText('¿Dónde está tu negocio?')).toHaveValue('cienaga');
    expect(screen.getByText('Buscando: cienaga')).toBeInTheDocument();
    expect(screen.getByLabelText('¿Dónde está tu negocio?')).toHaveFocus();
    root.remove();
  });
});
