package com.mountainbackend.recomendaciones.mensajeria;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mountainbackend.recomendaciones.repositorio.RepositorioPerfiles;

/**
 * Consume pagos.procesados. Solo suma al perfil las compras APROBADAS, usando
 * los items que se guardaron al recibir pedidos.creados. Los eventos repetidos
 * son tolerados: sumar dos veces solo refuerza la afinidad, no rompe nada.
 */
@Component
public class ConsumidorPagos {

	private static final Logger log = LoggerFactory.getLogger(ConsumidorPagos.class);

	private final RepositorioPerfiles repositorio;
	private final ConsumidorPedidos consumidorPedidos;
	private final ObjectMapper objectMapper;

	public ConsumidorPagos(
			RepositorioPerfiles repositorio,
			ConsumidorPedidos consumidorPedidos,
			ObjectMapper objectMapper) {
		this.repositorio = repositorio;
		this.consumidorPedidos = consumidorPedidos;
		this.objectMapper = objectMapper;
	}

	@KafkaListener(
		topics = "${app.kafka.topic.pagos:pagos.procesados}",
		groupId = "${app.kafka.group.pagos:recomendaciones-pagos}",
		containerFactory = "kafkaListenerContainerFactory")
	public void consumir(String payload) throws Exception {
		EventoPagoProcesado evento = objectMapper.readValue(payload, EventoPagoProcesado.class);
		if (!"APROBADO".equalsIgnoreCase(evento.status())) {
			log.debug("Pago {} en estado {} no suma compra", evento.paymentId(), evento.status());
			return;
		}
		if (evento.customerEmail() == null || evento.orderId() == null) {
			log.warn("Evento pagos.procesados incompleto, se ignora: {}", payload);
			return;
		}

		Map<Long, Integer> items = consumidorPedidos.itemsDe(evento.orderId());
		if (items.isEmpty()) {
			log.warn("No hay items para el pedido {}; no se puede enriquecer el perfil", evento.orderId());
			return;
		}
		repositorio.registrarCompra(evento.customerEmail(), items);
		log.info("Compra aprobada sumada al perfil de {} (pedido {}, {} productos)",
			evento.customerEmail(), evento.orderId(), items.size());
	}
}
