package com.rhoonart.plplsettlement.controller;

import com.rhoonart.plplsettlement.service.ExcelService;
import com.rhoonart.plplsettlement.service.ExclusionListService;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/excel")
@RequiredArgsConstructor
public class MelonController {

    private final ExcelService excelService;
    private final ExclusionListService exclusionListService;

    @PostMapping("/upload")
    public List<Map<String, String>> uploadFile(@RequestParam("file") MultipartFile file) throws IOException {
        List<Map<String, String>> data = new ArrayList<>();
        
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            
            // 6번째 행부터 데이터 읽기 (인덱스 5)
            for (int i = 5; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                
                Map<String, String> rowData = new HashMap<>();
                
                // 각 셀의 데이터를 맵에 저장
                rowData.put("songName", getCellValue(row.getCell(0)));
                rowData.put("songCode", getCellValue(row.getCell(1)));
                rowData.put("artist", getCellValue(row.getCell(2)));
                rowData.put("lhSongCode", getCellValue(row.getCell(3)));
                rowData.put("albumName", getCellValue(row.getCell(4)));
                rowData.put("albumCode", getCellValue(row.getCell(5)));
                rowData.put("lhAlbumCode", getCellValue(row.getCell(6)));
                rowData.put("barcode", getCellValue(row.getCell(7)));
                rowData.put("mainArtist", getCellValue(row.getCell(8)));
                rowData.put("releaseDate", getCellValue(row.getCell(9)));
                rowData.put("agency", getCellValue(row.getCell(10)));
                rowData.put("label", getCellValue(row.getCell(11)));
                rowData.put("infoFee", getCellValue(row.getCell(12)));
                rowData.put("st", getCellValue(row.getCell(13)));
                rowData.put("dl", getCellValue(row.getCell(14)));
                rowData.put("copyright", getCellValue(row.getCell(15)));
                
                data.add(rowData);
            }
        }
        
        return data;
    }
    
    private String getCellValue(Cell cell) {
        if (cell == null) return "";
        
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                }
                // LH음반코드 등의 ID 값이 소수점으로 표시되지 않게 처리
                int columnIndex = cell.getColumnIndex();
                if (columnIndex == 3 || columnIndex == 6) { // LH곡코드, LH음반코드
                    return String.valueOf((long) cell.getNumericCellValue());
                }
                return String.valueOf(cell.getNumericCellValue());
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    // 공식 결과가 숫자인 경우
                    double numericValue = cell.getNumericCellValue();
                    int formulaColumnIndex = cell.getColumnIndex();
                    if (formulaColumnIndex == 3 || formulaColumnIndex == 6) { // LH곡코드, LH음반코드
                        return String.valueOf((long) numericValue);
                    }
                    return String.valueOf(numericValue);
                } catch (Exception e) {
                    return String.valueOf(cell.getRichStringCellValue());
                }
            default:
                return "";
        }
    }

    @PostMapping("/convert")
    public ResponseEntity<byte[]> convertToCsv(@RequestBody Map<String, Object> data) {
        byte[] csvContent = excelService.convertToCsv(data);
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.setContentDispositionFormData("attachment", "converted.csv");
        
        return ResponseEntity.ok()
                .headers(headers)
                .body(csvContent);
    }
    
    @PostMapping("/exclusion-list")
    public List<String> uploadExclusionList(@RequestParam("file") MultipartFile file) throws IOException {
        List<String> exclusionList = new ArrayList<>();
        
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            
            for (int i = 1; i <= sheet.getLastRowNum(); i++) { // Start from row 1 (skip header)
                Row row = sheet.getRow(i);
                if (row == null || row.getCell(0) == null) continue;
                
                String trackCode = getCellValue(row.getCell(0));
                if (trackCode != null && !trackCode.isEmpty()) {
                    exclusionList.add(trackCode);
                }
            }
        }
        
        return exclusionList;
    }

    @PostMapping("/exclusions/upload")
    public ResponseEntity<Map<String, Object>> uploadExclusions(@RequestParam("file") MultipartFile file) {
        exclusionListService.addExclusionsFromFile("melon", file);
        return ResponseEntity.ok(Map.of(
            "message", "제외 리스트가 업로드되었습니다.",
            "exclusions", exclusionListService.getExclusionList("melon")
        ));
    }

    @GetMapping("/exclusions")
    public ResponseEntity<List<String>> getExclusions() {
        return ResponseEntity.ok(exclusionListService.getExclusionList("melon"));
    }

    @DeleteMapping("/exclusions/{exclusion}")
    public ResponseEntity<Map<String, Object>> removeExclusion(@PathVariable String exclusion) {
        exclusionListService.removeExclusion("melon", exclusion);
        return ResponseEntity.ok(Map.of(
            "message", "제외 항목이 삭제되었습니다.",
            "exclusions", exclusionListService.getExclusionList("melon")
        ));
    }

    @DeleteMapping("/exclusions")
    public ResponseEntity<Map<String, Object>> clearExclusions() {
        exclusionListService.clearExclusionList("melon");
        return ResponseEntity.ok(Map.of(
            "message", "제외 리스트가 초기화되었습니다.",
            "exclusions", exclusionListService.getExclusionList("melon")
        ));
    }
} 