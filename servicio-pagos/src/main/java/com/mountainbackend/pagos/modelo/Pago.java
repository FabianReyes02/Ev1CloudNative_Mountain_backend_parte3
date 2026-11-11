package com.mountainbackend.pagos.modelo;

import java.time.Instant;
import java.util.List;

/**
 * Pago registrado por el microservicio. El contrato JSON es consumido por el
 * frontend y por los eventos Kafka.
 */
public record Pago(
		String id,
		String orderId,
		String customerEmail,
		int amount,
		EstadoPago status,
		NivelRiesgo riskLevel,
		int fraudScore,
		List<String> fraudReasons,
		String paymentMethod,
		String webpayToken,
		String webpayUrl,
		boolean simulated,
		Instant createdAt,
		Instant updatedAt) {
}
