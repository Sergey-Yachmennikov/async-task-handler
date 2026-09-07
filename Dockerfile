# Многоступенчатая сборка: инструменты сборки не попадают в финальный образ.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Зависимости выкачиваются отдельным слоем, до копирования исходников:
# правка кода не приводит к повторной загрузке всего репозитория Maven
COPY pom.xml ./
RUN mvn -B dependency:go-offline

COPY src ./src
# Тесты здесь пропущены сознательно: им нужен Docker для Testcontainers,
# а внутри сборки образа его нет. Прогон тестов — задача CI, до сборки образа
RUN mvn -B clean package -DskipTests


FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Запуск не от root: процессу приложения права суперпользователя не нужны
RUN addgroup -S app && adduser -S -G app app

COPY --from=build /build/target/*.jar app.jar
RUN chown -R app:app /app
USER app

EXPOSE 8080

# Контейнеру отдаётся доля памяти хоста, а не фиксированное значение:
# так один и тот же образ ведёт себя корректно при разных лимитах
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
