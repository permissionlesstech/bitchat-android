FROM openjdk:17-alpine

# Устанавливаем Kotlin
RUN apk add --no-cache curl && \
    curl -s https://get.sdkman.io | bash && \
    source "$HOME/.sdkman/bin/sdkman-init.sh" && \
    sdk install kotlin

# Создаем рабочую директорию
WORKDIR /app

# Копируем исходные файлы
COPY BitchatTcpServer.kt .
COPY README.md .

# Компилируем сервер
RUN kotlinc BitchatTcpServer.kt -include-runtime -d bitchat-server.jar

# Создаем пользователя для запуска
RUN addgroup -g 1000 bitchat && \
    adduser -D -s /bin/sh -u 1000 -G bitchat bitchat

# Меняем владельца файлов
RUN chown -R bitchat:bitchat /app

# Переключаемся на непривилегированного пользователя
USER bitchat

# Открываем порт
EXPOSE 9090

# Запускаем сервер
CMD ["java", "-jar", "bitchat-server.jar"]