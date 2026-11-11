package com.mountainbackend.recomendaciones.servicio;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.mountainbackend.recomendaciones.modelo.PerfilUsuario;
import com.mountainbackend.recomendaciones.modelo.Producto;
import com.mountainbackend.recomendaciones.modelo.Recomendacion;

/**
 * Motor de recomendaciones simple, explicable y determinista.
 *
 * Estrategias:
 *  1. Personalizada: afinidad por categoria, marca y actividades ponderada por
 *     las compras del usuario. Excluye lo que ya compro.
 *  2. Tendencia: productos mas comprados globalmente (fallback: mas pedidos).
 *  3. Relacionadas: misma categoria o actividades compartidas.
 */
@Component
public class MotorRecomendaciones {

	public List<Recomendacion> personalizadas(PerfilUsuario perfil, List<Producto> catalogo, int limite) {
		Map<Long, Integer> historial = new HashMap<>();
		if (perfil != null) {
			historial.putAll(perfil.getPedidos());
			perfil.getCompras().forEach((id, cantidad) -> historial.merge(id, cantidad, Integer::sum));
		}
		if (historial.isEmpty()) {
			return tendencia(Map.of(), catalogo, limite);
		}

		Map<String, Integer> categorias = new HashMap<>();
		Map<String, Integer> marcas = new HashMap<>();
		Map<String, Integer> actividades = new HashMap<>();
		Set<Long> comprados = new HashSet<>(historial.keySet());

		for (Producto producto : catalogo) {
			int peso = historial.getOrDefault(producto.id(), 0);
			if (peso <= 0) {
				continue;
			}
			sumar(categorias, producto.category(), peso);
			sumar(marcas, producto.brand(), peso);
			if (producto.activity() != null) {
				for (String actividad : producto.activity()) {
					sumar(actividades, actividad, peso);
				}
			}
		}

		List<Recomendacion> resultado = new ArrayList<>();
		for (Producto producto : catalogo) {
			if (comprados.contains(producto.id())) {
				continue;
			}
			int score = 0;
			Set<String> motivos = new LinkedHashSet<>();

			int porCategoria = categorias.getOrDefault(normalizar(producto.category()), 0);
			if (porCategoria > 0) {
				score += 40 + porCategoria * 5;
				motivos.add("misma categoria (" + producto.category() + ")");
			}
			int porMarca = marcas.getOrDefault(normalizar(producto.brand()), 0);
			if (porMarca > 0) {
				score += 25 + porMarca * 3;
				motivos.add("misma marca (" + producto.brand() + ")");
			}
			if (producto.activity() != null) {
				for (String actividad : producto.activity()) {
					int porActividad = actividades.getOrDefault(normalizar(actividad), 0);
					if (porActividad > 0) {
						score += 10 + porActividad * 2;
						motivos.add("actividad " + actividad);
					}
				}
			}

			if (score > 0) {
				resultado.add(Recomendacion.desde(producto, score,
					"Recomendado por " + String.join(", ", motivos)));
			}
		}

		resultado.sort(Comparator.comparingInt(Recomendacion::score).reversed()
			.thenComparingLong(Recomendacion::id));

		if (resultado.size() < limite) {
			// Completa con tendencia para que la seccion nunca quede vacia,
			// sin volver a ofrecer lo que el usuario ya compro.
			Set<Long> yaIncluidos = new HashSet<>(comprados);
			resultado.forEach(recomendacion -> yaIncluidos.add(recomendacion.id()));
			for (Recomendacion tendencia : tendencia(Map.of(), catalogo, limite)) {
				if (resultado.size() >= limite) {
					break;
				}
				if (yaIncluidos.add(tendencia.id())) {
					resultado.add(tendencia);
				}
			}
		}

		return resultado.stream().limit(limite).toList();
	}

	public List<Recomendacion> tendencia(Map<Long, Integer> comprasGlobales, List<Producto> catalogo, int limite) {
		List<Recomendacion> resultado = new ArrayList<>();
		for (Producto producto : catalogo) {
			int compras = comprasGlobales.getOrDefault(producto.id(), 0);
			int score = 10 + compras * 10;
			String motivo = compras > 0
				? "Tendencia: " + compras + " compras"
				: "Recomendado por la tienda";
			resultado.add(Recomendacion.desde(producto, score, motivo));
		}
		resultado.sort(Comparator.comparingInt(Recomendacion::score).reversed()
			.thenComparingLong(Recomendacion::id));
		return resultado.stream().limit(limite).toList();
	}

	public List<Recomendacion> relacionadas(long productId, List<Producto> catalogo, int limite) {
		Producto base = catalogo.stream()
			.filter(producto -> producto.id() == productId)
			.findFirst()
			.orElse(null);
		if (base == null) {
			return List.of();
		}

		Set<String> actividadesBase = new HashSet<>();
		if (base.activity() != null) {
			base.activity().forEach(actividad -> actividadesBase.add(normalizar(actividad)));
		}

		List<Recomendacion> resultado = new ArrayList<>();
		for (Producto producto : catalogo) {
			if (producto.id() == productId) {
				continue;
			}
			int score = 0;
			Set<String> motivos = new LinkedHashSet<>();

			if (normalizar(base.category()).equals(normalizar(producto.category()))) {
				score += 50;
				motivos.add("misma categoria (" + producto.category() + ")");
			}
			if (normalizar(base.brand()).equals(normalizar(producto.brand()))) {
				score += 25;
				motivos.add("misma marca (" + producto.brand() + ")");
			}
			if (producto.activity() != null) {
				for (String actividad : producto.activity()) {
					if (actividadesBase.contains(normalizar(actividad))) {
						score += 15;
						motivos.add("actividad " + actividad);
					}
				}
			}
			if (score > 0) {
				resultado.add(Recomendacion.desde(producto, score,
					"Relacionado por " + String.join(", ", motivos)));
			}
		}

		resultado.sort(Comparator.comparingInt(Recomendacion::score).reversed()
			.thenComparingLong(Recomendacion::id));
		return resultado.stream().limit(limite).toList();
	}

	private void sumar(Map<String, Integer> mapa, String clave, int peso) {
		if (clave == null || clave.isBlank()) {
			return;
		}
		mapa.merge(normalizar(clave), peso, Integer::sum);
	}

	private String normalizar(String valor) {
		return valor == null ? "" : valor.trim().toLowerCase(Locale.ROOT);
	}
}
