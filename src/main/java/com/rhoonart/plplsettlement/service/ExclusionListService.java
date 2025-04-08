package com.rhoonart.plplsettlement.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ExclusionListService {
    private static final String STORAGE_DIR = "data";
    private final ConcurrentHashMap<String, List<String>> exclusionLists = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path storagePath;

    public ExclusionListService() throws IOException {
        storagePath = Paths.get(STORAGE_DIR);
        createStorageDirectoryIfNotExists();
    }

    @PostConstruct
    public void init() {
        loadAllExclusionLists();
    }

    private void createStorageDirectoryIfNotExists() throws IOException {
        if (!Files.exists(storagePath)) {
            Files.createDirectories(storagePath);
        }
    }

    private void loadAllExclusionLists() {
        String[] services = {"melon", "genie", "vibe", "flo"};
        for (String service : services) {
            loadExclusionList(service);
        }
    }

    private void loadExclusionList(String service) {
        Path filePath = storagePath.resolve(service + "_exclusion_list.json");
        try {
            if (Files.exists(filePath)) {
                List<String> loaded = objectMapper.readValue(filePath.toFile(), 
                    new TypeReference<List<String>>() {});
                exclusionLists.put(service, new CopyOnWriteArrayList<>(loaded));
            } else {
                exclusionLists.put(service, new CopyOnWriteArrayList<>());
            }
        } catch (IOException e) {
            e.printStackTrace();
            exclusionLists.put(service, new CopyOnWriteArrayList<>());
        }
    }

    private void saveExclusionList(String service) {
        Path filePath = storagePath.resolve(service + "_exclusion_list.json");
        try {
            objectMapper.writeValue(filePath.toFile(), exclusionLists.get(service));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public List<String> getExclusionList(String service) {
        return new ArrayList<>(exclusionLists.getOrDefault(service, new CopyOnWriteArrayList<>()));
    }

    public void addExclusionsFromFile(String service, MultipartFile file) {
        try {
            List<String> newExclusions = new ArrayList<>();
            List<String> currentList = exclusionLists.computeIfAbsent(service, k -> new CopyOnWriteArrayList<>());
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(file.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty() && !currentList.contains(line.trim())) {
                        newExclusions.add(line.trim());
                    }
                }
            }
            currentList.addAll(newExclusions);
            saveExclusionList(service);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void removeExclusion(String service, String exclusion) {
        List<String> currentList = exclusionLists.get(service);
        if (currentList != null) {
            currentList.remove(exclusion);
            saveExclusionList(service);
        }
    }

    public void clearExclusionList(String service) {
        List<String> currentList = exclusionLists.get(service);
        if (currentList != null) {
            currentList.clear();
            saveExclusionList(service);
        }
    }
} 