package com.rhoonart.plplsettlement.controller;

import com.rhoonart.plplsettlement.service.ExcelService;
import com.rhoonart.plplsettlement.service.ExclusionListService;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api/vibe")
@RequiredArgsConstructor
public class VibeController {
    private final ExcelService excelService;
    private final ExclusionListService exclusionListService;

    @PostMapping("/upload")
    public List<Map<String, String>> uploadFile(@RequestParam("file") MultipartFile file) throws IOException {
        List<Map<String, String>> data = new ArrayList<>();
        
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            
            // 마지막 행 번호 가져오기
            int lastRowNum = sheet.getLastRowNum();
            
            // 엑셀 데이터 순회 (마지막 행은 제외)
            for (int i = 1; i < lastRowNum; i++) { // 1부터 시작하여 마지막 행 직전까지
                Row row = sheet.getRow(i);
                if (row == null) continue;
                
                Map<String, String> rowData = new HashMap<>();
                
                // 정산월 처리 - 원본 값에서 1달 추가
                String settlementMonth = getNumericAsString(row.getCell(0));
                if (settlementMonth != null && !settlementMonth.isEmpty()) {
                    try {
                        // 숫자 형식으로 변환
                        settlementMonth = settlementMonth.replaceAll("[^0-9]", "");
                        if (settlementMonth.length() > 6) {
                            settlementMonth = settlementMonth.substring(0, 6);
                        } else if (settlementMonth.length() < 6) {
                            while (settlementMonth.length() < 6) {
                                settlementMonth = "0" + settlementMonth;
                            }
                        }
                        
                        int year = Integer.parseInt(settlementMonth.substring(0, 4));
                        int month = Integer.parseInt(settlementMonth.substring(4, 6));
                        month++;
                        if (month > 12) {
                            month = 1;
                            year++;
                        }
                        settlementMonth = String.format("%04d%02d", year, month);
                    } catch (Exception e) {
                        // 형식이 맞지 않으면 원본 값 사용
                    }
                }
                
                // 20으로 시작하는지 확인
                if (settlementMonth.length() >= 6 && !settlementMonth.startsWith("20")) {
                    settlementMonth = "20" + settlementMonth.substring(settlementMonth.length() - 4);
                }
                
                rowData.put("settlementMonth", settlementMonth);
                
                // 서비스월 처리 - 정산월과 동일한 형식
                String serviceMonth = getNumericAsString(row.getCell(1));
                if (serviceMonth != null && !serviceMonth.isEmpty()) {
                    try {
                        // 숫자 형식으로 변환
                        serviceMonth = serviceMonth.replaceAll("[^0-9]", "");
                        if (serviceMonth.length() > 6) {
                            serviceMonth = serviceMonth.substring(0, 6);
                        } else if (serviceMonth.length() < 6) {
                            while (serviceMonth.length() < 6) {
                                serviceMonth = "0" + serviceMonth;
                            }
                        }
                    } catch (Exception e) {
                        // 형식이 맞지 않으면 원본 값 사용
                    }
                }
                
                // 20으로 시작하는지 확인
                if (serviceMonth.length() >= 6 && !serviceMonth.startsWith("20")) {
                    serviceMonth = "20" + serviceMonth.substring(serviceMonth.length() - 4);
                }
                
                rowData.put("serviceMonth", serviceMonth);
                
                // 나머지 컬럼은 모두 문자열로 처리(히트수와 인접권료 제외)
                rowData.put("companyCode", getNumericAsString(row.getCell(2))); // 업체코드
                rowData.put("companyName", getNumericAsString(row.getCell(3))); // 업체명
                rowData.put("agencyCode", getNumericAsString(row.getCell(4))); // 기획사코드
                rowData.put("agencyName", getNumericAsString(row.getCell(5))); // 기획사명
                rowData.put("contractCode", getNumericAsString(row.getCell(6))); // 계약코드
                rowData.put("contractName", getNumericAsString(row.getCell(7))); // 계약명
                rowData.put("channelName", getNumericAsString(row.getCell(8))); // 채널명
                rowData.put("serviceCode", getNumericAsString(row.getCell(9))); // 서비스코드
                rowData.put("productCode", getNumericAsString(row.getCell(10))); // 상품코드
                rowData.put("serviceName", getNumericAsString(row.getCell(11))); // 서비스명
                rowData.put("isVideo", getNumericAsString(row.getCell(12))); // 뮤비여부
                rowData.put("isFree", getNumericAsString(row.getCell(13))); // 무료여부
                // PRM OFF는 생략 (인덱스 14)
                rowData.put("songCode", getNumericAsString(row.getCell(15))); // 곡코드
                rowData.put("companySongCode", getNumericAsString(row.getCell(16))); // 업체곡코드
                rowData.put("uci", getNumericAsString(row.getCell(17))); // UCI
                rowData.put("songName", getNumericAsString(row.getCell(18))); // 곡명
                rowData.put("albumCode", getNumericAsString(row.getCell(19))); // 앨범코드
                rowData.put("companyAlbumCode", getNumericAsString(row.getCell(20))); // 업체앨범코드
                rowData.put("albumName", getNumericAsString(row.getCell(21))); // 앨범명
                rowData.put("artistName", getNumericAsString(row.getCell(22))); // 아티스트명
                
                // 히트수는 자연수로 처리
                rowData.put("hitCount", getIntegerCellValue(row.getCell(23))); // 히트수
                
                // 인접권료는 실수로 처리 (VPS 최종인접권료 컬럼)
                rowData.put("adjacentRights", getDoubleCellValue(row.getCell(26))); // VPS 최종인접권료
                
                data.add(rowData);
            }
        }
        
