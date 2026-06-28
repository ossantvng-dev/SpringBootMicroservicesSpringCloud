FROM amazoncorretto:25
WORKDIR /app

# Copy the compiled JAR from the target directory.
COPY target/photo-app-discovery-service-0.0.1-SNAPSHOT.jar DiscoveryService.jar

# Run the Config Server
ENTRYPOINT ["java","-jar","DiscoveryService.jar"]