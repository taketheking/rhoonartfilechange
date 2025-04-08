package com.rhoonart.plplsettlement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

@SpringBootApplication(exclude = {SecurityAutoConfiguration.class})
public class PlplSettlementApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlplSettlementApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void openBrowserAfterStartup() {
        System.setProperty("java.awt.headless", "false");
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI("http://localhost:8080"));
                System.out.println("브라우저가 자동으로 열렸습니다.");
            } else {
                System.out.println("시스템이 브라우저 자동 실행을 지원하지 않습니다. http://localhost:8080 으로 접속해주세요.");
            }
        } catch (IOException | URISyntaxException e) {
            System.out.println("브라우저를 자동으로 열 수 없습니다. http://localhost:8080 으로 접속해주세요.");
            e.printStackTrace();
        }
    }
}
