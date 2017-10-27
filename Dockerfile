FROM openjdk:8-jre-alpine
MAINTAINER Akvo Foundation <devops@akvo.org>

WORKDIR /app
COPY target/akvo-flow-maps.jar /app/akvo-flow-maps.jar

CMD ["java", "-jar", "akvo-flow-maps.jar"]
