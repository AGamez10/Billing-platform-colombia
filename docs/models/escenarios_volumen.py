# Escenarios de volumen y compra de paquetes (2026-11-06). Uso: python3 docs/models/escenarios_volumen.py
# Fuentes: muisca.io/precios, muisca.io/precios/por-demanda, Mercado Pago link de pago. Supuestos en docs/PRICING.md.
import math, itertools, functools
PK=[("Pro",399900,5000),("Business Plus",674900,15000),("Executive",1599900,40000),("Enterprise",3590000,120000)]
TIERS=[(5000,90),(10000,80),(20000,65),(30000,55),(50000,45),(100000,35),(200000,33),(500000,31),(10**12,30)]
def ondemand(m):
    m=max(m,1000); cost=0; lo=0
    for hi,p in TIERS:
        if m>lo: cost+=(min(m,hi)-lo)*p
        lo=hi
    return cost
def best_prepaid(V):
    # min cost to cover V docs in 12 months, buying any combination (unused expire)
    best=None
    for counts in itertools.product(range(0,40),range(0,15),range(0,8),range(0,8)):
        docs=sum(c*p[2] for c,p in zip(counts,PK))
        if docs<V: continue
        cost=sum(c*p[1] for c,p in zip(counts,PK))
        if best is None or cost<best[0]: best=(cost,counts,docs)
    return best
print("== Costo por volumen mensual sostenido (12 meses) ==")
for m in [100,300,500,1000,2000,3000,5000,8000,10000,15000,20000,30000,50000,100000]:
    V=12*m; b=best_prepaid(V); od=ondemand(m)*12
    combo=", ".join(f"{c}x {p[0]}" for c,p in zip(b[1],PK) if c)
    print(f"{m:>7}/mes  prepago {b[0]:>12,} ({combo}; {b[0]/V:6.1f}/doc)   por demanda {od:>12,} ({od/V:6.1f}/doc)")

MP_PCT=0.0329*1.19; MP_FIXED=800  # Mercado Pago link de pago, liberación inmediata (+IVA); fijo $800 (conservador)
def net(price): return price*(1-MP_PCT)-MP_FIXED
SEG={ # nombre: (docs usados/mes, plan, precio mensual cobrado)
 "Profesional independiente":(8,"Micro (anual)",69000/12),
 "Fundación / ESAL":(15,"Emprendedor",12900),
 "Empresa de servicios":(60,"Negocio",29900),
 "Tienda / comercio pequeño":(400,"Comercio 500",34900),
 "Restaurante / cafetería":(1000,"Comercio 1.200",64900),
 "Minimercado":(2500,"Comercio 3.000",139900),
}
def net_micro(): return 69000*(1-MP_PCT)-MP_FIXED  # un cobro al año
print("\n== Capacidad de cada paquete (meses-cliente y clientes cubiertos 12 meses) ==")
for name,price,docs in PK:
    row=[f"{name} ({docs:,} docs, ${price:,})"]
    for s,(d,_,_) in SEG.items():
        row.append(f"{s}: {docs/d:,.0f} meses-cliente = {docs/(d*12):,.1f} clientes/año")
    print("\n  ".join(row))

print("\n== Margen por cliente según costo del documento ==")
for s,(d,plan,p) in SEG.items():
    n = net_micro()/12 if s.startswith("Profesional") else net(p)
    out=[f"{s} ({plan}, ${p:,.0f}/mes, usa {d}):"]
    for name,price,docs in PK:
        c=price/docs; m=(n-d*c)
        out.append(f"{name} ${c:.0f}/doc → aporte ${m:,.0f}/mes ({m/n*100:.0f}%)")
    print("  ".join(out))

SCEN={
 "A · Sin comercio":{"Profesional independiente":400,"Fundación / ESAL":300,"Empresa de servicios":300},
 "B · 20 % comercio":{"Profesional independiente":350,"Fundación / ESAL":250,"Empresa de servicios":200,"Tienda / comercio pequeño":140,"Restaurante / cafetería":50,"Minimercado":10},
 "C · 50 % comercio":{"Profesional independiente":200,"Fundación / ESAL":150,"Empresa de servicios":150,"Tienda / comercio pequeño":350,"Restaurante / cafetería":120,"Minimercado":30},
}
print("\n== Escenarios con 1.000 clientes (año completo, consumo estable) ==")
for k,mix in SCEN.items():
    assert sum(mix.values())==1000
    docs_m=sum(SEG[s][0]*n for s,n in mix.items())
    V=docs_m*12
    nEnt=math.ceil(V/120000)
    # cobertura: Enterprise en múltiplos (el más barato a este volumen)
    b=(nEnt*3590000,nEnt)
    rev=0; revnet=0
    for s,n in mix.items():
        d,plan,p=SEG[s]
        if s.startswith("Profesional"): rev+=69000*n; revnet+=net_micro()*n
        else: rev+=p*12*n; revnet+=net(p)*12*n
    gm=revnet-b[0]
    print(f"{k}: {docs_m:,} docs/mes = {V:,} docs/año → {nEnt} x Enterprise = ${b[0]:,} (${b[0]/V:.1f}/doc). Ingreso ${rev:,.0f}/año, neto MP ${revnet:,.0f}; margen bruto ${gm:,.0f} ({gm/revnet*100:.0f}%) ")

print("\n== Escalera de compras: escenario B, de 0 a 1.000 clientes en 12 meses (lineal), caja inicial $1.200.000 ==")
mix=SCEN["B · 20 % comercio"]
cash=1_200_000; balance=0; lots=[]  # (docs restantes, mes de vencimiento)
for month in range(1,13):
    clients=round(1000*month/12)
    f=clients/1000
    docs=sum(SEG[s][0]*n*f for s,n in mix.items())
    revnet=sum((net_micro()/12 if s.startswith("Profesional") else net(SEG[s][2]))*n*f for s,n in mix.items())
    # vencer lotes
    lots=[(d,exp) for d,exp in lots if exp>month]
    bought=[]
    need=docs
    while sum(d for d,_ in lots) < need:
        # regla: el paquete más grande que se alcance a consumir en 12 meses al ritmo actual
        proj=docs*12
        choice=PK[0]
        for p in PK:
            if p[2]<=proj: choice=p
        lots.append((choice[2],month+12)); cash-=choice[1]; bought.append(choice[0])
    # consumir FIFO por vencimiento
    rem=need; nl=[]
    for d,exp in sorted(lots,key=lambda x:x[1]):
        use=min(d,rem); rem-=use
        if d-use>0: nl.append((d-use,exp))
    lots=nl
    cash+=revnet
    print(f"mes {month:>2}: {clients:>4} clientes, {docs:>9,.0f} docs, compra {', '.join(bought) or '—':<38} caja fin de mes ${cash:>13,.0f}")
