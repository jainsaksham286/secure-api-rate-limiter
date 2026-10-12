package com.example.ratelimiter.model;

import java.util.List;

public record TimeSeriesResponse(int bucketSeconds, List<Point> points) {
    public record Point(long bucketStartEpochSecond, long accepted, long rejected) {
    }
}