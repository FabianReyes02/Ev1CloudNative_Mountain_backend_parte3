package com.mountainbackend.pagos.repositorio;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Repository;

import com.mountainbackend.pagos.modelo.PedidoSnapshot;

/** Snapshot en memoria de los pedidos escuchados en pedidos.creados. */
@Repository
public class RepositorioPedidosSnapshot {

	private final Map<String, PedidoSnapshot> pedidos = new ConcurrentHashMap<>();

	public void guardar(PedidoSnapshot pedido) {
		pedidos.put(pedido.orderId(), pedido);
	}

	public Optional<PedidoSnapshot> buscar(String orderId) {
		return Optional.ofNullable(pedidos.get(orderId));
	}
}
