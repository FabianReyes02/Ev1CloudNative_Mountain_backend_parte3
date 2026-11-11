package com.mountainbackend.pagos.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mountainbackend.pagos.configuracion.PropiedadesFraude;
import com.mountainbackend.pagos.configuracion.PropiedadesWebPay;
import com.mountainbackend.pagos.dto.SolicitudPago;
import com.mountainbackend.pagos.mensajeria.EventoFraudeDetectado;
import com.mountainbackend.pagos.mensajeria.PublicadorPagos;
import com.mountainbackend.pagos.modelo.EstadoPago;
import com.mountainbackend.pagos.modelo.Pago;
import com.mountainbackend.pagos.modelo.PedidoSnapshot;
import com.mountainbackend.pagos.pasarela.PasarelaWebPay;
import com.mountainbackend.pagos.pasarela.ResultadoConfirmacion;
import com.mountainbackend.pagos.pasarela.TransaccionWebPay;
import com.mountainbackend.pagos.repositorio.RepositorioPagos;
import com.mountainbackend.pagos.repositorio.RepositorioPedidosSnapshot;

class ServicioPagosTest {

	private RepositorioPagos repositorioPagos;
	private RepositorioPedidosSnapshot repositorioPedidos;
	private PublicadorPagos publicador;
	private PasarelaWebPay pasarela;
	private ServicioPagos servicio;

	@BeforeEach
	void preparar() {
		repositorioPagos = new RepositorioPagos();
		repositorioPedidos = new RepositorioPedidosSnapshot();
		publicador = mock(PublicadorPagos.class);
		pasarela = mock(PasarelaWebPay.class);

		PropiedadesFraude fraude = new PropiedadesFraude();
		PropiedadesWebPay webpay = new PropiedadesWebPay();
		webpay.setHabilitado(false);

		DetectorFraude detector = new DetectorFraude(fraude,
			Clock.fixed(Instant.parse("2026-11-11T18:00:00Z"), ZoneOffset.UTC));

		servicio = new ServicioPagos(repositorioPagos, repositorioPedidos, detector,
			pasarela, publicador, webpay,
			Clock.fixed(Instant.parse("2026-11-11T18:00:00Z"), ZoneOffset.UTC));
	}

	private void snapshotPedido(String orderId, int total) {
		repositorioPedidos.guardar(new PedidoSnapshot(orderId, "cliente@summitlab.cl", "Cliente",
			total, List.of(new PedidoSnapshot.ItemPedido(1L, "Chaqueta", total, 1)),
			Instant.parse("2026-11-11T12:00:00Z")));
	}

	@Test
	void pagoNormalQuedaPendienteYUsaLaPasarela() {
		snapshotPedido("PEDIDO-1", 100_000);
		when(pasarela.iniciar(any(), any(), any(Integer.class), any()))
			.thenReturn(new TransaccionWebPay("SIM-1", null, true));

		Pago pago = servicio.crear(new SolicitudPago("PEDIDO-1", 100_000, "WEBPAY", null), null);

		assertThat(pago.status()).isEqualTo(EstadoPago.PENDIENTE);
		assertThat(pago.simulated()).isTrue();
		assertThat(pago.webpayToken()).isEqualTo("SIM-1");
		assertThat(repositorioPagos.listar()).hasSize(1);
	}

	@Test
	void confirmarPublicaPagoProcesado() {
		snapshotPedido("PEDIDO-1", 100_000);
		when(pasarela.iniciar(any(), any(), any(Integer.class), any()))
			.thenReturn(new TransaccionWebPay("SIM-1", null, true));
		when(pasarela.confirmar("SIM-1"))
			.thenReturn(new ResultadoConfirmacion(true, "AUTHORIZED", "ok"));

		Pago creado = servicio.crear(new SolicitudPago("PEDIDO-1", 100_000, "WEBPAY", null), null);
		Pago confirmado = servicio.confirmar(creado.id());

		assertThat(confirmado.status()).isEqualTo(EstadoPago.APROBADO);
		verify(publicador, times(1)).publicarPagoProcesado(any());
	}

	@Test
	void fraudePublicaAlertaYNoLlamaAWebPay() {
		snapshotPedido("PEDIDO-1", 100_000);

		Pago pago = servicio.crear(new SolicitudPago("PEDIDO-1", 0, "WEBPAY", null), null);

		assertThat(pago.status()).isEqualTo(EstadoPago.RECHAZADO);
		assertThat(pago.fraudScore()).isEqualTo(100);
		verify(publicador).publicarFraude(any(EventoFraudeDetectado.class));
		verify(publicador).publicarPagoProcesado(any());
		verify(pasarela, times(0)).iniciar(any(), any(), any(Integer.class), any());
	}

	@Test
	void montoDescuadradoQuedaEnRevision() {
		snapshotPedido("PEDIDO-1", 100_000);

		Pago pago = servicio.crear(new SolicitudPago("PEDIDO-1", 50_000, "WEBPAY", null), null);

		assertThat(pago.status()).isEqualTo(EstadoPago.REVISION);
		assertThat(pago.fraudScore()).isEqualTo(40);
		verify(pasarela, times(0)).iniciar(any(), any(), any(Integer.class), any());
	}
}
