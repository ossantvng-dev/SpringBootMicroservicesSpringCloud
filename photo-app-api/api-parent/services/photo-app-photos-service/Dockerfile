FROM amazoncorretto:25
WORKDIR /app

COPY target/photo-app-photos-service-0.0.1-SNAPSHOT.jar PhotosService.jar

ENTRYPOINT ["java","-jar","PhotosService.jar"]
