FROM amazoncorretto:25
WORKDIR /app

COPY target/photo-app-albums-service-0.0.1-SNAPSHOT.jar AlbumsService.jar

ENTRYPOINT ["java","-jar","AlbumsService.jar"]
