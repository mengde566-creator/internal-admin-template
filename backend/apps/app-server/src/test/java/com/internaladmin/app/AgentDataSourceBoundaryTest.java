package com.internaladmin.app;

import com.internaladmin.module.agent.config.AgentLiquibaseConfiguration;
import com.internaladmin.module.agent.store.AgentStore;
import com.internaladmin.module.ai.observability.service.JdbcAiObservationRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import liquibase.integration.spring.SpringLiquibase;

import javax.sql.DataSource;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentDataSourceBoundaryTest {

    @Test
    void agentAndObservationUseBusinessDataSourceWhenKnowledgeAddsASecondOne() {
        new ApplicationContextRunner()
                .withUserConfiguration(BoundaryConfiguration.class, AgentStore.class,
                        JdbcAiObservationRecorder.class, AgentLiquibaseConfiguration.class)
                .withPropertyValues("app.ai.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    DataSource business = context.getBean("dataSource", DataSource.class);
                    DataSource knowledge = context.getBean("knowledgeDataSource", DataSource.class);
                    assertThat(business).isNotSameAs(knowledge);
                    assertThat(context.getBean("jdbcTemplate", JdbcTemplate.class).getDataSource())
                            .isSameAs(business);
                    assertThat(context.getBean("knowledgeJdbcTemplate", JdbcTemplate.class).getDataSource())
                            .isSameAs(knowledge);

                    AgentStore store = context.getBean(AgentStore.class);
                    JdbcAiObservationRecorder recorder = context.getBean(JdbcAiObservationRecorder.class);
                    assertThat(readField(store, "jdbc", JdbcTemplate.class).getDataSource()).isSameAs(business);
                    assertThat(readField(recorder, "jdbc", JdbcTemplate.class).getDataSource()).isSameAs(business);
                    assertThat(context.getBean("agentLiquibase", SpringLiquibase.class).getDataSource()).isSameAs(business);
                    assertThat(context.getBean(PlatformTransactionManager.class))
                            .isSameAs(context.getBean("transactionManager"));
                    assertThat(context.getBean("transactionManager"))
                            .isInstanceOf(DataSourceTransactionManager.class);
                    assertThat(context.getBean("knowledgeTransactionManager"))
                            .isNotSameAs(context.getBean("transactionManager"));
                });
    }

    private static <T> T readField(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BoundaryConfiguration {
        @Bean
        DataSource dataSource() {
            return mock(DataSource.class);
        }

        @Bean
        DataSource knowledgeDataSource() {
            return mock(DataSource.class);
        }

        @Bean(name = "jdbcTemplate")
        @Primary
        JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean(name = "knowledgeJdbcTemplate")
        JdbcTemplate knowledgeJdbcTemplate(@Qualifier("knowledgeDataSource") DataSource knowledgeDataSource) {
            return new JdbcTemplate(knowledgeDataSource);
        }

        @Bean(name = "transactionManager")
        @Primary
        PlatformTransactionManager transactionManager(@Qualifier("dataSource") DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean(name = "knowledgeTransactionManager")
        PlatformTransactionManager knowledgeTransactionManager(@Qualifier("knowledgeDataSource") DataSource knowledgeDataSource) {
            return new DataSourceTransactionManager(knowledgeDataSource);
        }

        @Bean
        static BeanPostProcessor keepLiquibaseReadOnly() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessBeforeInitialization(Object bean, String beanName) {
                    if ("agentLiquibase".equals(beanName) && bean instanceof SpringLiquibase liquibase) {
                        liquibase.setShouldRun(false);
                    }
                    return bean;
                }
            };
        }
    }
}
