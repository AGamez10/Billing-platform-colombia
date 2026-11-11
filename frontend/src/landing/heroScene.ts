import * as THREE from 'three';
import { encode } from 'uqr';

/**
 * Escena 3D del inicio: un celular con la caja en vivo (venta → QR → pago confirmado) y una
 * impresora flotante de la que sale el tiquete POS con el sello "Emitido". Es la promesa del
 * producto contada en 10 segundos.
 *
 * Presupuesto: sin sombras dinámicas ni postprocesado, texturas de lienzo pequeñas que solo se
 * redibujan al cambiar de estado, píxeles limitados, y el bucle se detiene fuera de pantalla o con
 * la pestaña oculta. Con movimiento reducido se dibuja un único cuadro final.
 */

export interface HeroSceneOptions {
  reducedMotion: boolean;
}

export interface HeroSceneHandle {
  dispose(): void;
}

const LOOP = 10; // segundos
const GREEN = '#0b7a5c';
const GREEN_BRIGHT = '#43d39b';
const INK = '#14211c';
const MUTED = '#5a6b63';
const FONT = "'Public Sans Variable', 'Public Sans', system-ui, sans-serif";
const DISPLAY = "'Bricolage Grotesque Variable', 'Bricolage Grotesque', system-ui, sans-serif";

const ITEMS = [
  { name: '2 × Gaseosa 400 ml', price: '$ 5.000', at: 0.5 },
  { name: '1 × Pan aliñado', price: '$ 3.500', at: 1.2 },
  { name: '1 × Leche 1 L', price: '$ 4.200', at: 1.9 },
];
const TOTALS = ['$ 0', '$ 5.000', '$ 8.500', '$ 12.700'];

type Stage = { key: string; items: number; screen: 'cart' | 'qr' | 'paid'; spin: number };

function stageAt(t: number): Stage {
  const items = ITEMS.filter((i) => t >= i.at).length;
  if (t < 3.1) return { key: `cart-${items}`, items, screen: 'cart', spin: 0 };
  if (t < 5.6) {
    const spin = Math.floor(t * 8) % 8; // el giro de "esperando" a ~8 cuadros por segundo
    return { key: `qr-${spin}`, items, screen: 'qr', spin };
  }
  return { key: 'paid', items, screen: 'paid', spin: 0 };
}

function roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

// ------------------------------------------------------------------ pantalla del celular

const SCREEN_W = 480;
const SCREEN_H = 1000;
const QR_MATRIX = encode('https://checkout.ejemplo.co/demo', { ecc: 'M', border: 0 });

