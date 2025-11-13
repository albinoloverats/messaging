FROM openjdk:25-jdk-slim
WORKDIR /opt/messaging-server
COPY build/libs/server-0.0.1-SNAPSHOT.jar messaging-server.jar
EXPOSE 2413
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "messaging-server.jar"]

# docker build -t messaging-server:latest server
# docker tag messaging-server:latest localhost:5000/messaging-server:latest
# docker push localhost:5000/messaging-server:latest
