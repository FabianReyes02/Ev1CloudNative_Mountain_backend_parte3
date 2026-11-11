package com.mountainbackend.pagos.seguridad;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.mountainbackend.pagos.configuracion.PropiedadesSeguridad;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Valida los JWT que protegen /orders. Soporta dos emisores:
 *  - JWT local de la tienda (servicio-usuarios, HS256): se activa con
 *    app.seguridad.local-habilitado=true. Valida firma con el secreto compartido,
 *    vigencia, emisor y audiencia del token.
 *  - JWT de Azure AD / Entra ID (RS256/ES256): se activa con app.seguridad.habilitado=true
 *    y valida contra el JWKS remoto (firma, vigencia, issuer y audience).
 *  - 401 sin token o token inválido/expirado; 403 sin scope/rol requerido.
 * Con ambos validador desactivados el filtro no corre (modo demo sin token).
 */
@Component
@Order(1)
public class FiltroValidacionJwt extends OncePerRequestFilter {

	public static final String ATRIBUTO_CLAIMS_JWT = "jwt.claims";

	private static final Logger log = LoggerFactory.getLogger(FiltroValidacionJwt.class);

	private final PropiedadesSeguridad propiedades;
	private final JWKSource<SecurityContext> fuenteClaves;

	public FiltroValidacionJwt(PropiedadesSeguridad propiedades) {
		this.propiedades = propiedades;
		this.fuenteClaves = construirFuenteClaves();
	}

	private JWKSource<SecurityContext> construirFuenteClaves() {
		try {
			String jwksUri = recortarONulo(propiedades.getJwksUri());
			if (propiedades.isHabilitado() && jwksUri != null) {
				DefaultResourceRetriever recuperador = new DefaultResourceRetriever(5000, 5000);
				JWKSource<SecurityContext> fuente = new RemoteJWKSet<>(URI.create(jwksUri).toURL(), recuperador);
				log.info("Validación JWT de Azure activa contra jwks-uri={}", jwksUri);
				return fuente;
			}
		} catch (Exception ex) {
			log.error("No se pudo inicializar el JWKS ({})", ex.getMessage());
		}
		if (propiedades.isHabilitado()) {
			log.warn("JWKS no configurado: setea app.seguridad.jwks-uri para validar tokens de Azure.");
		} else if (propiedades.isLocalHabilitado()) {
			log.info("Validación JWT local activa (tokens HS256 de servicio-usuarios).");
		} else {
			log.warn("Validación JWT DESACTIVADA: el microservicio acepta peticiones sin token.");
		}
		return null;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		boolean algunValidadorActivo = propiedades.isHabilitado() || propiedades.isLocalHabilitado();
		String ruta = request.getRequestURI();
		return !algunValidadorActivo
			|| "OPTIONS".equalsIgnoreCase(request.getMethod())
			|| ruta.equals("/actuator/health")
			|| ruta.startsWith("/actuator/")
			|| ruta.equals("/payments/webpay/return");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String header = request.getHeader("Authorization");
		if (header == null || !header.startsWith("Bearer ")) {
			escribirError(response, 401, "Falta el token JWT en el header Authorization.");
			return;
		}

		String token = header.substring(7).trim();
		JWTClaimsSet claims;
		try {
			claims = verificarYParsear(token);
		} catch (Exception ex) {
			log.debug("Token rechazado: {}", ex.getMessage());
			escribirError(response, 401, "Token JWT inválido o expirado.");
			return;
		}

		if (esTokenLocal(claims)) {
			if (!tieneScopeLocal(claims)) {
				escribirError(response, 403, "No tienes los scopes requeridos para esta operación.");
				return;
			}
		} else {
			if (!emisorValido(claims)) {
				escribirError(response, 401, "El issuer del token no es el esperado.");
				return;
			}
			if (!audienciaValida(claims)) {
				escribirError(response, 401, "La audience del token no es la esperada.");
				return;
			}
			if (!tieneAutoridadRequerida(claims)) {
				escribirError(response, 403, "No tienes los scopes/roles requeridos para esta operación.");
				return;
			}
		}

		request.setAttribute(ATRIBUTO_CLAIMS_JWT, claims);
		chain.doFilter(request, response);
	}

