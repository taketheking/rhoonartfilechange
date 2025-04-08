package com.rhoonart.plplsettlement.controller;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.util.*;

import com.rhoonart.plplsettlement.service.ExcelService;
import com.rhoonart.plplsettlement.service.ExclusionListService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/csv")
@RequiredArgsConstructor
public class GenieController {

    // 한 번에 처리하는 최대 라인 수 (메모리 관리용)
    private static final int BATCH_SIZE = 1000;

    private final ExcelService excelService;
    private final ExclusionListService exclusionListService;

    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body("파일이 비어있습니다.");
            }

            List<Map<String, Object>> data = new ArrayList<>();
            
            // 파일을 스트림으로 읽기 (바이트 배열 대신 스트림 사용)
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(file.getInputStream(), "EUC-KR"))) {
                
                String line;
                int lineNum = 0;
                int processedRows = 0;

                // 첫 번째 줄은 헤더이므로 건너뜁니다
                br.readLine();

                while ((line = br.readLine()) != null) {
                    lineNum++;
                    // 두 번째 줄부터 데이터 처리
                    if (lineNum >= 1) {
                        // 메모리 부하 감소를 위해 과도한 데이터는 제한
                        if (processedRows >= 50000) {
                            break;
                        }

                        String[] values = line.split(",(?=([^\"]*\"[^\"]*\")*[^\"]*$)"); // CSV 파싱 (쉼표가 포함된 필드 처리)
                        
                        if (values.length < 16) continue; // 필요한 컬럼 수보다 적으면 스킵

                        Map<String, Object> rowData = new HashMap<>();
                        
                        // 데이터 매핑 - 값이 필요한 데이터만 추출하여 메모리 사용 최소화
                        rowData.put("songName", cleanValue(values[0])); // 곡명
                        rowData.put("albumName", cleanValue(values[1])); // 음반명
                        rowData.put("artistName", cleanValue(values[2])); // 아티스트명
                        rowData.put("lpName", cleanValue(values[3])); // LP명
                        rowData.put("songLid", cleanValue(values[4])); // 곡LID
                        rowData.put("albumLid", cleanValue(values[5])); // 음반LID
                        rowData.put("songExternalCode", cleanValue(values[7])); // 곡외부코드
                        rowData.put("albumExternalCode", cleanValue(values[8]).replaceAll("^=\"|\"$", "")); // 음반외부코드 (=" 접두사 제거)
                        rowData.put("dn", cleanValue(values[9])); // DN
                        rowData.put("st", cleanValue(values[10])); // ST
                        rowData.put("salesAmount", cleanValue(values[11])); // 판매금액
                        
                        // 인접권료를 정산금액으로 사용
                        String adjacentRights = cleanValue(values[13]); // 인접권료
                        rowData.put("settlementAmount", adjacentRights); // 정산금액
                        rowData.put("adjacentRights", adjacentRights); // 인접권료
                        rowData.put("copyright", cleanValue(values[14])); // 저작권료
                        rowData.put("performanceRights", cleanValue(values[15])); // 실연권료

                        data.add(rowData);
                        processedRows++;
                        
                        // 배치 처리를 위한 메모리 관리 - GC 호출
                        if (processedRows % BATCH_SIZE == 0) {
                            System.gc(); // 명시적 GC 호출로 메모리 확보
                        }
                    }
                }
            }

            return ResponseEntity.ok(data);

        } catch (IOException e) {
            return ResponseEntity.badRequest().body("파일 처리 중 오류가 발생했습니다: " + e.getMessage());
        } catch (OutOfMemoryError e) {
            // 메모리 부족 오류 별도 처리
            System.gc(); // 메모리 확보 시도
            return ResponseEntity.status(500).body("파일이 너무 큽니다. 더 작은 파일로 시도해 주세요.");
        }
    }

    private String cleanValue(String value) {
        if (value == null) return "";
        // 따옴표 제거 및 트림 처리
        return value.replaceAll("^\"|\"$", "").trim();
    }

    @PostMapping("/exclusion-list")
    public ResponseEntity<?> uploadExclusionList(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body("파일이 비어있습니다.");
            }

            Map<String, List<String>> exclusionList = new HashMap<>();
            List<String> trackCodes = new ArrayList<>();
            List<String> albumCodes = new ArrayList<>();
            
            exclusionList.put("trackCodes", trackCodes);
            exclusionList.put("albumCodes", albumCodes);
            
            // 파일 확장자 확인
            String fileName = file.getOriginalFilename();
            boolean isExcel = fileName != null && (fileName.endsWith(".xlsx") || fileName.endsWith(".xls"));
            
            if (isExcel) {
                // 엑셀 파일 처리
                try (InputStream is = file.getInputStream()) {
                    Workbook workbook;
                    
                    if (fileName.endsWith(".xlsx")) {
                        workbook = new XSSFWorkbook(is);  // XLSX 포맷
                    } else {
                        workbook = new HSSFWorkbook(is);  // XLS 포맷
                    }
                    
                    // 첫 번째 시트 사용
                    Sheet sheet = workbook.getSheetAt(0);
                    
                    // 첫 번째 행은 헤더로 간주하고 건너뜀
                    boolean isFirstRow = true;
                    
                    for (Row row : sheet) {
                        if (isFirstRow) {
                            isFirstRow = false;
                            continue;
                        }
                        
                        // 트랙코드 (첫 번째 컬럼)
                        Cell trackCodeCell = row.getCell(0);
                        if (trackCodeCell != null) {
                            String trackCode = getCellValueAsString(trackCodeCell);
                            if (trackCode != null && !trackCode.trim().isEmpty()) {
                                trackCodes.add(trackCode.trim());
                                //System.out.println("트랙코드 추가 (엑셀): " + trackCode.trim());
                            }
                        }
                        
                        // 음반코드 (세 번째 컬럼)
                        Cell albumCodeCell = row.getCell(2);  // 0부터 시작하므로 세 번째 컬럼은 인덱스 2
                        if (albumCodeCell != null) {
                            String albumCode = getCellValueAsString(albumCodeCell);
                            if (albumCode != null && !albumCode.trim().isEmpty()) {
                                albumCodes.add(albumCode.trim());
                                //System.out.println("음반코드 추가 (엑셀): " + albumCode.trim());
                            }
                        }
                    }
                    
                    workbook.close();
                }
            } else {
                // CSV 파일 처리
                try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(file.getInputStream(), "UTF-8"))) {
                    
                    String line;
                    // 첫 번째 줄은 헤더로 간주하고 건너뜀
                    br.readLine();
                    
                    while ((line = br.readLine()) != null) {
                        // CSV 형식 파싱
                        String[] values = line.split(",(?=([^\"]*\"[^\"]*\")*[^\"]*$)");
                        
                        if (values.length > 0) {
                            // 첫 번째 컬럼 (트랙코드)
                            String trackCode = cleanValue(values[0]);
                            if (!trackCode.isEmpty()) {
                                trackCodes.add(trackCode);
                                System.out.println("트랙코드 추가 (CSV): " + trackCode);
                            }
                            
                            // 세 번째 컬럼 (음반코드)
                            if (values.length >= 3) {
                                String albumCode = cleanValue(values[2]);
                                if (!albumCode.isEmpty()) {
                                    albumCodes.add(albumCode);
                                    System.out.println("음반코드 추가 (CSV): " + albumCode);
                                }
                            }
                        }
                    }
                } catch (IOException e) {
                    System.err.println("UTF-8로 읽기 실패, EUC-KR로 시도합니다.");
                    
                    // EUC-KR로 재시도
                    try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(file.getInputStream(), "EUC-KR"))) {
                        
                        String line;
                        br.readLine(); // 헤더 건너뛰기
                        
                        while ((line = br.readLine()) != null) {
                            String[] values = line.split(",(?=([^\"]*\"[^\"]*\")*[^\"]*$)");
                            
                            if (values.length > 0) {
                                String trackCode = cleanValue(values[0]);
                                if (!trackCode.isEmpty()) {
                                    trackCodes.add(trackCode);
                                    System.out.println("트랙코드 추가 (CSV-EUC-KR): " + trackCode);
                                }
                                
                                if (values.length >= 3) {
                                    String albumCode = cleanValue(values[2]);
                                    if (!albumCode.isEmpty()) {
                                        albumCodes.add(albumCode);
                                        System.out.println("음반코드 추가 (CSV-EUC-KR): " + albumCode);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            System.out.println("제외 리스트 처리 완료: 트랙코드 " + trackCodes.size() + "개, 음반코드 " + albumCodes.size() + "개");
            return ResponseEntity.ok(exclusionList);

        } catch (Exception e) {
            System.err.println("제외 리스트 처리 중 오류 발생: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).body("제외 리스트 처리 중 오류가 발생했습니다: " + e.getMessage());
        }
    }
    
    // 엑셀 셀 값을 문자열로 변환하는 헬퍼 메서드
    private String getCellValueAsString(Cell cell) {
        if (cell == null) {
            return "";
        }
        
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                } else {
                    // 숫자를 문자열로 변환 (지수 표기법 방지)
                    double value = cell.getNumericCellValue();
                    if (value == Math.floor(value)) {
                        return String.format("%.0f", value);
                    } else {
                        return String.valueOf(value);
                    }
                }
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return String.valueOf(cell.getStringCellValue());
                } catch (Exception e) {
                    try {
                        return String.valueOf(cell.getNumericCellValue());
                    } catch (Exception e2) {
                        return "";
                    }
                }
            default:
                return "";
        }
    }

    @PostMapping("/exclusions/upload")
    public ResponseEntity<Map<String, Object>> uploadExclusions(@RequestParam("file") MultipartFile file) {
        exclusionListService.addExclusionsFromFile("genie", file);
        return ResponseEntity.ok(Map.of(
            "message", "제외 리스트가 업로드되었습니다.",
            "exclusions", exclusionListService.getExclusionList("genie")
        ));
    }

    @GetMapping("/exclusions")
    public ResponseEntity<List<String>> getExclusions() {
        return ResponseEntity.ok(exclusionListService.getExclusionList("genie"));
    }

    @DeleteMapping("/exclusions/{exclusion}")
    public ResponseEntity<Map<String, Object>> removeExclusion(@PathVariable String exclusion) {
        exclusionListService.removeExclusion("genie", exclusion);
        return ResponseEntity.ok(Map.of(
            "message", "제외 항목이 삭제되었습니다.",
            "exclusions", exclusionListService.getExclusionList("genie")
        ));
    }

    @DeleteMapping("/exclusions")
    public ResponseEntity<Map<String, Object>> clearExclusions() {
        exclusionListService.clearExclusionList("genie");
        return ResponseEntity.ok(Map.of(
            "message", "제외 리스트가 초기화되었습니다.",
            "exclusions", exclusionListService.getExclusionList("genie")
        ));
    }
} 