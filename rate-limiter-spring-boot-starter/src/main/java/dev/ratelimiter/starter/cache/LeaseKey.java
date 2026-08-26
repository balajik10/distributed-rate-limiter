package dev.ratelimiter.starter.cache;

import dev.ratelimiter.core.Algorithm;

public record LeaseKey(
    String policyId, long policyVersion, Algorithm algorithm, String subjectDigest) {}
