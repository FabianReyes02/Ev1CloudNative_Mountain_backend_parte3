package com.mountainbackend.recomendaciones.servicio;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.mountainbackend.recomendaciones.modelo.Producto;

/**
 * Cliente HTTP hacia servicio-productos con cache en memoria de 60 segundos.
 * Si el catalogo no responde, se devuelve el ultimo valor cacheado (o lista
 * vacia) para no romper el endpoint de recomendaciones.
 */
@Component
public class ClienteProductos {

	private static final Logger log = LoggerFactory.getLogger(ClienteProductos.class);
	private static final Duration TTL = Duration.ofSeconds(60);

	private final RestClient restClient;
	private volatile List<Producto> cache = List.of();
	private volatile Instant cacheExpira = Instant.EPOCH;

	public ClienteProductos(@Value("${app.productos.url:http://localhost:8081}") String baseUrl) {
		this.restClient = RestClient.builder().baseUrl(baseUrl).build();
	}

	public List<Producto> catalogo() {
		if (Instant.now().isBefore(cacheExpira)) {
			return cache;
		}
		synchronized (this) {
			if (Instant.now().isBefore(cacheExpira)) {
				return cache;
			}
			try {
				Producto[] productos = restClient.get()
					.uri("/products")
					.retrieve()
					.body(Producto[].class);
				cache = productos == null ? List.of() : Arrays.asList(productos);
				cacheExpira = Instant.now().plus(TTL);
				log.debug("Catalogo actualizado: {} productos", cache.size());
			} catch (Exception ex) {
				log.warn("No se pudo obtener el catalogo de productos: {}", ex.getMessage());
				// Reintenta pronto, pero sigue sirviendo lo cacheado.
				cacheExpira = Instant.now().plus(Duration.ofSeconds(10));
			}
			return cache;
		}
	}

	public Producto porId(long id) {
		return catalogo().stream()
			.filter(producto -> producto.id() == id)
			.findFirst()
			.orElse(null);
	}
}
