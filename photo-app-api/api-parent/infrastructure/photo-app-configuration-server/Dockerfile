FROM amazoncorretto:25
WORKDIR /app

# Copy the PKCS12 keystore from resources
COPY src/main/resources/keystore.p12 keystore.p12

# Copy the compiled JAR from the target directory.
COPY target/photo-app-configuration-server-0.0.1-SNAPSHOT.jar ConfigServer.jar

# Run the Config Server
ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-jar","ConfigServer.jar"]
