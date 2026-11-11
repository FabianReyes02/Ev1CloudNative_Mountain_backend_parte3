package com.mountainbackend.pagos.configuracion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Umbrales del detector de fraude. El score es 0-100:
 *  - menor que umbralAprobado  -> APROBADO
 *  - entre ambos umbrales      -> REVISION
 *  - mayor o igual a umbralRevision -> RECHAZADO / FRAUDE
 */
@ConfigurationProperties(prefix = "app.fraude")
public class PropiedadesFraude {

	private int umbralAprobado = 40;
	private int umbralRevision = 70;

	public int getUmbralAprobado() {
		return umbralAprobado;
	}

	public void setUmbralAprobado(int umbralAprobado) {
		this.umbralAprobado = umbralAprobado;
	}

	public int getUmbralRevision() {
		return umbralRevision;
	}

	public void setUmbralRevision(int umbralRevision) {
		this.umbralRevision = umbralRevision;
	}
}
