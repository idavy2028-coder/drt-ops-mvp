package com.idavy.drtops.domain.area;

import java.util.List;

public record VirtualStopImportResult(int createdCount, int skippedCount, List<Issue> issues) {

    public VirtualStopImportResult {
        issues = List.copyOf(issues);
    }

    public record Issue(int rowNumber, String message) {
    }
}
