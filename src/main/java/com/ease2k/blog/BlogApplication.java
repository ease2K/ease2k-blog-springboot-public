package com.ease2k.blog;

import com.ease2k.blog.owner.OwnerCredentials;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.nio.file.Path;

@SpringBootApplication
@EnableScheduling
public class BlogApplication {

    public static void main(String[] args) throws IOException {
        if (args.length == 2 && "setup-owner".equals(args[0])) {
            OwnerCredentials.setup(Path.of(args[1]));
            return;
        }
        SpringApplication.run(BlogApplication.class, args);
    }
}
