package com.mountainbackend.recomendaciones.modelo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Perfil de compra de un usuario. Solo guarda ids y cantidades: las
 * preferencias (categoria, marca, actividades) se calculan contra el catalogo
 * vigente al momento de recomendar.
 */
public class PerfilUsuario {

	private final String email;
	private final Map<Long, Integer> pedidos = new ConcurrentHashMap<>();
	private final Map<Long, Integer> compras = new ConcurrentHashMap<>();

	public PerfilUsuario(String email) {
		this.email = email;
	}

	public String getEmail() {
		return email;
	}

	public Map<Long, Integer> getPedidos() {
		return pedidos;
	}

	public Map<Long, Integer> getCompras() {
		return compras;
	}

	public void registrarPedido(Map<Long, Integer> items) {
		items.forEach((productId, cantidad) -> pedidos.merge(productId, cantidad, Integer::sum));
	}

	public void registrarCompra(Map<Long, Integer> items) {
		items.forEach((productId, cantidad) -> compras.merge(productId, cantidad, Integer::sum));
	}
}
