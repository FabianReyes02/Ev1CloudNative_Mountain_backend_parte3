package com.mountainbackend.pagos.controlador;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mountainbackend.pagos.configuracion.PropiedadesWebPay;
import com.mountainbackend.pagos.dto.SolicitudPago;
import com.mountainbackend.pagos.modelo.EstadoPago;
import com.mountainbackend.pagos.modelo.Pago;
import com.mountainbackend.pagos.seguridad.FiltroValidacionJwt;
import com.mountainbackend.pagos.servicio.ServicioPagos;
import com.nimbusds.jwt.JWTClaimsSet;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Endpoints de pagos.
 *
 * POST /payments                 -> crea el pago, evalua fraude e inicia WebPay
 * GET  /payments                 -> lista pagos
 * GET  /payments/{id}            -> detalle
 * POST /payments/{id}/confirm    -> confirma (modo simulado o post-retorno)
 * POST /payments/webpay/return   -> retorno publico de Transbank (token_ws)
 */
@RestController
@RequestMapping("/payments")
public class ControladorPagos {

	private final ServicioPagos servicio;
	private final PropiedadesWebPay propiedadesWebPay;

	public ControladorPagos(ServicioPagos servicio, PropiedadesWebPay propiedadesWebPay) {
		this.servicio = servicio;
		this.propiedadesWebPay = propiedadesWebPay;
	}

	@PostMapping
	public ResponseEntity<Pago> crear(@RequestBody SolicitudPago solicitud, HttpServletRequest request) {
		Pago pago = servicio.crear(solicitud, emailDe(request));
		return ResponseEntity.status(HttpStatus.CREATED).body(pago);
	}

	@GetMapping
	public List<Pago> listar() {
		return servicio.listar();
	}

	@GetMapping("/{id}")
	public Pago buscar(@PathVariable String id) {
		return servicio.buscar(id);
	}

	@PostMapping("/{id}/confirm")
	public Pago confirmar(@PathVariable String id) {
		return servicio.confirmar(id);
	}

	/**
	 * Retorno de Transbank. Es publico (Transbank no envia JWT), por eso el
	 * filtro de seguridad lo excluye. Redirige al frontend con el resultado.
	 */
	@RequestMapping(value = "/webpay/return", method = { RequestMethod.GET, RequestMethod.POST })
	public ResponseEntity<Void> retorno(
			@RequestParam(value = "token_ws", required = false) String tokenWs,
			@RequestParam(value = "TBK_TOKEN", required = false) String tbkToken) {

		if (tokenWs == null || tokenWs.isBlank()) {
			return redirigir(propiedadesWebPay.getFrontendUrl() + "/?payment=cancelled");
		}

		Pago pago = servicio.confirmarPorToken(tokenWs);
		String estado = pago.status() == EstadoPago.APROBADO ? "success" : "failed";
		String orderId = URLEncoder.encode(pago.orderId(), StandardCharsets.UTF_8);
		return redirigir(propiedadesWebPay.getFrontendUrl()
			+ "/?payment=" + estado + "&orderId=" + orderId);
	}

	private ResponseEntity<Void> redirigir(String url) {
		return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
	}

	private String emailDe(HttpServletRequest request) {
		Object atributo = request.getAttribute(FiltroValidacionJwt.ATRIBUTO_CLAIMS_JWT);
		if (atributo instanceof JWTClaimsSet claims) {
			try {
				String email = claims.getStringClaim("email");
				if (email == null) {
					email = claims.getStringClaim("preferred_username");
				}
				return email != null ? email : claims.getSubject();
			} catch (java.text.ParseException ex) {
				return claims.getSubject();
			}
		}
		return null;
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, Object>> solicitudInvalida(IllegalArgumentException ex) {
		return error(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	@ExceptionHandler(NoSuchElementException.class)
	public ResponseEntity<Map<String, Object>> noEncontrado(NoSuchElementException ex) {
		return error(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler(IllegalStateException.class)
	public ResponseEntity<Map<String, Object>> conflicto(IllegalStateException ex) {
		return error(HttpStatus.CONFLICT, ex.getMessage());
	}

	private ResponseEntity<Map<String, Object>> error(HttpStatus status, String mensaje) {
		Map<String, Object> cuerpo = new LinkedHashMap<>();
		cuerpo.put("status", status.value());
		cuerpo.put("error", mensaje);
		return ResponseEntity.status(status).body(cuerpo);
	}
}
