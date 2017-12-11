FROM openjdk:8-jre-alpine
MAINTAINER Akvo Foundation <devops@akvo.org>

WORKDIR /app
COPY target/akvo-flow-maps.jar /app/akvo-flow-maps.jar
COPY maybe-import-and-java-jar.sh /app/maybe-import-and-java-jar.sh
RUN chmod 777 /app/maybe-import-and-java-jar.sh

CMD ./maybe-import-and-java-jar.sh
