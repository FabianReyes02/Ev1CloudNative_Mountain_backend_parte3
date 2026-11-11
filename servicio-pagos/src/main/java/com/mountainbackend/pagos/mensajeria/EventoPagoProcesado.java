package com.mountainbackend.pagos.mensajeria;

import java.time.Instant;

/**
 * Evento pagos.procesados. Lo consumen pedidos (cambia estado), envios (crea
 * despacho solo si APROBADO), notificaciones (comprobante) y recomendaciones
 * (suma la compra al perfil).
 */
public record EventoPagoProcesado(
		String paymentId,
		String orderId,
		String customerEmail,
		int amount,
		String status,
		String riskLevel,
		int fraudScore,
		String paymentMethod,
		Instant occurredAt) {
}
