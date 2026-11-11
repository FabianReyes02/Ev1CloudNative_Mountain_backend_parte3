package com.mountainbackend.pagos.configuracion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propiedades de seguridad del microservicio de pedidos.
 * Con los datos de Azure AD / Entra ID se habilita la validación JWT en el servicio
 * (defensa en profundidad, además del JWT Authorizer del AWS API Gateway).
 */
@ConfigurationProperties(prefix = "app.seguridad")
public class PropiedadesSeguridad {

	/** Activa/desactiva la validación JWT (false = modo demo sin token). */
	private boolean habilitado = false;

	/** URL del JWKS de Azure. Ej: https://login.microsoftonline.com/{tenant-id}/discovery/v2.0/keys */
	private String jwksUri = "";

	/** Issuer esperado. Ej: https://login.microsoftonline.com/{tenant-id}/v2.0 */
	private String emisorUri = "";

	/** Audiencias aceptadas (separadas por coma). Ej: api://{app-client-id} */
	private String audiencias = "";

	/** Scopes requeridos (separados por coma). Ej: orders.write */
	private String scopesRequeridos = "";

	/** Roles de aplicación requeridos (separados por coma). Ej: Orders.Write */
	private String rolesRequeridos = "";

	/** Habilita el JWT local emitido por servicio-usuarios (HS256). */
	private boolean localHabilitado = false;

	/** Secreto compartido para validar los JWT de servicio-usuarios. */
	private String localSecreto = "";

	/** Emisor esperado de los JWT de la tienda (por defecto pedidos360-usuarios). */
	private String localEmisor = "pedidos360-usuarios";

	/** Audiencia esperada de los JWT de la tienda (pedidos360-api). */
	private String localAudiencia = "pedidos360-api";

	/** Scope requerido cuando se valida un JWT local (default orders.write). */
	private String localScopeRequerido = "orders.write";

	public boolean isHabilitado() {
		return habilitado;
	}

	public void setHabilitado(boolean habilitado) {
		this.habilitado = habilitado;
	}

	public String getJwksUri() {
		return jwksUri;
	}

	public void setJwksUri(String jwksUri) {
		this.jwksUri = jwksUri;
	}

	public String getEmisorUri() {
		return emisorUri;
	}

	public void setEmisorUri(String emisorUri) {
		this.emisorUri = emisorUri;
	}

	public String getAudiencias() {
		return audiencias;
	}

	public void setAudiencias(String audiencias) {
		this.audiencias = audiencias;
	}

	public String getScopesRequeridos() {
		return scopesRequeridos;
	}

	public void setScopesRequeridos(String scopesRequeridos) {
		this.scopesRequeridos = scopesRequeridos;
	}

	public String getRolesRequeridos() {
		return rolesRequeridos;
	}

	public void setRolesRequeridos(String rolesRequeridos) {
		this.rolesRequeridos = rolesRequeridos;
	}

	public boolean isLocalHabilitado() {
		return localHabilitado;
	}

	public void setLocalHabilitado(boolean localHabilitado) {
		this.localHabilitado = localHabilitado;
	}

	public String getLocalSecreto() {
		return localSecreto;
	}

	public void setLocalSecreto(String localSecreto) {
		this.localSecreto = localSecreto;
	}

	public String getLocalEmisor() {
		return localEmisor;
	}

	public void setLocalEmisor(String localEmisor) {
		this.localEmisor = localEmisor;
	}

	public String getLocalAudiencia() {
		return localAudiencia;
	}

	public void setLocalAudiencia(String localAudiencia) {
		this.localAudiencia = localAudiencia;
	}

	public String getLocalScopeRequerido() {
		return localScopeRequerido;
	}

	public void setLocalScopeRequerido(String localScopeRequerido) {
		this.localScopeRequerido = localScopeRequerido;
	}
}
