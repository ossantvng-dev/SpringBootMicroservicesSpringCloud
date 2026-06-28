FROM amazoncorretto:25
WORKDIR /app

COPY target/photo-app-accounts-service-0.0.1-SNAPSHOT.jar AccountsService.jar

ENTRYPOINT ["java","-jar","AccountsService.jar"]
