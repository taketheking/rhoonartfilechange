package com.rhoonart.plplsettlement.service;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ExcelService {

    public Map<String, Object> readExcelFile(MultipartFile file) {
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            
            List<String> headers = new ArrayList<>();
            for (Cell cell : headerRow) {
                headers.add(getCellValueAsString(cell));
            }
            
            List<List<String>> data = new ArrayList<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row != null) {
                    List<String> rowData = new ArrayList<>();
                    for (int j = 0; j < headers.size(); j++) {
                        Cell cell = row.getCell(j);
                        rowData.add(cell != null ? getCellValueAsString(cell) : "");
                    }
                    data.add(rowData);
                }
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("headers", headers);
            result.put("data", data);
            return result;
            
        } catch (IOException e) {
            throw new RuntimeException("Excel 파일 읽기 실패", e);
        }
    }

    public byte[] convertToCsv(Map<String, Object> data) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            @SuppressWarnings("unchecked")
            List<String> headers = (List<String>) data.get("headers");
            @SuppressWarnings("unchecked")
            List<List<String>> rows = (List<List<String>>) data.get("data");

            // Write headers
            outputStream.write(String.join(",", headers).getBytes());
            outputStream.write("\n".getBytes());

            // Write data
            for (List<String> row : rows) {
                List<String> escapedValues = new ArrayList<>();
                for (String value : row) {
                    escapedValues.add(escapeCsvValue(value));
                }
                outputStream.write(String.join(",", escapedValues).getBytes());
                outputStream.write("\n".getBytes());
            }

            return outputStream.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("CSV 변환 실패", e);
        }
    }

    private String getCellValueAsString(Cell cell) {
        if (cell == null) return "";
        
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getLocalDateTimeCellValue().toString();
                }
                return String.valueOf(cell.getNumericCellValue());
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                return cell.getCellFormula();
            default:
                return "";
        }
    }

    private String escapeCsvValue(String value) {
        if (value == null) return "";
        
        boolean needsQuotes = value.contains(",") || value.contains("\"") || value.contains("\n");
        if (!needsQuotes) return value;

        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
} 