function drawScreen(ctx: CanvasRenderingContext2D, s: Stage) {
  const W = SCREEN_W;
  ctx.clearRect(0, 0, W, SCREEN_H);
  ctx.save();
  roundRect(ctx, 0, 0, W, SCREEN_H, 54);
  ctx.clip();
  ctx.fillStyle = '#f5f7f4';
  ctx.fillRect(0, 0, W, SCREEN_H);

  // barra superior
  ctx.fillStyle = '#12261f';
  ctx.fillRect(0, 0, W, 150);
  ctx.fillStyle = GREEN_BRIGHT;
  roundRect(ctx, 36, 70, 40, 40, 10);
  ctx.fill();
  ctx.fillStyle = '#fff';
  ctx.font = `700 30px ${DISPLAY}`;
  ctx.fillText('Vender', 92, 101);
  ctx.font = `500 22px ${FONT}`;
  ctx.fillStyle = '#b9cbc2';
  ctx.textAlign = 'right';
  ctx.fillText('Caja 1', W - 36, 101);
  ctx.textAlign = 'left';

  if (s.screen === 'cart') {
    ctx.fillStyle = INK;
    ctx.font = `700 26px ${FONT}`;
    ctx.fillText('Venta actual', 36, 210);
    ITEMS.slice(0, s.items).forEach((item, i) => {
      const y = 250 + i * 92;
      ctx.fillStyle = '#fff';
      roundRect(ctx, 28, y, W - 56, 76, 16);
      ctx.fill();
      ctx.fillStyle = INK;
      ctx.font = `500 24px ${FONT}`;
      ctx.fillText(item.name, 50, y + 47);
      ctx.textAlign = 'right';
      ctx.font = `700 24px ${FONT}`;
      ctx.fillText(item.price, W - 50, y + 47);
      ctx.textAlign = 'left';
    });
    ctx.fillStyle = MUTED;
    ctx.font = `500 24px ${FONT}`;
    ctx.fillText('Total', 36, 640);
    ctx.fillStyle = INK;
    ctx.font = `800 72px ${DISPLAY}`;
    ctx.fillText(TOTALS[s.items] ?? '$ 0', 36, 720);
    ctx.fillStyle = s.items === ITEMS.length ? GREEN : '#9fbfb3';
    roundRect(ctx, 28, 820, W - 56, 104, 22);
    ctx.fill();
    ctx.fillStyle = '#fff';
    ctx.font = `700 30px ${FONT}`;
    ctx.textAlign = 'center';
    ctx.fillText('Cobrar con QR', W / 2, 884);
    ctx.textAlign = 'left';
  } else if (s.screen === 'qr') {
    ctx.fillStyle = INK;
    ctx.textAlign = 'center';
    ctx.font = `800 64px ${DISPLAY}`;
    ctx.fillText('$ 12.700', W / 2, 250);
    const size = 300;
    const x0 = (W - size) / 2;
    const y0 = 300;
    ctx.fillStyle = '#fff';
    roundRect(ctx, x0 - 22, y0 - 22, size + 44, size + 44, 24);
    ctx.fill();
    const cell = size / QR_MATRIX.size;
    ctx.fillStyle = '#000';
    QR_MATRIX.data.forEach((row, y) =>
      row.forEach((dark, x) => {
        if (dark) ctx.fillRect(x0 + x * cell, y0 + y * cell, Math.ceil(cell), Math.ceil(cell));
      }),
    );
    ctx.fillStyle = MUTED;
    ctx.font = `500 26px ${FONT}`;
    ctx.fillText('Esperando el pago…', W / 2, 720);
    // indicador de espera
    ctx.strokeStyle = GREEN;
    ctx.lineWidth = 8;
    ctx.lineCap = 'round';
    ctx.beginPath();
    const a = (s.spin / 8) * Math.PI * 2;
    ctx.arc(W / 2, 800, 28, a, a + Math.PI * 1.3);
    ctx.stroke();
    ctx.textAlign = 'left';
  } else {
    ctx.textAlign = 'center';
    ctx.fillStyle = GREEN;
    ctx.beginPath();
    ctx.arc(W / 2, 360, 96, 0, Math.PI * 2);
    ctx.fill();
    ctx.strokeStyle = '#fff';
    ctx.lineWidth = 16;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.beginPath();
    ctx.moveTo(W / 2 - 42, 362);
    ctx.lineTo(W / 2 - 10, 394);
    ctx.lineTo(W / 2 + 46, 330);
    ctx.stroke();
    ctx.fillStyle = INK;
    ctx.font = `800 48px ${DISPLAY}`;
    ctx.fillText('Pago recibido', W / 2, 540);
    ctx.fillStyle = MUTED;
    ctx.font = `500 26px ${FONT}`;
    ctx.fillText('$ 12.700 por Bre-B', W / 2, 590);
    ctx.fillStyle = '#e2f4ec';
    roundRect(ctx, 40, 680, W - 80, 120, 22);
    ctx.fill();
    ctx.fillStyle = GREEN;
    ctx.font = `700 26px ${FONT}`;
    ctx.fillText('Tiquete POS emitido solo', W / 2, 735);
    ctx.font = `500 22px ${FONT}`;
    ctx.fillText('CAJA 1043', W / 2, 772);
    ctx.textAlign = 'left';
  }
  ctx.restore();
}

// ------------------------------------------------------------------ tiquete

const RECEIPT_W = 460;
const RECEIPT_H = 820;

