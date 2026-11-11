package com.mountainbackend.pagos.pasarela;

/**
 * Resultado de confirmar (commit) una transaccion WebPay.
 *
 * @param aprobado true si Transbank autorizo el pago
 * @param estado   estado crudo devuelto por Transbank (AUTHORIZED, FAILED, ...)
 * @param detalle  mensaje legible para logs
 */
public record ResultadoConfirmacion(
		boolean aprobado,
		String estado,
		String detalle) {
}
