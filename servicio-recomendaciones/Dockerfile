# Imagen de ejecucion. Requiere el jar generado por Maven:
#   ./mvnw -pl servicio-recomendaciones package
#   docker build -t pedidos360/servicio-recomendaciones:latest servicio-recomendaciones/
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
LABEL org.opencontainers.image.title="pedidos360-servicio-recomendaciones"
LABEL org.opencontainers.image.description="Pedidos360 - Recomendaciones personalizadas, tendencia y relacionadas"
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} app.jar
ENV SERVER_PORT=8080
ENV JAVA_OPTS=""
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
