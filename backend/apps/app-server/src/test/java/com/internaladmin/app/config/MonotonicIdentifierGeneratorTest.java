package com.internaladmin.app.config;

import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MonotonicIdentifierGeneratorTest {

    @Test
    void wallClockRollbackStillProducesIncreasingUniqueIds() {
        AtomicLong wallClock = new AtomicLong(1_700_000_000_000L);
        AtomicLong monotonicNanos = new AtomicLong(10_000_000_000L);
        MonotonicIdentifierGenerator generator = new MonotonicIdentifierGenerator(
                1L, 1L, wallClock::get, monotonicNanos::get);

        long first = generator.nextId(null).longValue();
        wallClock.addAndGet(-92L);
        monotonicNanos.addAndGet(1_000_000L);
        long second = generator.nextId(null).longValue();

        assertThat(second).isGreaterThan(first);
        assertThat(SequenceTimestamp.timestamp(second)).isGreaterThanOrEqualTo(SequenceTimestamp.timestamp(first));
    }

    @Test
    void sameMillisecondSequenceRemainsUnique() {
        AtomicLong wallClock = new AtomicLong(1_700_000_000_000L);
        AtomicLong timeCalls = new AtomicLong();
        MonotonicIdentifierGenerator generator = new MonotonicIdentifierGenerator(
                1L, 1L, wallClock::get,
                () -> 10_000_000_000L
                        + (timeCalls.getAndIncrement() > 4_096 ? 1_000_000L : 0L));

        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 4_097; i++) {
            ids.add(generator.nextId(null).longValue());
        }

        assertThat(ids).hasSize(4_097);
    }

    @Test
    void springMybatisAssemblyUsesTheProjectGeneratorAndIdWorkerUsesTheSameBean() {
        new ApplicationContextRunner()
                .withUserConfiguration(GeneratorConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    IdentifierGenerator bean = context.getBean(IdentifierGenerator.class);
                    SqlSessionFactory factory = context.getBean(SqlSessionFactory.class);
                    GlobalConfig global = GlobalConfigUtils.getGlobalConfig(factory.getConfiguration());

                    assertThat(global.getIdentifierGenerator()).isSameAs(bean);
                    long id = IdWorker.getId();
                    assertThat(id).isPositive();
                    assertThat((id >>> 12) & 31L).isEqualTo(1L);
                    assertThat((id >>> 17) & 31L).isEqualTo(1L);
                });
    }

    private record SequenceTimestamp() {
        static long timestamp(long id) {
            return (id >>> 22) + 1_288_834_974_657L;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(MybatisPlusAutoConfiguration.class)
    static class GeneratorConfiguration {
        @Bean
        MonotonicIdentifierGenerator monotonicIdentifierGenerator() {
            return new MonotonicIdentifierGenerator(1L, 1L,
                    () -> 1_700_000_000_000L, () -> 10_000_000_000L);
        }

        @Bean
        DataSource dataSource() {
            return mock(DataSource.class);
        }
    }
}