	/**
	 * Verifica firma y vigencia del token. Si el emisor es el de la tienda
	 * (servicio-usuarios) valida el HS256 con el secreto compartido; en caso
	 * contrario valida contra el JWKS remoto de Azure (RS256/RS384/RS512/ES256...).
	 */
	private JWTClaimsSet verificarYParsear(String token) throws Exception {
		SignedJWT firmado = SignedJWT.parse(token);

		if (esFirmaLocal(firmado)) {
			if (!propiedades.isLocalHabilitado()) {
				throw new IllegalStateException("JWT local no habilitado en este servicio");
			}
			String secreto = recortarONulo(propiedades.getLocalSecreto());
			if (secreto == null) {
				throw new IllegalStateException("Falta el secreto local (JWT_LOCAL_SECRET)");
			}
			if (!firmado.verify(new MACVerifier(secreto.getBytes(java.nio.charset.StandardCharsets.UTF_8)))) {
				throw new IllegalStateException("Firma local JWT no válida");
			}
			JWTClaimsSet claims = firmado.getJWTClaimsSet();
			validarVigencia(claims);
			if (!audienciaLocalValida(claims)) {
				throw new IllegalStateException("Audiencia del token local no esperada");
			}
			return claims;
		}

		if (fuenteClaves == null) {
			throw new IllegalStateException("JWKS no configurado para validar tokens de Azure");
		}

		JWSHeader cabeceraJws = firmado.getHeader();
		JWSAlgorithm algoritmo = cabeceraJws.getAlgorithm();
		if (algoritmo == null || !JWSAlgorithm.Family.RSA.contains(algoritmo) && !JWSAlgorithm.Family.EC.contains(algoritmo)) {
			throw new IllegalStateException("Algoritmo de firma no soportado: " + algoritmo);
		}

		KeyType tipoClave = JWSAlgorithm.Family.RSA.contains(algoritmo) ? KeyType.RSA : KeyType.EC;
		// Solo se filtra por tipo de clave + kid: el JWKS de Azure no trae
		// campo "alg" en sus llaves y filtrar por algoritmo las descarta a
		// todas ("No se encontró clave de verificación en el JWKS").
		// La familia del algoritmo ya se validó arriba y cada llave se
		// verifica con su verificador correspondiente (RSA/EC).
		JWKMatcher.Builder coincidencia = new JWKMatcher.Builder()
			.keyType(tipoClave);
		if (cabeceraJws.getKeyID() != null) {
			coincidencia.keyID(cabeceraJws.getKeyID());
		}
		List<JWK> claves = fuenteClaves.get(new JWKSelector(coincidencia.build()), (SecurityContext) null);
		if (claves == null || claves.isEmpty()) {
			throw new IllegalStateException("No se encontró clave de verificación en el JWKS");
		}

		boolean verificada = false;
		for (JWK clave : claves) {
			JWSVerifier verificador = null;
			if (clave instanceof RSAKey rsa) {
				verificador = new RSASSAVerifier(rsa);
			} else if (clave instanceof ECKey ec) {
				verificador = new ECDSAVerifier(ec);
			}
			if (verificador != null && firmado.verify(verificador)) {
				verificada = true;
				break;
			}
		}
		if (!verificada) {
			throw new IllegalStateException("Firma JWT no válida");
		}

		return validarVigencia(firmado.getJWTClaimsSet());
	}

	private JWTClaimsSet validarVigencia(JWTClaimsSet claims) throws Exception {
		Date ahora = new Date();
		Date expiracion = claims.getExpirationTime();
		if (expiracion == null || expiracion.before(ahora)) {
			throw new IllegalStateException("Token expirado");
		}
		Date noAntesDe = claims.getNotBeforeTime();
		if (noAntesDe != null && noAntesDe.after(ahora)) {
			throw new IllegalStateException("Token aún no es válido (nbf)");
		}
		return claims;
	}

	/**
	 * true si el token fue emitido por servicio-usuarios (emisor local), para enrutar
	 * la validación sin tener que consultar el JWKS de Azure.
	 */
	private boolean esFirmaLocal(SignedJWT firmado) {
		try {
			String emisor = firmado.getJWTClaimsSet() != null ? firmado.getJWTClaimsSet().getIssuer() : null;
			String esperado = recortarONulo(propiedades.getLocalEmisor());
			return emisor != null && esperado != null && emisor.equals(esperado);
		} catch (Exception ex) {
			return false;
		}
	}

	private boolean esTokenLocal(JWTClaimsSet claims) {
		if (!propiedades.isLocalHabilitado()) {
			return false;
		}
		String emisor = recortarONulo(propiedades.getLocalEmisor());
		return emisor != null && emisor.equals(claims.getIssuer());
	}

