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
import java.io.*;
import java.math.BigDecimal;
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
    private static final int MAX_ALLOWED_SIZE_MB = 500;
    // 업로드 처리 타임아웃 설정 (10분)
    private static final int UPLOAD_TIMEOUT_MS = 600000;

    static {
        // Apache POI의 바이트 배열 최대 크기 제한을 늘림 (500MB)
        IOUtils.setByteArrayMaxOverride(500 * 1024 * 1024);
        // 소켓 타임아웃 설정 증가
        System.setProperty("sun.net.client.defaultConnectTimeout", "300000");
        System.setProperty("sun.net.client.defaultReadTimeout", "300000");
    }

    @Value("${excel.output.dir:#{systemProperties['java.io.tmpdir']}}")
    private String outputDir;

    // 최종 정산금 총 합계
    private static List<Map<String, String>> finalAmounts;

    private static BigDecimal finalAmount = new BigDecimal(0);
    private static double KRW = 0;

    @PostMapping("/split")
    public ResponseEntity<?> splitExcelToCSV(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "maxSizeMB", defaultValue = "29") int maxSizeMB) {

        List<Map<String, String>> finalAmountsCopy = new ArrayList<>();
        finalAmounts = new ArrayList<>(finalAmountsCopy);


        // 요청 검증
        if (files == null || files.length == 0) {
            return ResponseEntity.badRequest().body(createErrorResponse("파일이 비어 있습니다."));
        }

        // 요청된 크기가 허용 범위 내인지 확인
        if (maxSizeMB <= 0 || maxSizeMB > MAX_ALLOWED_SIZE_MB) {
            maxSizeMB = MAX_DEFAULT_SIZE_MB;
        }

        long maxSizeBytes = maxSizeMB * 1024L * 1024L;
        String sessionId = UUID.randomUUID().toString();
        String sessionDir = outputDir + File.separator + sessionId;
        List<Map<String, Object>> allResultFiles = new ArrayList<>();

        try {
            // 세션 디렉토리 생성
            Path sessionPath = Paths.get(sessionDir);
            Files.createDirectories(sessionPath);

            // 각 파일 처리
            for (MultipartFile file : files) {
                if (!file.getOriginalFilename().toLowerCase().endsWith(".xlsx")) {
                    System.out.println("XLSX 형식이 아닌 파일 건너뜀: " + file.getOriginalFilename());
                    continue;
                }

                String originalFilename = file.getOriginalFilename();
                String baseFilename = originalFilename.substring(0, originalFilename.lastIndexOf("."));

            // 임시 파일로 저장
            Path tempFile = sessionPath.resolve("temp_" + originalFilename);
            file.transferTo(tempFile.toFile());
            
                // 파일 변환
            List<Map<String, Object>> resultFiles = processExcelFile(tempFile, sessionPath, baseFilename, maxSizeBytes);
                allResultFiles.addAll(resultFiles);
            
            // 임시 파일 삭제
            Files.deleteIfExists(tempFile);
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("files", allResultFiles);
            response.put("sessionId", sessionId);
            response.put("message", allResultFiles.size() + "개의 CSV 파일을 생성했습니다.");
            response.put("finalAmounts", finalAmounts);
            
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

    private List<Map<String, Object>> processExcelFile(Path excelFilePath, Path sessionPath, String baseFilename, long maxFileSize) 
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
                            sessionPath, baseFilename, maxFileSize);

//                    CsvSplitSheetHandler handler = new CsvSplitSheetHandler(
//                            sessionPath, baseFilename + "_" + sheetName, maxFileSize);
                    
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
        
        // 원본 형식 그대로 유지하는 DataFormatter 생성 (정확한 숫자 표현을 위해)
        DataFormatter formatter = new DataFormatter() {
            @Override
            public String formatRawCellContents(double value, int formatIndex, String formatString, boolean use1904Windowing) {
                // 현재 처리 중인 컬럼의 인덱스 추출
                if (handler.getCurrentRow() != null) {
                    int colIndex = handler.getCurrentRow().size();
                    
                    // Partner Revenue 컬럼인 경우 13자리에서 반올림하여 14자리로 표시
                    if (colIndex == handler.getPartnerRevenueIndex()) {
                        // 소수점 13자리에서 반올림하여 14자리 표시
                        BigDecimal bd = new BigDecimal(value);
                        bd = bd.setScale(13, java.math.RoundingMode.HALF_UP);
                        return bd.toPlainString() + "0";
                    }
                    // Partner Revenue (KRW) 또는 최종 정산금 컬럼인 경우 14자리까지 정확히 표시
                    else if (colIndex == handler.getPartnerRevenueKrwIndex()) {
                        return formatExactDecimal(value, 14);
                    }
                    // 최종 정산금 컬럼인 경우 15자리에서 반올림하여 14자리까지 표시
                    else if (colIndex == handler.getFinalSettlementIndex()) {
                        // 15자리에서 반올림하여 14자리 표시
                        BigDecimal bd = new BigDecimal(value);
                        bd = bd.setScale(14, java.math.RoundingMode.HALF_UP);
                        return bd.toPlainString();
                    }
                    // 정산요율 또는 실연권요율 컬럼인 경우 백분위(%)로 표시
                    else if (colIndex == handler.getSettlementRateIndex() || colIndex == handler.getPerformanceRightsRateIndex()) {
                        // 요율을 백분위(%)로 표시
                        BigDecimal bd = new BigDecimal(value);
                        // 소수점 4자리까지 유지하고 백분율로 변환 (100 곱하기)
                        bd = bd.multiply(new BigDecimal(100)).setScale(4, java.math.RoundingMode.HALF_UP);
                        // 소수점 뒤가 0인 경우 정수로 표시
                        String result = bd.stripTrailingZeros().toPlainString();
                        if (result.endsWith(".0")) {
                            result = result.substring(0, result.length() - 2);
                        }
                        return result + "%";
                    }
                }
                
                // 그 외의 작은 소수 값은 정밀하게 표현
                if (Math.abs(value) < 1.0 && value != 0) {
                    return formatExactDecimal(value, 14);
                }
                
                return super.formatRawCellContents(value, formatIndex, formatString, use1904Windowing);
            }
            
            // 소수점 자리수를 정확하게 표현하는 메서드
            private String formatExactDecimal(double value, int precision) {
                StringBuilder sb = new StringBuilder();
                
                // 소수점 앞 부분
                long intPart = (long) Math.abs(value);
                if (value < 0) sb.append('-');
                sb.append(intPart);
                sb.append('.');
                
                // 소수점 뒷 부분 (정확한 자리수 유지)
                double fracPart = Math.abs(value) - intPart;
                for (int i = 0; i < precision; i++) {
                    fracPart *= 10;
                    int digit = (int) fracPart;
                    sb.append(digit);
                    fracPart -= digit;
                }
                
                return sb.toString();
            }
        };
        
        InputSource sheetSource = new InputSource(sheetData);
        XMLReader sheetParser = XMLHelper.newXMLReader();
        
        // 메모리 사용량을 줄이기 위한 SAX 파서 설정
        try {
            sheetParser.setFeature("http://apache.org/xml/features/namespaces", false);
            sheetParser.setFeature("http://xml.org/sax/features/namespaces", false);
            sheetParser.setFeature("http://xml.org/sax/features/validation", false);
            sheetParser.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (SAXException e) {
            System.out.println("SAX 파서 설정 중 일부 옵션을 설정할 수 없습니다: " + e.getMessage());
            // 오류가 발생해도 계속 진행
        }
        
        // 핸들러 연결
        ContentHandler contentHandler = new XSSFSheetXMLHandler(
                styles, strings, handler, formatter, false);
        sheetParser.setContentHandler(contentHandler);
        
        // 시트 처리 시작 
        System.out.println("시트 처리 시작 - 정밀한 소수점 표현 설정으로 진행");
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
        private final int BUFFER_FLUSH_THRESHOLD = 1000; // 1000행마다 버퍼 플러시
        
        // 모든 데이터 행을 저장하는 리스트 (헤더 제외)
        private List<List<String>> allRows = new ArrayList<>();
        
        // Video ID 컬럼 인덱스 저장
        private int videoIdColumnIndex = -1;
        private int partnerRevenueIndex = -1;
        private int partnerRevenueKrwIndex = -1;
        private int finalSettlementIndex = -1;
        private int settlementRateIndex = -1; // 정산요율 컬럼 인덱스
        private int performanceRightsRateIndex = -1; // 실연권요율 컬럼 인덱스
        
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
        }
        
        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            if (formattedValue == null) {
                formattedValue = "";
            }
            
            // 컬럼 인덱스 파악
            CellReference ref = new CellReference(cellReference);
            int thisCol = ref.getCol();
            
            // 중간에 빈 셀이 있는 경우 채우기
            while (currentRow.size() < thisCol) {
                currentRow.add("");
            }

            // 첫 행(헤더)에서 특수 컬럼 인덱스 식별
            if (currentRowNum == 0) {
                if (formattedValue.contains("Video ID")) {
                    videoIdColumnIndex = thisCol;
                    System.out.println("Video ID 컬럼 발견: 인덱스 " + videoIdColumnIndex);
                } else if (formattedValue.equals("Partner Revenue")) {
                    partnerRevenueIndex = thisCol;
                    System.out.println("Partner Revenue 컬럼 발견: 인덱스 " + partnerRevenueIndex);
                } else if (formattedValue.equals("Partner Revenue (KRW)")) {
                    partnerRevenueKrwIndex = thisCol;
                    System.out.println("Partner Revenue (KRW) 컬럼 발견: 인덱스 " + partnerRevenueKrwIndex);
                } else if (formattedValue.contains("최종 정산금")) {
                    finalSettlementIndex = thisCol;
                    System.out.println("최종 정산금 컬럼 발견: 인덱스 " + finalSettlementIndex);
                } else if (formattedValue.contains("정산요율")) {
                    settlementRateIndex = thisCol;
                    System.out.println("정산요율 컬럼 발견: 인덱스 " + settlementRateIndex);
                } else if (formattedValue.contains("실연권요율")) {
                    performanceRightsRateIndex = thisCol;
                    System.out.println("실연권요율 컬럼 발견: 인덱스 " + performanceRightsRateIndex);
                }
            }
            
            // Partner Revenue 컬럼 특수 처리 - 13자리에서 반올림하여 14자리로 표시
            if (currentRowNum > 0 && thisCol == partnerRevenueIndex && !formattedValue.isEmpty()) {
                try {
                    // Partner Revenue 값 처리 (소수점 13자리에서 반올림)
                    String cleanValue = formattedValue.replace(",", "").trim();
                    
                    // 값이 숫자인지 확인
                    if (cleanValue.matches("-?[\\d.]+")) {
                        // BigDecimal로 변환하여 정확히 13자리에서 반올림
                        BigDecimal bd = new BigDecimal(cleanValue);
                        bd = bd.setScale(13, java.math.RoundingMode.HALF_UP);
                        
                        // 14자리로 표시하기 위해 0 추가
                        formattedValue = bd.toPlainString() + "0";
                        System.out.println("Partner Revenue 반올림 처리: " + cleanValue + " -> " + formattedValue);
                    }
                } catch (Exception e) {
                    System.out.println("Partner Revenue 값 처리 중 오류: " + e.getMessage());
                }
            }
            // Partner Revenue (KRW) 컬럼 특수 처리 - 소수점 14자리까지 정확히 표시
            else if (currentRowNum > 0 && thisCol == partnerRevenueKrwIndex && !formattedValue.isEmpty()) {
                try {
                    String cleanValue = formattedValue.replace(",", "").trim();
                    
                    // 값이 숫자인지 확인
                    if (cleanValue.matches("-?[\\d.]+")) {
                        // 소수점 포함 여부 확인
                        if (cleanValue.contains(".")) {
                            String[] parts = cleanValue.split("\\.");
                            String integerPart = parts[0];
                            String decimalPart = parts[1];
                            
                            // 소수점 이하 자릿수가 14자리가 되도록 처리
                            if (decimalPart.length() < 14) {
                                // 0으로 채우기
                                StringBuilder paddedDecimal = new StringBuilder(decimalPart);
                                while (paddedDecimal.length() < 14) {
                                    paddedDecimal.append("0");
                                }
                                formattedValue = integerPart + "." + paddedDecimal;
                            }
                        } else {
                            // 소수점이 없는 경우 소수점 14자리 추가
                            formattedValue = cleanValue + ".00000000000000";
                        }
                    }
                } catch (Exception e) {
                    System.out.println("Partner Revenue (KRW) 값 처리 중 오류: " + e.getMessage());
                }
            }
            // 최종 정산금 컬럼 특수 처리 - 소수점 15자리에서 반올림하여 14자리로 표시
            else if (currentRowNum > 0 && thisCol == finalSettlementIndex && !formattedValue.isEmpty()) {
                try {
                    String cleanValue = formattedValue.replace(",", "").trim();
                    
                    // 값이 숫자인지 확인
                    if (cleanValue.matches("-?[\\d.]+")) {
                        // BigDecimal로 변환하여 정확히 15자리에서 반올림
                        BigDecimal bd = new BigDecimal(cleanValue);
                        bd = bd.setScale(14, java.math.RoundingMode.HALF_UP);
                        formattedValue = bd.toPlainString();
                        System.out.println("최종 정산금 반올림 처리: " + cleanValue + " -> " + formattedValue);
                    }
                } catch (Exception e) {
                    System.out.println("최종 정산금 값 처리 중 오류: " + e.getMessage());
                }
            }
            
            // Video ID 특별 처리
            if (thisCol == videoIdColumnIndex && currentRowNum > 0) {
                if (formattedValue.startsWith("=") || formattedValue.startsWith("+") || 
                    formattedValue.startsWith("-") || formattedValue.startsWith("@")) {
                    if (!formattedValue.startsWith("'")) {
                        formattedValue = "'" + formattedValue;
                    }
                }
            }
            
            // CSV 용으로 포맷팅
            formattedValue = escapeCsvValue(formattedValue, thisCol == videoIdColumnIndex);
            currentRow.add(formattedValue);
        }
        
        @Override
        public void endRow(int rowNum) {
            try {
                // 메모리 관리를 위한 명시적 GC 호출 (큰 행 처리 후)
                if (rowNum > 0 && rowNum % 10000 == 0) {
                    System.gc();
                    System.out.println("메모리 정리 수행: " + rowNum + "행 처리 완료");
                }
                
                // 헤더 처리
                if (rowNum == 0) {
                    headers = new ArrayList<>(currentRow);
                    
                    // Video ID 컬럼 인덱스 찾기
                    for (int i = 0; i < headers.size(); i++) {
                        if (headers.get(i).contains("Video ID")) {
                            videoIdColumnIndex = i;
                            System.out.println("Video ID 컬럼 발견: 인덱스 " + videoIdColumnIndex);
                            break;
                        }
                    }
                    
                    System.out.println("헤더 행 처리 완료: " + String.join(",", headers));
                    return;
                }
                
                // 데이터 행 처리
                List<String> rowData = new ArrayList<>(currentRow);

                // Video ID 컬럼이 발견된 경우, 특수문자로 시작하는 ID 처리
                if (videoIdColumnIndex != -1 && videoIdColumnIndex < rowData.size()) {
                    String videoId = rowData.get(videoIdColumnIndex);
                    if (videoId != null && videoId.length() > 0) {
                        // =, +, - 등으로 시작하는 값 처리
                        if (videoId.startsWith("=") || videoId.startsWith("+") || videoId.startsWith("-") || videoId.startsWith("@")) {
                            // 앞에 작은따옴표(')를 추가하여 수식으로 해석되지 않도록 함
                            if (!videoId.startsWith("'")) {
                                videoId = "'" + videoId;
                                rowData.set(videoIdColumnIndex, videoId);
                            }
                        }
                    }
                }
                
                // 최종 정산금 컬럼 확인 및 최종 금액 합산
                if (finalSettlementIndex != -1 && finalSettlementIndex < rowData.size()) {
                    String value = rowData.get(finalSettlementIndex).replace("\"", "");
                    
                    if (value != null && !value.isEmpty()) {
                        try {
                            // 쉼표 제거 및 숫자 변환
                            String cleanValue = value.replace(",", "").trim();
                            
                            // 값이 숫자인지 확인
                            if (cleanValue.matches("-?[\\d.]+")) {
                                // 최종 정산금 합산
                                BigDecimal rowAmount = new BigDecimal(cleanValue);
                                finalAmount = finalAmount.add(rowAmount);
                                System.out.println("최종 정산금 합산: " + rowAmount + ", 총합: " + finalAmount);
                            }
                        } catch (Exception e) {
                            System.out.println("최종 정산금 합산 중 오류: " + e.getMessage());
                        }
                    }
                }
                
                // 정산요율 컬럼 백분위 처리
                if (settlementRateIndex != -1 && settlementRateIndex < rowData.size()) {
                    String value = rowData.get(settlementRateIndex).replace("\"", "");
                    if (value != null && !value.isEmpty()) {
                        try {
                            // 값이 숫자인지 확인
                            if (value.matches("-?[\\d.]+")) {
                                // 백분율로 변환 (100 곱하기)
                                BigDecimal rate = new BigDecimal(value);
                                rate = rate.multiply(new BigDecimal(100)).setScale(4, java.math.RoundingMode.HALF_UP);
                                // 소수점 뒤가 0인 경우 정수로 표시
                                String formattedRate = rate.stripTrailingZeros().toPlainString();
                                if (formattedRate.endsWith(".0")) {
                                    formattedRate = formattedRate.substring(0, formattedRate.length() - 2);
                                }
                                formattedRate = formattedRate + "%";
                                rowData.set(settlementRateIndex, formattedRate);
                                System.out.println("정산요율 백분위 변환: " + value + " -> " + formattedRate);
                            }
                        } catch (Exception e) {
                            System.out.println("정산요율 백분위 변환 중 오류: " + e.getMessage());
                        }
                    }
                }
                
                // 실연권요율 컬럼 백분위 처리
                if (performanceRightsRateIndex != -1 && performanceRightsRateIndex < rowData.size()) {
                    String value = rowData.get(performanceRightsRateIndex).replace("\"", "");
                    if (value != null && !value.isEmpty()) {
                        try {
                            // 값이 숫자인지 확인
                            if (value.matches("-?[\\d.]+")) {
                                // 백분율로 변환 (100 곱하기)
                                BigDecimal rate = new BigDecimal(value);
                                rate = rate.multiply(new BigDecimal(100)).setScale(4, java.math.RoundingMode.HALF_UP);
                                // 소수점 뒤가 0인 경우 정수로 표시
                                String formattedRate = rate.stripTrailingZeros().toPlainString();
                                if (formattedRate.endsWith(".0")) {
                                    formattedRate = formattedRate.substring(0, formattedRate.length() - 2);
                                }
                                formattedRate = formattedRate + "%";
                                rowData.set(performanceRightsRateIndex, formattedRate);
                                System.out.println("실연권요율 백분위 변환: " + value + " -> " + formattedRate);
                            }
                        } catch (Exception e) {
                            System.out.println("실연권요율 백분위 변환 중 오류: " + e.getMessage());
                        }
                    }
                }
                
                allRows.add(rowData);
                // System.out.println("데이터 행 수집: " + rowNum + " (총 " + allRows.size() + "개 행)");
                
                // 메모리 관리를 위해 currentRow 초기화
                currentRow = null;
                
            } catch (Exception e) {
                throw new RuntimeException("행 처리 중 오류 발생: " + e.getMessage(), e);
            }
        }
        
        @Override
        public void headerFooter(String text, boolean isHeader, String tagName) {
            // 사용하지 않음
        }
        
        @Override
        public void endSheet() {
            try {
                System.out.println("시트 처리 완료. 총 " + allRows.size() + "개 데이터 행 수집됨.");

                Map<String, String> map = new HashMap<>();
                map.put("name", baseFilename);
                map.put("finalAmount", finalAmount.toPlainString());
                finalAmounts.add(map);
                finalAmount = new BigDecimal(0);
                
                // 모든 행에서 Partner Revenue 값 최종 확인
                System.out.println("Partner Revenue 컬럼 인덱스: " + partnerRevenueIndex);
                for (List<String> row : allRows) {
                    // Partner Revenue 컬럼 확인 및 수정 - 13자리에서 반올림하여 14자리로 표시
                    if (partnerRevenueIndex != -1 && partnerRevenueIndex < row.size()) {
                        // 값 추출 및 처리
                        String value = row.get(partnerRevenueIndex).replace("\"", "");
                        
                        if (value != null && !value.isEmpty()) {
                            try {
                                // 쉼표 제거 및 숫자 변환
                                String cleanValue = value.replace(",", "").trim();
                                
                                // 소수점 자리 처리 - 13자리에서 반올림
                                if (cleanValue.matches("-?[\\d.]+")) {
                                    // BigDecimal로 변환하여 정확히 13자리에서 반올림
                                    BigDecimal bd = new BigDecimal(cleanValue);
                                    bd = bd.setScale(13, java.math.RoundingMode.HALF_UP);
                                    
                                    // 14자리로 표시하기 위해 0 추가
                                    String processedValue = bd.toPlainString() + "0";
                                    row.set(partnerRevenueIndex, processedValue);
                                    System.out.println("Partner Revenue 최종 확인(반올림): " + cleanValue + " -> " + processedValue);
                                }
                            } catch (Exception e) {
                                System.out.println("Partner Revenue 최종 확인 중 오류: " + e.getMessage());
                            }
                        }
                    }
                    
                    // Partner Revenue (KRW) 컬럼 확인 및 수정 - 소수점 14자리까지 정확히 표시
                    if (partnerRevenueKrwIndex != -1 && partnerRevenueKrwIndex < row.size()) {
                        String value = row.get(partnerRevenueKrwIndex).replace("\"", "");
                        
                        if (value != null && !value.isEmpty()) {
                            try {
                                // 쉼표 제거 및 숫자 변환
                                String cleanValue = value.replace(",", "").trim();
                                
                                // 소수점 자리 처리 - 14자리까지 표시
                                if (cleanValue.matches("-?[\\d.]+")) {
                                    if (cleanValue.contains(".")) {
                                        String[] parts = cleanValue.split("\\.");
                                        String integerPart = parts[0];
                                        String decimalPart = parts[1];
                                        
                                        // 소수점 이하 자릿수가 14자리가 되도록 처리
                                        if (decimalPart.length() < 14) {
                                            StringBuilder paddedDecimal = new StringBuilder(decimalPart);
                                            while (paddedDecimal.length() < 14) {
                                                paddedDecimal.append("0");
                                            }
                                            String processedValue = integerPart + "." + paddedDecimal.toString();
                                            row.set(partnerRevenueKrwIndex, processedValue);
                                        }
                                    } else {
                                        // 소수점이 없는 경우 소수점 14자리 추가
                                        String processedValue = cleanValue + ".00000000000000";
                                        row.set(partnerRevenueKrwIndex, processedValue);
                                    }
                                }
                            } catch (Exception e) {
                                System.out.println("Partner Revenue (KRW) 최종 확인 중 오류: " + e.getMessage());
                            }
                        }
                    }
                    
                    // 최종 정산금 컬럼 확인 및 수정 - 소수점 15자리에서 반올림하여 14자리로 표시
                    if (finalSettlementIndex != -1 && finalSettlementIndex < row.size()) {
                        String value = row.get(finalSettlementIndex).replace("\"", "");
                        
                        if (value != null && !value.isEmpty()) {
                            try {
                                // 쉼표 제거 및 숫자 변환
                                String cleanValue = value.replace(",", "").trim();
                                
                                // 소수점 자리 처리 - 15자리에서 반올림하여 14자리로 표시
                                if (cleanValue.matches("-?[\\d.]+")) {
                                    // BigDecimal로 변환하여 정확히 15자리에서 반올림
                                    BigDecimal bd = new BigDecimal(cleanValue);
                                    bd = bd.setScale(14, java.math.RoundingMode.HALF_UP);
                                    String processedValue = bd.toPlainString();
                                    row.set(finalSettlementIndex, processedValue);
                                    //System.out.println("최종 정산금 반올림 처리: " + cleanValue + " -> " + processedValue);
                                }
                            } catch (Exception e) {
                                System.out.println("최종 정산금 최종 확인 중 오류: " + e.getMessage());
                            }
                        }
                    }
                }
                
                // 마지막 행이 합계 행인지 확인
                if (!allRows.isEmpty()) {
                    List<String> lastRow = allRows.get(allRows.size() - 1);
                    boolean isSummaryRow = false;
                    
                    // 마지막 행이 합계 행인지 확인 (합계, 총계, 계 등의 단어 포함 여부)
                    for (String cell : lastRow) {
                        if (cell != null && (cell.contains("합계") || cell.contains("총계") || cell.contains("계"))) {
                            isSummaryRow = true;
                            break;
                        }
                    }
                    
                    // 최종 정산금과 Partner Revenue (KRW) 컬럼만 값이 있는 경우 제외
                    if (finalSettlementIndex != -1 && partnerRevenueKrwIndex != -1) {
                        boolean onlySettlementValues = true;
                        for (int i = 0; i < lastRow.size(); i++) {
                            if (i != finalSettlementIndex && i != partnerRevenueKrwIndex && 
                                lastRow.get(i) != null && !lastRow.get(i).isEmpty()) {
                                onlySettlementValues = false;
                                break;
                            }
                        }
                        if (onlySettlementValues) {
                            isSummaryRow = true;
                        }
                    }
                    
                    if (isSummaryRow) {
                        System.out.println("마지막 행이 합계 행이거나 정산금 관련 컬럼만 값이 있으므로 제외됩니다.");
                        allRows.remove(allRows.size() - 1);
                    }
                }
                
                // CSV 파일 생성 및 데이터 쓰기
                createNewFile();
                
                // 헤더 쓰기 (UTF-8 인코딩)
                String headerLine = String.join(",", headers);
                currentWriter.write(headerLine);
                currentWriter.write("\n");
                currentFileSize += (headerLine + "\n").getBytes(StandardCharsets.UTF_8).length;
                
                // 데이터 행 쓰기
                for (List<String> rowData : allRows) {
                    // 각 행의 모든 셀을 정확하게 CSV 형식으로 변환
                    List<String> escapedCells = new ArrayList<>(rowData.size());
                    
                    for (int i = 0; i < rowData.size(); i++) {
                        String cell = rowData.get(i);
                        
                        // Partner Revenue는 이미 이전 단계에서 정확하게 처리되었음
                        boolean isVideoIdCol = (i == videoIdColumnIndex);
                        
                        // CSV로 escaping
                        String escapedCell = escapeCsvValue(cell, isVideoIdCol);
                        escapedCells.add(escapedCell);
                    }
                    
                    // 이스케이프된 셀들을 합쳐 하나의 CSV 라인으로 만들기
                    String line = String.join(",", escapedCells);
                    byte[] lineBytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
                    
                    // 파일 크기 제한을 초과하면 새 파일 생성
                    if (currentFileSize + lineBytes.length > maxFileSize) {
                        closeCurrentFile();
                        createNewFile();
                        
                        // 새 파일에 헤더 쓰기
                        currentWriter.write(headerLine);
                        currentWriter.write("\n");
                        currentFileSize += (headerLine + "\n").getBytes(StandardCharsets.UTF_8).length;
                    }
                    
                    // 행 데이터 쓰기
                    currentWriter.write(line);
                    currentWriter.write("\n");
                    currentFileSize += lineBytes.length;
                    
                    // 주기적으로 버퍼 플러시
                    if (currentRowNum > 0 && currentRowNum % BUFFER_FLUSH_THRESHOLD == 0) {
                        currentWriter.flush();
                    }
                }
                
                // 처리 완료 메시지
                System.out.println("CSV 기록 완료. " + allRows.size() + "개 행 처리됨.");
                
                // 현재 열려있는 파일 닫기
                closeCurrentFile();
                
                // 메모리 정리
                allRows.clear();
                
            } catch (IOException e) {
                throw new RuntimeException("CSV 파일 닫기 오류", e);
            }
        }
        
        private String escapeCsvValue(String value, boolean isVideoId) {
            if (value == null || value.isEmpty()) {
                return "";
            }
            
            // Video ID 특수 처리
            if (isVideoId) {
                if (value.startsWith("=") || value.startsWith("+") || value.startsWith("-") || value.startsWith("@")) {
                    // 앞에 작은따옴표(')를 추가하여 수식으로 해석되지 않도록 함
                    if (!value.startsWith("'")) {
                        value = "'" + value;
                    }
                }
            }
            
            // 값에 쉼표, 큰따옴표 또는 개행이 포함되어 있으면 큰따옴표로 감싸기
            boolean needsQuotes = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
            
            if (needsQuotes) {
                // 큰따옴표를 두 개로 이스케이프
                value = value.replace("\"", "\"\"");
                return "\"" + value + "\"";
            }
            
            return value;
        }
        
        public Map<String, Long> getCreatedFiles() {
            return createdFiles;
        }
        
        // 현재 처리 중인 행 가져오기
        public List<String> getCurrentRow() {
            return currentRow;
        }
        
        // Partner Revenue 컬럼 인덱스 가져오기
        public int getPartnerRevenueIndex() {
            return partnerRevenueIndex;
        }
        
        // Partner Revenue (KRW) 컬럼 인덱스 가져오기
        public int getPartnerRevenueKrwIndex() {
            return partnerRevenueKrwIndex;
        }
        
        // 최종 정산금 컬럼 인덱스 가져오기
        public int getFinalSettlementIndex() {
            return finalSettlementIndex;
        }
        
        // 정산요율 컬럼 인덱스 가져오기
        public int getSettlementRateIndex() {
            return settlementRateIndex;
        }
        
        // 실연권요율 컬럼 인덱스 가져오기
        public int getPerformanceRightsRateIndex() {
            return performanceRightsRateIndex;
        }
        
        // 새 파일을 생성하는 메서드 추가
        private void createNewFile() throws IOException {
            // 새 파일 생성
            String filename = baseFilename + "_split_" + fileIndex + ".csv";
            Path filePath = outputPath.resolve(filename);
            currentFile = filePath.toFile();
            fileIndex++;
            
            // UTF-8 with BOM으로 파일 생성
            FileOutputStream fos = new FileOutputStream(currentFile);
            
            // BOM 추가
            fos.write(0xEF);
            fos.write(0xBB);
            fos.write(0xBF);
            
            // 버퍼 크기 증가 (32KB)
            currentWriter = new BufferedWriter(
                new OutputStreamWriter(fos, StandardCharsets.UTF_8),
                32768
            );
            
            currentFileSize = 0;
            System.out.println("새 CSV 파일 생성: " + currentFile.getName());
        }
        
        // 현재 열려있는 파일을 닫는 메서드 추가
        private void closeCurrentFile() throws IOException {
            if (currentWriter != null) {
                try {
                    currentWriter.flush();
                    currentWriter.close();
                    
                    // 파일 크기 기록
                    String filename = currentFile.getName();
                    createdFiles.put(filename, currentFile.length());
                    
                    currentWriter = null;
                    currentFile = null;
                    currentFileSize = 0;
                } catch (IOException e) {
                    System.err.println("파일 닫기 중 오류 발생: " + e.getMessage());
                    throw e;
                }
            }
        }
    }
} 