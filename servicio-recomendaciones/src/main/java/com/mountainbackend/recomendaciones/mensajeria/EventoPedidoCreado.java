package com.mountainbackend.recomendaciones.mensajeria;

import java.util.List;

/** Contrato del evento pedidos.creados (identico al de servicio-pedidos). */
public record EventoPedidoCreado(
		String orderId,
		String customerEmail,
		String customerName,
		List<ItemPedidoCreado> items,
		int total) {

	public record ItemPedidoCreado(
			long productId,
			String name,
			int price,
			int quantity) {
	}
}
