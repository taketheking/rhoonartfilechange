package com.rhoonart.plplsettlement.controller;

import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.IOUtils;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.w3c.dom.Element;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/excel")
public class ExcelSplitController {

    private static final int MAX_DEFAULT_SIZE_MB = 30;
    private static final int MAX_ALLOWED_SIZE_MB = 100;

    static {
        // Apache POI의 바이트 배열 최대 크기 제한을 늘림 (250MB)
        IOUtils.setByteArrayMaxOverride(250 * 1024 * 1024);
    }

    @Value("${excel.output.dir:#{systemProperties['java.io.tmpdir']}}")
    private String outputDir;

    @PostMapping("/split")
    public ResponseEntity<?> splitExcelToCSV(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "maxSizeMB", defaultValue = "30") int maxSizeMB) {

        // 요청 검증
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(createErrorResponse("파일이 비어 있습니다."));
        }

        if (!file.getOriginalFilename().toLowerCase().endsWith(".xlsx")) {
            return ResponseEntity.badRequest().body(createErrorResponse("XLSX 형식의 파일만 지원합니다."));
        }

        // 요청된 크기가 허용 범위 내인지 확인
        if (maxSizeMB <= 0 || maxSizeMB > MAX_ALLOWED_SIZE_MB) {
            maxSizeMB = MAX_DEFAULT_SIZE_MB;
        }

        long maxSizeBytes = maxSizeMB * 1024L * 1024L;
        String originalFilename = file.getOriginalFilename();
        String baseFilename = originalFilename.substring(0, originalFilename.lastIndexOf("."));
        String sessionId = UUID.randomUUID().toString();
        String sessionDir = outputDir + File.separator + sessionId;

        try {
            // 세션 디렉토리 생성
            Path sessionPath = Paths.get(sessionDir);
            Files.createDirectories(sessionPath);

            // 임시 파일로 저장
            Path tempFile = sessionPath.resolve("temp_" + originalFilename);
            file.transferTo(tempFile.toFile());
            
            List<Map<String, Object>> resultFiles = processExcelFile(tempFile, sessionPath, baseFilename, maxSizeBytes);
            
            // 임시 파일 삭제
            Files.deleteIfExists(tempFile);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("files", resultFiles);
            response.put("sessionId", sessionId);
            response.put("message", resultFiles.size() + "개의 CSV 파일을 생성했습니다.");
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body(createErrorResponse("파일 처리 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }

    @GetMapping("/download")
    public ResponseEntity<byte[]> downloadFile(@RequestParam("file") String fileId) {
        try {
            String[] parts = fileId.split(":");
            if (parts.length != 2) {
                return ResponseEntity.badRequest().build();
            }

            String sessionId = parts[0];
            String filename = parts[1];
            
            Path filePath = Paths.get(outputDir, sessionId, filename);
            if (!Files.exists(filePath)) {
                return ResponseEntity.notFound().build();
            }

            byte[] fileContent = Files.readAllBytes(filePath);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("text/csv"));
            
            // RFC 5987 형식으로 파일명 인코딩
            String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8.toString())
                .replace("+", "%20");
            
            headers.set(HttpHeaders.CONTENT_DISPOSITION, 
                        "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename);
            
            headers.setCacheControl("must-revalidate, post-check=0, pre-check=0");
            
            return ResponseEntity.ok()
                    .headers(headers)
                    .contentLength(fileContent.length)
                    .body(fileContent);
                    
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/download-all")
    public ResponseEntity<byte[]> downloadAllFiles(@RequestParam(value = "sessionId", required = false) String sessionId) {
        try {
            if (sessionId == null || sessionId.isEmpty()) {
                // URI에서 sessionId 파라미터가 없는 경우
                return ResponseEntity.badRequest()
                        .body("세션 ID가 필요합니다.".getBytes(StandardCharsets.UTF_8));
            }
            
            Path sessionDir = Paths.get(outputDir, sessionId);
            if (!Files.exists(sessionDir)) {
                return ResponseEntity.notFound().build();
            }

            // 세션 디렉토리에서 파일 목록을 가져와 원본 파일명 확인
            List<String> fileList = Files.list(sessionDir)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".csv"))
                    .collect(java.util.stream.Collectors.toList());

            // 원본 파일명 추출 (첫 번째 파일명에서 추출)
            String originalFileName = "";
            if (!fileList.isEmpty()) {
                String firstFileName = fileList.get(0);
                int underscoreIndex = firstFileName.indexOf("_split_");
                if (underscoreIndex > 0) {
                    originalFileName = firstFileName.substring(0, underscoreIndex);
                }
            }

            // 압축 파일명 설정 (원본 파일명 + "_압축")
            String zipFileName = (originalFileName.isEmpty() ? "excel" : originalFileName) + "_압축.zip";
            Path zipPath = Paths.get(outputDir, sessionId + ".zip");
            
            // UTF-8 인코딩을 명시적으로 지정하는 ZipOutputStream 설정
            try (ZipOutputStream zipOut = new ZipOutputStream(
                                            new BufferedOutputStream(
                                                new FileOutputStream(zipPath.toFile())), 
                                            StandardCharsets.UTF_8)) {
                
                // ZIP 파일 내부의 한글 파일명을 위한 설정
                zipOut.setMethod(ZipOutputStream.DEFLATED);
                zipOut.setLevel(9); // 최대 압축률
                
                Files.list(sessionDir).forEach(filePath -> {
                    try {
                        String fileName = filePath.getFileName().toString();
                        
                        // 한글 파일명을 위한 새로운 ZipEntry 생성 방식
                        ZipEntry zipEntry = new ZipEntry(fileName);
                        zipOut.putNextEntry(zipEntry);
                        
                        // 파일 내용 복사
                        try (InputStream in = new BufferedInputStream(new FileInputStream(filePath.toFile()))) {
                            byte[] buffer = new byte[8192];
                            int len;
                            while ((len = in.read(buffer)) > 0) {
                                zipOut.write(buffer, 0, len);
                            }
                        }
                        
                        zipOut.closeEntry();
                    } catch (IOException e) {
                        throw new RuntimeException("ZIP 파일 생성 중 오류 발생: " + e.getMessage(), e);
                    }
                });
            }
            
            byte[] zipContent = Files.readAllBytes(zipPath);
            
            // 임시 ZIP 파일 삭제
            Files.delete(zipPath);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/zip"));
            
            // RFC 5987 형식으로 파일명 인코딩
            String encodedFilename = URLEncoder.encode(zipFileName, StandardCharsets.UTF_8.toString())
                .replace("+", "%20");
            
            headers.set(HttpHeaders.CONTENT_DISPOSITION, 
                        "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename);
            
            return ResponseEntity.ok()
                    .headers(headers)
                    .contentLength(zipContent.length)
                    .body(zipContent);
                    
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().build();
        }
    }

    private List<Map<String, Object>> processExcelFile(Path excelFilePath, Path sessionPath, String baseFilename, long maxSizeBytes) 
            throws IOException, OpenXML4JException, SAXException, ParserConfigurationException {
        
        List<Map<String, Object>> resultFiles = new ArrayList<>();
        
        // 파일 접근 방식 변경: 메모리 효율적으로 처리하기 위해 OPCPackage 직접 사용 대신 파일 경로 기반으로 열기
        try (OPCPackage pkg = OPCPackage.open(excelFilePath.toFile(), PackageAccess.READ)) {
            XSSFReader reader = new XSSFReader(pkg);
            StylesTable styles = reader.getStylesTable();
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
            
            Iterator<InputStream> sheetsData = reader.getSheetsData();
            
            int sheetIndex = 0;
            while (sheetsData.hasNext()) {
                try (InputStream sheetStream = sheetsData.next()) {
                    sheetIndex++;
                    String sheetName = "Sheet" + sheetIndex;
                    
                    System.out.println("처리 중인 시트: " + sheetName);
                    
                    // CSV 분할 파일을 처리할 핸들러 생성
                    CsvSplitSheetHandler handler = new CsvSplitSheetHandler(
                            sessionPath, baseFilename + "_" + sheetName, maxSizeBytes);
                    
                    processSheet(styles, strings, handler, sheetStream);
                    
                    // 생성된 파일 정보 수집
                    handler.getCreatedFiles().forEach((fileName, size) -> {
                        Map<String, Object> fileInfo = new HashMap<>();
                        fileInfo.put("name", fileName);
                        fileInfo.put("size", size);
                        fileInfo.put("id", sessionPath.getFileName() + ":" + fileName);
                        resultFiles.add(fileInfo);
                    });
                }
            }
        }
        
        return resultFiles;
    }

    private void processSheet(StylesTable styles, ReadOnlySharedStringsTable strings, 
                             CsvSplitSheetHandler handler, InputStream sheetData) 
                             throws IOException, SAXException, ParserConfigurationException {
        
        DataFormatter formatter = new DataFormatter();
        InputSource sheetSource = new InputSource(sheetData);
        
        XMLReader sheetParser = XMLHelper.newXMLReader();
        
        // 메모리 사용량을 줄이기 위한 SAX 파서 설정
        try {
            sheetParser.setFeature("http://apache.org/xml/features/namespaces", false);
            sheetParser.setFeature("http://apache.org/xml/features/dom/defer-node-expansion", true);
            sheetParser.setFeature("http://xml.org/sax/features/namespaces", false);
            sheetParser.setFeature("http://xml.org/sax/features/validation", false);
            sheetParser.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (SAXException e) {
            System.out.println("SAX 파서 설정 중 일부 옵션을 설정할 수 없습니다: " + e.getMessage());
            // 오류가 발생해도 계속 진행
        }
        
        // 마지막 행 확인 - SAX 파싱으로는 직접 확인이 어려우므로 전처리로 시트 내용 검사
        try {
            // sheetData를, 마지막 행 번호 찾기 위해 별도 분석 필요
            // XSSFReader를 통해 읽은 XML 데이터에서 마지막 행 찾기
            // <row> 태그의 최대 r 속성 값을 찾음
            ByteArrayOutputStream tempOut = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int readLen;
            
            // sheetData 스트림의 내용을 복제
            while ((readLen = sheetData.read(buffer)) != -1) {
                tempOut.write(buffer, 0, readLen);
            }
            
            // 원본 스트림 복원
            byte[] sheetBytes = tempOut.toByteArray();
            InputStream originalInputStream = new ByteArrayInputStream(sheetBytes);
            InputStream copyInputStream = new ByteArrayInputStream(sheetBytes);
            
            // 마지막 행 번호 찾기
            int maxRowIndex = -1;
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(false);
                factory.setValidating(false);
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                
                DocumentBuilder builder = factory.newDocumentBuilder();
                Document document = builder.parse(new InputSource(copyInputStream));
                
                NodeList rows = document.getElementsByTagName("row");
                for (int i = 0; i < rows.getLength(); i++) {
                    Element row = (Element) rows.item(i);
                    int rowIndex = Integer.parseInt(row.getAttribute("r")) - 1; // r 속성은 1부터 시작, 내부적으로는 0부터
                    if (rowIndex > maxRowIndex) {
                        maxRowIndex = rowIndex;
                    }
                }
            } catch (Exception e) {
                System.out.println("마지막 행 분석 중 오류: " + e.getMessage());
                // 오류가 발생해도 계속 진행
            }
            
            // 마지막 행 번호 설정
            if (maxRowIndex >= 0) {
                System.out.println("시트의 마지막 행 번호: " + maxRowIndex);
                handler.setLastRowNum(maxRowIndex);
            }
            
            // 원본 스트림으로 실제 파싱 진행
            sheetSource = new InputSource(originalInputStream);
        } catch (Exception e) {
            System.out.println("마지막 행 검출 중 오류: " + e.getMessage());
            // 오류 발생 시 원본 스트림 사용
        }
        
        ContentHandler contentHandler = new XSSFSheetXMLHandler(
                styles, strings, handler, formatter, false);
        sheetParser.setContentHandler(contentHandler);
        
        sheetParser.parse(sheetSource);
    }

    private Map<String, Object> createErrorResponse(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message);
        return response;
    }

    /**
     * XLSX 파일의 시트를 CSV로 변환하고 지정된 크기로 분할하는 핸들러
     */
    private static class CsvSplitSheetHandler implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final Path outputPath;
        private final String baseFilename;
        private final long maxFileSize;
        
        private List<String> currentRow;
        private int currentRowNum = 0;
        private List<String> headers;
        private Map<String, Long> createdFiles = new HashMap<>();
        
        private Writer currentWriter;
        private File currentFile;
        private int fileIndex = 1;
        private long currentFileSize = 0;
        private int lastRowNum = -1; // 마지막 행 번호를 저장하기 위한 변수
        private boolean isProcessingLastRow = false; // 현재 처리 중인 행이 마지막 행인지 여부
        
        public CsvSplitSheetHandler(Path outputPath, String baseFilename, long maxFileSize) {
            this.outputPath = outputPath;
            // 파일명에서 한글 및 특수문자를 영문 및 숫자로 대체하여 안전한 파일명 생성
            this.baseFilename = sanitizeFilename(baseFilename);
            this.maxFileSize = maxFileSize;
        }
        
        // 안전한 파일명 생성 메서드
        private String sanitizeFilename(String input) {
            // 타임스탬프 추가
            String timestamp = String.valueOf(System.currentTimeMillis());
            
            // 허용되지 않는 문자 제거 (ASCII 범위 외의 문자와 파일명에 사용할 수 없는 특수문자)
            String sanitized = input.replaceAll("[^a-zA-Z0-9가-힣._-]", "_");
            
            // 결과가 너무 길면 잘라내기
            if (sanitized.length() > 50) {
                sanitized = sanitized.substring(0, 50);
            }
            
            // 타임스탬프를 추가하여 고유한 파일명 보장
            return sanitized + "_" + timestamp;
        }
        
        @Override
        public void startRow(int rowNum) {
            currentRowNum = rowNum;
            currentRow = new ArrayList<>();
            // 현재 행이 마지막 행인지 확인
            checkLastRow(rowNum);
        }
        
        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            if (formattedValue == null) {
                formattedValue = "";
            }
            
            // 셀 레퍼런스에서 컬럼 인덱스 추출
            CellReference ref = new CellReference(cellReference);
            int thisCol = ref.getCol();
            
            // 중간에 빈 셀이 있는 경우 채우기
            while (currentRow.size() < thisCol) {
                currentRow.add("");
            }
            
            // CSV 용으로 포맷팅
            formattedValue = escapeCsvValue(formattedValue);
            currentRow.add(formattedValue);
        }
        
        @Override
        public void headerFooter(String text, boolean isHeader, String tagName) {
            // 사용하지 않음
        }
        
        @Override
        public void endRow(int rowNum) {
            try {
                // 헤더 처리
                if (rowNum == 0) {
                    headers = new ArrayList<>(currentRow);
                    return; // 헤더는 아직 파일에 쓰지 않음
                }
                
                // 마지막 행을 처리하고 있는지 확인
                if (isProcessingLastRow) {
                    System.out.println("마지막 행(" + rowNum + ")은 처리하지 않습니다.");
                    return; // 마지막 행은 처리하지 않음
                }
                
                // 새 파일이 필요한 경우
                if (currentWriter == null) {
                    createNewFile();
                    
                    // 헤더 쓰기
                    String headerLine = String.join(",", headers);
                    currentWriter.write(headerLine);
                    currentWriter.write("\n");
                    
                    // 헤더 크기 계산
                    byte[] headerBytes = (headerLine + "\n").getBytes(StandardCharsets.UTF_8);
                    currentFileSize += headerBytes.length;
                }
                
                // 현재 행 쓰기
                String line = String.join(",", currentRow);
                byte[] lineBytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
                
                // 현재 파일 크기 + 새 라인 크기가 최대 크기를 초과하는 경우 새 파일 생성
                if (currentFileSize + lineBytes.length > maxFileSize) {
                    closeCurrentFile();
                    createNewFile();
                    
                    // 헤더 쓰기
                    String headerLine = String.join(",", headers);
                    currentWriter.write(headerLine);
                    currentWriter.write("\n");
                    
                    // 헤더 크기 계산
                    byte[] headerBytes = (headerLine + "\n").getBytes(StandardCharsets.UTF_8);
                    currentFileSize = headerBytes.length;
                }
                
                // 행 데이터 쓰기
                currentWriter.write(line);
                currentWriter.write("\n");
                currentFileSize += lineBytes.length;
                
            } catch (IOException e) {
                throw new RuntimeException("CSV 파일 쓰기 오류", e);
            }
        }
        
        private void createNewFile() throws IOException {
            String filename = baseFilename + "_split_" + fileIndex + ".csv";
            Path filePath = outputPath.resolve(filename);
            currentFile = filePath.toFile();
            currentWriter = new OutputStreamWriter(new FileOutputStream(currentFile), StandardCharsets.UTF_8);
            fileIndex++;
        }
        
        private void closeCurrentFile() throws IOException {
            if (currentWriter != null) {
                currentWriter.flush();
                currentWriter.close();
                
                // 파일 크기 기록
                String filename = currentFile.getName();
                createdFiles.put(filename, currentFile.length());
                
                currentWriter = null;
                currentFile = null;
                currentFileSize = 0;
            }
        }
        
        @Override
        public void endSheet() {
            try {
                closeCurrentFile();
            } catch (IOException e) {
                throw new RuntimeException("CSV 파일 닫기 오류", e);
            }
        }
        
        // 마지막 행 번호 설정 메소드 추가
        public void setLastRowNum(int lastRowNum) {
            this.lastRowNum = lastRowNum;
        }
        
        // 현재 처리 중인 행이 마지막 행인지 확인하는 메소드
        public void checkLastRow(int rowNum) {
            if (lastRowNum != -1 && rowNum == lastRowNum) {
                isProcessingLastRow = true;
                System.out.println("마지막 행 감지: " + rowNum);
            }
        }
        
        private String escapeCsvValue(String value) {
            if (value == null) {
                return "";
            }
            
            // 쌍따옴표나 쉼표가 포함된 경우 쌍따옴표로 감싸기
            if (value.contains("\"") || value.contains(",") || value.contains("\n")) {
                // 내부의 쌍따옴표는 두 번 반복하여 이스케이프
                value = value.replace("\"", "\"\"");
                return "\"" + value + "\"";
            }
            
            return value;
        }
        
        public Map<String, Long> getCreatedFiles() {
            return createdFiles;
        }
    }
} 