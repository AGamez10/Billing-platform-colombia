import { useId } from 'react';
import type { FiscalProfile, PersonType, VatRegime } from '../api/types';
import { PERSON_TYPE_LABEL, RESPONSIBILITIES, toggleResponsibility, VAT_REGIME_LABEL } from '../lib/fiscal';

interface Props {
  value: FiscalProfile;
  onChange: (value: FiscalProfile) => void;
  disabled?: boolean;
  /** Texto de ayuda según quién es la parte (tu empresa o tu cliente). */
  whose: 'own' | 'customer';
}

/** Tipo de persona, régimen frente al IVA y responsabilidades del RUT (casilla 53). */
export function FiscalFields({ value, onChange, disabled, whose }: Props) {
  const id = useId();
  const set = (patch: Partial<FiscalProfile>) => onChange({ ...value, ...patch });
  return (
    <div className="fiscal stack tight-stack">
      <fieldset className="fieldset" disabled={disabled}>
        <legend>Tipo de persona</legend>
        <div className="segmented">
          {(Object.keys(PERSON_TYPE_LABEL) as PersonType[]).map((p) => (
            <label key={p} className={value.personType === p ? 'on' : undefined}>
              <input type="radio" name={`${id}-person`} checked={value.personType === p} onChange={() => set({ personType: p })} />
              {PERSON_TYPE_LABEL[p]}
            </label>
          ))}
        </div>
      </fieldset>
      <fieldset className="fieldset" disabled={disabled}>
        <legend>Régimen frente al IVA</legend>
        <div className="segmented">
          {(Object.keys(VAT_REGIME_LABEL) as VatRegime[]).map((r) => (
            <label key={r} className={value.vatRegime === r ? 'on' : undefined}>
              <input type="radio" name={`${id}-regime`} checked={value.vatRegime === r} onChange={() => set({ vatRegime: r })} />
              {VAT_REGIME_LABEL[r]}
            </label>
          ))}
        </div>
      </fieldset>
      <fieldset className="fieldset" disabled={disabled}>
        <legend>Responsabilidades fiscales</legend>
        <p className="small muted">
          {whose === 'own'
            ? 'Están en la casilla 53 de tu RUT. Marca solo las que aparecen allí.'
            : 'Están en la casilla 53 del RUT de tu cliente. Si no tiene RUT o no tiene ninguna de estas, deja la última.'}
        </p>
        <div className="check-list">
          {RESPONSIBILITIES.map((r) => (
            <label key={r.code} className="consent">
              <input
                type="checkbox"
                checked={value.responsibilities.includes(r.code)}
                onChange={() => set({ responsibilities: toggleResponsibility(value.responsibilities, r.code) })}
              />
              <span>
                <strong>{r.code}</strong> {r.label}
              </span>
            </label>
          ))}
        </div>
      </fieldset>
    </div>
  );
}
