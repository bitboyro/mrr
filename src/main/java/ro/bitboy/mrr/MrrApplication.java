package ro.bitboy.mrr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(MrrProperties.class)
public class MrrApplication {

    public static void main(String[] args) {
        SpringApplication.run(MrrApplication.class, args);
    }
}
