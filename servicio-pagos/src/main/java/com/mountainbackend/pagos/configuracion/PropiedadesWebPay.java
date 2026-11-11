package com.mountainbackend.pagos.configuracion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracion de WebPay Plus (Transbank).
 * Sin credenciales (habilitado=false) se usa el simulador, de modo que la demo
 * funciona completa sin depender de Transbank.
 */
@ConfigurationProperties(prefix = "app.webpay")
public class PropiedadesWebPay {

	private boolean habilitado = false;
	private String entorno = "INTEGRATION";
	private String commerceCode = "";
	private String apiKey = "";
	private String returnUrl = "http://localhost:8087/payments/webpay/return";
	private String frontendUrl = "http://localhost:5173";

	public boolean isHabilitado() {
		return habilitado;
	}

	public void setHabilitado(boolean habilitado) {
		this.habilitado = habilitado;
	}

	public String getEntorno() {
		return entorno;
	}

	public void setEntorno(String entorno) {
		this.entorno = entorno;
	}

	public String getCommerceCode() {
		return commerceCode;
	}

	public void setCommerceCode(String commerceCode) {
		this.commerceCode = commerceCode;
	}

	public String getApiKey() {
		return apiKey;
	}

	public void setApiKey(String apiKey) {
		this.apiKey = apiKey;
	}

	public String getReturnUrl() {
		return returnUrl;
	}

	public void setReturnUrl(String returnUrl) {
		this.returnUrl = returnUrl;
	}

	public String getFrontendUrl() {
		return frontendUrl;
	}

	public void setFrontendUrl(String frontendUrl) {
		this.frontendUrl = frontendUrl;
	}

	/** URL base del API REST de Transbank segun el entorno. */
	public String resolverBaseUrl() {
		return "PRODUCTION".equalsIgnoreCase(entorno)
			? "https://webpay3g.transbank.cl"
			: "https://webpay3gint.transbank.cl";
	}

	public boolean tieneCredenciales() {
		return habilitado
			&& commerceCode != null && !commerceCode.isBlank()
			&& apiKey != null && !apiKey.isBlank();
	}
}
