package com.mountainbackend.pagos.dto;

/**
 * Payload de POST /payments.
 * amount es Integer para poder distinguir "no enviado" de 0.
 */
public record SolicitudPago(
		String orderId,
		Integer amount,
		String paymentMethod,
		String customerEmail) {
}
