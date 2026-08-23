package ro.bitboy.mrr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import ro.bitboy.mrr.secret.SecretProperties;

@SpringBootApplication
@EnableConfigurationProperties({MrrProperties.class, SecretProperties.class})
@EnableScheduling
public class MrrApplication {

    public static void main(String[] args) {
        SpringApplication.run(MrrApplication.class, args);
    }
}
