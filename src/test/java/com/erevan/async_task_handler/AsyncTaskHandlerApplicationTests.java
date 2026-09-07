package com.erevan.async_task_handler;

import com.erevan.async_task_handler.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Контекст поднимается против реальной PostgreSQL: без неё не отработает
 * Liquibase, а ddl-auto: validate не сможет сверить маппинг со схемой.
 */
class AsyncTaskHandlerApplicationTests extends AbstractIntegrationTest {

	@Test
	void contextLoads() {
	}

}
