package com.mountainbackend.recomendaciones.mensajeria;

import java.time.Instant;

/** Contrato del evento pagos.procesados. */
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