function drawReceipt(ctx: CanvasRenderingContext2D, stamped: boolean) {
  const W = RECEIPT_W;
  ctx.clearRect(0, 0, W, RECEIPT_H);
  ctx.fillStyle = '#fffdf8';
  // borde inferior dentado del papel
  ctx.beginPath();
  ctx.moveTo(0, 0);
  ctx.lineTo(W, 0);
  ctx.lineTo(W, RECEIPT_H - 16);
  const teeth = 18;
  for (let i = teeth; i >= 0; i--) {
    const x = (W / teeth) * i;
    ctx.lineTo(x, i % 2 === 0 ? RECEIPT_H - 16 : RECEIPT_H);
  }
  ctx.closePath();
  ctx.fill();

  ctx.fillStyle = INK;
  ctx.font = `800 40px ${DISPLAY}`;
  ctx.fillText('Tienda Doña Rosa', 34, 80);
  ctx.fillStyle = MUTED;
  ctx.font = `500 20px ${FONT}`;
  ctx.fillText('NIT 900.123.456-8 · Bogotá', 34, 114);
  ctx.fillText('POS electrónico CAJA 1043', 34, 142);

  const dash = (y: number) => {
    ctx.strokeStyle = '#c8d2cc';
    ctx.setLineDash([8, 8]);
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(34, y);
    ctx.lineTo(W - 34, y);
    ctx.stroke();
    ctx.setLineDash([]);
  };
  dash(176);
  ctx.font = `500 24px ${FONT}`;
  ITEMS.forEach((item, i) => {
    const y = 230 + i * 48;
    ctx.fillStyle = INK;
    ctx.fillText(item.name, 34, y);
    ctx.textAlign = 'right';
    ctx.fillText(item.price, W - 34, y);
    ctx.textAlign = 'left';
  });
  dash(370);
  ctx.font = `800 40px ${DISPLAY}`;
  ctx.fillText('Total', 34, 430);
  ctx.textAlign = 'right';
  ctx.fillText('$ 12.700', W - 34, 430);
  ctx.textAlign = 'left';
  ctx.fillStyle = GREEN;
  ctx.font = `600 24px ${FONT}`;
  ctx.fillText('Pagado con Bre-B', 34, 480);
  ctx.fillStyle = MUTED;
  ctx.font = `500 18px ${FONT}`;
  ctx.fillText('CUDE 8f3a…c91e', 34, 530);

  if (stamped) {
    ctx.save();
    ctx.translate(W - 150, 640);
    ctx.rotate(-0.14);
    ctx.strokeStyle = GREEN;
    ctx.lineWidth = 6;
    roundRect(ctx, -120, -44, 240, 88, 16);
    ctx.stroke();
    ctx.fillStyle = GREEN;
    ctx.font = `800 40px ${DISPLAY}`;
    ctx.textAlign = 'center';
    ctx.fillText('Emitido', 0, 14);
    ctx.restore();
  }
}

// ------------------------------------------------------------------ escena

function phoneShape(w: number, h: number, r: number): THREE.Shape {
  const s = new THREE.Shape();
  const x = -w / 2;
  const y = -h / 2;
  s.moveTo(x + r, y);
  s.lineTo(x + w - r, y);
  s.quadraticCurveTo(x + w, y, x + w, y + r);
  s.lineTo(x + w, y + h - r);
  s.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
  s.lineTo(x + r, y + h);
  s.quadraticCurveTo(x, y + h, x, y + h - r);
  s.lineTo(x, y + r);
  s.quadraticCurveTo(x, y, x + r, y);
  return s;
}

function canvasTexture(w: number, h: number): { canvas: HTMLCanvasElement; ctx: CanvasRenderingContext2D; texture: THREE.CanvasTexture } {
  const canvas = document.createElement('canvas');
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('2d context unavailable');
  const texture = new THREE.CanvasTexture(canvas);
  texture.colorSpace = THREE.SRGBColorSpace;
  texture.anisotropy = 4;
  return { canvas, ctx, texture };
}

