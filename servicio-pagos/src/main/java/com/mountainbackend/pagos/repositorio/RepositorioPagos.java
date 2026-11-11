package com.mountainbackend.pagos.repositorio;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Repository;

import com.mountainbackend.pagos.modelo.Pago;

/**
 * Pagos en memoria. Para produccion puede reemplazarse por DynamoDB/RDS sin
 * tocar el controlador.
 */
@Repository
public class RepositorioPagos {

	private final Map<String, Pago> pagos = new ConcurrentHashMap<>();
	private final AtomicLong secuencia = new AtomicLong(1);

	public String nuevoId() {
		return String.format("PAGO-%06d", secuencia.getAndIncrement());
	}

	public Pago guardar(Pago pago) {
		pagos.put(pago.id(), pago);
		return pago;
	}

	public Optional<Pago> buscar(String id) {
		return Optional.ofNullable(pagos.get(id));
	}

	public Optional<Pago> buscarPorToken(String token) {
		if (token == null) {
			return Optional.empty();
		}
		return pagos.values().stream()
			.filter(pago -> token.equals(pago.webpayToken()))
			.findFirst();
	}

	public List<Pago> listar() {
		return pagos.values().stream()
			.sorted(Comparator.comparing(Pago::createdAt).reversed())
			.toList();
	}

	public List<Pago> porEmail(String email) {
		if (email == null) {
			return List.of();
		}
		return pagos.values().stream()
			.filter(pago -> email.equalsIgnoreCase(pago.customerEmail()))
			.sorted(Comparator.comparing(Pago::createdAt).reversed())
			.toList();
	}
}
