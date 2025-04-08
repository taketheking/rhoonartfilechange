# 루나르트 파일 변환 프로그램

## JAR 파일 빌드 방법

### 필요 조건
- Java JDK 11 이상
- Maven

### 빌드 단계

1. Maven을 사용하여 프로젝트 빌드:
```bash
mvn clean package
```

2. 빌드가 완료되면 다음 위치에 실행 가능한 JAR 파일이 생성됩니다:
   - 생성된 JAR 파일 위치: `target/plplsettlement-1.0.0.jar`

## 실행 방법

### macOS/Linux에서 실행
```bash
java -jar target/plplsettlement-1.0.0.jar
```

### 바로가기 스크립트 생성 (선택사항)
macOS나 Linux에서 더 쉽게 실행할 수 있도록 실행 스크립트를 만들 수 있습니다:

1. 다음 내용으로 `run.sh` 파일을 생성합니다:
```bash
#!/bin/bash
java -jar plplsettlement-1.0.0.jar
```

2. 실행 권한을 부여합니다:
```bash
chmod +x run.sh
```

3. 이제 `./run.sh`로 프로그램을 실행할 수 있습니다.

## Windows용 EXE 변환 (별도 작업)

Windows 사용자를 위한 EXE 파일이 필요한 경우, 다음 방법을 사용할 수 있습니다:

1. Windows 환경에서 Launch4j와 같은 도구를 사용하여 JAR를 EXE로 변환
2. 또는 JPACKAGE 도구 사용 (JDK 14 이상):
```bash
jpackage --input target/ --main-jar plplsettlement-1.0.0.jar --main-class org.springframework.boot.loader.JarLauncher --type exe --name "RhoonartFileProcessor" --app-version 1.0.0
```

## 사용 방법

1. JAR 파일을 실행하면 자동으로 기본 브라우저가 열리며 웹 인터페이스가 표시됩니다.
2. 브라우저가 자동으로 열리지 않는 경우, http://localhost:8080 으로 접속하세요.

## 참고사항

- 기본 포트가 8080으로 설정되어 있습니다. 다른 포트를 사용하려면 다음과 같이 실행하세요:
  ```bash
  java -jar plplsettlement-1.0.0.jar --server.port=9090
  ```
- 실행 시 Java가 설치되어 있어야 합니다.