/** Monta la escena en el lienzo. Lanza un error si no hay WebGL (quien llama muestra la versión estática). */
export async function mountHeroScene(canvas: HTMLCanvasElement, options: HeroSceneOptions): Promise<HeroSceneHandle> {
  try {
    await document.fonts.ready;
  } catch {
    // sin la API de fuentes se dibuja con la del sistema
  }

  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true, powerPreference: 'low-power' });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.75));
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.localClippingEnabled = true;

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(30, 1, 0.1, 50);
  camera.position.set(0, 0.1, 9);

  scene.add(new THREE.HemisphereLight(0xffffff, 0x0d1f19, 1.4));
  const key = new THREE.DirectionalLight(0xffffff, 2.2);
  key.position.set(-3, 5, 6);
  scene.add(key);
  const rim = new THREE.PointLight(0x43d39b, 30, 12, 2);
  rim.position.set(2.5, 1.5, -2);
  scene.add(rim);

  // Celular
  const phone = new THREE.Group();
  const PW = 1.8;
  const PH = 3.7;
  const body = new THREE.Mesh(
    new THREE.ExtrudeGeometry(phoneShape(PW, PH, 0.28), { depth: 0.14, bevelEnabled: true, bevelThickness: 0.04, bevelSize: 0.04, bevelSegments: 4, curveSegments: 12 }),
    new THREE.MeshStandardMaterial({ color: 0x0f1b17, metalness: 0.55, roughness: 0.32 }),
  );
  body.position.z = -0.14;
  phone.add(body);
  const screenTex = canvasTexture(SCREEN_W, SCREEN_H);
  const screen = new THREE.Mesh(
    new THREE.PlaneGeometry(PW - 0.14, (PW - 0.14) * (SCREEN_H / SCREEN_W)),
    new THREE.MeshBasicMaterial({ map: screenTex.texture, transparent: true, toneMapped: false }),
  );
  screen.position.z = 0.045;
  phone.add(screen);
  scene.add(phone);

  // Impresora y tiquete
  const printer = new THREE.Group();
  const slot = new THREE.Mesh(
    new THREE.ExtrudeGeometry(phoneShape(1.6, 0.26, 0.12), { depth: 0.3, bevelEnabled: true, bevelThickness: 0.03, bevelSize: 0.03, bevelSegments: 3 }),
    new THREE.MeshStandardMaterial({ color: 0x1d3a30, metalness: 0.4, roughness: 0.45 }),
  );
  slot.position.z = -0.15;
  printer.add(slot);
  const slit = new THREE.Mesh(new THREE.PlaneGeometry(1.36, 0.04), new THREE.MeshBasicMaterial({ color: 0x050b09 }));
  slit.position.set(0, -0.06, 0.19);
  printer.add(slit);

  const receiptTex = canvasTexture(RECEIPT_W, RECEIPT_H);
  const RW = 1.3;
  const RH = RW * (RECEIPT_H / RECEIPT_W);
  const receiptGeometry = new THREE.PlaneGeometry(RW, RH, 1, 24);
  // leve curva del papel, como recién salido de la impresora
  const pos = receiptGeometry.attributes.position as THREE.BufferAttribute;
  for (let i = 0; i < pos.count; i++) {
    const y = pos.getY(i);
    const fromTop = (RH / 2 - y) / RH;
    pos.setZ(i, 0.22 * fromTop * fromTop);
  }
  receiptGeometry.computeVertexNormals();
  const clip = new THREE.Plane(new THREE.Vector3(0, -1, 0), 0);
  const receiptMaterial = new THREE.MeshStandardMaterial({
    map: receiptTex.texture, roughness: 0.85, metalness: 0, side: THREE.DoubleSide, transparent: true, clippingPlanes: [clip],
  });
  const receipt = new THREE.Mesh(receiptGeometry, receiptMaterial);
  printer.add(receipt);
  scene.add(printer);

  // Sombra suave bajo el celular (textura radial, sin luces de sombra)
  const shadowTex = canvasTexture(128, 128);
  const g = shadowTex.ctx.createRadialGradient(64, 64, 4, 64, 64, 64);
  g.addColorStop(0, 'rgba(0,0,0,0.45)');
  g.addColorStop(1, 'rgba(0,0,0,0)');
  shadowTex.ctx.fillStyle = g;
  shadowTex.ctx.fillRect(0, 0, 128, 128);
  shadowTex.texture.needsUpdate = true;
  const shadow = new THREE.Mesh(new THREE.PlaneGeometry(2.6, 0.7), new THREE.MeshBasicMaterial({ map: shadowTex.texture, transparent: true, depthWrite: false }));
  shadow.rotation.x = -Math.PI / 2;
  scene.add(shadow);

  // Disposición según el ancho
  let slotTopY = 1.4;
  function layout() {
    const rect = canvas.getBoundingClientRect();
    const w = Math.max(1, rect.width);
    const h = Math.max(1, rect.height);
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    const narrow = camera.aspect < 1;
    camera.position.z = narrow ? 11.5 : 9;
    camera.updateProjectionMatrix();
    phone.position.set(narrow ? -0.75 : -1.05, -0.05, 0);
    printer.position.set(narrow ? 1.05 : 1.35, 1.55, 0.6);
    printer.rotation.set(0, -0.28, 0.04);
    shadow.position.set(phone.position.x, -2.35, 0);
    slotTopY = printer.position.y - 0.06;
  }
  layout();

  // Puntero: inclinación suave hacia donde está el mouse
  const target = { x: 0, y: 0 };
  const current = { x: 0, y: 0 };
  function onPointer(e: PointerEvent) {
    target.x = (e.clientX / window.innerWidth) * 2 - 1;
    target.y = (e.clientY / window.innerHeight) * 2 - 1;
  }
  if (!options.reducedMotion) window.addEventListener('pointermove', onPointer, { passive: true });

  let lastKey = '';
  let lastElapsed = 8.8;
  let lastStamped: boolean | null = null;

  function frame(elapsed: number) {
    lastElapsed = elapsed;
    const t = elapsed % LOOP;
    const s = stageAt(t);
    if (s.key !== lastKey) {
      drawScreen(screenTex.ctx, s);
      screenTex.texture.needsUpdate = true;
      lastKey = s.key;
    }
    const stamped = t >= 8.2;
    if (stamped !== lastStamped) {
      drawReceipt(receiptTex.ctx, stamped);
      receiptTex.texture.needsUpdate = true;
      lastStamped = stamped;
    }

    // impresión: el papel baja desde la ranura entre 6.0 s y 8.0 s
    const p = THREE.MathUtils.clamp((t - 6) / 2, 0, 1);
    const eased = 1 - Math.pow(1 - p, 3);
    receipt.position.y = -0.06 + RH / 2 - eased * RH;
    receipt.position.z = 0.12;
    clip.constant = slotTopY - 0.03;
    receiptMaterial.opacity = t > 9.5 ? Math.max(0, 1 - (t - 9.5) / 0.5) : 1;

    // flotación e inclinación
    current.x += (target.x - current.x) * 0.06;
    current.y += (target.y - current.y) * 0.06;
    const float = options.reducedMotion ? 0 : Math.sin(elapsed * 0.9) * 0.07;
    phone.rotation.set(0.06 + current.y * 0.12, 0.38 + current.x * 0.22, -0.04);
    phone.position.y = -0.05 + float;
    printer.rotation.y = -0.28 + current.x * 0.12;
    shadow.scale.setScalar(1 - float * 0.8);

    renderer.render(scene, camera);
  }

  // Bucle solo mientras se ve
  let raf = 0;
  let running = false;
  let visible = true;
  const clock = new THREE.Clock(false);
  let offset = 0;
  function loop() {
    frame(offset + clock.getElapsedTime());
    raf = requestAnimationFrame(loop);
  }
  function start() {
    if (running || options.reducedMotion) return;
    running = true;
    clock.start();
    raf = requestAnimationFrame(loop);
  }
  function stop() {
    if (!running) return;
    running = false;
    offset += clock.getElapsedTime();
    clock.stop();
    cancelAnimationFrame(raf);
  }
  // Al cambiar el tamaño el lienzo se borra: se vuelve a dibujar aunque el bucle esté quieto.
  const resizeObserver = new ResizeObserver(() => {
    layout();
    if (!running) frame(lastElapsed);
  });
  resizeObserver.observe(canvas);

  const intersection = new IntersectionObserver(([entry]) => {
    visible = !!entry?.isIntersecting;
    if (visible && !document.hidden) start();
    else stop();
  });
  intersection.observe(canvas);
  function onVisibility() {
    if (document.hidden) stop();
    else if (visible) start();
  }
  document.addEventListener('visibilitychange', onVisibility);

  if (options.reducedMotion) {
    frame(8.8); // un cuadro fijo: pago recibido y tiquete emitido
  } else {
    start();
  }

  return {
    dispose() {
      stop();
      intersection.disconnect();
      resizeObserver.disconnect();
      window.removeEventListener('pointermove', onPointer);
      document.removeEventListener('visibilitychange', onVisibility);
      scene.traverse((o) => {
        if (o instanceof THREE.Mesh) {
          o.geometry.dispose();
          const materials = Array.isArray(o.material) ? o.material : [o.material];
          materials.forEach((m) => {
            (m as THREE.MeshStandardMaterial).map?.dispose();
            m.dispose();
          });
        }
      });
      renderer.dispose();
    },
  };
}
