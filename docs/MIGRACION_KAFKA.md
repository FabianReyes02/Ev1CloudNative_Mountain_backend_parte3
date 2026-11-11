# Migración de colas RabbitMQ a Kafka

> Documento de apoyo para la defensa del proyecto. Responde la pregunta:
> **¿las colas de la evaluación anterior se mantienen como colas o se migran a Kafka?**

## 1. Respuesta corta

**Se migran a Kafka y se elimina RabbitMQ.**

Las colas actuales no desaparecen conceptualmente: se transforman en **consumer groups**
sobre tópicos Kafka. El fan-out se conserva y se agregan retención, replay y escalado.

## 2. Qué había antes (RabbitMQ)

```text
servicio-pedidos
   └── exchange directo "pedidos.eventos"
         └── routing key "pedido.creado"
               ├── cola "pedidos.creados"                (consumidor histórico)
               ├── cola "pedidos.creados.stock"          (servicio-productos)
               ├── cola "pedidos.creados.notificaciones" (servicio-notificaciones)
               └── cola "pedidos.creados.envios"         (servicio-envíos)
```

Cada cola recibe **una copia** del mismo evento. Es un fan-out clásico de RabbitMQ:
el exchange enruta y las colas desacoplan a los consumidores.

## 3. Qué hay ahora (Kafka)

```text
servicio-pedidos ──► tópico "pedidos.creados" (3 particiones)
                         ├── consumer group "productos-stock"
                         ├── consumer group "pagos-pedidos"
                         ├── consumer group "recomendaciones-pedidos"
                         └── consumer group "notificaciones-pedidos"

servicio-pagos ────► tópico "pagos.procesados"
                         ├── consumer group "pedidos-pagos"
                         ├── consumer group "envios-pagos"
                         ├── consumer group "notificaciones-pagos"
                         └── consumer group "recomendaciones-pagos"

servicio-pagos ────► tópico "pagos.fraude.detectado"
                         ├── consumer group "pedidos-fraude"
                         └── consumer group "notificaciones-fraude"
```

## 4. Tabla de equivalencias

| RabbitMQ (antes) | Kafka (ahora) | Por qué |
|---|---|---|
| exchange `pedidos.eventos` + routing key `pedido.creado` | tópico `pedidos.creados` | El tópico es el canal lógico del evento |
| cola `pedidos.creados.stock` | consumer group `productos-stock` | Cada grupo recibe todas las particiones asignadas y mantiene su offset |
| cola `pedidos.creados.notificaciones` | consumer group `notificaciones-pedidos` | Misma independencia, sin declarar cola |
| cola `pedidos.creados.envios` | consumer group `envios-pagos` | Envíos ahora depende del **pago aprobado**, no de la creación del pedido |
| — | tópico `pagos.procesados` | Habilita el flujo pagos → pedidos → envíos → notificaciones → recomendaciones |
| — | tópico `pagos.fraude.detectado` | Alerta específica de fraude, sin mezclarla con pagos normales |
| — | tópicos `*.DLT` | Reintentos + dead-letter para mensajes que fallan |

## 5. Por qué migrar (argumentos para el examen)

1. **La rúbrica exige Kafka con mínimo 2 tópicos.** Mantener Rabbit además de Kafka obliga a
   operar dos brokers, dos configuraciones y dos modelos de error sin beneficio funcional.
2. **El fan-out se conserva 1:1 con consumer groups.** Cada servicio sigue recibiendo todos
   los eventos que le importan, de forma independiente, sin acoplarse a los demás.
3. **Retención y replay.** Kafka conserva los eventos un tiempo configurable
   (`retention.ms`). Eso permite auditoría, reprocesar un consumidor nuevo y recuperar
   datos tras un bug. RabbitMQ borra el mensaje al hacer `ack`.
4. **Escalado horizontal por particiones.** Varias instancias del mismo servicio comparten
   un consumer group y se reparten las particiones (competing consumers), manteniendo orden
   por clave. En Rabbit se escala con más consumidores sobre la misma cola, pero sin orden
   por partición ni replay.
5. **Un solo broker para todo el sistema.** Menos infraestructura, menos RAM y un único
   punto de observación (Kafka UI).
6. **Camino directo a la nube gestionada.** El mismo código funciona contra AWS MSK,
   Confluent Cloud o un Kafka autohospedado; solo cambia `KAFKA_BOOTSTRAP_SERVERS`.
7. **Dead-letter topics nativos.** `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`
   reintentan y derivan a `*.DLT`, lo que facilita diagnosticar mensajes venenosos.

## 6. Cuándo sí convendría mantener RabbitMQ

Kafka no es la respuesta universal. RabbitMQ seguiría siendo mejor si:

- Se necesitara **routing complejo** (topic exchanges, header exchanges, patrones comodín).
- Se usaran **prioridades de mensaje** o **TTL por mensaje**.
- El volumen fuera bajo y se quisiera **baja latencia por mensaje** sin retención.
- Se requirieran **colas temporales exclusivas** por cliente (RPC).

Nuestro caso es **streaming de eventos de negocio con múltiples consumidores independientes,
necesidad de auditoría/replay y crecimiento por particiones**: Kafka encaja mejor.

## 7. Decisiones de implementación

- **Serialización JSON plana como `String`.** Evita acoplar clases entre microservicios y
  elimina los problemas de headers de tipo entre productores y consumidores.
- **Clave del mensaje = `orderId`.** Garantiza que todos los eventos de un mismo pedido
  caigan en la misma partición y se procesen en orden.
- **`acks=all` + idempotencia del productor.** Evita duplicados por reintentos de red.
- **Consumidores idempotentes.** Productos registra los `orderId` ya procesados para no
  descontar stock dos veces; pagos y recomendaciones toleran eventos repetidos.
- **`auto.offset.reset=earliest`.** Un consumidor nuevo procesa el histórico disponible.
- **Reintentos + DLT.** 3 reintentos con backoff de 1 s y luego `<topico>.DLT`.
- **Particiones = 3.** Permite hasta 3 instancias del mismo consumer group en paralelo.

## 8. Rollback

Si hubiera que volver atrás:

1. Los eventos son JSON plano, así que se pueden republicar en Rabbit sin transformación.
2. Se conserva el contrato `{orderId, customerEmail, customerName, items[], total}`.
3. El cambio está aislado en `mensajeria/` de cada servicio; los controladores y modelos no
   dependen del broker.

## 9. Evidencia para la entrega

```bash
# Tópicos
docker exec pedidos360-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --list

# Consumer groups
docker exec pedidos360-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --list

# Offsets por grupo
docker exec pedidos360-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group productos-stock

# Mensajes de un tópico
docker exec -it pedidos360-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic pedidos.creados --from-beginning
```
