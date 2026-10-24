package com.example.demo.service;

import com.example.demo.entity.CompanySource;
import com.example.demo.entity.Operation;
import com.example.demo.entity.OperationSource;
import com.example.demo.repository.CompanySourceRepository;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The user's choice of data sources. A company has all sources enabled until the user switches some off
 * ({@code company_sources}); operations of disabled sources are ignored by every calculation:
 * forecast, ML Core / ml-service requests, KPIs, charts, categories and search.
 */
public final class SourceFilter {

    private SourceFilter() {
    }

    public static Set<OperationSource> disabled(CompanySourceRepository repository, String inn) {
        Set<OperationSource> out = EnumSet.noneOf(OperationSource.class);
        List<CompanySource> rows = repository.findByCompanyInnAndEnabledFalse(inn);
        if (rows != null) {
            for (CompanySource row : rows) {
                try {
                    out.add(OperationSource.from(row.getSourceCode()));
                } catch (IllegalArgumentException ignored) {
                    // unknown code in the table: nothing to exclude
                }
            }
        }
        return out;
    }

    public static List<Operation> without(List<Operation> operations, Set<OperationSource> disabled) {
        if (disabled == null || disabled.isEmpty()) {
            return operations;
        }
        return operations.stream().filter(o -> !disabled.contains(o.getSource())).toList();
    }
}
