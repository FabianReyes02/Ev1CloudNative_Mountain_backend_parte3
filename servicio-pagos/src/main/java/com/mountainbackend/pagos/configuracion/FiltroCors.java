package com.mountainbackend.pagos.configuracion;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * CORS seguro para el microservicio de pedidos.
 * La capa perimetral (AWS API Gateway) restringe el dominio del frontend;
 * este filtro permite desarrollo local y refuerza el control por origen.
 */
@Component
@Order(0)
public class FiltroCors extends OncePerRequestFilter {

	private final List<String> origenesPermitidos;

	public FiltroCors(@Value("${app.cors.origenes-permitidos:http://localhost:5173}") String origenesPermitidos) {
		this.origenesPermitidos = Arrays.stream(origenesPermitidos.split(","))
			.map(String::trim)
			.filter(value -> !value.isEmpty())
			.toList();
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String origin = request.getHeader("Origin");
		if (origin != null && esOrigenPermitido(origin)) {
			response.setHeader("Access-Control-Allow-Origin", origin);
			response.setHeader("Access-Control-Allow-Credentials", "true");
			response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH, OPTIONS");
			response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
			response.setHeader("Access-Control-Max-Age", "3600");

			if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
				response.setStatus(HttpServletResponse.SC_OK);
				return;
			}
		}
		chain.doFilter(request, response);
	}

	private boolean esOrigenPermitido(String origin) {
		return origenesPermitidos.contains("*") || origenesPermitidos.contains(origin);
	}
}
