package cn.nuaa.jensonxu.fairy.web;

import cn.nuaa.jensonxu.fairy.integration.agent.harness.HarnessLimitProperties;
import cn.nuaa.jensonxu.fairy.integration.agent.harness.risk.HarnessRiskProperties;
import org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@Import(RocketMQAutoConfiguration.class)
@EnableConfigurationProperties({HarnessRiskProperties.class, HarnessLimitProperties.class})
@SpringBootApplication(scanBasePackages = "cn.nuaa.jensonxu")
@MapperScan("cn.nuaa.jensonxu.fairy.common.repository.mysql.mapper")
public class FairyWebApplication {

	public static void main(String[] args) {
		SpringApplication.run(FairyWebApplication.class, args);
	}

}
