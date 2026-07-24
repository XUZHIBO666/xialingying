package com.xzb.xialingying;

import com.demo.demo.DemoApplication;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@Disabled("Spring AI Alibaba 迁移后需配置 DASHSCOPE_API_KEY 才能加载完整上下文")
@SpringBootTest(classes = DemoApplication.class)
class XialingyingApplicationTests {

	@Test
	void contextLoads() {
	}

}
