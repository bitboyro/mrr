package ro.bitboy.mrr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import ro.bitboy.mrr.secret.SecretProperties;
import ro.bitboy.mrr.store.BlankDbUrlMeansSqlite;

@SpringBootApplication
@EnableConfigurationProperties({MrrProperties.class, SecretProperties.class})
@EnableScheduling
public class MrrApplication {

    public static void main(String[] args) {
        // Before the context: Compose hands us MRR_DB_URL="" for a variable nobody set, and
        // an empty value beats application.yml's SQLite fallback. See the class for why this
        // is a line in main rather than an EnvironmentPostProcessor.
        BlankDbUrlMeansSqlite.apply();
        SpringApplication.run(MrrApplication.class, args);
    }
}
