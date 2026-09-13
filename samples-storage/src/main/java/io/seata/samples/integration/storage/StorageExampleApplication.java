package io.seata.samples.integration.storage;


import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "io.seata.samples.integration.storage")
// 第二个包来自 samples-common：TCC 事务控制表（幂等/空回滚/防悬挂）由三个 RM 服务共用
@MapperScan({"io.seata.samples.integration.storage.mapper",
        "io.seata.samples.integration.common.mapper"})
@EnableDubbo(scanBasePackages = "io.seata.samples.integration.storage")
public class StorageExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(StorageExampleApplication.class, args);
    }

}

