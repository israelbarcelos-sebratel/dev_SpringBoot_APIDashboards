package com.sebratel.dashboards.common.stats;

import java.util.List;

public record OutlierResult(
        long iqrOutlierCount,
        long zScoreOutlierCount,
        List<Double> sampleOutliers
) {
}
