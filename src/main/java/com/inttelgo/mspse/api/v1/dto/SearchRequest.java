package com.inttelgo.mspse.api.v1.dto;

import com.inttelgo.mspse.common.Filter;
import java.util.List;

public record SearchRequest(List<Filter> filters) {
}
