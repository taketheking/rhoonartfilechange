package com.rhoonart.plplsettlement.controller;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api/flo")
public class FloController {

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
                
                // 컬럼 매핑
                rowData.put("no", getNumericAsString(row.getCell(0))); // No
                
                // 정산월 처리 (- 제거, +1달 추가)
                String settlementMonth = getNumericAsString(row.getCell(1));
                settlementMonth = cleanDateFormat(settlementMonth);
                
                if (settlementMonth != null && !settlementMonth.isEmpty()) {
                    try {
                        // 숫자 형식으로 변환
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
                
                // 판매월 처리 (- 제거)
                String saleMonth = getNumericAsString(row.getCell(2));
                saleMonth = cleanDateFormat(saleMonth);
                
                // 20으로 시작하는지 확인
                if (saleMonth.length() >= 6 && !saleMonth.startsWith("20")) {
                    saleMonth = "20" + saleMonth.substring(saleMonth.length() - 4);
                }
                
                rowData.put("saleMonth", saleMonth);
                
                // 나머지 컬럼 매핑
                rowData.put("companyCode", getNumericAsString(row.getCell(3))); // 회사코드
                rowData.put("companyName", getNumericAsString(row.getCell(4))); // 회사명
                rowData.put("contractCode", getNumericAsString(row.getCell(5))); // 계약코드
                rowData.put("contractName", getNumericAsString(row.getCell(6))); // 계약명
                rowData.put("settlementCode", getNumericAsString(row.getCell(7))); // 정산코드
                rowData.put("settlementCodeName", getNumericAsString(row.getCell(8))); // 정산코드명
                rowData.put("saleChannel", getNumericAsString(row.getCell(9))); // 판매채널
                rowData.put("saleType", getNumericAsString(row.getCell(10))); // 판매타입
                rowData.put("productType", getNumericAsString(row.getCell(11))); // 상품타입
                rowData.put("mainCategory", getNumericAsString(row.getCell(12))); // 대분류
                rowData.put("middleCategory", getNumericAsString(row.getCell(13))); // 중분류
                rowData.put("subCategory", getNumericAsString(row.getCell(14))); // 소분류
                rowData.put("albumCode", getNumericAsString(row.getCell(15))); // 앨범코드
                rowData.put("albumName", getNumericAsString(row.getCell(16))); // 앨범명
                rowData.put("albumArtistName", getNumericAsString(row.getCell(17))); // 앨범가수명
                rowData.put("upc", getNumericAsString(row.getCell(18))); // UPC
                rowData.put("companyAlbumCode", getNumericAsString(row.getCell(19))); // 업체앨범코드
                rowData.put("songCode", getNumericAsString(row.getCell(20))); // 곡코드
                rowData.put("songName", getNumericAsString(row.getCell(21))); // 곡명
                rowData.put("uci", getNumericAsString(row.getCell(22))); // UCI
                rowData.put("isrc", getNumericAsString(row.getCell(23))); // ISRC
                rowData.put("companySongCode", getNumericAsString(row.getCell(24))); // 업체곡코드
                rowData.put("contentName", getNumericAsString(row.getCell(25))); // 콘텐츠명
                rowData.put("artistName", getNumericAsString(row.getCell(26))); // 가수명
                rowData.put("agencyName", getNumericAsString(row.getCell(27))); // 기획사명
                
                // 히트수는 자연수로 처리
                rowData.put("hitCount", getIntegerCellValue(row.getCell(28))); // 히트수
                
                // 정산금액은 실수로 처리
                rowData.put("settlementAmount", getDoubleCellValue(row.getCell(29))); // 정산금액
                
                data.add(rowData);
            }
        }
        
        return data;
    }
    
    // 날짜 형식 정리 (- 제거)
    private String cleanDateFormat(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return "";
        
        // '-' 제거
        dateStr = dateStr.replace("-", "");
        
        // 숫자만 남기기
        return dateStr.replaceAll("[^0-9]", "");
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
    
    // 정산금액은 실수로 가져오기
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
} 
 
 