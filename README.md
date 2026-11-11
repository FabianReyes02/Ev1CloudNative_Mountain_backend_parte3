# Pedidos360 — Backend parte 3 (EC2 backend-3)

Microservicios nuevos de la evaluación y broker de mensajería:

| Servicio | Puerto local | Descripción |
|---|---:|---|
| `servicio-pagos` | 8087 | Pagos con detección de fraude (score 0–100) y WebPay Plus (real o simulado) |
| `servicio-recomendaciones` | 8088 | Recomendaciones personalizadas, tendencia y productos relacionados |
| Kafka (KRaft) | 29092 | Broker único para los 8 microservicios |
| Kafka UI | 8090 | Interfaz web para evidenciar tópicos, mensajes y consumer groups |

> Este repo **no reemplaza** a los otros dos: backend-1 conserva productos/pedidos/usuarios
> y backend-2 conserva notificaciones/carrito/envíos. Aquí viven los servicios nuevos y Kafka.

## Arquitectura de eventos

```text
pedidos :8082 ──► pedidos.creados ──┬─► productos        (descuenta stock)
                                     ├─► pagos            (snapshot del pedido)
                                     ├─► recomendaciones  (perfil de compra)
                                     └─► notificaciones   (correo "pedido recibido")

pagos :8087 ──► pagos.procesados ───┬─► pedidos          (PAGADO / RECHAZADO)
                                     ├─► envíos           (crea despacho si APROBADO)
                                     ├─► notificaciones   (comprobante)
                                     └─► recomendaciones  (compra aprobada)

pagos :8087 ──► pagos.fraude.detectado ─┬─► pedidos          (estado FRAUDE)
                                         └─► notificaciones  (alerta)
```

Cada flecha es un **consumer group** distinto: todos reciben el mismo evento de forma
independiente, igual que las colas Rabbit de la evaluación anterior. Ver
[`docs/MIGRACION_KAFKA.md`](docs/MIGRACION_KAFKA.md) para la explicación completa
(útil para el examen).

## Endpoints

### servicio-pagos (`:8087`)

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/payments` | Crea el pago, evalúa fraude e inicia WebPay (real o simulado) |
| `GET` | `/payments` | Lista pagos |
| `GET` | `/payments/{id}` | Detalle de un pago |
| `POST` | `/payments/{id}/confirm` | Confirma el pago (modo simulado o tras el retorno) |
| `POST` | `/payments/webpay/return` | Retorno de Transbank (`token_ws`), público |

Ejemplo:

```bash
curl -X POST http://localhost:8087/payments \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"orderId":"PEDIDO-100001","amount":179800,"paymentMethod":"WEBPAY"}'
```

Respuesta:

```json
{
  "id": "PAGO-000001",
  "orderId": "PEDIDO-100001",
  "amount": 179800,
  "status": "PENDIENTE",
  "riskLevel": "BAJO",
  "fraudScore": 0,
  "fraudReasons": [],
  "webpayUrl": null,
  "webpayToken": null,
  "simulated": true
}
```

### Reglas de fraude (score 0–100)

| Regla | Puntos |
|---|---:|
| Monto que no coincide con el total del pedido | +40 |
| Monto ≥ $2.000.000 CLP | +35 |
| Más de 3 intentos del mismo email en 10 min | +30 |
| Email desechable (`mailinator`, `yopmail`, etc.) | +25 |
| Más de 10 unidades u 8 líneas | +15 |
| Compra entre 00:00 y 05:59 (America/Santiago) | +10 |
| Mismo monto repetido más de 2 veces en 10 min | +20 |

Decisión: `< 40` **APROBADO**, `40–69` **REVISION**, `>= 70` **RECHAZADO / FRAUDE**.

### servicio-recomendaciones (`:8088`)

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/recommendations?email=&limit=` | Personalizadas (o tendencia si no hay historial) |
| `GET` | `/recommendations/trending?limit=` | Más compradas globalmente |
| `GET` | `/recommendations/products/{id}?limit=` | Relacionadas por categoría/actividad/marca |

## Cómo levantarlo en local

Requisitos: Docker Desktop y JDK 17+.

```bash
cp .env.example .env
./mvnw clean package -DskipTests
docker compose up -d --build
docker compose ps
```

- Kafka: `localhost:29092`
- Kafka UI: <http://localhost:8090>
- Pagos: <http://localhost:8087/actuator/health>
- Recomendaciones: <http://localhost:8088/actuator/health>

Crear tópicos manualmente (opcional, Spring los crea solos):

```bash
docker exec pedidos360-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --create --if-not-exists \
  --topic pedidos.creados --partitions 3 --replication-factor 1
docker exec pedidos360-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --create --if-not-exists \
  --topic pagos.procesados --partitions 3 --replication-factor 1
docker exec pedidos360-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --create --if-not-exists \
  --topic pagos.fraude.detectado --partitions 3 --replication-factor 1
```

## Build y tests

```bash
./mvnw clean verify
```

En Windows:

```powershell
.\mvnw.cmd clean verify
```

## WebPay

- `WEBPAY_ENABLED=false` (default): usa `WebPaySimulado`. El botón "Simular pago" del
  frontend confirma el pago y dispara `pagos.procesados`.
- `WEBPAY_ENABLED=true` + `WEBPAY_COMMERCE_CODE` + `WEBPAY_API_KEY`: usa
  `WebPayPlusCliente` contra Transbank. `WEBPAY_ENV=INTEGRATION` apunta a
  `webpay3gint.transbank.cl`; `PRODUCTION` a `webpay3g.transbank.cl`.
- `WEBPAY_RETURN_URL` debe ser públicamente accesible (el nginx del frontend lo expone como
  `/api/payments/webpay/return`).

## Despliegue

Ver [`docs/DESPLIEGUE_AWS.md`](docs/DESPLIEGUE_AWS.md): guía completa desde cero
(VPC, security groups, EC2, Kafka, variables y verificación).

## Estado del trabajo (pausa)

La migración de RabbitMQ a Kafka de los otros dos repos está en la rama
`feature/kafka-migracion` de cada uno. Como respaldo dentro de este repo, los
cambios completos están en:

- [`docs/parches/backend-parte1-kafka.patch`](docs/parches/backend-parte1-kafka.patch)
- [`docs/parches/backend-parte2-kafka.patch`](docs/parches/backend-parte2-kafka.patch)

Para aplicarlos sobre el `main` original:

```bash
git checkout -b feature/kafka-migracion
git apply docs/parches/backend-parte1-kafka.patch
```

Pendiente al retomar:

1. Compilar y correr tests de parte 1 y parte 2 después de la migración.
2. Integrar pagos y recomendaciones en el frontend (`PaymentModal`, checkout,
   sección de recomendaciones, `nginx.conf`).
3. Actualizar `NOTAS.md` y READMEs de los tres repos.
4. Verificar el flujo E2E con Docker Compose y luego en AWS.
