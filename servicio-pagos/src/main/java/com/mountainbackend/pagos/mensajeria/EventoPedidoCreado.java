package com.mountainbackend.pagos.mensajeria;

import java.util.List;

/**
 * Contrato del evento pedidos.creados. Se mantiene identico al que publica
 * servicio-pedidos para no acoplar clases entre microservicios.
 */
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
