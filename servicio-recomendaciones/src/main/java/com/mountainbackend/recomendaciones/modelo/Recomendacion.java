package com.mountainbackend.recomendaciones.modelo;

import java.util.List;

/**
 * Producto recomendado + explicacion. Se aplana el producto para que el
 * frontend lo consuma con el mismo shape que /products.
 */
public record Recomendacion(
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
		List<String> colors,
		int score,
		String reason) {

	public static Recomendacion desde(Producto producto, int score, String reason) {
		return new Recomendacion(
			producto.id(), producto.name(), producto.description(), producto.price(),
			producto.image(), producto.membrane(), producto.tempRating(), producto.waterproof(),
			producto.weight(), producto.activity(), producto.category(), producto.brand(),
			producto.colors(), score, reason);
	}
}
