# ---- Build stage ----
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy Maven descriptor first to leverage caching
COPY pom.xml /app/pom.xml
RUN mvn -q -DskipTests dependency:go-offline

# Copy sources and build shaded JAR named java-mcp-server.jar
COPY src /app/src
RUN mvn -q -DskipTests package

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# Allow the server to access the mock repository
COPY MockRepository /app/MockRepository

# Copy final jar
COPY --from=build /app/target/java-mcp-server.jar /app/java-mcp-server.jar

# Non-root user
RUN useradd -m mcpuser && chown -R mcpuser:mcpuser /app
USER mcpuser

# MCP runs over stdio; no ports needed
ENTRYPOINT ["java","-Dorg.slf4j.simpleLogger.logFile=System.err","-jar","/app/java-mcp-server.jar"]
ENV JAVA_TOOL_OPTIONS="-Dorg.slf4j.simpleLogger.logFile=System.err"