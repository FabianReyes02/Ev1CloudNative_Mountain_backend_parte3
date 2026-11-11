package com.mountainbackend.recomendaciones.modelo;

import java.util.List;

/**
 * Producto del catalogo. Mismos campos que expone servicio-productos
 * (contrato JSON en ingles, igual que el frontend).
 */
public record Producto(
		long id,
		String name,
		String description,
		int price,
		String image,
		String membrane,
		String tempRating,
		String waterproof,
		String weight,
		List<String> activity,
		String category,
		String brand,
		List<String> colors) {
}