        return data;
    }
    
    // 문자열로 셀 값 가져오기 (부동소수점 문제 해결)
    private String getNumericAsString(Cell cell) {
        if (cell == null) return "";
        
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                }
                // 부동소수점 표현 문제 해결
                double numValue = cell.getNumericCellValue();
                // 정수인 경우
                if (numValue == Math.floor(numValue)) {
                    return String.valueOf((long) numValue);
                }
                // 소수점이 있는 경우
                return String.valueOf(numValue);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    double formulaValue = cell.getNumericCellValue();
                    // 정수인 경우
                    if (formulaValue == Math.floor(formulaValue)) {
                        return String.valueOf((long) formulaValue);
                    }
                    // 소수점이 있는 경우
                    return String.valueOf(formulaValue);
                } catch (Exception e) {
                    try {
                        return cell.getStringCellValue();
                    } catch (Exception ex) {
                        return "";
                    }
                }
            default:
                return "";
        }
    }
    
    // 히트수는 정수로 가져오기
    private String getIntegerCellValue(Cell cell) {
        if (cell == null) return "0";
        
        switch (cell.getCellType()) {
            case STRING:
                try {
                    return String.valueOf((int) Double.parseDouble(cell.getStringCellValue()));
                } catch (Exception e) {
                    return "0";
                }
            case NUMERIC:
                return String.valueOf((int) cell.getNumericCellValue());
            case FORMULA:
                try {
                    return String.valueOf((int) cell.getNumericCellValue());
                } catch (Exception e) {
                    return "0";
                }
            default:
                return "0";
        }
    }
    
    // 인접권료는 실수로 가져오기
    private String getDoubleCellValue(Cell cell) {
        if (cell == null) return "0";
        
        switch (cell.getCellType()) {
            case STRING:
                try {
                    return String.valueOf(Double.parseDouble(cell.getStringCellValue()));
                } catch (Exception e) {
                    return "0";
                }
            case NUMERIC:
                return String.valueOf(cell.getNumericCellValue());
            case FORMULA:
                try {
                    return String.valueOf(cell.getNumericCellValue());
                } catch (Exception e) {
                    return "0";
                }
            default:
                return "0";
        }
    }

    @PostMapping("/exclusions/upload")
    public ResponseEntity<Map<String, Object>> uploadExclusions(@RequestParam("file") MultipartFile file) {
        exclusionListService.addExclusionsFromFile("vibe", file);
        return ResponseEntity.ok(Map.of(
            "message", "제외 리스트가 업로드되었습니다.",
            "exclusions", exclusionListService.getExclusionList("vibe")
        ));
    }

    @GetMapping("/exclusions")
    public ResponseEntity<List<String>> getExclusions() {
        return ResponseEntity.ok(exclusionListService.getExclusionList("vibe"));
    }

    @DeleteMapping("/exclusions/{exclusion}")
    public ResponseEntity<Map<String, Object>> removeExclusion(@PathVariable String exclusion) {
        exclusionListService.removeExclusion("vibe", exclusion);
        return ResponseEntity.ok(Map.of(
            "message", "제외 항목이 삭제되었습니다.",
            "exclusions", exclusionListService.getExclusionList("vibe")
        ));
    }

    @DeleteMapping("/exclusions")
    public ResponseEntity<Map<String, Object>> clearExclusions() {
        exclusionListService.clearExclusionList("vibe");
        return ResponseEntity.ok(Map.of(
            "message", "제외 리스트가 초기화되었습니다.",
            "exclusions", exclusionListService.getExclusionList("vibe")
        ));
    }
} 