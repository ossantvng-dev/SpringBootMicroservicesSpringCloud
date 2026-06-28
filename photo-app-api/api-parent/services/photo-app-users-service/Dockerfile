FROM amazoncorretto:25
WORKDIR /app

COPY target/photo-app-users-service-0.0.1-SNAPSHOT.jar UsersService.jar

ENTRYPOINT ["java","-jar","UsersService.jar"]
