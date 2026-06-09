package com.saasify.user.exception;

import lombok.Getter;

/**
 * Custom runtime exception thrown when a tenant attempts to register more users
 * than allowed under their current plan quota.
 */
@Getter
public class QuotaExceededException extends RuntimeException {

    private final long currentUsage;
    private final long maxLimit;
    private final String planType;
    private final String upgradeUrl;

    public QuotaExceededException(long currentUsage, long maxLimit, String planType, String upgradeUrl) {
        super(String.format("User limit exceeded. Current plan '%s' allows a maximum of %d users, current active count is %d.", planType, maxLimit, currentUsage));
        this.currentUsage = currentUsage;
        this.maxLimit = maxLimit;
        this.planType = planType;
        this.upgradeUrl = upgradeUrl;
    }
}
