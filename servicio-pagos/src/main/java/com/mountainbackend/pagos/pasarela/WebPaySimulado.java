package com.mountainbackend.pagos.pasarela;

import java.util.UUID;

/**
 * Pasarela simulada: permite probar el flujo completo sin credenciales de
 * Transbank. El frontend muestra el boton "Simular pago" y luego llama a
 * POST /payments/{id}/confirm.
 */
public class WebPaySimulado implements PasarelaWebPay {

	@Override
	public TransaccionWebPay iniciar(String buyOrder, String sessionId, int amount, String returnUrl) {
		return new TransaccionWebPay("SIM-" + UUID.randomUUID(), null, true);
	}

	@Override
	public ResultadoConfirmacion confirmar(String token) {
		return new ResultadoConfirmacion(true, "AUTHORIZED", "Pago simulado aprobado");
	}
}
