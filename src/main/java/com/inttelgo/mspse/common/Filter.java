package com.inttelgo.mspse.common;

import java.util.List;

public record Filter(
        String column,
        String operator,
        Object value,
        String logic,
        List<Filter> filters
) {
}
