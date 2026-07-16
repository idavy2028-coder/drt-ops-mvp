package com.idavy.drtops.domain.location;

import java.util.UUID;

public interface IdempotencyKeyLock {

    void acquire(UUID idempotencyKey);
}
