package com.internaladmin.app.config;

import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.toolkit.Sequence;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.function.LongSupplier;

/**
 * Project-wide MyBatis-Plus 64-bit ID generator with a monotonic runtime clock.
 *
 * <p>The standard Snowflake layout, worker identity and sequence handling remain
 * owned by MyBatis-Plus. Only the timestamp source is anchored once to wall-clock
 * milliseconds and advanced with {@link System#nanoTime()}, so a later wall-clock
 * correction cannot make IDs move backwards.</p>
 */
@Component
public final class MonotonicIdentifierGenerator extends DefaultIdentifierGenerator {

    public MonotonicIdentifierGenerator() {
        this(new MonotonicSequence(null, System::currentTimeMillis, System::nanoTime));
    }

    MonotonicIdentifierGenerator(long workerId, long datacenterId,
                                 LongSupplier wallClockMillis, LongSupplier monotonicNanos) {
        this(new MonotonicSequence(workerId, datacenterId, wallClockMillis, monotonicNanos));
    }

    private MonotonicIdentifierGenerator(Sequence sequence) {
        super(sequence);
    }

    static final class MonotonicSequence extends Sequence {
        private final long startupWallClockMillis;
        private final long startupMonotonicNanos;
        private final LongSupplier monotonicNanos;

        MonotonicSequence(InetAddress address, LongSupplier wallClockMillis,
                          LongSupplier monotonicNanos) {
            super(address);
            this.startupWallClockMillis = wallClockMillis.getAsLong();
            this.startupMonotonicNanos = monotonicNanos.getAsLong();
            this.monotonicNanos = monotonicNanos;
        }

        MonotonicSequence(long workerId, long datacenterId, LongSupplier wallClockMillis,
                          LongSupplier monotonicNanos) {
            super(workerId, datacenterId);
            this.startupWallClockMillis = wallClockMillis.getAsLong();
            this.startupMonotonicNanos = monotonicNanos.getAsLong();
            this.monotonicNanos = monotonicNanos;
        }

        @Override
        protected long timeGen() {
            long elapsedNanos = monotonicNanos.getAsLong() - startupMonotonicNanos;
            long elapsedMillis = elapsedNanos <= 0 ? 0 : elapsedNanos / 1_000_000L;
            return startupWallClockMillis + elapsedMillis;
        }
    }
}
