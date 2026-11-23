# pagos-webhooks-idempotente

Servicio que recibe los webhooks de eventos de pago de **PayGate** y mantiene el estado consolidado de cada pago.
Está diseñado para tolerar lo que PayGate hace en producción: **reintentos** con el mismo `event_id`, entregas
**fuera de orden** y eventos del mismo pago **en paralelo**, sin dejar pagos en estados inválidos ni acreditar dos
veces.

Java 17 · Spring Boot 3 · JDBC · Flyway · PostgreSQL (H2 en memoria para las pruebas) · Actuator/Micrometer.

## Ejecutarlo

Con Docker (levanta PostgreSQL y el servicio):

```bash
docker compose up --build
```

O local, con Java 17 y Maven, contra un PostgreSQL propio:

```bash
docker compose up -d db
mvn spring-boot:run
```

Variables de entorno opcionales: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `DB_POOL_SIZE` (20 por defecto). Flyway crea
el esquema al arrancar. El contrato completo está en [`openapi.yaml`](openapi.yaml).

```bash
curl -X POST localhost:8080/webhooks/paygate -H 'Content-Type: application/json' -d '{
  "event_id": "7c1f0a52-0d1a-4a52-9a3e-1b0c9f3b0001",
  "payment_id": "a8d3f9b2-6a0e-4e53-8c11-5b6a7e2d0002",
  "status": "AUTHORIZED", "amount": 1500.00, "currency": "ARS",
  "timestamp": "2023-10-25T14:30:00Z" }'

curl localhost:8080/payments/a8d3f9b2-6a0e-4e53-8c11-5b6a7e2d0002
curl localhost:8080/payments/a8d3f9b2-6a0e-4e53-8c11-5b6a7e2d0002/events
curl localhost:8080/actuator/health
curl localhost:8080/actuator/metrics/paygate.webhooks
```

## Probarlo

```bash
mvn test
```

No requiere Docker: las pruebas usan H2 en modo PostgreSQL con las mismas migraciones. Son 17 pruebas:

| Clase | Qué demuestra |
|---|---|
| `WebhookFlowTest` | Alta del pago, **duplicados y reintentos**, evento viejo que llega tarde, estados terminales, pagos independientes, payloads inválidos (400) y pagos inexistentes (404). |
| `ConvergenceTest` | **El orden de llegada no importa**: las 24 permutaciones del ciclo `PENDING → AUTHORIZED → CAPTURED → REFUNDED` y las 6 del camino `… → FAILED` terminan en el mismo estado, con todo el historial registrado. Además, la `version` solo sube cuando el estado cambia. |
| `ConcurrencyTest` | 12 hilos con eventos del mismo pago desordenados y repetidos; 8 hilos con el mismo `event_id` (se aplica una sola vez); 30 pagos distintos en paralelo. |

## API

| Método | Ruta | Respuesta |
|---|---|---|
| `POST` | `/webhooks/paygate` | **200** `{event_id, outcome}` con `APPLIED`, `IGNORED` o `DUPLICATE`; **400** si el payload es inválido |
| `GET` | `/payments/{id}` | Estado actual, monto, moneda, `version` y cantidad de eventos; **404** si no existe |
| `GET` | `/payments/{id}/events` | Historial cronológico (por `timestamp` del evento) con el `outcome` de cada uno |

Se responde **200 también ante duplicados y eventos ignorados**: PayGate reintenta (hasta 5 veces, con backoff
exponencial) mientras no reciba un 2xx en menos de 2 segundos, y un evento ya procesado no debe seguir
reintentándose ni volver a aplicarse.

## Diseño

### 1. Idempotencia por `event_id`

`payment_event.event_id` es `UNIQUE`. PayGate conserva el mismo `event_id` en cada reintento, así que un evento ya
registrado devuelve `DUPLICATE` y no escribe nada ni cambia el estado. La respuesta es la misma ante el primer
envío y ante cualquier reintento posterior.

### 2. El estado depende de una máquina de estados, no del orden de llegada

El cambio de estado es **un único `UPDATE` condicional** sobre la fila del pago:

```sql
UPDATE payment SET status = :nuevo, ..., version = version + 1
 WHERE payment_id = :id AND status IN (:orígenes permitidos)
```

Si la condición no se cumple (evento viejo, estado final), el pago no cambia y el evento se guarda igual con
`outcome = IGNORED`, que es lo que pidió el negocio: *un evento anterior nunca degrada un estado ya avanzado, pero
queda en el historial para auditoría*.

