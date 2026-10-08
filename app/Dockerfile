# Creat_ 서버 이미지. jar는 GitHub Actions에서 ./gradlew bootJar 로 먼저 만든다.
FROM eclipse-temurin:21-jre

WORKDIR /app
COPY build/libs/mainblog-*.jar /app/app.jar

ENV TZ=Asia/Seoul \
    UPLOAD_DIR=/app/data/uploads \
    JAVA_OPTS="-Xms256m -Xmx512m"

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
