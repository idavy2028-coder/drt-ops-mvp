package com.idavy.drtops.integration.algorithm;

public final class AlgorithmUnavailableException extends RuntimeException {

    public static final String ERROR_CODE = "ALGORITHM_UNAVAILABLE";
    public static final String USER_MESSAGE = "算法服务不可用";

    public AlgorithmUnavailableException(Throwable cause) {
        super(USER_MESSAGE, cause);
    }
}
