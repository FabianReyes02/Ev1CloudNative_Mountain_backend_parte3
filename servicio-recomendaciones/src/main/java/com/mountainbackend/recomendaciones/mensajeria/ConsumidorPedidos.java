package com.mountainbackend.recomendaciones.mensajeria;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mountainbackend.recomendaciones.repositorio.RepositorioPerfiles;

/**
 * Consume pedidos.creados: registra el interes del usuario y guarda los items
 * por orderId. Cuando llegue el pago aprobado se usan esos items para sumar la
 * compra al perfil (el evento de pago no incluye el detalle de productos).
 */
@Component
public class ConsumidorPedidos {

	private static final Logger log = LoggerFactory.getLogger(ConsumidorPedidos.class);

	private final RepositorioPerfiles repositorio;
	private final ObjectMapper objectMapper;
	private final Map<String, Map<Long, Integer>> itemsPorPedido = new ConcurrentHashMap<>();

	public ConsumidorPedidos(RepositorioPerfiles repositorio, ObjectMapper objectMapper) {
		this.repositorio = repositorio;
		this.objectMapper = objectMapper;
	}

	@KafkaListener(
		topics = "${app.kafka.topic.pedidos:pedidos.creados}",
		groupId = "${app.kafka.group.pedidos:recomendaciones-pedidos}",
		containerFactory = "kafkaListenerContainerFactory")
	public void consumir(String payload) throws Exception {
		EventoPedidoCreado evento = objectMapper.readValue(payload, EventoPedidoCreado.class);
		if (evento.orderId() == null || evento.customerEmail() == null) {
			log.warn("Evento pedidos.creados incompleto, se ignora: {}", payload);
			return;
		}

		Map<Long, Integer> items = new HashMap<>();
		if (evento.items() != null) {
			for (EventoPedidoCreado.ItemPedidoCreado item : evento.items()) {
				items.merge(item.productId(), item.quantity(), Integer::sum);
			}
		}

		itemsPorPedido.put(evento.orderId(), items);
		repositorio.registrarPedido(evento.customerEmail(), items);
		log.info("Interes registrado para {} (pedido {}, {} productos)",
			evento.customerEmail(), evento.orderId(), items.size());
	}

	public Map<Long, Integer> itemsDe(String orderId) {
		return itemsPorPedido.getOrDefault(orderId, Map.of());
	}
}
