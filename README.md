# exchanging - Document Format Converter

A document format conversion tool supporting TXT, HTML, and PDF formats, built with Java Spring Boot.

## Features
- Convert documents between TXT, HTML, and PDF
- Web-based interface for easy file upload & conversion
- Batch conversion support
- Download converted files directly

## Tech Stack
- Java 17+
- Spring Boot
- Maven
- Thymeleaf (templates)

## Project Structure
`
exchanging/
  src/main/java/          # Java source code
    controller/           # Web controllers
    service/              # Conversion service
  src/main/resources/
    templates/            # Thymeleaf HTML templates
    static/               # CSS & JavaScript
    application.properties # Configuration
  pom.xml                 # Maven configuration
  start.bat               # One-click launcher
`

## Setup
`ash
mvn clean package
java -jar target/document-converter-1.0.0.jar
`

Or double-click start.bat.

## Usage
1. Open http://localhost:8080 in browser
2. Upload a document (TXT/HTML/PDF)
3. Select target format
4. Download the converted file

## License
MIT
