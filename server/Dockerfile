FROM eclipse-temurin:25
WORKDIR /opt/messaging-server
COPY build/libs/server-0.1.1.jar messaging-server.jar
EXPOSE 2413
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "messaging-server.jar"]
