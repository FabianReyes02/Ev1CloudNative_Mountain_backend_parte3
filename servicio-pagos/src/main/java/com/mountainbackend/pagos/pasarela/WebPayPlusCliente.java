package com.mountainbackend.pagos.pasarela;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.mountainbackend.pagos.configuracion.PropiedadesWebPay;

/**
 * Adaptador real de WebPay Plus (Transbank) usando su API REST v1.2.
 *
 * Flujo:
 *  1. POST /transactions  -> token + url de redireccion
 *  2. El usuario paga en WebPay y Transbank vuelve a return_url con token_ws
 *  3. PUT /transactions/{token} -> commit (AUTHORIZED / FAILED)
 *
 * Credenciales: Tbk-Api-Key-Id (commerce code) y Tbk-Api-Key-Secret (api key).
 */
public class WebPayPlusCliente implements PasarelaWebPay {

	private static final Logger log = LoggerFactory.getLogger(WebPayPlusCliente.class);

	private final PropiedadesWebPay propiedades;
	private final RestClient restClient;

	public WebPayPlusCliente(PropiedadesWebPay propiedades) {
		this.propiedades = propiedades;
		this.restClient = RestClient.builder()
			.baseUrl(propiedades.resolverBaseUrl())
			.defaultHeader("Tbk-Api-Key-Id", propiedades.getCommerceCode())
			.defaultHeader("Tbk-Api-Key-Secret", propiedades.getApiKey())
			.build();
	}

	@Override
	@SuppressWarnings("unchecked")
	public TransaccionWebPay iniciar(String buyOrder, String sessionId, int amount, String returnUrl) {
		try {
			Map<String, Object> respuesta = restClient.post()
				.uri("/rswebpaytransaction/api/webpay/v1.2/transactions")
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of(
					"buy_order", buyOrder,
					"session_id", sessionId,
					"amount", amount,
					"return_url", returnUrl))
				.retrieve()
				.body(Map.class);

			if (respuesta == null || respuesta.get("token") == null) {
				throw new IllegalStateException("WebPay no devolvio token");
			}
			String token = String.valueOf(respuesta.get("token"));
			String url = String.valueOf(respuesta.get("url"));
			log.info("WebPay iniciado buyOrder={} url={}", buyOrder, url);
			return new TransaccionWebPay(token, url, false);
		} catch (Exception ex) {
			log.error("Error iniciando WebPay: {}", ex.getMessage());
			throw new IllegalStateException("No se pudo iniciar WebPay: " + ex.getMessage(), ex);
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public ResultadoConfirmacion confirmar(String token) {
		try {
			Map<String, Object> respuesta = restClient.put()
				.uri("/rswebpaytransaction/api/webpay/v1.2/transactions/{token}", token)
				.contentType(MediaType.APPLICATION_JSON)
				.retrieve()
				.body(Map.class);

			String estado = respuesta == null ? "FAILED" : String.valueOf(respuesta.get("status"));
			boolean aprobado = "AUTHORIZED".equalsIgnoreCase(estado);
			log.info("WebPay commit token={} estado={}", token, estado);
			return new ResultadoConfirmacion(aprobado, estado, aprobado ? "Pago autorizado" : "Pago rechazado por WebPay");
		} catch (Exception ex) {
			log.error("Error confirmando WebPay: {}", ex.getMessage());
			return new ResultadoConfirmacion(false, "ERROR", "No se pudo confirmar WebPay: " + ex.getMessage());
		}
	}
}
