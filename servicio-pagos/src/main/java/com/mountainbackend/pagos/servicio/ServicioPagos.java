package com.mountainbackend.pagos.servicio;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.mountainbackend.pagos.configuracion.PropiedadesWebPay;
import com.mountainbackend.pagos.dto.SolicitudPago;
import com.mountainbackend.pagos.mensajeria.EventoFraudeDetectado;
import com.mountainbackend.pagos.mensajeria.EventoPagoProcesado;
import com.mountainbackend.pagos.mensajeria.PublicadorPagos;
import com.mountainbackend.pagos.modelo.EstadoPago;
import com.mountainbackend.pagos.modelo.EvaluacionFraude;
import com.mountainbackend.pagos.modelo.NivelRiesgo;
import com.mountainbackend.pagos.modelo.Pago;
import com.mountainbackend.pagos.modelo.PedidoSnapshot;
import com.mountainbackend.pagos.pasarela.PasarelaWebPay;
import com.mountainbackend.pagos.pasarela.ResultadoConfirmacion;
import com.mountainbackend.pagos.pasarela.TransaccionWebPay;
import com.mountainbackend.pagos.repositorio.RepositorioPagos;
import com.mountainbackend.pagos.repositorio.RepositorioPedidosSnapshot;

/**
 * Orquesta el flujo de pago:
 *  1. valida la solicitud y busca el snapshot del pedido,
 *  2. evalua fraude,
 *  3. si el riesgo es bajo inicia WebPay (real o simulado),
 *  4. al confirmar publica pagos.procesados.
 */
@Service
public class ServicioPagos {

	private static final Logger log = LoggerFactory.getLogger(ServicioPagos.class);

	private final RepositorioPagos repositorioPagos;
	private final RepositorioPedidosSnapshot repositorioPedidos;
	private final DetectorFraude detectorFraude;
	private final PasarelaWebPay pasarela;
	private final PublicadorPagos publicador;
	private final PropiedadesWebPay propiedadesWebPay;
	private final Clock clock;

	public ServicioPagos(
			RepositorioPagos repositorioPagos,
			RepositorioPedidosSnapshot repositorioPedidos,
			DetectorFraude detectorFraude,
			PasarelaWebPay pasarela,
			PublicadorPagos publicador,
			PropiedadesWebPay propiedadesWebPay,
			Clock clock) {
		this.repositorioPagos = repositorioPagos;
		this.repositorioPedidos = repositorioPedidos;
		this.detectorFraude = detectorFraude;
		this.pasarela = pasarela;
		this.publicador = publicador;
		this.propiedadesWebPay = propiedadesWebPay;
		this.clock = clock;
	}

