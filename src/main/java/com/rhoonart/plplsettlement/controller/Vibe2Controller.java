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
@RequestMapping("/api/vibe2")
@RequiredArgsConstructor
public class Vibe2Controller {
    private final ExcelService excelService;
    private final ExclusionListService exclusionListService;

    @PostMapping("/upload")
    public List<Map<String, String>> uploadFile(@RequestParam("file") MultipartFile file) throws IOException {
        List<Map<String, String>> data = new ArrayList<>();
        
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            
            // 헤더 행 가져오기
            Row headerRow = sheet.getRow(0);
            Map<String, Integer> columnIndexMap = new HashMap<>();
            
            // 컬럼 인덱스 매핑
            for (Cell cell : headerRow) {
                String headerName = cell.getStringCellValue().trim();
                columnIndexMap.put(headerName, cell.getColumnIndex());
            }
            
            // 데이터 행 처리
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                
                Map<String, String> rowData = new HashMap<>();
                
                // 정산월과 서비스월은 현재 날짜 기준으로 설정
                java.util.Calendar cal = java.util.Calendar.getInstance();
                int currentYear = cal.get(java.util.Calendar.YEAR);
                int currentMonth = cal.get(java.util.Calendar.MONTH) + 1;
                
                // 정산월 (현재 월)
                rowData.put("settlementMonth", String.format("%d%02d", currentYear, currentMonth));
                
                // 서비스월 (2개월 전)
                int serviceMonth = currentMonth - 2;
                int serviceYear = currentYear;
                if (serviceMonth <= 0) {
                    serviceMonth += 12;
                    serviceYear--;
                }
                rowData.put("serviceMonth", String.format("%d%02d", serviceYear, serviceMonth));
                
                // 나머지 컬럼 처리
                for (Map.Entry<String, Integer> entry : columnIndexMap.entrySet()) {
                    String headerName = entry.getKey();
                    int columnIndex = entry.getValue();
                    Cell cell = row.getCell(columnIndex);
                    
                    String value = "";
                    if (cell != null) {
                        switch (cell.getCellType()) {
                            case STRING:
                                value = cell.getStringCellValue().trim();
                                break;
                            case NUMERIC:
                                if (DateUtil.isCellDateFormatted(cell)) {
                                    value = cell.getDateCellValue().toString();
                                } else {
                                    // 숫자 값은 정수로 변환
                                    double numValue = cell.getNumericCellValue();
                                    if (numValue == Math.floor(numValue)) {
                                        value = String.valueOf((long) numValue);
                                    } else {
                                        value = String.valueOf(numValue);
                                    }
                                }
                                break;
                            case BOOLEAN:
                                value = String.valueOf(cell.getBooleanCellValue());
                                break;
                            default:
                                value = "";
                        }
                    }
                    
                    // 컬럼명 매핑 (PRM OFF와 비고 제외)
                    switch (headerName) {
                        case "업체코드":
                            rowData.put("companyCode", value);
                            break;
                        case "업체명":
                            rowData.put("companyName", value);
                            break;
                        case "계약코드":
                            rowData.put("contractCode", value);
                            break;
                        case "계약명":
                            rowData.put("contractName", value);
                            break;
                        case "채널명":
                            rowData.put("channelName", value);
                            break;
                        case "서비스코드":
                            rowData.put("serviceCode", value);
                            break;
                        case "상품코드":
                            rowData.put("productCode", value);
                            break;
                        case "서비스명":
                            rowData.put("serviceName", value);
                            break;
                        case "뮤비여부":
                            rowData.put("isVideo", value);
                            break;
                        case "무료여부":
                            rowData.put("isFree", value);
                            break;
                        case "곡코드":
                            rowData.put("songCode", value);
                            break;
                        case "업체곡코드":
                            rowData.put("companySongCode", value);
                            break;
                        case "UCI":
                            rowData.put("uci", value);
                            break;
                        case "곡명":
                            rowData.put("songName", value);
                            break;
                        case "앨범코드":
                            rowData.put("albumCode", value);
                            break;
                        case "업체앨범코드":
                            rowData.put("companyAlbumCode", value);
                            break;
                        case "앨범명":
                            rowData.put("albumName", value);
                            break;
                        case "아티스트명":
                            rowData.put("artistName", value);
                            break;
                        case "히트수":
                            rowData.put("hitCount", value);
                            break;
                        case "인접권료":
                            rowData.put("adjacentRights", value);
                            break;
                    }
                }
                
                // 인접권료만 있는 행인지 확인
                boolean hasOnlyAdjacentRights = true;
                for (String key : rowData.keySet()) {
                    if (!key.equals("adjacentRights") && 
                        !key.equals("settlementMonth") && 
                        !key.equals("serviceMonth") && 
                        !rowData.get(key).isEmpty()) {
                        hasOnlyAdjacentRights = false;
                        break;
                    }
                }
                
                // 인접권료만 있는 행이 아니면 데이터에 추가
                if (!hasOnlyAdjacentRights) {
                    data.add(rowData);
                }
            }
        }
        
        return data;
    }

    @PostMapping("/exclusion-list")
    public ResponseEntity<Map<String, List<String>>> uploadExclusionList(@RequestParam("file") MultipartFile file) throws IOException {
        Map<String, List<String>> exclusionList = new HashMap<>();
        List<String> trackCodes = new ArrayList<>();
        List<String> albumCodes = new ArrayList<>();
        
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                
                // 첫 번째 열은 트랙 코드
                Cell trackCell = row.getCell(0);
                if (trackCell != null) {
                    String trackCode = trackCell.getStringCellValue().trim();
                    if (!trackCode.isEmpty()) {
                        trackCodes.add(trackCode);
                    }
                }
                
                // 두 번째 열은 앨범 코드
                Cell albumCell = row.getCell(1);
                if (albumCell != null) {
                    String albumCode = albumCell.getStringCellValue().trim();
                    if (!albumCode.isEmpty()) {
                        albumCodes.add(albumCode);
                    }
                }
            }
        }
        
        exclusionList.put("trackCodes", trackCodes);
        exclusionList.put("albumCodes", albumCodes);
        
        return ResponseEntity.ok(exclusionList);
    }
} 