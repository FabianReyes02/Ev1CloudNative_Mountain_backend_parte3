package com.mountainbackend.pagos.mensajeria;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mountainbackend.pagos.modelo.PedidoSnapshot;
import com.mountainbackend.pagos.repositorio.RepositorioPedidosSnapshot;

/**
 * Consume pedidos.creados y guarda un snapshot del pedido para validar monto,
 * email e items al momento de pagar. Si el JSON es invalido lanza la excepcion
 * para que el DefaultErrorHandler reintente y derive a pedidos.creados.DLT.
 */
@Component
public class ConsumidorPedidos {

	private static final Logger log = LoggerFactory.getLogger(ConsumidorPedidos.class);

	private final RepositorioPedidosSnapshot repositorio;
	private final ObjectMapper objectMapper;

	public ConsumidorPedidos(RepositorioPedidosSnapshot repositorio, ObjectMapper objectMapper) {
		this.repositorio = repositorio;
		this.objectMapper = objectMapper;
	}

	@KafkaListener(
		topics = "${app.kafka.topic.pedidos:pedidos.creados}",
		groupId = "${app.kafka.group.pedidos:pagos-pedidos}",
		containerFactory = "kafkaListenerContainerFactory")
	public void consumir(String payload) throws Exception {
		EventoPedidoCreado evento = objectMapper.readValue(payload, EventoPedidoCreado.class);
		List<PedidoSnapshot.ItemPedido> items = evento.items() == null
			? List.of()
			: evento.items().stream()
				.map(item -> new PedidoSnapshot.ItemPedido(
					item.productId(), item.name(), item.price(), item.quantity()))
				.toList();

		repositorio.guardar(new PedidoSnapshot(
			evento.orderId(),
			evento.customerEmail(),
			evento.customerName(),
			evento.total(),
			items,
			Instant.now()));

		log.info("Snapshot de pedido {} registrado para pagos (total={})", evento.orderId(), evento.total());
	}
}