	public Pago crear(SolicitudPago solicitud, String emailToken) {
		if (solicitud == null || solicitud.orderId() == null || solicitud.orderId().isBlank()) {
			throw new IllegalArgumentException("El campo 'orderId' es obligatorio.");
		}

		int monto = solicitud.amount() == null ? 0 : solicitud.amount();
		String metodo = primeroNoVacio(solicitud.paymentMethod(), "WEBPAY").toUpperCase(Locale.ROOT);
		PedidoSnapshot pedido = repositorioPedidos.buscar(solicitud.orderId()).orElse(null);
		String email = primeroNoVacio(
			emailToken,
			solicitud.customerEmail(),
			pedido != null ? pedido.customerEmail() : null,
			"demo@summitlab.cl");

		List<Pago> historial = repositorioPagos.porEmail(email);
		EvaluacionFraude evaluacion = detectorFraude.evaluar(pedido, solicitud, historial);
		Instant ahora = Instant.now(clock);
		String id = repositorioPagos.nuevoId();

		if (evaluacion.nivel() == NivelRiesgo.FRAUDE) {
			Pago rechazado = new Pago(id, solicitud.orderId(), email, monto,
				EstadoPago.RECHAZADO, evaluacion.nivel(), evaluacion.score(), evaluacion.motivos(),
				metodo, null, null, true, ahora, ahora);
			repositorioPagos.guardar(rechazado);
			publicarProcesado(rechazado);
			publicador.publicarFraude(new EventoFraudeDetectado(
				rechazado.id(), rechazado.orderId(), rechazado.customerEmail(), rechazado.amount(),
				rechazado.fraudScore(), rechazado.fraudReasons(), ahora));
			log.warn("Pago {} rechazado por fraude (score={})", id, evaluacion.score());
			return rechazado;
		}

		if (evaluacion.nivel() == NivelRiesgo.MEDIO) {
			Pago revision = new Pago(id, solicitud.orderId(), email, monto,
				EstadoPago.REVISION, evaluacion.nivel(), evaluacion.score(), evaluacion.motivos(),
				metodo, null, null, true, ahora, ahora);
			repositorioPagos.guardar(revision);
			publicarProcesado(revision);
			log.info("Pago {} enviado a revision (score={})", id, evaluacion.score());
			return revision;
		}

		try {
			TransaccionWebPay transaccion = pasarela.iniciar(
				id, email, monto, propiedadesWebPay.getReturnUrl());
			Pago pendiente = new Pago(id, solicitud.orderId(), email, monto,
				EstadoPago.PENDIENTE, evaluacion.nivel(), evaluacion.score(), evaluacion.motivos(),
				metodo, transaccion.token(), transaccion.url(), transaccion.simulado(), ahora, ahora);
			repositorioPagos.guardar(pendiente);
			log.info("Pago {} iniciado (simulado={})", id, transaccion.simulado());
			return pendiente;
		} catch (RuntimeException ex) {
			Pago fallido = new Pago(id, solicitud.orderId(), email, monto,
				EstadoPago.RECHAZADO, NivelRiesgo.ALTO, evaluacion.score(),
				List.of("No se pudo iniciar WebPay: " + ex.getMessage()),
				metodo, null, null, true, ahora, ahora);
			repositorioPagos.guardar(fallido);
			publicarProcesado(fallido);
			log.error("Pago {} no pudo iniciar WebPay: {}", id, ex.getMessage());
			return fallido;
		}
	}

	public Pago confirmar(String id) {
		Pago pago = repositorioPagos.buscar(id)
			.orElseThrow(() -> new NoSuchElementException("Pago no encontrado: " + id));

		if (pago.status() == EstadoPago.APROBADO) {
			return pago;
		}
		if (pago.status() != EstadoPago.PENDIENTE) {
			throw new IllegalStateException("El pago esta en estado " + pago.status() + " y no se puede confirmar.");
		}

		ResultadoConfirmacion resultado = pasarela.confirmar(pago.webpayToken());
		Instant ahora = Instant.now(clock);
		EstadoPago estado = resultado.aprobado() ? EstadoPago.APROBADO : EstadoPago.RECHAZADO;
		NivelRiesgo nivel = resultado.aprobado() ? NivelRiesgo.BAJO : NivelRiesgo.ALTO;

		Pago actualizado = new Pago(
			pago.id(), pago.orderId(), pago.customerEmail(), pago.amount(),
			estado, nivel, pago.fraudScore(), pago.fraudReasons(),
			pago.paymentMethod(), pago.webpayToken(), pago.webpayUrl(), pago.simulated(),
			pago.createdAt(), ahora);

		repositorioPagos.guardar(actualizado);
		publicarProcesado(actualizado);
		log.info("Pago {} confirmado: {} ({})", id, estado, resultado.detalle());
		return actualizado;
	}

	public Pago confirmarPorToken(String token) {
		Pago pago = repositorioPagos.buscarPorToken(token)
			.orElseThrow(() -> new NoSuchElementException("No existe un pago con ese token de WebPay."));
		return confirmar(pago.id());
	}

	public Pago buscar(String id) {
		return repositorioPagos.buscar(id)
			.orElseThrow(() -> new NoSuchElementException("Pago no encontrado: " + id));
	}

	public List<Pago> listar() {
		return repositorioPagos.listar();
	}

	private void publicarProcesado(Pago pago) {
		publicador.publicarPagoProcesado(new EventoPagoProcesado(
			pago.id(), pago.orderId(), pago.customerEmail(), pago.amount(),
			pago.status().name(), pago.riskLevel().name(), pago.fraudScore(),
			pago.paymentMethod(), pago.updatedAt()));
	}

	private String primeroNoVacio(String... valores) {
		for (String valor : valores) {
			if (valor != null && !valor.isBlank()) {
				return valor.trim();
			}
		}
		return "";
	}
}
