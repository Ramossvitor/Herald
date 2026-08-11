# ---- Build ------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace

# Resolve dependencies in their own layer so source edits don't re-download.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src src
RUN mvn -B -q -DskipTests package

# ---- Optimize ---------------------------------------------------------
# Extract the fat jar and run a CDS training run: the JVM records every class
# it loads while the context starts, and reuses that archive on real boots.
# On a fraction of a vCPU this cuts startup dramatically.
FROM eclipse-temurin:25-jre-noble AS optimize
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --destination extracted
WORKDIR /app/extracted
# The cds profile starts (and immediately exits) the context with no database.
# A failed training run only costs the optimization, never the image.
RUN java -XX:ArchiveClassesAtExit=app.jsa \
      -Dspring.profiles.active=cds -Dspring.context.exit=onRefresh \
      -jar app.jar || true

# ---- Run --------------------------------------------------------------
FROM eclipse-temurin:25-jre-noble
RUN useradd --system --no-create-home herald
USER herald
WORKDIR /app
COPY --from=optimize /app/extracted /app

# Sized for a 512 MB container: modest heap ceiling, capped metaspace, and the
# serial collector — at these sizes a concurrent GC only spends memory and
# threads. -Xshare:auto ignores a missing archive instead of failing.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=55 -XX:MaxMetaspaceSize=120m -XX:+UseSerialGC -Xss512k -Xshare:auto -XX:SharedArchiveFile=app.jsa"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
