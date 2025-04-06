package com.rhoonart.plplsettlement.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/csv")
public class CsvController {

    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body("파일이 비어있습니다.");
            }

            List<Map<String, Object>> data = new ArrayList<>();
            
            // 파일 내용을 바이트 배열로 읽어들임
            byte[] fileBytes = file.getBytes();
            
            // EUC-KR로 파일 읽기 시도
            BufferedReader br = new BufferedReader(
                new InputStreamReader(
                    new ByteArrayInputStream(fileBytes), 
                    "EUC-KR"
                )
            );
            
            String line;
            int lineNum = 0;

            // 첫 번째 줄은 헤더이므로 건너뜁니다
            br.readLine();

            while ((line = br.readLine()) != null) {
                lineNum++;
                // 두 번째 줄부터 데이터 처리
                if (lineNum >= 1) {
                    String[] values = line.split(",(?=([^\"]*\"[^\"]*\")*[^\"]*$)"); // CSV 파싱 (쉼표가 포함된 필드 처리)
                    
                    if (values.length < 16) continue; // 필요한 컬럼 수보다 적으면 스킵

                    Map<String, Object> rowData = new HashMap<>();
                    
                    // 데이터 매핑
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
                }
            }

            return ResponseEntity.ok(data);

        } catch (IOException e) {
            return ResponseEntity.badRequest().body("파일 처리 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    private String cleanValue(String value) {
        if (value == null) return "";
        // 따옴표 제거 및 트림 처리
        return value.replaceAll("^\"|\"$", "").trim();
    }
} 