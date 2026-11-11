package com.mountainbackend.pagos.servicio;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mountainbackend.pagos.configuracion.PropiedadesFraude;
import com.mountainbackend.pagos.dto.SolicitudPago;
import com.mountainbackend.pagos.modelo.EvaluacionFraude;
import com.mountainbackend.pagos.modelo.NivelRiesgo;
import com.mountainbackend.pagos.modelo.Pago;
import com.mountainbackend.pagos.modelo.PedidoSnapshot;

class DetectorFraudeTest {

	private PropiedadesFraude propiedades;
	private PedidoSnapshot pedido;

	@BeforeEach
	void preparar() {
		propiedades = new PropiedadesFraude();
		propiedades.setUmbralAprobado(40);
		propiedades.setUmbralRevision(70);
		pedido = new PedidoSnapshot(
			"PEDIDO-1", "cliente@summitlab.cl", "Cliente", 100_000,
			List.of(new PedidoSnapshot.ItemPedido(1L, "Chaqueta", 100_000, 1)),
			Instant.parse("2026-11-11T12:00:00Z"));
	}

	private DetectorFraude detector(String instanteUtc) {
		Clock clock = Clock.fixed(Instant.parse(instanteUtc), ZoneOffset.UTC);
		return new DetectorFraude(propiedades, clock);
	}

	@Test
	void pagoNormalNoSumaRiesgo() {
		EvaluacionFraude evaluacion = detector("2026-11-11T18:00:00Z")
			.evaluar(pedido, new SolicitudPago("PEDIDO-1", 100_000, "WEBPAY", "cliente@summitlab.cl"), List.of());

		assertThat(evaluacion.score()).isZero();
		assertThat(evaluacion.nivel()).isEqualTo(NivelRiesgo.BAJO);
		assertThat(evaluacion.motivos()).isEmpty();
	}

	@Test
	void montoDistintoAlPedidoSumaCuarenta() {
		EvaluacionFraude evaluacion = detector("2026-11-11T18:00:00Z")
			.evaluar(pedido, new SolicitudPago("PEDIDO-1", 50_000, "WEBPAY", "cliente@summitlab.cl"), List.of());

		assertThat(evaluacion.score()).isEqualTo(40);
		assertThat(evaluacion.nivel()).isEqualTo(NivelRiesgo.MEDIO);
		assertThat(evaluacion.motivos()).anyMatch(motivo -> motivo.contains("no coincide"));
	}

	@Test
	void montoInvalidoSeRechazaConCien() {
		EvaluacionFraude evaluacion = detector("2026-11-11T18:00:00Z")
			.evaluar(pedido, new SolicitudPago("PEDIDO-1", 0, "WEBPAY", "cliente@summitlab.cl"), List.of());

		assertThat(evaluacion.score()).isEqualTo(100);
		assertThat(evaluacion.nivel()).isEqualTo(NivelRiesgo.FRAUDE);
	}

	@Test
	void montoAltoYDescuadradoLlegaAFraude() {
		EvaluacionFraude evaluacion = detector("2026-11-11T18:00:00Z")
			.evaluar(pedido, new SolicitudPago("PEDIDO-1", 2_500_000, "WEBPAY", "cliente@summitlab.cl"), List.of());

		// +40 descuadre +35 monto alto = 75
		assertThat(evaluacion.score()).isEqualTo(75);
		assertThat(evaluacion.nivel()).isEqualTo(NivelRiesgo.FRAUDE);
	}

	@Test
	void emailDesechableSumaVeinticinco() {
		EvaluacionFraude evaluacion = detector("2026-11-11T18:00:00Z")
			.evaluar(pedido, new SolicitudPago("PEDIDO-1", 100_000, "WEBPAY", "test@mailinator.com"), List.of());

		assertThat(evaluacion.score()).isEqualTo(25);
		assertThat(evaluacion.nivel()).isEqualTo(NivelRiesgo.BAJO);
		assertThat(evaluacion.motivos()).contains("Email desechable");
	}

	@Test
	void compraNocturnaSumaDiez() {
		// 05:00 UTC = 02:00 en America/Santiago (UTC-3 en noviembre)
		EvaluacionFraude evaluacion = detector("2026-11-11T05:00:00Z")
			.evaluar(pedido, new SolicitudPago("PEDIDO-1", 100_000, "WEBPAY", "cliente@summitlab.cl"), List.of());

		assertThat(evaluacion.score()).isEqualTo(10);
		assertThat(evaluacion.motivos()).anyMatch(motivo -> motivo.contains("nocturno"));
	}

	@Test
	void muchosIntentosRecientesSumanTreinta() {
		Instant ahora = Instant.parse("2026-11-11T18:00:00Z");
		List<Pago> historial = List.of(
			pago("P1", 100_000, ahora.minusSeconds(60)),
			pago("P2", 100_000, ahora.minusSeconds(120)),
			pago("P3", 100_000, ahora.minusSeconds(180)),
			pago("P4", 100_000, ahora.minusSeconds(240)));

		EvaluacionFraude evaluacion = detector("2026-11-11T18:00:00Z")
			.evaluar(pedido, new SolicitudPago("PEDIDO-1", 100_000, "WEBPAY", "cliente@summitlab.cl"), historial);

		// +30 intentos +20 mismo monto
		assertThat(evaluacion.score()).isEqualTo(50);
		assertThat(evaluacion.nivel()).isEqualTo(NivelRiesgo.MEDIO);
	}

	private Pago pago(String id, int monto, Instant creado) {
		return new Pago(id, "PEDIDO-1", "cliente@summitlab.cl", monto, null, null, 0,
			List.of(), "WEBPAY", null, null, true, creado, creado);
	}
}
