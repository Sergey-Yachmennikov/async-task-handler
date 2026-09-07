package com.erevan.async_task_handler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.core.Ordered;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
/*
 * Ретраи из Spring Framework 7 (org.springframework.resilience) — отдельная
 * библиотека spring-retry для этого больше не нужна и с Boot 4 не поставляется.
 *
 * Порядок задан явно и это принципиально. Перехватчик транзакций работает
 * с LOWEST_PRECEDENCE, и значение на единицу меньше ставит ретраи снаружи него.
 * Иначе повтор происходил бы внутри транзакции — а конфликт версий вскрывается
 * только при коммите, то есть уже после того, как повторять стало нечего.
 */
@EnableResilientMethods(order = Ordered.LOWEST_PRECEDENCE - 1)
public class AsyncTaskHandlerApplication {

	public static void main(String[] args) {
		SpringApplication.run(AsyncTaskHandlerApplication.class, args);
	}

}
