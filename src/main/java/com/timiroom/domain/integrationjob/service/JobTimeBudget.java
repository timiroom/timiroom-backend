package com.timiroom.domain.integrationjob.service;
import java.time.*;
/** All provider calls made by a job share the lease's absolute deadline. */
public final class JobTimeBudget implements AutoCloseable {
    private static final ThreadLocal<Instant> DEADLINE=new ThreadLocal<>();
    private JobTimeBudget(Instant deadline) { DEADLINE.set(deadline); }
    public static JobTimeBudget open(Instant deadline) { return new JobTimeBudget(deadline); }
    public static Duration limit(Duration desired) {
        var deadline=DEADLINE.get(); if(deadline==null) return desired;
        var remaining=Duration.between(Instant.now(),deadline);
        if(remaining.isNegative() || remaining.isZero()) throw new IllegalStateException("JOB_TIMEOUT");
        return remaining.compareTo(desired)<0 ? remaining : desired;
    }
    @Override public void close() { DEADLINE.remove(); }
}
