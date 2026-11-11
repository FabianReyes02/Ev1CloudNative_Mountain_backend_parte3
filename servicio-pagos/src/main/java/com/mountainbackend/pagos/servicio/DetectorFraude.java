package com.mountainbackend.pagos.servicio;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.mountainbackend.pagos.configuracion.PropiedadesFraude;
import com.mountainbackend.pagos.dto.SolicitudPago;
import com.mountainbackend.pagos.modelo.EvaluacionFraude;
import com.mountainbackend.pagos.modelo.NivelRiesgo;
import com.mountainbackend.pagos.modelo.Pago;
import com.mountainbackend.pagos.modelo.PedidoSnapshot;

/**
 * Deteccion de fraude por reglas ponderadas (score 0-100).
 *
 * Reglas:
 *  - monto invalido (<= 0)                                  -> rechazo directo (100)
 *  - monto distinto al total del pedido                     -> +40
 *  - monto >= $2.000.000 CLP                                -> +35
 *  - mas de 3 intentos del mismo email en 10 min            -> +30
 *  - email desechable                                       -> +25
 *  - mas de 10 unidades u 8 lineas                          -> +15
 *  - compra entre 00:00 y 05:59 (America/Santiago)          -> +10
 *  - mismo monto repetido mas de 2 veces en 10 min          -> +20
 */
@Component
public class DetectorFraude {

	private static final ZoneId ZONA = ZoneId.of("America/Santiago");
	private static final Set<String> DOMINIOS_DESECHABLES = Set.of(
		"mailinator.com", "yopmail.com", "guerrillamail.com",
		"10minutemail.com", "tempmail.com", "trashmail.com", "sharklasers.com");

	private final PropiedadesFraude propiedades;
	private final Clock clock;

	public DetectorFraude(PropiedadesFraude propiedades, Clock clock) {
		this.propiedades = propiedades;
		this.clock = clock;
	}

	public EvaluacionFraude evaluar(PedidoSnapshot pedido, SolicitudPago solicitud, List<Pago> historial) {
		int score = 0;
		List<String> motivos = new ArrayList<>();

		int monto = solicitud.amount() == null ? 0 : solicitud.amount();
		if (monto <= 0) {
			score = 100;
			motivos.add("Monto invalido: debe ser mayor a cero");
		}

		if (pedido != null && monto > 0 && monto != pedido.total()) {
			score += 40;
			motivos.add("El monto no coincide con el total del pedido ($" + pedido.total() + ")");
		}

		if (monto >= 2_000_000) {
			score += 35;
			motivos.add("Monto inusualmente alto (>= $2.000.000)");
		}

		String email = emailDe(solicitud, pedido);
		if (email != null && esDesechable(email)) {
			score += 25;
			motivos.add("Email desechable");
		}

		Instant ahora = Instant.now(clock);
		Instant hace10 = ahora.minus(10, ChronoUnit.MINUTES);
		List<Pago> recientes = historial == null ? List.of() : historial.stream()
			.filter(pago -> pago.createdAt() != null && !pago.createdAt().isBefore(hace10))
			.toList();

		if (recientes.size() > 3) {
			score += 30;
			motivos.add("Mas de 3 intentos del mismo email en los ultimos 10 minutos");
		}

		long mismoMonto = recientes.stream().filter(pago -> pago.amount() == monto).count();
		if (mismoMonto > 2) {
			score += 20;
			motivos.add("Mismo monto repetido mas de 2 veces en 10 minutos");
		}

		if (pedido != null && pedido.items() != null) {
			int unidades = pedido.items().stream()
				.mapToInt(PedidoSnapshot.ItemPedido::quantity)
				.sum();
			if (unidades > 10 || pedido.items().size() > 8) {
				score += 15;
				motivos.add("Cantidad de items inusual (" + unidades + " unidades)");
			}
		}

		int hora = LocalTime.now(clock.withZone(ZONA)).getHour();
		if (hora < 6) {
			score += 10;
			motivos.add("Compra en horario nocturno (00:00-05:59)");
		}

		score = Math.min(100, score);
		NivelRiesgo nivel;
		if (score >= propiedades.getUmbralRevision()) {
			nivel = NivelRiesgo.FRAUDE;
		} else if (score >= propiedades.getUmbralAprobado()) {
			nivel = NivelRiesgo.MEDIO;
		} else {
			nivel = NivelRiesgo.BAJO;
		}
		return new EvaluacionFraude(score, nivel, List.copyOf(motivos));
	}

	private String emailDe(SolicitudPago solicitud, PedidoSnapshot pedido) {
		if (solicitud.customerEmail() != null && !solicitud.customerEmail().isBlank()) {
			return solicitud.customerEmail();
		}
		return pedido != null ? pedido.customerEmail() : null;
	}

	private boolean esDesechable(String email) {
		int arroba = email.indexOf('@');
		if (arroba < 0 || arroba == email.length() - 1) {
			return false;
		}
		String dominio = email.substring(arroba + 1).trim().toLowerCase(Locale.ROOT);
		return DOMINIOS_DESECHABLES.contains(dominio);
	}
}
