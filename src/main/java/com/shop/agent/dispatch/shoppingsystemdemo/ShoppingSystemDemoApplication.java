package com.shop.agent.dispatch.shoppingsystemdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "com.shop.agent.dispatch")
@EnableJpaRepositories(basePackages = "com.shop.agent.dispatch")
@EntityScan(basePackages = "com.shop.agent.dispatch")
public class ShoppingSystemDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShoppingSystemDemoApplication.class, args);
    }

}
