package com.mountainbackend.pagos.pasarela;

/**
 * Contrato de la pasarela de pago. Permite intercambiar WebPay real por el
 * simulador sin tocar el servicio de pagos (patron adapter).
 */
public interface PasarelaWebPay {

	TransaccionWebPay iniciar(String buyOrder, String sessionId, int amount, String returnUrl);

	ResultadoConfirmacion confirmar(String token);
}
