package com.mountainbackend.pagos.mensajeria;

import java.time.Instant;
import java.util.List;

/**
 * Evento pagos.fraude.detectado. Lo consumen pedidos (estado FRAUDE) y
 * notificaciones (alerta).
 */
public record EventoFraudeDetectado(
		String paymentId,
		String orderId,
		String customerEmail,
		int amount,
		int fraudScore,
		List<String> reasons,
		Instant occurredAt) {
}