| Evento recibido | Se aplica si el pago está en |
|---|---|
| `PENDING` | solo crea el pago |
| `AUTHORIZED` | `PENDING` |
| `CAPTURED` | `PENDING`, `AUTHORIZED` |
| `FAILED` | `PENDING`, `AUTHORIZED` |
| `REFUNDED` | `PENDING`, `AUTHORIZED`, `CAPTURED` |

`FAILED` y `REFUNDED` son terminales. Un `REFUNDED` que llega antes que su `CAPTURED` igual queda como estado
final: gana el estado más avanzado y el `CAPTURED` tardío se ignora (y se registra). Por eso el resultado
**converge** al mismo estado para cualquier orden de llegada, que es lo que verifica `ConvergenceTest`.

### 3. Concurrencia sin bloqueos de tabla

- El `UPDATE` condicional toma un lock **de fila**: los eventos del mismo pago se serializan y reevalúan la
  condición ya sobre el estado actualizado; los eventos de pagos distintos no se esperan entre sí.
- No hay `SELECT ... FOR UPDATE` ni locks de tabla.
- Crear el pago y registrar el evento usan `INSERT ... WHERE NOT EXISTS` más los índices únicos. Si dos hilos
  compiten, la base hace fallar a uno y el servicio **reintenta la transacción completa** (hasta 3 veces), que ya
  ve el resultado del otro. Se evitó `ON CONFLICT` para que el mismo SQL corra en PostgreSQL y en H2.

### 4. Esquema

- `payment`: una fila por pago con el estado consolidado, `amount`/`currency` del último evento aplicado y una
  **`version`** que sube en cada cambio de estado (permite a quien consulta detectar cambios sin comparar campos).
- `payment_event`: un registro por evento recibido, con su `outcome`. Índice `(payment_id, event_timestamp, id)`
  para el historial.

### 5. Observabilidad

`/actuator/health` y la métrica `paygate.webhooks` (contador con el tag `outcome`: `APPLIED`, `IGNORED`,
`DUPLICATE`). Una tasa alta de `IGNORED` o `DUPLICATE` indica problemas de red o de orden en PayGate.

## Supuestos y decisiones

Confirmado por el Product Owner: `event_id` idéntico en reintentos; hasta 5 reintentos con backoff exponencial y
timeout de 2 s; sin garantía de orden; un evento anterior no degrada un estado que ya avanzó; los estados son
`PENDING`, `AUTHORIZED`, `CAPTURED`, `FAILED` y `REFUNDED` (los tres últimos, finales); el payload siempre trae
`event_id`, `payment_id`, `status`, `amount`, `currency` y `timestamp`; carga de unos 350 eventos por segundo en
hora pico y ráfagas de 800, sin bloqueos pesados de tabla.

**Decisiones propias, a validar con el equipo antes de producción:**

- **Matriz de transiciones.** Mario no la definió más allá de la regla general. Se eligió la tabla de arriba: un
  `CAPTURED` ya procesado no pasa a `FAILED` (se ignora y queda en el historial), y `REFUNDED` puede suceder a
  `CAPTURED` aunque ambos figuren como finales.
- **Reembolsos parciales.** No se sabe si `amount` puede variar entre eventos. Hoy el pago toma el monto del último
  evento aplicado y cada evento guarda el suyo. Si existen reembolsos parciales, `REFUNDED` debería modelarse con
  el monto reembolsado en un campo aparte.
- **Concurrencia por pago.** Mario no pudo confirmar si PayGate sincroniza los envíos del mismo pago, así que se
  asume que pueden llegar en paralelo en cualquier momento.

## Escalabilidad

- Cada request son 3 a 4 sentencias cortas por clave primaria o única, en una transacción breve y sin llamadas
  externas, lo que entra holgado en el timeout de 2 s de PayGate.
- A 800 req/s el primer límite es el pool de conexiones (`DB_POOL_SIZE`) contra el máximo de PostgreSQL; se escala
  con más réplicas del servicio, porque la corrección no depende de la memoria de una instancia sino de la base.
- Para volúmenes mayores, el paso natural es persistir el evento y responder 2xx de inmediato, y aplicarlo de forma
  asíncrona; la idempotencia por `event_id` y el `UPDATE` condicional ya soportan ese esquema sin cambios.
- Las pruebas de concurrencia corren sobre H2; conviene repetirlas contra PostgreSQL real (por ejemplo con
  Testcontainers) antes de salir a producción.
