package com.inttelgo.mspse.common;

import java.util.List;

public record PageSlice<T>(List<T> items, long total, int skip, int limit) {

    public boolean hasMore() {
        return (long) skip + limit < total;
    }
}
