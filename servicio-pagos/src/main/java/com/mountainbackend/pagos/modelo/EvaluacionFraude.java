package com.mountainbackend.pagos.modelo;

import java.util.List;

/**
 * Resultado de evaluar una solicitud de pago.
 *
 * @param score   puntaje 0-100 (mayor = mas sospechoso)
 * @param nivel   BAJO / MEDIO / FRAUDE
 * @param motivos explicacion legible de cada regla que sumo puntos
 */
public record EvaluacionFraude(
		int score,
		NivelRiesgo nivel,
		List<String> motivos) {
}
