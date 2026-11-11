package com.mountainbackend.recomendaciones.repositorio;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Repository;

import com.mountainbackend.recomendaciones.modelo.PerfilUsuario;

/**
 * Perfiles en memoria + contadores globales para la tendencia.
 * En produccion puede reemplazarse por DynamoDB/RDS.
 */
@Repository
public class RepositorioPerfiles {

	private final Map<String, PerfilUsuario> perfiles = new ConcurrentHashMap<>();
	private final Map<Long, Integer> comprasGlobales = new ConcurrentHashMap<>();
	private final Map<Long, Integer> pedidosGlobales = new ConcurrentHashMap<>();

	public Optional<PerfilUsuario> perfil(String email) {
		if (email == null || email.isBlank()) {
			return Optional.empty();
		}
		return Optional.ofNullable(perfiles.get(normalizar(email)));
	}

	public PerfilUsuario obtenerOCrear(String email) {
		return perfiles.computeIfAbsent(normalizar(email), PerfilUsuario::new);
	}

	public void registrarPedido(String email, Map<Long, Integer> items) {
		if (email == null || email.isBlank()) {
			return;
		}
		obtenerOCrear(email).registrarPedido(items);
		items.forEach((productId, cantidad) ->
			pedidosGlobales.merge(productId, cantidad, Integer::sum));
	}

	public void registrarCompra(String email, Map<Long, Integer> items) {
		if (email == null || email.isBlank()) {
			return;
		}
		obtenerOCrear(email).registrarCompra(items);
		items.forEach((productId, cantidad) ->
			comprasGlobales.merge(productId, cantidad, Integer::sum));
	}

	public Map<Long, Integer> comprasGlobales() {
		return Map.copyOf(comprasGlobales);
	}

	public Map<Long, Integer> pedidosGlobales() {
		return Map.copyOf(pedidosGlobales);
	}

	private String normalizar(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}
}
