FROM gradle:8.10.2-jdk17-focal AS build
WORKDIR /app
COPY . /app
RUN ./gradlew buildImage

FROM openjdk:17-slim
WORKDIR /app
COPY --from=build /app/build/libs/arknights-gacha-recorder-0.0.1.jar /app/myapp.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/myapp.jar"]