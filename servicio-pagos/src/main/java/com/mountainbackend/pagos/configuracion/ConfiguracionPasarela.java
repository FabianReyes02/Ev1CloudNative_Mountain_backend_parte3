package com.mountainbackend.pagos.configuracion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.mountainbackend.pagos.pasarela.PasarelaWebPay;
import com.mountainbackend.pagos.pasarela.WebPayPlusCliente;
import com.mountainbackend.pagos.pasarela.WebPaySimulado;

/** Elige WebPay real o simulado segun las credenciales configuradas. */
@Configuration
public class ConfiguracionPasarela {

	private static final Logger log = LoggerFactory.getLogger(ConfiguracionPasarela.class);

	@Bean
	public PasarelaWebPay pasarelaWebPay(PropiedadesWebPay propiedades) {
		if (propiedades.tieneCredenciales()) {
			log.info("WebPay Plus REAL activo (entorno={})", propiedades.getEntorno());
			return new WebPayPlusCliente(propiedades);
		}
		log.warn("WebPay SIMULADO activo: configura WEBPAY_ENABLED=true + credenciales para usar Transbank.");
		return new WebPaySimulado();
	}
}
