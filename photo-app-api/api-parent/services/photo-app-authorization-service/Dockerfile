FROM amazoncorretto:25
WORKDIR /app

COPY target/photo-app-authorization-service-0.0.1-SNAPSHOT.jar AuthorizationService.jar

ENTRYPOINT ["java","-jar","AuthorizationService.jar"]
