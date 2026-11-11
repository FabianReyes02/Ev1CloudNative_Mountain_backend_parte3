package com.mountainbackend.pagos.modelo;

import java.time.Instant;
import java.util.List;

/**
 * Copia local del pedido recibida por Kafka (pedidos.creados).
 * Permite validar monto, email e items sin llamar por HTTP a servicio-pedidos.
 */
public record PedidoSnapshot(
		String orderId,
		String customerEmail,
		String customerName,
		int total,
		List<ItemPedido> items,
		Instant recibidoEn) {

	public record ItemPedido(
			long productId,
			String name,
			int price,
			int quantity) {
	}
}
