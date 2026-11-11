package com.mountainbackend.recomendaciones.servicio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mountainbackend.recomendaciones.modelo.PerfilUsuario;
import com.mountainbackend.recomendaciones.modelo.Producto;
import com.mountainbackend.recomendaciones.modelo.Recomendacion;

class MotorRecomendacionesTest {

	private final MotorRecomendaciones motor = new MotorRecomendaciones();

	private Producto producto(long id, String nombre, String categoria, String marca, List<String> actividades) {
		return new Producto(id, nombre, "desc", 10_000, "img", "Gore-Tex", "-10°C", "Sí", "500g",
			actividades, categoria, marca, List.of("Negro"));
	}

	private List<Producto> catalogo() {
		return List.of(
			producto(1, "Chaqueta Alpina", "Abrigo", "Summit", List.of("Montaña")),
			producto(2, "Chaqueta Andina", "Abrigo", "Summit", List.of("Montaña")),
			producto(3, "Mochila Trek", "Mochilas", "Trail", List.of("Trekking")),
			producto(4, "Botas Snow", "Calzado", "Alpine", List.of("Nieve")));
	}

	@Test
	void personalizadasExcluyeLoCompradoYPriorizaAfinidad() {
		PerfilUsuario perfil = new PerfilUsuario("cliente@summitlab.cl");
		perfil.registrarCompra(java.util.Map.of(1L, 2));

		List<Recomendacion> resultado = motor.personalizadas(perfil, catalogo(), 3);

		assertThat(resultado).isNotEmpty();
		assertThat(resultado).extracting(Recomendacion::id).doesNotContain(1L);
		assertThat(resultado.get(0).id()).isEqualTo(2L);
		assertThat(resultado.get(0).reason()).contains("categoria");
	}

	@Test
	void sinHistorialDevuelveTendenciaConTodoElCatalogo() {
		List<Recomendacion> resultado = motor.tendencia(java.util.Map.of(3L, 5), catalogo(), 10);

		assertThat(resultado).hasSize(4);
		assertThat(resultado.get(0).id()).isEqualTo(3L);
		assertThat(resultado.get(0).reason()).contains("Tendencia");
	}

	@Test
	void relacionadasPriorizaMismaCategoria() {
		List<Recomendacion> resultado = motor.relacionadas(1L, catalogo(), 4);

		assertThat(resultado).isNotEmpty();
		assertThat(resultado.get(0).id()).isEqualTo(2L);
		assertThat(resultado).extracting(Recomendacion::id).doesNotContain(1L);
	}

	@Test
	void limiteSeRespeta() {
		List<Recomendacion> resultado = motor.tendencia(java.util.Map.of(), catalogo(), 2);
		assertThat(resultado).hasSize(2);
	}
}
