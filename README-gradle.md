# Gradle Build Instructions

This project has been converted to use Gradle as the build system.

## Prerequisites
- Java 21 or later
- Gradle Wrapper (included) or Gradle 8.5+

## Build Commands

### Build the project
```bash
# On Windows
./gradlew.bat build

# On Linux/Mac
./gradlew build
```

### Run tests
```bash
./gradlew test
```

### Build the application JAR
```bash
./gradlew bootJar
```

The built JAR will be located at `build/libs/smpp-mls-0.1.0.jar`

### Run the application
```bash
./gradlew bootRun
```

## Maven to Gradle Conversion Notes
- All Maven dependencies have been converted to Gradle format
- Java 21 compatibility is configured
- Spring Boot and other plugins are properly configured
- The build produces an executable JAR with all dependencies included

## Project Structure
- Source code: `src/main/java`
- Resources: `src/main/resources`
- Test code: `src/test/java`
- Build output: `build/`

## Dependencies
- Spring Boot 3.3.0
- PostgreSQL JDBC Driver
- CloudHopper SMPP 5.0.4
- Lombok
- Spring Boot Test (for testing)
