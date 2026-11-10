import { useState, type FormEvent } from 'react';
import { http } from '../api/client';
import type { Role, Team as TeamData } from '../api/types';
import { useSession } from '../auth/session';
import { Alert, Field, Loading, PageHead } from '../components/ui';
import { messageFor } from '../lib/errors';
import { formatDate, ROLE_DESCRIPTION, ROLE_LABEL } from '../lib/format';
import { useApi } from '../lib/useApi';

const INVITABLE: Role[] = ['BILLER', 'VIEWER', 'ADMIN'];

export function Team() {
  const { can, state } = useSession();
  const team = useApi<TeamData>(can('manage') ? '/api/v1/team' : null);
  const [email, setEmail] = useState('');
  const [role, setRole] = useState<Role>('BILLER');
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const myRole = state.status === 'signed-in' ? state.me.role : 'VIEWER';

  if (!can('manage')) {
    return (
      <>
        <PageHead title="Equipo" />
        <Alert tone="info">Solo el dueño o un administrador pueden invitar personas y cambiar permisos.</Alert>
      </>
    );
  }
  if (team.loading && !team.data) return <Loading />;

  async function run(key: string, action: () => Promise<unknown>, done: string) {
    setBusy(key);
    setError(null);
    setNotice(null);
    try {
      await action();
      setNotice(done);
      await team.reload();
    } catch (err) {
      setError(messageFor(err));
    } finally {
      setBusy(null);
    }
  }

  function invite(e: FormEvent) {
    e.preventDefault();
    const address = email.trim();
    void run('invite', () => http.post('/api/v1/team/invitations', { email: address, role }),
      `Enviamos la invitación a ${address}. Tiene 7 días para aceptarla.`).then(() => setEmail(''));
  }

  const data = team.data;
  const seats = data?.maxUsers ? `${data.members.length + data.invitations.length} de ${data.maxUsers} usuarios` : null;
  const grantable = INVITABLE.filter((r) => myRole === 'OWNER' || r !== 'ADMIN' || myRole === 'ADMIN');

  return (
    <>
      <PageHead title="Equipo" subtitle="Quién entra a tu cuenta y qué puede hacer. Cada persona usa su propio correo y contraseña." />
      {error && <Alert tone="error">{error}</Alert>}
      {notice && <Alert tone="ok">{notice}</Alert>}

      <section className="card stack" aria-labelledby="invite-h">
        <h2 id="invite-h">Invitar a alguien</h2>
        <form className="stack" onSubmit={invite} noValidate>
          <Field label="Correo" type="email" autoComplete="off" required value={email} onChange={(e) => setEmail(e.target.value)}
            hint="Le llega un enlace para crear su contraseña. Si ya tiene cuenta, entra con la suya." />
          <fieldset>
            <legend>Qué puede hacer</legend>
            <div className="role-options">
              {grantable.map((r) => (
                <label key={r} className="role-option">
                  <input type="radio" name="role" value={r} checked={role === r} onChange={() => setRole(r)} />
                  <span>
                    <strong>{ROLE_LABEL[r]}</strong>
                    <span className="small muted">{ROLE_DESCRIPTION[r]}</span>
                  </span>
                </label>
              ))}
            </div>
          </fieldset>
          <div>
            <button type="submit" className="btn btn-primary" disabled={busy !== null || !email.includes('@')}>
              {busy === 'invite' ? 'Enviando…' : 'Enviar invitación'}
            </button>
          </div>
        </form>
      </section>

      <section className="card tight" aria-labelledby="members-h">
        <div className="row card-head">
          <h2 id="members-h">Personas con acceso</h2>
          {seats && <span className="right small muted">{seats}</span>}
        </div>
        <ul className="list">
          {data?.members.map((m) => {
            const editable = !m.you && m.role !== 'OWNER' && (myRole === 'OWNER' || m.role !== 'ADMIN');
            return (
              <li key={m.userId} className="list-item member">
                <span className="avatar" aria-hidden="true">{initials(m.fullName)}</span>
                <div className="grow">
                  <div className="title">
                    {m.fullName} {m.you && <span className="muted">(tú)</span>}
                  </div>
                  <div className="sub">
                    {m.email} · desde {formatDate(m.since)}
                  </div>
                </div>
                {editable ? (
                  <div className="row member-actions">
                    <label className="visually-hidden" htmlFor={`role-${m.userId}`}>Rol de {m.fullName}</label>
                    <select id={`role-${m.userId}`} value={m.role} disabled={busy !== null}
                      onChange={(e) => void run(m.userId, () => http.patch(`/api/v1/team/members/${m.userId}`, { role: e.target.value }),
                        `${m.fullName} ahora es ${ROLE_LABEL[e.target.value as Role].toLowerCase()}. El cambio aplica en su próximo ingreso o en minutos.`)}>
                      {grantable.map((r) => (
                        <option key={r} value={r}>{ROLE_LABEL[r]}</option>
                      ))}
                    </select>
                    <button type="button" className="btn btn-danger" disabled={busy !== null}
                      onClick={() => {
                        if (window.confirm(`¿Quitarle el acceso a ${m.fullName}? Podrás invitarle de nuevo cuando quieras.`)) {
                          void run(m.userId, () => http.del(`/api/v1/team/members/${m.userId}`), `${m.fullName} ya no tiene acceso.`);
                        }
                      }}>
                      Quitar acceso
                    </button>
                  </div>
                ) : (
                  <span className="badge badge-neutral">{ROLE_LABEL[m.role]}</span>
                )}
              </li>
            );
          })}
        </ul>
      </section>

      {data && data.invitations.length > 0 && (
        <section className="card tight" aria-labelledby="pending-h">
          <div className="row card-head">
            <h2 id="pending-h">Invitaciones sin aceptar</h2>
          </div>
          <ul className="list">
            {data.invitations.map((i) => (
              <li key={i.id} className="list-item">
                <div className="grow">
                  <div className="title">{i.email}</div>
                  <div className="sub">
                    {ROLE_LABEL[i.role]} · vence el {formatDate(i.expiresAt)}
                  </div>
                </div>
                <div className="row">
                  <button type="button" className="btn btn-secondary" disabled={busy !== null}
                    onClick={() => void run(i.id, () => http.post('/api/v1/team/invitations', { email: i.email, role: i.role }),
                      `Reenviamos la invitación a ${i.email}. El enlace anterior ya no sirve.`)}>
                    Reenviar
                  </button>
                  <button type="button" className="btn btn-ghost" disabled={busy !== null}
                    onClick={() => void run(i.id, () => http.del(`/api/v1/team/invitations/${i.id}`), `Anulamos la invitación a ${i.email}.`)}>
                    Anular
                  </button>
                </div>
              </li>
            ))}
          </ul>
        </section>
      )}
    </>
  );
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  return ((parts[0]?.[0] ?? '') + (parts.length > 1 ? parts[parts.length - 1]![0] : '')).toUpperCase() || '·';
}
