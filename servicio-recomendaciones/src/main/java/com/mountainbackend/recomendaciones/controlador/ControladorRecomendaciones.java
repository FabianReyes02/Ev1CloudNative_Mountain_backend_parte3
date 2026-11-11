package com.mountainbackend.recomendaciones.controlador;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mountainbackend.recomendaciones.modelo.Producto;
import com.mountainbackend.recomendaciones.modelo.Recomendacion;
import com.mountainbackend.recomendaciones.repositorio.RepositorioPerfiles;
import com.mountainbackend.recomendaciones.servicio.ClienteProductos;
import com.mountainbackend.recomendaciones.servicio.MotorRecomendaciones;

/**
 * Endpoints publicos de recomendaciones (los consume el frontend via nginx).
 *
 * GET /recommendations?email=&limit=      -> personalizadas (o tendencia)
 * GET /recommendations/trending?limit=    -> mas compradas
 * GET /recommendations/products/{id}      -> relacionadas a un producto
 */
@RestController
@RequestMapping("/recommendations")
public class ControladorRecomendaciones {

	private static final int LIMITE_DEFECTO = 6;
	private static final int LIMITE_MAXIMO = 24;

	private final RepositorioPerfiles repositorio;
	private final ClienteProductos clienteProductos;
	private final MotorRecomendaciones motor;

	public ControladorRecomendaciones(
			RepositorioPerfiles repositorio,
			ClienteProductos clienteProductos,
			MotorRecomendaciones motor) {
		this.repositorio = repositorio;
		this.clienteProductos = clienteProductos;
		this.motor = motor;
	}

	@GetMapping
	public List<Recomendacion> personalizadas(
			@RequestParam(value = "email", required = false) String email,
			@RequestParam(value = "limit", defaultValue = "6") int limit) {
		int limite = normalizarLimite(limit);
		List<Producto> catalogo = clienteProductos.catalogo();
		return repositorio.perfil(email)
			.map(perfil -> motor.personalizadas(perfil, catalogo, limite))
			.orElseGet(() -> motor.tendencia(repositorio.comprasGlobales(), catalogo, limite));
	}

	@GetMapping("/trending")
	public List<Recomendacion> tendencia(
			@RequestParam(value = "limit", defaultValue = "6") int limit) {
		return motor.tendencia(
			repositorio.comprasGlobales(),
			clienteProductos.catalogo(),
			normalizarLimite(limit));
	}

	@GetMapping("/products/{id}")
	public List<Recomendacion> relacionadas(
			@PathVariable("id") long id,
			@RequestParam(value = "limit", defaultValue = "4") int limit) {
		return motor.relacionadas(id, clienteProductos.catalogo(), normalizarLimite(limit));
	}

	private int normalizarLimite(int limit) {
		if (limit <= 0) {
			return LIMITE_DEFECTO;
		}
		return Math.min(limit, LIMITE_MAXIMO);
	}
}