	private boolean audienciaLocalValida(JWTClaimsSet claims) {
		String esperada = recortarONulo(propiedades.getLocalAudiencia());
		if (esperada == null) {
			return true;
		}
		List<String> audienciasToken = claims.getAudience();
		return audienciasToken != null && audienciasToken.contains(esperada);
	}

	private boolean tieneScopeLocal(JWTClaimsSet claims) {
		String scopeEsperado = recortarONulo(propiedades.getLocalScopeRequerido());
		if (scopeEsperado == null || scopeEsperado.isEmpty()) {
			return true;
		}
		Set<String> scopes = new HashSet<>();
		for (String nombreClaim : List.of("scp", "scope", "scopes")) {
			Object valor = claims.getClaim(nombreClaim);
			if (valor instanceof List<?> lista) {
				for (Object item : lista) {
					scopes.add(String.valueOf(item));
				}
			} else if (valor != null) {
				scopes.add(String.valueOf(valor));
			}
		}
		return scopes.stream().anyMatch(scope -> esPermiso(scope, scopeEsperado));
	}

	private boolean emisorValido(JWTClaimsSet claims) {
		String esperado = recortarONulo(propiedades.getEmisorUri());
		return esperado == null || esperado.equals(claims.getIssuer());
	}

	private boolean audienciaValida(JWTClaimsSet claims) {
		List<String> esperadas = dividir(propiedades.getAudiencias());
		if (esperadas.isEmpty()) {
			return true;
		}
		List<String> audienciasToken = claims.getAudience();
		if (audienciasToken == null || audienciasToken.isEmpty()) {
			return false;
		}
		for (String audienciaEsperada : esperadas) {
			if (audienciasToken.contains(audienciaEsperada)) {
				return true;
			}
		}
		return false;
	}

	private boolean tieneAutoridadRequerida(JWTClaimsSet claims) {
		List<String> scopesRequeridos = dividir(propiedades.getScopesRequeridos());
		List<String> rolesRequeridos = dividir(propiedades.getRolesRequeridos());
		if (scopesRequeridos.isEmpty() && rolesRequeridos.isEmpty()) {
			return true;
		}

		Set<String> scopesToken = new HashSet<>();
		for (String nombreClaim : List.of("scp", "scope", "scopes")) {
			Object valor = claims.getClaim(nombreClaim);
			if (valor instanceof List<?> lista) {
				for (Object item : lista) {
					scopesToken.add(String.valueOf(item));
				}
			} else if (valor != null) {
				scopesToken.add(String.valueOf(valor));
			}
		}

		Set<String> rolesToken = new HashSet<>();
		Object valorRoles = claims.getClaim("roles");
		if (valorRoles instanceof List<?> roles) {
			for (Object rol : roles) {
				rolesToken.add(String.valueOf(rol));
			}
		} else if (valorRoles != null) {
			rolesToken.add(String.valueOf(valorRoles));
		}

		for (String requerido : scopesRequeridos) {
			if (scopesToken.stream().anyMatch(scope -> esPermiso(scope, requerido))) {
				return true;
			}
		}
		for (String requerido : rolesRequeridos) {
			if (rolesToken.contains(requerido)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Comprueba si un scope del token cubre el requerido. Azure entrega el claim scp
	 * a veces como lista separada por espacios y otras con el prefijo completo del
	 * recurso (p.ej. "api://app-id/orders.write"): se acepta coincidencia exacta o
	 * por sufijo ("/orders.write").
	 */
	private boolean esPermiso(String permisoToken, String requerido) {
		if (permisoToken == null || requerido == null) {
			return false;
		}
		for (String parte : permisoToken.trim().split("\\s+")) {
			if (parte.isEmpty()) {
				continue;
			}
			if (parte.equals(requerido) || parte.endsWith("/" + requerido)) {
				return true;
			}
		}
		return false;
	}

	private List<String> dividir(String csv) {
		List<String> resultado = new ArrayList<>();
		String valor = recortarONulo(csv);
		if (valor == null) {
			return resultado;
		}
		for (String parte : valor.split(",")) {
			String recortada = parte.trim();
			if (!recortada.isEmpty()) {
				resultado.add(recortada);
			}
		}
		return resultado;
	}

	private String recortarONulo(String valor) {
		if (valor == null) {
			return null;
		}
		String recortado = valor.trim();
		return recortado.isEmpty() ? null : recortado;
	}

	private void escribirError(HttpServletResponse response, int status, String mensaje) throws IOException {
		response.setStatus(status);
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write("{\"status\":" + status + ",\"error\":\"" + mensaje + "\"}");
	}
}
