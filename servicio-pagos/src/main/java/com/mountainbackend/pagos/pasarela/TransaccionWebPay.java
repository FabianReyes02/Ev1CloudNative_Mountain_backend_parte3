package com.mountainbackend.pagos.pasarela;

/**
 * Transaccion iniciada en WebPay.
 *
 * @param token    token_ws que devuelve Transbank
 * @param url      URL de redireccion a WebPay (null en modo simulado)
 * @param simulado true si la genero el simulador
 */
public record TransaccionWebPay(
		String token,
		String url,
		boolean simulado) {
}
