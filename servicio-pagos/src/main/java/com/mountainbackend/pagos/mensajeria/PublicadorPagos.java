package com.mountainbackend.pagos.mensajeria;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Publica los eventos de pagos como JSON plano. Si Kafka no esta disponible
 * solo se registra el fallo: el pago ya quedo registrado en memoria y la
 * respuesta HTTP no se bloquea por la mensajeria.
 */
@Component
public class PublicadorPagos {

	private static final Logger log = LoggerFactory.getLogger(PublicadorPagos.class);

	private final KafkaTemplate<String, String> kafkaTemplate;
	private final ObjectMapper objectMapper;
	private final String topicPagos;
	private final String topicFraude;

	public PublicadorPagos(
			KafkaTemplate<String, String> kafkaTemplate,
			ObjectMapper objectMapper,
			@Value("${app.kafka.topic.pagos:pagos.procesados}") String topicPagos,
			@Value("${app.kafka.topic.fraude:pagos.fraude.detectado}") String topicFraude) {
		this.kafkaTemplate = kafkaTemplate;
		this.objectMapper = objectMapper;
		this.topicPagos = topicPagos;
		this.topicFraude = topicFraude;
	}

	public void publicarPagoProcesado(EventoPagoProcesado evento) {
		enviar(topicPagos, evento.orderId(), evento);
	}

	public void publicarFraude(EventoFraudeDetectado evento) {
		enviar(topicFraude, evento.orderId(), evento);
	}

	private void enviar(String topic, String clave, Object evento) {
		try {
			String json = objectMapper.writeValueAsString(evento);
			kafkaTemplate.send(topic, clave, json).whenComplete((resultado, error) -> {
				if (error != null) {
					log.error("No se pudo publicar en {}: {}", topic, error.getMessage());
				} else {
					log.info("Evento publicado en {} key={} offset={}",
						topic, clave, resultado.getRecordMetadata().offset());
				}
			});
		} catch (Exception ex) {
			log.error("No se pudo serializar el evento para {}: {}", topic, ex.getMessage());
		}
	}
}
